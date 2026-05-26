package com.calendaradd.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.URL
import java.nio.charset.Charset

private const val FETCH_TIMEOUT_MS = 10_000
private const val DEFAULT_BUFFER_SIZE = 8192
private const val MAX_RESPONSE_BYTES = 1 * 1024 * 1024 // 1 MB safety cap
private val ALLOWED_SCHEMES = setOf("http", "https")

/**
 * Service for creating link previews.
 *
 * Hardening notes:
 *  - Only http(s) URLs are fetched. file://, javascript:, data:, content://, etc. are rejected
 *    to prevent local file disclosure and other scheme-confusion abuse when URLs originate
 *    from untrusted text (e.g. OCR or shared content).
 *  - Response bodies are capped at MAX_RESPONSE_BYTES so a hostile or accidentally huge page
 *    cannot exhaust memory.
 *  - Connect and read timeouts cap latency.
 */
class LinkPreviewService {
    companion object {
        private const val TAG = "LinkPreviewService"
    }

    suspend fun getLinkPreview(url: String): LinkPreview? = withContext(Dispatchers.IO) {
        val sanitized = sanitizeUrl(url) ?: run {
            AppLog.w(TAG, "Rejected link preview for unsupported url=$url")
            return@withContext null
        }
        try {
            val document = fetchAndParseUrl(sanitized)
            extractLinkInfo(document, sanitized)
        } catch (e: Exception) {
            AppLog.w(TAG, "Failed to build link preview url=$sanitized", e)
            null
        }
    }

    /**
     * Validates that the url is well-formed and uses an allowed scheme.
     * Returns the canonical URL string when accepted, null otherwise.
     */
    private fun sanitizeUrl(url: String): String? {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return null
        return try {
            val parsed = URL(trimmed)
            val scheme = parsed.protocol?.lowercase().orEmpty()
            if (scheme !in ALLOWED_SCHEMES) return null
            if (parsed.host.isNullOrBlank()) return null
            parsed.toString()
        } catch (e: MalformedURLException) {
            null
        }
    }

    private fun fetchAndParseUrl(url: String): LinkPreviewDocument {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = FETCH_TIMEOUT_MS
            readTimeout = FETCH_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Mozilla/5.0")
        }

        return try {
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                throw IllegalStateException("HTTP $responseCode")
            }

            val contentType = connection.contentType.orEmpty()
            if (contentType.isNotBlank() && !contentType.contains("html", ignoreCase = true)) {
                throw IllegalStateException("Unsupported content type $contentType")
            }

            val charset = contentType.extractCharset() ?: Charsets.UTF_8
            val html = connection.inputStream.use { stream ->
                readCapped(stream, MAX_RESPONSE_BYTES).toString(charset)
            }
            parseHtml(html)
        } finally {
            connection.disconnect()
        }
    }

    private fun extractLinkInfo(document: LinkPreviewDocument, originalUrl: String): LinkPreview? {
        return try {
            val title = document.title.ifBlank { extractTitleFromMeta(document).orEmpty() }
            val description = extractDescription(document).orEmpty()
            val imageUrl = (extractOpenGraphImage(document) ?: extractMetaImage(document))
                ?.let { resolveUrl(originalUrl, it) ?: it }

            LinkPreview(
                url = originalUrl,
                title = title,
                description = description,
                imageUrl = imageUrl,
                faviconUrl = extractFavicon(document, originalUrl),
                textSnippet = extractBodySnippet(document)
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun extractTitleFromMeta(document: LinkPreviewDocument): String? {
        return document.meta["property:og:title"]
            ?: document.meta["name:twitter:title"]
    }

    private fun extractDescription(document: LinkPreviewDocument): String? {
        return (document.meta["property:og:description"]
            ?: document.meta["name:description"]
            ?: document.meta["name:twitter:description"])
            ?.takeIf { it.isNotBlank() }
            ?.take(200)
    }

    private fun extractOpenGraphImage(document: LinkPreviewDocument): String? {
        return document.meta["property:og:image"]?.takeIf { it.isNotBlank() }
    }

    private fun extractMetaImage(document: LinkPreviewDocument): String? {
        return document.meta["name:twitter:image"]?.takeIf { it.isNotBlank() }
    }

    private fun extractFavicon(document: LinkPreviewDocument, originalUrl: String): String? {
        val href = document.links.firstOrNull { link ->
            val rel = link.rel.lowercase()
            rel == "icon" || rel == "shortcut icon" || rel == "apple-touch-icon"
        }?.href ?: return null
        return resolveUrl(originalUrl, href)
    }

    private fun extractBodySnippet(document: LinkPreviewDocument): String {
        return document.bodyText
            .take(4000)
    }

    private fun parseHtml(html: String): LinkPreviewDocument {
        val title = TITLE_REGEX.find(html)
            ?.groupValues
            ?.getOrNull(1)
            ?.toPlainText()
            .orEmpty()

        val meta = META_TAG_REGEX.findAll(html)
            .mapNotNull { match ->
                val attributes = parseAttributes(match.value)
                val content = attributes["content"]?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val key = attributes["property"]?.let { "property:${it.lowercase()}" }
                    ?: attributes["name"]?.let { "name:${it.lowercase()}" }
                    ?: return@mapNotNull null
                key to content.decodeHtmlEntities()
            }
            .toMap()

        val links = LINK_TAG_REGEX.findAll(html)
            .mapNotNull { match ->
                val attributes = parseAttributes(match.value)
                val rel = attributes["rel"] ?: return@mapNotNull null
                val href = attributes["href"] ?: return@mapNotNull null
                LinkTag(rel = rel, href = href)
            }
            .toList()

        val bodyHtml = BODY_REGEX.find(html)?.groupValues?.getOrNull(1) ?: html
        val bodyText = bodyHtml
            .replace(SCRIPT_STYLE_REGEX, " ")
            .toPlainText()

        return LinkPreviewDocument(
            title = title,
            meta = meta,
            links = links,
            bodyText = bodyText
        )
    }

    private fun parseAttributes(tag: String): Map<String, String> {
        return ATTRIBUTE_REGEX.findAll(tag)
            .associate { match ->
                val value = match.groups[3]?.value
                    ?: match.groups[4]?.value
                    ?: match.groups[5]?.value
                    ?: ""
                match.groupValues[1].lowercase() to value.decodeHtmlEntities()
            }
    }

    private fun readCapped(inputStream: InputStream, maxBytes: Int): ByteArray {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        val output = ByteArrayOutputStream()
        var totalBytes = 0
        while (true) {
            val bytesRead = inputStream.read(buffer)
            if (bytesRead == -1) break
            val remaining = maxBytes - totalBytes
            if (remaining <= 0) break
            val bytesToWrite = minOf(bytesRead, remaining)
            output.write(buffer, 0, bytesToWrite)
            totalBytes += bytesToWrite
        }
        return output.toByteArray()
    }

    private fun resolveUrl(baseUrl: String, candidate: String): String? {
        return try {
            URL(URL(baseUrl), candidate).toString()
        } catch (e: MalformedURLException) {
            null
        }
    }

    private fun String.extractCharset(): Charset? {
        return Regex("charset=([^;]+)", RegexOption.IGNORE_CASE)
            .find(this)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim('"', '\'', ' ')
            ?.let { runCatching { Charset.forName(it) }.getOrNull() }
    }

    private fun String.toPlainText(): String {
        return replace(TAG_REGEX, " ")
            .decodeHtmlEntities()
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun String.decodeHtmlEntities(): String {
        return replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
    }
}

private val TITLE_REGEX = Regex("(?is)<title[^>]*>(.*?)</title>")
private val BODY_REGEX = Regex("(?is)<body[^>]*>(.*?)</body>")
private val META_TAG_REGEX = Regex("(?is)<meta\\b[^>]*>")
private val LINK_TAG_REGEX = Regex("(?is)<link\\b[^>]*>")
private val SCRIPT_STYLE_REGEX = Regex("(?is)<(script|style)\\b[^>]*>.*?</\\1>")
private val TAG_REGEX = Regex("(?is)<[^>]+>")
private val ATTRIBUTE_REGEX = Regex("""([a-zA-Z_:][-a-zA-Z0-9_:.]*)\s*=\s*("([^"]*)"|'([^']*)'|([^\s"'>]+))""")

private data class LinkPreviewDocument(
    val title: String,
    val meta: Map<String, String>,
    val links: List<LinkTag>,
    val bodyText: String
)

private data class LinkTag(
    val rel: String,
    val href: String
)

data class LinkPreview(
    val url: String,
    val title: String,
    val description: String,
    val imageUrl: String?,
    val faviconUrl: String?,
    val textSnippet: String = ""
)

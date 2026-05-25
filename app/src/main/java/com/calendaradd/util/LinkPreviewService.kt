package com.calendaradd.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.MalformedURLException
import java.net.URL

private const val FETCH_TIMEOUT_MS = 10_000
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

    private fun fetchAndParseUrl(url: String): Document {
        return Jsoup.connect(url)
            .timeout(FETCH_TIMEOUT_MS)
            .maxBodySize(MAX_RESPONSE_BYTES)
            .userAgent("Mozilla/5.0")
            .followRedirects(true)
            .ignoreContentType(false)
            .get()
    }

    private fun extractLinkInfo(document: Document, originalUrl: String): LinkPreview? {
        return try {
            val title = document.title()
                .ifBlank { extractTitleFromMeta(document).orEmpty() }
            val description = extractDescription(document).orEmpty()
            val imageUrl = extractOpenGraphImage(document) ?: extractMetaImage(document)

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

    private fun extractTitleFromMeta(document: Document): String? {
        return document.selectFirst("meta[property=og:title], meta[name=twitter:title]")
            ?.attr("content")
            ?.takeIf { it.isNotBlank() }
    }

    private fun extractDescription(document: Document): String? {
        return document.selectFirst("meta[property=og:description], meta[name=description], meta[name=twitter:description]")
            ?.attr("content")
            ?.takeIf { it.isNotBlank() }
            ?.take(200)
    }

    private fun extractOpenGraphImage(document: Document): String? {
        val imageTag = document.selectFirst("meta[property=og:image]") ?: return null
        return imageTag.absUrl("content")
            .ifBlank { imageTag.attr("content") }
            .takeIf { it.isNotBlank() }
    }

    private fun extractMetaImage(document: Document): String? {
        return document.selectFirst("meta[name=twitter:image]")
            ?.attr("content")
            ?.takeIf { it.isNotBlank() }
    }

    private fun extractFavicon(document: Document, originalUrl: String): String? {
        val linkTag = document.selectFirst("link[rel=icon], link[rel='shortcut icon'], link[rel='apple-touch-icon']") ?: return null
        val href = linkTag.absUrl("href").ifBlank { linkTag.attr("href") }
        return href.takeIf { it.isNotBlank() }?.let {
            if (it.startsWith("http")) it else URL(URL(originalUrl), it).toString()
        }
    }

    private fun extractBodySnippet(document: Document): String {
        return document.body()
            ?.text()
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.take(4000)
            .orEmpty()
    }
}

data class LinkPreview(
    val url: String,
    val title: String,
    val description: String,
    val imageUrl: String?,
    val faviconUrl: String?,
    val textSnippet: String = ""
)
package com.calendaradd.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URL

/**
 * Service for creating link previews.
 */
class LinkPreviewService {

    suspend fun getLinkPreview(url: String): LinkPreview? = withContext(Dispatchers.IO) {
        try {
            val document = fetchAndParseUrl(url)
            extractLinkInfo(document, url)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private suspend fun fetchAndParseUrl(url: String): Document {
        return withContext(Dispatchers.IO) {
            val document = Jsoup.connect(url)
                .timeout(10000) // 10s timeout
                .userAgent("Mozilla/5.0")
                .get()
            document
        }
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

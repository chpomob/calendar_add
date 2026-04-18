package com.calendaradd.util

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URL

/**
 * Service for creating link previews.
 */
class LinkPreviewService(
    private val context: Context
) {

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
            val title = document.title() ?: extractTitleFromMeta(document) ?: ""
            val description = extractDescription(document) ?: ""
            val imageUrl = extractOpenGraphImage(document) ?: extractMetaImage(document)

            LinkPreview(
                url = originalUrl,
                title = title,
                description = description,
                imageUrl = imageUrl,
                faviconUrl = extractFavicon(document)
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun extractTitleFromMeta(document: Document): String? {
        val metaTag = document.selectFirst("meta[property=og:title]") ?: return null
        return metaTag.attr("content") ?: null
    }

    private fun extractDescription(document: Document): String? {
        val descTag = document.selectFirst("meta[property=og:description]") ?: return null
        val content = descTag.attr("content") ?: return null
        return content.take(200)
    }

    private fun extractOpenGraphImage(document: Document): String? {
        val imageTag = document.selectFirst("meta[property=og:image]") ?: return null
        return imageTag.attr("content")?.let { Uri.parse(it).toString() }
    }

    private fun extractMetaImage(document: Document): String? {
        val imageTag = document.selectFirst("meta[name=twitter:image]") ?: return null
        return imageTag.attr("content")
    }

    private fun extractFavicon(document: Document): String? {
        val linkTag = document.selectFirst("link[rel=icon]") ?: return null
        return linkTag.attr("href")?.let {
            context.resources.getString(R.string.base_url).let { base ->
                if (it.startsWith("http")) it else "$base$it"
            }
        }
    }
}

data class LinkPreview(
    val url: String,
    val title: String,
    val description: String,
    val imageUrl: String?,
    val faviconUrl: String?
)

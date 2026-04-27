package com.calendaradd.service

import com.calendaradd.usecase.InputContext
import com.calendaradd.util.AppLog
import com.calendaradd.util.LinkPreview
import com.calendaradd.util.LinkPreviewService
import android.net.Uri
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup

/**
 * Optional late web confirmation for image extraction.
 *
 * This stays off by default and only runs after local extraction. It can use a
 * URL from OCR when present, or a public search query built from the extracted
 * event evidence.
 */
class WebVerificationService(
    private val linkPreviewService: LinkPreviewService = LinkPreviewService(),
    private val webSearchClient: WebSearchClient = DuckDuckGoWebSearchClient()
) {
    companion object {
        private const val TAG = "WebVerificationService"
    }

    suspend fun refineImageEvents(
        currentEvents: List<EventExtraction>,
        ocrText: String?,
        context: InputContext
    ): List<EventExtraction> = withContext(Dispatchers.IO) {
        val preview = resolvePreview(currentEvents, ocrText) ?: return@withContext currentEvents
        refineWithPreview(currentEvents, preview, context)
    }

    private suspend fun resolvePreview(
        currentEvents: List<EventExtraction>,
        ocrText: String?
    ): LinkPreview? {
        extractFirstUrl(ocrText)?.let { url ->
            linkPreviewService.getLinkPreview(url)?.let { return it }
        }

        val searchQuery = buildSearchQuery(currentEvents, ocrText) ?: return null
        AppLog.i(TAG, "Trying public web lookup query='${searchQuery.take(120)}'")
        val resultUrl = webSearchClient.findFirstResultUrl(searchQuery) ?: return null
        return linkPreviewService.getLinkPreview(resultUrl)
    }

    private fun refineWithPreview(
        currentEvents: List<EventExtraction>,
        preview: LinkPreview,
        context: InputContext
    ): List<EventExtraction> {
        val titleHint = preview.title.takeIf { it.isNotBlank() }
        val descriptionHint = preview.description.takeIf { it.isNotBlank() }
        if (titleHint == null && descriptionHint == null) return currentEvents

        return currentEvents.map { event ->
            val title = chooseBetterTitle(event.title, titleHint)
            val description = chooseBetterDescription(event.description, descriptionHint)
            val location = chooseBetterLocation(event.location, descriptionHint)
            event.copy(
                title = title,
                description = description,
                location = location
            )
        }
    }

    private fun buildSearchQuery(
        currentEvents: List<EventExtraction>,
        ocrText: String?
    ): String? {
        val primaryEvent = currentEvents.firstOrNull() ?: return null
        val parts = linkedSetOf<String>()

        fun addPart(value: String?) {
            val normalized = value
                ?.replace(Regex("\\s+"), " ")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: return
            if (looksGeneric(normalized)) return
            parts += normalized
        }

        addPart(primaryEvent.title)
        addPart(primaryEvent.location)
        addPart(primaryEvent.description)
        addPart(extractDateHint(primaryEvent.startTime))

        val ocrHints = extractSearchHints(ocrText)
        if (parts.size < 2) {
            ocrHints.forEach { addPart(it) }
        } else {
            ocrHints.take(1).forEach { addPart(it) }
        }

        return parts.joinToString(" ").takeIf { it.isNotBlank() }
    }

    private fun chooseBetterTitle(current: String, hint: String?): String {
        val cleanedCurrent = current.normalizeKey()
        if (hint.isNullOrBlank()) return current
        val cleanedHint = hint.normalizeKey()
        if (current.isBlank()) return hint
        if (looksGeneric(current)) return hint
        if (cleanedHint.contains(cleanedCurrent) && hint.length >= current.length + 8) return hint
        if (cleanedCurrent.isNotBlank() && cleanedHint == cleanedCurrent) return hint
        return current
    }

    private fun chooseBetterDescription(current: String, hint: String?): String {
        if (hint.isNullOrBlank()) return current
        if (current.isBlank()) return hint
        if (looksGeneric(current) && hint.length >= current.length) return hint
        return current
    }

    private fun chooseBetterLocation(current: String, hintDescription: String?): String {
        if (current.isNotBlank()) return current
        val extracted = extractLocationCandidate(hintDescription)
        return extracted ?: current
    }

    private fun extractLocationCandidate(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val normalized = text.replace(Regex("\\s+"), " ").trim()
        val locationPatterns = listOf(
            Regex("(?i)\\blieu\\s*:\\s*([^\\.]+)"),
            Regex("(?i)\\blocation\\s*:\\s*([^\\.]+)"),
            Regex("(?i)\\b(online|virtual|zoom|teams)\\b"),
            Regex("(?i)\\b(room\\s*\\d+[A-Za-z0-9\\-]*)\\b"),
            Regex("(?i)\\b([0-9]{1,5}\\s+[A-Za-z0-9.'\\- ]+(street|st\\.|avenue|ave\\.|road|rd\\.|boulevard|blvd\\.|drive|dr\\.|hall|center|centre|building|campus))\\b")
        )
        for (pattern in locationPatterns) {
            val match = pattern.find(normalized) ?: continue
            val candidate = match.groupValues.last().takeIf { it.isNotBlank() } ?: match.value
            return candidate.trim().trimEnd('.', ',', ';')
        }
        return null
    }

    private fun looksGeneric(value: String): Boolean {
        val normalized = value.normalizeKey()
        return normalized in setOf(
            "event",
            "event flyer",
            "flyer",
            "poster",
            "workshop",
            "meeting",
            "seminar",
            "conference",
            "schedule"
        ) || normalized.length <= 4
    }

    private fun extractFirstUrl(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val regex = Regex("""https?://[^\s<>()]+|www\.[^\s<>()]+""", RegexOption.IGNORE_CASE)
        return regex.find(text)?.value?.let {
            if (it.startsWith("http")) it else "https://$it"
        }
    }

    private fun extractSearchHints(text: String?): List<String> {
        if (text.isNullOrBlank()) return emptyList()
        return text.lineSequence()
            .map { it.normalizeVisibleText() }
            .filter { it.isNotBlank() }
            .filterNot { looksLikeNoiseLine(it) }
            .take(3)
            .toList()
    }

    private fun extractDateHint(startTime: String?): String? {
        if (startTime.isNullOrBlank() || startTime.length < 10) return null
        val candidate = startTime.substring(0, 10)
        return candidate.takeIf { it.matches(Regex("""\d{4}-\d{2}-\d{2}""")) }
    }

    private fun looksLikeNoiseLine(value: String): Boolean {
        return value.equals("event flyer", ignoreCase = true) ||
            value.equals("flyer", ignoreCase = true) ||
            value.equals("poster", ignoreCase = true) ||
            value.equals("schedule", ignoreCase = true) ||
            value.matches(Regex("(?i)^\\d{1,2}(:\\d{2})?\\s*[ap]m\\s*[-–]\\s*\\d{1,2}(:\\d{2})?\\s*[ap]m$")) ||
            value.matches(Regex("(?i)^(mon|tues|wednes|thurs|fri|satur|sun)day,?\\s+.*$"))
    }

    private fun String.normalizeKey(): String {
        return trim().lowercase().replace(Regex("\\s+"), " ")
    }

    private fun String.normalizeVisibleText(): String {
        return trim()
            .replace('’', '\'')
            .replace('“', '"')
            .replace('”', '"')
            .replace('–', '-')
            .replace('—', '-')
            .replace(Regex("\\s+"), " ")
    }
}

interface WebSearchClient {
    suspend fun findFirstResultUrl(query: String): String?
}

class DuckDuckGoWebSearchClient : WebSearchClient {
    override suspend fun findFirstResultUrl(query: String): String? = withContext(Dispatchers.IO) {
        try {
            val searchUrl = "https://html.duckduckgo.com/html/?q=${Uri.encode(query)}"
            val document = Jsoup.connect(searchUrl)
                .timeout(10000)
                .userAgent("Mozilla/5.0")
                .get()
            val link = document.selectFirst("a.result__a") ?: document.selectFirst("a[href*='uddg=']")
            resolveSearchResultUrl(link?.attr("href"))
        } catch (e: Exception) {
            AppLog.w("WebVerificationService", "Public web lookup failed: ${e.message}")
            null
        }
    }

    private fun resolveSearchResultUrl(href: String?): String? {
        if (href.isNullOrBlank()) return null
        if (href.startsWith("http")) return href
        val redirectMatch = Regex("[?&]uddg=([^&]+)").find(href)
        if (redirectMatch != null) {
            return Uri.decode(redirectMatch.groupValues[1])
        }
        return if (href.startsWith("//")) "https:$href" else "https://duckduckgo.com$href"
    }
}

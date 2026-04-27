package com.calendaradd.service

import com.calendaradd.usecase.InputContext
import com.calendaradd.util.AppLog
import com.calendaradd.util.LinkPreview
import com.calendaradd.util.LinkPreviewService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
        val previews = resolvePreviews(currentEvents, ocrText)
        if (previews.isEmpty()) return@withContext currentEvents
        refineWithPreviews(currentEvents, previews, context)
    }

    private suspend fun resolvePreviews(
        currentEvents: List<EventExtraction>,
        ocrText: String?
    ): List<LinkPreview> {
        val primaryPreview = resolvePrimaryPreview(currentEvents, ocrText) ?: return emptyList()
        val previews = mutableListOf(primaryPreview)
        resolveVenuePreview(currentEvents, ocrText, primaryPreview)?.let { venuePreview ->
            if (venuePreview.url != primaryPreview.url) previews += venuePreview
        }
        return previews
    }

    private suspend fun resolvePrimaryPreview(
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

    private suspend fun resolveVenuePreview(
        currentEvents: List<EventExtraction>,
        ocrText: String?,
        primaryPreview: LinkPreview
    ): LinkPreview? {
        val venue = extractVenueLookupName(currentEvents.firstOrNull()?.location) ?: return null
        if (hasStreetAddress(venue)) return null
        val combinedEvidence = listOf(ocrText, primaryPreview.title, primaryPreview.description, primaryPreview.textSnippet)
            .filterNotNull()
            .joinToString(" ")
        val primaryAddress = extractLocationCandidate(combinedEvidence)
        if (primaryAddress != null && hasStreetAddress(primaryAddress)) return null
        val cityHint = extractCityHint(combinedEvidence) ?: "France"
        val query = "$venue $cityHint adresse"
        AppLog.i(TAG, "Trying venue address lookup query='${query.take(120)}'")
        val resultUrl = webSearchClient.findFirstResultUrl(query) ?: return null
        return linkPreviewService.getLinkPreview(resultUrl)
    }

    private fun refineWithPreviews(
        currentEvents: List<EventExtraction>,
        previews: List<LinkPreview>,
        context: InputContext
    ): List<EventExtraction> {
        val titleHint = previews.firstNotNullOfOrNull { preview ->
            preview.title.takeIf { it.isNotBlank() }
        }
        val descriptionHint = previews.firstNotNullOfOrNull { preview ->
            preview.description.takeIf { it.isNotBlank() }
                ?: extractDescriptionCandidate(preview.textSnippet)
        }
        val locationHintText = previews.joinToString(" ") { preview ->
            listOf(preview.title, preview.description, preview.textSnippet)
                .filter { it.isNotBlank() }
                .joinToString(" ")
        }
        if (titleHint == null && descriptionHint == null && locationHintText.isBlank()) return currentEvents

        return currentEvents.map { event ->
            val title = chooseBetterTitle(event.title, titleHint)
            val description = chooseBetterDescription(event.description, descriptionHint)
            val location = chooseBetterLocation(event.location, locationHintText)
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
        addPart(extractDistinctiveTerms(primaryEvent.description, 6))
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
        val extracted = extractLocationCandidate(hintDescription)
        if (current.isBlank()) return extracted ?: current
        if (extracted == null || hasStreetAddress(current) || !hasStreetAddress(extracted)) return current
        if (current.normalizeKey() in extracted.normalizeKey()) return extracted
        return if (looksVenueOnly(current)) "$current, $extracted" else current
    }

    private fun extractLocationCandidate(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val normalized = text.replace(Regex("\\s+"), " ").trim()
        val locationPatterns = listOf(
            Regex("(?i)\\b(?:adresse|acc[èe]s|lieu|location|où)\\s*[:\\-–]\\s*([^\\.]+)"),
            Regex("(?i)\\b([0-9]{1,5}\\s*(?:bis|ter)?\\s+(?:rue|avenue|av\\.?|boulevard|bd\\.?|place|impasse|route|chemin|quai|all[ée]e|cours)\\s+[A-Za-zÀ-ÖØ-öø-ÿ0-9'’ .\\-]{2,80}(?:,?\\s*[0-9]{5}\\s+[A-Za-zÀ-ÖØ-öø-ÿ'’ \\-]{2,60})?)\\b"),
            Regex("(?i)\\b(online|virtual|zoom|teams)\\b"),
            Regex("(?i)\\b(room\\s*\\d+[A-Za-z0-9\\-]*)\\b"),
            Regex("(?i)\\b([0-9]{1,5}\\s+[A-Za-z0-9.'\\- ]+(street|st\\.|avenue|ave\\.|road|rd\\.|boulevard|blvd\\.|drive|dr\\.|hall|center|centre|building|campus))\\b")
        )
        for (pattern in locationPatterns) {
            val match = pattern.find(normalized) ?: continue
            val candidate = match.groupValues.getOrNull(1).takeIf { !it.isNullOrBlank() } ?: match.value
            return cleanLocationCandidate(candidate)
        }
        return null
    }

    private fun cleanLocationCandidate(value: String): String {
        return value
            .replace(Regex("\\s+"), " ")
            .trim()
            .trimEnd('.', ',', ';', '-', '–')
            .replace(Regex("(?i)\\s+(accès voiture|rer|métro|metro|bus|parking)\\b.*$"), "")
            .trim()
            .trimEnd('.', ',', ';', '-', '–')
    }

    private fun extractDescriptionCandidate(text: String?): String? {
        if (text.isNullOrBlank()) return null
        return text.replace(Regex("\\s+"), " ")
            .split(Regex("(?<=[.!?])\\s+"))
            .map { it.trim() }
            .firstOrNull { sentence ->
                sentence.length in 80..280 &&
                    !sentence.contains("Newsletter", ignoreCase = true) &&
                    !sentence.contains("Tous droit réservés", ignoreCase = true)
            }
    }

    private fun extractVenueLookupName(currentLocation: String?): String? {
        val normalized = currentLocation
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.trimEnd(',', ';', '.')
            ?: return null
        if (normalized.isBlank() || looksGeneric(normalized) || hasStreetAddress(normalized)) return null
        if (!looksVenueOnly(normalized)) return null
        return normalized
    }

    private fun extractCityHint(value: String?): String? {
        if (value.isNullOrBlank()) return null
        val postalCity = Regex("\\b[0-9]{5}\\s+([A-Za-zÀ-ÖØ-öø-ÿ'’ \\-]{2,60})\\b").find(value)
        if (postalCity != null) return postalCity.groupValues[1].trim()
        val knownCity = Regex("(?i)\\b(Vitry-sur-Seine|Paris|Strasbourg|San Francisco|Los Angeles|New York)\\b").find(value)
        return knownCity?.value
    }

    private fun hasStreetAddress(value: String): Boolean {
        return value.contains(Regex("(?i)\\b[0-9]{1,5}\\s*(?:bis|ter)?\\s+(?:rue|avenue|av\\.?|boulevard|bd\\.?|place|impasse|route|chemin|quai|all[ée]e|cours|street|st\\.|road|rd\\.|drive|dr\\.)\\b")) ||
            value.contains(Regex("\\b[0-9]{5}\\b"))
    }

    private fun looksVenueOnly(value: String): Boolean {
        val normalized = value.normalizeKey()
        if (normalized.contains("@") || normalized.contains("http")) return false
        if (hasStreetAddress(value)) return false
        return normalized.split(" ").size <= 6
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

    private fun extractDistinctiveTerms(value: String?, maxTerms: Int): String? {
        if (value.isNullOrBlank()) return null
        return value
            .normalizeSearchTokens()
            .filterNot { it in SEARCH_STOP_WORDS }
            .distinct()
            .take(maxTerms)
            .joinToString(" ")
            .takeIf { it.isNotBlank() }
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

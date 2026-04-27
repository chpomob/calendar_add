package com.calendaradd.service

import android.graphics.Bitmap
import com.calendaradd.usecase.InputContext
import com.calendaradd.usecase.PreferencesManager
import com.calendaradd.util.AppLog
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.time.LocalDate
import java.time.LocalTime
import java.time.Instant
import java.time.ZonedDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Service for orchestrating the AI analysis pipeline.
 */
class TextAnalysisService(
    private val gemmaLlmService: EventJsonExtractor,
    private val preferencesManager: PreferencesManager? = null,
    private val ocrService: OcrService? = null,
    private val webVerificationService: WebVerificationService? = null
) {
    companion object {
        private const val TAG = "TextAnalysisService"
        private val promptDateTimeFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME
        private const val HEAVY_IMAGE_STAGE_1 = "Heavy mode stage 1/3: multimodal image observations"
        private const val HEAVY_IMAGE_STAGE_2 = "Heavy mode stage 2/3: temporal normalization"
        private const val HEAVY_IMAGE_STAGE_3 = "Heavy mode stage 3/3: final event composition"
        private const val HEAVY_AUDIO_STAGE_1 = "Heavy mode stage 1/3: multimodal audio observations"
        private const val HEAVY_AUDIO_STAGE_2 = "Heavy mode stage 2/3: temporal normalization"
        private const val HEAVY_AUDIO_STAGE_3 = "Heavy mode stage 3/3: final event composition"
    }

    private val debugSnapshotLock = Any()
    private var lastDebugSnapshot: AnalysisDebugSnapshot? = null

    /**
     * Analyzes text input and extracts event information.
     */
    suspend fun analyzeText(
        input: String,
        context: InputContext = InputContext()
    ): List<EventExtraction> = withContext(Dispatchers.IO) {
        clearDebugSnapshot()
        AppLog.i(TAG, "[${context.traceId}] Analyzing text chars=${input.length}")
        val promptText = buildString {
            append(buildReferencePrompt(context))
            appendLine("Input type: text")
            appendLine("Extract calendar events from this user text.")
            append(buildFinalEventJsonInstructions())
            appendLine("User input: $input")
        }
        val jsonString = gemmaLlmService.extractEventJson(text = promptText)
        parseJsonToExtractions(jsonString, context.traceId)
    }

    /**
     * Analyzes image for event information.
     */
    suspend fun analyzeImage(
        bitmap: Bitmap,
        context: InputContext = InputContext()
    ): List<EventExtraction> = withContext(Dispatchers.IO) {
        clearDebugSnapshot()
        AppLog.i(
            TAG,
            "[${context.traceId}] Analyzing image bitmap=${bitmap.width}x${bitmap.height} config=${bitmap.config ?: "null"}"
        )
        if (isHeavyAnalysisEnabled()) {
            AppLog.i(TAG, "[${context.traceId}] Heavy analysis enabled for image input")
            return@withContext analyzeImageHeavy(bitmap, context)
        }
        val promptText = buildString {
            append(buildReferencePrompt(context))
            appendLine("Input type: image")
            appendLine("Extract calendar events from this flyer, poster, screenshot, or event notice.")
            appendLine("Use the exact visible event title, date, time, and location when they are present.")
            appendLine("Treat visible virtual-location text such as Online, Virtual, Zoom, or Teams as a real location value.")
            appendLine("Do not guess missing details.")
            appendLine("If the image contains a schedule table or a flyer series with multiple explicit date/time rows, return one event per row when the rows clearly describe separate occurrences.")
            appendLine("Do not merge distinct schedule rows into one generic event just because they share the same date, venue, or series title.")
            appendLine("For schedule rows, use the row's own visible title as the event title and do not prefix it with the flyer or series title.")
            appendLine("The flyer banner title is not the event title for individual schedule rows.")
            appendLine("Copy the row title exactly as visible and do not add extra words, adjectives, or paraphrases.")
            appendLine("When copying locations, preserve spaces, punctuation, and parentheses as visible instead of compressing them.")
            appendLine("If the image contains relative date or time phrases, resolve them using the reference local datetime above.")
            append(buildFinalEventJsonInstructions())
        }
        val jsonString = gemmaLlmService.extractEventJson(text = promptText, image = bitmap)
        parseJsonToExtractions(jsonString, context.traceId)
    }

    /**
     * Analyzes audio for event information.
     */
    suspend fun analyzeAudio(
        audioData: ByteArray,
        context: InputContext = InputContext()
    ): List<EventExtraction> = withContext(Dispatchers.IO) {
        clearDebugSnapshot()
        AppLog.i(TAG, "[${context.traceId}] Analyzing audio bytes=${audioData.size}")
        if (isHeavyAnalysisEnabled()) {
            AppLog.i(TAG, "[${context.traceId}] Heavy analysis enabled for audio input")
            return@withContext analyzeAudioHeavy(audioData, context)
        }
        val promptText = buildString {
            append(buildReferencePrompt(context))
            appendLine("Input type: audio")
            appendLine("Extract the intended calendar event from this audio only when the speaker clearly proposes, confirms, schedules, or reschedules a concrete calendar item.")
            appendLine("Ignore incidental mentions of time, generic future statements, or status updates that are not actual calendar commitments.")
            appendLine("If the audio only mentions a time or date without naming an event, do not invent a generic title like Meeting.")
            appendLine("The audio may contain filler words, background noise, repeated fragments, and ASR mistakes.")
            appendLine("Use the intended meaning of the speech, not the noisy transcription artifacts.")
            appendLine("If the speaker says relative dates or times, resolve them using the reference local datetime above.")
            append(buildFinalEventJsonInstructions())
        }
        val jsonString = gemmaLlmService.extractEventJson(text = promptText, audio = audioData)
        parseJsonToExtractions(jsonString, context.traceId)
    }

    private suspend fun analyzeImageHeavy(
        bitmap: Bitmap,
        context: InputContext
    ): List<EventExtraction> {
        val ocrText = extractImageText(bitmap)
        val observations = gemmaLlmService.extractEventJson(
            text = buildHeavyImageObservationPrompt(context, ocrText),
            image = bitmap
        )
        if (observations.isNullOrBlank()) {
            recordStageFailure(context.traceId, HEAVY_IMAGE_STAGE_1, "The model returned no image observations.", observations)
            return emptyList()
        }

        val temporalResolution = gemmaLlmService.extractEventJson(
            text = buildTemporalResolutionPrompt(
                context = context,
                stageLabel = HEAVY_IMAGE_STAGE_2,
                sourceType = "image",
                observations = observations
            )
        )
        if (temporalResolution.isNullOrBlank()) {
            recordStageFailure(context.traceId, HEAVY_IMAGE_STAGE_2, "The model returned no temporal resolution for the image.", observations)
            return emptyList()
        }

        val finalJson = gemmaLlmService.extractEventJson(
            text = buildFinalCompositionPrompt(
                context = context,
                stageLabel = HEAVY_IMAGE_STAGE_3,
                sourceType = "image",
                observations = observations,
                temporalResolution = temporalResolution
            )
        )

        val results = parseJsonToExtractions(finalJson, context.traceId)
        if (results.isEmpty()) {
            recordStageFailure(
                traceId = context.traceId,
                stageLabel = HEAVY_IMAGE_STAGE_3,
                issue = "Heavy image mode completed but did not produce usable final events.",
                rawResponse = combineHeavyModeResponses(observations, temporalResolution, finalJson)
            )
        }
        val refinedResults = refineHeavyImageResults(results, ocrText, context)
        return maybeApplyWebVerification(refinedResults, ocrText, context)
    }

    private suspend fun analyzeAudioHeavy(
        audioData: ByteArray,
        context: InputContext
    ): List<EventExtraction> {
        val observations = gemmaLlmService.extractEventJson(
            text = buildHeavyAudioObservationPrompt(context),
            audio = audioData
        )
        if (observations.isNullOrBlank()) {
            recordStageFailure(context.traceId, HEAVY_AUDIO_STAGE_1, "The model returned no audio observations.", observations)
            return emptyList()
        }

        val temporalResolution = gemmaLlmService.extractEventJson(
            text = buildTemporalResolutionPrompt(
                context = context,
                stageLabel = HEAVY_AUDIO_STAGE_2,
                sourceType = "audio",
                observations = observations
            )
        )
        if (temporalResolution.isNullOrBlank()) {
            recordStageFailure(context.traceId, HEAVY_AUDIO_STAGE_2, "The model returned no temporal resolution for the audio.", observations)
            return emptyList()
        }

        val finalJson = gemmaLlmService.extractEventJson(
            text = buildFinalCompositionPrompt(
                context = context,
                stageLabel = HEAVY_AUDIO_STAGE_3,
                sourceType = "audio",
                observations = observations,
                temporalResolution = temporalResolution
            )
        )

        val results = parseJsonToExtractions(finalJson, context.traceId)
        if (results.isEmpty()) {
            recordStageFailure(
                traceId = context.traceId,
                stageLabel = HEAVY_AUDIO_STAGE_3,
                issue = "Heavy audio mode completed but did not produce usable final events.",
                rawResponse = combineHeavyModeResponses(observations, temporalResolution, finalJson)
            )
        }
        return results
    }

    fun consumeLastDebugSnapshot(): AnalysisDebugSnapshot? {
        return synchronized(debugSnapshotLock) {
            lastDebugSnapshot.also { lastDebugSnapshot = null }
        }
    }

    private fun isHeavyAnalysisEnabled(): Boolean = preferencesManager?.isHeavyAnalysisEnabled == true

    private fun buildReferencePrompt(context: InputContext): String {
        val zonedReference = Instant.ofEpochMilli(context.timestamp).atZone(ZoneId.of(context.timezone))
        return buildString {
            appendLine("Reference local datetime: ${promptDateTimeFormatter.format(zonedReference)}")
            appendLine("Reference timezone: ${context.timezone}")
            appendLine("Reference day of week: ${zonedReference.dayOfWeek}")
            appendLine("User language: ${context.language}")
            appendLine(
                "Resolve relative date and time phrases such as today, tomorrow, tonight, this evening, " +
                    "next Friday, this weekend, and in two days against the reference local datetime."
            )
            appendLine("Return absolute ISO-8601 values with timezone offsets in startTime and endTime. Never leave relative words in the JSON output.")
        }
    }

    private fun buildFinalEventJsonInstructions(): String {
        return buildString {
            appendLine("If the input contains multiple fragments about the same event, merge them into one event.")
            appendLine("If the input contains multiple distinct events, return them all.")
            appendLine("Return ONLY valid JSON in this exact shape: { \"events\": [ { \"title\": \"\", \"description\": \"\", \"startTime\": \"ISO-8601 with timezone offset\", \"endTime\": \"ISO-8601 with timezone offset\", \"location\": \"\", \"attendees\": [] } ] }")
            appendLine("If there is only one event, still return it inside the events array.")
            appendLine("If there are no events, return { \"events\": [] }.")
        }
    }

    private fun buildHeavyImageObservationPrompt(context: InputContext): String {
        return buildString {
            append(buildReferencePrompt(context))
            appendLine(HEAVY_IMAGE_STAGE_1)
            appendLine("Inspect the image conservatively and capture raw event evidence before normalization.")
            appendLine("Treat the image as a flyer, poster, screenshot, or event notice.")
            appendLine("Prefer exact visible event title, date, time, and location text.")
            appendLine("Treat visible virtual-location text such as Online, Virtual, Zoom, or Teams as a real location value.")
            appendLine("Do not invent details that are not visible.")
            appendLine("If the image shows a schedule table or recurring series with multiple explicit date/time rows, keep one candidate per row when the rows clearly describe separate occurrences.")
            appendLine("Do not merge distinct schedule rows into one generic observation just because they share the same date, venue, or series title.")
            appendLine("For schedule rows, keep the row's own visible title separate from the flyer or series title.")
            appendLine("The flyer banner title is not the event title for individual schedule rows.")
            appendLine("Copy the row title exactly as visible and do not add extra words, adjectives, or paraphrases.")
            appendLine("When copying locations, preserve spaces, punctuation, and parentheses as visible instead of compressing them.")
            appendLine("Return ONLY JSON in this exact shape:")
            appendLine("{ \"events\": [ { \"titleCandidates\": [], \"descriptionCandidates\": [], \"locationCandidates\": [], \"dateCandidates\": [], \"timeCandidates\": [], \"supportingText\": [], \"notes\": [] } ], \"globalNotes\": [] }")
            appendLine("Keep multiple candidate dates or times if the image is ambiguous.")
            appendLine("Copy visible phrases as they appear when useful. Do not output final ISO timestamps yet.")
        }
    }

    private fun buildHeavyImageObservationPrompt(context: InputContext, ocrText: String?): String {
        return buildString {
            append(buildHeavyImageObservationPrompt(context))
            if (!ocrText.isNullOrBlank()) {
                appendLine("OCR text extracted from the image:")
                appendLine(normalizeVisibleText(ocrText))
            }
        }
    }

    private fun buildHeavyAudioObservationPrompt(context: InputContext): String {
        return buildString {
            append(buildReferencePrompt(context))
            appendLine(HEAVY_AUDIO_STAGE_1)
            appendLine("Listen to the audio conservatively and capture raw event evidence before normalization.")
            appendLine("Only capture actual calendar commitments; ignore incidental future mentions and general availability statements.")
            appendLine("Do not invent a generic meeting when the audio only mentions a time or date without naming an event.")
            appendLine("The audio may contain filler words, background noise, repeated fragments, and ASR mistakes.")
            appendLine("Keep the intended event, not the transcription artifacts.")
            appendLine("Return ONLY JSON in this exact shape:")
            appendLine("{ \"events\": [ { \"titleCandidates\": [], \"descriptionCandidates\": [], \"locationCandidates\": [], \"dateCandidates\": [], \"timeCandidates\": [], \"quotedPhrases\": [], \"notes\": [] } ], \"globalNotes\": [] }")
            appendLine("Keep multiple candidate dates or times if the speaker is ambiguous.")
            appendLine("Do not output final ISO timestamps yet.")
        }
    }

    private fun buildTemporalResolutionPrompt(
        context: InputContext,
        stageLabel: String,
        sourceType: String,
        observations: String
    ): String {
        return buildString {
            append(buildReferencePrompt(context))
            appendLine(stageLabel)
            appendLine("You are resolving temporal information for a heavy $sourceType extraction pass.")
            appendLine("Use the observation JSON below and focus only on dates, times, durations, and event boundaries.")
            appendLine("Return ONLY JSON in this exact shape:")
            appendLine("{ \"events\": [ { \"resolvedStartTime\": \"ISO-8601 or empty\", \"resolvedEndTime\": \"ISO-8601 or empty\", \"dateReasoning\": \"\", \"remainingAmbiguity\": \"\" } ] }")
            appendLine("If you cannot safely resolve a time, leave it empty instead of guessing.")
            appendLine("Observation JSON:")
            appendLine(observations)
        }
    }

    private fun buildFinalCompositionPrompt(
        context: InputContext,
        stageLabel: String,
        sourceType: String,
        observations: String,
        temporalResolution: String
    ): String {
        return buildString {
            append(buildReferencePrompt(context))
            appendLine(stageLabel)
            appendLine("You are composing final events for a heavy $sourceType extraction pass.")
            appendLine("Use the observation JSON for titles, descriptions, locations, and attendees.")
            appendLine("Use the temporal-resolution JSON for startTime and endTime when available.")
            appendLine("Return ONLY valid JSON in this exact shape: { \"events\": [ { \"title\": \"\", \"description\": \"\", \"startTime\": \"ISO-8601\", \"endTime\": \"ISO-8601\", \"location\": \"\", \"attendees\": [] } ] }")
            appendLine("Keep multiple distinct events if the earlier stages found them.")
            appendLine("If a date cannot be resolved safely, leave startTime and endTime empty rather than inventing one.")
            appendLine("Observation JSON:")
            appendLine(observations)
            appendLine()
            appendLine("Temporal-resolution JSON:")
            appendLine(temporalResolution)
        }
    }

    private suspend fun extractImageText(bitmap: Bitmap): String? {
        return ocrService?.extractText(bitmap)
    }

    private suspend fun maybeApplyWebVerification(
        results: List<EventExtraction>,
        ocrText: String?,
        context: InputContext
    ): List<EventExtraction> {
        if (results.isEmpty()) return results
        if (preferencesManager?.isWebVerificationEnabled != true) return results
        return webVerificationService?.refineImageEvents(results, ocrText, context) ?: results
    }

    private fun parseOcrScheduleRows(ocrText: String, timezone: String): List<HeavyImageScheduleRow> {
        val lines = ocrText.lineSequence()
            .map { normalizeVisibleText(it) }
            .filter { it.isNotBlank() }
            .toList()
        if (lines.isEmpty()) return emptyList()

        val rows = mutableListOf<HeavyImageScheduleRow>()
        var index = 0
        while (index < lines.size) {
            val line = lines[index]
            if (!looksLikeDateLine(line)) {
                index++
                continue
            }

            val title = findNextMeaningfulLine(lines, index + 1)
            val timeLine = findNextTimeLine(lines, index + 1)
            if (title != null && timeLine != null) {
                val timeRange = parseTimeRange(timeLine) ?: run {
                    index++
                    continue
                }
                val date = parseDateLine(line) ?: run {
                    index++
                    continue
                }
                val location = findLikelyLocationLine(lines, 0, index)
                val start = ZonedDateTime.of(date, timeRange.start, ZoneId.of(timezone)).format(promptDateTimeFormatter)
                val end = ZonedDateTime.of(date, timeRange.end, ZoneId.of(timezone)).format(promptDateTimeFormatter)
                rows += HeavyImageScheduleRow(
                    title = title,
                    location = location.orEmpty(),
                    startTime = start,
                    endTime = end
                )
            }
            index++
        }
        return rows
    }

    private fun findNextMeaningfulLine(lines: List<String>, startIndex: Int): String? {
        for (index in startIndex until lines.size) {
            val line = lines[index]
            if (looksLikeDateLine(line) || looksLikeTimeLine(line) || line.equals("Event flyer", ignoreCase = true)) {
                continue
            }
            return line
        }
        return null
    }

    private fun findNextTimeLine(lines: List<String>, startIndex: Int): String? {
        for (index in startIndex until lines.size) {
            val line = lines[index]
            if (looksLikeDateLine(line)) return null
            if (looksLikeTimeLine(line)) return line
        }
        return null
    }

    private fun findLikelyLocationLine(lines: List<String>, startIndex: Int, endIndexExclusive: Int): String? {
        for (index in startIndex until endIndexExclusive) {
            val line = lines[index]
            if (looksLikeLocationLine(line)) {
                return line
            }
        }
        return null
    }

    private fun parseDateLine(value: String): java.time.LocalDate? {
        val formatters = listOf(
            DateTimeFormatter.ofPattern("EEEE, MMMM d, uuuu", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("MMMM d, uuuu", Locale.ENGLISH)
        )
        return formatters.firstNotNullOfOrNull { formatter ->
            try {
                LocalDate.parse(value, formatter)
            } catch (_: DateTimeParseException) {
                null
            }
        }
    }

    private fun parseTimeRange(value: String): TimeRange? {
        val pattern = Regex("""(?i)^\s*(.+?)\s*[-–]\s*(.+?)\s*$""")
        val match = pattern.matchEntire(value) ?: return null
        val start = parseClockTime(match.groupValues[1]) ?: return null
        val end = parseClockTime(match.groupValues[2]) ?: return null
        return TimeRange(start, end)
    }

    private fun parseClockTime(value: String): LocalTime? {
        val trimmed = normalizeVisibleText(value).replace(".", "")
        val formatters = listOf(
            DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("h a", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("hh:mm a", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("hh a", Locale.ENGLISH)
        )
        return formatters.firstNotNullOfOrNull { formatter ->
            try {
                LocalTime.parse(trimmed.uppercase(Locale.ENGLISH), formatter)
            } catch (_: DateTimeParseException) {
                null
            }
        }
    }

    private fun looksLikeDateLine(value: String): Boolean {
        return value.matches(Regex("(?i)^(mon|tues|wednes|thurs|fri|satur|sun)day,?\\s+.*$")) ||
            value.matches(Regex("(?i)^(january|february|march|april|may|june|july|august|september|october|november|december)\\s+\\d{1,2},\\s+\\d{4}$")) ||
            value.matches(Regex("(?i)^(mon|tues|wednes|thurs|fri|satur|sun)day,?\\s+(january|february|march|april|may|june|july|august|september|october|november|december)\\s+\\d{1,2},\\s+\\d{4}$"))
    }

    private fun looksLikeTimeLine(value: String): Boolean {
        return value.matches(Regex("(?i)^\\d{1,2}(:\\d{2})?\\s*[ap]m\\s*[-–]\\s*\\d{1,2}(:\\d{2})?\\s*[ap]m$"))
    }

    private fun looksLikeLocationLine(value: String): Boolean {
        return value.contains("(") ||
            value.contains(")") ||
            value.contains(Regex("(?i)\\b(online|virtual|zoom|teams|meet|room|suite|hall|center|centre|building|campus|auditorium|library|office|floor)\\b"))
    }

    private fun normalizeVisibleText(value: String): String {
        return value.trim()
            .replace('’', '\'')
            .replace('“', '"')
            .replace('”', '"')
            .replace('–', '-')
            .replace('—', '-')
            .replace(Regex("\\s+"), " ")
    }

    private data class TimeRange(
        val start: LocalTime,
        val end: LocalTime
    )

    private data class HeavyImageScheduleRow(
        val title: String,
        val location: String,
        val startTime: String,
        val endTime: String
    )

    private fun refineHeavyImageResults(
        results: List<EventExtraction>,
        ocrText: String?,
        context: InputContext
    ): List<EventExtraction> {
        val scheduleRows = ocrText?.let { parseOcrScheduleRows(it, context.timezone) }.orEmpty()
        if (scheduleRows.isEmpty()) {
            return results
        }

        if (results.size == 1 && scheduleRows.size > 1) {
            val base = results.first()
            return scheduleRows.map { row ->
                base.copy(
                    title = row.title,
                    startTime = row.startTime,
                    endTime = row.endTime,
                    location = row.location.takeIf { it.isNotBlank() } ?: base.location
                )
            }
        }

        if (scheduleRows.size >= results.size) {
            return results.mapIndexed { index, event ->
                val row = scheduleRows.getOrNull(index) ?: return@mapIndexed event
                event.copy(
                    title = row.title,
                    startTime = row.startTime.takeIf { it.isNotBlank() } ?: event.startTime,
                    endTime = row.endTime.takeIf { it.isNotBlank() } ?: event.endTime,
                    location = row.location.takeIf { it.isNotBlank() } ?: event.location
                )
            }
        }

        return results
    }

    private fun parseJsonToExtractions(jsonString: String?, traceId: String): List<EventExtraction> {
        if (jsonString == null) {
            AppLog.w(TAG, "[$traceId] LLM returned no response")
            setDebugSnapshot(
                AnalysisDebugSnapshot(
                    traceId = traceId,
                    rawResponse = null,
                    cleanedResponse = null,
                    issue = "The model returned no response."
                )
            )
            return emptyList()
        }

        val cleaned = jsonString.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        val jsonPayload = cleaned.extractJsonPayload()

        return try {
            val element = JsonParser.parseString(jsonPayload)
            val rawEvents = element.asEventObjects()
                .map(::parseEventObject)
                .filter { it.hasMeaningfulContent() }

            val mergedEvents = mergeRelatedEvents(rawEvents)
            setDebugSnapshot(
                AnalysisDebugSnapshot(
                    traceId = traceId,
                    rawResponse = jsonString,
                    cleanedResponse = jsonPayload,
                    issue = if (mergedEvents.isEmpty()) "The model response did not contain any usable events." else null
                )
            )
            AppLog.i(
                TAG,
                "[$traceId] Parsed response chars=${jsonString.length} rawEvents=${rawEvents.size} mergedEvents=${mergedEvents.size}"
            )
            mergedEvents
        } catch (e: Exception) {
            AppLog.e(TAG, "[$traceId] Failed to parse LLM response chars=${jsonString.length}", e)
            setDebugSnapshot(
                AnalysisDebugSnapshot(
                    traceId = traceId,
                    rawResponse = jsonString,
                    cleanedResponse = jsonPayload,
                    issue = "Failed to parse the model response as event JSON: ${e.message ?: e::class.java.simpleName}"
                )
            )
            emptyList()
        }
    }

    private fun clearDebugSnapshot() {
        synchronized(debugSnapshotLock) {
            lastDebugSnapshot = null
        }
    }

    private fun recordStageFailure(
        traceId: String,
        stageLabel: String,
        issue: String,
        rawResponse: String?
    ) {
        setDebugSnapshot(
            AnalysisDebugSnapshot(
                traceId = traceId,
                rawResponse = rawResponse,
                cleanedResponse = rawResponse,
                issue = "$stageLabel failed: $issue"
            )
        )
    }

    private fun setDebugSnapshot(snapshot: AnalysisDebugSnapshot) {
        synchronized(debugSnapshotLock) {
            lastDebugSnapshot = snapshot
        }
    }

    private fun JsonElement.asEventObjects(): List<JsonObject> {
        return when {
            isJsonObject -> {
                val obj = asJsonObject
                val eventsArray = obj.get("events")
                if (eventsArray?.isJsonArray == true) {
                    eventsArray.asJsonArray.mapNotNull { it.takeIf { element -> element.isJsonObject }?.asJsonObject }
                } else {
                    listOf(obj)
                }
            }
            isJsonArray -> asJsonArray.mapNotNull { it.takeIf { element -> element.isJsonObject }?.asJsonObject }
            else -> emptyList()
        }
    }

    private fun parseEventObject(json: JsonObject): EventExtraction {
        return EventExtraction(
            title = json.stringValue("title"),
            description = json.stringValue("description"),
            startTime = json.stringValue("startTime"),
            endTime = json.stringValue("endTime"),
            location = json.stringValue("location"),
            attendees = json.attendeesValue("attendees")
        )
    }

    private fun mergeRelatedEvents(events: List<EventExtraction>): List<EventExtraction> {
        val merged = mutableListOf<EventExtraction>()

        for (event in events) {
            val existingIndex = merged.indexOfFirst { it.canMergeWith(event) }
            if (existingIndex >= 0) {
                merged[existingIndex] = merged[existingIndex].mergeWith(event)
            } else {
                merged += event.normalized()
            }
        }

        return merged
    }

    private fun JsonObject.stringValue(key: String): String {
        return get(key)?.takeIf { !it.isJsonNull }?.asString.orEmpty()
    }

    private fun JsonObject.attendeesValue(key: String): List<String> {
        val rawValue = get(key) ?: return emptyList()
        return when {
            rawValue.isJsonArray -> rawValue.asJsonArray
                .mapNotNull { element -> element.takeIf { !it.isJsonNull }?.asString?.trim() }
            rawValue.isJsonPrimitive -> rawValue.asString
                .split(",", ";", "\n")
                .map { it.trim() }
            else -> emptyList()
        }.filter { it.isNotEmpty() }
            .distinct()
    }

    private fun String.extractJsonPayload(): String {
        val firstBrace = indexOf('{')
        val firstBracket = indexOf('[')
        val objectStartsFirst = firstBrace >= 0 && (firstBracket < 0 || firstBrace < firstBracket)

        return if (objectStartsFirst) {
            val lastBrace = lastIndexOf('}')
            if (lastBrace > firstBrace) substring(firstBrace, lastBrace + 1) else this
        } else if (firstBracket >= 0) {
            val lastBracket = lastIndexOf(']')
            if (lastBracket > firstBracket) substring(firstBracket, lastBracket + 1) else this
        } else {
            this
        }
    }
}

private fun combineHeavyModeResponses(
    observations: String?,
    temporalResolution: String?,
    finalJson: String?
): String {
    return buildString {
        appendLine("Heavy mode observation stage:")
        appendLine(observations ?: "<no response>")
        appendLine()
        appendLine("Heavy mode temporal stage:")
        appendLine(temporalResolution ?: "<no response>")
        appendLine()
        appendLine("Heavy mode final stage:")
        appendLine(finalJson ?: "<no response>")
    }.trim()
}

data class AnalysisDebugSnapshot(
    val traceId: String,
    val rawResponse: String?,
    val cleanedResponse: String?,
    val issue: String?
)

data class AnalysisFailureDebug(
    val title: String,
    val body: String
)

/**
 * Data class for event extraction results.
 */
data class EventExtraction(
    val title: String,
    val description: String,
    val startTime: String, // ISO-8601
    val endTime: String,   // ISO-8601
    val location: String,
    val attendees: List<String>,
    val confidence: Float? = null
)

fun EventExtraction.hasMeaningfulContent(): Boolean {
    return title.isNotBlank() ||
        description.isNotBlank() ||
        startTime.isNotBlank() ||
        endTime.isNotBlank() ||
        location.isNotBlank() ||
        attendees.isNotEmpty()
}

private fun EventExtraction.normalized(): EventExtraction {
    return copy(
        title = title.trim(),
        description = description.trim(),
        startTime = startTime.trim(),
        endTime = endTime.trim(),
        location = location.trim(),
        attendees = attendees.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    )
}

private fun EventExtraction.canMergeWith(other: EventExtraction): Boolean {
    val thisTitle = title.normalizedKey()
    val otherTitle = other.title.normalizedKey()
    val titlesCompatible = when {
        thisTitle.isBlank() || otherTitle.isBlank() -> true
        else -> thisTitle == otherTitle
    }

    if (!titlesCompatible) return false

    // If neither fragment has a title, require at least one shared non-empty anchor.
    if (thisTitle.isBlank() && otherTitle.isBlank()) {
        val sharedStart = startTime.isNotBlank() && startTime == other.startTime
        val sharedEnd = endTime.isNotBlank() && endTime == other.endTime
        val sharedLocation = location.isNotBlank() && location.equals(other.location, ignoreCase = true)
        val sharedDescription = description.normalizedKey() == other.description.normalizedKey() &&
            description.normalizedKey().isNotBlank()

        if (!(sharedStart || sharedEnd || sharedLocation || sharedDescription)) {
            return false
        }
    }

    val startsCompatible = startTime.isBlank() || other.startTime.isBlank() || startTime == other.startTime
    val endsCompatible = endTime.isBlank() || other.endTime.isBlank() || endTime == other.endTime
    val locationsCompatible = location.isBlank() || other.location.isBlank() || location.equals(other.location, ignoreCase = true)

    return startsCompatible && endsCompatible && locationsCompatible
}

private fun EventExtraction.mergeWith(other: EventExtraction): EventExtraction {
    return EventExtraction(
        title = longerNonBlank(title, other.title),
        description = mergeDescriptions(description, other.description),
        startTime = firstNonBlank(startTime, other.startTime),
        endTime = firstNonBlank(endTime, other.endTime),
        location = longerNonBlank(location, other.location),
        attendees = (attendees + other.attendees).map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
        confidence = listOfNotNull(confidence, other.confidence).maxOrNull()
    )
}

private fun String.normalizedKey(): String {
    return trim().lowercase().replace(Regex("\\s+"), " ")
}

private fun firstNonBlank(first: String, second: String): String {
    return first.takeIf { it.isNotBlank() } ?: second
}

private fun longerNonBlank(first: String, second: String): String {
    return listOf(first, second)
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .maxByOrNull { it.length }
        .orEmpty()
}

private fun mergeDescriptions(first: String, second: String): String {
    val parts = listOf(first.trim(), second.trim()).filter { it.isNotBlank() }.distinct()
    return parts.joinToString("\n\n")
}

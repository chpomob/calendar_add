package com.calendaradd.service

import android.graphics.Bitmap
import com.calendaradd.usecase.InputContext
import com.calendaradd.usecase.PreferencesManager
import com.calendaradd.util.AppLog
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
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
        val promptText = buildTextPrompt(input, context)
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
        val promptText = buildImagePrompt(context)
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
        val promptText = buildAudioPrompt(context)
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

            val mergedEvents = mergeRelatedEventExtractions(rawEvents)
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

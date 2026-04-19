package com.calendaradd.service

import android.graphics.Bitmap
import com.calendaradd.usecase.InputContext
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Service for orchestrating the AI analysis pipeline.
 */
class TextAnalysisService(
    private val gemmaLlmService: EventJsonExtractor,
    private val imageTextExtractor: ImageTextExtractor? = null
) {

    /**
     * Analyzes text input and extracts event information.
     */
    suspend fun analyzeText(
        input: String,
        context: InputContext = InputContext()
    ): EventExtraction = withContext(Dispatchers.IO) {
        val promptText = buildString {
            appendLine("Current context: ${context.timestamp} (UTC/Local depending on timezone), Timezone: ${context.timezone}, Language: ${context.language}")
            appendLine("Reference date: ${java.time.Instant.ofEpochMilli(context.timestamp).atZone(java.time.ZoneId.of(context.timezone))}")
            appendLine("User input: $input")
        }
        val jsonString = gemmaLlmService.extractEventJson(text = promptText)
        parseJsonToExtraction(jsonString)
    }

    /**
     * Analyzes image for event information.
     */
    suspend fun analyzeImage(
        bitmap: Bitmap,
        context: InputContext = InputContext()
    ): EventExtraction = withContext(Dispatchers.IO) {
        val extractedText = imageTextExtractor?.extractText(bitmap)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("No readable text was found in the selected image.")

        val imagePrompt = buildString {
            appendLine("This text was extracted from an image via OCR.")
            appendLine("Prioritize event details that are explicitly present in the OCR text.")
            appendLine("OCR text:")
            append(extractedText)
        }

        analyzeText(imagePrompt, context)
    }

    /**
     * Analyzes audio for event information.
     */
    suspend fun analyzeAudio(
        audioData: ByteArray,
        context: InputContext = InputContext()
    ): EventExtraction = withContext(Dispatchers.IO) {
        val promptText = buildString {
            appendLine("Current context: ${context.timestamp}, Reference date: ${java.time.Instant.ofEpochMilli(context.timestamp).atZone(java.time.ZoneId.of(context.timezone))}")
            appendLine("Extract event from this audio recording.")
        }
        val jsonString = gemmaLlmService.extractEventJson(text = promptText, audio = audioData)
        parseJsonToExtraction(jsonString)
    }

    private fun parseJsonToExtraction(jsonString: String?): EventExtraction {
        if (jsonString == null) return emptyExtraction()
        
        return try {
            val cleaned = jsonString.trim()
                .removePrefix("```json")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()
            val jsonPayload = cleaned.extractJsonPayload()

            val json = JsonParser.parseString(jsonPayload).asJsonObject

            EventExtraction(
                title = json.stringValue("title"),
                description = json.stringValue("description"),
                startTime = json.stringValue("startTime"),
                endTime = json.stringValue("endTime"),
                location = json.stringValue("location"),
                attendees = json.attendeesValue("attendees")
            )
        } catch (e: Exception) {
            e.printStackTrace()
            emptyExtraction()
        }
    }

    private fun emptyExtraction() = EventExtraction(
        title = "",
        description = "",
        startTime = "",
        endTime = "",
        location = "",
        attendees = emptyList()
    )

    private fun com.google.gson.JsonObject.stringValue(key: String): String {
        return get(key)?.takeIf { !it.isJsonNull }?.asString.orEmpty()
    }

    private fun com.google.gson.JsonObject.attendeesValue(key: String): List<String> {
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
        val lastBrace = lastIndexOf('}')
        return if (firstBrace >= 0 && lastBrace > firstBrace) {
            substring(firstBrace, lastBrace + 1)
        } else {
            this
        }
    }
}

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

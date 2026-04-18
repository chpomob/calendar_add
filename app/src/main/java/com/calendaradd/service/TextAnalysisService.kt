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
    private val gemmaLlmService: EventJsonExtractor
) {

    /**
     * Analyzes text input and extracts event information.
     */
    suspend fun analyzeText(
        input: String,
        context: InputContext = InputContext()
    ): EventExtraction = withContext(Dispatchers.IO) {
        val jsonString = gemmaLlmService.extractEventJson(text = input)
        parseJsonToExtraction(jsonString)
    }

    /**
     * Analyzes image for event information.
     */
    suspend fun analyzeImage(
        bitmap: Bitmap,
        context: InputContext = InputContext()
    ): EventExtraction = withContext(Dispatchers.IO) {
        val jsonString = gemmaLlmService.extractEventJson(text = "Extract event from this image.", image = bitmap)
        parseJsonToExtraction(jsonString)
    }

    /**
     * Analyzes audio for event information.
     */
    suspend fun analyzeAudio(
        audioData: ByteArray,
        context: InputContext = InputContext()
    ): EventExtraction = withContext(Dispatchers.IO) {
        val jsonString = gemmaLlmService.extractEventJson(text = "Extract event from this audio recording.", audio = audioData)
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

            val json = JsonParser.parseString(cleaned).asJsonObject

            EventExtraction(
                title = json.stringValue("title"),
                description = json.stringValue("description"),
                startTime = json.stringValue("startTime"),
                endTime = json.stringValue("endTime"),
                location = json.stringValue("location"),
                attendees = json.arrayValue("attendees")
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

    private fun com.google.gson.JsonObject.arrayValue(key: String): List<String> {
        return getAsJsonArray(key)
            ?.mapNotNull { element -> element.takeIf { !it.isJsonNull }?.asString }
            ?: emptyList()
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

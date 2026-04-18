package com.calendaradd.service

import android.graphics.Bitmap
import com.calendaradd.usecase.InputContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

/**
 * Service for orchestrating the AI analysis pipeline.
 */
class TextAnalysisService(
    private val gemmaLlmService: GemmaLlmService,
    private val ocrService: OcrService
) {

    /**
     * Analyzes text input and extracts event information.
     */
    suspend fun analyzeInput(
        input: String,
        context: InputContext = InputContext()
    ): EventExtraction = withContext(Dispatchers.IO) {
        val jsonString = gemmaLlmService.extractEventJson(input)
        parseJsonToExtraction(jsonString)
    }

    /**
     * Analyzes image for event information.
     */
    suspend fun analyzeImage(
        bitmap: Bitmap,
        context: InputContext = InputContext()
    ): EventExtraction = withContext(Dispatchers.IO) {
        val extractedText = ocrService.extractText(bitmap) ?: return@withContext emptyExtraction()
        analyzeInput(extractedText, context)
    }

    private fun parseJsonToExtraction(jsonString: String?): EventExtraction {
        if (jsonString == null) return emptyExtraction()
        
        return try {
            // Clean up JSON string (LLMs sometimes add markdown code blocks)
            val cleaned = jsonString.trim()
                .removePrefix("```json")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()
                
            val json = JSONObject(cleaned)
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
            
            EventExtraction(
                title = json.optString("title"),
                description = json.optString("description"),
                startTime = json.optString("startTime"),
                endTime = json.optString("endTime"),
                location = json.optString("location"),
                attendees = json.optJSONArray("attendees")?.let { arr ->
                    List(arr.length()) { i -> arr.getString(i) }
                } ?: emptyList()
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

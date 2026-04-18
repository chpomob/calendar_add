package com.calendaradd.service

import com.calendaradd.usecase.InputContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Service for analyzing various inputs using local LLMs.
 * Supports text, audio, and image analysis for event extraction.
 */
class TextAnalysisService(
    private val llmEngine: LlmEngine? = null
) {

    /**
     * Analyzes text input and extracts event information.
     * Uses LLM if available, otherwise falls back to basic extraction.
     */
    suspend fun analyzeInput(
        input: String,
        context: InputContext
    ): EventExtraction = withContext(Dispatchers.IO) {
        // If LLM engine is loaded, use it for extraction
        if (llmEngine?.isLoaded() == true) {
            val llmResult = llmEngine.analyzeInput(input)
            if (llmResult != null) {
                // Validate and enrich with current time
                val now = java.util.Date()
                EventExtraction(
                    title = llmResult.title.ifEmpty { extractTitleFromInput(input) },
                    description = llmResult.description.ifEmpty { extractDescriptionFromInput(input) },
                    startTime = llmResult.startTime.ifEmpty { now.toString() },
                    endTime = llmResult.endTime.ifEmpty { "" },
                    location = llmResult.location.ifEmpty { extractLocationFromInput(input) },
                    attendees = llmResult.attendees
                )
            } else {
                // Fallback to basic extraction
                EventExtraction(
                    title = extractTitleFromInput(input),
                    description = extractDescriptionFromInput(input),
                    startTime = now.toString(),
                    endTime = "",
                    location = extractLocationFromInput(input),
                    attendees = extractAttendeesFromInput(input)
                )
            }
        } else {
            // No LLM, use basic extraction
            EventExtraction(
                title = extractTitleFromInput(input),
                description = extractDescriptionFromInput(input),
                startTime = now.toString(),
                endTime = "",
                location = extractLocationFromInput(input),
                attendees = extractAttendeesFromInput(input)
            )
        }
    }

    private fun extractTitleFromInput(input: String): String {
        return input.lines().firstOrNull { it.isNotBlank() }
            ?: "Untitled Event"
    }

    private fun extractDescriptionFromInput(input: String): String {
        return input.lines().drop(1).joinToString("\n")
            .takeIf { it.isNotEmpty() } ?: ""
    }

    private fun extractLocationFromInput(input: String): String {
        return input.lines()
            .firstOrNull { it.contains("location", ignoreCase = true) || it.contains("place", ignoreCase = true) }
            ?.removePrefix("- ")
            ?.removePrefix("📍 ")
            ?.take(100)
            .orEmpty()
    }

    private fun extractAttendeesFromInput(input: String): List<String> {
        return input.lines()
            .filter { it.contains("attendee", ignoreCase = true) || it.contains("with", ignoreCase = true) }
            .map { it.replaceFirstChar { it.uppercase() } }
            .distinct()
    }

    /**
     * Analyzes audio file for transcription and event extraction.
     */
    suspend fun analyzeAudioFile(
        audioPath: String,
        context: InputContext
    ): EventExtraction? = withContext(Dispatchers.IO) {
        // TODO: Implement audio-to-text and event extraction
        null
    }

    /**
     * Analyzes image file for event information using OCR.
     */
    suspend fun analyzeImageFile(
        imagePath: String,
        context: InputContext
    ): EventExtraction? = withContext(Dispatchers.IO) {
        // TODO: Implement OCR and event extraction from images
        null
    }
}

/**
 * Data class for event extraction results.
 */
data class EventExtraction(
    val title: String,
    val description: String,
    val startTime: String,
    val endTime: String,
    val location: String,
    val attendees: List<String>,
    val confidence: Float? = null
)

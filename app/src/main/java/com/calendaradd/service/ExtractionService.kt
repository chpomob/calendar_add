package com.calendaradd.service

import com.calendaradd.usecase.InputContext
import com.calendaradd.usecase.EventExtraction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

/**
 * Service for extracting events from various inputs using LLM.
 */
class ExtractionService(
    private val llmEngine: LlmEngine
) {

    suspend fun extractFromText(
        input: String,
        context: InputContext = InputContext()
    ): EventExtraction? = withContext(Dispatchers.IO) {
        try {
            // Get LLM inference result
            val llmResult = llmEngine.analyzeInput(input) ?: return@withContext null

            // Parse and validate result
            parseAndValidate(llmResult, context)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    suspend fun extractFromAudio(
        audioPath: String,
        context: InputContext = InputContext()
    ): EventExtraction? = withContext(Dispatchers.IO) {
        // TODO: Implement audio transcription and extraction
        // For now, return null
        null
    }

    suspend fun extractFromImage(
        imagePath: String,
        context: InputContext = InputContext()
    ): EventExtraction? = withContext(Dispatchers.IO) {
        // TODO: Implement OCR and extraction
        // For now, return null
        null
    }

    private fun parseAndValidate(result: EventExtraction, context: InputContext): EventExtraction? {
        return try {
            // Validate and enrich extraction
            EventExtraction(
                title = result.title.ifEmpty { extractTitleFromInput(input) },
                description = result.description.ifEmpty { extractDescriptionFromInput(input) },
                startTime = formatDateTime(
                    result.date.ifEmpty { "" },
                    result.time.ifEmpty { "" },
                    context.timezone
                ),
                endTime = formatEndTime(result.startTime, result.duration),
                location = result.location.ifEmpty { extractLocationFromInput(input) },
                attendees = result.attendees.filter { it.isNotEmpty() }.toSet().toList().sorted()
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
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

    private fun formatDateTime(
        date: String,
        time: String,
        timezone: String
    ): String {
        // TODO: Implement proper datetime formatting
        return "${System.currentTimeMillis()}"
    }

    private fun formatEndTime(startTime: String, duration: Int = 60): String {
        // TODO: Implement end time calculation
        return ""
    }
}

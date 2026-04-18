package com.calendaradd.service

import com.calendaradd.usecase.InputContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Service for extracting events from various inputs using LLM.
 */
interface LlmEngine {
    suspend fun analyzeInput(input: String): EventExtraction?
}

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
        return result.copy(
            title = result.title.ifBlank { "Untitled Event" },
            attendees = result.attendees.filter { it.isNotBlank() }.distinct(),
            confidence = result.confidence ?: 1.0f
        )
    }
}

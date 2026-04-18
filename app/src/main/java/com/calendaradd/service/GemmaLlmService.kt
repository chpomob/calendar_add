package com.calendaradd.service

import com.google.mlkit.genai.Generation
import com.google.mlkit.genai.GenerativeModel
import com.google.mlkit.genai.FeatureStatus
import com.google.mlkit.genai.DownloadStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Service for interacting with Gemma 4 via ML Kit GenAI Prompt API.
 */
class GemmaLlmService {

    private val generativeModel: GenerativeModel = Generation.getClient()

    /**
     * Checks if the Gemma 4 model is available and returns its status.
     */
    suspend fun checkModelStatus(): FeatureStatus {
        return generativeModel.checkStatus()
    }

    /**
     * Initiates the download of the Gemma 4 model.
     */
    fun downloadModel(): Flow<Int> {
        return generativeModel.download().map { status ->
            when (status) {
                is DownloadStatus.Progress -> status.progress
                is DownloadStatus.DownloadCompleted -> 100
                is DownloadStatus.DownloadFailed -> -1
                else -> 0
            }
        }
    }

    /**
     * Extracts event information from the given input text using Gemma 4.
     * Returns a JSON string containing the extracted fields.
     */
    suspend fun extractEventJson(input: String): String? {
        val systemPrompt = """
            You are a calendar assistant. Extract event details from the user input.
            Respond ONLY with a JSON object containing:
            - title (string)
            - description (string)
            - startTime (ISO-8601 string or empty)
            - endTime (ISO-8601 string or empty)
            - location (string)
            - attendees (list of strings)
            
            If a field is missing, use an empty string or empty list.
            Input: "$input"
        """.trimIndent()

        return try {
            val response = generativeModel.generateContent(systemPrompt)
            response.text
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}

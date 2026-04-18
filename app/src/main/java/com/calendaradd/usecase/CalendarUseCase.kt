package com.calendaradd.usecase

import android.graphics.Bitmap
import com.calendaradd.service.EventExtraction
import com.calendaradd.service.TextAnalysisService
import java.text.SimpleDateFormat
import java.util.*

/**
 * Use case for creating calendar events from various inputs.
 */
class CalendarUseCase(
    private val textAnalysisService: TextAnalysisService,
    private val eventDatabase: EventDatabase
) {

    suspend fun createEventFromText(
        input: String,
        context: InputContext = InputContext()
    ): EventResult {
        return try {
            val analysis = textAnalysisService.analyzeText(input, context)
            saveExtraction(analysis, "text")
        } catch (e: Exception) {
            EventResult.Failure(e.message ?: "Unknown error")
        }
    }

    suspend fun createEventFromImage(
        bitmap: Bitmap,
        context: InputContext = InputContext()
    ): EventResult {
        return try {
            val analysis = textAnalysisService.analyzeImage(bitmap, context)
            saveExtraction(analysis, "image")
        } catch (e: Exception) {
            EventResult.Failure(e.message ?: "Unknown error")
        }
    }

    suspend fun createEventFromAudio(
        audioData: ByteArray,
        context: InputContext = InputContext()
    ): EventResult {
        return try {
            val analysis = textAnalysisService.analyzeAudio(audioData, context)
            saveExtraction(analysis, "audio")
        } catch (e: Exception) {
            EventResult.Failure(e.message ?: "Unknown error")
        }
    }

    private suspend fun saveExtraction(analysis: EventExtraction, sourceType: String): EventResult {
        val startTime = parseIso8601(analysis.startTime) ?: System.currentTimeMillis()
        val endTime = parseIso8601(analysis.endTime) ?: (startTime + 3600000L) // Default 1 hour

        val event = Event(
            title = analysis.title.ifEmpty { "Untitled Event" },
            description = analysis.description,
            startTime = startTime,
            endTime = endTime,
            location = analysis.location,
            attendees = analysis.attendees.joinToString(", "),
            sourceType = sourceType,
            aiConfidence = analysis.confidence ?: 1.0f
        )

        val id = eventDatabase.eventDao().insert(event)
        return EventResult.Success(event.copy(id = id))
    }

    private fun parseIso8601(dateString: String?): Long? {
        if (dateString.isNullOrBlank()) return null
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
            sdf.parse(dateString)?.time
        } catch (e: Exception) {
            null
        }
    }

    fun getAllEvents() = eventDatabase.eventDao().getAllEvents()
    
    suspend fun deleteEvent(id: Long) = eventDatabase.eventDao().deleteEvent(id)
}

/**
 * Input context for event creation.
 */
data class InputContext(
    val timestamp: Long = System.currentTimeMillis(),
    val timezone: String = java.util.TimeZone.getDefault().id,
    val aiModel: String = "Gemma 4",
    val language: String = "en"
)

/**
 * Result of event creation operation.
 */
sealed class EventResult {
    data class Success(val event: Event) : EventResult()
    data class Failure(val message: String) : EventResult()
}

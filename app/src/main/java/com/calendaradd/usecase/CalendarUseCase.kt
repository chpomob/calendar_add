package com.calendaradd.usecase

import android.graphics.Bitmap
import com.calendaradd.service.*
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * Use case for creating calendar events from various inputs.
 */
class CalendarUseCase(
    private val textAnalysisService: TextAnalysisService,
    private val eventDatabase: EventDatabase,
    private val systemCalendarService: SystemCalendarService,
    private val preferencesManager: PreferencesManager
) {

    suspend fun createEventFromText(
        input: String,
        context: InputContext = InputContext()
    ): EventResult {
        return try {
            val analysis = textAnalysisService.analyzeText(input, context)
            saveAndSyncExtraction(analysis, "text")
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
            saveAndSyncExtraction(analysis, "image")
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
            saveAndSyncExtraction(analysis, "audio")
        } catch (e: Exception) {
            EventResult.Failure(e.message ?: "Unknown error")
        }
    }

    private suspend fun saveAndSyncExtraction(analysis: EventExtraction, sourceType: String): EventResult {
        val startTime = parseIso8601(analysis.startTime) ?: System.currentTimeMillis()
        val parsedEndTime = parseIso8601(analysis.endTime)
        val endTime = if (parsedEndTime != null && parsedEndTime > startTime) {
            parsedEndTime
        } else {
            startTime + 3600000L
        }

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

        // Step 1: Save to internal database
        val internalId = eventDatabase.eventDao().insert(event)
        val savedEvent = event.copy(id = internalId)

        // Step 2: Auto-add to system calendar if enabled
        if (preferencesManager.isAutoAddEnabled && systemCalendarService.hasCalendarPermissions()) {
            val calendars = systemCalendarService.getAvailableCalendars()
            val calendarId = preferencesManager.targetCalendarId
                .takeIf { id -> id != -1L && calendars.any { it.id == id } }
                ?: calendars.find { it.isPrimary }?.id
                ?: calendars.firstOrNull()?.id

            if (calendarId != null) {
                systemCalendarService.insertEvent(
                    calendarId = calendarId,
                    title = savedEvent.title,
                    description = savedEvent.description,
                    startTimeMillis = savedEvent.startTime,
                    endTimeMillis = savedEvent.endTime,
                    location = savedEvent.location
                )
            }
        }

        return EventResult.Success(savedEvent)
    }

    fun syncEventToSystem(event: Event, calendarId: Long): Long? {
        return systemCalendarService.insertEvent(
            calendarId = calendarId,
            title = event.title,
            description = event.description,
            startTimeMillis = event.startTime,
            endTimeMillis = event.endTime,
            location = event.location
        )
    }

    private fun parseIso8601(dateString: String?): Long? {
        if (dateString.isNullOrBlank()) return null
        return try {
            Instant.parse(dateString).toEpochMilli()
        } catch (e: Exception) {
            try {
                OffsetDateTime.parse(dateString).toInstant().toEpochMilli()
            } catch (e: Exception) {
                try {
                    LocalDateTime.parse(dateString).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                } catch (e: Exception) {
                    try {
                        LocalDate.parse(dateString).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                    } catch (e: Exception) {
                        null
                    }
                }
            }
        }
    }

    fun getAllEvents() = eventDatabase.eventDao().getAllEvents()
    
    suspend fun deleteEvent(id: Long) = eventDatabase.eventDao().deleteEvent(id)
    
    fun getAvailableCalendars() = systemCalendarService.getAvailableCalendars()
}

/**
 * Result of event creation operation.
 */
sealed class EventResult {
    data class Success(val event: Event) : EventResult()
    data class Failure(val message: String) : EventResult()
}

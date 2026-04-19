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
        // Use current time as fallback, but AI should ideally provide it.
        val now = System.currentTimeMillis()
        val startTime = parseIso8601(analysis.startTime) ?: now
        
        val parsedEndTime = parseIso8601(analysis.endTime)
        // If end time is missing or invalid (before start), default to +1 hour
        val endTime = if (parsedEndTime != null && parsedEndTime > startTime) {
            parsedEndTime
        } else {
            startTime + 3600000L // 1 hour default
        }

        val event = Event(
            title = analysis.title.takeIf { it.isNotBlank() } ?: "New Event",
            description = analysis.description,
            startTime = startTime,
            endTime = endTime,
            location = analysis.location,
            attendees = analysis.attendees.joinToString(", "),
            sourceType = sourceType,
            aiConfidence = analysis.confidence ?: 1.0f
        )

        return try {
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
                    val systemId = systemCalendarService.insertEvent(
                        calendarId = calendarId,
                        title = savedEvent.title,
                        description = savedEvent.description,
                        startTimeMillis = savedEvent.startTime,
                        endTimeMillis = savedEvent.endTime,
                        location = savedEvent.location
                    )
                    if (systemId == null) {
                        // Log or handle sync failure, but we still return success because it's in our DB
                    }
                }
            }
            EventResult.Success(savedEvent)
        } catch (e: Exception) {
            EventResult.Failure("Database error: ${e.message}")
        }
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

    /**
     * Robust ISO-8601 and common date format parser.
     */
    private fun parseIso8601(dateString: String?): Long? {
        if (dateString.isNullOrBlank()) return null
        val cleaned = dateString.trim()
        
        // Try various formats from most specific to least
        val parsers = listOf<() -> Long?>(
            { Instant.parse(cleaned).toEpochMilli() },
            { OffsetDateTime.parse(cleaned).toInstant().toEpochMilli() },
            { LocalDateTime.parse(cleaned).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() },
            { LocalDate.parse(cleaned).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() },
            // Handle common AI shorthand if it leaks through
            { 
                if (cleaned.length == 10 && cleaned.count { it == '-' } == 2) {
                    LocalDate.parse(cleaned).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                } else null
            }
        )

        for (parser in parsers) {
            try {
                return parser()
            } catch (e: Exception) {
                // Continue to next parser
            }
        }
        return null
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

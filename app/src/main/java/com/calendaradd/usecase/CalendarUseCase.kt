package com.calendaradd.usecase

import android.graphics.Bitmap
import com.calendaradd.service.*
import com.calendaradd.util.AppLog
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
    companion object {
        private const val TAG = "CalendarUseCase"
    }

    suspend fun createEventFromText(
        input: String,
        context: InputContext = InputContext()
    ): EventResult {
        return try {
            AppLog.i(TAG, "[${context.traceId}] createEventFromText chars=${input.length}")
            val analyses = textAnalysisService.analyzeText(input, context)
            saveAndSyncExtractions(analyses, "text", context)
        } catch (e: Exception) {
            AppLog.e(TAG, "[${context.traceId}] Text event creation failed", e)
            EventResult.Failure(e.message ?: "Unknown error")
        }
    }

    suspend fun createEventFromImage(
        bitmap: Bitmap,
        context: InputContext = InputContext()
    ): EventResult {
        return try {
            AppLog.i(TAG, "[${context.traceId}] createEventFromImage bitmap=${bitmap.width}x${bitmap.height}")
            val analyses = textAnalysisService.analyzeImage(bitmap, context)
            saveAndSyncExtractions(analyses, "image", context)
        } catch (e: Exception) {
            AppLog.e(TAG, "[${context.traceId}] Image event creation failed", e)
            EventResult.Failure(e.message ?: "Unknown error")
        }
    }

    suspend fun createEventFromAudio(
        audioData: ByteArray,
        context: InputContext = InputContext()
    ): EventResult {
        return try {
            AppLog.i(TAG, "[${context.traceId}] createEventFromAudio bytes=${audioData.size}")
            val analyses = textAnalysisService.analyzeAudio(audioData, context)
            saveAndSyncExtractions(analyses, "audio", context)
        } catch (e: Exception) {
            AppLog.e(TAG, "[${context.traceId}] Audio event creation failed", e)
            EventResult.Failure(e.message ?: "Unknown error")
        }
    }

    private suspend fun saveAndSyncExtractions(
        analyses: List<EventExtraction>,
        sourceType: String,
        context: InputContext
    ): EventResult {
        val validAnalyses = analyses.filter { it.hasMeaningfulContent() }
        if (validAnalyses.isEmpty()) {
            AppLog.w(TAG, "[${context.traceId}] No valid events extracted from $sourceType input")
            return EventResult.Failure("Could not extract enough event details from the $sourceType input.")
        }

        return try {
            val calendars = if (preferencesManager.isAutoAddEnabled && systemCalendarService.hasCalendarPermissions()) {
                systemCalendarService.getAvailableCalendars()
            } else {
                emptyList()
            }
            val preferredCalendarId = preferencesManager.targetCalendarId
                .takeIf { id -> id != -1L && calendars.any { it.id == id } }
                ?: calendars.find { it.isPrimary }?.id
                ?: calendars.firstOrNull()?.id

            AppLog.i(
                TAG,
                "[${context.traceId}] Saving ${validAnalyses.size} extracted event(s) source=$sourceType autoSync=${preferredCalendarId != null}"
            )
            val savedEvents = validAnalyses.mapNotNull { analysis ->
                val event = analysis.toEventOrNull(sourceType, ::parseIso8601)
                if (event == null) {
                    AppLog.w(
                        TAG,
                        "[${context.traceId}] Skipping extracted event without parseable start time title=${analysis.title.ifBlank { "<untitled>" }}"
                    )
                    return@mapNotNull null
                }
                val internalId = eventDatabase.eventDao().insert(event)
                val savedEvent = event.copy(id = internalId)

                if (preferredCalendarId != null) {
                    systemCalendarService.insertEvent(
                        calendarId = preferredCalendarId,
                        title = savedEvent.title,
                        description = savedEvent.description,
                        startTimeMillis = savedEvent.startTime,
                        endTimeMillis = savedEvent.endTime,
                        location = savedEvent.location
                    )
                }

                savedEvent
            }

            if (savedEvents.isEmpty()) {
                AppLog.w(TAG, "[${context.traceId}] No extracted events had a valid start time source=$sourceType")
                return EventResult.Failure("Could not extract a valid event date and time from the $sourceType input.")
            }

            AppLog.i(TAG, "[${context.traceId}] Saved ${savedEvents.size} event(s) source=$sourceType")
            EventResult.Success(savedEvents)
        } catch (e: Exception) {
            AppLog.e(TAG, "[${context.traceId}] Failed to persist extracted events source=$sourceType", e)
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

private fun EventExtraction.toEventOrNull(
    sourceType: String,
    parseIso8601: (String?) -> Long?
): Event? {
    val startTimeMillis = parseIso8601(startTime) ?: return null
    val parsedEndTime = parseIso8601(endTime)
    val endTimeMillis = if (parsedEndTime != null && parsedEndTime > startTimeMillis) {
        parsedEndTime
    } else {
        startTimeMillis + 3600000L
    }

    return Event(
        title = title.takeIf { it.isNotBlank() } ?: "New Event",
        description = description,
        startTime = startTimeMillis,
        endTime = endTimeMillis,
        location = location,
        attendees = attendees.joinToString(", "),
        sourceType = sourceType,
        aiConfidence = confidence ?: 1.0f
    )
}

/**
 * Result of event creation operation.
 */
sealed class EventResult {
    data class Success(val events: List<Event>) : EventResult() {
        val event: Event get() = events.first()
    }
    data class Failure(val message: String) : EventResult()
}

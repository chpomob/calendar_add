package com.calendaradd.usecase

import android.graphics.Bitmap
import com.calendaradd.service.*
import com.calendaradd.util.AppLog
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeParseException

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
        context: InputContext = InputContext(),
        sourceAttachment: SourceAttachment? = null
    ): EventResult {
        return try {
            AppLog.i(TAG, "[${context.traceId}] createEventFromImage bitmap=${bitmap.width}x${bitmap.height}")
            val analyses = textAnalysisService.analyzeImage(bitmap, context)
            saveAndSyncExtractions(analyses, "image", context, sourceAttachment)
        } catch (e: Exception) {
            AppLog.e(TAG, "[${context.traceId}] Image event creation failed", e)
            EventResult.Failure(e.message ?: "Unknown error")
        }
    }

    suspend fun createEventFromAudio(
        audioData: ByteArray,
        context: InputContext = InputContext(),
        sourceAttachment: SourceAttachment? = null
    ): EventResult {
        return try {
            AppLog.i(TAG, "[${context.traceId}] createEventFromAudio bytes=${audioData.size}")
            val analyses = textAnalysisService.analyzeAudio(audioData, context)
            saveAndSyncExtractions(analyses, "audio", context, sourceAttachment)
        } catch (e: Exception) {
            AppLog.e(TAG, "[${context.traceId}] Audio event creation failed", e)
            EventResult.Failure(e.message ?: "Unknown error")
        }
    }

    private suspend fun saveAndSyncExtractions(
        analyses: List<EventExtraction>,
        sourceType: String,
        context: InputContext,
        sourceAttachment: SourceAttachment? = null
    ): EventResult {
        val validAnalyses = analyses.filter { it.hasMeaningfulContent() }
        if (validAnalyses.isEmpty()) {
            AppLog.w(TAG, "[${context.traceId}] No valid events extracted from $sourceType input")
            return EventResult.Failure(
                message = "Could not extract enough event details from the $sourceType input.",
                debug = buildFailureDebug(
                    message = "No usable event details were extracted from the $sourceType input.",
                    sourceType = sourceType,
                    context = context
                )
            )
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
                val event = analysis.toEventOrNull(sourceType, context, sourceAttachment, ::parseIso8601)
                if (event == null) {
                    AppLog.w(
                        TAG,
                        "[${context.traceId}] Skipping extracted event without parseable start time title=${analysis.title.ifBlank { "<untitled>" }}"
                    )
                    return@mapNotNull null
                }
                val internalId = eventDatabase.eventDao().insert(event)
                val savedEvent = event.copy(id = internalId)

                val systemCalendarEventId = preferredCalendarId?.let { calendarId ->
                    syncEventToSystem(savedEvent, calendarId)
                }

                savedEvent.copy(systemCalendarEventId = systemCalendarEventId)
            }

            if (savedEvents.isEmpty()) {
                AppLog.w(TAG, "[${context.traceId}] No extracted events had a valid start time source=$sourceType")
                return EventResult.Failure(
                    message = "Could not extract a valid event date and time from the $sourceType input.",
                    debug = buildFailureDebug(
                        message = "The model response did not contain any event with a parseable absolute start time.",
                        sourceType = sourceType,
                        context = context
                    )
                )
            }

            AppLog.i(TAG, "[${context.traceId}] Saved ${savedEvents.size} event(s) source=$sourceType")
            EventResult.Success(savedEvents)
        } catch (e: Exception) {
            AppLog.e(TAG, "[${context.traceId}] Failed to persist extracted events source=$sourceType", e)
            EventResult.Failure(
                message = "Database error: ${e.message}",
                debug = buildFailureDebug(
                    message = "Persistence failed after model extraction.",
                    sourceType = sourceType,
                    context = context
                )
            )
        }
    }

    private fun buildFailureDebug(
        message: String,
        sourceType: String,
        context: InputContext
    ): AnalysisFailureDebug? {
        if (!preferencesManager.isFailureJsonDebugEnabled) {
            textAnalysisService.consumeLastDebugSnapshot()
            return null
        }

        val snapshot = textAnalysisService.consumeLastDebugSnapshot()
        val response = snapshot?.cleanedResponse
            ?.takeIf { it.isNotBlank() }
            ?: snapshot?.rawResponse?.takeIf { it.isNotBlank() }
            ?: "<no model response available>"

        val body = buildString {
            appendLine("Source: $sourceType")
            appendLine("Trace: ${snapshot?.traceId ?: context.traceId}")
            appendLine("Failure: $message")
            snapshot?.issue?.let { issue ->
                appendLine("Model issue: $issue")
            }
            appendLine()
            appendLine("Model response:")
            append(response)
        }.trim().take(12_000)

        return AnalysisFailureDebug(
            title = "Failure Debug JSON",
            body = body
        )
    }

    suspend fun syncEventToSystem(event: Event, calendarId: Long): Long? {
        event.systemCalendarEventId?.let { existingSystemEventId ->
            val updated = systemCalendarService.updateEvent(
                systemEventId = existingSystemEventId,
                calendarId = calendarId,
                title = event.title,
                description = event.description,
                startTimeMillis = event.startTime,
                endTimeMillis = event.endTime,
                location = event.location
            )
            if (updated) {
                return existingSystemEventId
            }
            return null
        }

        val newSystemEventId = systemCalendarService.insertEvent(
            calendarId = calendarId,
            title = event.title,
            description = event.description,
            startTimeMillis = event.startTime,
            endTimeMillis = event.endTime,
            location = event.location
        ) ?: return null

        if (event.id != 0L && event.systemCalendarEventId != newSystemEventId) {
            eventDatabase.eventDao().update(event.copy(systemCalendarEventId = newSystemEventId))
        }

        return newSystemEventId
    }

    suspend fun updateEvent(event: Event): EventUpdateResult {
        val updatedEvent = event.copy(updatedAt = System.currentTimeMillis())
        eventDatabase.eventDao().update(updatedEvent)

        if (!preferencesManager.isAutoAddEnabled) {
            return EventUpdateResult.Success(updatedEvent, syncedToSystem = false)
        }

        if (!systemCalendarService.hasCalendarPermissions()) {
            return EventUpdateResult.Success(
                event = updatedEvent,
                syncedToSystem = false,
                warning = "Saved locally. Calendar permission is required to update the system calendar."
            )
        }

        val calendars = systemCalendarService.getAvailableCalendars()
        val preferredCalendarId = preferencesManager.targetCalendarId
            .takeIf { id -> id != -1L && calendars.any { it.id == id } }
            ?: calendars.find { it.isPrimary }?.id
            ?: calendars.firstOrNull()?.id

        if (preferredCalendarId == null) {
            return EventUpdateResult.Success(
                event = updatedEvent,
                syncedToSystem = false,
                warning = "Saved locally. No system calendar was found for auto-sync."
            )
        }

        val systemEventId = syncEventToSystem(updatedEvent, preferredCalendarId)
            ?: return EventUpdateResult.Success(
                event = updatedEvent,
                syncedToSystem = false,
                warning = "Saved locally. System calendar update failed."
            )

        val syncedEvent = updatedEvent.copy(systemCalendarEventId = systemEventId)
        return EventUpdateResult.Success(syncedEvent, syncedToSystem = true)
    }

    /**
     * Robust ISO-8601 and common date format parser.
     */
    private fun parseIso8601(dateString: String?, zoneId: ZoneId = ZoneId.systemDefault()): Long? {
        if (dateString.isNullOrBlank()) return null
        val cleaned = dateString.trim()
        
        // Try various formats from most specific to least
        val parsers = listOf<() -> Long?>(
            { Instant.parse(cleaned).toEpochMilli() },
            { OffsetDateTime.parse(cleaned).toInstant().toEpochMilli() },
            { LocalDateTime.parse(cleaned).atZone(zoneId).toInstant().toEpochMilli() },
            { LocalDate.parse(cleaned).atStartOfDay(zoneId).toInstant().toEpochMilli() },
            // Handle common AI shorthand if it leaks through
            { 
                if (cleaned.length == 10 && cleaned.count { it == '-' } == 2) {
                    LocalDate.parse(cleaned).atStartOfDay(zoneId).toInstant().toEpochMilli()
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
    
    suspend fun deleteEvent(id: Long) {
        val dao = eventDatabase.eventDao()
        val event = dao.getEventById(id)
        dao.deleteEvent(id)
        val sourcePath = event?.sourceAttachmentPath?.takeIf { it.isNotBlank() } ?: return
        if (dao.countEventsWithSourceAttachmentPath(sourcePath) == 0) {
            File(sourcePath).delete()
        }
    }
    
    fun getAvailableCalendars() = systemCalendarService.getAvailableCalendars()
}

private fun EventExtraction.toEventOrNull(
    sourceType: String,
    context: InputContext,
    sourceAttachment: SourceAttachment?,
    parseIso8601: (String?, ZoneId) -> Long?
): Event? {
    val zoneId = context.zoneIdOrDefault()
    val timePolicy = EventTimePolicy.from(this)
    val startTimeMillis = resolveStartTime(zoneId, timePolicy, parseIso8601) ?: return null
    val parsedEndTime = parseIso8601(endTime, zoneId)
    val endTimeMillis = resolveEndTime(
        startTimeMillis = startTimeMillis,
        parsedEndTime = parsedEndTime,
        timePolicy = timePolicy
    )

    return Event(
        title = title.takeIf { it.isNotBlank() } ?: "New Event",
        description = description,
        startTime = startTimeMillis,
        endTime = endTimeMillis,
        location = location,
        attendees = attendees.joinToString(", "),
        sourceType = sourceType,
        sourceAttachmentPath = sourceAttachment?.path.orEmpty(),
        sourceAttachmentMimeType = sourceAttachment?.mimeType.orEmpty(),
        sourceAttachmentName = sourceAttachment?.displayName.orEmpty(),
        aiConfidence = confidence ?: 1.0f
    )
}

private fun InputContext.zoneIdOrDefault(): ZoneId {
    return try {
        ZoneId.of(timezone)
    } catch (e: DateTimeParseException) {
        ZoneId.systemDefault()
    } catch (e: RuntimeException) {
        ZoneId.systemDefault()
    }
}

private fun EventExtraction.resolveStartTime(
    zoneId: ZoneId,
    timePolicy: EventTimePolicy,
    parseIso8601: (String?, ZoneId) -> Long?
): Long? {
    val cleanedStart = startTime.trim()
    val dateOnly = cleanedStart.toIsoDateOrNull()
    if (dateOnly != null && timePolicy.inferredStartTime != null) {
        return dateOnly.atTime(timePolicy.inferredStartTime)
            .atZone(zoneId)
            .toInstant()
            .toEpochMilli()
    }
    return parseIso8601(cleanedStart, zoneId)
}

private fun resolveEndTime(
    startTimeMillis: Long,
    parsedEndTime: Long?,
    timePolicy: EventTimePolicy
): Long {
    val fallbackEndTime = startTimeMillis + timePolicy.defaultDurationMillis
    if (parsedEndTime == null || parsedEndTime <= startTimeMillis) {
        return fallbackEndTime
    }

    val parsedDuration = parsedEndTime - startTimeMillis
    return if (parsedDuration > timePolicy.maximumDurationMillis) {
        fallbackEndTime
    } else {
        parsedEndTime
    }
}

private fun String.toIsoDateOrNull(): LocalDate? {
    val cleaned = trim()
    if (!Regex("\\d{4}-\\d{2}-\\d{2}").matches(cleaned)) return null
    return try {
        LocalDate.parse(cleaned)
    } catch (e: DateTimeParseException) {
        null
    }
}

private data class EventTimePolicy(
    val inferredStartTime: LocalTime?,
    val defaultDurationMillis: Long,
    val maximumDurationMillis: Long
) {
    companion object {
        private const val HOUR_MS = 3_600_000L

        fun from(event: EventExtraction): EventTimePolicy {
            val text = listOf(event.title, event.description, event.location)
                .joinToString(" ")
                .lowercase()

            return when {
                text.containsAny("festival", "fest") -> EventTimePolicy(
                    inferredStartTime = LocalTime.of(20, 0),
                    defaultDurationMillis = 4 * HOUR_MS,
                    maximumDurationMillis = 8 * HOUR_MS
                )
                text.containsAny(
                    "concert",
                    "show",
                    "live music",
                    "gig",
                    "lineup",
                    "line-up",
                    "dj set",
                    "party"
                ) -> EventTimePolicy(
                    inferredStartTime = LocalTime.of(20, 0),
                    defaultDurationMillis = 3 * HOUR_MS,
                    maximumDurationMillis = 6 * HOUR_MS
                )
                text.containsAny("theatre", "theater", "cinema", "film", "movie", "screening") -> EventTimePolicy(
                    inferredStartTime = LocalTime.of(19, 30),
                    defaultDurationMillis = 2 * HOUR_MS,
                    maximumDurationMillis = 5 * HOUR_MS
                )
                text.containsAny("dinner") -> EventTimePolicy(
                    inferredStartTime = LocalTime.of(19, 0),
                    defaultDurationMillis = 2 * HOUR_MS,
                    maximumDurationMillis = 4 * HOUR_MS
                )
                text.containsAny("lunch") -> EventTimePolicy(
                    inferredStartTime = LocalTime.of(12, 0),
                    defaultDurationMillis = HOUR_MS + HOUR_MS / 2,
                    maximumDurationMillis = 3 * HOUR_MS
                )
                text.containsAny("market", "picnic", "family day") -> EventTimePolicy(
                    inferredStartTime = LocalTime.of(14, 0),
                    defaultDurationMillis = 3 * HOUR_MS,
                    maximumDurationMillis = 6 * HOUR_MS
                )
                text.containsAny("appointment", "meeting", "class", "workshop") -> EventTimePolicy(
                    inferredStartTime = LocalTime.of(9, 0),
                    defaultDurationMillis = HOUR_MS,
                    maximumDurationMillis = 4 * HOUR_MS
                )
                else -> EventTimePolicy(
                    inferredStartTime = null,
                    defaultDurationMillis = HOUR_MS,
                    maximumDurationMillis = 24 * HOUR_MS
                )
            }
        }
    }
}

private fun String.containsAny(vararg needles: String): Boolean {
    return needles.any { contains(it) }
}

data class SourceAttachment(
    val path: String,
    val mimeType: String,
    val displayName: String
)

sealed class EventUpdateResult {
    data class Success(
        val event: Event,
        val syncedToSystem: Boolean,
        val warning: String? = null
    ) : EventUpdateResult()
}

/**
 * Result of event creation operation.
 */
sealed class EventResult {
    data class Success(val events: List<Event>) : EventResult() {
        val event: Event get() = events.first()
    }
    data class Failure(
        val message: String,
        val debug: AnalysisFailureDebug? = null
    ) : EventResult()
}

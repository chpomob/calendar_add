package com.calendaradd.usecase

import com.calendaradd.service.TextAnalysisService

/**
 * Use case for creating calendar events from various inputs.
 * This orchestrates the AI analysis and event creation flow.
 */
class CalendarUseCase(
    private val textAnalysisService: TextAnalysisService,
    private val eventDatabase: EventDatabase,
    private val userPreferences: UserPreferences = UserPreferences()
) {

    suspend fun createEvent(
        input: String,
        context: InputContext = InputContext(),
        sourceType: String = "text",
        onProgress: ((String) -> Unit)? = null
    ): EventResult? {
        return try {
            // Step 1: Analyze input with AI
            val analysis = textAnalysisService.analyzeInput(input, context)

            // Step 2: Create event from analysis
            val event = Event(
                title = analysis.title.ifEmpty { "Untitled Event" },
                description = analysis.description,
                startTime = analysis.startTime.ifEmpty { "" },
                endTime = analysis.endTime.ifEmpty { "" },
                location = analysis.location.ifEmpty { "" },
                attendees = analysis.attendees.ifEmpty { "" },
                sourceType = sourceType,
                aiConfidence = analysis.confidence ?: 1.0f,
                aiSourceModel = context.aiModel ?: ""
            )

            // Step 3: Save to database
            val eventId = eventDatabase.eventDao().insert(event)

            // Step 4: Update creation timestamp
            val updatedEvent = event.copy(id = eventId, createdAt = System.currentTimeMillis())
            eventDatabase.eventDao().update(updatedEvent)

            return EventResult.Success(updatedEvent)

        } catch (e: Exception) {
            return EventResult.Failure(e.message ?: "Unknown error occurred while creating event")
        }
    }

    suspend fun getAllEvents(): List<Event> = eventDatabase.eventDao().getAllEvents()

    suspend fun getUpcomingEvents(): List<Event> = eventDatabase.eventDao().getUpcomingEvents()

    suspend fun deleteEvent(eventId: Long) {
        eventDatabase.eventDao().deleteEvent(eventId)
    }
}

/**
 * Input context for event creation.
 */
data class InputContext(
    val timestamp: Long = System.currentTimeMillis(),
    val timezone: String = java.util.TimeZone.getDefault().id,
    val aiModel: String = "",
    val language: String = "en"
)

/**
 * Default user preferences.
 */
data class UserPreferences(
    val preferredTimezone: String = java.util.TimeZone.getDefault().id,
    val defaultReminder: Long = 60 * 60 * 1000L, // 1 hour
    val language: String = "en"
)

/**
 * Result of event creation operation.
 */
sealed class EventResult {
    data class Success(val event: Event) : EventResult()
    data class Failure(val message: String) : EventResult()
}

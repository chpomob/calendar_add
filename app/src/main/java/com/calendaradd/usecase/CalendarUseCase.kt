package com.calendaradd.usecase

import com.calendaradd.service.TextAnalysisService

/**
 * Use case for creating calendar events from various inputs.
 * This orchestrates the AI analysis and event creation flow.
 */
class CalendarUseCase(
    private val textAnalysisService: TextAnalysisService,
    private val eventDatabase: EventDatabase
) {

    suspend fun createEvent(
        input: String,
        context: InputContext = InputContext(),
        onProgress: ((String) -> Unit)? = null
    ): EventResult? {
        try {
            // Step 1: Analyze input with AI
            val analysis = textAnalysisService.analyzeInput(input, context)

            // Step 2: Create event from analysis
            val event = Event(
                title = analysis.title,
                description = analysis.description,
                startTime = analysis.startTime,
                endTime = analysis.endTime,
                location = analysis.location,
                attendees = analysis.attendees
            )

            // Step 3: Save to database
            eventDatabase.insert(event)

            return EventResult.Success(event)

        } catch (e: Exception) {
            return EventResult.Failure(e.message ?: "Unknown error")
        }
    }
}

data class InputContext(
    val currentDate: String = java.util.Calendar.getInstance().getTime().toString(),
    val timezone: String = "UTC",
    val userPreferences: UserPreferences = UserPreferences()
)

data class UserPreferences(
    val preferredTimezone: String = "UTC",
    val defaultReminder: Long = 60 * 60 * 1000L, // 1 hour
    val language: String = "en"
)

data class EventResult(val success: Boolean, val message: String?, val event: Event?)

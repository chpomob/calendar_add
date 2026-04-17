package com.calendaradd.usecase

/**
 * Database abstraction for storing calendar events.
 * Use Room or SQLite for production.
 */
class EventDatabase {

    /**
     * Insert an event into the database.
     */
    suspend fun insert(event: Event): Long {
        // TODO: Implement with Room/SQLite
        return 0
    }

    /**
     * Get all events.
     */
    suspend fun getAll(): List<Event> {
        // TODO: Implement
        return emptyList()
    }

    /**
     * Delete an event.
     */
    suspend fun delete(eventId: Long) {
        // TODO: Implement
    }
}

data class Event(
    val id: Long,
    val title: String,
    val description: String,
    val startTime: String,
    val endTime: String,
    val location: String,
    val attendees: List<String>,
    val createdAt: Long = System.currentTimeMillis()
)

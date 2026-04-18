package com.calendaradd.usecase

import androidx.room.*

/**
 * DAO for Event entity operations.
 */
@Dao
interface EventDao {

    // CRUD Operations
    @Query("SELECT * FROM events ORDER BY createdAt DESC")
    suspend fun getAllEvents(): List<Event>

    @Query("SELECT * FROM events WHERE id = :eventId")
    suspend fun getEventById(eventId: Long): Event?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: Event): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(events: List<Event>): List<Long>

    @Query("DELETE FROM events WHERE id = :eventId")
    suspend fun deleteEvent(eventId: Long)

    @Update
    suspend fun update(event: Event)

    // Utility Queries
    @Query("SELECT * FROM events ORDER BY startTime ASC")
    suspend fun getUpcomingEvents(): List<Event>

    @Query("SELECT * FROM events ORDER BY createdAt DESC LIMIT :limit")
    suspend fun getRecentEvents(limit: Int = 10): List<Event>

    @Query("SELECT * FROM events WHERE title LIKE '%' || :search || '%' OR description LIKE '%' || :search || '%'")
    suspend fun searchEvents(search: String): List<Event>

    @Query("SELECT COUNT(*) FROM events")
    suspend fun getEventCount(): Int

    @Query("DELETE FROM events")
    suspend fun deleteAll()

    // Helper methods
    fun getAllEventsFlow() = eventDatabaseFlowable { getAllEvents() }
    fun getUpcomingEventsFlow() = eventDatabaseFlowable { getUpcomingEvents() }
    fun getEventByIdFlow(eventId: Long) = eventDatabaseFlowable { getEventById(eventId) }
}

/**
 * Extends Room DAO to support Kotlin Flow queries.
 */
interface DatabaseFlowableDAO {
    fun <T> eventDatabaseFlowable(block: suspend () -> T): kotlinx.coroutines.flow.Flow<T> =
        kotlinx.coroutines.flow.flow { block() }
}

package com.calendaradd.usecase

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * DAO for Event entity operations.
 */
@Dao
interface EventDao {

    // CRUD Operations
    @Query("SELECT * FROM events ORDER BY createdAt DESC")
    fun getAllEvents(): Flow<List<Event>>

    @Query("SELECT * FROM events WHERE id = :eventId")
    suspend fun getEventById(eventId: Long): Event?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: Event): Long

    @Query("DELETE FROM events WHERE id = :eventId")
    suspend fun deleteEvent(eventId: Long)

    @Update
    suspend fun update(event: Event)

    // Utility Queries
    @Query("SELECT * FROM events WHERE startTime >= :now ORDER BY startTime ASC")
    fun getUpcomingEvents(now: Long = System.currentTimeMillis()): Flow<List<Event>>

    @Query("SELECT * FROM events WHERE title LIKE '%' || :search || '%' OR description LIKE '%' || :search || '%'")
    suspend fun searchEvents(search: String): List<Event>

    @Query("SELECT COUNT(*) FROM events WHERE sourceAttachmentPath = :path")
    suspend fun countEventsWithSourceAttachmentPath(path: String): Int

    @Query("DELETE FROM events")
    suspend fun deleteAll()
}

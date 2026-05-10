package com.calendaradd.usecase

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.Date

/**
 * Event entity for Room database.
 */
@Entity(tableName = "events")
data class Event(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val title: String = "",
    val description: String = "",

    val startTime: Long = 0L,
    val endTime: Long = 0L,

    val location: String = "",
    val attendees: String = "",

    val isFavorite: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),

    // Source of event creation
    val sourceType: String = "manual", // manual, text, audio, image
    val sourceAttachmentPath: String = "",
    val sourceAttachmentMimeType: String = "",
    val sourceAttachmentName: String = "",

    // AI extraction data
    val aiConfidence: Float = 1.0f,
    val aiSourceModel: String = "",

    // Android Calendar row created by optional system-calendar sync.
    val systemCalendarEventId: Long? = null
)

/**
 * Type-safe extension properties for Event.
 */
val Event.durationMillis: Long get() = if (endTime > startTime) endTime - startTime else 0L

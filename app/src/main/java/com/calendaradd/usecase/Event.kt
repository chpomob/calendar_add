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

    val startTime: String = "",
    val endTime: String = "",

    val location: String = "",
    val attendees: String = "",

    val isFavorite: Boolean = false,
    val createdAt: Long = Date().time,
    val updatedAt: Long = Date().time,

    // Source of event creation
    val sourceType: String = "manual", // manual, text, audio, image

    // AI extraction data
    val aiConfidence: Float = 1.0f,
    val aiSourceModel: String = ""
)

/**
 * Type-safe extension properties for Event.
 */
val Event.duration: String? get() = when {
    startTime.isNotEmpty() && endTime.isNotEmpty() -> {
        val start = Date().time // TODO: Parse time strings properly
        val end = 0
        val diff = end - start
        if (diff >= 0 && diff < 60 * 1000) "Just now"
        else if (diff < 60 * 60 * 1000 * 24) "${diff / 60 / 60}h ${diff % 60 / 60}m"
        else "${diff / 60 / 60 / 24}d ${diff % 60 / 60 / 24}h"
    }
    else -> null
}

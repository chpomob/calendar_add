package com.calendaradd.service

import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import java.util.TimeZone

/**
 * Service for interacting with the Android System Calendar.
 */
class SystemCalendarService(private val context: Context) {

    /**
     * Inserts an event into the primary calendar.
     */
    fun insertEvent(
        title: String,
        description: String,
        startTimeMillis: Long,
        endTimeMillis: Long,
        location: String = ""
    ): Long? {
        val calendarId = getPrimaryCalendarId() ?: return null
        
        val values = ContentValues().apply {
            put(CalendarContract.Events.DTSTART, startTimeMillis)
            put(CalendarContract.Events.DTEND, endTimeMillis)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DESCRIPTION, description)
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            put(CalendarContract.Events.EVENT_LOCATION, location)
        }

        return try {
            val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            uri?.lastPathSegment?.toLong()
        } catch (e: SecurityException) {
            e.printStackTrace()
            null
        }
    }

    private fun getPrimaryCalendarId(): Long? {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.IS_PRIMARY
        )
        
        val cursor = context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            projection,
            null,
            null,
            null
        )

        cursor?.use {
            while (it.moveToNext()) {
                val id = it.getLong(0)
                val isPrimary = it.getInt(1) != 0
                if (isPrimary) return id
            }
        }
        
        // Fallback to the first available calendar if no primary found
        cursor?.use {
            if (it.moveToFirst()) return it.getLong(0)
        }
        
        return null
    }
}

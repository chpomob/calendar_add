package com.calendaradd.service

import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import com.calendaradd.util.hasCalendarPermissions
import java.util.TimeZone

/**
 * Service for interacting with the Android System Calendar.
 */
class SystemCalendarService(private val context: Context) {

    data class CalendarInfo(val id: Long, val name: String, val accountName: String, val isPrimary: Boolean)

    /**
     * Fetches all available calendars on the device.
     */
    fun getAvailableCalendars(): List<CalendarInfo> {
        if (!hasCalendarPermissions()) return emptyList()
        val calendars = mutableListOf<CalendarInfo>()
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.IS_PRIMARY
        )
        
        return try {
            val cursor = context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                projection,
                null,
                null,
                null
            )

            cursor?.use {
                while (it.moveToNext()) {
                    calendars.add(
                        CalendarInfo(
                            id = it.getLong(0),
                            name = it.getString(1),
                            accountName = it.getString(2),
                            isPrimary = it.getInt(3) != 0
                        )
                    )
                }
            }
            calendars
        } catch (e: SecurityException) {
            e.printStackTrace()
            emptyList()
        }
    }

    /**
     * Inserts an event into a specific calendar.
     */
    fun insertEvent(
        calendarId: Long,
        title: String,
        description: String,
        startTimeMillis: Long,
        endTimeMillis: Long,
        location: String = ""
    ): Long? {
        if (!hasCalendarPermissions()) return null
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

    fun getPrimaryCalendarId(): Long? {
        return getAvailableCalendars().find { it.isPrimary }?.id 
            ?: getAvailableCalendars().firstOrNull()?.id
    }

    fun hasCalendarPermissions(): Boolean = context.hasCalendarPermissions()
}

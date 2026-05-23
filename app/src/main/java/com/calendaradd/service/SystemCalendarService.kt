package com.calendaradd.service

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.CalendarContract
import com.calendaradd.util.AppLog
import com.calendaradd.util.hasCalendarPermissions
import java.util.TimeZone

/**
 * Service for interacting with the Android System Calendar.
 */
class SystemCalendarService(private val context: Context) {
    companion object {
        private const val TAG = "SystemCalendarService"
    }

    data class CalendarInfo(val id: Long, val name: String, val accountName: String, val isPrimary: Boolean)

    /**
     * Fetches writable, syncing calendars on the device.
     * Filters by CALENDAR_ACCESS_LEVEL >= CONTRIBUTOR and SYNC_EVENTS = 1,
     * matching AOSP CalendarContract access-level constants.
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
        // Only query calendars the user can write to and that sync events.
        // AOSP CalendarContract defines CAL_ACCESS_CONTRIBUTOR = 500, CAL_ACCESS_EDITOR = 600, CAL_ACCESS_OWNER = 700.
        // See frameworks/base/core/java/android/provider/CalendarContract.java:414.
        val selection =
            "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ? " +
            "AND ${CalendarContract.Calendars.SYNC_EVENTS} = ?"
        val selectionArgs = arrayOf("500", "1")
        
        return try {
            val cursor = context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                projection,
                selection,
                selectionArgs,
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
            AppLog.w(TAG, "Missing calendar permission while querying calendars", e)
            emptyList()
        }
    }

    /**
     * Inserts an event into a specific calendar.
     * Returns the event ID if successful, null if failed.
     */
    fun insertEvent(
        calendarId: Long,
        title: String,
        description: String,
        startTimeMillis: Long,
        endTimeMillis: Long,
        location: String = ""
    ): Long? {
        if (!hasCalendarPermissions()) {
            AppLog.e(TAG, "Missing calendar permissions")
            return null
        }
        
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
            if (uri == null) {
                AppLog.e(TAG, "Failed to insert event: uri is null")
            }
            uri?.lastPathSegment?.toLong()
        } catch (e: Exception) {
            AppLog.e(TAG, "Error inserting calendar event", e)
            null
        }
    }

    fun updateEvent(
        systemEventId: Long,
        calendarId: Long,
        title: String,
        description: String,
        startTimeMillis: Long,
        endTimeMillis: Long,
        location: String = ""
    ): Boolean {
        if (!hasCalendarPermissions()) {
            AppLog.e(TAG, "Missing calendar permissions")
            return false
        }

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
            val uri = Uri.withAppendedPath(CalendarContract.Events.CONTENT_URI, systemEventId.toString())
            val updatedRows = context.contentResolver.update(uri, values, null, null)
            if (updatedRows <= 0) {
                AppLog.w(TAG, "No system calendar event updated for id=$systemEventId")
            }
            updatedRows > 0
        } catch (e: Exception) {
            AppLog.e(TAG, "Error updating calendar event", e)
            false
        }
    }

    fun getPrimaryCalendarId(): Long? {
        return getAvailableCalendars().find { it.isPrimary }?.id 
            ?: getAvailableCalendars().firstOrNull()?.id
    }

    fun hasCalendarPermissions(): Boolean = context.hasCalendarPermissions()

    /**
     * Deletes a system calendar event previously inserted by the app.
     * Must be called when the local Room event is deleted, to avoid orphaned
     * rows in CalendarProvider.
     */
    fun deleteEvent(systemEventId: Long): Boolean {
        if (!hasCalendarPermissions()) {
            AppLog.w(TAG, "Missing calendar permissions, cannot delete system event $systemEventId")
            return false
        }
        return try {
            val uri = Uri.withAppendedPath(CalendarContract.Events.CONTENT_URI, systemEventId.toString())
            val deleted = context.contentResolver.delete(uri, null, null)
            if (deleted <= 0) {
                AppLog.w(TAG, "No system calendar event deleted for id=$systemEventId")
            } else {
                AppLog.i(TAG, "Deleted system calendar event id=$systemEventId")
            }
            deleted > 0
        } catch (e: Exception) {
            AppLog.e(TAG, "Error deleting system calendar event id=$systemEventId", e)
            false
        }
    }
}

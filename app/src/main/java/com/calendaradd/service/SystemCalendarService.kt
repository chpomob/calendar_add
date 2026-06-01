package com.calendaradd.service

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.CalendarContract
import com.calendaradd.BuildConfig
import com.calendaradd.util.AppLog
import com.calendaradd.util.hasCalendarPermissions
import java.util.TimeZone

/**
 * Service for interacting with the Android System Calendar.
 */
class SystemCalendarService(
    private val context: Context,
    private val permissionChecker: (Context) -> Boolean = { it.hasCalendarPermissions() },
    private val eventsUri: Uri = CalendarContract.Events.CONTENT_URI,
    private val extendedPropertiesUri: Uri = CalendarContract.ExtendedProperties.CONTENT_URI,
    private val eventUriForId: (Long) -> Uri = { eventId ->
        Uri.withAppendedPath(CalendarContract.Events.CONTENT_URI, eventId.toString())
    },
    private val contentValuesFactory: () -> ContentValues = { ContentValues() }
) {
    companion object {
        private const val TAG = "SystemCalendarService"
        private const val APP_SYNC_MARKER_NAME = "com.calendaradd.sync_uid"
        private const val APP_SYNC_MARKER_VALUE_PREFIX = "calendaradd:"
    }

    data class CalendarInfo(val id: Long, val name: String, val accountName: String, val isPrimary: Boolean)

    fun getAvailableCalendars(): List<CalendarInfo> {
        if (!hasCalendarPermissions()) return emptyList()
        val calendars = mutableListOf<CalendarInfo>()
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.IS_PRIMARY
        )
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
                            name = it.getString(1).orEmpty(),
                            accountName = it.getString(2).orEmpty(),
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
        
        val values = contentValuesFactory().apply {
            put(CalendarContract.Events.DTSTART, startTimeMillis)
            put(CalendarContract.Events.DTEND, endTimeMillis)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DESCRIPTION, description)
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            put(CalendarContract.Events.EVENT_LOCATION, location)
        }

        return try {
            val uri = context.contentResolver.insert(eventsUri, values)
            if (uri == null) {
                AppLog.e(TAG, "Failed to insert event: uri is null")
            }
            val eventId = uri?.lastPathSegment?.toLongOrNull() ?: return null
            if (!insertAppSyncMarker(eventId)) {
                AppLog.w(
                    TAG,
                    "Inserted system event id=$eventId without app sync marker; provider may restrict ExtendedProperties"
                )
            }
            eventId
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

        val values = contentValuesFactory().apply {
            put(CalendarContract.Events.DTSTART, startTimeMillis)
            put(CalendarContract.Events.DTEND, endTimeMillis)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DESCRIPTION, description)
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            put(CalendarContract.Events.EVENT_LOCATION, location)
        }

        return try {
            when (markerStatus(systemEventId)) {
                MarkerStatus.PRESENT -> Unit
                MarkerStatus.MISSING -> {
                    AppLog.w(TAG, "Skipping update for system event id=$systemEventId because app sync marker is missing")
                    return false
                }
                MarkerStatus.UNAVAILABLE -> {
                    AppLog.w(
                        TAG,
                        "Updating system event id=$systemEventId without app sync marker verification"
                    )
                }
            }
            val uri = eventUriForId(systemEventId)
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

    fun hasCalendarPermissions(): Boolean = permissionChecker(context)

    fun deleteEvent(systemEventId: Long): Boolean {
        if (!hasCalendarPermissions()) {
            AppLog.w(TAG, "Missing calendar permissions, cannot delete system event $systemEventId")
            return false
        }
        return try {
            when (markerStatus(systemEventId)) {
                MarkerStatus.PRESENT -> Unit
                MarkerStatus.MISSING -> {
                    AppLog.w(TAG, "Skipping delete for system event id=$systemEventId because app sync marker is missing")
                    return false
                }
                MarkerStatus.UNAVAILABLE -> {
                    AppLog.w(
                        TAG,
                        "Deleting system event id=$systemEventId without app sync marker verification"
                    )
                }
            }
            val uri = eventUriForId(systemEventId)
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

    private fun insertAppSyncMarker(systemEventId: Long): Boolean {
        val values = contentValuesFactory().apply {
            put(CalendarContract.ExtendedProperties.EVENT_ID, systemEventId)
            put(CalendarContract.ExtendedProperties.NAME, APP_SYNC_MARKER_NAME)
            put(CalendarContract.ExtendedProperties.VALUE, appSyncMarkerValue())
        }
        return try {
            val uri = context.contentResolver.insert(extendedPropertiesUri, values)
            if (uri == null) {
                AppLog.e(TAG, "Failed to insert app sync marker: uri is null")
                false
            } else {
                true
            }
        } catch (e: Exception) {
            AppLog.e(TAG, "Error inserting app sync marker for system event id=$systemEventId", e)
            false
        }
    }

    private fun markerStatus(systemEventId: Long): MarkerStatus {
        val projection = arrayOf(CalendarContract.ExtendedProperties._ID)
        val selection =
            "${CalendarContract.ExtendedProperties.EVENT_ID} = ? " +
                "AND ${CalendarContract.ExtendedProperties.NAME} = ? " +
                "AND ${CalendarContract.ExtendedProperties.VALUE} = ?"
        val selectionArgs = arrayOf(
            systemEventId.toString(),
            APP_SYNC_MARKER_NAME,
            appSyncMarkerValue()
        )
        return try {
            val cursor = context.contentResolver.query(
                extendedPropertiesUri,
                projection,
                selection,
                selectionArgs,
                null
            ) ?: return MarkerStatus.UNAVAILABLE
            cursor.use {
                if (it.moveToFirst()) MarkerStatus.PRESENT else MarkerStatus.MISSING
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "Unable to verify app sync marker for system event id=$systemEventId", e)
            MarkerStatus.UNAVAILABLE
        }
    }

    private fun appSyncMarkerValue(): String {
        return APP_SYNC_MARKER_VALUE_PREFIX + BuildConfig.APPLICATION_ID
    }

    private enum class MarkerStatus {
        PRESENT,
        MISSING,
        UNAVAILABLE
    }
}

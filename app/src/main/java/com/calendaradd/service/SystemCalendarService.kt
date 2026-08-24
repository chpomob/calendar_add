package com.calendaradd.service

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.CalendarContract
import com.calendaradd.util.AppLog
import com.calendaradd.util.hasCalendarPermissions
import java.util.TimeZone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class SystemCalendarUpdateResult {
    UPDATED,
    MISSING,
    ERROR
}

/** R3: tri-state result of a sync-marker lookup. Distinguishes a confirmed absence
 * ([NotFound]) from an inconclusive query ([LookupError]) so callers never insert on top
 * of an unverified provider state and risk a duplicate. */
sealed interface SyncMarkerLookup {
    data class Found(val systemEventId: Long) : SyncMarkerLookup
    data object NotFound : SyncMarkerLookup
    data object LookupError : SyncMarkerLookup
}

/**
 * Service for interacting with the Android System Calendar.
 */
class SystemCalendarService(
    private val context: Context,
    private val permissionChecker: (Context) -> Boolean = { it.hasCalendarPermissions() },
    private val eventsUri: Uri = CalendarContract.Events.CONTENT_URI,
    private val eventUriForId: (Long) -> Uri = { eventId ->
        Uri.withAppendedPath(CalendarContract.Events.CONTENT_URI, eventId.toString())
    },
    private val contentValuesFactory: () -> ContentValues = { ContentValues() }
) {
    companion object {
        private const val TAG = "SystemCalendarService"

        /**
         * R3: sync marker appended to the provider DESCRIPTION on insert, embedding the local
         * Room event id. After a crash between the provider insert and the Room link persist,
         * [findEventBySyncMarker] uses it to adopt the orphaned provider row instead of
         * inserting a duplicate. Trailing `]` keeps ids unambiguous (`[calendar-add:5]` is not
         * a substring of `[calendar-add:55]`).
         */
        fun syncMarker(localEventId: Long): String = "\n[calendar-add:$localEventId]"
    }

    private val appContext = context.applicationContext

    data class CalendarInfo(val id: Long, val name: String, val accountName: String, val isPrimary: Boolean)

    suspend fun getAvailableCalendars(): List<CalendarInfo> = withContext(Dispatchers.IO) {
        if (!hasCalendarPermissions()) return@withContext emptyList()
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
        
        try {
            val cursor = appContext.contentResolver.query(
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

    suspend fun insertEvent(
        calendarId: Long,
        title: String,
        description: String,
        startTimeMillis: Long,
        endTimeMillis: Long,
        location: String = ""
    ): Long? = withContext(Dispatchers.IO) {
        if (!hasCalendarPermissions()) {
            AppLog.e(TAG, "Missing calendar permissions")
            return@withContext null
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

        try {
            val uri = appContext.contentResolver.insert(eventsUri, values)
            if (uri == null) {
                AppLog.e(TAG, "Failed to insert event: uri is null")
            }
            val eventId = uri?.lastPathSegment?.toLongOrNull()
            eventId
        } catch (e: Exception) {
            AppLog.e(TAG, "Error inserting calendar event", e)
            null
        }
    }

    /**
     * R3: finds a provider event previously inserted on behalf of local Room event
     * [localEventId], by the [syncMarker] embedded in DESCRIPTION. [SyncMarkerLookup.NotFound]
     * means the query succeeded and confirmed no marker row exists (normal first insert);
     * anything that cannot confirm absence — provider error, null cursor, missing
     * permission — returns [SyncMarkerLookup.LookupError], and callers must NOT insert
     * (a transient failure could be hiding an orphan; inserting would duplicate it).
     */
    suspend fun findEventBySyncMarker(calendarId: Long, localEventId: Long): SyncMarkerLookup =
        withContext(Dispatchers.IO) {
            if (!hasCalendarPermissions()) {
                AppLog.w(TAG, "Missing calendar permissions, cannot look up sync marker")
                return@withContext SyncMarkerLookup.LookupError
            }
            val selection = "${CalendarContract.Events.CALENDAR_ID} = ? AND " +
                "${CalendarContract.Events.DESCRIPTION} LIKE ?"
            val selectionArgs = arrayOf(calendarId.toString(), "%${syncMarker(localEventId)}")
            try {
                val cursor = appContext.contentResolver.query(
                    eventsUri,
                    arrayOf(CalendarContract.Events._ID),
                    selection,
                    selectionArgs,
                    null
                ) ?: run {
                    AppLog.e(TAG, "Null cursor looking up sync marker for local event id=$localEventId")
                    return@withContext SyncMarkerLookup.LookupError
                }
                cursor.use {
                    if (it.moveToFirst()) SyncMarkerLookup.Found(it.getLong(0))
                    else SyncMarkerLookup.NotFound
                }
            } catch (e: Exception) {
                AppLog.e(TAG, "Error looking up sync marker for local event id=$localEventId", e)
                SyncMarkerLookup.LookupError
            }
        }

    suspend fun updateEvent(
        systemEventId: Long,
        calendarId: Long,
        title: String,
        description: String,
        startTimeMillis: Long,
        endTimeMillis: Long,
        location: String = ""
    ): SystemCalendarUpdateResult = withContext(Dispatchers.IO) {
        if (!hasCalendarPermissions()) {
            AppLog.e(TAG, "Missing calendar permissions")
            return@withContext SystemCalendarUpdateResult.ERROR
        }

        val values = contentValuesFactory().apply {
            put(CalendarContract.Events.DTSTART, startTimeMillis)
            put(CalendarContract.Events.DTEND, endTimeMillis)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DESCRIPTION, description)
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            put(CalendarContract.Events.EVENT_LOCATION, location)
        }

        try {
            when (eventExistence(systemEventId)) {
                EventExistence.EXISTS -> Unit
                EventExistence.MISSING -> {
                    AppLog.w(TAG, "System event id=$systemEventId no longer exists — update skipped")
                    return@withContext SystemCalendarUpdateResult.MISSING
                }
                EventExistence.ERROR -> return@withContext SystemCalendarUpdateResult.ERROR
            }
            val uri = eventUriForId(systemEventId)
            val updatedRows = appContext.contentResolver.update(uri, values, null, null)
            if (updatedRows <= 0) {
                AppLog.w(TAG, "No system calendar event updated for id=$systemEventId")
            }
            if (updatedRows > 0) SystemCalendarUpdateResult.UPDATED else SystemCalendarUpdateResult.ERROR
        } catch (e: Exception) {
            AppLog.e(TAG, "Error updating calendar event", e)
            SystemCalendarUpdateResult.ERROR
        }
    }

    suspend fun getPrimaryCalendarId(): Long? {
        return getAvailableCalendars().find { it.isPrimary }?.id 
            ?: getAvailableCalendars().firstOrNull()?.id
    }

    suspend fun hasCalendarPermissions(): Boolean = withContext(Dispatchers.IO) {
        permissionChecker(appContext)
    }

    suspend fun deleteEvent(systemEventId: Long): Boolean = withContext(Dispatchers.IO) {
        if (!hasCalendarPermissions()) {
            AppLog.w(TAG, "Missing calendar permissions, cannot delete system event $systemEventId")
            return@withContext false
        }
        try {
            when (eventExistence(systemEventId)) {
                EventExistence.EXISTS -> Unit
                EventExistence.MISSING -> {
                    AppLog.w(TAG, "System event id=$systemEventId no longer exists — delete skipped")
                    return@withContext false
                }
                EventExistence.ERROR -> return@withContext false
            }
            val uri = eventUriForId(systemEventId)
            val deleted = appContext.contentResolver.delete(uri, null, null)
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

    private fun eventExistence(systemEventId: Long): EventExistence {
        val projection = arrayOf(CalendarContract.Events._ID)
        val uri = eventUriForId(systemEventId)
        return try {
            val cursor = appContext.contentResolver.query(uri, projection, null, null, null)
            if (cursor?.use { it.moveToFirst() } == true) {
                EventExistence.EXISTS
            } else {
                EventExistence.MISSING
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "Unable to verify system event id=$systemEventId exists", e)
            EventExistence.ERROR
        }
    }

}

private enum class EventExistence {
    EXISTS,
    MISSING,
    ERROR
}

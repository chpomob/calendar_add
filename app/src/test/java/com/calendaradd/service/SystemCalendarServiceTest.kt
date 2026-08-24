package com.calendaradd.service

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.database.Cursor
import android.net.Uri
import android.provider.CalendarContract
import com.calendaradd.usecase.PreferencesManager
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class SystemCalendarServiceTest {
    private val context = mockk<Context>(relaxed = true)
    private val resolver = mockk<ContentResolver>(relaxed = true)
    private val eventsUri = mockk<Uri>(relaxed = true)
    private val eventUri = mockk<Uri>(relaxed = true)
    private val insertedEventUri = mockk<Uri>(relaxed = true)
    private val remindersUri = mockk<Uri>(relaxed = true)
    private val attendeesUri = mockk<Uri>(relaxed = true)

    @Test
    fun `insertEvent should return event id`() = runBlocking {
        val service = service()
        every { insertedEventUri.lastPathSegment } returns "42"
        every { resolver.insert(eventsUri, any()) } returns insertedEventUri

        val eventId = service.insertEvent(
            calendarId = 7L,
            title = "Title",
            description = "Description",
            startTimeMillis = 1_000L,
            endTimeMillis = 2_000L
        )

        assertEquals(42L, eventId)
    }

    @Test
    fun `updateEvent should refuse event when it no longer exists`() = runBlocking {
        val service = service()
        everyEventExists(doesExist = false)

        val updated = service.updateEvent(
            systemEventId = 42L,
            calendarId = 7L,
            title = "Title",
            description = "Description",
            startTimeMillis = 1_000L,
            endTimeMillis = 2_000L
        )

        assertEquals(SystemCalendarUpdateResult.MISSING, updated)
        verify(exactly = 0) { resolver.update(any(), any(), any(), any()) }
    }

    @Test
    fun `deleteEvent should refuse event when it no longer exists`() = runBlocking {
        val service = service()
        everyEventExists(doesExist = false)

        assertFalse(service.deleteEvent(42L))

        verify(exactly = 0) { resolver.delete(any(), any(), any()) }
    }

    @Test
    fun `updateEvent should allow event when it exists`() = runBlocking {
        val service = service()
        everyEventExists(doesExist = true)
        every { resolver.update(eventUri, any(), null, null) } returns 1

        val updated = service.updateEvent(
            systemEventId = 42L,
            calendarId = 7L,
            title = "Title",
            description = "Description",
            startTimeMillis = 1_000L,
            endTimeMillis = 2_000L
        )

        assertEquals(SystemCalendarUpdateResult.UPDATED, updated)
    }

    private fun service(): SystemCalendarService {
        every { context.applicationContext } returns context
        every { context.contentResolver } returns resolver
        return SystemCalendarService(
            context = context,
            permissionChecker = { true },
            eventsUri = eventsUri,
            eventUriForId = { eventUri },
            remindersUri = remindersUri,
            attendeesUri = attendeesUri,
            contentValuesFactory = { mockk<ContentValues>(relaxed = true) }
        )
    }

    // ---- R3/AC6-AC7: crash-adopt via description sync marker ----

    @Test
    fun `syncMarker embeds local event id and keeps ids unambiguous`() {
        assertEquals("\n[calendar-add:5]", SystemCalendarService.syncMarker(5L))
        assertEquals("\n[calendar-add:55]", SystemCalendarService.syncMarker(55L))
        // AC7: one id's marker must never be a substring of another's, or the LIKE lookup
        // would merge two distinct local events into one provider row.
        assertFalse(SystemCalendarService.syncMarker(55L).contains(SystemCalendarService.syncMarker(5L)))
    }

    @Test
    fun `findEventBySyncMarker should return Found when marker row exists`() = runBlocking {
        everyMarkerCursorReturns(77L)

        assertEquals(SyncMarkerLookup.Found(77L), service().findEventBySyncMarker(calendarId = 7L, localEventId = 5L))
    }

    @Test
    fun `findEventBySyncMarker should return NotFound when no marker row exists`() = runBlocking {
        val cursor = mockk<Cursor>()
        every { cursor.moveToFirst() } returns false
        every { cursor.close() } just Runs
        every { resolver.query(eventsUri, any(), any(), any(), any()) } returns cursor

        assertEquals(SyncMarkerLookup.NotFound, service().findEventBySyncMarker(calendarId = 7L, localEventId = 5L))
    }

    @Test
    fun `findEventBySyncMarker should return LookupError when provider query throws`() = runBlocking {
        every { resolver.query(eventsUri, any(), any(), any(), any()) } throws RuntimeException("provider down")

        assertEquals(SyncMarkerLookup.LookupError, service().findEventBySyncMarker(calendarId = 7L, localEventId = 5L))
    }

    @Test
    fun `findEventBySyncMarker should return LookupError when cursor is null`() = runBlocking {
        every { resolver.query(eventsUri, any(), any(), any(), any()) } returns null

        assertEquals(SyncMarkerLookup.LookupError, service().findEventBySyncMarker(calendarId = 7L, localEventId = 5L))
    }

    @Test
    fun `findEventBySyncMarker should query calendar and marker via LIKE`() = runBlocking {
        everyMarkerCursorReturns(77L)

        service().findEventBySyncMarker(calendarId = 7L, localEventId = 5L)

        verify {
            resolver.query(
                eventsUri,
                arrayOf(CalendarContract.Events._ID),
                "${CalendarContract.Events.CALENDAR_ID} = ? AND ${CalendarContract.Events.DESCRIPTION} LIKE ?",
                arrayOf("7", "%\n[calendar-add:5]"),
                null
            )
        }
    }

    @Test
    fun `crash-adopt - insertEvent embeds the sync marker in the provider description`() = runBlocking {
        val descSlot = slot<String>()
        every { context.applicationContext } returns context
        every { context.contentResolver } returns resolver
        val service = SystemCalendarService(
            context = context,
            permissionChecker = { true },
            eventsUri = eventsUri,
            eventUriForId = { eventUri },
            remindersUri = remindersUri,
            attendeesUri = attendeesUri,
            contentValuesFactory = {
                mockk<ContentValues>(relaxed = true) {
                    every { put(CalendarContract.Events.DESCRIPTION, capture(descSlot)) } just Runs
                }
            }
        )
        every { resolver.insert(eventsUri, any()) } answers {
            mockk<Uri> { every { lastPathSegment } returns "100" }
        }

        val insertedId = service.insertEvent(
            calendarId = 7L,
            title = "Title",
            description = "Description\n[calendar-add:1]",
            startTimeMillis = 1_000L,
            endTimeMillis = 2_000L
        )

        // The marker a later findEventBySyncMarker would look for reached the provider row.
        assertEquals(100L, insertedId)
        assertEquals("Description\n[calendar-add:1]", descSlot.captured)
    }

    // ---- R4/AC8 & R7/AC12: reminder and attendee child rows ----

    @Test
    fun `insertEvent writes exactly one reminder row with configured minutes`() = runBlocking {
        val service = service()
        val reminderValues = slot<ContentValues>()
        every { insertedEventUri.lastPathSegment } returns "42"
        every { resolver.insert(eventsUri, any()) } returns insertedEventUri
        every { resolver.insert(remindersUri, capture(reminderValues)) } returns mockk()

        service.insertEvent(
            calendarId = 7L,
            title = "Title",
            description = "Description",
            startTimeMillis = 1_000L,
            endTimeMillis = 2_000L,
            reminderMinutes = 15
        )

        verify(exactly = 1) { resolver.insert(remindersUri, any()) }
        verify(exactly = 0) { resolver.insert(attendeesUri, any()) }
        verify { reminderValues.captured.put(CalendarContract.Reminders.EVENT_ID, 42L) }
        verify { reminderValues.captured.put(CalendarContract.Reminders.MINUTES, 15) }
        verify { reminderValues.captured.put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT) }
    }

    @Test
    fun `insertEvent writes one attendee row per attendee`() = runBlocking {
        val service = service()
        val attendeeValues = mutableListOf<ContentValues>()
        val attendeeNames = mutableListOf<String>()
        every { insertedEventUri.lastPathSegment } returns "42"
        every { resolver.insert(eventsUri, any()) } returns insertedEventUri
        every { resolver.insert(attendeesUri, capture(attendeeValues)) } returns mockk()

        service.insertEvent(
            calendarId = 7L,
            title = "Title",
            description = "Description",
            startTimeMillis = 1_000L,
            endTimeMillis = 2_000L,
            attendees = listOf("Alice", "Bob")
        )

        verify(exactly = 2) { resolver.insert(attendeesUri, any()) }
        verify(exactly = 0) { resolver.insert(remindersUri, any()) }
        assertEquals(2, attendeeValues.size)
        attendeeValues.forEach { values ->
            verify { values.put(CalendarContract.Attendees.EVENT_ID, 42L) }
            verify {
                values.put(
                    CalendarContract.Attendees.ATTENDEE_RELATIONSHIP,
                    CalendarContract.Attendees.RELATIONSHIP_ATTENDEE
                )
            }
            verify { values.put(CalendarContract.Attendees.ATTENDEE_NAME, capture(attendeeNames)) }
            // A1: the required provider fields must be populated so conforming providers
            // accept the row rather than rejecting it.
            verify { values.put(CalendarContract.Attendees.ATTENDEE_EMAIL, "") }
            verify { values.put(CalendarContract.Attendees.ATTENDEE_TYPE, CalendarContract.Attendees.TYPE_NONE) }
            verify { values.put(CalendarContract.Attendees.ATTENDEE_STATUS, CalendarContract.Attendees.ATTENDEE_STATUS_NONE) }
        }
        assertEquals(listOf("Alice", "Bob"), attendeeNames)
    }

    @Test
    fun `updateEvent replaces reminder and attendee rows on re-sync`() = runBlocking {
        val service = service()
        val reminderValues = mutableListOf<ContentValues>()
        val attendeeValues = mutableListOf<ContentValues>()
        everyEventExists(doesExist = true)
        every { resolver.update(eventUri, any(), null, null) } returns 1
        every { resolver.insert(remindersUri, capture(reminderValues)) } returns mockk()
        every { resolver.insert(attendeesUri, capture(attendeeValues)) } returns mockk()

        val result = service.updateEvent(
            systemEventId = 42L,
            calendarId = 7L,
            title = "Title",
            description = "Description",
            startTimeMillis = 1_000L,
            endTimeMillis = 2_000L,
            attendees = listOf("Alice", "Bob"),
            reminderMinutes = 10
        )

        assertEquals(SystemCalendarUpdateResult.UPDATED, result)
        // AC8: re-sync converges to exactly one reminder row.
        verify(exactly = 1) { resolver.insert(remindersUri, any()) }
        // AC12: N attendees → N rows, and stale rows are cleared before re-insertion.
        verify(exactly = 2) { resolver.insert(attendeesUri, any()) }
        verify(exactly = 1) { resolver.delete(remindersUri, any(), any()) }
        verify(exactly = 1) { resolver.delete(attendeesUri, any(), any()) }
        assertEquals(1, reminderValues.size)
        assertEquals(2, attendeeValues.size)
    }

    @Test
    fun `insertEvent returns null when a child row insert is rejected`() = runBlocking {
        val service = service()
        every { insertedEventUri.lastPathSegment } returns "42"
        every { resolver.insert(eventsUri, any()) } returns insertedEventUri
        every { resolver.insert(remindersUri, any()) } throws RuntimeException("provider rejected reminder")

        val eventId = service.insertEvent(
            calendarId = 7L,
            title = "Title",
            description = "Description",
            startTimeMillis = 1_000L,
            endTimeMillis = 2_000L,
            reminderMinutes = 15
        )

        // A2: a failed child-row write must surface as a failed insert, not a returned id.
        assertNull(eventId)
    }

    @Test
    fun `updateEvent returns ERROR when a child row write fails`() = runBlocking {
        val service = service()
        everyEventExists(doesExist = true)
        every { resolver.update(eventUri, any(), null, null) } returns 1
        every { resolver.insert(remindersUri, any()) } throws RuntimeException("provider rejected reminder")

        val result = service.updateEvent(
            systemEventId = 42L,
            calendarId = 7L,
            title = "Title",
            description = "Description",
            startTimeMillis = 1_000L,
            endTimeMillis = 2_000L,
            reminderMinutes = 10
        )

        // A2: a failed child-row write must not be reported as UPDATED.
        assertEquals(SystemCalendarUpdateResult.ERROR, result)
    }

    @Test
    fun `insertEvent returns null when a child row insert is rejected with a null uri`() = runBlocking {
        val service = service()
        every { insertedEventUri.lastPathSegment } returns "42"
        every { resolver.insert(eventsUri, any()) } returns insertedEventUri
        // A conforming provider rejects the reminder insert by returning null, not throwing.
        every { resolver.insert(remindersUri, any()) } returns null

        val eventId = service.insertEvent(
            calendarId = 7L,
            title = "Title",
            description = "Description",
            startTimeMillis = 1_000L,
            endTimeMillis = 2_000L,
            reminderMinutes = 15
        )

        // A2: a null-uri child insert must surface as a failed insert, not a returned id.
        assertNull(eventId)
    }

    @Test
    fun `insertEvent does not insert a reminder when clearing existing reminders fails`() = runBlocking {
        val service = service()
        every { insertedEventUri.lastPathSegment } returns "42"
        every { resolver.insert(eventsUri, any()) } returns insertedEventUri
        every { resolver.delete(remindersUri, any(), any()) } throws RuntimeException("provider down")

        val eventId = service.insertEvent(
            calendarId = 7L,
            title = "Title",
            description = "Description",
            startTimeMillis = 1_000L,
            endTimeMillis = 2_000L,
            reminderMinutes = 15
        )

        // A2: a failed clear must abort before inserting, or a retry would double the reminder
        // row and violate AC8.
        assertNull(eventId)
        verify(exactly = 0) { resolver.insert(remindersUri, any()) }
    }

    // ---- R4b/AC8b-AC8c: positive reminder on every sync, 0 = explicit opt-out ----

    @Test
    fun `insertEvent with no explicit reminder writes the positive default`() = runBlocking {
        everyDefaultReminderMinutes(PreferencesManager.DEFAULT_REMINDER_MINUTES)
        val service = service()
        val reminderValues = slot<ContentValues>()
        every { insertedEventUri.lastPathSegment } returns "42"
        every { resolver.insert(eventsUri, any()) } returns insertedEventUri
        every { resolver.insert(remindersUri, capture(reminderValues)) } returns mockk()

        service.insertEvent(
            calendarId = 7L,
            title = "Title",
            description = "Description",
            startTimeMillis = 1_000L,
            endTimeMillis = 2_000L
        )

        // AC8b: the default sync still writes exactly one reminder, with positive minutes.
        verify(exactly = 1) { resolver.insert(remindersUri, any()) }
        verify { reminderValues.captured.put(CalendarContract.Reminders.MINUTES, PreferencesManager.DEFAULT_REMINDER_MINUTES) }
        verify { reminderValues.captured.put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT) }
    }

    @Test
    fun `insertEvent with reminder zero clears stale reminders and inserts none`() = runBlocking {
        val service = service()
        every { insertedEventUri.lastPathSegment } returns "42"
        every { resolver.insert(eventsUri, any()) } returns insertedEventUri

        val eventId = service.insertEvent(
            calendarId = 7L,
            title = "Title",
            description = "Description",
            startTimeMillis = 1_000L,
            endTimeMillis = 2_000L,
            reminderMinutes = 0
        )

        // AC8c: 0 is the explicit opt-out — no reminder row is written, but a stale row from
        // an earlier sync is cleared rather than left behind.
        assertEquals(42L, eventId)
        verify(exactly = 1) { resolver.delete(remindersUri, any(), any()) }
        verify(exactly = 0) { resolver.insert(remindersUri, any()) }
    }

    @Test
    fun `updateEvent with reminder zero clears stale reminders and inserts none`() = runBlocking {
        val service = service()
        everyEventExists(doesExist = true)
        every { resolver.update(eventUri, any(), null, null) } returns 1

        val result = service.updateEvent(
            systemEventId = 42L,
            calendarId = 7L,
            title = "Title",
            description = "Description",
            startTimeMillis = 1_000L,
            endTimeMillis = 2_000L,
            reminderMinutes = 0
        )

        assertEquals(SystemCalendarUpdateResult.UPDATED, result)
        verify(exactly = 1) { resolver.delete(remindersUri, any(), any()) }
        verify(exactly = 0) { resolver.insert(remindersUri, any()) }
    }

    private fun everyMarkerCursorReturns(id: Long) {
        val cursor = mockk<Cursor>()
        every { cursor.moveToFirst() } returns true
        every { cursor.getLong(0) } returns id
        every { cursor.close() } just Runs
        every { resolver.query(eventsUri, any(), any(), any(), any()) } returns cursor
    }

    private fun everyDefaultReminderMinutes(minutes: Int) {
        val prefs = mockk<SharedPreferences>()
        every { prefs.getInt(any(), any()) } returns minutes
        every { context.getSharedPreferences("calendar_add_prefs", Context.MODE_PRIVATE) } returns prefs
    }

    private fun everyEventExists(doesExist: Boolean) {
        val cursor = mockk<Cursor>()
        every { cursor.moveToFirst() } returns doesExist
        every { cursor.close() } just Runs
        every { resolver.query(eventUri, any(), any(), any(), any()) } returns cursor
    }
}

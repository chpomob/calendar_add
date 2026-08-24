package com.calendaradd.service

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.CalendarContract
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SystemCalendarServiceTest {
    private val context = mockk<Context>(relaxed = true)
    private val resolver = mockk<ContentResolver>(relaxed = true)
    private val eventsUri = mockk<Uri>(relaxed = true)
    private val eventUri = mockk<Uri>(relaxed = true)
    private val insertedEventUri = mockk<Uri>(relaxed = true)

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

    private fun everyMarkerCursorReturns(id: Long) {
        val cursor = mockk<Cursor>()
        every { cursor.moveToFirst() } returns true
        every { cursor.getLong(0) } returns id
        every { cursor.close() } just Runs
        every { resolver.query(eventsUri, any(), any(), any(), any()) } returns cursor
    }

    private fun everyEventExists(doesExist: Boolean) {
        val cursor = mockk<Cursor>()
        every { cursor.moveToFirst() } returns doesExist
        every { cursor.close() } just Runs
        every { resolver.query(eventUri, any(), any(), any(), any()) } returns cursor
    }
}

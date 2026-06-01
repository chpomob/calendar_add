package com.calendaradd.service

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemCalendarServiceTest {
    private val context = mockk<Context>(relaxed = true)
    private val resolver = mockk<ContentResolver>(relaxed = true)
    private val eventsUri = mockk<Uri>(relaxed = true)
    private val extendedPropertiesUri = mockk<Uri>(relaxed = true)
    private val eventUri = mockk<Uri>(relaxed = true)
    private val insertedEventUri = mockk<Uri>(relaxed = true)

    @Test
    fun `insertEvent should return event id when marker insert succeeds`() {
        val service = service()
        every { insertedEventUri.lastPathSegment } returns "42"
        every { resolver.insert(eventsUri, any()) } returns insertedEventUri
        every { resolver.insert(extendedPropertiesUri, any()) } returns mockk(relaxed = true)

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
    fun `insertEvent should keep event when marker insert is unavailable`() {
        val service = service()
        every { insertedEventUri.lastPathSegment } returns "42"
        every { resolver.insert(eventsUri, any()) } returns insertedEventUri
        every { resolver.insert(extendedPropertiesUri, any()) } returns null

        val eventId = service.insertEvent(
            calendarId = 7L,
            title = "Title",
            description = "Description",
            startTimeMillis = 1_000L,
            endTimeMillis = 2_000L
        )

        assertEquals(42L, eventId)
        verify(exactly = 0) { resolver.delete(any(), any(), any()) }
    }

    @Test
    fun `updateEvent should refuse event when marker is explicitly absent`() {
        val service = service()
        everyMarkerQueryReturns(hasMarker = false)

        val updated = service.updateEvent(
            systemEventId = 42L,
            calendarId = 7L,
            title = "Title",
            description = "Description",
            startTimeMillis = 1_000L,
            endTimeMillis = 2_000L
        )

        assertFalse(updated)
        verify(exactly = 0) { resolver.update(any(), any(), any(), any()) }
    }

    @Test
    fun `deleteEvent should refuse event when marker is explicitly absent`() {
        val service = service()
        everyMarkerQueryReturns(hasMarker = false)

        assertFalse(service.deleteEvent(42L))

        verify(exactly = 0) { resolver.delete(any(), any(), any()) }
    }

    @Test
    fun `updateEvent should allow event when marker query is unavailable`() {
        val service = service()
        every { resolver.query(extendedPropertiesUri, any(), any(), any(), any()) } returns null
        every { resolver.update(eventUri, any(), null, null) } returns 1

        val updated = service.updateEvent(
            systemEventId = 42L,
            calendarId = 7L,
            title = "Title",
            description = "Description",
            startTimeMillis = 1_000L,
            endTimeMillis = 2_000L
        )

        assertTrue(updated)
    }

    private fun service(): SystemCalendarService {
        every { context.contentResolver } returns resolver
        return SystemCalendarService(
            context = context,
            permissionChecker = { true },
            eventsUri = eventsUri,
            extendedPropertiesUri = extendedPropertiesUri,
            eventUriForId = { eventUri },
            contentValuesFactory = { mockk<ContentValues>(relaxed = true) }
        )
    }

    private fun everyMarkerQueryReturns(hasMarker: Boolean) {
        val cursor = mockk<Cursor>()
        every { cursor.moveToFirst() } returns hasMarker
        every { cursor.close() } just Runs
        every { resolver.query(extendedPropertiesUri, any(), any(), any(), any()) } returns cursor
    }
}

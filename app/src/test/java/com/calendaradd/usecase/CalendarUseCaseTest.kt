package com.calendaradd.usecase

import android.graphics.Bitmap
import com.calendaradd.service.AnalysisDebugSnapshot
import com.calendaradd.service.EventExtraction
import com.calendaradd.service.SystemCalendarService
import com.calendaradd.service.TextAnalysisService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class CalendarUseCaseTest {

    private lateinit var textAnalysisService: TextAnalysisService
    private lateinit var eventDatabase: EventDatabase
    private lateinit var eventDao: EventDao
    private lateinit var systemCalendarService: SystemCalendarService
    private lateinit var preferencesManager: PreferencesManager
    private lateinit var useCase: CalendarUseCase

    @Before
    fun setup() {
        textAnalysisService = mockk()
        eventDatabase = mockk()
        eventDao = mockk()
        systemCalendarService = mockk()
        preferencesManager = mockk()

        every { eventDatabase.eventDao() } returns eventDao
        every { preferencesManager.isAutoAddEnabled } returns false
        every { preferencesManager.targetCalendarId } returns -1L
        every { preferencesManager.isFailureJsonDebugEnabled } returns false
        every { textAnalysisService.consumeLastDebugSnapshot() } returns null

        useCase = CalendarUseCase(
            textAnalysisService = textAnalysisService,
            eventDatabase = eventDatabase,
            systemCalendarService = systemCalendarService,
            preferencesManager = preferencesManager
        )
    }

    @Test
    fun `createEventFromImage should fail when extraction is empty`() = runBlocking {
        val bitmap = mockk<Bitmap>(relaxed = true)
        coEvery { textAnalysisService.analyzeImage(bitmap, any()) } returns listOf(
            EventExtraction(
                title = "",
                description = "",
                startTime = "",
                endTime = "",
                location = "",
                attendees = emptyList()
            )
        )

        val result = useCase.createEventFromImage(bitmap)

        assertTrue(result is EventResult.Failure)
        assertEquals(
            "Could not extract enough event details from the image input.",
            (result as EventResult.Failure).message
        )
        coVerify(exactly = 0) { eventDao.insert(any()) }
    }

    @Test
    fun `createEventFromText should persist multiple extracted events`() = runBlocking {
        coEvery { eventDao.insert(any()) } returnsMany listOf(1L, 2L)
        coEvery { textAnalysisService.analyzeText(any(), any()) } returns listOf(
            EventExtraction(
                title = "Lunch",
                description = "",
                startTime = "2026-04-20T12:00:00",
                endTime = "",
                location = "",
                attendees = emptyList()
            ),
            EventExtraction(
                title = "Dentist",
                description = "",
                startTime = "2026-04-21T09:00:00",
                endTime = "",
                location = "",
                attendees = emptyList()
            )
        )

        val result = useCase.createEventFromText("Lunch tomorrow and dentist Tuesday")

        assertTrue(result is EventResult.Success)
        result as EventResult.Success
        assertEquals(2, result.events.size)
        assertEquals("Lunch", result.events[0].title)
        assertEquals("Dentist", result.events[1].title)
        coVerify(exactly = 2) { eventDao.insert(any()) }
    }

    @Test
    fun `createEventFromImage should persist source attachment metadata`() = runBlocking {
        val bitmap = mockk<Bitmap>(relaxed = true)
        val insertedEvent = slot<Event>()
        coEvery { eventDao.insert(capture(insertedEvent)) } returns 42L
        coEvery { textAnalysisService.analyzeImage(bitmap, any()) } returns listOf(
            EventExtraction(
                title = "Flyer concert",
                description = "Original flyer should be linked",
                startTime = "2026-05-07T19:30:00",
                endTime = "",
                location = "Venue",
                attendees = emptyList()
            )
        )
        val sourceAttachment = SourceAttachment(
            path = "/app/source/source-image.jpg",
            mimeType = "image/jpeg",
            displayName = "shared-flyer.jpg"
        )

        val result = useCase.createEventFromImage(bitmap, sourceAttachment = sourceAttachment)

        assertTrue(result is EventResult.Success)
        result as EventResult.Success
        assertEquals("/app/source/source-image.jpg", insertedEvent.captured.sourceAttachmentPath)
        assertEquals("image/jpeg", insertedEvent.captured.sourceAttachmentMimeType)
        assertEquals("shared-flyer.jpg", insertedEvent.captured.sourceAttachmentName)
        assertEquals("/app/source/source-image.jpg", result.event.sourceAttachmentPath)
    }

    @Test
    fun `deleteEvent should delete unreferenced source attachment file`() = runBlocking {
        val sourceFile = File.createTempFile("calendar-add-source", ".jpg").apply {
            writeText("source")
        }
        coEvery { eventDao.getEventById(7L) } returns Event(
            id = 7L,
            title = "Source event",
            startTime = 1L,
            endTime = 2L,
            sourceAttachmentPath = sourceFile.absolutePath,
            sourceAttachmentMimeType = "image/jpeg",
            sourceAttachmentName = "source.jpg"
        )
        coEvery { eventDao.deleteEvent(7L) } returns Unit
        coEvery { eventDao.countEventsWithSourceAttachmentPath(sourceFile.absolutePath) } returns 0

        useCase.deleteEvent(7L)

        assertFalse(sourceFile.exists())
    }

    @Test
    fun `createEventFromText should fail when extracted date is invalid`() = runBlocking {
        coEvery { textAnalysisService.analyzeText(any(), any()) } returns listOf(
            EventExtraction(
                title = "Board meeting",
                description = "Quarterly review",
                startTime = "next Blursday after lunch",
                endTime = "",
                location = "HQ",
                attendees = emptyList()
            )
        )

        val result = useCase.createEventFromText("Board meeting next Blursday after lunch")

        assertTrue(result is EventResult.Failure)
        assertEquals(
            "Could not extract a valid event date and time from the text input.",
            (result as EventResult.Failure).message
        )
        coVerify(exactly = 0) { eventDao.insert(any()) }
    }

    @Test
    fun `createEventFromText should attach debug response when enabled and extracted date is invalid`() = runBlocking {
        every { preferencesManager.isFailureJsonDebugEnabled } returns true
        every {
            textAnalysisService.consumeLastDebugSnapshot()
        } returns AnalysisDebugSnapshot(
            traceId = "req-debug",
            rawResponse = """{"events":[{"title":"Board meeting","startTime":"tomorrow afternoon"}]}""",
            cleanedResponse = """{"events":[{"title":"Board meeting","startTime":"tomorrow afternoon"}]}""",
            issue = "The model returned a relative date instead of an absolute timestamp."
        )
        coEvery { textAnalysisService.analyzeText(any(), any()) } returns listOf(
            EventExtraction(
                title = "Board meeting",
                description = "Quarterly review",
                startTime = "tomorrow afternoon",
                endTime = "",
                location = "HQ",
                attendees = emptyList()
            )
        )

        val result = useCase.createEventFromText("Board meeting tomorrow afternoon")

        assertTrue(result is EventResult.Failure)
        result as EventResult.Failure
        assertEquals(
            "Could not extract a valid event date and time from the text input.",
            result.message
        )
        assertTrue(result.debug?.body?.contains("Model response:") == true)
        assertTrue(result.debug?.body?.contains("tomorrow afternoon") == true)
    }

    @Test
    fun `createEventFromText should skip invalid extracted events when others are valid`() = runBlocking {
        coEvery { eventDao.insert(any()) } returns 1L
        coEvery { textAnalysisService.analyzeText(any(), any()) } returns listOf(
            EventExtraction(
                title = "Broken event",
                description = "",
                startTime = "someday soon",
                endTime = "",
                location = "",
                attendees = emptyList()
            ),
            EventExtraction(
                title = "Valid lunch",
                description = "",
                startTime = "2026-04-22T12:00:00",
                endTime = "",
                location = "",
                attendees = emptyList()
            )
        )

        val result = useCase.createEventFromText("One broken date and one valid lunch")

        assertTrue(result is EventResult.Success)
        result as EventResult.Success
        assertEquals(1, result.events.size)
        assertEquals("Valid lunch", result.events.single().title)
        coVerify(exactly = 1) { eventDao.insert(any()) }
    }
}

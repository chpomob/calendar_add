package com.calendaradd.usecase

import android.graphics.Bitmap
import com.calendaradd.service.EventExtraction
import com.calendaradd.service.SystemCalendarService
import com.calendaradd.service.TextAnalysisService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

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

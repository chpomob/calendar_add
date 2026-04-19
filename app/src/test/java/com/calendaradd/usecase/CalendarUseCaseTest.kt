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

        useCase = CalendarUseCase(
            textAnalysisService = textAnalysisService,
            eventDatabase = eventDatabase,
            systemCalendarService = systemCalendarService,
            preferencesManager = preferencesManager
        )
    }

    @Test
    fun `createEventFromImage should fail when extraction is empty`() = runBlocking {
        val bitmap = mockk<Bitmap>()
        coEvery { textAnalysisService.analyzeImage(bitmap, any()) } returns EventExtraction(
            title = "",
            description = "",
            startTime = "",
            endTime = "",
            location = "",
            attendees = emptyList()
        )

        val result = useCase.createEventFromImage(bitmap)

        assertTrue(result is EventResult.Failure)
        assertEquals(
            "Could not extract enough event details from the image input.",
            (result as EventResult.Failure).message
        )
        coVerify(exactly = 0) { eventDao.insert(any()) }
    }
}

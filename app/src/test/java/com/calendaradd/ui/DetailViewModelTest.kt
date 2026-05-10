package com.calendaradd.ui

import com.calendaradd.usecase.CalendarUseCase
import com.calendaradd.usecase.Event
import com.calendaradd.usecase.EventUpdateResult
import com.calendaradd.usecase.PreferencesManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DetailViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var calendarUseCase: CalendarUseCase
    private lateinit var preferencesManager: PreferencesManager

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
        calendarUseCase = mockk()
        preferencesManager = mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `saveEdits should reject invalid start time`() = runTest(dispatcher) {
        every { calendarUseCase.getAllEvents() } returns flowOf(listOf(baseEvent()))
        val viewModel = DetailViewModel(7L, calendarUseCase, preferencesManager)
        advanceUntilIdle()

        viewModel.saveEdits(
            EventEditDraft(
                title = "Updated",
                startTime = "tomorrow at 10",
                endTime = "2026-05-10 11:00"
            )
        )

        val status = viewModel.editStatus.value
        assertTrue(status is EditStatus.Error)
        assertEquals("Start time must use YYYY-MM-DD HH:mm.", (status as EditStatus.Error).message)
        coVerify(exactly = 0) { calendarUseCase.updateEvent(any()) }
    }

    @Test
    fun `saveEdits should persist edited fields through use case`() = runTest(dispatcher) {
        val savedEvent = slot<Event>()
        every { calendarUseCase.getAllEvents() } returns flowOf(listOf(baseEvent()))
        coEvery {
            calendarUseCase.updateEvent(capture(savedEvent))
        } answers {
            EventUpdateResult.Success(savedEvent.captured, syncedToSystem = true)
        }
        val viewModel = DetailViewModel(7L, calendarUseCase, preferencesManager)
        advanceUntilIdle()

        viewModel.saveEdits(
            EventEditDraft(
                title = "Updated title",
                startTime = "2026-05-10 10:30",
                endTime = "2026-05-10 12:00",
                location = "Updated room",
                attendees = "Alice, Bob",
                description = "Updated notes"
            )
        )
        advanceUntilIdle()

        assertEquals("Updated title", savedEvent.captured.title)
        assertEquals("Updated room", savedEvent.captured.location)
        assertEquals("Alice, Bob", savedEvent.captured.attendees)
        assertEquals("Updated notes", savedEvent.captured.description)
        assertEquals(epochMillis("2026-05-10 10:30"), savedEvent.captured.startTime)
        assertEquals(epochMillis("2026-05-10 12:00"), savedEvent.captured.endTime)
        assertTrue(viewModel.editStatus.value is EditStatus.Success)
        assertTrue(viewModel.syncStatus.value is SyncStatus.Success)
    }

    private fun baseEvent(): Event {
        return Event(
            id = 7L,
            title = "Original title",
            startTime = epochMillis("2026-05-10 09:00"),
            endTime = epochMillis("2026-05-10 10:00"),
            location = "Original room",
            systemCalendarEventId = 42L
        )
    }

    private fun epochMillis(value: String): Long {
        return LocalDateTime.parse(value, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
    }
}

package com.calendaradd.usecase

import android.content.Context
import android.graphics.Bitmap
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.calendaradd.service.AnalysisDebugSnapshot
import com.calendaradd.service.EventExtraction
import com.calendaradd.service.SystemCalendarService
import com.calendaradd.service.SystemCalendarUpdateResult
import com.calendaradd.service.TextAnalysisService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class CalendarUseCaseTest {

    private lateinit var textAnalysisService: TextAnalysisService
    private lateinit var systemCalendarService: SystemCalendarService
    private lateinit var preferencesManager: PreferencesManager
    private lateinit var realDatabase: EventDatabase
    private lateinit var eventDatabase: EventDatabase
    private lateinit var useCase: CalendarUseCase

    /**
     * A relaxed mock is not enough here: Room's file-backed [Room.databaseBuilder] still
     * resolves the database name through [Context.getDatabasePath] even when a driver is
     * set, and a relaxed auto-mock returns an unrelated proxy File, silently redirecting
     * Room to the wrong file. Real Android's [Context.getDatabasePath] returns the path
     * as-is when it is already absolute, so mirror that here.
     */
    private fun fakeContext(): Context {
        val context = mockk<Context>(relaxed = true)
        every { context.applicationContext } returns context
        every { context.getDatabasePath(any()) } answers { File(firstArg<String>()) }
        return context
    }

    private fun inMemoryDatabase(): EventDatabase =
        Room.inMemoryDatabaseBuilder(fakeContext(), EventDatabase::class.java)
            .setDriver(BundledSQLiteDriver())
            .build()

    /** Rebinds [useCase] to a [CalendarUseCase] backed by [dao] instead of the plain real DAO. */
    private fun useCaseWith(dao: EventDao): CalendarUseCase {
        eventDatabase = mockk()
        every { eventDatabase.eventDao() } returns dao
        return CalendarUseCase(
            textAnalysisService = textAnalysisService,
            eventDatabase = eventDatabase,
            systemCalendarService = systemCalendarService,
            preferencesManager = preferencesManager
        )
    }

    @Before
    fun setup() {
        textAnalysisService = mockk()
        systemCalendarService = mockk()
        preferencesManager = mockk()

        every { preferencesManager.isAutoAddEnabled } returns false
        every { preferencesManager.targetCalendarId } returns -1L
        every { preferencesManager.isFailureJsonDebugEnabled } returns false
        every { textAnalysisService.consumeLastDebugSnapshot() } returns null

        realDatabase = inMemoryDatabase()
        useCase = useCaseWith(realDatabase.eventDao())
    }

    @After
    fun tearDown() {
        realDatabase.close()
    }

    private fun extraction(
        title: String,
        startTime: String,
        endTime: String = "",
        location: String = "",
        description: String = ""
    ) = EventExtraction(
        title = title,
        description = description,
        startTime = startTime,
        endTime = endTime,
        location = location,
        attendees = emptyList()
    )

    /** Deterministic, distinctly-titled extractions with strictly increasing valid start times. */
    private fun sequentialExtractions(count: Int): List<EventExtraction> {
        return (0 until count).map { index ->
            extraction(
                title = "Event $index",
                startTime = LocalDate.of(2026, 4, 1).atTime(LocalTime.MIDNIGHT).plusHours(index.toLong()).toString()
            )
        }
    }

    // ---- R1/AC1: hard cap of MAX_EVENTS_PER_EXTRACTION applied before any write ----

    @Test
    fun `saveAndSyncExtractions keeps all events when under the cap`() = runBlocking {
        coEvery { textAnalysisService.analyzeText(any(), any()) } returns sequentialExtractions(5)

        val result = useCase.createEventFromText("Five events")

        assertTrue(result is EventResult.Success)
        result as EventResult.Success
        assertEquals(5, result.events.size)
        assertEquals((0 until 5).map { "Event $it" }, result.events.map { it.title })
        assertEquals(5, realDatabase.eventDao().getAllEventsOnce().size)
    }

    @Test
    fun `saveAndSyncExtractions keeps exactly the cap with no violation at the boundary`() = runBlocking {
        coEvery { textAnalysisService.analyzeText(any(), any()) } returns
            sequentialExtractions(CalendarUseCase.MAX_EVENTS_PER_EXTRACTION)

        val result = useCase.createEventFromText("Twenty events")

        assertTrue(result is EventResult.Success)
        result as EventResult.Success
        assertEquals(CalendarUseCase.MAX_EVENTS_PER_EXTRACTION, result.events.size)
        assertEquals(CalendarUseCase.MAX_EVENTS_PER_EXTRACTION, realDatabase.eventDao().getAllEventsOnce().size)
    }

    @Test
    fun `saveAndSyncExtractions caps 25 events to the first 20 in order and drops the rest before any write`() = runBlocking {
        coEvery { textAnalysisService.analyzeText(any(), any()) } returns sequentialExtractions(25)

        val result = useCase.createEventFromText("Twenty five events")

        assertTrue(result is EventResult.Success)
        result as EventResult.Success
        assertEquals(20, result.events.size)
        assertEquals((0 until 20).map { "Event $it" }, result.events.map { it.title })
        assertEquals(20, realDatabase.eventDao().getAllEventsOnce().size)
        assertTrue(realDatabase.eventDao().getAllEventsOnce().none { it.title == "Event 24" })
    }

    // ---- R3/AC3: deterministic capped order and stable replay ----

    @Test
    fun `replaying the same request identity reuses the identical capped events without duplicating rows`() = runBlocking {
        coEvery { textAnalysisService.analyzeText(any(), any()) } returns sequentialExtractions(25)
        // Same InputContext (and therefore same requestId) as both calls models a genuine
        // retry of one logical job — e.g. BackgroundAnalysisWorker re-running the same
        // WorkManager job id after a transient failure (A1).
        val retryContext = InputContext(requestId = "job-retry-1")

        val first = useCase.createEventFromText("Many events", retryContext)
        val second = useCase.createEventFromText("Many events", retryContext)

        assertTrue(first is EventResult.Success)
        assertTrue(second is EventResult.Success)
        first as EventResult.Success
        second as EventResult.Success

        assertEquals(20, first.events.size)
        assertEquals(first.events.map { it.title }, second.events.map { it.title })
        assertEquals((0 until 20).map { "Event $it" }, first.events.map { it.title })
        // Replay must resolve to the same journal row and reuse its rows, not double-insert.
        assertEquals(20, realDatabase.eventDao().getAllEventsOnce().size)
        assertTrue(realDatabase.eventDao().getUnfinishedBatches().isEmpty())
    }

    // ---- A2: distinct requests with identical content must not collide ----

    @Test
    fun `two independent requests with identical content each save their own events`() = runBlocking {
        coEvery { textAnalysisService.analyzeText(any(), any()) } returns listOf(
            extraction("Lunch", "2026-04-20T12:00:00")
        )

        // Two genuinely separate save requests — e.g. two different BackgroundAnalysisWorker
        // jobs, or a user intentionally resubmitting identical text — must each persist their
        // own event rather than being folded into a single journal row by content alone.
        val first = useCase.createEventFromText("Lunch tomorrow")
        val second = useCase.createEventFromText("Lunch tomorrow")

        assertTrue(first is EventResult.Success)
        assertTrue(second is EventResult.Success)
        first as EventResult.Success
        second as EventResult.Success

        assertEquals(1, first.events.size)
        assertEquals(1, second.events.size)
        assertTrue(first.event.id != second.event.id)
        assertEquals(2, realDatabase.eventDao().getAllEventsOnce().size)
        assertTrue(realDatabase.eventDao().getUnfinishedBatches().isEmpty())
    }

    @Test
    fun `resubmitting identical content after the original event is deleted recreates it`() = runBlocking {
        coEvery { textAnalysisService.analyzeText(any(), any()) } returns listOf(
            extraction("Lunch", "2026-04-20T12:00:00")
        )

        val first = useCase.createEventFromText("Lunch tomorrow")
        assertTrue(first is EventResult.Success)
        useCase.deleteEvent((first as EventResult.Success).event.id)
        assertTrue(realDatabase.eventDao().getAllEventsOnce().isEmpty())

        val second = useCase.createEventFromText("Lunch tomorrow")

        assertTrue(second is EventResult.Success)
        assertEquals(1, realDatabase.eventDao().getAllEventsOnce().size)
    }

    // ---- R2/AC2: atomic local persistence via the P7 journal ----

    @Test
    fun `journal creation failure writes zero rows, then a replay commits exactly once`() = runBlocking {
        val faultyDao = FaultInjectingEventDao(realDatabase.eventDao())
        useCase = useCaseWith(faultyDao)
        faultyDao.failNextCall("getOrCreateBatch")
        coEvery { textAnalysisService.analyzeText(any(), any()) } returns listOf(
            extraction("Lunch", "2026-04-20T12:00:00")
        )
        // Same requestId on both calls models a worker retrying the same job after a
        // transient failure (A1), not a second independent save request (A2).
        val jobContext = InputContext(requestId = "job-retry-2")

        val failed = useCase.createEventFromText("Lunch tomorrow", jobContext)

        assertTrue(failed is EventResult.Failure)
        assertTrue(realDatabase.eventDao().getAllEventsOnce().isEmpty())
        assertTrue(realDatabase.eventDao().getUnfinishedBatches().isEmpty())

        val replayed = useCase.createEventFromText("Lunch tomorrow", jobContext)

        assertTrue(replayed is EventResult.Success)
        assertEquals(1, realDatabase.eventDao().getAllEventsOnce().size)
        assertTrue(realDatabase.eventDao().getUnfinishedBatches().isEmpty())
    }

    @Test
    fun `event-row insert failure rolls back the whole batch, then a replay commits exactly once`() = runBlocking {
        val faultyDao = FaultInjectingEventDao(realDatabase.eventDao())
        useCase = useCaseWith(faultyDao)
        faultyDao.failNextCall("commitBatchItems")
        coEvery { textAnalysisService.analyzeText(any(), any()) } returns listOf(
            extraction("Lunch", "2026-04-20T12:00:00"),
            extraction("Dentist", "2026-04-21T09:00:00")
        )
        // Same requestId on both calls models a worker retrying the same job after a
        // transient failure (A1), not a second independent save request (A2).
        val jobContext = InputContext(requestId = "job-retry-3")

        val failed = useCase.createEventFromText("Lunch and dentist", jobContext)

        assertTrue(failed is EventResult.Failure)
        assertTrue("no event rows on a mid-batch insert failure", realDatabase.eventDao().getAllEventsOnce().isEmpty())
        assertTrue("no dangling journal items", realDatabase.eventDao().getUnfinishedBatches().let {
            it.size == 1 && realDatabase.eventDao().getBatchItems(it.single().id).isEmpty()
        })
        assertEquals(BatchStatus.PENDING.name, realDatabase.eventDao().getUnfinishedBatches().single().status)

        val replayed = useCase.createEventFromText("Lunch and dentist", jobContext)

        assertTrue(replayed is EventResult.Success)
        replayed as EventResult.Success
        assertEquals(2, replayed.events.size)
        assertEquals(2, realDatabase.eventDao().getAllEventsOnce().size)
        assertTrue(realDatabase.eventDao().getUnfinishedBatches().isEmpty())
    }

    @Test
    fun `batch-completion failure still reports success for the already-committed events`() = runBlocking {
        val faultyDao = FaultInjectingEventDao(realDatabase.eventDao())
        useCase = useCaseWith(faultyDao)
        faultyDao.failNextCall("completeBatch")
        coEvery { textAnalysisService.analyzeText(any(), any()) } returns listOf(
            extraction("Lunch", "2026-04-20T12:00:00")
        )

        val result = useCase.createEventFromText("Lunch tomorrow")

        // A3: local events are already durably committed by the time completeBatch runs;
        // a failure marking the batch COMMITTED must not be reported as a failed save, since
        // that would invite a caller retry that duplicates already-saved events.
        assertTrue(result is EventResult.Success)
        result as EventResult.Success
        assertEquals(1, result.events.size)
        assertEquals(1, realDatabase.eventDao().getAllEventsOnce().size)
    }

    @Test
    fun `provider outcome recording failure keeps the locally committed event and still completes the batch`() = runBlocking {
        val faultyDao = FaultInjectingEventDao(realDatabase.eventDao())
        useCase = useCaseWith(faultyDao)
        faultyDao.failNextCall("recordProviderOutcome")

        every { preferencesManager.isAutoAddEnabled } returns true
        coEvery { systemCalendarService.hasCalendarPermissions() } returns true
        coEvery { systemCalendarService.getAvailableCalendars() } returns listOf(
            SystemCalendarService.CalendarInfo(id = 4L, name = "Main", accountName = "local", isPrimary = true)
        )
        coEvery {
            systemCalendarService.insertEvent(
                calendarId = 4L,
                title = "Auto synced",
                description = "",
                startTimeMillis = any(),
                endTimeMillis = any(),
                location = ""
            )
        } returns 88L
        coEvery { textAnalysisService.analyzeText(any(), any()) } returns listOf(
            extraction("Auto synced", "2026-04-20T12:00:00", endTime = "2026-04-20T13:00:00")
        )

        val result = useCase.createEventFromText("Auto synced lunch")

        // R2 scopes atomicity to local Room persistence; provider-outcome bookkeeping is
        // best-effort (P8A owns the provider itself), so a failure here must not unwind the
        // already-committed local event.
        assertTrue(result is EventResult.Success)
        result as EventResult.Success
        assertEquals(88L, result.event.systemCalendarEventId)
        assertEquals(1, realDatabase.eventDao().getAllEventsOnce().size)
        assertTrue(realDatabase.eventDao().getUnfinishedBatches().isEmpty())
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
        assertTrue(realDatabase.eventDao().getAllEventsOnce().isEmpty())
    }

    @Test
    fun `createEventFromText should persist multiple extracted events`() = runBlocking {
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
        assertEquals(2, realDatabase.eventDao().getAllEventsOnce().size)
    }

    @Test
    fun `createEventFromText should infer time policy for plural event words`() = runBlocking {
        coEvery { textAnalysisService.analyzeText(any(), any()) } returns listOf(
            EventExtraction(
                title = "Team meetings",
                description = "",
                startTime = "2026-04-20",
                endTime = "",
                location = "",
                attendees = emptyList()
            )
        )

        val result = useCase.createEventFromText(
            "Team meetings on April 20",
            InputContext(timezone = "UTC")
        )

        assertTrue(result is EventResult.Success)
        val savedEvent = realDatabase.eventDao().getAllEventsOnce().single()
        assertEquals(
            LocalDate.of(2026, 4, 20)
                .atTime(LocalTime.of(9, 0))
                .atZone(ZoneId.of("UTC"))
                .toInstant()
                .toEpochMilli(),
            savedEvent.startTime
        )
    }

    @Test
    fun `createEventFromText should remember system calendar id when auto sync inserts event`() = runBlocking {
        every { preferencesManager.isAutoAddEnabled } returns true
        coEvery { systemCalendarService.hasCalendarPermissions() } returns true
        coEvery { systemCalendarService.getAvailableCalendars() } returns listOf(
            SystemCalendarService.CalendarInfo(id = 4L, name = "Main", accountName = "local", isPrimary = true)
        )
        coEvery { textAnalysisService.analyzeText(any(), any()) } returns listOf(
            EventExtraction(
                title = "Auto synced",
                description = "",
                startTime = "2026-04-20T12:00:00",
                endTime = "2026-04-20T13:00:00",
                location = "HQ",
                attendees = emptyList()
            )
        )
        coEvery {
            systemCalendarService.insertEvent(
                calendarId = 4L,
                title = "Auto synced",
                description = "",
                startTimeMillis = any(),
                endTimeMillis = any(),
                location = "HQ"
            )
        } returns 88L

        val result = useCase.createEventFromText("Auto synced lunch")

        assertTrue(result is EventResult.Success)
        result as EventResult.Success
        assertEquals(88L, result.event.systemCalendarEventId)
        val savedEvent = realDatabase.eventDao().getAllEventsOnce().single()
        assertEquals(88L, savedEvent.systemCalendarEventId)
    }

    @Test
    fun `createEventFromImage should persist source attachment metadata`() = runBlocking {
        val bitmap = mockk<Bitmap>(relaxed = true)
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
        val savedEvent = realDatabase.eventDao().getAllEventsOnce().single()
        assertEquals("/app/source/source-image.jpg", savedEvent.sourceAttachmentPath)
        assertEquals("image/jpeg", savedEvent.sourceAttachmentMimeType)
        assertEquals("shared-flyer.jpg", savedEvent.sourceAttachmentName)
        assertEquals("/app/source/source-image.jpg", result.event.sourceAttachmentPath)
    }

    @Test
    fun `createEventFromImage should infer evening start and longer duration for date only concert flyer`() = runBlocking {
        val bitmap = mockk<Bitmap>(relaxed = true)
        coEvery { textAnalysisService.analyzeImage(bitmap, any()) } returns listOf(
            EventExtraction(
                title = "Summer Concert: Alice + Bob + Chloé",
                description = "Three live artists on the same lineup",
                startTime = "2026-07-12",
                endTime = "",
                location = "Main Hall",
                attendees = emptyList()
            )
        )

        val result = useCase.createEventFromImage(
            bitmap,
            context = InputContext(timezone = "Europe/Paris")
        )

        val expectedStart = LocalDate.of(2026, 7, 12)
            .atTime(LocalTime.of(20, 0))
            .atZone(ZoneId.of("Europe/Paris"))
            .toInstant()
            .toEpochMilli()
        assertTrue(result is EventResult.Success)
        val savedEvent = realDatabase.eventDao().getAllEventsOnce().single()
        assertEquals(expectedStart, savedEvent.startTime)
        assertEquals(expectedStart + 3 * 60 * 60 * 1000L, savedEvent.endTime)
    }

    @Test
    fun `createEventFromText should clamp all day concert end time from model output`() = runBlocking {
        coEvery { textAnalysisService.analyzeText(any(), any()) } returns listOf(
            EventExtraction(
                title = "Concert with multiple artists",
                description = "Alice, Bob, and Chloé live",
                startTime = "2026-07-12T20:00:00+02:00",
                endTime = "2026-07-13T20:00:00+02:00",
                location = "Main Hall",
                attendees = emptyList()
            )
        )

        val result = useCase.createEventFromText("Concert flyer")

        assertTrue(result is EventResult.Success)
        val savedEvent = realDatabase.eventDao().getAllEventsOnce().single()
        assertEquals(6 * 60 * 60 * 1000L, savedEvent.endTime - savedEvent.startTime)
    }

    @Test
    fun `createEventFromText should keep one hour default for generic event without end time`() = runBlocking {
        coEvery { textAnalysisService.analyzeText(any(), any()) } returns listOf(
            EventExtraction(
                title = "Planning sync",
                description = "",
                startTime = "2026-07-12T10:00:00+02:00",
                endTime = "",
                location = "",
                attendees = emptyList()
            )
        )

        val result = useCase.createEventFromText("Planning sync")

        assertTrue(result is EventResult.Success)
        val savedEvent = realDatabase.eventDao().getAllEventsOnce().single()
        assertEquals(60 * 60 * 1000L, savedEvent.endTime - savedEvent.startTime)
    }

    @Test
    fun `deleteEvent should delete unreferenced source attachment file`() = runBlocking {
        val sourceFile = File.createTempFile("calendar-add-source", ".jpg").apply {
            writeText("source")
        }
        val dao = realDatabase.eventDao()
        val eventId = dao.insert(
            Event(
                title = "Source event",
                startTime = 1L,
                endTime = 2L,
                sourceAttachmentPath = sourceFile.absolutePath,
                sourceAttachmentMimeType = "image/jpeg",
                sourceAttachmentName = "source.jpg"
            )
        )

        useCase.deleteEvent(eventId)

        assertFalse(sourceFile.exists())
        assertNull(dao.getEventByIdOnce(eventId))
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
        assertTrue(realDatabase.eventDao().getAllEventsOnce().isEmpty())
        // A4: an extraction with no parseable start time can never journal any items under
        // this batchKey, so the PENDING row created by getOrCreateBatch must not linger.
        assertTrue(realDatabase.eventDao().getUnfinishedBatches().isEmpty())
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
        assertEquals(1, realDatabase.eventDao().getAllEventsOnce().size)
    }

    @Test
    fun `syncEventToSystem should insert first system event and store calendar event id`() = runBlocking {
        val dao = realDatabase.eventDao()
        val eventId = dao.insert(
            Event(
                title = "Planning",
                description = "Quarterly planning",
                startTime = 1000L,
                endTime = 2000L,
                location = "HQ"
            )
        )
        coEvery {
            systemCalendarService.insertEvent(
                calendarId = 4L,
                title = "Planning",
                description = "Quarterly planning",
                startTimeMillis = 1000L,
                endTimeMillis = 2000L,
                location = "HQ"
            )
        } returns 88L

        val result = useCase.syncEventToSystem(
            dao.getEventByIdOnce(eventId)!!,
            calendarId = 4L
        )

        assertEquals(88L, result)
        assertEquals(88L, dao.getEventByIdOnce(eventId)?.systemCalendarEventId)
        coVerify(exactly = 1) { systemCalendarService.insertEvent(any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { systemCalendarService.updateEvent(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `syncEventToSystem should update existing system event without inserting duplicate`() = runBlocking {
        coEvery {
            systemCalendarService.updateEvent(
                systemEventId = 88L,
                calendarId = 4L,
                title = "Updated planning",
                description = "Quarterly planning",
                startTimeMillis = 1000L,
                endTimeMillis = 2000L,
                location = "HQ"
            )
        } returns SystemCalendarUpdateResult.UPDATED

        val result = useCase.syncEventToSystem(
            Event(
                id = 9L,
                title = "Updated planning",
                description = "Quarterly planning",
                startTime = 1000L,
                endTime = 2000L,
                location = "HQ",
                systemCalendarEventId = 88L
            ),
            calendarId = 4L
        )

        assertEquals(88L, result)
        coVerify(exactly = 1) { systemCalendarService.updateEvent(any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { systemCalendarService.insertEvent(any(), any(), any(), any(), any(), any()) }
        assertNull(realDatabase.eventDao().getEventByIdOnce(9L))
    }

    @Test
    fun `syncEventToSystem should clear stale system id and reinsert`() = runBlocking {
        val dao = realDatabase.eventDao()
        val eventId = dao.insert(
            Event(
                id = 9L,
                title = "Recovered planning",
                description = "Quarterly planning",
                startTime = 1000L,
                endTime = 2000L,
                location = "HQ",
                systemCalendarEventId = 88L
            )
        )
        coEvery {
            systemCalendarService.updateEvent(
                systemEventId = 88L,
                calendarId = 4L,
                title = "Recovered planning",
                description = "Quarterly planning",
                startTimeMillis = 1000L,
                endTimeMillis = 2000L,
                location = "HQ"
            )
        } returns SystemCalendarUpdateResult.MISSING
        coEvery {
            systemCalendarService.insertEvent(
                calendarId = 4L,
                title = "Recovered planning",
                description = "Quarterly planning",
                startTimeMillis = 1000L,
                endTimeMillis = 2000L,
                location = "HQ"
            )
        } returns 99L

        val result = useCase.syncEventToSystem(dao.getEventByIdOnce(eventId)!!, calendarId = 4L)

        assertEquals(99L, result)
        assertEquals(99L, dao.getEventByIdOnce(eventId)?.systemCalendarEventId)
        coVerify(exactly = 1) { systemCalendarService.updateEvent(any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 1) { systemCalendarService.insertEvent(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `syncEventToSystem should not clear id or insert duplicate when update errors`() = runBlocking {
        coEvery {
            systemCalendarService.updateEvent(
                systemEventId = 88L,
                calendarId = 4L,
                title = "Recovered planning",
                description = "Quarterly planning",
                startTimeMillis = 1000L,
                endTimeMillis = 2000L,
                location = "HQ"
            )
        } returns SystemCalendarUpdateResult.ERROR

        val result = useCase.syncEventToSystem(
            Event(
                id = 9L,
                title = "Recovered planning",
                description = "Quarterly planning",
                startTime = 1000L,
                endTime = 2000L,
                location = "HQ",
                systemCalendarEventId = 88L
            ),
            calendarId = 4L
        )

        assertEquals(null, result)
        coVerify(exactly = 1) { systemCalendarService.updateEvent(any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { systemCalendarService.insertEvent(any(), any(), any(), any(), any(), any()) }
        assertNull(realDatabase.eventDao().getEventByIdOnce(9L))
    }

    @Test
    fun `updateEvent should save locally and update existing system calendar event when auto sync is enabled`() = runBlocking {
        val dao = realDatabase.eventDao()
        val eventId = dao.insert(
            Event(
                id = 9L,
                title = "Planning",
                description = "Notes",
                startTime = 1000L,
                endTime = 2000L,
                location = "HQ",
                systemCalendarEventId = 88L
            )
        )
        every { preferencesManager.isAutoAddEnabled } returns true
        coEvery { systemCalendarService.hasCalendarPermissions() } returns true
        coEvery { systemCalendarService.getAvailableCalendars() } returns listOf(
            SystemCalendarService.CalendarInfo(id = 4L, name = "Main", accountName = "local", isPrimary = true)
        )
        coEvery {
            systemCalendarService.updateEvent(
                systemEventId = 88L,
                calendarId = 4L,
                title = "Edited planning",
                description = "Edited notes",
                startTimeMillis = 1000L,
                endTimeMillis = 2000L,
                location = "HQ"
            )
        } returns SystemCalendarUpdateResult.UPDATED

        val result = useCase.updateEvent(
            Event(
                id = eventId,
                title = "Edited planning",
                description = "Edited notes",
                startTime = 1000L,
                endTime = 2000L,
                location = "HQ",
                systemCalendarEventId = 88L
            )
        )

        assertTrue(result is EventUpdateResult.Success)
        result as EventUpdateResult.Success
        assertTrue(result.syncedToSystem)
        assertEquals(88L, result.event.systemCalendarEventId)
        assertEquals("Edited planning", dao.getEventByIdOnce(eventId)?.title)
        coVerify(exactly = 1) { systemCalendarService.updateEvent(any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { systemCalendarService.insertEvent(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `updateEvent should save locally without calendar sync when auto sync is disabled`() = runBlocking {
        val dao = realDatabase.eventDao()
        val eventId = dao.insert(
            Event(
                title = "Original",
                startTime = 1000L,
                endTime = 2000L,
                systemCalendarEventId = 88L
            )
        )

        val result = useCase.updateEvent(
            Event(
                id = eventId,
                title = "Local edit",
                startTime = 1000L,
                endTime = 2000L,
                systemCalendarEventId = 88L
            )
        )

        assertTrue(result is EventUpdateResult.Success)
        result as EventUpdateResult.Success
        assertFalse(result.syncedToSystem)
        assertEquals("Local edit", dao.getEventByIdOnce(eventId)?.title)
        coVerify(exactly = 0) { systemCalendarService.updateEvent(any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { systemCalendarService.insertEvent(any(), any(), any(), any(), any(), any()) }
    }
}

/**
 * Wraps a real (Room-backed) [EventDao] and can be told to throw once on a named method,
 * so tests can inject a failure at a specific point in the save pipeline while every other
 * call still goes through the real, transactional implementation.
 */
private class FaultInjectingEventDao(private val real: EventDao) : EventDao {
    private val failuresRemaining = mutableMapOf<String, Int>()

    fun failNextCall(method: String, times: Int = 1) {
        failuresRemaining[method] = times
    }

    private fun maybeFail(method: String) {
        val remaining = failuresRemaining[method] ?: return
        if (remaining <= 0) return
        failuresRemaining[method] = remaining - 1
        throw RuntimeException("injected failure: $method")
    }

    override fun getAllEvents(): Flow<List<Event>> = real.getAllEvents()
    override suspend fun getAllEventsOnce(): List<Event> = real.getAllEventsOnce()
    override fun getEventById(eventId: Long): Flow<Event?> = real.getEventById(eventId)
    override suspend fun getEventByIdOnce(eventId: Long): Event? = real.getEventByIdOnce(eventId)
    override suspend fun insert(event: Event): Long = real.insert(event)
    override suspend fun deleteEvent(eventId: Long) = real.deleteEvent(eventId)
    override suspend fun update(event: Event) = real.update(event)
    override fun getUpcomingEvents(now: Long): Flow<List<Event>> = real.getUpcomingEvents(now)
    override suspend fun searchEvents(search: String): List<Event> = real.searchEvents(search)
    override suspend fun countEventsWithSourceAttachmentPath(path: String): Int =
        real.countEventsWithSourceAttachmentPath(path)
    override suspend fun deleteAll() = real.deleteAll()

    override suspend fun findBatchByKey(batchKey: String): ExtractionBatchEntity? = real.findBatchByKey(batchKey)
    override suspend fun countBatchesForKey(batchKey: String): Int = real.countBatchesForKey(batchKey)
    override suspend fun getBatchById(batchId: Long): ExtractionBatchEntity? = real.getBatchById(batchId)
    override suspend fun getUnfinishedBatches(): List<ExtractionBatchEntity> = real.getUnfinishedBatches()
    override suspend fun insertBatchIgnore(batch: ExtractionBatchEntity): Long = real.insertBatchIgnore(batch)

    override suspend fun getOrCreateBatch(
        batchKey: String,
        sourceType: String,
        sourceAttachmentPath: String,
        sourceAttachmentMimeType: String,
        sourceAttachmentName: String
    ): ExtractionBatchEntity {
        maybeFail("getOrCreateBatch")
        return real.getOrCreateBatch(
            batchKey,
            sourceType,
            sourceAttachmentPath,
            sourceAttachmentMimeType,
            sourceAttachmentName
        )
    }

    override suspend fun findBatchItem(batchId: Long, ordinal: Int): ExtractionBatchItemEntity? =
        real.findBatchItem(batchId, ordinal)
    override suspend fun getBatchItems(batchId: Long): List<ExtractionBatchItemEntity> = real.getBatchItems(batchId)
    override suspend fun insertEventForJournal(event: Event): Long = real.insertEventForJournal(event)
    override suspend fun insertBatchItem(item: ExtractionBatchItemEntity): Long = real.insertBatchItem(item)

    override suspend fun commitBatchItems(
        batchId: Long,
        items: List<PendingBatchItem>
    ): List<ExtractionBatchItemEntity> {
        maybeFail("commitBatchItems")
        return real.commitBatchItems(batchId, items)
    }

    override suspend fun recordProviderOutcome(
        itemId: Long,
        providerId: Long?,
        syncState: String,
        recoveryStatus: String
    ) {
        maybeFail("recordProviderOutcome")
        real.recordProviderOutcome(itemId, providerId, syncState, recoveryStatus)
    }

    override suspend fun setBatchStatus(batchId: Long, status: String, completedAt: Long?) =
        real.setBatchStatus(batchId, status, completedAt)

    override suspend fun completeBatch(batchId: Long) {
        maybeFail("completeBatch")
        real.completeBatch(batchId)
    }

    override suspend fun rollbackBatch(batchId: Long) {
        maybeFail("rollbackBatch")
        real.rollbackBatch(batchId)
    }

    override suspend fun deleteBatchJournal(batchId: Long) = real.deleteBatchJournal(batchId)
}

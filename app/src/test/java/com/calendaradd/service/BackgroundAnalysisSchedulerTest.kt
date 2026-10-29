package com.calendaradd.service

import android.app.DownloadManager
import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.Operation
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.google.common.util.concurrent.ListenableFuture
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BackgroundAnalysisSchedulerTest {

    private lateinit var context: Context
    private lateinit var workManager: WorkManager
    private lateinit var inputDir: File

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        workManager = mockk(relaxed = true)
        val sharedPrefs: android.content.SharedPreferences = mockk(relaxed = true)
        val downloadMgr: android.app.DownloadManager = mockk(relaxed = true)
        inputDir = File("build/tmp/background-analysis-scheduler-test").apply {
            deleteRecursively()
            mkdirs()
        }

        @Suppress("UNUSED_VARIABLE")
        val downloadManager = mockk<DownloadManager>(relaxed = true)
        every { context.getSystemService(Context.DOWNLOAD_SERVICE) } returns downloadManager
        every { context.applicationContext } returns context
        every { context.noBackupFilesDir } returns inputDir
        every { context.filesDir } returns inputDir
        every { context.getSharedPreferences(any(), any()) } returns sharedPrefs
        every { context.getSystemService(Context.DOWNLOAD_SERVICE) } returns downloadMgr
        every { context.getExternalFilesDir(any()) } returns inputDir
    }

    @Test
    fun `reconcilePendingWork clears the stale job and re-enqueues valid survivors to undo the cascade`() {
        // AC1 + A1: with a stale (missing-input) job alongside valid jobs, the stale id/input
        // is removed and every valid non-RUNNING survivor is cancelled-and-re-enqueued so the
        // stale-node cancel's forward cascade cannot silently drop valid queued work.
        val validA = File(analysisInputDir(), "valid-a.txt").apply { writeText("a") }
        val validB = File(analysisInputDir(), "valid-b.txt").apply { writeText("b") }
        val staleId = UUID.randomUUID()

        val validInfoA = mockWorkInfo(
            state = WorkInfo.State.ENQUEUED,
            tags = setOf(workTag(), modelTag(), inputTag(validA))
        )
        val validInfoB = mockWorkInfo(
            state = WorkInfo.State.ENQUEUED,
            tags = setOf(workTag(), modelTag(), inputTag(validB))
        )
        // Capture ids up front: reading a mock's getter inside a verify{} block is itself
        // recorded by mockk, so the ids must be plain values at verification time.
        val validAId = validInfoA.id
        val validBId = validInfoB.id
        val staleInfo = mockWorkInfo(
            id = staleId,
            state = WorkInfo.State.ENQUEUED,
            tags = setOf(workTag(), modelTag(), inputTag(File(analysisInputDir(), "gone.txt")))
        )
        every {
            workManager.getWorkInfosForUniqueWork(BackgroundAnalysisScheduler.UNIQUE_WORK_NAME)
        } returns immediateFuture(listOf(validInfoA, validInfoB, staleInfo))
        every {
            workManager.enqueueUniqueWork(
                BackgroundAnalysisScheduler.UNIQUE_WORK_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                any<OneTimeWorkRequest>()
            )
        } returns immediateSuccessOperation()

        val scheduler = BackgroundAnalysisScheduler(context, workManager)
        val status = runBlocking { scheduler.reconcilePendingWork() }

        assertTrue(status.hasPendingWork)
        assertTrue(status.clearedStaleWork)
        // Valid inputs are reused, never deleted.
        assertTrue(validA.exists())
        assertTrue(validB.exists())
        // Stale id cancelled by clearStaleItem; valid ids cancelled inside reEnqueueSurvivor
        // (before its re-enqueue) so the new requests cannot duplicate the originals.
        verify(exactly = 1) { workManager.cancelWorkById(staleId) }
        verify(exactly = 1) { workManager.cancelWorkById(validAId) }
        verify(exactly = 1) { workManager.cancelWorkById(validBId) }
        // Both valid survivors re-enqueued to undo the forward cascade.
        verify(exactly = 2) {
            workManager.enqueueUniqueWork(
                BackgroundAnalysisScheduler.UNIQUE_WORK_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                any<OneTimeWorkRequest>()
            )
        }
        verify(exactly = 0) { workManager.cancelUniqueWork(any()) }
        verify(exactly = 0) { workManager.pruneWork() }
    }

    @Test
    fun `reconcilePendingWork cancels a legacy item that carries no input metadata`() {
        // AC1 variant: an entry without an input tag is stale and cancelled by id, but no input
        // file deletion is attempted (its input path is unknown).
        val unrelated = File(analysisInputDir(), "unrelated.txt").apply { writeText("keep") }
        val legacyId = UUID.randomUUID()
        val legacyInfo = mockWorkInfo(
            id = legacyId,
            state = WorkInfo.State.ENQUEUED,
            tags = setOf(workTag(), modelTag())
        )
        every {
            workManager.getWorkInfosForUniqueWork(BackgroundAnalysisScheduler.UNIQUE_WORK_NAME)
        } returns immediateFuture(listOf(legacyInfo))

        val scheduler = BackgroundAnalysisScheduler(context, workManager)
        val status = runBlocking { scheduler.reconcilePendingWork() }

        assertFalse(status.hasPendingWork)
        assertTrue(status.clearedStaleWork)
        verify(exactly = 1) { workManager.cancelWorkById(legacyId) }
        verify(exactly = 0) { workManager.cancelUniqueWork(any()) }
        // An unrelated, unreferenced but young file must survive (no global wipe).
        assertTrue(unrelated.exists())
    }

    @Test
    fun `reconcilePendingWork keeps valid pending work with persisted input`() {
        val inputFile = File(analysisInputDir(), "queued-image.jpg").apply { writeText("ok") }
        val workInfo = mockWorkInfo(
            state = WorkInfo.State.ENQUEUED,
            tags = setOf(workTag(), modelTag(), inputTag(inputFile))
        )
        every {
            workManager.getWorkInfosForUniqueWork(BackgroundAnalysisScheduler.UNIQUE_WORK_NAME)
        } returns immediateFuture(listOf(workInfo))

        val scheduler = BackgroundAnalysisScheduler(context, workManager)
        val status = runBlocking { scheduler.reconcilePendingWork() }

        assertTrue(status.hasPendingWork)
        assertFalse(status.clearedStaleWork)
        assertTrue(inputFile.exists())
        verify(exactly = 0) { workManager.cancelWorkById(any()) }
        verify(exactly = 0) { workManager.cancelUniqueWork(any()) }
    }

    @Test
    fun `reconcilePendingWork keeps a blocked valid item and its input`() {
        // AC2: a BLOCKED item with its input present is never deleted and never cancelled.
        val inputFile = File(analysisInputDir(), "blocked.txt").apply { writeText("data") }
        val workInfo = mockWorkInfo(
            state = WorkInfo.State.BLOCKED,
            tags = setOf(workTag(), modelTag(), inputTag(inputFile))
        )
        every {
            workManager.getWorkInfosForUniqueWork(BackgroundAnalysisScheduler.UNIQUE_WORK_NAME)
        } returns immediateFuture(listOf(workInfo))

        val scheduler = BackgroundAnalysisScheduler(context, workManager)
        val status = runBlocking { scheduler.reconcilePendingWork() }

        assertTrue(status.hasPendingWork)
        assertFalse(status.clearedStaleWork)
        assertTrue(inputFile.exists())
        verify(exactly = 0) { workManager.cancelWorkById(any()) }
    }

    @Test
    fun `reconcilePendingWork treats a RUNNING worker with missing input as in flight`() {
        // X6 race: a finishing worker deletes its input in finally while its WorkInfo is still
        // RUNNING. Such a worker must never be classified as stale.
        val missing = File(analysisInputDir(), "deleted-by-worker.txt")
        val workInfo = mockWorkInfo(
            state = WorkInfo.State.RUNNING,
            tags = setOf(workTag(), modelTag(), inputTag(missing))
        )
        every {
            workManager.getWorkInfosForUniqueWork(BackgroundAnalysisScheduler.UNIQUE_WORK_NAME)
        } returns immediateFuture(listOf(workInfo))

        val scheduler = BackgroundAnalysisScheduler(context, workManager)
        val status = runBlocking { scheduler.reconcilePendingWork() }

        assertTrue(status.hasPendingWork)
        assertFalse(status.clearedStaleWork)
        verify(exactly = 0) { workManager.cancelWorkById(any()) }
        verify(exactly = 0) { workManager.cancelUniqueWork(any()) }
    }

    @Test
    fun `reconcilePendingWork leaves a RUNNING survivor untouched while re-enqueueing non-running ones`() {
        // A1: a RUNNING worker heads the chain and is never reached by a forward cascade, so it
        // is neither cancelled nor re-enqueued even when a stale item sits behind it. Only the
        // non-RUNNING valid survivor is cancelled-and-re-enqueued.
        val runningInput = File(analysisInputDir(), "running.txt").apply { writeText("running") }
        val queuedInput = File(analysisInputDir(), "queued.txt").apply { writeText("queued") }
        val staleId = UUID.randomUUID()

        val runningInfo = mockWorkInfo(
            state = WorkInfo.State.RUNNING,
            tags = setOf(workTag(), modelTag(), inputTag(runningInput))
        )
        val runningId = runningInfo.id
        val queuedInfo = mockWorkInfo(
            state = WorkInfo.State.ENQUEUED,
            tags = setOf(workTag(), modelTag(), inputTag(queuedInput))
        )
        val staleInfo = mockWorkInfo(
            id = staleId,
            state = WorkInfo.State.ENQUEUED,
            tags = setOf(workTag(), modelTag(), inputTag(File(analysisInputDir(), "gone.txt")))
        )
        every {
            workManager.getWorkInfosForUniqueWork(BackgroundAnalysisScheduler.UNIQUE_WORK_NAME)
        } returns immediateFuture(listOf(runningInfo, staleInfo, queuedInfo))
        every {
            workManager.enqueueUniqueWork(
                BackgroundAnalysisScheduler.UNIQUE_WORK_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                any<OneTimeWorkRequest>()
            )
        } returns immediateSuccessOperation()

        val scheduler = BackgroundAnalysisScheduler(context, workManager)
        val status = runBlocking { scheduler.reconcilePendingWork() }

        assertTrue(status.hasPendingWork)
        assertTrue(status.clearedStaleWork)
        assertTrue(runningInput.exists())
        assertTrue(queuedInput.exists())
        // The RUNNING head is never cancelled and never re-enqueued.
        verify(exactly = 0) { workManager.cancelWorkById(runningId) }
        verify(exactly = 1) {
            workManager.enqueueUniqueWork(
                BackgroundAnalysisScheduler.UNIQUE_WORK_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                any<OneTimeWorkRequest>()
            )
        }
    }

    @Test
    fun `reconcilePendingWork returns a recoverable no-change status when the WorkInfo query throws`() {
        // AC3 (throws branch): a failing query must not propagate an uncaught exception. The
        // status reports no pending work and no clearing; nothing is cancelled.
        every {
            workManager.getWorkInfosForUniqueWork(BackgroundAnalysisScheduler.UNIQUE_WORK_NAME)
        } returns failingFuture(RuntimeException("boom"))

        val scheduler = BackgroundAnalysisScheduler(context, workManager)
        val status = runBlocking { scheduler.reconcilePendingWork() }

        assertFalse(status.hasPendingWork)
        assertFalse(status.clearedStaleWork)
        verify(exactly = 0) { workManager.cancelWorkById(any()) }
        verify(exactly = 0) { workManager.cancelUniqueWork(any()) }
    }

    @Test
    fun `reconcilePendingWork is bounded when the WorkInfo future never completes`() {
        // AC3 (incomplete branch): with a shrunk deadline the reconcile must return within it
        // rather than wedging on an unbounded Future.get().
        every {
            workManager.getWorkInfosForUniqueWork(BackgroundAnalysisScheduler.UNIQUE_WORK_NAME)
        } returns neverCompletingFuture()

        val scheduler = BackgroundAnalysisScheduler(context, workManager, workManagerDeadlineMs = 200L)
        val start = System.currentTimeMillis()
        val status = runBlocking { scheduler.reconcilePendingWork() }
        val elapsed = System.currentTimeMillis() - start

        assertTrue("reconcile exceeded the bounded deadline: ${elapsed}ms", elapsed < 2000L)
        assertFalse(status.hasPendingWork)
        assertFalse(status.clearedStaleWork)
        // A1: the inconclusive path surfaces reconcileSucceeded=false instead of throwing,
        // so callers can treat the unknown queue state as retryable.
        assertFalse(status.reconcileSucceeded)
        verify(exactly = 0) { workManager.cancelWorkById(any()) }
    }

    @Test
    fun `a later successful WorkInfo query works after a failing one`() {
        // AC3 self-heal: the first reconcile fails (recoverable), the second succeeds.
        val inputFile = File(analysisInputDir(), "queued.txt").apply { writeText("ok") }
        val workInfo = mockWorkInfo(
            state = WorkInfo.State.ENQUEUED,
            tags = setOf(workTag(), modelTag(), inputTag(inputFile))
        )
        every {
            workManager.getWorkInfosForUniqueWork(BackgroundAnalysisScheduler.UNIQUE_WORK_NAME)
        } returnsMany listOf(
            failingFuture(RuntimeException("transient")),
            immediateFuture(listOf(workInfo))
        )

        val scheduler = BackgroundAnalysisScheduler(context, workManager)
        val first = runBlocking { scheduler.reconcilePendingWork() }
        val second = runBlocking { scheduler.reconcilePendingWork() }

        assertFalse(first.hasPendingWork)
        assertFalse(first.clearedStaleWork)
        assertFalse(first.reconcileSucceeded)
        assertTrue(second.hasPendingWork)
        assertFalse(second.clearedStaleWork)
        assertTrue(second.reconcileSucceeded)
        assertTrue(inputFile.exists())
    }

    @Test
    fun `reconcilePendingWork re-enqueues every valid survivor regardless of list order`() {
        // A1: a stale-node cancel cascades forward through the chain, but
        // getWorkInfosForUniqueWork() does not guarantee dependency order. A valid survivor
        // that the list places on EITHER side of the stale item may have been appended after
        // it in the chain and so cascade-cancelled. The previous index-based approach missed
        // survivors located before the stale item in the list; every valid non-RUNNING item is
        // now re-enqueued no matter where it sits.
        val validBefore = File(analysisInputDir(), "valid-before.txt").apply { writeText("before") }
        val validAfter = File(analysisInputDir(), "valid-after.txt").apply { writeText("after") }
        val staleId = UUID.randomUUID()

        val validBeforeInfo = mockWorkInfo(
            state = WorkInfo.State.ENQUEUED,
            tags = setOf(workTag(), modelTag(), inputTag(validBefore))
        )
        val staleInfo = mockWorkInfo(
            id = staleId,
            state = WorkInfo.State.ENQUEUED,
            tags = setOf(workTag(), modelTag(), inputTag(File(analysisInputDir(), "gone.txt")))
        )
        val validAfterInfo = mockWorkInfo(
            state = WorkInfo.State.ENQUEUED,
            tags = setOf(workTag(), modelTag(), inputTag(validAfter))
        )
        // Deliberately out of dependency order: a valid item, the stale item, another valid
        // item. Both valid items must be re-enqueued regardless of list position.
        every {
            workManager.getWorkInfosForUniqueWork(BackgroundAnalysisScheduler.UNIQUE_WORK_NAME)
        } returns immediateFuture(listOf(validAfterInfo, staleInfo, validBeforeInfo))
        every {
            workManager.enqueueUniqueWork(
                BackgroundAnalysisScheduler.UNIQUE_WORK_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                any<OneTimeWorkRequest>()
            )
        } returns immediateSuccessOperation()

        val scheduler = BackgroundAnalysisScheduler(context, workManager)
        val status = runBlocking { scheduler.reconcilePendingWork() }

        assertTrue(status.hasPendingWork)
        assertTrue(status.clearedStaleWork)
        assertTrue(validBefore.exists())
        assertTrue(validAfter.exists())
        verify(exactly = 1) { workManager.cancelWorkById(staleId) }
        // Both valid survivors re-enqueued even though one precedes the stale item in the list.
        verify(exactly = 2) {
            workManager.enqueueUniqueWork(
                BackgroundAnalysisScheduler.UNIQUE_WORK_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                any<OneTimeWorkRequest>()
            )
        }
        verify(exactly = 0) { workManager.cancelUniqueWork(any()) }
    }

    @Test
    fun `a queued job tagged with a removed Gemma 3n id is skipped without a legacy download`() {
        // R6/AC21: Gemma 3n left the catalogue, but a job enqueued before the upgrade still
        // carries its persisted model tag. getPendingModels and reconciliation must complete
        // normally, never re-enqueue (or download) the removed id, and still rescue the
        // retained-model survivor that a stale-node cancel cascades through.
        val removedInput = File(analysisInputDir(), "text-removed.txt").apply { writeText("removed") }
        val survivorInput = File(analysisInputDir(), "text-survivor.txt").apply { writeText("survivor") }
        val removedInfo = mockWorkInfo(
            state = WorkInfo.State.ENQUEUED,
            tags = setOf(workTag(), "calendaradd-model:gemma-3n-e2b", inputTag(removedInput))
        )
        val survivorInfo = mockWorkInfo(
            state = WorkInfo.State.ENQUEUED,
            tags = setOf(workTag(), modelTag(), inputTag(survivorInput))
        )
        val staleInfo = mockWorkInfo(
            state = WorkInfo.State.ENQUEUED,
            tags = setOf(workTag(), modelTag(), inputTag(File(analysisInputDir(), "gone.txt")))
        )
        val removedId = removedInfo.id
        val survivorId = survivorInfo.id
        val staleId = staleInfo.id
        every {
            workManager.getWorkInfosForUniqueWork(BackgroundAnalysisScheduler.UNIQUE_WORK_NAME)
        } returns immediateFuture(listOf(removedInfo, staleInfo, survivorInfo))
        val enqueued = mutableListOf<OneTimeWorkRequest>()
        every {
            workManager.enqueueUniqueWork(
                BackgroundAnalysisScheduler.UNIQUE_WORK_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                capture(enqueued)
            )
        } returns immediateSuccessOperation()
        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val requestedUrls = mutableListOf<String>()
        // Real download manager: any network fetch it attempted would be recorded here.
        val modelDownloadManager = ModelDownloadManager(
            context = context,
            preferencesManager = com.calendaradd.usecase.PreferencesManager(context),
            connectionFactory = { url ->
                requestedUrls += url
                throw AssertionError("Unexpected model download: $url")
            }
        )

        val scheduler = BackgroundAnalysisScheduler(context, workManager, modelDownloadManager)
        val pendingModels = runBlocking { scheduler.getPendingModels() }
        val status = runBlocking { scheduler.reconcilePendingWork() }

        // The removed id resolves to nothing pending; only the retained model is kept.
        assertEquals(setOf("gemma-4-e2b"), pendingModels.map { it.id }.toSet())
        assertTrue(status.reconcileSucceeded)
        assertTrue(status.hasPendingWork)
        assertTrue(status.clearedStaleWork)
        verify(exactly = 1) { workManager.cancelWorkById(staleId) }
        // reEnqueueSurvivor ran for both valid items: the survivor was cancelled and
        // re-appended, the removed-id job was skipped before any cancel or enqueue.
        verify(exactly = 1) { workManager.cancelWorkById(survivorId) }
        verify(exactly = 0) { workManager.cancelWorkById(removedId) }
        assertEquals(1, enqueued.size)
        val reEnqueued = enqueued.single()
        assertEquals("gemma-4-e2b", reEnqueued.workSpec.input.getString(BackgroundAnalysisScheduler.KEY_MODEL_ID))
        assertEquals(survivorInput.absolutePath, reEnqueued.workSpec.input.getString(BackgroundAnalysisScheduler.KEY_INPUT_PATH))
        assertTrue(reEnqueued.tags.contains(modelTag()))
        assertTrue(reEnqueued.tags.none { it.contains("gemma-3n") })
        // Neither input is deleted: the survivor is reused, the removed-id job is left to its
        // worker's documented missing-model outcome.
        assertTrue(survivorInput.exists())
        assertTrue(removedInput.exists())
        // No legacy (or any) model download was requested.
        verify(exactly = 0) { downloadManager.enqueue(any()) }
        assertTrue(requestedUrls.none { it.substringAfterLast('/').startsWith("gemma-3n") })
        assertTrue(requestedUrls.isEmpty())
        verify(exactly = 0) { workManager.cancelUniqueWork(any()) }
    }

    @Test
    fun `getPendingModels keeps all managed models when the WorkInfo query fails`() {
        // A2: a transient queue-query failure must not let cleanup delete a model a pending job
        // needs. The conservative result is "keep everything", not "keep nothing".
        every {
            workManager.getWorkInfosForUniqueWork(BackgroundAnalysisScheduler.UNIQUE_WORK_NAME)
        } returns failingFuture(RuntimeException("boom"))

        val scheduler = BackgroundAnalysisScheduler(context, workManager)
        val models = runBlocking { scheduler.getPendingModels() }

        assertEquals(LiteRtModelCatalog.models.toSet(), models)
    }

    @Test
    fun `enqueueText rolls back only the just-persisted input when enqueue fails`() {
        // AC4: enqueue failure after persist must delete only that input and rethrow.
        val preexisting = File(analysisInputDir(), "preexisting.txt").apply { writeText("keep me") }
        val failingOperation = mockk<Operation>()
        every { failingOperation.result } returns failingFuture(RuntimeException("enqueue failed"))
        every {
            workManager.enqueueUniqueWork(
                BackgroundAnalysisScheduler.UNIQUE_WORK_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                any<OneTimeWorkRequest>()
            )
        } returns failingOperation

        val scheduler = BackgroundAnalysisScheduler(context, workManager)
        assertThrows(Exception::class.java) {
            runBlocking { scheduler.enqueueText("Dinner tomorrow", LiteRtModelCatalog.find("gemma-4-e2b")) }
        }

        // The just-persisted input is gone; the unrelated pre-existing input survives.
        assertFalse(analysisInputDir().listFiles().orEmpty().any { it.name.startsWith("text") })
        assertTrue(preexisting.exists())
        verify(exactly = 1) {
            workManager.enqueueUniqueWork(
                BackgroundAnalysisScheduler.UNIQUE_WORK_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                any<OneTimeWorkRequest>()
            )
        }
        verify(exactly = 0) { workManager.cancelUniqueWork(any()) }
    }

    @Test
    fun `enqueueText retains the input when the enqueue observation times out`() {
        // A3/R2: a bounded enqueue timeout is a TimeoutCancellationException, and the request
        // may already be enqueued — the worker must find its input file when it starts. The
        // input is retained for reconciliation to classify (AC4); it still rethrows so the
        // caller knows the request id was never returned.
        val preexisting = File(analysisInputDir(), "preexisting.txt").apply { writeText("keep me") }
        val neverCompletingOp = mockk<Operation>()
        every { neverCompletingOp.result } returns neverCompletingFuture()
        every {
            workManager.enqueueUniqueWork(
                BackgroundAnalysisScheduler.UNIQUE_WORK_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                any<OneTimeWorkRequest>()
            )
        } returns neverCompletingOp

        val scheduler = BackgroundAnalysisScheduler(context, workManager, workManagerDeadlineMs = 200L)
        assertThrows(TimeoutCancellationException::class.java) {
            runBlocking { scheduler.enqueueText("Dinner tomorrow", LiteRtModelCatalog.find("gemma-4-e2b")) }
        }

        // The just-persisted input survives so an enqueued worker can still process it.
        assertTrue(analysisInputDir().listFiles().orEmpty().any { it.name.startsWith("text") })
        assertTrue(preexisting.exists())
        verify(exactly = 0) { workManager.cancelWorkById(any()) }
    }

    @Test
    fun `enqueueText appends work without cancelling the existing chain`() {
        every {
            workManager.enqueueUniqueWork(
                BackgroundAnalysisScheduler.UNIQUE_WORK_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                any<OneTimeWorkRequest>()
            )
        } returns immediateSuccessOperation()

        val scheduler = BackgroundAnalysisScheduler(context, workManager)
        val workId = runBlocking {
            scheduler.enqueueText("Dinner tomorrow", LiteRtModelCatalog.find("gemma-4-e2b"))
        }

        assertTrue(workId.toString().isNotBlank())
        verify {
            workManager.enqueueUniqueWork(
                BackgroundAnalysisScheduler.UNIQUE_WORK_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                any<OneTimeWorkRequest>()
            )
        }
        verify(exactly = 0) { workManager.cancelUniqueWork(BackgroundAnalysisScheduler.UNIQUE_WORK_NAME) }
        assertTrue(analysisInputDir().listFiles().orEmpty().isNotEmpty())
    }

    @Test
    fun `sweep keeps young unreferenced inputs and deletes only aged orphans`() {
        // R5: a young unreferenced file is protected by the grace period; an aged orphan is swept.
        val young = File(analysisInputDir(), "young.txt").apply { writeText("fresh") }
        val aged = File(analysisInputDir(), "aged.txt").apply {
            writeText("old")
            // Age the file past the 10-minute grace window.
            setLastModified(System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(20))
        }
        every {
            workManager.getWorkInfosForUniqueWork(BackgroundAnalysisScheduler.UNIQUE_WORK_NAME)
        } returns immediateFuture(emptyList())

        val scheduler = BackgroundAnalysisScheduler(context, workManager)
        val status = runBlocking { scheduler.reconcilePendingWork() }

        assertFalse(status.hasPendingWork)
        assertTrue(young.exists())
        assertFalse(aged.exists())
    }

    @Test
    fun `promoteInputToEventSource copies image into durable source folder`() {
        val inputFile = File(inputDir, "queued-image.jpg").apply { writeText("image-bytes") }
        val scheduler = BackgroundAnalysisScheduler(context, workManager)

        val source = scheduler.promoteInputToEventSource(inputFile, AnalysisInputType.IMAGE)

        requireNotNull(source)
        assertEquals("image/jpeg", source.mimeType)
        assertEquals("queued-image.jpg", source.displayName)
        assertTrue(File(source.path).exists())
        assertEquals("image-bytes", File(source.path).readText())
        assertTrue(source.path.contains("event-source-files"))
    }

    private fun analysisInputDir(): File =
        File(inputDir, "background-analysis-inputs").apply { mkdirs() }

    private fun workTag() = BackgroundAnalysisScheduler.WORK_TAG
    private fun modelTag() = "calendaradd-model:gemma-4-e2b"
    private fun inputTag(file: File) = "calendaradd-input:${file.absolutePath}"

    private fun mockWorkInfo(
        state: WorkInfo.State,
        tags: Set<String>,
        id: UUID = UUID.randomUUID()
    ): WorkInfo {
        return mockk {
            every { this@mockk.id } returns id
            every { this@mockk.state } returns state
            every { this@mockk.tags } returns tags
        }
    }

    private fun immediateSuccessOperation(): Operation {
        val operation = mockk<Operation>()
        every { operation.result } returns immediateFuture(Operation.SUCCESS)
        return operation
    }

    private fun <T> immediateFuture(value: T): ListenableFuture<T> {
        return object : ListenableFuture<T> {
            override fun get(): T = value
            override fun get(timeout: Long, unit: TimeUnit): T = value
            override fun isDone(): Boolean = true
            override fun isCancelled(): Boolean = false
            override fun cancel(mayInterruptIfRunning: Boolean): Boolean = false
            override fun addListener(listener: Runnable, executor: java.util.concurrent.Executor) {
                executor.execute(listener)
            }
        }
    }

    private fun <T> failingFuture(cause: Throwable): ListenableFuture<T> {
        return object : ListenableFuture<T> {
            override fun get(): T = throw cause
            override fun get(timeout: Long, unit: TimeUnit): T = throw cause
            override fun isDone(): Boolean = true
            override fun isCancelled(): Boolean = false
            override fun cancel(mayInterruptIfRunning: Boolean): Boolean = false
            override fun addListener(listener: Runnable, executor: java.util.concurrent.Executor) {
                executor.execute(listener)
            }
        }
    }

    private fun <T> neverCompletingFuture(): ListenableFuture<T> {
        return object : ListenableFuture<T> {
            override fun get(): T = throw UnsupportedOperationException("never completes")
            override fun get(timeout: Long, unit: TimeUnit): T = throw UnsupportedOperationException("never completes")
            override fun isDone(): Boolean = false
            override fun isCancelled(): Boolean = false
            override fun cancel(mayInterruptIfRunning: Boolean): Boolean = false
            // Intentionally never runs the listener: simulates a stalled WorkManager query.
            override fun addListener(listener: Runnable, executor: java.util.concurrent.Executor) {}
        }
    }
}

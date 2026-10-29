package com.calendaradd.service

import android.app.DownloadManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.provider.CalendarContract
import androidx.core.app.NotificationCompat
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundUpdater
import androidx.work.ListenableWorker
import androidx.work.OneTimeWorkRequest
import androidx.work.Operation
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.calendaradd.usecase.EventDatabase
import com.calendaradd.util.AppLog
import com.calendaradd.util.FileImportHandler
import com.calendaradd.util.pcm16MonoToWav
import com.google.common.util.concurrent.ListenableFuture
import com.google.mlkit.vision.text.TextRecognition
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

class BackgroundAnalysisWorkerTest {

    private lateinit var context: Context
    private lateinit var workManager: WorkManager
    private lateinit var downloadManager: DownloadManager
    private lateinit var foregroundUpdater: ForegroundUpdater
    private lateinit var rootDir: File
    private lateinit var modelDir: File
    private val notificationTitles = mutableListOf<String>()

    @Before
    fun setupWorkerDoubles() {
        rootDir = File("build/tmp/background-analysis-worker-test").apply {
            deleteRecursively()
            mkdirs()
        }
        // Empty model root: the resolved fallback model is never on disk.
        modelDir = File(rootDir, "models").apply { mkdirs() }
        downloadManager = mockk(relaxed = true)
        foregroundUpdater = mockk(relaxed = true)
        workManager = mockk(relaxed = true)
        val sharedPrefs: SharedPreferences = mockk(relaxed = true)
        context = mockk(relaxed = true)
        every { context.applicationContext } returns context
        every { context.noBackupFilesDir } returns rootDir
        every { context.filesDir } returns rootDir
        every { context.getExternalFilesDir(any()) } returns modelDir
        every { context.getSharedPreferences(any(), any()) } returns sharedPrefs
        every { context.getSystemService(Context.DOWNLOAD_SERVICE) } returns downloadManager
        every { context.getSystemService(Context.NOTIFICATION_SERVICE) } returns mockk<NotificationManager>(relaxed = true)

        // Platform doubles only: WorkManager singleton, ML Kit client, Room singleton and the
        // android.jar notification stubs. The worker, scheduler, catalogue and download
        // manager all run their real code.
        mockkObject(WorkManager.Companion)
        every { WorkManager.getInstance(any()) } returns workManager
        mockkStatic(TextRecognition::class)
        every { TextRecognition.getClient(any()) } returns mockk(relaxed = true)
        mockkObject(EventDatabase.Companion)
        every { EventDatabase.getDatabase(any()) } returns mockk(relaxed = true)
        // CalendarContract URIs are null in the android.jar stubs, which would trip the
        // SystemCalendarService constructor the worker builds before any branch runs.
        populateNullStubUri(CalendarContract.Events::class.java)
        populateNullStubUri(CalendarContract.Reminders::class.java)
        populateNullStubUri(CalendarContract.Attendees::class.java)
        mockkConstructor(Intent::class)
        every { anyConstructed<Intent>().setFlags(any()) } answers { self as Intent }
        every { anyConstructed<Intent>().putExtra(any<String>(), any<String>()) } answers { self as Intent }
        mockkStatic(PendingIntent::class)
        every { PendingIntent.getActivity(any(), any(), any(), any()) } returns mockk(relaxed = true)
        mockkConstructor(NotificationCompat.Builder::class)
        every { anyConstructed<NotificationCompat.Builder>().setContentTitle(any()) } answers {
            notificationTitles += firstArg<CharSequence>().toString()
            self as NotificationCompat.Builder
        }
        every { anyConstructed<NotificationCompat.Builder>().build() } returns mockk(relaxed = true)

        // Observed collaborators: every observed method keeps its real behaviour via
        // callOriginal(). MockK only records constructor-mocked calls that are stubbed, so the
        // explicit stubs are what make the exactly = 0 verifications below meaningful.
        mockkConstructor(ModelDownloadManager::class)
        every { anyConstructed<ModelDownloadManager>().isModelDownloaded(any()) } answers { callOriginal() }
        every { anyConstructed<ModelDownloadManager>().startDownload(any(), any()) } answers { callOriginal() }
        mockkConstructor(GemmaLlmService::class)
        coEvery { anyConstructed<GemmaLlmService>().initialize(any(), any(), any(), any()) } coAnswers { callOriginal() }
        mockkObject(AppLog)
        every { AppLog.i(any(), any()) } answers { callOriginal() }
        every { AppLog.e(any(), any(), any()) } answers { callOriginal() }
    }

    @After
    fun tearDownWorkerDoubles() {
        unmockkAll()
    }

    @Test
    fun `queued gemma-3n-e2b job retries on its first attempt when the fallback model is missing`() {
        assertRemovedModelRetries("gemma-3n-e2b")
    }

    @Test
    fun `queued gemma-3n-e2b job fails with the missing-model outcome on its last attempt`() {
        assertRemovedModelFailsTerminally("gemma-3n-e2b")
    }

    @Test
    fun `queued gemma-3n-e4b job retries on its first attempt when the fallback model is missing`() {
        assertRemovedModelRetries("gemma-3n-e4b")
    }

    @Test
    fun `queued gemma-3n-e4b job fails with the missing-model outcome on its last attempt`() {
        assertRemovedModelFailsTerminally("gemma-3n-e4b")
    }

    private fun assertRemovedModelRetries(removedModelId: String) {
        val queuedInput = queuedTextInput()
        val worker = removedModelWorker(removedModelId, queuedInput, runAttemptCount = 0)

        val result = runBlocking { worker.doWork() }

        assertEquals(ListenableWorker.Result.retry(), result)
        assertTrue("queued input must survive for the retry", queuedInput.exists())
        // A retry never resolves the chain, so no successor rescue is attempted.
        verify(exactly = 0) { workManager.enqueueUniqueWork(any<String>(), any(), any<OneTimeWorkRequest>()) }
        assertMissingModelBranchOnly(removedModelId)
    }

    private fun assertRemovedModelFailsTerminally(removedModelId: String) {
        val queuedInput = queuedTextInput()
        val workId = UUID.randomUUID()
        val successorInput = File(queuedInput.parentFile, "text-successor.txt").apply { writeText("successor") }
        val successorInfo = mockWorkInfo(
            state = WorkInfo.State.ENQUEUED,
            tags = setOf(
                BackgroundAnalysisScheduler.WORK_TAG,
                "calendaradd-model:gemma-4-e2b",
                "calendaradd-input:${successorInput.absolutePath}"
            )
        )
        val selfInfo = mockWorkInfo(
            id = workId,
            state = WorkInfo.State.RUNNING,
            tags = setOf(
                BackgroundAnalysisScheduler.WORK_TAG,
                "calendaradd-model:$removedModelId",
                "calendaradd-input:${queuedInput.absolutePath}"
            )
        )
        every {
            workManager.getWorkInfosForUniqueWork(BackgroundAnalysisScheduler.UNIQUE_WORK_NAME)
        } returns immediateFuture(listOf(selfInfo, successorInfo))
        val inputPresentAtRescue = mutableListOf<Boolean>()
        every {
            workManager.enqueueUniqueWork(
                BackgroundAnalysisScheduler.UNIQUE_WORK_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                any<OneTimeWorkRequest>()
            )
        } answers {
            inputPresentAtRescue += queuedInput.exists()
            immediateSuccessOperation()
        }
        val worker = removedModelWorker(removedModelId, queuedInput, runAttemptCount = 1, workId = workId)

        val result = runBlocking { worker.doWork() }

        assertTrue(result is ListenableWorker.Result.Failure)
        // Output data proves the missing-model branch produced the failure, not the generic
        // exception handler (whose message would differ).
        assertEquals(
            workDataOf(
                "error" to "Selected model is no longer downloaded.",
                "analysis_failed" to true
            ),
            result.outputData
        )
        // The successor was rescued while the queued input still existed, then the input of
        // this exhausted job was deleted.
        assertEquals(listOf(true), inputPresentAtRescue)
        assertFalse("exhausted job must delete its queued input", queuedInput.exists())
        assertTrue(successorInput.exists())
        assertMissingModelBranchOnly(removedModelId)
    }

    private fun assertMissingModelBranchOnly(removedModelId: String) {
        // The real catalogue maps every removed id onto the default model, and the worker ran
        // with exactly that resolution.
        assertEquals("gemma-4-e4b", LiteRtModelCatalog.find(removedModelId).id)
        verify(exactly = 1) {
            AppLog.i("BackgroundAnalysisWorker", match { it.startsWith("Starting background analysis") && it.endsWith("model=Gemma 4 E4B") })
        }
        verify {
            anyConstructed<ModelDownloadManager>().isModelDownloaded(match { it.id == "gemma-4-e4b" })
        }
        // The removed id itself is never probed (the terminal rows' successor rescue probes
        // only the successor's own retained gemma-4-e2b model).
        verify(exactly = 0) { anyConstructed<ModelDownloadManager>().isModelDownloaded(match { it.id.startsWith("gemma-3n") }) }
        verify(exactly = 0) { anyConstructed<ModelDownloadManager>().startDownload(any(), any()) }
        verify(exactly = 0) { downloadManager.enqueue(any()) }
        // No inference: the engine is never initialised and no foreground promotion happens.
        coVerify(exactly = 0) { anyConstructed<GemmaLlmService>().initialize(any(), any(), any(), any()) }
        verify(exactly = 0) { foregroundUpdater.setForegroundAsync(any(), any(), any()) }
        verify(exactly = 0) { context.registerComponentCallbacks(any()) }
        // No unexpected exception: the generic handler logs an error and posts "Analysis
        // failed"; the missing-model branch posts only the "Model missing" notification.
        verify(exactly = 0) { AppLog.e(any(), any(), any()) }
        assertEquals(listOf("Model missing"), notificationTitles)
    }

    private fun queuedTextInput(): File {
        val inputDir = File(rootDir, "background-analysis-inputs").apply { mkdirs() }
        return File(inputDir, "text-queued.txt").apply { writeText("Dinner with Sam tomorrow at 7pm") }
    }

    private fun removedModelWorker(
        removedModelId: String,
        queuedInput: File,
        runAttemptCount: Int,
        workId: UUID = UUID.randomUUID()
    ): BackgroundAnalysisWorker {
        val params = mockk<WorkerParameters>(relaxed = true)
        every { params.id } returns workId
        every { params.runAttemptCount } returns runAttemptCount
        every { params.foregroundUpdater } returns foregroundUpdater
        every { params.inputData } returns workDataOf(
            BackgroundAnalysisScheduler.KEY_INPUT_TYPE to AnalysisInputType.TEXT.name,
            BackgroundAnalysisScheduler.KEY_INPUT_PATH to queuedInput.absolutePath,
            BackgroundAnalysisScheduler.KEY_MODEL_ID to removedModelId
        )
        return BackgroundAnalysisWorker(context, params)
    }

    @Test
    fun `hasExceededBackgroundAttemptLimit should stop after two retries`() {
        assertFalse(hasExceededBackgroundAttemptLimit(0))
        assertFalse(hasExceededBackgroundAttemptLimit(1))
        assertTrue(hasExceededBackgroundAttemptLimit(2))
    }

    @Test
    fun `shouldRetryBackgroundFailure should retry only before the attempt limit`() {
        assertTrue(shouldRetryBackgroundFailure(1))
        assertFalse(shouldRetryBackgroundFailure(2))
        assertFalse(shouldRetryBackgroundFailure(3))
    }

    @Test
    fun `exceedsMaxAudioDuration should reject only beyond the five-minute boundary`() {
        val maxDurationMs = 5 * 60 * 1000L

        assertFalse(exceedsMaxAudioDuration(null))
        assertFalse(exceedsMaxAudioDuration(maxDurationMs))
        assertTrue(exceedsMaxAudioDuration(maxDurationMs + 1))
    }

    @Test
    fun `exceedsMaxAudioBytes should reject only beyond the byte ceiling`() {
        assertFalse(exceedsMaxAudioBytes(FileImportHandler.MAX_AUDIO_BYTES))
        assertTrue(exceedsMaxAudioBytes(FileImportHandler.MAX_AUDIO_BYTES + 1))
    }

    @Test
    fun `buildProgressMessage should label retry attempts`() {
        assertEquals("Initializing Gemma...", buildProgressMessage("Initializing Gemma...", 0))
        assertEquals("Retry 2: Initializing Gemma...", buildProgressMessage("Initializing Gemma...", 1))
    }

    @Test
    fun `buildAnalysisTimeoutMessage should suggest heavy mode mitigation`() {
        val message = buildAnalysisTimeoutMessage()

        assertTrue(message.contains("timed out"))
        assertTrue(message.contains("heavy mode"))
    }

    @Test
    fun `probeAudioDurationMs should fall back to wav header duration`() {
        val pcmBytes = ByteArray(16_000 * 2)
        val wavFile = File.createTempFile("calendar-add-duration", ".wav").apply {
            writeBytes(pcm16MonoToWav(pcmBytes))
            deleteOnExit()
        }

        assertEquals(1_000L, probeAudioDurationMs(wavFile))
    }

    @Test
    fun `readWavDurationMs should parse wav with metadata chunk before data`() {
        val wavFile = File.createTempFile("calendar-add-duration-list", ".wav").apply {
            writeBytes(nonCanonicalWav(byteRate = 32_000, dataSize = 32_000))
            deleteOnExit()
        }

        assertEquals(1_000L, readWavDurationMs(wavFile))
    }
}

/** Fills a stub class's null `CONTENT_URI` constant so platform constructors can read it. */
private fun populateNullStubUri(stubClass: Class<*>) {
    val field = stubClass.getField("CONTENT_URI")
    if (field.get(null) != null) return
    // sun.misc.Unsafe is not on the android.jar compile classpath, so it is driven reflectively.
    val unsafeClass = Class.forName("sun.misc.Unsafe")
    val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
    val base = unsafeClass.getMethod("staticFieldBase", java.lang.reflect.Field::class.java).invoke(unsafe, field)
    val offset = unsafeClass.getMethod("staticFieldOffset", java.lang.reflect.Field::class.java).invoke(unsafe, field)
    unsafeClass.getMethod("putObject", Any::class.java, Long::class.javaPrimitiveType, Any::class.java)
        .invoke(unsafe, base, offset, mockk<Uri>(relaxed = true))
}

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
        override fun addListener(listener: Runnable, executor: Executor) {
            executor.execute(listener)
        }
    }
}

private fun nonCanonicalWav(byteRate: Int, dataSize: Int): ByteArray {
    val listPayload = "INFO".toByteArray()
    val fmtPayload = ByteBuffer.allocate(16)
        .order(ByteOrder.LITTLE_ENDIAN)
        .putShort(1.toShort())
        .putShort(1.toShort())
        .putInt(16_000)
        .putInt(byteRate)
        .putShort(2.toShort())
        .putShort(16.toShort())
        .array()
    val dataPayload = ByteArray(dataSize)
    val totalSize = 4 + chunkSize("fmt ", fmtPayload) + chunkSize("LIST", listPayload) + chunkSize("data", dataPayload)
    return ByteBuffer.allocate(8 + totalSize)
        .order(ByteOrder.LITTLE_ENDIAN)
        .put("RIFF".toByteArray())
        .putInt(totalSize)
        .put("WAVE".toByteArray())
        .putChunk("fmt ", fmtPayload)
        .putChunk("LIST", listPayload)
        .putChunk("data", dataPayload)
        .array()
}

private fun chunkSize(id: String, payload: ByteArray): Int {
    return 8 + payload.size + (payload.size % 2)
}

private fun ByteBuffer.putChunk(id: String, payload: ByteArray): ByteBuffer {
    put(id.toByteArray())
    putInt(payload.size)
    put(payload)
    if (payload.size % 2 == 1) {
        put(0)
    }
    return this
}

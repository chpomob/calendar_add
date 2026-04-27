package com.calendaradd.service

import android.content.Context
import androidx.work.Operation
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.google.common.util.concurrent.ListenableFuture
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        inputDir = File("build/tmp/background-analysis-scheduler-test").apply {
            deleteRecursively()
            mkdirs()
        }

        every { context.applicationContext } returns context
        every { context.noBackupFilesDir } returns inputDir
        every { context.filesDir } returns inputDir
    }

    @Test
    fun `reconcilePendingWork should clear stale legacy work without input metadata`() {
        val workInfo = mockWorkInfo(
            state = WorkInfo.State.ENQUEUED,
            tags = setOf(BackgroundAnalysisScheduler.WORK_TAG)
        )
        every {
            workManager.getWorkInfosForUniqueWork(BackgroundAnalysisScheduler.UNIQUE_WORK_NAME)
        } returns immediateFuture(listOf(workInfo))
        every { workManager.cancelUniqueWork(BackgroundAnalysisScheduler.UNIQUE_WORK_NAME) } returns mockk<Operation>(relaxed = true)
        every { workManager.pruneWork() } returns mockk<Operation>(relaxed = true)

        val scheduler = BackgroundAnalysisScheduler(context, workManager)
        val status = kotlinx.coroutines.runBlocking { scheduler.reconcilePendingWork() }

        assertFalse(status.hasPendingWork)
        assertTrue(status.clearedStaleWork)
        verify { workManager.cancelUniqueWork(BackgroundAnalysisScheduler.UNIQUE_WORK_NAME) }
        verify { workManager.pruneWork() }
    }

    @Test
    fun `reconcilePendingWork should keep valid pending work with persisted input`() {
        val inputFile = File(inputDir, "queued-image.jpg").apply { writeText("ok") }
        val workInfo = mockWorkInfo(
            state = WorkInfo.State.ENQUEUED,
            tags = setOf(
                BackgroundAnalysisScheduler.WORK_TAG,
                "calendaradd-model:gemma-4-e2b",
                "calendaradd-input:${inputFile.absolutePath}"
            )
        )
        every {
            workManager.getWorkInfosForUniqueWork(BackgroundAnalysisScheduler.UNIQUE_WORK_NAME)
        } returns immediateFuture(listOf(workInfo))

        val scheduler = BackgroundAnalysisScheduler(context, workManager)
        val status = kotlinx.coroutines.runBlocking { scheduler.reconcilePendingWork() }

        assertTrue(status.hasPendingWork)
        assertFalse(status.clearedStaleWork)
        verify(exactly = 0) { workManager.cancelUniqueWork(any()) }
    }

    @Test
    fun `promoteInputToEventSource should copy image into durable source folder`() {
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

    private fun mockWorkInfo(
        state: WorkInfo.State,
        tags: Set<String>
    ): WorkInfo {
        return mockk {
            every { id } returns UUID.randomUUID()
            every { this@mockk.state } returns state
            every { this@mockk.tags } returns tags
        }
    }

    private fun immediateFuture(workInfos: List<WorkInfo>): ListenableFuture<List<WorkInfo>> {
        return mockk {
            every { get() } returns workInfos
        }
    }
}

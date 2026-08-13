package com.calendaradd.service

import android.content.Context
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.calendaradd.usecase.BatchStatus
import com.calendaradd.usecase.Event
import com.calendaradd.usecase.EventDao
import com.calendaradd.usecase.EventDatabase
import com.google.common.util.concurrent.ListenableFuture
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Covers P11 (F12): [AttachmentRetention]'s oldest-first eviction of queued/promoted media
 * once total storage crosses [AttachmentRetention.QUOTA_BYTES].
 */
class AttachmentRetentionTest {

    private lateinit var context: Context
    private lateinit var workManager: WorkManager
    private lateinit var realDatabase: EventDatabase
    private lateinit var root: File

    private val oneDayMs = 24 * 60 * 60 * 1000L
    private val mib = 1024L * 1024L

    @Before
    fun setup() {
        root = File("build/tmp/attachment-retention-test").apply {
            deleteRecursively()
            mkdirs()
        }
        context = mockk(relaxed = true)
        every { context.applicationContext } returns context
        every { context.noBackupFilesDir } returns root
        every { context.filesDir } returns root

        workManager = mockk(relaxed = true)
        every { workManager.getWorkInfosByTag(BackgroundAnalysisScheduler.WORK_TAG) } returns
            immediateFuture(emptyList())

        realDatabase = Room.inMemoryDatabaseBuilder(fakeRoomContext(), EventDatabase::class.java)
            .setDriver(BundledSQLiteDriver())
            .build()
    }

    @After
    fun tearDown() {
        realDatabase.close()
        root.deleteRecursively()
    }

    private fun fakeRoomContext(): Context {
        val roomContext = mockk<Context>(relaxed = true)
        every { roomContext.applicationContext } returns roomContext
        every { roomContext.getDatabasePath(any()) } answers { File(firstArg<String>()) }
        return roomContext
    }

    private fun dao(): EventDao = realDatabase.eventDao()

    private fun retention() = AttachmentRetention(context, dao(), workManager)

    private fun queueDir(): File = File(root, "background-analysis-inputs").apply { mkdirs() }

    private fun promotedDir(): File = File(root, "event-source-files").apply { mkdirs() }

    /** Fast, disk-cheap way to build a file of an exact logical size for quota math. */
    private fun sizedFile(dir: File, name: String, sizeBytes: Long, ageMs: Long): File {
        val file = File(dir, name)
        RandomAccessFile(file, "rw").use { it.setLength(sizeBytes) }
        file.setLastModified(System.currentTimeMillis() - ageMs)
        return file
    }

    private fun inputTag(file: File) = "calendaradd-input:${file.absolutePath}"

    private fun mockWorkInfo(state: WorkInfo.State, tags: Set<String>): WorkInfo {
        return mockk {
            every { this@mockk.id } returns UUID.randomUUID()
            every { this@mockk.state } returns state
            every { this@mockk.tags } returns tags
        }
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

    @Test
    fun `enforceQuota is a no-op when total attachments are under quota`() = runBlocking {
        val file = sizedFile(queueDir(), "small.jpg", 10 * mib, ageMs = 10 * oneDayMs)

        val result = retention().enforceQuota()

        assertTrue(result.evictedPaths.isEmpty())
        assertEquals(result.totalBytesBefore, result.totalBytesAfter)
        assertTrue(file.exists())
    }

    @Test
    fun `enforceQuota evicts orphaned files oldest-first until back under quota`() = runBlocking {
        // AC1: 30 x 20 MiB (600 MiB total) orphan files, all past the grace period, sorted
        // oldest (index 0) to newest (index 29). 600 MiB is 88 MiB over the 512 MiB quota, so
        // the 5 oldest (100 MiB) must go to land back under quota.
        val sizeEach = 20 * mib
        val files = (0 until 30).map { i ->
            sizedFile(queueDir(), "orphan-$i.jpg", sizeEach, ageMs = (30 - i) * oneDayMs)
        }

        val result = retention().enforceQuota()

        assertEquals(5, result.evictedPaths.size)
        assertEquals(files.take(5).map { it.absolutePath }, result.evictedPaths)
        files.take(5).forEach { assertFalse(it.exists()) }
        files.drop(5).forEach { assertTrue(it.exists()) }
        assertTrue(result.totalBytesAfter <= AttachmentRetention.QUOTA_BYTES)
        assertEquals(600 * mib - 5 * sizeEach, result.totalBytesAfter)
    }

    @Test
    fun `enforceQuota never evicts a file referenced by a pending batch, even past the grace period`() = runBlocking {
        // AC2: the only file present is well over quota by itself and long past the grace
        // period, but it belongs to a still-PENDING (unfinished) journal batch, so eviction
        // must leave it alone entirely.
        val file = sizedFile(promotedDir(), "pending-source.jpg", 550 * mib, ageMs = 10 * oneDayMs)
        dao().getOrCreateBatch(
            batchKey = "pending-batch",
            sourceAttachmentPath = file.absolutePath
        )

        val result = retention().enforceQuota()

        assertTrue(result.evictedPaths.isEmpty())
        assertTrue(file.exists())
        assertEquals(result.totalBytesBefore, result.totalBytesAfter)
    }

    @Test
    fun `enforceQuota never evicts a file referenced by a RUNNING worker`() = runBlocking {
        val protectedFile = sizedFile(queueDir(), "running-input.jpg", 300 * mib, ageMs = 10 * oneDayMs)
        val evictableFile = sizedFile(queueDir(), "orphan-input.jpg", 300 * mib, ageMs = 10 * oneDayMs)
        every { workManager.getWorkInfosByTag(BackgroundAnalysisScheduler.WORK_TAG) } returns
            immediateFuture(listOf(mockWorkInfo(WorkInfo.State.RUNNING, setOf(inputTag(protectedFile)))))

        val result = retention().enforceQuota()

        assertTrue(protectedFile.exists())
        assertFalse(evictableFile.exists())
        assertTrue(result.evictedPaths.contains(evictableFile.absolutePath))
        assertFalse(result.evictedPaths.contains(protectedFile.absolutePath))
        assertTrue(result.totalBytesAfter <= AttachmentRetention.QUOTA_BYTES)
    }

    @Test
    fun `enforceQuota never evicts a file referenced by an active (enqueued) download`() = runBlocking {
        // "Active download" is modeled as a job still ENQUEUED (e.g. waiting on a model
        // download before it can run) rather than only a RUNNING worker.
        val protectedFile = sizedFile(queueDir(), "enqueued-input.jpg", 300 * mib, ageMs = 10 * oneDayMs)
        val evictableFile = sizedFile(queueDir(), "orphan-input.jpg", 300 * mib, ageMs = 10 * oneDayMs)
        every { workManager.getWorkInfosByTag(BackgroundAnalysisScheduler.WORK_TAG) } returns
            immediateFuture(listOf(mockWorkInfo(WorkInfo.State.ENQUEUED, setOf(inputTag(protectedFile)))))

        val result = retention().enforceQuota()

        assertTrue(protectedFile.exists())
        assertFalse(evictableFile.exists())
    }

    @Test
    fun `enforceQuota does not evict an orphan younger than the grace period even if it alone exceeds quota`() = runBlocking {
        val file = sizedFile(queueDir(), "fresh-orphan.jpg", 550 * mib, ageMs = 60 * 60 * 1000L)

        val result = retention().enforceQuota()

        assertTrue(result.evictedPaths.isEmpty())
        assertTrue(file.exists())
    }

    @Test
    fun `enforceQuota evicts an orphan older than the grace period`() = runBlocking {
        val file = sizedFile(queueDir(), "aged-orphan.jpg", 550 * mib, ageMs = oneDayMs + 60_000L)

        val result = retention().enforceQuota()

        assertTrue(result.evictedPaths.contains(file.absolutePath))
        assertFalse(file.exists())
    }

    @Test
    fun `enforceQuota marks a rolled-back batch's attachment evicted without touching event rows or other journal rows`() = runBlocking {
        // AC3: an unrelated event row (simulating an existing calendar entry) must survive
        // untouched, and the rolled-back batch's journal row must be marked, not deleted.
        val keptEventId = dao().insert(
            Event(title = "Unrelated kept event", sourceAttachmentPath = "")
        )

        val file = sizedFile(promotedDir(), "rolled-back-source.jpg", 550 * mib, ageMs = 10 * oneDayMs)
        val batch = dao().getOrCreateBatch(
            batchKey = "rolled-back-batch",
            sourceAttachmentPath = file.absolutePath
        )
        dao().rollbackBatch(batch.id)

        val result = retention().enforceQuota()

        assertTrue(result.evictedPaths.contains(file.absolutePath))
        assertFalse(file.exists())

        val batchAfter = requireNotNull(dao().getBatchById(batch.id))
        assertEquals(BatchStatus.ROLLED_BACK.name, batchAfter.status)
        assertEquals("", batchAfter.sourceAttachmentPath)
        assertTrue(dao().findBatchesByAttachmentPath(file.absolutePath).isEmpty())

        assertTrue(dao().getAllEventsOnce().any { it.id == keptEventId })
    }

    @Test
    fun `enforceQuota never evicts a file directly referenced by an events row even when its batch row disagrees`() = runBlocking {
        // A2: simulates a worker retry — the batch journal row (same batchKey across attempts)
        // still points at an earlier attempt's now-gone path, while the event actually committed
        // references this attempt's path directly. Direct event reference must protect the file
        // even though findBatchesByAttachmentPath(file path) finds no batch row for it at all.
        val file = sizedFile(promotedDir(), "retry-source.jpg", 550 * mib, ageMs = 10 * oneDayMs)
        dao().getOrCreateBatch(
            batchKey = "retried-batch",
            sourceAttachmentPath = "/no/longer/exists/first-attempt.jpg"
        )
        dao().insert(Event(title = "Committed via retry", sourceAttachmentPath = file.absolutePath))

        val result = retention().enforceQuota()

        assertTrue(result.evictedPaths.isEmpty())
        assertTrue(file.exists())
    }

    @Test
    fun `enforceQuota marks every rolled-back batch row sharing the evicted path`() = runBlocking {
        // A3: sourceAttachmentPath is not unique — two independent batches can end up pointing
        // at the same shared file. Both rolled-back rows must be marked evicted, not just one.
        val file = sizedFile(promotedDir(), "shared-source.jpg", 550 * mib, ageMs = 10 * oneDayMs)
        val batchA = dao().getOrCreateBatch(batchKey = "shared-batch-a", sourceAttachmentPath = file.absolutePath)
        val batchB = dao().getOrCreateBatch(batchKey = "shared-batch-b", sourceAttachmentPath = file.absolutePath)
        dao().rollbackBatch(batchA.id)
        dao().rollbackBatch(batchB.id)

        val result = retention().enforceQuota()

        assertTrue(result.evictedPaths.contains(file.absolutePath))
        assertFalse(file.exists())
        assertEquals("", requireNotNull(dao().getBatchById(batchA.id)).sourceAttachmentPath)
        assertEquals("", requireNotNull(dao().getBatchById(batchB.id)).sourceAttachmentPath)
    }

    @Test
    fun `enforceQuota never evicts an orphan younger than 24h even though the scheduler would sweep it after 10 minutes`() = runBlocking {
        // A4: AttachmentRetention must not delegate any part of its scan to the scheduler's own
        // reconcile+sweep, since that sweep evicts unreferenced queue files after only 10
        // minutes — bypassing this class's 24-hour grace and quota-triggered contract entirely.
        // A file well past the scheduler's 10-minute window but inside this class's 24-hour
        // grace must still survive.
        val file = sizedFile(queueDir(), "recent-orphan.jpg", 550 * mib, ageMs = 15 * 60 * 1000L)

        val result = retention().enforceQuota()

        assertTrue(result.evictedPaths.isEmpty())
        assertTrue(file.exists())
    }
}

package com.calendaradd.service

import android.app.DownloadManager
import android.content.Context
import android.database.Cursor
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Environment
import android.telephony.TelephonyManager
import com.calendaradd.usecase.PreferencesManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ModelDownloadManagerTest {

    private companion object {
        const val GEMMA_3N_E2B = "gemma-3n-E2B-it-int4.litertlm"
        const val GEMMA_3N_E4B = "gemma-3n-E4B-it-int4.litertlm"
    }

    private lateinit var context: Context
    private lateinit var preferencesManager: PreferencesManager
    private lateinit var downloadManager: DownloadManager
    private lateinit var modelDir: File
    private lateinit var manager: ModelDownloadManager

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        preferencesManager = mockk(relaxed = true)
        downloadManager = mockk(relaxed = true)
        modelDir = File("build/tmp/model-download-manager-test").apply {
            deleteRecursively()
            mkdirs()
        }

        every { context.getSystemService(Context.DOWNLOAD_SERVICE) } returns downloadManager
        every { context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) } returns modelDir
        every { context.filesDir } returns modelDir
        every { preferencesManager.selectedModelId } returns LiteRtModelCatalog.DEFAULT_MODEL_ID

        manager = ModelDownloadManager(context, preferencesManager)
    }

    @Test
    fun `cleanupUnusedModelFiles should keep selected model and delete known stale files`() {
        val keepModel = LiteRtModelCatalog.find("qwen-3_5-0_8b")
        // A3: cleanup unconditionally preserves the live selection, so make keepModel the
        // selected model; gemma-4-e2b is then genuinely unused and eligible for deletion.
        every { preferencesManager.selectedModelId } returns keepModel.id

        val keepFile = File(modelDir, keepModel.fileName).apply { writeText("keep") }
        val currentOtherFile = File(modelDir, LiteRtModelCatalog.find("gemma-4-e2b").fileName).apply { writeText("delete") }
        val legacyFile = File(modelDir, "qwen3.5-4b-model_multimodal.litertlm").apply { writeText("delete") }
        val unrelatedFile = File(modelDir, "notes.txt").apply { writeText("keep") }

        manager.cleanupUnusedModelFiles(listOf(keepModel))

        assertTrue(keepFile.exists())
        assertFalse(currentOtherFile.exists())
        assertFalse(legacyFile.exists())
        assertTrue(unrelatedFile.exists())
    }

    // ----- A3: cleanup always preserves the live selection, even when the caller's snapshot is stale -----

    @Test
    fun `A3 cleanup preserves the currently-selected model even when absent from keepModels`() {
        val keepModel = LiteRtModelCatalog.find("qwen-3_5-0_8b")
        val selectedModel = LiteRtModelCatalog.find("gemma-4-e2b")
        every { preferencesManager.selectedModelId } returns selectedModel.id

        val keepFile = File(modelDir, keepModel.fileName).apply { writeText("keep") }
        val selectedFile = File(modelDir, selectedModel.fileName).apply { writeText("selected") }

        // Caller passes a stale snapshot that omits the model the user just selected.
        manager.cleanupUnusedModelFiles(listOf(keepModel))

        assertTrue(keepFile.exists())
        assertTrue(selectedFile.exists())
    }

    @Test
    fun `cleanupUnusedModelFiles should keep every queued model file`() {
        val currentModel = LiteRtModelCatalog.find("gemma-4-e2b")
        val queuedModel = LiteRtModelCatalog.find("gemma-4-e4b")
        val otherModel = LiteRtModelCatalog.find("qwen-3_5-0_8b")

        val currentFile = File(modelDir, currentModel.fileName).apply { writeText("keep") }
        val queuedFile = File(modelDir, queuedModel.fileName).apply { writeText("keep") }
        val otherFile = File(modelDir, otherModel.fileName).apply { writeText("delete") }

        manager.cleanupUnusedModelFiles(listOf(currentModel, queuedModel))

        assertTrue(currentFile.exists())
        assertTrue(queuedFile.exists())
        assertFalse(otherFile.exists())
    }

    // ----- R6: leftover Gemma 3n files from the removed catalogue entries -----

    @Test
    fun `cleanupUnusedModelFiles deletes leftover Gemma 3n files and keeps the selected model`() {
        val selectedModel = LiteRtModelCatalog.find(LiteRtModelCatalog.DEFAULT_MODEL_ID)
        val selectedFile = File(modelDir, selectedModel.fileName).apply { writeText("selected") }
        val gemma3nE2b = File(modelDir, GEMMA_3N_E2B).apply { writeText("stale") }
        val gemma3nE4b = File(modelDir, GEMMA_3N_E4B).apply { writeText("stale") }
        val emptyCursor = activeCursor(emptyList())
        val managerWithCursor = ModelDownloadManager(context, preferencesManager, activeStatusQueryOverride = { emptyCursor })

        managerWithCursor.cleanupUnusedModelFiles()

        assertFalse(gemma3nE2b.exists())
        assertFalse(gemma3nE4b.exists())
        assertTrue(selectedFile.exists())
        verify(exactly = 1) { emptyCursor.close() }
    }

    @Test
    fun `cleanupUnusedModelFiles keeps a Gemma 3n file targeted by an active download`() {
        val selectedModel = LiteRtModelCatalog.find(LiteRtModelCatalog.DEFAULT_MODEL_ID)
        // The injected cursor stands in for the status-filtered query (pending|running|paused);
        // run one cleanup per active status so each is exercised through the same seam.
        listOf(
            DownloadManager.STATUS_PENDING,
            DownloadManager.STATUS_RUNNING,
            DownloadManager.STATUS_PAUSED
        ).forEach { status ->
            val selectedFile = File(modelDir, selectedModel.fileName).apply { writeText("selected") }
            val activeFile = File(modelDir, GEMMA_3N_E4B).apply { writeText("downloading") }
            val inactiveFile = File(modelDir, GEMMA_3N_E2B).apply { writeText("stale") }
            val cursor = activeCursor(listOf(ActiveCursorRow(777L, "file://${activeFile.absolutePath}", status)))
            val managerWithCursor = ModelDownloadManager(context, preferencesManager, activeStatusQueryOverride = { cursor })

            managerWithCursor.cleanupUnusedModelFiles()

            assertTrue("active legacy target kept for status $status", activeFile.exists())
            assertTrue("selected model kept for status $status", selectedFile.exists())
            assertFalse("inactive legacy file removed for status $status", inactiveFile.exists())
            verify(exactly = 1) { cursor.close() }
        }
    }

    @Test
    fun `cleanupUnusedModelFiles ignores outside-root malformed and null download URIs`() {
        val selectedModel = LiteRtModelCatalog.find(LiteRtModelCatalog.DEFAULT_MODEL_ID)
        val selectedFile = File(modelDir, selectedModel.fileName).apply { writeText("selected") }
        val rootE2b = File(modelDir, GEMMA_3N_E2B).apply { writeText("stale") }
        val rootE4b = File(modelDir, GEMMA_3N_E4B).apply { writeText("stale") }
        val unrelatedFile = File(modelDir, "notes.txt").apply { writeText("keep") }
        val outsideDir = File("build/tmp/model-download-manager-outside-root").apply {
            deleteRecursively()
            mkdirs()
        }
        val outsideE2b = File(outsideDir, GEMMA_3N_E2B).apply { writeText("someone else's") }

        val cursor = activeCursor(
            listOf(
                // Same legacy file name, but outside the resolved root: must not protect rootE2b.
                ActiveCursorRow(1L, "file://${outsideE2b.absolutePath}", DownloadManager.STATUS_RUNNING),
                // Malformed escape naming the root E4B file: unparsable, so it protects nothing.
                ActiveCursorRow(2L, "file://${rootE4b.absolutePath}%zz", DownloadManager.STATUS_PENDING),
                ActiveCursorRow(3L, null, DownloadManager.STATUS_PAUSED)
            )
        )
        val managerWithCursor = ModelDownloadManager(context, preferencesManager, activeStatusQueryOverride = { cursor })

        managerWithCursor.cleanupUnusedModelFiles()

        assertFalse(rootE2b.exists())
        assertFalse(rootE4b.exists())
        assertTrue(selectedFile.exists())
        assertTrue(unrelatedFile.exists())
        assertTrue(outsideE2b.exists()) // cleanup never reaches outside the resolved root
        verify(exactly = 1) { cursor.close() }
    }

    // ----- AC1: verified readiness is cached across launches, zero enqueues -----

    @Test
    fun `AC1 verified file is ready across two launches and triggers no enqueue`() {
        val content = "verified model payload".toByteArray()
        val model = testModel("ac1", content)
        val modelFile = File(modelDir, model.fileName).apply { writeBytes(content) }

        assertFalse(File(modelDir, model.fileName + ".verified").exists())

        // First launch: verifies (streams SHA) and records the sidecar.
        assertTrue(manager.isModelDownloaded(model))
        assertTrue(File(modelDir, model.fileName + ".verified").exists())

        // Second launch is recognised from the sidecar: tamper the content but preserve length
        // and last-modified, then assert readiness still holds without re-hashing.
        val tampered = ByteArray(content.size) { (('A'.code + it).toByte()) }
        modelFile.writeBytes(tampered)
        val recordedMtime = readSidecarLastModified(modelFile)!!
        modelFile.setLastModified(recordedMtime)
        assertTrue(manager.isModelDownloaded(model))

        // Ready model short-circuits startDownload without enqueueing.
        assertEquals(-1L, manager.startDownload(model))
        verify(exactly = 0) { downloadManager.enqueue(any()) }
    }

    // ----- AC2: mismatched completed files are quarantined, not deleted, excluded from lookup -----

    @Test
    fun `AC2 wrong-length completed file is quarantined with diagnostic and excluded from lookup`() {
        val content = "verified".toByteArray()
        val model = testModel("ac2-len", content)
        val bad = File(modelDir, model.fileName).apply { writeBytes(content + "X".toByteArray()) }

        val result = manager.isModelDownloaded(model)
        assertFalse(result)

        // Atomically moved out of the lookup location.
        assertFalse(bad.exists())
        assertFalse(manager.getModelFile(model).exists())

        val qDir = File(modelDir, ".model-quarantine")
        assertTrue(qDir.exists())
        val quarantined = qDir.listFiles()!!.first { !it.name.endsWith(".diag") }
        assertEquals(content.size + 1L, quarantined.length())

        // User-visible diagnostic recorded.
        val diag = qDir.listFiles()!!.first { it.name.endsWith(".diag") }
        val diagText = diag.readText()
        assertTrue(diagText.contains("modelId=ac2-len"))
        assertTrue(diagText.contains("reason=size mismatch"))

        // No automatic retry: a readiness check never enqueues.
        verify(exactly = 0) { downloadManager.enqueue(any()) }
    }

    @Test
    fun `AC2 size-correct wrong-digest file is quarantined and never deleted`() {
        val content = "verified".toByteArray() // 8 bytes
        val model = testModel("ac2-sha", content)
        val wrongContent = "XERIFIED".toByteArray() // same length, different digest
        assertEquals(content.size, wrongContent.size)
        val bad = File(modelDir, model.fileName).apply { writeBytes(wrongContent) }

        assertFalse(manager.isModelDownloaded(model))
        assertFalse(bad.exists())

        val qDir = File(modelDir, ".model-quarantine")
        val quarantined = qDir.listFiles()!!.first { !it.name.endsWith(".diag") }
        // Never deleted: the original bytes are preserved in quarantine.
        assertArrayEquals(wrongContent, quarantined.readBytes())
    }

    // ----- AC3: explicit network policy (conservative by default, unrestricted by consent) -----

    @Test
    fun `AC3 default conservative policy disables metered and roaming`() {
        val model = testModel("ac3", "x".toByteArray())
        val plan = manager.downloadPlan(model, NetworkPolicy.CONSERVATIVE)
        assertFalse(plan.allowMetered)
        assertFalse(plan.allowRoaming)
    }

    @Test
    fun `AC3 explicit unrestricted policy enables metered and roaming`() {
        val model = testModel("ac3", "x".toByteArray())
        val plan = manager.downloadPlan(model, NetworkPolicy.UNRESTRICTED)
        assertTrue(plan.allowMetered)
        assertTrue(plan.allowRoaming)
    }

    // ----- AC4: lookup and destination share the one resolved root -----

    @Test
    fun `AC4 lookup and destination share the resolved external root`() {
        val model = testModel("ac4ext", "x".toByteArray())
        val plan = manager.downloadPlan(model)

        assertTrue(plan.useExternal)
        assertEquals(manager.getModelFile(model).absolutePath, plan.targetFile.absolutePath)
        assertEquals(modelDir.absolutePath, plan.targetFile.parentFile?.absolutePath)
        assertNull(plan.partFile)
    }

    @Test
    fun `AC4 lookup and destination share the resolved internal root when external is unavailable`() {
        val internalDir = File("build/tmp/model-download-manager-internal").apply {
            deleteRecursively()
            mkdirs()
        }
        val localContext = mockk<Context>(relaxed = true)
        every { localContext.getSystemService(Context.DOWNLOAD_SERVICE) } returns downloadManager
        every { localContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) } returns null
        every { localContext.filesDir } returns internalDir
        every { localContext.getSystemService(Context.CONNECTIVITY_SERVICE) } returns null
        val localManager = ModelDownloadManager(localContext, preferencesManager)

        val model = testModel("ac4int", "x".toByteArray())
        val plan = localManager.downloadPlan(model)

        assertFalse(plan.useExternal)
        // Lookup path == destination path, both under the resolved internal root.
        assertEquals(localManager.getModelFile(model).absolutePath, plan.targetFile.absolutePath)
        assertEquals(internalDir.absolutePath, plan.targetFile.parentFile?.absolutePath)
        assertNotNull(plan.partFile)
        assertEquals(internalDir.absolutePath, plan.partFile?.parentFile?.absolutePath)
    }

    // ----- AC5: active-download discovery returns pending jobs' target files -----

    @Test
    fun `AC5 activeDownloads returns target file of a pending DownloadManager job`() {
        val model = LiteRtModelCatalog.find("gemma-4-e2b")
        val target = File(modelDir, model.fileName)
        val cursor = mockk<Cursor>(relaxed = true)
        every { cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_ID) } returns 0
        every { cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI) } returns 1
        every { cursor.moveToNext() } returnsMany listOf(true, false)
        every { cursor.getLong(0) } returns 777L
        every { cursor.getString(1) } returns "file://${target.absolutePath}"
        val managerWithCursor = ModelDownloadManager(context, preferencesManager, activeStatusQueryOverride = { cursor })

        val actives = managerWithCursor.activeDownloads()

        assertEquals(1, actives.size)
        assertEquals(777L, actives[0].downloadId)
        assertEquals(target.absolutePath, actives[0].targetFile.absolutePath)
    }

    // ----- R4: internal streaming path promotes verified content, quarantines bad content -----

    @Test
    fun `R4 internal streaming promotes verified content into the resolved internal root`() {
        val internalDir = File("build/tmp/model-download-manager-r4-ok").apply {
            deleteRecursively()
            mkdirs()
        }
        val localContext = mockk<Context>(relaxed = true)
        every { localContext.getSystemService(Context.DOWNLOAD_SERVICE) } returns downloadManager
        every { localContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) } returns null
        every { localContext.filesDir } returns internalDir
        val bytes = "streamed verified payload".toByteArray()
        val model = testModel("r4ok", bytes)
        val localManager = ModelDownloadManager(localContext, preferencesManager, connectionFactory = {
            FakeHttpConnection(it, bytes)
        })

        val downloadId = localManager.startDownload(model, NetworkPolicy.UNRESTRICTED)
        assertEquals(ModelDownloadManager.INTERNAL_STREAM_ID, downloadId)

        val statuses = runBlocking { localManager.trackProgress(downloadId, model, NetworkPolicy.UNRESTRICTED).toList() }

        assertTrue(statuses.any { it is DownloadStatus.Progress })
        assertEquals(DownloadStatus.Success, statuses.last())
        val target = File(internalDir, model.fileName)
        assertTrue(target.exists())
        assertArrayEquals(bytes, target.readBytes())
        assertFalse(File(internalDir, model.fileName + ".part").exists())
        assertTrue(File(internalDir, model.fileName + ".verified").exists())
    }

    @Test
    fun `R4 internal streaming quarantines wrong-digest content and reports failure`() {
        val internalDir = File("build/tmp/model-download-manager-r4-bad").apply {
            deleteRecursively()
            mkdirs()
        }
        val localContext = mockk<Context>(relaxed = true)
        every { localContext.getSystemService(Context.DOWNLOAD_SERVICE) } returns downloadManager
        every { localContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) } returns null
        every { localContext.filesDir } returns internalDir
        val goodBytes = "verified payload!!".toByteArray()
        val model = testModel("r4bad", goodBytes)
        val tampered = "tampered payload!!".toByteArray()
        assertEquals(goodBytes.size, tampered.size)
        val localManager = ModelDownloadManager(localContext, preferencesManager, connectionFactory = {
            FakeHttpConnection(it, tampered)
        })

        val downloadId = localManager.startDownload(model, NetworkPolicy.UNRESTRICTED)
        val statuses = runBlocking { localManager.trackProgress(downloadId, model, NetworkPolicy.UNRESTRICTED).toList() }

        assertTrue(statuses.last() is DownloadStatus.Failed)
        assertFalse(File(internalDir, model.fileName).exists())
        assertFalse(File(internalDir, model.fileName + ".part").exists())
        val qDir = File(internalDir, ".model-quarantine")
        assertTrue(qDir.listFiles()!!.any { !it.name.endsWith(".diag") })
    }

    // ----- A3: cleanup holds the selection lock across read+delete, so a concurrent selection
    //           change cannot land mid-cleanup and have its just-selected file deleted -----

    @Test
    fun `A3 cleanup holds the selection lock across its read-then-delete critical section`() {
        val realLock = ReentrantLock()
        every { preferencesManager.modelSelectionLock } returns realLock
        every { preferencesManager.selectedModelId } returns LiteRtModelCatalog.DEFAULT_MODEL_ID

        // An unused file that cleanup will delete once it is past the activeDownloads query.
        val unusedModel = LiteRtModelCatalog.find("gemma-4-e2b")
        val unusedFile = File(modelDir, unusedModel.fileName).apply { writeText("unused") }

        // Gate the activeDownloads query so cleanup pauses mid-critical-section, holding the lock.
        val enteredCleanup = CountDownLatch(1)
        val releaseCleanup = CountDownLatch(1)
        val slowCursor = mockk<Cursor>(relaxed = true)
        every { slowCursor.getColumnIndexOrThrow(any()) } returns 0
        every { slowCursor.moveToNext() } answers {
            enteredCleanup.countDown()
            releaseCleanup.await(5, TimeUnit.SECONDS)
            false
        }
        val managerWithCursor = ModelDownloadManager(context, preferencesManager, activeStatusQueryOverride = { slowCursor })

        val cleanupDone = AtomicBoolean(false)
        val cleanupThread = Thread {
            managerWithCursor.cleanupUnusedModelFiles(emptyList())
            cleanupDone.set(true)
        }
        cleanupThread.start()

        // Cleanup is now inside its locked critical section, blocked on the slow cursor.
        assertTrue(enteredCleanup.await(2, TimeUnit.SECONDS))

        // While cleanup holds the lock, a different thread (the setter runs on the UI thread)
        // cannot acquire it — proving a concurrent selectModel would block until cleanup finishes.
        assertFalse(realLock.tryLock(500, TimeUnit.MILLISECONDS))

        releaseCleanup.countDown()
        cleanupThread.join(5_000)
        assertTrue(cleanupDone.get())
        assertFalse(unusedFile.exists()) // the delete ran inside the locked section

        // After cleanup releases, the lock is free for the next selection write.
        assertTrue(realLock.tryLock(1, TimeUnit.SECONDS))
        realLock.unlock()
    }

    // ----- A2: cleanup protects an in-progress download target even when unselected -----

    @Test
    fun `A2 cleanup preserves the target file of an active pending download`() {
        val activeModel = LiteRtModelCatalog.find("gemma-4-e2b")
        val keepModel = LiteRtModelCatalog.find("gemma-4-e4b")
        val activeFile = File(modelDir, activeModel.fileName).apply { writeText("downloading") }

        val cursor = mockk<Cursor>(relaxed = true)
        every { cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_ID) } returns 0
        every { cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI) } returns 1
        every { cursor.moveToNext() } returnsMany listOf(true, false)
        every { cursor.getLong(0) } returns 999L
        every { cursor.getString(1) } returns "file://${activeFile.absolutePath}"
        val managerWithCursor = ModelDownloadManager(context, preferencesManager, activeStatusQueryOverride = { cursor })

        managerWithCursor.cleanupUnusedModelFiles(listOf(keepModel))

        // Active target is preserved even though it is not in keepModels.
        assertTrue(activeFile.exists())
    }

    // ----- R1 / AC1: startup keep-set preserves selected, queued and active-download, removes only unused -----

    @Test
    fun `AC1 startup keep-set preserves selected queued and active-download and removes only unused`() {
        val selectedModel = LiteRtModelCatalog.find("gemma-4-e2b")
        val queuedModel = LiteRtModelCatalog.find("gemma-4-e4b")
        val activeModel = testModel("active-download", "downloading".toByteArray())
        val unusedModel = LiteRtModelCatalog.find("qwen-3_5-0_8b")

        val selectedFile = File(modelDir, selectedModel.fileName).apply { writeText("selected") }
        val queuedFile = File(modelDir, queuedModel.fileName).apply { writeText("queued") }
        val activeFile = File(modelDir, activeModel.fileName).apply { writeText("downloading") }
        val unusedFile = File(modelDir, unusedModel.fileName).apply { writeText("unused") }

        val cursor = mockk<Cursor>(relaxed = true)
        every { cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_ID) } returns 0
        every { cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI) } returns 1
        every { cursor.moveToNext() } returnsMany listOf(true, false)
        every { cursor.getLong(0) } returns 555L
        every { cursor.getString(1) } returns "file://${activeFile.absolutePath}"
        val managerWithCursor = ModelDownloadManager(context, preferencesManager, activeStatusQueryOverride = { cursor })

        // Startup keep-set = selected + queued. The active-download target is additionally
        // protected by the activeDownloads() query that cleanup runs internally.
        managerWithCursor.cleanupUnusedModelFiles(listOf(selectedModel, queuedModel))

        assertTrue(selectedFile.exists())
        assertTrue(queuedFile.exists())
        assertTrue(activeFile.exists())
        assertFalse(unusedFile.exists())
    }

    // ----- A3: internal streaming aborts a response larger than the expected model size -----

    @Test
    fun `A3 internal streaming aborts a response larger than the expected model size`() {
        val internalDir = File("build/tmp/model-download-manager-a3").apply {
            deleteRecursively()
            mkdirs()
        }
        val localContext = mockk<Context>(relaxed = true)
        every { localContext.getSystemService(Context.DOWNLOAD_SERVICE) } returns downloadManager
        every { localContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) } returns null
        every { localContext.filesDir } returns internalDir
        val good = "verified payload!".toByteArray() // 16 bytes
        val oversized = ByteArray(good.size + 64) { 'A'.code.toByte() }
        val model = testModel("a3", good)
        val localManager = ModelDownloadManager(localContext, preferencesManager, connectionFactory = {
            FakeHttpConnection(it, oversized)
        })

        val downloadId = localManager.startDownload(model, NetworkPolicy.UNRESTRICTED)
        val statuses = runBlocking { localManager.trackProgress(downloadId, model, NetworkPolicy.UNRESTRICTED).toList() }

        assertTrue(statuses.last() is DownloadStatus.Failed)
        assertFalse(File(internalDir, model.fileName).exists())          // never promoted
        assertFalse(File(internalDir, model.fileName + ".part").exists()) // part cleaned up on failure
    }

    // ----- A4: conservative policy blocks an internal download on a roaming unmetered net -----

    @Test
    fun `A4 conservative internal download is blocked on a roaming unmetered network`() {
        val internalDir = File("build/tmp/model-download-manager-a4").apply {
            deleteRecursively()
            mkdirs()
        }
        val caps = mockk<NetworkCapabilities>(relaxed = true)
        every { caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) } returns true
        // NET_CAPABILITY_NOT_ROAMING left as relaxed-false -> treated as roaming.
        val network = mockk<Network>()
        val cm = mockk<ConnectivityManager>()
        every { cm.activeNetwork } returns network
        every { cm.getNetworkCapabilities(network) } returns caps
        val localContext = mockk<Context>(relaxed = true)
        every { localContext.getSystemService(Context.DOWNLOAD_SERVICE) } returns downloadManager
        every { localContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) } returns null
        every { localContext.filesDir } returns internalDir
        every { localContext.getSystemService(Context.CONNECTIVITY_SERVICE) } returns cm
        val localManager = ModelDownloadManager(
            localContext, preferencesManager,
            connectionFactory = { FakeHttpConnection(it, "x".toByteArray()) },
            sdkIntProvider = { 28 } // >= P so the NET_CAPABILITY_NOT_ROAMING guard runs
        )

        val model = testModel("a4", "x".toByteArray())
        val downloadId = localManager.startDownload(model, NetworkPolicy.CONSERVATIVE)
        assertEquals(ModelDownloadManager.INTERNAL_STREAM_ID, downloadId)

        val statuses = runBlocking { localManager.trackProgress(downloadId, model, NetworkPolicy.CONSERVATIVE).toList() }
        val failed = statuses.last() as DownloadStatus.Failed
        assertTrue(failed.error.contains("policy"))
    }

    // A4 (API 26/27): NET_CAPABILITY_NOT_ROAMING is unavailable; the TelephonyManager fallback
    // must still block a conservative internal download when isNetworkRoaming is true.
    @Test
    fun `A4 conservative internal download is blocked on roaming via TelephonyManager on pre-P`() {
        val internalDir = File("build/tmp/model-download-manager-a4-prep").apply {
            deleteRecursively()
            mkdirs()
        }
        val caps = mockk<NetworkCapabilities>(relaxed = true)
        every { caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) } returns true
        val network = mockk<Network>()
        val cm = mockk<ConnectivityManager>()
        every { cm.activeNetwork } returns network
        every { cm.getNetworkCapabilities(network) } returns caps
        val telephony = mockk<TelephonyManager>()
        every { telephony.isNetworkRoaming } returns true
        val localContext = mockk<Context>(relaxed = true)
        every { localContext.getSystemService(Context.DOWNLOAD_SERVICE) } returns downloadManager
        every { localContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) } returns null
        every { localContext.filesDir } returns internalDir
        every { localContext.getSystemService(Context.CONNECTIVITY_SERVICE) } returns cm
        every { localContext.getSystemService(Context.TELEPHONY_SERVICE) } returns telephony
        val localManager = ModelDownloadManager(
            localContext, preferencesManager,
            connectionFactory = { FakeHttpConnection(it, "x".toByteArray()) },
            sdkIntProvider = { Build.VERSION_CODES.O } // pre-P (26/27)
        )

        val model = testModel("a4prep", "x".toByteArray())
        val downloadId = localManager.startDownload(model, NetworkPolicy.CONSERVATIVE)
        assertEquals(ModelDownloadManager.INTERNAL_STREAM_ID, downloadId)

        val statuses = runBlocking { localManager.trackProgress(downloadId, model, NetworkPolicy.CONSERVATIVE).toList() }
        val failed = statuses.last() as DownloadStatus.Failed
        assertTrue(failed.error.contains("policy"))
    }

    private data class ActiveCursorRow(val id: Long, val localUri: String?, val status: Int)

    /** Relaxed DownloadManager cursor serving [rows] through the id/local-uri/status columns. */
    private fun activeCursor(rows: List<ActiveCursorRow>): Cursor {
        val cursor = mockk<Cursor>(relaxed = true)
        var position = -1
        every { cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_ID) } returns 0
        every { cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI) } returns 1
        every { cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS) } returns 2
        every { cursor.moveToNext() } answers { ++position < rows.size }
        every { cursor.getLong(0) } answers { rows[position].id }
        every { cursor.getString(1) } answers { rows[position].localUri }
        every { cursor.getInt(2) } answers { rows[position].status }
        return cursor
    }

    private fun testModel(id: String, content: ByteArray): LiteRtModelConfig {
        val sha = MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(it) }
        return LiteRtModelConfig(
            id = id,
            displayName = id,
            shortName = id,
            description = "test model",
            source = "test",
            downloadUrl = "https://example.com/$id",
            fileName = "$id.litertlm",
            sizeBytes = content.size.toLong(),
            sha256 = sha,
            capabilities = setOf(ModelCapability.TEXT),
            executionProfile = ModelExecutionProfile.CPU_ONLY_MULTIMODAL
        )
    }

    private fun readSidecarLastModified(modelFile: File): Long? {
        val sidecar = File(modelFile.parentFile, modelFile.name + ".verified")
        if (!sidecar.exists()) return null
        return sidecar.readLines()
            .firstOrNull { it.startsWith("verifiedLastModified=") }
            ?.substringAfter("=")
            ?.toLongOrNull()
    }
}

private class FakeHttpConnection(
    url: String,
    private val body: ByteArray
) : HttpURLConnection(URL(url)) {
    override fun disconnect() = Unit
    override fun usingProxy(): Boolean = false
    override fun connect() = Unit
    override fun getResponseCode(): Int = HTTP_OK
    override fun getInputStream() = ByteArrayInputStream(body)
}

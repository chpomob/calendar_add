package com.calendaradd.service

import android.app.DownloadManager
import android.content.Context
import android.os.Environment
import com.calendaradd.usecase.PreferencesManager
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile

class ModelDownloadManagerTest {

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

    @Test
    fun `cleanupUnusedModelFiles should keep every queued model file`() {
        val currentModel = LiteRtModelCatalog.find("gemma-4-e2b")
        val queuedModel = LiteRtModelCatalog.find("gemma-4-e4b")
        val otherModel = LiteRtModelCatalog.find("gemma-3n-e2b")

        val currentFile = File(modelDir, currentModel.fileName).apply { writeText("keep") }
        val queuedFile = File(modelDir, queuedModel.fileName).apply { writeText("keep") }
        val otherFile = File(modelDir, otherModel.fileName).apply { writeText("delete") }

        manager.cleanupUnusedModelFiles(listOf(currentModel, queuedModel))

        assertTrue(currentFile.exists())
        assertTrue(queuedFile.exists())
        assertFalse(otherFile.exists())
    }

    @Test
    fun `isModelDownloaded should require exact size for Gallery pinned Gemma models`() {
        val model = LiteRtModelCatalog.find("gemma-4-e2b")
        val modelFile = File(modelDir, model.fileName)

        modelFile.setSparseLength(model.sizeBytes - 1L)
        assertFalse(manager.isModelDownloaded(model))

        modelFile.setSparseLength(model.sizeBytes)
        assertTrue(manager.isModelDownloaded(model))

        modelFile.setSparseLength(model.sizeBytes + 1L)
        assertFalse(manager.isModelDownloaded(model))
    }

    @Test
    fun `isModelDownloaded should keep threshold validation for approximate third party models`() {
        val model = LiteRtModelCatalog.find("qwen-3_5-0_8b")
        val modelFile = File(modelDir, model.fileName)

        modelFile.setSparseLength(model.minimumExpectedBytes - 1L)
        assertFalse(manager.isModelDownloaded(model))

        modelFile.setSparseLength(model.minimumExpectedBytes)
        assertTrue(manager.isModelDownloaded(model))
    }
}

private fun File.setSparseLength(length: Long) {
    RandomAccessFile(this, "rw").use { file ->
        file.setLength(length)
    }
}

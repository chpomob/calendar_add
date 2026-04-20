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

        manager.cleanupUnusedModelFiles(keepModel)

        assertTrue(keepFile.exists())
        assertFalse(currentOtherFile.exists())
        assertFalse(legacyFile.exists())
        assertTrue(unrelatedFile.exists())
    }
}

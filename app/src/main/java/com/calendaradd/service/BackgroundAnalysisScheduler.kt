package com.calendaradd.service

import android.content.Context
import android.graphics.Bitmap
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val ANALYSIS_INPUT_DIR = "background-analysis-inputs"
private const val MODEL_TAG_PREFIX = "calendaradd-model:"

enum class AnalysisInputType {
    TEXT,
    IMAGE,
    AUDIO
}

class BackgroundAnalysisScheduler(private val context: Context) {
    companion object {
        const val UNIQUE_WORK_NAME = "calendaradd-background-analysis"
        const val WORK_TAG = "calendaradd-background-analysis"

        const val KEY_INPUT_TYPE = "input_type"
        const val KEY_INPUT_PATH = "input_path"
        const val KEY_MODEL_ID = "model_id"
    }

    private val appContext = context.applicationContext
    private val workManager = WorkManager.getInstance(appContext)

    fun enqueueText(input: String, model: LiteRtModelConfig): UUID {
        val inputFile = persistText(input)
        return enqueueWork(AnalysisInputType.TEXT, inputFile, model)
    }

    fun enqueueImage(bitmap: Bitmap, model: LiteRtModelConfig): UUID {
        val inputFile = persistBitmap(bitmap)
        return enqueueWork(AnalysisInputType.IMAGE, inputFile, model)
    }

    fun enqueueAudio(audioData: ByteArray, model: LiteRtModelConfig): UUID {
        val inputFile = persistBytes(audioData, "audio", ".bin")
        return enqueueWork(AnalysisInputType.AUDIO, inputFile, model)
    }

    suspend fun hasPendingWork(): Boolean = withContext(Dispatchers.IO) {
        workManager.getWorkInfosForUniqueWork(UNIQUE_WORK_NAME).get().any { info ->
            info.state == WorkInfo.State.ENQUEUED || info.state == WorkInfo.State.RUNNING || info.state == WorkInfo.State.BLOCKED
        }
    }

    suspend fun getPendingModels(): Set<LiteRtModelConfig> = withContext(Dispatchers.IO) {
        workManager.getWorkInfosForUniqueWork(UNIQUE_WORK_NAME).get()
            .asSequence()
            .filter { info ->
                info.state == WorkInfo.State.ENQUEUED ||
                    info.state == WorkInfo.State.RUNNING ||
                    info.state == WorkInfo.State.BLOCKED
            }
            .mapNotNull { info ->
                val modelId = info.tags.firstOrNull { it.startsWith(MODEL_TAG_PREFIX) }
                    ?.removePrefix(MODEL_TAG_PREFIX)
                LiteRtModelCatalog.models.firstOrNull { it.id == modelId }
            }
            .toSet()
    }

    private fun enqueueWork(inputType: AnalysisInputType, inputFile: File, model: LiteRtModelConfig): UUID {
        val request = OneTimeWorkRequestBuilder<BackgroundAnalysisWorker>()
            .setInputData(
                Data.Builder()
                    .putString(KEY_INPUT_TYPE, inputType.name)
                    .putString(KEY_INPUT_PATH, inputFile.absolutePath)
                    .putString(KEY_MODEL_ID, model.id)
                    .build()
            )
            .addTag(WORK_TAG)
            .addTag("$MODEL_TAG_PREFIX${model.id}")
            .build()

        workManager.enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        return request.id
    }

    private fun persistText(text: String): File {
        val file = createInputFile("text", ".txt")
        file.writeText(text, StandardCharsets.UTF_8)
        return file
    }

    private fun persistBitmap(bitmap: Bitmap): File {
        val file = createInputFile("image", ".jpg")
        FileOutputStream(file).use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)) {
                "Failed to persist image input"
            }
        }
        return file
    }

    private fun persistBytes(bytes: ByteArray, prefix: String, suffix: String): File {
        val file = createInputFile(prefix, suffix)
        file.writeBytes(bytes)
        return file
    }

    private fun createInputFile(prefix: String, suffix: String): File {
        val dir = File(appContext.cacheDir, ANALYSIS_INPUT_DIR).apply { mkdirs() }
        return File.createTempFile(prefix, suffix, dir)
    }
}

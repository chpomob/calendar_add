package com.calendaradd.service

import android.content.Context
import android.graphics.Bitmap
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.calendaradd.usecase.SourceAttachment
import com.calendaradd.util.AppLog
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val ANALYSIS_INPUT_DIR = "background-analysis-inputs"
private const val EVENT_SOURCE_DIR = "event-source-files"
private const val MODEL_TAG_PREFIX = "calendaradd-model:"
private const val INPUT_TAG_PREFIX = "calendaradd-input:"

enum class AnalysisInputType {
    TEXT,
    IMAGE,
    AUDIO
}

data class PendingWorkStatus(
    val hasPendingWork: Boolean,
    val clearedStaleWork: Boolean
)

class BackgroundAnalysisScheduler(
    context: Context,
    private val workManager: WorkManager = WorkManager.getInstance(context.applicationContext)
) {
    companion object {
        private const val TAG = "BackgroundAnalysisScheduler"
        const val UNIQUE_WORK_NAME = "calendaradd-background-analysis"
        const val WORK_TAG = "calendaradd-background-analysis"

        const val KEY_INPUT_TYPE = "input_type"
        const val KEY_INPUT_PATH = "input_path"
        const val KEY_MODEL_ID = "model_id"
    }

    private val appContext = context.applicationContext

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

    fun promoteInputToEventSource(inputFile: File, inputType: AnalysisInputType): SourceAttachment? {
        if (inputType != AnalysisInputType.IMAGE && inputType != AnalysisInputType.AUDIO) return null
        if (!inputFile.exists()) return null

        val extension = when (inputType) {
            AnalysisInputType.IMAGE -> ".jpg"
            AnalysisInputType.AUDIO -> ".bin"
            AnalysisInputType.TEXT -> ".txt"
        }
        val prefix = when (inputType) {
            AnalysisInputType.IMAGE -> "source-image"
            AnalysisInputType.AUDIO -> "source-audio"
            AnalysisInputType.TEXT -> "source-text"
        }
        val mimeType = when (inputType) {
            AnalysisInputType.IMAGE -> "image/jpeg"
            AnalysisInputType.AUDIO -> "audio/*"
            AnalysisInputType.TEXT -> "text/plain"
        }
        val targetDir = eventSourceStorageDir().apply { mkdirs() }
        val targetFile = File.createTempFile(prefix, extension, targetDir)
        inputFile.copyTo(targetFile, overwrite = true)
        return SourceAttachment(
            path = targetFile.absolutePath,
            mimeType = mimeType,
            displayName = inputFile.name
        )
    }

    suspend fun hasPendingWork(): Boolean {
        return reconcilePendingWork().hasPendingWork
    }

    suspend fun getPendingModels(): Set<LiteRtModelConfig> = withContext(Dispatchers.IO) {
        getPendingInfos()
            .asSequence()
            .mapNotNull { info ->
                val modelId = info.tags.firstOrNull { it.startsWith(MODEL_TAG_PREFIX) }
                    ?.removePrefix(MODEL_TAG_PREFIX)
                LiteRtModelCatalog.models.firstOrNull { it.id == modelId }
            }
            .toSet()
    }

    suspend fun reconcilePendingWork(): PendingWorkStatus = withContext(Dispatchers.IO) {
        val pendingInfos = getPendingInfos()
        if (pendingInfos.isEmpty()) {
            return@withContext PendingWorkStatus(
                hasPendingWork = false,
                clearedStaleWork = false
            )
        }

        val staleReasons = pendingInfos.mapNotNull { info ->
            when (val inputPath = extractInputPath(info)) {
                null -> "legacy-or-invalid-metadata:${info.id}"
                else -> if (!File(inputPath).exists()) {
                    "missing-input:${info.id}"
                } else {
                    null
                }
            }
        }

        if (staleReasons.isNotEmpty()) {
            AppLog.w(TAG, "Clearing stale background analysis chain reasons=${staleReasons.joinToString()}")
            workManager.cancelUniqueWork(UNIQUE_WORK_NAME)
            workManager.pruneWork()
            clearPersistedInputs()
            return@withContext PendingWorkStatus(
                hasPendingWork = false,
                clearedStaleWork = true
            )
        }

        PendingWorkStatus(
            hasPendingWork = true,
            clearedStaleWork = false
        )
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
            .addTag("$INPUT_TAG_PREFIX${inputFile.absolutePath}")
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
        val dir = inputStorageDir().apply { mkdirs() }
        return File.createTempFile(prefix, suffix, dir)
    }

    private fun inputStorageDir(): File {
        return File(appContext.noBackupFilesDir, ANALYSIS_INPUT_DIR)
    }

    private fun eventSourceStorageDir(): File {
        return File(appContext.filesDir, EVENT_SOURCE_DIR)
    }

    private fun clearPersistedInputs() {
        inputStorageDir().listFiles()?.forEach { file ->
            if (file.isFile && !file.delete()) {
                AppLog.w(TAG, "Failed to delete stale queued input ${file.absolutePath}")
            }
        }
    }

    private fun getPendingInfos(): List<WorkInfo> {
        return workManager.getWorkInfosForUniqueWork(UNIQUE_WORK_NAME).get().filter { info ->
            info.state == WorkInfo.State.ENQUEUED ||
                info.state == WorkInfo.State.RUNNING ||
                info.state == WorkInfo.State.BLOCKED
        }
    }

    private fun extractInputPath(info: WorkInfo): String? {
        return info.tags.firstOrNull { it.startsWith(INPUT_TAG_PREFIX) }
            ?.removePrefix(INPUT_TAG_PREFIX)
    }
}

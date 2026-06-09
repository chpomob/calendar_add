package com.calendaradd.service

import android.content.Context
import android.graphics.Bitmap
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.calendaradd.usecase.PreferencesManager
import com.calendaradd.usecase.SourceAttachment
import com.calendaradd.util.AppLog
import com.calendaradd.util.hasWavHeader
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private const val ANALYSIS_INPUT_DIR = "background-analysis-inputs"
private const val EVENT_SOURCE_DIR = "event-source-files"
private const val MODEL_TAG_PREFIX = "calendaradd-model:"
private const val INPUT_TAG_PREFIX = "calendaradd-input:"
private const val INPUT_SWEEP_GRACE_PERIOD_MS = 10 * 60 * 1000L

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
    private val workManager: WorkManager = WorkManager.getInstance(context.applicationContext),
    private val modelDownloadManager: ModelDownloadManager =
        ModelDownloadManager(context.applicationContext, PreferencesManager(context.applicationContext))
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

    suspend fun enqueueText(input: String, model: LiteRtModelConfig): UUID = withContext(Dispatchers.IO) {
        val inputFile = persistText(input)
        enqueueWork(AnalysisInputType.TEXT, inputFile, model)
    }

    suspend fun enqueueImage(bitmap: Bitmap, model: LiteRtModelConfig): UUID = withContext(Dispatchers.IO) {
        val inputFile = persistBitmap(bitmap)
        enqueueWork(AnalysisInputType.IMAGE, inputFile, model)
    }

    suspend fun enqueueAudio(
        audioData: ByteArray,
        model: LiteRtModelConfig,
        mimeType: String? = null
    ): UUID = withContext(Dispatchers.IO) {
        val resolvedMimeType = audioData.inferAudioMimeType(mimeType)
        val inputFile = persistBytes(audioData, "audio", resolvedMimeType.audioFileExtension())
        enqueueWork(AnalysisInputType.AUDIO, inputFile, model)
    }

    fun promoteInputToEventSource(inputFile: File, inputType: AnalysisInputType): SourceAttachment? {
        if (inputType != AnalysisInputType.IMAGE && inputType != AnalysisInputType.AUDIO) return null
        if (!inputFile.exists()) return null

        val extension = when (inputType) {
            AnalysisInputType.IMAGE -> ".jpg"
            AnalysisInputType.AUDIO -> inputFile.extension.takeIf { it.isNotBlank() }?.let { ".$it" } ?: ".wav"
            AnalysisInputType.TEXT -> ".txt"
        }
        val prefix = when (inputType) {
            AnalysisInputType.IMAGE -> "source-image"
            AnalysisInputType.AUDIO -> "source-audio"
            AnalysisInputType.TEXT -> "source-text"
        }
        val mimeType = when (inputType) {
            AnalysisInputType.IMAGE -> "image/jpeg"
            AnalysisInputType.AUDIO -> inputFile.extension.audioMimeTypeForExtension()
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
            sweepUnreferencedInputs(emptySet())
            return@withContext PendingWorkStatus(
                hasPendingWork = false,
                clearedStaleWork = false
            )
        }

        val referencedInputs = pendingInfos.mapNotNull { extractInputPath(it) }.toSet()
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

        sweepUnreferencedInputs(referencedInputs)
        PendingWorkStatus(
            hasPendingWork = true,
            clearedStaleWork = false
        )
    }

    private fun enqueueWork(inputType: AnalysisInputType, inputFile: File, model: LiteRtModelConfig): UUID {
        // Network is only required when the worker may still need to (re-)download the
        // model. Extraction itself is 100% on-device, so requiring connectivity when the
        // model is already cached just blocks jobs on a flaky/offline link unnecessarily.
        val modelAlreadyOnDisk = runCatching { modelDownloadManager.isModelDownloaded(model) }
            .getOrElse { error ->
                AppLog.w(TAG, "Could not probe model presence; assuming network required", error)
                false
            }
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(
                if (modelAlreadyOnDisk) NetworkType.NOT_REQUIRED else NetworkType.CONNECTED
            )
            .setRequiresBatteryNotLow(true)
            .build()

        val request = OneTimeWorkRequestBuilder<BackgroundAnalysisWorker>()
            .setInputData(
                Data.Builder()
                    .putString(KEY_INPUT_TYPE, inputType.name)
                    .putString(KEY_INPUT_PATH, inputFile.absolutePath)
                    .putString(KEY_MODEL_ID, model.id)
                    .build()
            )
            .setConstraints(constraints)
            .setBackoffCriteria(
                androidx.work.BackoffPolicy.EXPONENTIAL,
                1, TimeUnit.MINUTES
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

    private fun sweepUnreferencedInputs(referencedPaths: Set<String>) {
        val now = System.currentTimeMillis()
        inputStorageDir().listFiles()?.forEach { file ->
            val isTooYoungToSweep = now - file.lastModified() < INPUT_SWEEP_GRACE_PERIOD_MS
            if (file.isFile && !isTooYoungToSweep && file.absolutePath !in referencedPaths && !file.delete()) {
                AppLog.w(TAG, "Failed to delete orphan queued input ${file.absolutePath}")
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

private fun ByteArray.inferAudioMimeType(declaredMimeType: String?): String {
    return declaredMimeType
        ?.takeIf { it.startsWith("audio/", ignoreCase = true) }
        ?: if (hasWavHeader()) "audio/wav" else "audio/mpeg"
}

private fun String.audioFileExtension(): String {
    return when (lowercase()) {
        "audio/wav", "audio/wave", "audio/x-wav" -> ".wav"
        "audio/mpeg", "audio/mp3" -> ".mp3"
        "audio/mp4", "audio/aac", "audio/x-m4a" -> ".m4a"
        "audio/ogg" -> ".ogg"
        "audio/flac" -> ".flac"
        else -> ".audio"
    }
}

private fun String.audioMimeTypeForExtension(): String {
    return when (lowercase()) {
        "wav" -> "audio/wav"
        "mp3" -> "audio/mpeg"
        "m4a", "mp4", "aac" -> "audio/mp4"
        "ogg" -> "audio/ogg"
        "flac" -> "audio/flac"
        else -> "audio/*"
    }
}

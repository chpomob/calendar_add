package com.calendaradd.service

import android.app.DownloadManager
import android.content.Context
import android.os.Environment
import androidx.core.net.toUri
import com.calendaradd.usecase.PreferencesManager
import com.calendaradd.util.AppLog
import java.io.BufferedInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.security.MessageDigest
import java.util.Locale

/**
 * Manages downloads for selectable LiteRT-LM models.
 */
class ModelDownloadManager(
    private val context: Context,
    private val preferencesManager: PreferencesManager
) {
    companion object {
        private const val TAG = "ModelDownloadManager"
        private val LEGACY_MODEL_FILE_NAMES = setOf(
            "qwen3.5-0.8b-model_multimodal.litertlm",
            "qwen3.5-4b-model_multimodal.litertlm",
            "qwen35_mm_q8_ekv4096.litertlm"
        )
    }

    private val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    private fun modelStorageDir(): File {
        return context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
    }

    /**
     * Returns the local file path where the model should be stored.
     * Use externalFilesDir (Downloads) which is app-specific but accessible by DownloadManager.
     */
    fun getModelFile(model: LiteRtModelConfig = getSelectedModel()): File {
        val dir = modelStorageDir()
        if (!dir.exists()) dir.mkdirs()
        return File(dir, model.fileName)
    }

    fun getSelectedModel(): LiteRtModelConfig {
        return LiteRtModelCatalog.find(preferencesManager.selectedModelId)
    }

    /**
     * Checks if the model is already downloaded.
     */
    fun isModelDownloaded(model: LiteRtModelConfig = getSelectedModel()): Boolean {
        val file = getModelFile(model)
        if (!file.exists()) return false
        val fileSize = file.length()
        return fileSize == model.sizeBytes
    }

    /**
     * Estimates if there is enough disk space for the model.
     */
    fun hasEnoughSpace(model: LiteRtModelConfig = getSelectedModel()): Boolean {
        val dir = modelStorageDir()
        val availableBytes = dir.usableSpace
        return availableBytes > model.requiredFreeSpaceBytes
    }

    /**
     * Removes stale model files managed by the app while preserving the active one.
     */
    fun cleanupUnusedModelFiles(keepModels: Collection<LiteRtModelConfig> = listOf(getSelectedModel())) {
        val dir = modelStorageDir()
        if (!dir.exists()) return
        val keepFileNames = keepModels.mapTo(mutableSetOf()) { it.fileName }

        val managedFileNames = LiteRtModelCatalog.models.mapTo(mutableSetOf<String>()) { it.fileName }.apply {
            addAll(LEGACY_MODEL_FILE_NAMES)
        }

        dir.listFiles()
            ?.filter { file -> file.isFile && file.name in managedFileNames && file.name !in keepFileNames }
            ?.forEach { file ->
                if (file.delete()) {
                    AppLog.i(TAG, "Removed unused model file ${file.name}")
                } else {
                    AppLog.w(TAG, "Failed to remove unused model file ${file.name}")
                }
            }
    }

    /**
     * Starts the download of the selected LiteRT-LM model.
     * Returns the download ID, or -1 if the model is already fully downloaded.
     */
    fun startDownload(model: LiteRtModelConfig = getSelectedModel()): Long {
        if (!hasEnoughSpace(model)) {
            throw IllegalStateException(
                "Not enough disk space to download ${model.displayName} (requires ~${model.requiredFreeSpaceLabel} free)."
            )
        }

        val targetFile = getModelFile(model)

        // If the model is already fully downloaded, don't re-download.
        // This prevents unnecessary re-downloads after app updates where
        // DownloadManager IDs are invalidated but the file survives in
        // externalFilesDir.
        if (isModelDownloaded(model)) {
            AppLog.i(TAG, "Model ${model.shortName} is already fully downloaded, skipping re-download")
            return -1
        }

        findActiveDownloadId(targetFile, model)?.let { downloadId ->
            AppLog.i(TAG, "Re-attaching to active model download id=$downloadId file=${targetFile.name}")
            preferencesManager.activeModelDownloadId = downloadId
            preferencesManager.activeModelDownloadModelId = model.id
            return downloadId
        }

        // Only delete partial/incomplete downloads once no DownloadManager job is still writing them.
        // DownloadManager may flag existing files with ERROR_FILE_ALREADY_EXISTS
        // if it still tracks a previous download ID.
        if (targetFile.exists()) {
            AppLog.w(TAG, "Removing incomplete/stale model file ${targetFile.name} before re-download")
            targetFile.delete()
        }

        val request = DownloadManager.Request(model.downloadUrl.toUri())
            .setTitle("Downloading ${model.displayName}")
            .setDescription("Required for local ${model.capabilitySummary.lowercase(Locale.ROOT)} extraction")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, model.fileName)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)

        return downloadManager.enqueue(request).also { downloadId ->
            preferencesManager.activeModelDownloadId = downloadId
            preferencesManager.activeModelDownloadModelId = model.id
        }
    }

    /**
     * Tracks the progress of a download ID.
     */
    fun trackProgress(
        downloadId: Long,
        model: LiteRtModelConfig = getSelectedModel()
    ): Flow<DownloadStatus> = flow {
        if (downloadId == -1L) {
            emit(DownloadStatus.Success)
            return@flow
        }

        var isDownloading = true
        while (isDownloading) {
            val query = DownloadManager.Query().setFilterById(downloadId)
            downloadManager.query(query)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    val bytesDownloaded = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                    val bytesTotal = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))

                    when (status) {
                        DownloadManager.STATUS_SUCCESSFUL -> {
                            if (verifyDownloadedModel(model)) {
                                preferencesManager.clearActiveModelDownload(downloadId)
                                emit(DownloadStatus.Success)
                            } else {
                                getModelFile(model).delete()
                                preferencesManager.clearActiveModelDownload(downloadId)
                                emit(DownloadStatus.Failed("Downloaded model checksum did not match. Try downloading again."))
                            }
                            isDownloading = false
                        }
                        DownloadManager.STATUS_FAILED -> {
                            val reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                            val message = when (reason) {
                                DownloadManager.ERROR_CANNOT_RESUME -> "Cannot resume download."
                                DownloadManager.ERROR_DEVICE_NOT_FOUND -> "Device not found."
                                DownloadManager.ERROR_FILE_ALREADY_EXISTS -> "File already exists."
                                DownloadManager.ERROR_FILE_ERROR -> "File error."
                                DownloadManager.ERROR_HTTP_DATA_ERROR -> "HTTP data error."
                                DownloadManager.ERROR_INSUFFICIENT_SPACE -> "Insufficient disk space."
                                DownloadManager.ERROR_TOO_MANY_REDIRECTS -> "Too many redirects."
                                DownloadManager.ERROR_UNHANDLED_HTTP_CODE -> "Unhandled HTTP code."
                                else -> "Download failed (error code: $reason). Please check your connection."
                            }
                            preferencesManager.clearActiveModelDownload(downloadId)
                            emit(DownloadStatus.Failed(message))
                            isDownloading = false
                        }
                        DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PAUSED -> {
                            val progress = if (bytesTotal > 0) (bytesDownloaded * 100 / bytesTotal).toInt() else 0
                            emit(DownloadStatus.Progress(progress))
                        }
                    }
                } else {
                    emit(DownloadStatus.Failed("Download was not found by the system downloader."))
                    isDownloading = false
                }
            }
            if (isDownloading) delay(1000)
        }
    }.flowOn(Dispatchers.IO)

    private fun findActiveDownloadId(targetFile: File, model: LiteRtModelConfig): Long? {
        val savedId = preferencesManager.activeModelDownloadId
        if (savedId > 0L && preferencesManager.activeModelDownloadModelId == model.id) {
            if (isDownloadActiveForFile(savedId, targetFile)) {
                return savedId
            }
            preferencesManager.clearActiveModelDownload(savedId)
        }

        val activeStatuses = DownloadManager.STATUS_PENDING or
            DownloadManager.STATUS_RUNNING or
            DownloadManager.STATUS_PAUSED
        val query = DownloadManager.Query().setFilterByStatus(activeStatuses)
        downloadManager.query(query)?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)
            val localUriColumn = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI)
            while (cursor.moveToNext()) {
                val localUri = cursor.getString(localUriColumn)
                if (localUriMatchesFile(localUri, targetFile)) {
                    return cursor.getLong(idColumn)
                }
            }
        }
        return null
    }

    private fun isDownloadActiveForFile(downloadId: Long, targetFile: File): Boolean {
        val activeStatuses = DownloadManager.STATUS_PENDING or
            DownloadManager.STATUS_RUNNING or
            DownloadManager.STATUS_PAUSED
        val query = DownloadManager.Query()
            .setFilterById(downloadId)
            .setFilterByStatus(activeStatuses)
        downloadManager.query(query)?.use { cursor ->
            if (!cursor.moveToFirst()) return false
            val localUri = cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI))
            return localUriMatchesFile(localUri, targetFile)
        }
        return false
    }

    private fun localUriMatchesFile(localUri: String?, targetFile: File): Boolean {
        if (localUri.isNullOrBlank()) return false
        val path = runCatching { localUri.toUri().path }.getOrNull() ?: return false
        return File(path).absolutePath == targetFile.absolutePath
    }

    private fun verifyDownloadedModel(model: LiteRtModelConfig): Boolean {
        val file = getModelFile(model)
        if (!file.exists() || file.length() != model.sizeBytes) {
            AppLog.w(TAG, "Downloaded model ${model.shortName} has invalid size bytes=${file.length()} expected=${model.sizeBytes}")
            return false
        }
        val actualSha256 = sha256(file)
        val matches = actualSha256.equals(model.sha256, ignoreCase = true)
        if (!matches) {
            AppLog.w(TAG, "Downloaded model ${model.shortName} checksum mismatch actual=$actualSha256 expected=${model.sha256}")
        }
        return matches
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        BufferedInputStream(file.inputStream()).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

sealed class DownloadStatus {
    data class Progress(val percentage: Int) : DownloadStatus()
    object Success : DownloadStatus()
    data class Failed(val error: String) : DownloadStatus()
}

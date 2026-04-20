package com.calendaradd.service

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import com.calendaradd.usecase.PreferencesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File

/**
 * Manages downloads for selectable LiteRT-LM models.
 */
class ModelDownloadManager(
    private val context: Context,
    private val preferencesManager: PreferencesManager
) {

    private val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    /**
     * Returns the local file path where the model should be stored.
     * Use externalFilesDir (Downloads) which is app-specific but accessible by DownloadManager.
     */
    fun getModelFile(model: LiteRtModelConfig = getSelectedModel()): File {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
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
        return file.exists() && file.length() >= model.minimumExpectedBytes
    }

    /**
     * Estimates if there is enough disk space for the model.
     */
    fun hasEnoughSpace(model: LiteRtModelConfig = getSelectedModel()): Boolean {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
        val availableBytes = dir.usableSpace
        return availableBytes > model.requiredFreeSpaceBytes
    }

    /**
     * Starts the download of the selected LiteRT-LM model.
     * Returns the download ID.
     */
    fun startDownload(model: LiteRtModelConfig = getSelectedModel()): Long {
        if (!hasEnoughSpace(model)) {
            throw IllegalStateException(
                "Not enough disk space to download ${model.displayName} (requires ~${model.requiredFreeSpaceLabel} free)."
            )
        }

        // Clean up any existing file or partial download first to avoid ERROR_FILE_ALREADY_EXISTS
        val targetFile = getModelFile(model)
        if (targetFile.exists()) {
            targetFile.delete()
        }

        val request = DownloadManager.Request(Uri.parse(model.downloadUrl))
            .setTitle("Downloading ${model.displayName}")
            .setDescription("Required for local ${model.capabilitySummary.lowercase()} extraction")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, model.fileName)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)

        return downloadManager.enqueue(request)
    }

    /**
     * Tracks the progress of a download ID.
     */
    fun trackProgress(downloadId: Long): Flow<DownloadStatus> = flow {
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
                            emit(DownloadStatus.Success)
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
}

sealed class DownloadStatus {
    data class Progress(val percentage: Int) : DownloadStatus()
    object Success : DownloadStatus()
    data class Failed(val error: String) : DownloadStatus()
}

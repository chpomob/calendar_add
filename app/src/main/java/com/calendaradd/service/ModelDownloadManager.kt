package com.calendaradd.service

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File

/**
 * Manages the download of Gemma 4 models.
 */
class ModelDownloadManager(private val context: Context) {

    private val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    companion object {
        const val MODEL_URL = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm"
        const val MODEL_FILENAME = "gemma-4-E2B-it.litertlm"
    }

    /**
     * Returns the local file path where the model should be stored.
     * Use externalFilesDir (Downloads) which is app-specific but accessible by DownloadManager.
     */
    fun getModelFile(): File {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
        if (!dir.exists()) dir.mkdirs()
        return File(dir, MODEL_FILENAME)
    }

    /**
     * Checks if the model is already downloaded.
     */
    fun isModelDownloaded(): Boolean {
        val file = getModelFile()
        return file.exists() && file.length() > 2000000000L // Check if roughly correct size (>2GB)
    }

    /**
     * Estimates if there is enough disk space for the model.
     * Model is ~2.6GB, we check for 3.1GB to be safe.
     */
    fun hasEnoughSpace(): Boolean {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
        val availableBytes = dir.usableSpace
        val requiredBytes = 2.6 * 1024 * 1024 * 1024 // 2.6 GB
        return availableBytes > (requiredBytes.toLong() + (500 * 1024 * 1024)) // +500MB buffer
    }

    /**
     * Starts the download of the Gemma 4 model.
     * Returns the download ID.
     */
    fun startDownload(): Long {
        if (!hasEnoughSpace()) {
            throw IllegalStateException("Not enough disk space to download the model (requires ~3.1GB free).")
        }

        // Clean up any existing file or partial download first to avoid ERROR_FILE_ALREADY_EXISTS
        val targetFile = getModelFile()
        if (targetFile.exists()) {
            targetFile.delete()
        }

        val request = DownloadManager.Request(Uri.parse(MODEL_URL))
            .setTitle("Downloading Gemma 4 AI Model")
            .setDescription("Required for local event extraction")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, MODEL_FILENAME)
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

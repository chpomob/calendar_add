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
        const val MODEL_URL = "https://huggingface.co/google/gemma-4-e2b-it-litertlm/resolve/main/gemma-4-e2b-it.litertlm"
        const val MODEL_FILENAME = "gemma-4-e2b-it.litertlm"
    }

    /**
     * Returns the local file path where the model should be stored.
     * Use internal filesDir for better security and stability.
     */
    fun getModelFile(): File {
        val dir = File(context.filesDir, "models").apply { mkdirs() }
        return File(dir, MODEL_FILENAME)
    }

    /**
     * Estimates if there is enough disk space for the model.
     * Model is ~1.5GB, we check for 2.0GB to be safe.
     */
    fun hasEnoughSpace(): Boolean {
        val file = context.filesDir
        val availableBytes = file.usableSpace
        val requiredBytes = 1.5 * 1024 * 1024 * 1024 // 1.5 GB
        return availableBytes > (requiredBytes + (500 * 1024 * 1024)) // +500MB buffer
    }

    /**
     * Starts the download of the Gemma 4 model.
     * Returns the download ID.
     */
    fun startDownload(): Long {
        if (!hasEnoughSpace()) {
            throw IllegalStateException("Not enough disk space to download the model (requires ~2GB free).")
        }

        val request = DownloadManager.Request(Uri.parse(MODEL_URL))
            .setTitle("Downloading Gemma 4 AI Model")
            .setDescription("Required for local event extraction")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationUri(Uri.fromFile(getModelFile()))
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
                            emit(DownloadStatus.Failed("Download failed. Please check your connection."))
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

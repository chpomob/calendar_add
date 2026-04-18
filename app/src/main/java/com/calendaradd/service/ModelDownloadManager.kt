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
     */
    fun getModelFile(): File {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        return File(dir, MODEL_FILENAME)
    }

    /**
     * Checks if the model is already downloaded.
     */
    fun isModelDownloaded(): Boolean {
        return getModelFile().exists() && getModelFile().length() > 0
    }

    /**
     * Starts the download of the Gemma 4 model.
     * Returns the download ID.
     */
    fun startDownload(): Long {
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
            val cursor = downloadManager.query(query)
            
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
            }
            cursor.close()
            if (isDownloading) delay(1000)
        }
    }.flowOn(Dispatchers.IO)
}

sealed class DownloadStatus {
    data class Progress(val percentage: Int) : DownloadStatus()
    object Success : DownloadStatus()
    data class Failed(val error: String) : DownloadStatus()
}

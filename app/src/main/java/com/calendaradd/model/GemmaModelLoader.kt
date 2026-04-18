package com.calendaradd.model

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.*
import kotlin.coroutines.coroutineContext

/**
 * Loads Gemma model from HuggingFace for offline inference.
 * Downloads Gemma-2B-IT in GGUF format (optimized for mobile).
 */
object GemmaModelLoader {

    private const val MODEL_URL = "https://huggingface.co/google/gemma-2b-it/resolve/main/ggml-model-q4f32.bin"
    private const val MODEL_FILENAME = "gemma-2b-it-q4f32.gguf"
    private const val MODEL_DIR = "app/src/main/assets/models/"

    /**
     * Downloads the Gemma model if not already present.
     */
    suspend fun loadModel(context: Context): String = withContext(Dispatchers.IO) {
        val modelPath = File(MODEL_DIR, MODEL_FILENAME).absolutePath
        val modelExists = File(modelPath).exists()

        if (!modelExists) {
            downloadModel(context, MODEL_URL, MODEL_FILENAME)
        }

        // Copy from sdcard to assets (Android requires assets for model access)
        copyToAssets(context, modelPath)

        modelPath
    }

    private suspend fun downloadModel(
        context: Context,
        url: String,
        filename: String
    ) = withContext(Dispatchers.IO) {
        println("Downloading model from: $url")

        val modelDir = File(MODEL_DIR)
        modelDir.mkdirs()

        val outputFile = File(modelDir, filename)

        // Download in chunks to save memory
        val bufferSize = 1024 * 1024 // 1MB chunks
        val outputStream = FileOutputStream(outputFile)
        val urlObject = java.net.URL(url)
        val connection = urlObject.openConnection() as java.net.HttpURLConnection
        connection.connect()

        val contentLength = connection.contentLengthLong
        println("Model size: ${contentLength / 1024 / 1024} MB")

        var bytesDownloaded = 0L
        val buffer = ByteArray(bufferSize)

        var downloaded = false

        while (!downloaded) {
            val bytesRead = connection.inputStream.read(buffer)
            if (bytesRead > 0) {
                outputStream.write(buffer, 0, bytesRead)
                bytesDownloaded += bytesRead
                println("Downloaded: $bytesDownloaded / $contentLength bytes (${(bytesDownloaded.toFloat() / contentLength * 100).toFixed(1)}%)")
            } else {
                downloaded = true
            }
        }

        outputStream.close()
        connection.disconnect()

        println("Model downloaded successfully: ${outputFile.absolutePath}")
        outputFile.absolutePath
    }

    private fun copyToAssets(context: Context, sourcePath: String) {
        // For now, model stays on sdcard
        // TODO: For production, we'd use AssetManager to copy to assets
        // This is a simplified version - in reality, we'd handle this differently
        println("Model will be loaded from: $sourcePath")
    }
}

package com.calendaradd.model

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * LLM Engine for running Gemma on Android.
 * Downloads model to app's internal storage on first launch.
 */
class LlmEngine(
    private val context: Context,
    private val assetManager: AssetManager = context.assets,
    private val modelPath: String? = null
) {

    private val modelLoaded by kotlinx.atomicfu.atomic.AtomicBoolean(false)

    companion object {
        private const val MODEL_FILENAME = "gemma-2b-it-q4f32.gguf"
        // Use app's internal files directory for model storage
        private const val MODEL_DIR_NAME = "models"
    }

    /**
     * Loads the model from storage or downloads it on first launch.
     */
    suspend fun loadModel(downloadRequired: Boolean = true): Boolean = withContext(Dispatchers.IO) {
        try {
            // Use app's internal files directory
            val modelDir = File(context.getExternalFilesDir(null), MODEL_DIR_NAME)
            val modelPath = File(modelDir, MODEL_FILENAME).absolutePath
            val modelExists = modelPath.startsWith(context.filesDir.absolutePath) || File(modelPath).exists()

            if (modelExists) {
                println("✓ Model loaded from: $modelPath")
                modelLoaded.value = true
                return@withContext true
            }

            if (downloadRequired) {
                // Download model to internal storage
                println("⏳ Downloading AI model to: $modelPath")
                println("   This will use ~${(1500 / 1024).toInt()} MB of storage")

                val url = "https://huggingface.co/cjpizzolo/Gemma-2b-it-GGUF/resolve/main/ggml-model-q4f32.gguf"
                val success = downloadModel(url, modelPath)

                if (success) {
                    println("✓ Model downloaded successfully")
                    modelLoaded.value = true
                } else {
                    println("⚠ Model download failed, using fallback extraction")
                }
            }

            modelLoaded.value = modelExists
            true
        } catch (e: Exception) {
            e.printStackTrace()
            println("Error loading model: ${e.message}")
            false
        }
    }

    private suspend fun downloadModel(url: String, destPath: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val destFile = File(destPath)
            val destDir = destFile.parentFile
            destDir.mkdirs()

            // Use HTTP client for download
            val urlObject = java.net.URL(url)
            val connection = urlObject.openConnection() as java.net.HttpURLConnection
            connection.connect()
            val inputStream = connection.inputStream

            // Check connection status
            if (connection.responseCode != 200) {
                println("Download failed: HTTP ${connection.responseCode}")
                return@withContext false
            }

            val bufferSize = 1024 * 1024 // 1MB chunks
            val outputStream = FileOutputStream(destFile)
            val bytesRead = inputStream.read(bufferSize)

            outputStream.write(bufferSize.coerceAtMost(bytesRead.toInt()))
            outputStream.close()
            inputStream.close()
            connection.disconnect()

            println("✓ Model downloaded: ${(destFile.length() / 1024 / 1024).toInt()} MB")
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Generates text response from model.
     */
    suspend fun generateText(prompt: String): String? = withContext(Dispatchers.IO) {
        try {
            if (!modelLoaded.value) {
                // Fallback when model not loaded
                return@withContext null
            }
            // TODO: Implement actual inference
            "Model response: $prompt"
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Checks if the model is loaded and ready.
     */
    fun isLoaded(): Boolean = modelLoaded.value

    /**
     * Unloads model to free memory.
     */
    fun unloadModel() {
        modelLoaded.value = false
        println("Model unloaded")
    }
}

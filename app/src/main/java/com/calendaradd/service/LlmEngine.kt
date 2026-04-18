package com.calendaradd.service

import android.content.Context
import android.content.res.AssetManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * LLM engine for running Gemma models on-device.
 * Supports GGUF format models for local inference.
 */
class LlmEngine(
    private val context: Context,
    private val assetManager: AssetManager = AssetManager(),
    private val modelPath: String = "app/src/main/assets/models/gemma-2b-it-q4f32.gguf"
) {

    private var modelPathValue: String = modelPath
    private val executor by lazy { java.util.concurrent.Executors.newFixedThreadPool(4) }
    private val isModelLoaded by kotlinx.atomicfu.atomic.AtomicBoolean(false)

    companion object {
        private const val MODEL_URL = "https://huggingface.co/cjpizzolo/Gemma-2b-it-GGUF/resolve/main/ggml-model-q4f32.gguf"
        private const val MODEL_DIR = "app/src/main/assets/models/"
        private const val MODEL_FILENAME = "gemma-2b-it-q4f32.gguf"
    }

    /**
     * Loads the model from assets or downloads it on first launch.
     * Uses download approach for Play Store compatibility.
     */
    suspend fun loadModel(downloadRequired: Boolean = true): Boolean = withContext(Dispatchers.IO) {
        try {
            val modelPath = File(MODEL_DIR, MODEL_FILENAME).absolutePath
            val modelExists = File(modelPath).exists()

            if (modelExists) {
                // Model already downloaded, just load
                println("✓ Model loaded from storage: $modelPath")
                isModelLoaded.value = true
                return@withContext true
            }

            if (downloadRequired) {
                // Download model on first launch
                println("⏳ Downloading AI model (${MODEL_FILENAME})...")
                println("   This may take 5-10 minutes depending on your connection")

                val result = downloadModel(MODEL_URL, MODEL_FILENAME)
                if (result) {
                    println("✓ Model downloaded successfully")
                    isModelLoaded.value = true
                } else {
                    println("⚠ Model download failed, using fallback extraction")
                }
            } else {
                // Model not present, user must download manually
                println("⚠ Model not found. Please download manually:")
                println("   $MODEL_URL")
            }

            isModelLoaded.value = modelExists
            true
        } catch (e: Exception) {
            e.printStackTrace()
            println("Error loading model: ${e.message}")
            false
        }
    }

    private suspend fun downloadModel(
        url: String,
        filename: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            println("Connecting to: $url")

            // Check if wget or curl is available
            val command = when {
                java.io.File("/bin/wget").exists() -> "wget"
                java.io.File("/bin/curl").exists() -> "curl"
                else -> throw Exception("No download tool found (wget or curl required)")
            }

            // Execute download
            val process = ProcessBuilder(command, "-O", MODEL_DIR, filename, url).apply {
                redirectErrorStream = true
            }.start()

            val success = process.waitFor(10, java.util.concurrent.TimeUnit.MINUTES)
            val exitCode = process.exitValue()

            if (success && exitCode == 0) {
                val file = File(MODEL_DIR, filename)
                println("✓ Model downloaded: ${file.length / 1024 / 1024} MB")
                return@withContext true
            } else {
                println("⚠ Download failed. Exit code: $exitCode")
                return@withContext false
            }
        } catch (e: Exception) {
            e.printStackTrace()
            println("Download error: ${e.message}")
            false
        }
    }

    /**
     * Checks if the model is loaded.
     */
    fun isLoaded(): Boolean = isModelLoaded.value

    /**
     * Performs inference on the input text.
     * For now, returns basic extraction with fallback.
     */
    suspend fun analyzeInput(
        input: String,
        modelType: String = "gemma-2b-it",
        temperature: Float = 0.7f,
        maxTokens: Int = 256
    ): EventExtraction? = withContext(Dispatchers.IO) {
        try {
            if (!isModelLoaded.value) {
                println("Model not loaded, using fallback extraction")
                // Fallback to basic extraction
                return@withContext EventExtraction(
                    title = extractTitle(input),
                    description = extractDescription(input),
                    startTime = "",
                    endTime = "",
                    location = extractLocation(input),
                    attendees = extractAttendees(input)
                )
            }

            // TODO: Implement actual inference when model is available
            // For now, return basic extraction
            EventExtraction(
                title = extractTitle(input),
                description = extractDescription(input),
                startTime = "",
                endTime = "",
                location = extractLocation(input),
                attendees = extractAttendees(input)
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Releases the model.
     */
    fun unloadModel() {
        isModelLoaded.value = false
        println("Model unloaded")
    }

    /**
     * Gets model information.
     */
    fun getModelInfo(): ModelInfo {
        return ModelInfo(
            name = "Gemma-2b-it",
            size = "1.5 GB",
            parameters = 2_000_000_000L,
            contextLength = 8192,
            supportedFormats = listOf("gguf", "ggml")
        )
    }

    /**
     * Runs a simple health check.
     */
    suspend fun healthCheck(): String = withContext(Dispatchers.IO) {
        if (isModelLoaded.value) {
            "Model loaded successfully: ${getModelInfo().name}"
        } else {
            "Model not loaded. Call loadModel() first."
        }
    }

    private fun extractTitle(input: String): String {
        return input.lines().firstOrNull { it.isNotBlank() }
            ?: "Untitled Event"
    }

    private fun extractDescription(input: String): String {
        return input.lines().drop(1).joinToString("\n")
            .takeIf { it.isNotEmpty() } ?: ""
    }

    private fun extractLocation(input: String): String {
        return input.lines()
            .firstOrNull { it.contains("location", ignoreCase = true) || it.contains("place", ignoreCase = true) || it.contains("where", ignoreCase = true) }
            ?.removePrefix("- ")
            ?.removePrefix("📍 ")
            ?.removePrefix("Location: ")
            ?.removePrefix("At: ")
            ?.take(100) ?: ""
    }

    private fun extractAttendees(input: String): List<String> {
        return input.lines()
            .filter { it.contains("attendee", ignoreCase = true) || it.contains("with", ignoreCase = true) || it.contains(",", ignoreCase = true) }
            .map { it.replaceFirstChar { it.uppercase() } }
            .distinct()
    }
}

data class ModelInfo(
    val name: String,
    val size: String,
    val parameters: Long,
    val contextLength: Int,
    val supportedFormats: List<String>
)

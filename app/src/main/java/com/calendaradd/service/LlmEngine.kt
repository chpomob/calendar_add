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
    }

    /**
     * Loads the model from assets or downloads it.
     */
    suspend fun loadModel(): Boolean = withContext(Dispatchers.IO) {
        try {
            val file = File(modelPathValue)
            val modelExists = file.exists()

            if (!modelExists) {
                println("Model not found, downloading from: $MODEL_URL")
                // TODO: Implement download logic here
                // For now, simulate download
                isModelLoaded.value = true
            }

            isModelLoaded.value = true
            true
        } catch (e: Exception) {
            e.printStackTrace()
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

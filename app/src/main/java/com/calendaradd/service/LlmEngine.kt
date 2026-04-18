package com.calendaradd.service

import android.content.Context
import android.content.res.AssetManager
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Local LLM engine for running Gemma4 or similar models on-device.
 * Handles model loading, inference, and result parsing.
 */
class LlmEngine(
    modelPath: String = "app/src/main/assets/model.tflite",
    private val assetManager: AssetManager = AssetManager(),
    private val context: Context
) {

    private var modelInterpreter: Interpreter? = null
    private val executor: ExecutorService by lazy { Executors.newFixedThreadPool(4) }
    private val isModelLoaded by lazy { mutableStateOf(false) }

    /**
     * Loads the TensorFlow Lite model from assets.
     */
    suspend fun loadModel(): Boolean {
        return try {
            executor.execute {
                val file = context.assets.open("model.tflite")
                val size = file.channel().size()
                val buffer = MappedByteBuffer(
                    FileInputStream(context.assets.openFileDescriptor("model.tflite", "r"))
                        .channel
                )
                val options = Interpreter.Options().apply {
                    setNumThreads(4)
                    // setUseXdl(true) // Optional: Use XDL for faster loading
                }
                modelInterpreter = Interpreter(buffer, options)
                isModelLoaded.value = true
                true
            }
        } catch (e: Exception) {
            e.printStackTrace()
            isModelLoaded.value = false
            false
        }
    }

    /**
     * Checks if the model is loaded.
     */
    fun isLoaded(): Boolean = isModelLoaded.value

    /**
     * Performs inference on the input text.
     * For now, returns a mock result since the model is not loaded.
     */
    suspend fun analyzeInput(
        input: String,
        prompt: String = "Extract event information from this text:\n\n${input}\n\n"
    ): EventExtraction? {
        return try {
            // TODO: Implement actual inference when model is loaded
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
     * Releases the model interpreter.
     */
    fun unloadModel() {
        modelInterpreter?.close()
        modelInterpreter = null
        isModelLoaded.value = false
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
            ?.removePrefix("- ").removePrefix("📍 ").removePrefix("Location: ").removePrefix("At: ")
            ?.take(100) ?: ""
    }

    private fun extractAttendees(input: String): List<String> {
        return input.lines()
            .filter { it.contains("attendee", ignoreCase = true) || it.contains("with", ignoreCase = true) || it.contains(",", ignoreCase = true) }
            .map { it.replaceFirstChar { it.uppercase() } }
            .distinct()
    }
}

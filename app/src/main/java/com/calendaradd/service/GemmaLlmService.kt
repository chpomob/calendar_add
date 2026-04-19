package com.calendaradd.service

import android.content.Context
import android.graphics.Bitmap
import com.google.ai.edge.litertlm.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Service for interacting with Gemma 4 via LiteRT-LM API.
 * Supports native multimodal (Text, Image, Audio) inference.
 */
interface EventJsonExtractor {
    suspend fun extractEventJson(
        text: String,
        image: Bitmap? = null,
        audio: ByteArray? = null
    ): String?
}

open class GemmaLlmService(private val context: Context) : EventJsonExtractor {

    private var engine: Engine? = null
    private var conversation: Conversation? = null
    
    /**
     * Tracks the last successfully initialized backend.
     */
    var lastBackendUsed: String? = null
        private set

    /**
     * Initializes the LiteRT-LM engine with a Gemma 4 model.
     * Attempts to use NPU acceleration first, falling back to CPU if it fails.
     */
    open suspend fun initialize(modelPath: String) = withContext(Dispatchers.IO) {
        if (engine != null) return@withContext

        val cacheDirPath = File(context.cacheDir, "litertlm").apply { mkdirs() }.absolutePath
        
        // Strategy: Try NPU first, then fallback to CPU
        val backends = listOf(
            "NPU" to Backend.NPU(),
            "CPU" to Backend.CPU()
        )

        var lastError: Exception? = null

        for ((name, backend) in backends) {
            try {
                val config = EngineConfig(
                    modelPath = modelPath,
                    backend = backend,
                    cacheDir = cacheDirPath
                )
                val initializedEngine = Engine(config).apply {
                    initialize()
                }
                engine = initializedEngine
                conversation = initializedEngine.createConversation(ConversationConfig())
                lastBackendUsed = name
                return@withContext
            } catch (e: Exception) {
                lastError = e
                // Continue to next backend
            }
        }

        throw lastError ?: RuntimeException("Failed to initialize engine with any backend")
    }

    /**
     * Processes multimodal content and returns the extracted event JSON string.
     * Creates a fresh conversation for each request to ensure statelessness.
     */
    override suspend fun extractEventJson(
        text: String,
        image: Bitmap?,
        audio: ByteArray?
    ): String? = withContext(Dispatchers.IO) {
        val currentEngine = engine ?: return@withContext null
        
        // Create a new stateless conversation for this specific extraction
        val conv = currentEngine.createConversation(ConversationConfig())
        
        val requestText = buildString {
            appendLine("Extract ONE calendar event from the input.")
            appendLine("Return ONLY valid JSON. Structure: { \"title\": \"\", \"description\": \"\", \"startTime\": \"ISO-8601\", \"endTime\": \"ISO-8601\", \"location\": \"\", \"attendees\": [] }")
            appendLine("Example: User: 'Lunch with Bob tomorrow at 12pm' -> Response: { \"title\": \"Lunch with Bob\", \"startTime\": \"2026-04-20T12:00:00\", \"endTime\": \"2026-04-20T13:00:00\" }")
            appendLine("Input data:")
            append(text)
        }
        val requestContents = buildList {
            add(Content.Text(requestText))
            image?.let { add(Content.ImageBytes(it.toPngBytes())) }
            audio?.let { add(Content.AudioBytes(it)) }
        }

        return@withContext try {
            val response = conv.sendMessage(Contents.of(requestContents))
            val result = response.contents.contents
                .filterIsInstance<Content.Text>()
                .joinToString("\n") { it.text }
                .ifBlank { null }
            
            conv.close() // Close the transient conversation
            result
        } catch (e: Exception) {
            e.printStackTrace()
            conv.close()
            null
        }
    }

    /**
     * Closes the engine and releases resources.
     */
    open fun close() {
        conversation?.close()
        engine?.close()
        engine = null
        conversation = null
    }
}

private fun Bitmap.toPngBytes(): ByteArray {
    return ByteArrayOutputStream().use { output ->
        compress(Bitmap.CompressFormat.PNG, 100, output)
        output.toByteArray()
    }
}

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
    private val mutex = Any()
    
    /**
     * Tracks the last successfully initialized backend.
     */
    var lastBackendUsed: String? = null
        private set

    protected open fun createEngine(config: EngineConfig): Engine = Engine(config)

    protected open fun createConversation(engine: Engine): Conversation =
        engine.createConversation(ConversationConfig())

    /**
     * Initializes the LiteRT-LM engine with a Gemma 4 model.
     * Attempts to use NPU acceleration first, falling back to CPU if it fails.
     */
    open suspend fun initialize(modelPath: String) = withContext(Dispatchers.IO) {
        synchronized(mutex) {
            if (engine != null) return@synchronized

            val cacheDirPath = File(context.cacheDir, "litertlm").apply { mkdirs() }.absolutePath
            
            // Strategy: Try NPU first, then fallback to CPU
            val backends = listOf(
                "NPU" to Backend.NPU(),
                "CPU" to Backend.CPU()
            )

            var lastError: Exception? = null

            for ((name, backend) in backends) {
                var initializedEngine: Engine? = null
                try {
                    val config = EngineConfig(
                        modelPath = modelPath,
                        backend = backend,
                        cacheDir = cacheDirPath
                    )
                    initializedEngine = createEngine(config).apply {
                        initialize()
                    }
                    engine = initializedEngine
                    lastBackendUsed = name
                    return@withContext
                } catch (e: Exception) {
                    initializedEngine?.close()
                    lastError = e
                }
            }

            throw lastError ?: RuntimeException("Failed to initialize engine with any backend")
        }
    }

    /**
     * Processes multimodal content and returns the extracted event JSON string.
     * Uses a fresh conversation per request so the model stays stateless while
     * respecting LiteRT-LM's single active session limit.
     */
    override suspend fun extractEventJson(
        text: String,
        image: Bitmap?,
        audio: ByteArray?
    ): String? = withContext(Dispatchers.IO) {
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

        synchronized(mutex) {
            val currentEngine = engine ?: return@withContext null
            var conversation: Conversation? = null

            try {
                conversation = createConversation(currentEngine)
                val response = conversation.sendMessage(Contents.of(requestContents))
                val result = response.contents.contents
                    .filterIsInstance<Content.Text>()
                    .joinToString("\n") { it.text }
                    .ifBlank { null }

                return@synchronized result
            } catch (e: Exception) {
                e.printStackTrace()
                return@synchronized null
            } finally {
                conversation?.close()
            }
        }
    }

    /**
     * Closes the engine and releases resources.
     */
    open fun close() {
        synchronized(mutex) {
            engine?.close()
            engine = null
        }
    }
}

private fun Bitmap.toPngBytes(): ByteArray {
    return ByteArrayOutputStream().use { output ->
        compress(Bitmap.CompressFormat.PNG, 100, output)
        output.toByteArray()
    }
}

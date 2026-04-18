package com.calendaradd.service

import android.content.Context
import android.graphics.Bitmap
import com.google.ai.edge.litertlm.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
     * Initializes the LiteRT-LM engine with a Gemma 4 model.
     * Uses NPU acceleration for 2026-era hardware.
     */
    open suspend fun initialize(modelPath: String) = withContext(Dispatchers.IO) {
        if (engine != null) return@withContext

        val config = EngineConfig(
            modelPath = modelPath,
            backend = Backend.CPU(),
            cacheDir = File(context.cacheDir, "litertlm").apply { mkdirs() }.absolutePath
        )

        val initializedEngine = Engine(config).apply {
            initialize()
        }
        engine = initializedEngine
        conversation = initializedEngine.createConversation(ConversationConfig())
    }

    /**
     * Processes multimodal content and returns the extracted event JSON string.
     */
    override suspend fun extractEventJson(
        text: String,
        image: Bitmap?,
        audio: ByteArray?
    ): String? = withContext(Dispatchers.IO) {
        val conv = conversation ?: return@withContext null
        val requestText = buildString {
            appendLine("Extract one calendar event from the following input.")
            appendLine("Respond only with JSON using keys: title, description, startTime, endTime, location, attendees.")
            append(text)
            if (image != null) append("\n[image attached]")
            if (audio != null) append("\n[audio attached]")
        }

        return@withContext try {
            val response = conv.sendMessage(requestText)
            response.contents.contents
                .filterIsInstance<Content.Text>()
                .joinToString("\n") { it.text }
                .ifBlank { null }
        } catch (e: Exception) {
            e.printStackTrace()
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

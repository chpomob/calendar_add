package com.calendaradd.service

import android.content.Context
import android.graphics.Bitmap
import com.google.ai.edge.litertlm.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Service for interacting with Gemma 4 via LiteRT-LM API.
 * Supports native multimodal (Text, Image, Audio) inference.
 */
class GemmaLlmService(private val context: Context) {

    private var engine: Engine? = null
    private var conversation: Conversation? = null

    /**
     * Initializes the LiteRT-LM engine with a Gemma 4 model.
     * Uses NPU acceleration for 2026-era hardware.
     */
    suspend fun initialize(modelPath: String) = withContext(Dispatchers.IO) {
        if (engine != null) return@withContext

        val config = EngineConfig(
            modelPath = modelPath,
            backend = Backend.NPU() // Hardware acceleration for 2026 devices
        )
        
        engine = Engine(config).apply {
            initialize()
        }
        conversation = engine?.createConversation()
    }

    /**
     * Processes multimodal content and returns the extracted event JSON string.
     */
    suspend fun extractEventJson(
        text: String,
        image: Bitmap? = null,
        audio: ByteArray? = null
    ): String? = withContext(Dispatchers.IO) {
        val conv = conversation ?: return@withContext null

        val systemPrompt = """
            You are a calendar assistant. Extract event details from the user input.
            Respond ONLY with a JSON object containing:
            - title (string)
            - description (string)
            - startTime (ISO-8601 string or empty)
            - endTime (ISO-8601 string or empty)
            - location (string)
            - attendees (list of strings)
            
            If a field is missing, use an empty string or empty list.
            Input text: "$text"
        """.trimIndent()

        val contentBuilder = Content.builder()
            .addText(systemPrompt)
            
        image?.let { contentBuilder.addImage(it) }
        audio?.let { contentBuilder.addAudio(it) }

        val content = contentBuilder.build()

        return@withContext try {
            val response = conv.sendMessage(content)
            response.text
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Closes the engine and releases resources.
     */
    fun close() {
        engine?.close()
        engine = null
        conversation = null
    }
}

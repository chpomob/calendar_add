package com.calendaradd.service

import android.content.Context
import android.graphics.Bitmap
import com.calendaradd.util.AppLog
import com.google.ai.edge.litertlm.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

private const val GEMMA_MAX_IMAGE_DIMENSION = 1280
private const val GEMMA_JPEG_QUALITY = 88
private const val GEMMA_REQUEST_IMAGE_DIR = "litertlm-request-images"

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
    companion object {
        private const val TAG = "GemmaLlmService"
        private val processEngineGuard = Any()
        private var activeService: GemmaLlmService? = null
    }
    private var engine: Engine? = null
    private val mutex = Any()
    private var activeBackendLabel: String? = null
    private var activeModelPath: String? = null
    
    /**
     * Tracks the last successfully initialized backend.
     */
    var lastBackendUsed: String? = null
        private set

    var lastInitializationFailure: String? = null
        private set

    protected open fun createEngine(config: EngineConfig): Engine = Engine(config)

    protected open fun createConversation(engine: Engine): Conversation =
        engine.createConversation(ConversationConfig())

    /**
     * Initializes the LiteRT-LM engine with a Gemma 4 model.
     * Attempts to use NPU acceleration first, falling back to mixed NPU/CPU and CPU if it fails.
     */
    open suspend fun initialize(modelPath: String, modelConfig: LiteRtModelConfig? = null) = withContext(Dispatchers.IO) {
        synchronized(processEngineGuard) {
            synchronized(mutex) {
                if (engine != null && activeModelPath == modelPath) return@withContext
            }

            val previousActiveService = activeService
            if (previousActiveService != null && previousActiveService !== this) {
                AppLog.w(TAG, "Closing previously active LiteRT-LM service instance before initialization")
                previousActiveService.closeEngineForProcessTransfer()
            }

            synchronized(mutex) {
                if (engine != null && activeModelPath != modelPath) {
                    AppLog.i(TAG, "Switching LiteRT-LM model from $activeModelPath to $modelPath")
                    closeEngineLocked()
                    lastInitializationFailure = null
                }

                val cacheDirPath = File(context.cacheDir, "litertlm").apply { mkdirs() }.absolutePath
                val npuNativeLibraryDir = context.applicationInfo.nativeLibraryDir.orEmpty()
                val backends = backendProfilesFor(modelConfig, npuNativeLibraryDir)

                var lastError: Throwable? = null
                val attemptedBackends = mutableListOf<String>()

                for (profile in backends) {
                    var initializedEngine: Engine? = null
                    try {
                        attemptedBackends += profile.label
                        AppLog.i(
                            TAG,
                            "Initializing LiteRT-LM engine backend=${profile.label} model=${modelConfig?.displayName ?: "unknown"}"
                        )
                        val config = EngineConfig(
                            modelPath = modelPath,
                            backend = profile.textBackend,
                            visionBackend = profile.visionBackend,
                            audioBackend = profile.audioBackend,
                            maxNumTokens = modelConfig?.maxNumTokens,
                            cacheDir = cacheDirPath
                        )
                        initializedEngine = createEngine(config).apply {
                            initialize()
                        }
                        engine = initializedEngine
                        activeBackendLabel = profile.label
                        activeModelPath = modelPath
                        lastBackendUsed = profile.label
                        lastInitializationFailure = null
                        activeService = this@GemmaLlmService
                        AppLog.i(TAG, "LiteRT-LM engine ready backend=${profile.label}")
                        return@withContext
                    } catch (e: Throwable) {
                        if (!e.isRecoverableBackendInitializationFailure()) {
                            throw e
                        }
                        AppLog.w(TAG, "LiteRT-LM engine init failed backend=${profile.label}", e)
                        initializedEngine?.close()
                        lastError = e
                    }
                }

                lastInitializationFailure = buildString {
                    append("attempted=")
                    append(attemptedBackends.joinToString(", "))
                    if (lastError != null) {
                        append(" error=")
                        append(lastError::class.java.simpleName)
                        val message = lastError.message?.trim()
                        if (!message.isNullOrEmpty()) {
                            append(": ")
                            append(message)
                        }
                    }
                }
                throw lastError ?: RuntimeException("Failed to initialize engine with any backend")
            }
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
        val requestId = "llm-${System.currentTimeMillis().toString(16)}"
        val mode = requestMode(image, audio)
        val requestText = buildString {
            appendLine("Extract calendar events from the input.")
            appendLine("If the input contains multiple fragments about the same event, merge them into one event.")
            appendLine("If the input contains multiple distinct events, return them all.")
            appendLine("Use any supplied reference local datetime and timezone to resolve relative date or time phrases such as today, tomorrow, tonight, next Friday, and this weekend.")
            appendLine("Return absolute ISO-8601 timestamps in startTime and endTime. Do not leave relative temporal words unresolved in the JSON.")
            appendLine("Return ONLY valid JSON in this exact shape: { \"events\": [ { \"title\": \"\", \"description\": \"\", \"startTime\": \"ISO-8601\", \"endTime\": \"ISO-8601\", \"location\": \"\", \"attendees\": [] } ] }")
            appendLine("If there is only one event, still return it inside the events array.")
            appendLine("If there are no events, return { \"events\": [] }.")
            appendLine("Input data:")
            append(text)
        }
        AppLog.i(
            TAG,
            "[$requestId] Starting request mode=$mode backend=${activeBackendLabel ?: "uninitialized"} " +
                "promptChars=${requestText.length} image=${image.describeForLogs()} audio=${audio.describeForLogs()}"
        )

        synchronized(mutex) {
            val currentEngine = engine ?: run {
                AppLog.e(TAG, "[$requestId] Rejecting request because engine is not initialized")
                return@withContext null
            }
            var conversation: Conversation? = null
            var preparedImage: PreparedImageFile? = null

            try {
                val requestContents = buildList {
                    add(Content.Text(requestText))
                    image?.let {
                        preparedImage = it.toModelImageFile(context.cacheDir, requestId)
                        AppLog.i(
                            TAG,
                            "[$requestId] Prepared image file size=${preparedImage?.sizeBytes} " +
                                "dimensions=${preparedImage?.width}x${preparedImage?.height}"
                        )
                        add(Content.ImageFile(requireNotNull(preparedImage).file.absolutePath))
                    }
                    audio?.let { add(Content.AudioBytes(it)) }
                }
                conversation = createConversation(currentEngine)
                AppLog.i(TAG, "[$requestId] Conversation created backend=${activeBackendLabel ?: "unknown"} mode=$mode")
                AppLog.i(TAG, "[$requestId] Sending message with ${requestContents.size} content blocks")
                val response = conversation.sendMessage(Contents.of(requestContents))
                val result = response.contents.contents
                    .filterIsInstance<Content.Text>()
                    .joinToString("\n") { it.text }
                    .ifBlank { null }

                AppLog.i(TAG, "[$requestId] Request completed responseChars=${result?.length ?: 0}")
                return@synchronized result
            } catch (e: IllegalStateException) {
                AppLog.e(TAG, "[$requestId] Invalid request state before LiteRT-LM call", e)
                return@synchronized null
            } catch (e: OutOfMemoryError) {
                AppLog.e(TAG, "[$requestId] Image preprocessing ran out of memory", e)
                return@synchronized null
            } catch (e: Exception) {
                AppLog.e(
                    TAG,
                    "[$requestId] LiteRT-LM extraction failed backend=${activeBackendLabel ?: "unknown"} mode=$mode",
                    e
                )
                return@synchronized null
            } finally {
                conversation?.close()
                preparedImage?.deleteQuietly(requestId)
            }
        }
    }

    /**
     * Closes the engine and releases resources.
     */
    open fun close() {
        synchronized(processEngineGuard) {
            synchronized(mutex) {
                closeEngineLocked()
                if (activeService === this) {
                    activeService = null
                }
            }
        }
    }

    private fun closeEngineForProcessTransfer() {
        synchronized(mutex) {
            closeEngineLocked()
            if (activeService === this) {
                activeService = null
            }
        }
    }

    private fun closeEngineLocked() {
        engine?.close()
        engine = null
        activeBackendLabel = null
        activeModelPath = null
    }
}

private fun Throwable.isRecoverableBackendInitializationFailure(): Boolean {
    return this is Exception || this is LinkageError
}

private data class BackendProfile(
    val label: String,
    val textBackend: Backend,
    val visionBackend: Backend?,
    val audioBackend: Backend?
)

private fun backendProfilesFor(
    modelConfig: LiteRtModelConfig?,
    npuNativeLibraryDir: String
): List<BackendProfile> {
    return when (modelConfig?.executionProfile) {
        ModelExecutionProfile.CPU_ONLY_MULTIMODAL -> listOf(
            BackendProfile(
                label = "CPU-only multimodal",
                textBackend = Backend.CPU(),
                visionBackend = Backend.CPU(),
                audioBackend = if (modelConfig.supportsAudio) Backend.CPU() else null
            )
        )
        else -> listOf(
            BackendProfile(
                label = "NPU multimodal",
                textBackend = npuBackend(npuNativeLibraryDir),
                visionBackend = if (modelConfig?.supportsImage != false) npuBackend(npuNativeLibraryDir) else null,
                audioBackend = if (modelConfig?.supportsAudio != false) npuBackend(npuNativeLibraryDir) else null
            ),
            BackendProfile(
                label = "NPU(text)+CPU(vision/audio)",
                textBackend = npuBackend(npuNativeLibraryDir),
                visionBackend = if (modelConfig?.supportsImage != false) Backend.CPU() else null,
                audioBackend = if (modelConfig?.supportsAudio != false) Backend.CPU() else null
            ),
            BackendProfile(
                label = "CPU",
                textBackend = Backend.CPU(),
                visionBackend = if (modelConfig?.supportsImage != false) Backend.CPU() else null,
                audioBackend = if (modelConfig?.supportsAudio != false) Backend.CPU() else null
            )
        )
    }
}

private fun npuBackend(nativeLibraryDir: String): Backend.NPU = Backend.NPU(nativeLibraryDir)

private data class PreparedImageFile(
    val file: File,
    val width: Int,
    val height: Int,
    val sizeBytes: Long
)

private enum class RequestMode {
    TEXT,
    IMAGE,
    AUDIO,
    IMAGE_AND_AUDIO
}

private fun requestMode(image: Bitmap?, audio: ByteArray?): RequestMode {
    return when {
        image != null && audio != null -> RequestMode.IMAGE_AND_AUDIO
        image != null -> RequestMode.IMAGE
        audio != null -> RequestMode.AUDIO
        else -> RequestMode.TEXT
    }
}

private fun Bitmap.toModelImageFile(cacheDir: File, requestId: String): PreparedImageFile {
    require(!isRecycled) { "Bitmap is already recycled" }

    val requestDir = File(cacheDir, GEMMA_REQUEST_IMAGE_DIR).apply { mkdirs() }
    val normalizedBitmap = ensureArgb8888()
    val preparedBitmap = normalizedBitmap.scaleDownIfNeeded(GEMMA_MAX_IMAGE_DIMENSION)
    val imageFile = File.createTempFile("litertlm-$requestId-", ".jpg", requestDir)

    return try {
        FileOutputStream(imageFile).use { output ->
            check(preparedBitmap.compress(Bitmap.CompressFormat.JPEG, GEMMA_JPEG_QUALITY, output)) {
                "Bitmap compression failed"
            }
        }
        PreparedImageFile(
            file = imageFile,
            width = preparedBitmap.width,
            height = preparedBitmap.height,
            sizeBytes = imageFile.length()
        )
    } finally {
        if (preparedBitmap !== normalizedBitmap) {
            preparedBitmap.recycle()
        }
        if (normalizedBitmap !== this && normalizedBitmap !== preparedBitmap) {
            normalizedBitmap.recycle()
        }
    }
}

private fun Bitmap.ensureArgb8888(): Bitmap {
    if (config == Bitmap.Config.ARGB_8888) return this
    return copy(Bitmap.Config.ARGB_8888, false)
}

private fun Bitmap?.describeForLogs(): String {
    if (this == null) return "none"
    return "size=${width}x${height},config=${config ?: "null"},bytes=$allocationByteCount,recycled=$isRecycled"
}

private fun ByteArray?.describeForLogs(): String {
    return if (this == null) "none" else "bytes=$size"
}

private fun PreparedImageFile.deleteQuietly(requestId: String) {
    if (!file.exists()) return
    if (!file.delete()) {
        AppLog.w("GemmaLlmService", "[$requestId] Failed to delete temp image file ${file.absolutePath}")
    }
}

private fun Bitmap.scaleDownIfNeeded(maxDimension: Int): Bitmap {
    val largestSide = max(width, height)
    if (largestSide <= maxDimension) return this

    val scale = maxDimension.toFloat() / largestSide.toFloat()
    val scaledWidth = (width * scale).roundToInt().coerceAtLeast(1)
    val scaledHeight = (height * scale).roundToInt().coerceAtLeast(1)
    return Bitmap.createScaledBitmap(this, scaledWidth, scaledHeight, true)
}

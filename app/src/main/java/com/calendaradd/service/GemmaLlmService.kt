package com.calendaradd.service

import android.content.Context
import android.graphics.Bitmap
import com.calendaradd.util.AppLog
import com.calendaradd.util.hasWavHeader
import com.google.ai.edge.litertlm.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext

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

open class GemmaLlmService(
    private val context: Context,
    private val gpuBackendAvailableOverride: Boolean? = null
) : EventJsonExtractor {
    companion object {
        private const val TAG = "GemmaLlmService"
        private val processEngineGuard = Any()
        private var activeService: GemmaLlmService? = null
    }
    private var engine: Engine? = null
    private val mutex = Any()
    private var activeBackendLabel: String? = null
    private var activeModelSignature: ActiveModelSignature? = null
    private var activeConversationConfig: ConversationConfig = ConversationConfig()
    private var activeBackendProfiles: List<BackendProfile> = emptyList()
    private var activeBackendIndex: Int? = null
    
    /**
     * Tracks the last successfully initialized backend.
     */
    var lastBackendUsed: String? = null
        private set

    var lastInitializationFailure: String? = null
        private set

    protected open fun createEngine(config: EngineConfig): Engine = Engine(config)

    protected open fun createConversation(engine: Engine): Conversation =
        engine.createConversation(activeConversationConfig)

    protected open fun isGpuBackendAvailable(): Boolean =
        gpuBackendAvailableOverride ?: context.hasOpenClLibrary()

    /**
     * Initializes the LiteRT-LM engine with a Gemma 4 model.
     * Initializes with the backend order from the selected model's Gallery-aligned profile.
     */
    open suspend fun initialize(
        modelPath: String,
        modelConfig: LiteRtModelConfig? = null,
        enableImage: Boolean = modelConfig?.supportsImage != false,
        enableAudio: Boolean = modelConfig?.supportsAudio != false
    ) = withContext(Dispatchers.IO) {
        synchronized(processEngineGuard) {
            val requestedModelSignature = ActiveModelSignature(
                modelPath = modelPath,
                modelId = modelConfig?.id,
                enableImage = enableImage,
                enableAudio = enableAudio,
                maxNumTokens = modelConfig?.maxNumTokens
            )
            synchronized(mutex) {
                if (engine != null && activeModelSignature == requestedModelSignature) return@withContext
            }

            val previousActiveService = activeService
            if (previousActiveService != null && previousActiveService !== this) {
                AppLog.w(TAG, "Closing previously active LiteRT-LM service instance before initialization")
                previousActiveService.closeEngineForProcessTransfer()
            }

            synchronized(mutex) {
                if (engine != null && activeModelSignature != requestedModelSignature) {
                    AppLog.i(TAG, "Switching LiteRT-LM engine from $activeModelSignature to $requestedModelSignature")
                    closeEngineLocked()
                    lastInitializationFailure = null
                }

                val deviceMemoryGb = context.deviceMemoryGb()
                modelConfig?.validateDeviceMemoryOrThrow(deviceMemoryGb)?.let { failure ->
                    lastInitializationFailure = failure
                    throw IllegalStateException(failure)
                }

                val cacheDirPath = liteRtCacheDir(context, modelPath)
                val isGpuBackendAvailable = isGpuBackendAvailable()
                if (!isGpuBackendAvailable) {
                    AppLog.w(TAG, "OpenCL library not found; using CPU LiteRT-LM backends")
                }
                val backends = backendProfilesFor(
                    modelConfig = modelConfig,
                    enableImage = enableImage,
                    enableAudio = enableAudio,
                    isGpuBackendAvailable = isGpuBackendAvailable
                )

                var lastError: Throwable? = null
                val attemptedBackends = mutableListOf<String>()

                for ((index, profile) in backends.withIndex()) {
                    var initializedEngine: Engine? = null
                    try {
                        attemptedBackends += profile.label
                        AppLog.i(
                            TAG,
                            "Initializing LiteRT-LM engine backend=${profile.label} " +
                                "model=${modelConfig?.displayName ?: "unknown"} image=$enableImage audio=$enableAudio"
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
                        activeModelSignature = requestedModelSignature
                        activeConversationConfig = conversationConfigFor(modelConfig)
                        activeBackendProfiles = backends
                        activeBackendIndex = index
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
                val failure = lastError ?: RuntimeException("Failed to initialize engine with any backend")
                if (failure is Exception) {
                    throw failure
                }
                throw RuntimeException("Failed to initialize engine with any backend", failure)
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
        val requestText = text
        AppLog.i(
            TAG,
            "[$requestId] Starting request mode=$mode backend=${activeBackendLabel ?: "uninitialized"} " +
                "promptChars=${requestText.length} image=${image.describeForLogs()} audio=${audio.describeForLogs()}"
        )
        val requestJob = currentCoroutineContext()[Job]

        synchronized(mutex) {
            if (engine == null) {
                AppLog.e(TAG, "[$requestId] Rejecting request because engine is not initialized")
                return@synchronized null
            }
            var preparedImage: PreparedImageBytes? = null

            try {
                val requestContents = buildList {
                    image?.let {
                        preparedImage = it.toModelImageBytes()
                        AppLog.i(
                            TAG,
                            "[$requestId] Prepared PNG image bytes=${preparedImage?.sizeBytes} " +
                                "dimensions=${preparedImage?.width}x${preparedImage?.height}"
                        )
                        add(Content.ImageBytes(requireNotNull(preparedImage).bytes))
                    }
                    audio?.let {
                        if (!it.hasWavHeader()) {
                            AppLog.w(TAG, "[$requestId] Audio bytes do not have a WAV header; LiteRT-LM may reject this input")
                        }
                        add(Content.AudioBytes(it))
                    }
                    add(Content.Text(requestText))
                }
                var retryCount = 0
                var finalResult: String? = null
                var isDone = false
                while (!isDone) {
                    val requestEngine = engine
                    if (requestEngine == null) {
                        AppLog.e(TAG, "[$requestId] Rejecting request because engine is not initialized")
                        isDone = true
                        continue
                    }
                    var conversation: Conversation? = null
                    try {
                        conversation = createConversation(requestEngine)
                        AppLog.i(TAG, "[$requestId] Conversation created backend=${activeBackendLabel ?: "unknown"} mode=$mode")
                        AppLog.i(TAG, "[$requestId] Sending async message with ${requestContents.size} content blocks")
                        val result = conversation.awaitResponse(
                            contents = Contents.of(requestContents),
                            requestId = requestId,
                            cancellationJob = requestJob
                        )

                        AppLog.i(TAG, "[$requestId] Request completed responseChars=${result?.length ?: 0}")
                        finalResult = result
                        isDone = true
                    } catch (e: CancellationException) {
                        AppLog.w(TAG, "[$requestId] LiteRT-LM request cancelled backend=${activeBackendLabel ?: "unknown"} mode=$mode")
                        throw e
                    } catch (e: IllegalStateException) {
                        AppLog.e(TAG, "[$requestId] Invalid request state before LiteRT-LM call", e)
                        isDone = true
                    } catch (e: Exception) {
                        val failedBackend = activeBackendLabel ?: "unknown"
                        AppLog.e(
                            TAG,
                            "[$requestId] LiteRT-LM extraction failed backend=$failedBackend mode=$mode",
                            e
                        )
                        val didSwitchBackend = retryCount == 0 && switchToNextBackendAfterRequestFailureLocked(
                            requestId = requestId,
                            failedBackend = failedBackend,
                            cause = e
                        )
                        if (didSwitchBackend) {
                            retryCount += 1
                            AppLog.i(TAG, "[$requestId] Retrying request backend=${activeBackendLabel ?: "unknown"}")
                            continue
                        }
                        isDone = true
                    } finally {
                        conversation?.close()
                    }
                }
                finalResult
            } catch (e: CancellationException) {
                AppLog.w(TAG, "[$requestId] LiteRT-LM request cancelled backend=${activeBackendLabel ?: "unknown"} mode=$mode")
                throw e
            } catch (e: IllegalStateException) {
                AppLog.e(TAG, "[$requestId] Invalid request state before LiteRT-LM call", e)
                return@synchronized null
            } catch (e: OutOfMemoryError) {
                AppLog.e(TAG, "[$requestId] Image preprocessing ran out of memory", e)
                return@synchronized null
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
        activeModelSignature = null
        activeConversationConfig = ConversationConfig()
        activeBackendProfiles = emptyList()
        activeBackendIndex = null
    }

    private fun switchToNextBackendAfterRequestFailureLocked(
        requestId: String,
        failedBackend: String,
        cause: Exception
    ): Boolean {
        val profiles = activeBackendProfiles
        val currentIndex = activeBackendIndex ?: return false
        val signature = activeModelSignature ?: return false
        if (currentIndex >= profiles.lastIndex) return false

        val remainingProfiles = profiles.drop(currentIndex + 1)
        engine?.close()
        engine = null
        activeBackendLabel = null
        activeBackendIndex = null

        var lastError: Throwable = cause
        for ((offset, profile) in remainingProfiles.withIndex()) {
            var initializedEngine: Engine? = null
            try {
                AppLog.w(TAG, "[$requestId] Switching LiteRT-LM backend after $failedBackend request failure to ${profile.label}")
                val config = EngineConfig(
                    modelPath = signature.modelPath,
                    backend = profile.textBackend,
                    visionBackend = profile.visionBackend,
                    audioBackend = profile.audioBackend,
                    maxNumTokens = signature.maxNumTokens,
                    cacheDir = liteRtCacheDir(context, signature.modelPath)
                )
                initializedEngine = createEngine(config).apply {
                    initialize()
                }
                engine = initializedEngine
                activeBackendLabel = profile.label
                activeBackendIndex = currentIndex + offset + 1
                lastBackendUsed = profile.label
                lastInitializationFailure = null
                AppLog.i(TAG, "[$requestId] LiteRT-LM backend fallback ready backend=${profile.label}")
                return true
            } catch (fallbackError: Throwable) {
                initializedEngine?.close()
                lastError = fallbackError
                AppLog.w(TAG, "[$requestId] LiteRT-LM backend fallback failed backend=${profile.label}", fallbackError)
            }
        }

        lastInitializationFailure = "requestFailureBackend=$failedBackend fallbackError=${lastError::class.java.simpleName}: ${lastError.message.orEmpty()}"
        return false
    }
}

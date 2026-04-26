package com.calendaradd.service

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import com.calendaradd.util.AppLog
import com.calendaradd.util.hasWavHeader
import com.google.ai.edge.litertlm.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max
import kotlin.math.roundToInt

private const val GEMMA_MAX_IMAGE_DIMENSION = 1280
private const val GEMMA_PNG_QUALITY = 100
private const val LITERTLM_CALLBACK_POLL_MS = 250L

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
    private var activeModelSignature: ActiveModelSignature? = null
    private var activeConversationConfig: ConversationConfig = ConversationConfig()
    
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
                val backends = backendProfilesFor(
                    modelConfig = modelConfig,
                    enableImage = enableImage,
                    enableAudio = enableAudio,
                    deviceMemoryGb = deviceMemoryGb
                )

                var lastError: Throwable? = null
                val attemptedBackends = mutableListOf<String>()

                for (profile in backends) {
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
            val currentEngine = engine ?: run {
                AppLog.e(TAG, "[$requestId] Rejecting request because engine is not initialized")
                return@withContext null
            }
            var conversation: Conversation? = null
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
                conversation = createConversation(currentEngine)
                AppLog.i(TAG, "[$requestId] Conversation created backend=${activeBackendLabel ?: "unknown"} mode=$mode")
                AppLog.i(TAG, "[$requestId] Sending async message with ${requestContents.size} content blocks")
                val result = conversation.awaitResponse(
                    contents = Contents.of(requestContents),
                    requestId = requestId,
                    cancellationJob = requestJob
                )

                AppLog.i(TAG, "[$requestId] Request completed responseChars=${result?.length ?: 0}")
                return@synchronized result
            } catch (e: CancellationException) {
                AppLog.w(TAG, "[$requestId] LiteRT-LM request cancelled backend=${activeBackendLabel ?: "unknown"} mode=$mode")
                throw e
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
    }
}

private data class ActiveModelSignature(
    val modelPath: String,
    val modelId: String?,
    val enableImage: Boolean,
    val enableAudio: Boolean,
    val maxNumTokens: Int?
)

private fun Throwable.isRecoverableBackendInitializationFailure(): Boolean {
    return this is Exception || this is LinkageError || this is OutOfMemoryError
}

private data class BackendProfile(
    val label: String,
    val textBackend: Backend,
    val visionBackend: Backend?,
    val audioBackend: Backend?
)

private fun backendProfilesFor(
    modelConfig: LiteRtModelConfig?,
    enableImage: Boolean,
    enableAudio: Boolean,
    deviceMemoryGb: Double?
): List<BackendProfile> {
    return when (modelConfig?.executionProfile) {
        ModelExecutionProfile.CPU_ONLY_MULTIMODAL -> listOf(
            BackendProfile(
                label = "CPU-only multimodal",
                textBackend = Backend.CPU(),
                visionBackend = if (enableImage && modelConfig.supportsImage) Backend.CPU() else null,
                audioBackend = if (enableAudio && modelConfig.supportsAudio) Backend.CPU() else null
            )
        )
        else -> acceleratedBackendProfiles(modelConfig, enableImage, enableAudio, deviceMemoryGb)
    }
}

private fun acceleratedBackendProfiles(
    modelConfig: LiteRtModelConfig?,
    enableImage: Boolean,
    enableAudio: Boolean,
    deviceMemoryGb: Double?
): List<BackendProfile> {
    val mainBackends = mainBackendOrderFor(modelConfig, enableImage, enableAudio, deviceMemoryGb)
    val visionBackends = if (enableImage && modelConfig?.supportsImage != false) {
        buildList {
            modelConfig?.visionBackend?.let { add(it) }
            add(ModelBackendKind.CPU)
        }.distinct()
    } else {
        listOf(null)
    }
    val audioBackend = if (enableAudio && modelConfig?.supportsAudio != false) Backend.CPU() else null

    return mainBackends.flatMap { mainBackend ->
        visionBackends.map { visionBackend ->
            BackendProfile(
                label = backendProfileLabel(mainBackend, visionBackend, audioBackend != null),
                textBackend = mainBackend.toLiteRtBackend(),
                visionBackend = visionBackend?.toLiteRtBackend(),
                audioBackend = audioBackend
            )
        }
    }.distinctBy { profile ->
        listOf(
            profile.textBackend::class.java.name,
            profile.visionBackend?.let { it::class.java.name }.orEmpty(),
            profile.audioBackend?.let { it::class.java.name }.orEmpty()
        )
    }
}

private fun mainBackendOrderFor(
    modelConfig: LiteRtModelConfig?,
    enableImage: Boolean,
    enableAudio: Boolean,
    deviceMemoryGb: Double?
): List<ModelBackendKind> {
    val configuredBackends = modelConfig?.mainBackendOrder
        ?.takeIf { it.isNotEmpty() }
        ?: listOf(ModelBackendKind.GPU, ModelBackendKind.CPU)
    val multimodalGpuMainMinimumMemoryGb = modelConfig?.multimodalGpuMainMinimumMemoryGb
    val isMultimodalJob = enableImage || enableAudio
    val hasEnoughMemoryForMultimodalGpuMain = deviceMemoryGb == null ||
        multimodalGpuMainMinimumMemoryGb == null ||
        deviceMemoryGb >= multimodalGpuMainMinimumMemoryGb

    return if (
        isMultimodalJob &&
        !hasEnoughMemoryForMultimodalGpuMain &&
        configuredBackends.firstOrNull() == ModelBackendKind.GPU
    ) {
        listOf(ModelBackendKind.CPU, ModelBackendKind.GPU) + configuredBackends.drop(1)
    } else {
        configuredBackends
    }.distinct()
}

private fun backendProfileLabel(
    mainBackend: ModelBackendKind,
    visionBackend: ModelBackendKind?,
    hasAudio: Boolean
): String {
    val parts = mutableListOf("${mainBackend.label}(text)")
    if (visionBackend != null) {
        parts += "${visionBackend.label}(vision)"
    }
    if (hasAudio) {
        parts += "CPU(audio)"
    }
    return parts.joinToString("+")
}

private fun ModelBackendKind.toLiteRtBackend(): Backend {
    return when (this) {
        ModelBackendKind.CPU -> Backend.CPU()
        ModelBackendKind.GPU -> Backend.GPU()
    }
}

private val ModelBackendKind.label: String
    get() = when (this) {
        ModelBackendKind.CPU -> "CPU"
        ModelBackendKind.GPU -> "GPU"
    }

private fun LiteRtModelConfig.validateDeviceMemoryOrThrow(deviceMemoryGb: Double?): String? {
    val requiredMemoryGb = minimumDeviceMemoryGb ?: return null
    deviceMemoryGb ?: return null
    return if (deviceMemoryGb < requiredMemoryGb) {
        "${shortName} requires at least ${requiredMemoryGb}GB RAM; this device reports ${formatMemoryGb(deviceMemoryGb)}GB."
    } else {
        null
    }
}

private fun Context.deviceMemoryGb(): Double? {
    val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return null
    val memoryInfo = ActivityManager.MemoryInfo()
    activityManager.getMemoryInfo(memoryInfo)
    val totalBytes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        memoryInfo.advertisedMem.takeIf { it > 0L } ?: memoryInfo.totalMem
    } else {
        memoryInfo.totalMem
    }
    if (totalBytes <= 0L) return null
    return totalBytes / BYTES_IN_GB
}

private fun liteRtCacheDir(context: Context, modelPath: String): String? {
    return if (modelPath.startsWith("/data/local/tmp")) {
        context.getExternalFilesDir(null)?.absolutePath
    } else {
        null
    }
}

private fun conversationConfigFor(modelConfig: LiteRtModelConfig?): ConversationConfig {
    return ConversationConfig(
        samplerConfig = SamplerConfig(
            topK = modelConfig?.topK ?: 64,
            topP = modelConfig?.topP ?: 0.95,
            temperature = modelConfig?.temperature ?: 1.0
        )
    )
}

private fun formatMemoryGb(value: Double): String = String.format("%.1f", value)

private const val BYTES_IN_GB = 1024.0 * 1024.0 * 1024.0

private data class PreparedImageBytes(
    val bytes: ByteArray,
    val width: Int,
    val height: Int,
    val sizeBytes: Int
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

private fun Bitmap.toModelImageBytes(): PreparedImageBytes {
    require(!isRecycled) { "Bitmap is already recycled" }

    val normalizedBitmap = ensureArgb8888()
    val preparedBitmap = normalizedBitmap.scaleDownIfNeeded(GEMMA_MAX_IMAGE_DIMENSION)
    val output = ByteArrayOutputStream()

    return try {
        check(preparedBitmap.compress(Bitmap.CompressFormat.PNG, GEMMA_PNG_QUALITY, output)) {
            "Bitmap compression failed"
        }
        val imageBytes = output.toByteArray()
        PreparedImageBytes(
            bytes = imageBytes,
            width = preparedBitmap.width,
            height = preparedBitmap.height,
            sizeBytes = imageBytes.size
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

private fun Bitmap.scaleDownIfNeeded(maxDimension: Int): Bitmap {
    val largestSide = max(width, height)
    if (largestSide <= maxDimension) return this

    val scale = maxDimension.toFloat() / largestSide.toFloat()
    val scaledWidth = (width * scale).roundToInt().coerceAtLeast(1)
    val scaledHeight = (height * scale).roundToInt().coerceAtLeast(1)
    return Bitmap.createScaledBitmap(this, scaledWidth, scaledHeight, true)
}

private fun Conversation.awaitResponse(
    contents: Contents,
    requestId: String,
    cancellationJob: Job?
): String? {
    val completed = CountDownLatch(1)
    val failure = AtomicReference<Throwable?>()
    val response = StringBuilder()

    try {
        sendMessageAsync(
            contents,
            object : MessageCallback {
                override fun onMessage(message: Message) {
                    val chunk = message.textChunk()
                    if (chunk.isNotBlank()) {
                        synchronized(response) {
                            response.append(chunk)
                        }
                    }
                }

                override fun onDone() {
                    completed.countDown()
                }

                override fun onError(throwable: Throwable) {
                    failure.set(throwable)
                    completed.countDown()
                }
            },
            emptyMap()
        )

        while (!completed.await(LITERTLM_CALLBACK_POLL_MS, TimeUnit.MILLISECONDS)) {
            if (cancellationJob?.isActive == false) {
                cancelProcess()
                throw CancellationException("LiteRT-LM request $requestId was cancelled")
            }
        }
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        cancelProcess()
        throw CancellationException("LiteRT-LM request $requestId was interrupted")
    }

    failure.get()?.let { error ->
        if (error is Exception) throw error
        throw RuntimeException("LiteRT-LM async callback failed", error)
    }

    return synchronized(response) {
        response.toString().ifBlank { null }
    }
}

private fun Message.textChunk(): String {
    val contentText = contents.contents
        .filterIsInstance<Content.Text>()
        .joinToString("\n") { it.text }
    return contentText.ifBlank { toString() }
}

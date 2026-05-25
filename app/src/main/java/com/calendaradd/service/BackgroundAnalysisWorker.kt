package com.calendaradd.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.calendaradd.MainActivity
import com.calendaradd.navigation.Screen
import com.calendaradd.R
import com.calendaradd.usecase.CalendarUseCase
import com.calendaradd.usecase.EventDatabase
import com.calendaradd.usecase.EventResult
import com.calendaradd.usecase.InputContext
import com.calendaradd.usecase.PreferencesManager
import com.calendaradd.usecase.SourceAttachment
import com.calendaradd.util.AppLog
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlin.math.max
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

private const val ANALYSIS_CHANNEL_ID = "analysis_jobs"
private const val ANALYSIS_CHANNEL_NAME = "Background analysis"
private const val ANALYSIS_RESULT_CHANNEL_ID = "analysis_results"
private const val ANALYSIS_RESULT_CHANNEL_NAME = "Analysis results"
private const val FOREGROUND_NOTIFICATION_ID = 1301
private const val RESULT_NOTIFICATION_ID_BASE = 1302
private const val MAX_BACKGROUND_RUN_ATTEMPTS = 2
private const val QUEUED_IMAGE_MAX_DIMENSION = 1280
private const val LLM_IMAGE_MAX_DIMENSION = 512
private const val MAX_TEXT_INPUT_LENGTH = 32_000
private const val MAX_AUDIO_DURATION_MS = 5 * 60 * 1000L
private const val BACKGROUND_ANALYSIS_TIMEOUT_MS = 10 * 60 * 1000L

// WAV header layout — see http://soundfile.sapp.org/doc/WaveFormat/
private const val WAV_HEADER_MIN_SIZE = 44
private const val WAV_BYTE_RATE_OFFSET = 28
private const val WAV_DATA_SIZE_OFFSET = 40

class BackgroundAnalysisWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "BackgroundAnalysisWorker"
        private const val KEY_CREATED_COUNT = "created_count"
        private const val KEY_FIRST_TITLE = "first_title"
        private const val KEY_ERROR = "error"
    }

    override suspend fun doWork(): Result {
        val inputTypeName = inputData.getString(BackgroundAnalysisScheduler.KEY_INPUT_TYPE)
        val inputPath = inputData.getString(BackgroundAnalysisScheduler.KEY_INPUT_PATH)
        val modelId = inputData.getString(BackgroundAnalysisScheduler.KEY_MODEL_ID)

        if (inputTypeName.isNullOrBlank() || inputPath.isNullOrBlank() || modelId.isNullOrBlank()) {
            return Result.failure(workDataOf(KEY_ERROR to "Missing background analysis parameters."))
        }

        val inputType = runCatching { AnalysisInputType.valueOf(inputTypeName) }.getOrElse {
            return Result.failure(workDataOf(KEY_ERROR to "Unsupported background analysis type."))
        }
        val inputFile = File(inputPath)
        val modelConfig = LiteRtModelCatalog.find(modelId)
        val attemptNumber = runAttemptCount + 1

        // Register memory pressure callback to release LLM model before Android LMKD kills us.
        // AOSP ProcessList.handleOomEvent() (frameworks/base/.../ProcessList.java:906) tracks
        // OOM kills — giving up the model early avoids the kill.
        var oomCallback: ComponentCallbacks2? = null

        val preferencesManager = PreferencesManager(applicationContext)
        val modelDownloadManager = ModelDownloadManager(applicationContext, preferencesManager)
        val backgroundAnalysisScheduler = BackgroundAnalysisScheduler(applicationContext)
        val gemmaLlmService = GemmaLlmService(applicationContext)
        val ocrService = OcrService()
        val webVerificationService = WebVerificationService(webSearchClient = PreferencesWebSearchClient(preferencesManager))
        val textAnalysisService = TextAnalysisService(
            gemmaLlmService = gemmaLlmService,
            preferencesManager = preferencesManager,
            ocrService = ocrService,
            webVerificationService = webVerificationService
        )
        val calendarUseCase = CalendarUseCase(
            textAnalysisService = textAnalysisService,
            eventDatabase = EventDatabase.getDatabase(applicationContext),
            systemCalendarService = SystemCalendarService(applicationContext),
            preferencesManager = preferencesManager
        )
        var sourceAttachment: SourceAttachment? = null
        var keepSourceAttachment = false

        try {
            AppLog.i(
                TAG,
                "Starting background analysis workId=$id attempt=$attemptNumber inputType=$inputType model=${modelConfig.shortName}"
            )
            if (hasExceededBackgroundAttemptLimit(runAttemptCount)) {
                ensureNotificationChannels()
                return failureResult(
                    "Background analysis restarted too many times before completion. " +
                        "Try a smaller model, a smaller image, or plain text input."
                )
            }

            if (!inputFile.exists()) {
                AppLog.w(TAG, "Dropping stale background analysis workId=$id missing input=$inputPath")
                return Result.failure(workDataOf(KEY_ERROR to "Queued input file is missing."))
            }

            ensureNotificationChannels()
            if (!modelDownloadManager.isModelDownloaded(modelConfig)) {
                notifyResult(
                    "Model missing",
                    "${modelConfig.shortName} is not downloaded anymore.",
                    Screen.Home.route
                )
                return Result.failure(workDataOf(KEY_ERROR to "Selected model is no longer downloaded."))
            }

            setForeground(createForegroundInfo(buildProgressMessage("Initializing ${modelConfig.shortName}...", runAttemptCount)))

            // Register OOM callback — release LLM model if system signals low memory.
            // Matches Android's onTrimMemory(TRIM_MEMORY_RUNNING_LOW → TRIM_MEMORY_RUNNING_CRITICAL)
            // pipeline that precedes LMKD process kill.
            oomCallback = object : ComponentCallbacks2 {
                override fun onTrimMemory(level: Int) {
                    if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
                        AppLog.w(TAG, "Memory pressure level=$level, releasing LLM model")
                        gemmaLlmService.close()
                    }
                }
                override fun onConfigurationChanged(newConfig: Configuration) {}
                override fun onLowMemory() {
                    AppLog.w(TAG, "onLowMemory, releasing LLM model")
                    gemmaLlmService.close()
                }
            }
            applicationContext.registerComponentCallbacks(oomCallback!!)

            gemmaLlmService.initialize(
                modelPath = modelDownloadManager.getModelFile(modelConfig).absolutePath,
                modelConfig = modelConfig,
                enableImage = inputType == AnalysisInputType.IMAGE,
                enableAudio = inputType == AnalysisInputType.AUDIO
            )
            val keepModels = backgroundAnalysisScheduler.getPendingModels() + modelConfig
            modelDownloadManager.cleanupUnusedModelFiles(keepModels)

            val traceId = "bg-${System.currentTimeMillis().toString(16)}"
            val inputContext = InputContext(traceId = traceId)
            sourceAttachment = backgroundAnalysisScheduler.promoteInputToEventSource(inputFile, inputType)
            val result = try {
                withTimeout(BACKGROUND_ANALYSIS_TIMEOUT_MS) {
                    when (inputType) {
                        AnalysisInputType.TEXT -> {
                            setForeground(createForegroundInfo(buildProgressMessage("Analyzing text with ${modelConfig.shortName}...", runAttemptCount)))
                            // Cap text input length: the model has a finite context window and
                            // user-pasted/share-imported text can blow past it. Truncating at
                            // the worker boundary keeps prompts well-formed and predictable.
                            val rawText = inputFile.readText(StandardCharsets.UTF_8)
                            val text = if (rawText.length > MAX_TEXT_INPUT_LENGTH) {
                                AppLog.w(
                                    TAG,
                                    "Truncating text input from ${rawText.length} to $MAX_TEXT_INPUT_LENGTH chars traceId=$traceId"
                                )
                                rawText.substring(0, MAX_TEXT_INPUT_LENGTH)
                            } else {
                                rawText
                            }
                            calendarUseCase.createEventFromText(text, inputContext)
                        }
                        AnalysisInputType.IMAGE -> {
                            setForeground(createForegroundInfo(buildProgressMessage("Analyzing image with ${modelConfig.shortName}...", runAttemptCount)))
                            val bitmap = decodeQueuedImage(inputFile)
                                ?: return@withTimeout EventResult.Failure("Unable to decode the queued image.")
                            try {
                                calendarUseCase.createEventFromImage(bitmap, inputContext, sourceAttachment)
                            } finally {
                                if (!bitmap.isRecycled) {
                                    bitmap.recycle()
                                }
                            }
                        }
                        AnalysisInputType.AUDIO -> {
                            setForeground(createForegroundInfo(buildProgressMessage("Analyzing audio with ${modelConfig.shortName}...", runAttemptCount)))
                            // Reject audio clips longer than MAX_AUDIO_DURATION_MS. We probe with
                            // MediaMetadataRetriever first (handles MP3/MP4/AAC/etc.); for WAV
                            // produced in-app the retriever sometimes returns null on certain
                            // OEM builds, so we fall back to a WAV-header byte-rate computation.
                            // If neither yields a duration the audio is passed through —
                            // model-side processing will surface the failure.
                            val durationMs = probeAudioDurationMs(inputFile)
                            if (durationMs != null && durationMs > MAX_AUDIO_DURATION_MS) {
                                AppLog.w(
                                    TAG,
                                    "Rejecting audio input duration=${durationMs}ms > ${MAX_AUDIO_DURATION_MS}ms traceId=$traceId"
                                )
                                return@withTimeout EventResult.Failure(buildAudioTooLongMessage())
                            }
                            calendarUseCase.createEventFromAudio(inputFile.readBytes(), inputContext, sourceAttachment)
                        }
                    }
                }
            } catch (e: TimeoutCancellationException) {
                AppLog.e(TAG, "Background analysis timed out for ${modelConfig.displayName} traceId=$traceId", e)
                return failureResult(buildAnalysisTimeoutMessage())
            }

            return when (result) {
                is EventResult.Success -> {
                    keepSourceAttachment = true
                    val message = if (result.events.size == 1) {
                        "Created event: ${result.event.title}"
                    } else {
                        "Created ${result.events.size} events. First: ${result.event.title}"
                    }
                    val destinationRoute = if (result.events.size == 1) {
                        "${Screen.EventDetail.route}/${result.event.id}"
                    } else {
                        Screen.EventList.route
                    }
                    notifyResult("Analysis complete", message, destinationRoute)
                    Result.success(
                        workDataOf(
                            KEY_CREATED_COUNT to result.events.size,
                            KEY_FIRST_TITLE to result.event.title
                        )
                    )
                }
                is EventResult.Failure -> {
                    notifyResult(
                        title = "Analysis failed",
                        message = result.message,
                        destinationRoute = Screen.Home.route,
                        debug = result.debug
                    )
                    Result.failure(workDataOf(KEY_ERROR to result.message))
                }
            }
        } catch (e: Exception) {
            AppLog.e(TAG, "Background analysis crashed for ${modelConfig.displayName} attempt=$attemptNumber", e)
            notifyResult(
                "Analysis failed",
                buildUnexpectedFailureMessage(e.message, runAttemptCount),
                Screen.Home.route
            )
            return Result.failure(workDataOf(KEY_ERROR to (e.message ?: "Unexpected background analysis error.")))
        } finally {
            oomCallback?.let { applicationContext.unregisterComponentCallbacks(it) }
            gemmaLlmService.close()
            // ML Kit text recognizer holds native resources; release them so retries
            // and the next worker invocation start from a clean slate.
            runCatching { ocrService.close() }
                .onFailure { error -> AppLog.w(TAG, "Failed to close OcrService", error) }
            // Force GC to release native LiteRT engine memory before next retry
            Runtime.getRuntime().gc()
            if (!keepSourceAttachment) {
                sourceAttachment?.path?.let { File(it).deleteQuietly() }
            }
            inputFile.deleteQuietly()
        }
    }

    private fun failureResult(message: String): Result {
        notifyResult("Analysis failed", message, Screen.Home.route)
        return Result.failure(workDataOf(KEY_ERROR to message))
    }

    private fun createForegroundInfo(message: String): ForegroundInfo {
        val notification = NotificationCompat.Builder(applicationContext, ANALYSIS_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Calendar Add analysis")
            .setContentText(message)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(
                0,
                "Cancel",
                WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
            )
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                FOREGROUND_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
            )
        } else {
            ForegroundInfo(FOREGROUND_NOTIFICATION_ID, notification)
        }
    }

    private fun notifyResult(
        title: String,
        message: String,
        destinationRoute: String,
        debug: AnalysisFailureDebug? = null
    ) {
        val resultNotificationId = resultNotificationIdFor(id)
        val displayMessage = if (debug != null) {
            "$message\n\nDebug JSON available. Tap to inspect."
        } else {
            message
        }
        val notification = NotificationCompat.Builder(applicationContext, ANALYSIS_RESULT_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(displayMessage)
            .setStyle(NotificationCompat.BigTextStyle().bigText(displayMessage))
            .setAutoCancel(true)
            .setContentIntent(createLaunchIntent(destinationRoute, resultNotificationId, debug))
            .build()

        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(FOREGROUND_NOTIFICATION_ID)
        manager.notify(resultNotificationId, notification)
    }

    private fun createLaunchIntent(
        destinationRoute: String,
        requestCode: Int,
        debug: AnalysisFailureDebug?
    ): PendingIntent? {
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_OPEN_ROUTE, destinationRoute)
            debug?.let {
                putExtra(MainActivity.EXTRA_DEBUG_FAILURE_TITLE, it.title)
                putExtra(MainActivity.EXTRA_DEBUG_FAILURE_BODY, it.body)
            }
        }
        return PendingIntent.getActivity(
            applicationContext,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun ensureNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val progressChannel = NotificationChannel(
            ANALYSIS_CHANNEL_ID,
            ANALYSIS_CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW
        )
        val resultChannel = NotificationChannel(
            ANALYSIS_RESULT_CHANNEL_ID,
            ANALYSIS_RESULT_CHANNEL_NAME,
            NotificationManager.IMPORTANCE_DEFAULT
        )
        manager.createNotificationChannel(progressChannel)
        manager.createNotificationChannel(resultChannel)
    }
}

internal fun resultNotificationIdFor(workId: UUID): Int {
    val boundedOffset = (workId.hashCode().toLong() and 0x7fffffffL) % 1_000_000L
    return RESULT_NOTIFICATION_ID_BASE + boundedOffset.toInt()
}

private fun File.deleteQuietly() {
    if (exists()) {
        delete()
    }
}

internal fun hasExceededBackgroundAttemptLimit(runAttemptCount: Int): Boolean {
    return runAttemptCount >= MAX_BACKGROUND_RUN_ATTEMPTS
}

internal fun buildProgressMessage(message: String, runAttemptCount: Int): String {
    return if (runAttemptCount <= 0) {
        message
    } else {
        "Retry ${runAttemptCount + 1}: $message"
    }
}

private fun buildUnexpectedFailureMessage(message: String?, runAttemptCount: Int): String {
    val baseMessage = message ?: "Unexpected background analysis error."
    return if (runAttemptCount <= 0) {
        baseMessage
    } else {
        "The background analysis restarted and then failed. $baseMessage"
    }
}

internal fun buildAnalysisTimeoutMessage(): String {
    return "Analysis timed out. Try disabling heavy mode, using a smaller image or audio clip, or selecting a smaller model."
}

internal fun buildAudioTooLongMessage(): String {
    val maxSeconds = MAX_AUDIO_DURATION_MS / 1000L
    return "Audio is longer than ${maxSeconds}s. Trim the clip and try again."
}

/**
 * Probes audio duration for the supplied file. Returns null when the duration cannot
 * be determined reliably (caller should then accept the clip).
 *
 * Strategy:
 *  1. MediaMetadataRetriever (handles MP3, MP4/AAC, OGG, WAV on modern Android)
 *  2. WAV-header fallback (covers VoiceRecordingSession output even when the OEM
 *     retriever returns null)
 */
private fun probeAudioDurationMs(file: File): Long? {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(file.absolutePath)
        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
    } catch (_: Exception) {
        null
    } finally {
        runCatching { retriever.release() }
    }
}

private fun readWavDurationMs(file: File): Long? {
    return runCatching {
        if (file.length() < WAV_HEADER_MIN_SIZE) return@runCatching null
        val header = ByteArray(WAV_HEADER_MIN_SIZE)
        file.inputStream().use { input ->
            var read = 0
            while (read < WAV_HEADER_MIN_SIZE) {
                val n = input.read(header, read, WAV_HEADER_MIN_SIZE - read)
                if (n <= 0) return@runCatching null
                read += n
            }
        }
        if (!header.startsWithAscii("RIFF", 0) || !header.startsWithAscii("WAVE", 8)) {
            return@runCatching null
        }
        val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val byteRate = buffer.getInt(WAV_BYTE_RATE_OFFSET).toLong()
        val declaredDataSize = buffer.getInt(WAV_DATA_SIZE_OFFSET).toLong()
        if (byteRate <= 0L) return@runCatching null
        // Trust the header's data size when sensible, otherwise derive from file length.
        val dataSize = if (declaredDataSize > 0L) {
            declaredDataSize
        } else {
            file.length() - WAV_HEADER_MIN_SIZE
        }
        if (dataSize <= 0L) return@runCatching null
        dataSize * 1000L / byteRate
    }.getOrNull()
}

private fun MediaMetadataRetriever.use(block: (MediaMetadataRetriever) -> String?): String? {
    return try {
        block(this)
    } finally {
        runCatching { release() }
    }
}

private fun ByteArray.startsWithAscii(value: String, offset: Int): Boolean {
    if (offset + value.length > size) return false
    return value.indices.all { index -> this[offset + index] == value[index].code.toByte() }
}

private fun decodeQueuedImage(file: File): Bitmap? {
    val bounds = BitmapFactory.Options().apply {
        inJustDecodeBounds = true
    }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
        return null
    }

    val options = BitmapFactory.Options().apply {
        inSampleSize = calculateQueuedImageSampleSize(
            width = bounds.outWidth,
            height = bounds.outHeight,
            maxDimension = QUEUED_IMAGE_MAX_DIMENSION
        )
        inPreferredConfig = Bitmap.Config.RGB_565
    }
    return BitmapFactory.decodeFile(file.absolutePath, options)
}

private fun calculateQueuedImageSampleSize(width: Int, height: Int, maxDimension: Int): Int {
    var sampleSize = 1
    var currentWidth = width
    var currentHeight = height

    while (currentWidth > maxDimension || currentHeight > maxDimension) {
        currentWidth /= 2
        currentHeight /= 2
        sampleSize *= 2
    }

    return max(1, sampleSize)
}
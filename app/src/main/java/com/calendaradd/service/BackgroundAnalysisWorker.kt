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
import com.calendaradd.util.FileImportHandler
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import kotlin.math.max
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

private const val ANALYSIS_CHANNEL_ID = "analysis_jobs"
private const val ANALYSIS_CHANNEL_NAME = "Background analysis"
private const val ANALYSIS_RESULT_CHANNEL_ID = "analysis_results"
private const val ANALYSIS_RESULT_CHANNEL_NAME = "Analysis results"
private const val FOREGROUND_NOTIFICATION_ID = 1301
private const val RESULT_NOTIFICATION_ID_BASE = 1302
private const val RESULT_NOTIFICATION_PREFS = "analysis_notification_ids"
private const val KEY_NEXT_RESULT_NOTIFICATION_ID = "next_result_notification_id"
private const val MAX_BACKGROUND_RUN_ATTEMPTS = 2
private const val QUEUED_IMAGE_MAX_DIMENSION = 1280
private const val LLM_IMAGE_MAX_DIMENSION = 512
private const val MAX_TEXT_INPUT_LENGTH = 32_000
private const val MAX_AUDIO_DURATION_MS = 5 * 60 * 1000L
private const val BACKGROUND_ANALYSIS_TIMEOUT_MS = 10 * 60 * 1000L

// WAV header layout — see http://soundfile.sapp.org/doc/WaveFormat/
private const val WAV_HEADER_MIN_SIZE = 12
private const val WAV_CHUNK_HEADER_SIZE = 8
private const val WAV_BYTE_RATE_OFFSET_IN_FMT = 8
private const val WAV_FMT_CHUNK_READ_LIMIT = 64

class BackgroundAnalysisWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "BackgroundAnalysisWorker"
        private const val KEY_CREATED_COUNT = "created_count"
        private const val KEY_FIRST_TITLE = "first_title"
        private const val KEY_ERROR = "error"
        private const val KEY_ANALYSIS_FAILED = "analysis_failed"
    }

    override suspend fun doWork(): Result {
        val inputTypeName = inputData.getString(BackgroundAnalysisScheduler.KEY_INPUT_TYPE)
        val inputPath = inputData.getString(BackgroundAnalysisScheduler.KEY_INPUT_PATH)
        val modelId = inputData.getString(BackgroundAnalysisScheduler.KEY_MODEL_ID)
        // Constructed up front (cheap: field assignment only, no I/O) so every terminal-failure
        // path below — including the parameter-validation checks — can rescue chain successors.
        val backgroundAnalysisScheduler = BackgroundAnalysisScheduler(applicationContext)

        // Terminal: malformed job metadata can never resolve by retrying, and the queued
        // input (if any) is left for the scheduler's orphan sweep rather than deleted here.
        if (inputTypeName.isNullOrBlank() || inputPath.isNullOrBlank() || modelId.isNullOrBlank()) {
            return terminalFailureResult(
                backgroundAnalysisScheduler,
                "Missing background analysis parameters.",
                notify = false
            )
        }

        val inputType = runCatching { AnalysisInputType.valueOf(inputTypeName) }.getOrElse {
            return terminalFailureResult(
                backgroundAnalysisScheduler,
                "Unsupported background analysis type.",
                notify = false
            )
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
        val gemmaLlmService = GemmaLlmService(applicationContext)
        val ocrService = OcrService()
        val textAnalysisService = TextAnalysisService(
            gemmaLlmService = gemmaLlmService,
            preferencesManager = preferencesManager,
            ocrService = ocrService
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
                // Exhaustion: every allowed attempt already ran (or the process was killed and
                // WorkManager auto-restarted us past the limit). No further retry is coming, so
                // the queued input is deleted here rather than lingering for the orphan sweep.
                ensureNotificationChannels()
                return terminalFailureResult(
                    backgroundAnalysisScheduler,
                    "Background analysis restarted too many times before completion. " +
                        "Try a smaller model, a smaller image, or plain text input.",
                    inputFile = inputFile
                )
            }

            if (!inputFile.exists()) {
                AppLog.w(TAG, "Dropping stale background analysis workId=$id missing input=$inputPath")
                return terminalFailureResult(backgroundAnalysisScheduler, "Queued input file is missing.", notify = false)
            }

            ensureNotificationChannels()
            if (!modelDownloadManager.isModelDownloaded(modelConfig)) {
                notifyResult(
                    "Model missing",
                    "${modelConfig.shortName} is not downloaded anymore.",
                    Screen.Home.route
                )
                // Transient: a concurrent download or user re-download could resolve this before
                // the retry limit is hit.
                return handleTransientFailure(
                    backgroundAnalysisScheduler,
                    "Selected model is no longer downloaded.",
                    attemptNumber,
                    inputFile,
                    notify = false
                )
            }

            if (!modelConfig.supportsInputType(inputType)) {
                return terminalFailureResult(
                    backgroundAnalysisScheduler,
                    "${modelConfig.shortName} does not support $inputType input.",
                    inputFile = inputFile
                )
            }

            if (inputType == AnalysisInputType.AUDIO) {
                // R4: probe size/duration before paying for model initialization — an
                // oversize/too-long clip is terminal regardless of how well the model loads.
                if (exceedsMaxAudioBytes(inputFile.length())) {
                    AppLog.w(
                        TAG,
                        "Rejecting audio input bytes=${inputFile.length()} > ${FileImportHandler.MAX_AUDIO_BYTES}"
                    )
                    return terminalFailureResult(
                        backgroundAnalysisScheduler,
                        "That audio file is too large to analyze safely.",
                        inputFile = inputFile
                    )
                }
                val probedDurationMs = probeAudioDurationMs(inputFile)
                if (exceedsMaxAudioDuration(probedDurationMs)) {
                    AppLog.w(TAG, "Rejecting audio input duration=${probedDurationMs}ms > ${MAX_AUDIO_DURATION_MS}ms")
                    return terminalFailureResult(
                        backgroundAnalysisScheduler,
                        buildAudioTooLongMessage(),
                        inputFile = inputFile
                    )
                }
            }

            setForegroundSafe(createForegroundInfo(buildProgressMessage("Initializing ${modelConfig.shortName}...", runAttemptCount)))

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
                            setForegroundSafe(createForegroundInfo(buildProgressMessage("Analyzing text with ${modelConfig.shortName}...", runAttemptCount)))
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
                            setForegroundSafe(createForegroundInfo(buildProgressMessage("Analyzing image with ${modelConfig.shortName}...", runAttemptCount)))
                            // Parse-invalid input: the file exists but isn't a decodable image.
                            // Terminal — decoding will not succeed on retry either.
                            val bitmap = decodeQueuedImage(inputFile)
                                ?: throw TerminalAnalysisException("Unable to decode the queued image.")
                            try {
                                calendarUseCase.createEventFromImage(bitmap, inputContext, sourceAttachment)
                            } finally {
                                if (!bitmap.isRecycled) {
                                    bitmap.recycle()
                                }
                            }
                        }
                        AnalysisInputType.AUDIO -> {
                            setForegroundSafe(createForegroundInfo(buildProgressMessage("Analyzing audio with ${modelConfig.shortName}...", runAttemptCount)))
                            // R4: size/duration were already probed and bounded before model
                            // initialization; the audio bytes are read here exactly once, within
                            // the already-verified MAX_AUDIO_BYTES ceiling — no additional
                            // whole-file copy of the queued input.
                            calendarUseCase.createEventFromAudio(inputFile.readBytes(), inputContext, sourceAttachment)
                        }
                    }
                }
            } catch (e: TimeoutCancellationException) {
                AppLog.e(TAG, "Background analysis timed out for ${modelConfig.displayName} traceId=$traceId", e)
                // Transient: load-dependent, a retry may complete within the deadline.
                return handleTransientFailure(backgroundAnalysisScheduler, buildAnalysisTimeoutMessage(), attemptNumber, inputFile)
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
                    // R3: the queued input is durably superseded by the saved event(s); delete
                    // only the queue copy — the promoted source attachment is kept via
                    // keepSourceAttachment below.
                    inputFile.deleteQuietly()
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
                    // Transient: extraction/persistence issues surfaced by the use case may not
                    // reproduce on retry (model backend fallback, transient DB contention, etc.).
                    handleTransientFailure(backgroundAnalysisScheduler, result.message, attemptNumber, inputFile, notify = false)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: TerminalAnalysisException) {
            AppLog.w(TAG, "Terminal background analysis failure attempt=$attemptNumber: ${e.message}")
            notifyResult("Analysis failed", e.message ?: "Analysis failed.", Screen.Home.route)
            return terminalFailureResult(
                backgroundAnalysisScheduler,
                e.message ?: "Analysis failed.",
                notify = false,
                inputFile = inputFile
            )
        } catch (e: OutOfMemoryError) {
            AppLog.e(TAG, "Out of memory during background analysis attempt=$attemptNumber", e)
            notifyResult(
                "Analysis failed",
                "Ran out of memory during analysis. Try a smaller model, image, or audio clip.",
                Screen.Home.route
            )
            // Transient: memory pressure often clears by the next attempt (device GC, other
            // apps trimmed, the oomCallback above already released the LLM model early).
            return handleTransientFailure(
                backgroundAnalysisScheduler,
                "Ran out of memory during analysis.",
                attemptNumber,
                inputFile,
                notify = false
            )
        } catch (e: Exception) {
            AppLog.e(TAG, "Background analysis crashed for ${modelConfig.displayName} attempt=$attemptNumber", e)
            notifyResult(
                "Analysis failed",
                buildUnexpectedFailureMessage(e.message, runAttemptCount),
                Screen.Home.route
            )
            return handleTransientFailure(
                backgroundAnalysisScheduler,
                e.message ?: "Unexpected background analysis error.",
                attemptNumber,
                inputFile,
                notify = false
            )
        } finally {
            oomCallback?.let { applicationContext.unregisterComponentCallbacks(it) }
            closeResourceQuietly("GemmaLlmService") { gemmaLlmService.close() }
            // ML Kit text recognizer holds native resources; release them so retries
            // and the next worker invocation start from a clean slate.
            closeResourceQuietly("OcrService") { ocrService.close() }
            // Force GC to release native LiteRT engine memory before next retry
            Runtime.getRuntime().gc()
            if (!keepSourceAttachment) {
                sourceAttachment?.path?.let { File(it).deleteQuietly() }
            }
        }
    }

    /**
     * Non-allocating, idempotent resource close: swallows both a normal failure and an
     * out-of-memory close so one stuck resource never blocks the rest of cleanup or lets an
     * OOM escape [doWork] from within `finally` (R2 "resource close" injection point).
     */
    private inline fun closeResourceQuietly(label: String, close: () -> Unit) {
        try {
            close()
        } catch (e: OutOfMemoryError) {
            AppLog.w(TAG, "OOM while closing $label")
        } catch (e: Exception) {
            AppLog.w(TAG, "Failed to close $label", e)
        }
    }

    /**
     * Terminal (no retry): the failure cannot resolve by re-running the same job. [inputFile],
     * when supplied, is a genuinely dead queued input (A3) and is deleted immediately rather
     * than left for the scheduler's orphan sweep grace period.
     */
    private suspend fun terminalFailureResult(
        scheduler: BackgroundAnalysisScheduler,
        message: String,
        notify: Boolean = true,
        inputFile: File? = null
    ): Result {
        if (notify) {
            notifyResult("Analysis failed", message, Screen.Home.route)
        }
        rescueSuccessorsBeforeFailure(scheduler)
        inputFile?.deleteQuietly()
        return Result.failure(
            workDataOf(
                KEY_ERROR to message,
                KEY_ANALYSIS_FAILED to true
            )
        )
    }

    /**
     * Transient failure: retries while attempts remain (R1), keeping the queued input for the
     * next run. Once [attemptNumber] reaches [MAX_BACKGROUND_RUN_ATTEMPTS] the job is exhausted —
     * no further attempt is coming, so the queued input is deleted here (R3).
     */
    private suspend fun handleTransientFailure(
        scheduler: BackgroundAnalysisScheduler,
        message: String,
        attemptNumber: Int,
        inputFile: File,
        notify: Boolean = true
    ): Result {
        if (notify) {
            notifyResult("Analysis failed", message, Screen.Home.route)
        }
        if (shouldRetryBackgroundFailure(attemptNumber)) {
            return Result.retry()
        }
        rescueSuccessorsBeforeFailure(scheduler)
        inputFile.deleteQuietly()
        return Result.failure(
            workDataOf(
                KEY_ERROR to message,
                KEY_ANALYSIS_FAILED to true
            )
        )
    }

    /**
     * A1: WorkManager cascades this worker's imminent FAILED state forward to every dependent
     * appended after it in the unique-work chain, even though those jobs are otherwise valid.
     * Best-effort: a failure here just means the cascade proceeds unrescued, which is the
     * pre-existing (if flawed) behavior — it must never mask or replace the real failure result.
     */
    private suspend fun rescueSuccessorsBeforeFailure(scheduler: BackgroundAnalysisScheduler) {
        try {
            scheduler.rescueSuccessorsBeforeFailure(id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.w(TAG, "Failed to rescue chain successors before terminal failure workId=$id", e)
        }
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

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            ForegroundInfo(
                FOREGROUND_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                FOREGROUND_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
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
        val resultNotificationId = nextResultNotificationId()
        val displayMessage = if (debug != null) {
            "$message\n\nDebug JSON available. Tap to inspect."
        } else {
            message
        }
        val preferencesManager = PreferencesManager(applicationContext)
        val notification = NotificationCompat.Builder(applicationContext, ANALYSIS_RESULT_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(displayMessage)
            .setStyle(NotificationCompat.BigTextStyle().bigText(displayMessage))
            .setAutoCancel(true)
            .setContentIntent(createLaunchIntent(destinationRoute, resultNotificationId, debug, preferencesManager))
            .build()

        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(FOREGROUND_NOTIFICATION_ID)
        manager.notify(resultNotificationId, notification)
    }

    private fun createLaunchIntent(
        destinationRoute: String,
        requestCode: Int,
        debug: AnalysisFailureDebug?,
        preferencesManager: PreferencesManager
    ): PendingIntent? {
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_OPEN_ROUTE, destinationRoute)
            debug?.let {
                putExtra(MainActivity.EXTRA_DEBUG_FAILURE_TITLE, it.title)
                putExtra(MainActivity.EXTRA_DEBUG_FAILURE_BODY, it.body)
                putExtra(
                    MainActivity.EXTRA_DEBUG_FAILURE_NONCE,
                    preferencesManager.createDebugFailureNonce()
                )
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

    /**
     * Wraps [setForeground] to catch [ForegroundServiceStartNotAllowedException]
     * on Android 12+ when the app process is in the background. Instead of
     * crashing the worker, we continue without foreground promotion.
     */
    private suspend fun setForegroundSafe(foregroundInfo: ForegroundInfo) {
        try {
            setForeground(foregroundInfo)
        } catch (e: Exception) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                e.javaClass.name == "android.app.ForegroundServiceStartNotAllowedException"
            ) {
                AppLog.w(TAG, "ForegroundServiceStartNotAllowedException — continuing without foreground", e)
            } else {
                throw e
            }
        }
    }

    private fun nextResultNotificationId(): Int {
        val prefs = applicationContext.getSharedPreferences(RESULT_NOTIFICATION_PREFS, Context.MODE_PRIVATE)
        val current = prefs.getInt(KEY_NEXT_RESULT_NOTIFICATION_ID, RESULT_NOTIFICATION_ID_BASE - 1)
        val next = if (current >= Int.MAX_VALUE - 1) RESULT_NOTIFICATION_ID_BASE else current + 1
        prefs.edit().putInt(KEY_NEXT_RESULT_NOTIFICATION_ID, next).apply()
        return next
    }
}

private fun File.deleteQuietly() {
    if (exists()) {
        delete()
    }
}

internal fun hasExceededBackgroundAttemptLimit(runAttemptCount: Int): Boolean {
    return runAttemptCount >= MAX_BACKGROUND_RUN_ATTEMPTS
}

/**
 * R1: whether a transient failure at [attemptNumber] (1-indexed: `runAttemptCount + 1`) should
 * retry. Once the current attempt is already the last one [MAX_BACKGROUND_RUN_ATTEMPTS] allows,
 * the failure is exhausted and must resolve to `Result.failure()` instead of another retry.
 */
internal fun shouldRetryBackgroundFailure(attemptNumber: Int): Boolean {
    return attemptNumber < MAX_BACKGROUND_RUN_ATTEMPTS
}

/** R4/AC4: exact five-minute boundary — a clip of exactly [MAX_AUDIO_DURATION_MS] is accepted. */
internal fun exceedsMaxAudioDuration(durationMs: Long?): Boolean {
    return durationMs != null && durationMs > MAX_AUDIO_DURATION_MS
}

/** R4/AC4: exact byte-ceiling boundary — a file of exactly the max size is accepted. */
internal fun exceedsMaxAudioBytes(sizeBytes: Long): Boolean {
    return sizeBytes > FileImportHandler.MAX_AUDIO_BYTES
}

/** R1: signals a failure the same job can never resolve by retrying. */
private class TerminalAnalysisException(message: String) : Exception(message)

private fun LiteRtModelConfig.supportsInputType(inputType: AnalysisInputType): Boolean {
    return when (inputType) {
        AnalysisInputType.IMAGE -> supportsImage
        AnalysisInputType.AUDIO -> supportsAudio
        AnalysisInputType.TEXT -> true
    }
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
internal fun probeAudioDurationMs(file: File): Long? {
    return try {
        MediaMetadataRetriever().use { retriever ->
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                ?: readWavDurationMs(file)
        }
    } catch (_: Exception) {
        readWavDurationMs(file)
    }
}

internal fun readWavDurationMs(file: File): Long? {
    return runCatching {
        if (file.length() < WAV_HEADER_MIN_SIZE) return@runCatching null
        file.inputStream().use { input ->
            val riffHeader = input.readFullyOrNull(WAV_HEADER_MIN_SIZE) ?: return@runCatching null
            if (!riffHeader.startsWithAscii("RIFF", 0) || !riffHeader.startsWithAscii("WAVE", 8)) {
                return@runCatching null
            }

            var byteRate: Long? = null
            var dataSize: Long? = null

            while (byteRate == null || dataSize == null) {
                val chunkHeader = input.readFullyOrNull(WAV_CHUNK_HEADER_SIZE) ?: break
                val chunkId = chunkHeader.toAsciiString(0, 4)
                val chunkSize = chunkHeader.littleEndianInt(4)
                if (chunkSize < 0) {
                    return@runCatching null
                }
                when (chunkId) {
                    "fmt " -> {
                        val bytesToRead = minOf(chunkSize, WAV_FMT_CHUNK_READ_LIMIT)
                        val chunk = input.readFullyOrNull(bytesToRead) ?: return@runCatching null
                        input.skipFully((chunkSize - bytesToRead).toLong())
                        if (chunk.size >= WAV_BYTE_RATE_OFFSET_IN_FMT + Int.SIZE_BYTES) {
                            byteRate = ByteBuffer.wrap(chunk)
                                .order(ByteOrder.LITTLE_ENDIAN)
                                .getInt(WAV_BYTE_RATE_OFFSET_IN_FMT)
                                .toLong()
                        }
                    }
                    "data" -> {
                        dataSize = chunkSize.toLong()
                        input.skipFully(chunkSize.toLong())
                    }
                    else -> input.skipFully(chunkSize.toLong())
                }
                if (chunkSize % 2 == 1) {
                    input.skipFully(1L)
                }
            }

            val resolvedByteRate = byteRate ?: return@runCatching null
            val resolvedDataSize = dataSize ?: return@runCatching null
            if (resolvedByteRate <= 0L || resolvedDataSize <= 0L) return@runCatching null
            resolvedDataSize * 1000L / resolvedByteRate
        }
    }.getOrNull()
}

private fun MediaMetadataRetriever.use(block: (MediaMetadataRetriever) -> Long?): Long? {
    return try {
        block(this)
    } finally {
        runCatching { release() }
    }
}

private fun java.io.InputStream.readFullyOrNull(size: Int): ByteArray? {
    val buffer = ByteArray(size)
    var read = 0
    while (read < size) {
        val n = read(buffer, read, size - read)
        if (n <= 0) return null
        read += n
    }
    return buffer
}

private fun java.io.InputStream.skipFully(byteCount: Long) {
    var remaining = byteCount
    while (remaining > 0L) {
        val skipped = skip(remaining)
        if (skipped <= 0L) {
            if (read() == -1) return
            remaining -= 1L
        } else {
            remaining -= skipped
        }
    }
}

private fun ByteArray.startsWithAscii(value: String, offset: Int): Boolean {
    if (offset + value.length > size) return false
    return value.indices.all { index -> this[offset + index] == value[index].code.toByte() }
}

private fun ByteArray.toAsciiString(offset: Int, length: Int): String {
    return String(this, offset, length, StandardCharsets.US_ASCII)
}

private fun ByteArray.littleEndianInt(offset: Int): Int {
    return ByteBuffer.wrap(this, offset, Int.SIZE_BYTES)
        .order(ByteOrder.LITTLE_ENDIAN)
        .int
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

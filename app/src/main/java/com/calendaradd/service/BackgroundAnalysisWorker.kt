package com.calendaradd.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import com.calendaradd.util.AppLog
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlin.math.max

private const val ANALYSIS_CHANNEL_ID = "analysis_jobs"
private const val ANALYSIS_CHANNEL_NAME = "Background analysis"
private const val ANALYSIS_RESULT_CHANNEL_ID = "analysis_results"
private const val ANALYSIS_RESULT_CHANNEL_NAME = "Analysis results"
private const val FOREGROUND_NOTIFICATION_ID = 1301
private const val RESULT_NOTIFICATION_ID_BASE = 1302
private const val MAX_BACKGROUND_RUN_ATTEMPTS = 2
private const val QUEUED_IMAGE_MAX_DIMENSION = 1280

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

        val preferencesManager = PreferencesManager(applicationContext)
        val modelDownloadManager = ModelDownloadManager(applicationContext, preferencesManager)
        val backgroundAnalysisScheduler = BackgroundAnalysisScheduler(applicationContext)
        val gemmaLlmService = GemmaLlmService(applicationContext)
        val textAnalysisService = TextAnalysisService(gemmaLlmService)
        val calendarUseCase = CalendarUseCase(
            textAnalysisService = textAnalysisService,
            eventDatabase = EventDatabase.getDatabase(applicationContext),
            systemCalendarService = SystemCalendarService(applicationContext),
            preferencesManager = preferencesManager
        )

        try {
            AppLog.i(
                TAG,
                "Starting background analysis workId=$id attempt=$attemptNumber inputType=$inputType model=${modelConfig.shortName}"
            )
            ensureNotificationChannels()
            if (hasExceededBackgroundAttemptLimit(runAttemptCount)) {
                return failureResult(
                    "Background analysis restarted too many times before completion. " +
                        "Try a smaller model, a smaller image, or plain text input."
                )
            }

            setForeground(createForegroundInfo(buildProgressMessage("Initializing ${modelConfig.shortName}...", runAttemptCount)))

            if (!inputFile.exists()) {
                notifyResult(
                    "Analysis failed",
                    "The queued input file is no longer available.",
                    Screen.Home.route
                )
                return Result.failure(workDataOf(KEY_ERROR to "Queued input file is missing."))
            }

            if (!modelDownloadManager.isModelDownloaded(modelConfig)) {
                notifyResult(
                    "Model missing",
                    "${modelConfig.shortName} is not downloaded anymore.",
                    Screen.Home.route
                )
                return Result.failure(workDataOf(KEY_ERROR to "Selected model is no longer downloaded."))
            }

            gemmaLlmService.initialize(
                modelPath = modelDownloadManager.getModelFile(modelConfig).absolutePath,
                modelConfig = modelConfig
            )
            val keepModels = backgroundAnalysisScheduler.getPendingModels() + modelConfig
            modelDownloadManager.cleanupUnusedModelFiles(keepModels)

            val traceId = "bg-${System.currentTimeMillis().toString(16)}"
            val inputContext = InputContext(traceId = traceId)
            val result = when (inputType) {
                AnalysisInputType.TEXT -> {
                    setForeground(createForegroundInfo(buildProgressMessage("Analyzing text with ${modelConfig.shortName}...", runAttemptCount)))
                    val text = inputFile.readText(StandardCharsets.UTF_8)
                    calendarUseCase.createEventFromText(text, inputContext)
                }
                AnalysisInputType.IMAGE -> {
                    setForeground(createForegroundInfo(buildProgressMessage("Analyzing image with ${modelConfig.shortName}...", runAttemptCount)))
                    val bitmap = decodeQueuedImage(inputFile)
                        ?: return failureResult("Unable to decode the queued image.")
                    try {
                        calendarUseCase.createEventFromImage(bitmap, inputContext)
                    } finally {
                        if (!bitmap.isRecycled) {
                            bitmap.recycle()
                        }
                    }
                }
                AnalysisInputType.AUDIO -> {
                    setForeground(createForegroundInfo(buildProgressMessage("Analyzing audio with ${modelConfig.shortName}...", runAttemptCount)))
                    calendarUseCase.createEventFromAudio(inputFile.readBytes(), inputContext)
                }
            }

            return when (result) {
                is EventResult.Success -> {
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
            gemmaLlmService.close()
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

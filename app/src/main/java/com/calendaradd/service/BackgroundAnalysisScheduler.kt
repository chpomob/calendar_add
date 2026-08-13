package com.calendaradd.service

import android.content.Context
import android.graphics.Bitmap
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.calendaradd.usecase.PreferencesManager
import com.calendaradd.usecase.SourceAttachment
import com.calendaradd.util.AppLog
import com.calendaradd.util.hasWavHeader
import com.google.common.util.concurrent.ListenableFuture
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

private const val ANALYSIS_INPUT_DIR = "background-analysis-inputs"
private const val EVENT_SOURCE_DIR = "event-source-files"
private const val MODEL_TAG_PREFIX = "calendaradd-model:"
private const val INPUT_TAG_PREFIX = "calendaradd-input:"
private const val INPUT_SWEEP_GRACE_PERIOD_MS = 10 * 60 * 1000L

enum class AnalysisInputType {
    TEXT,
    IMAGE,
    AUDIO
}

data class PendingWorkStatus(
    val hasPendingWork: Boolean,
    val clearedStaleWork: Boolean,
    // A1: false only on the inconclusive path (WorkInfo query threw or hit the deadline). The
    // scheduler deliberately does NOT throw there — it leaves WorkManager untouched so callers
    // can retry safely — so the failure is surfaced here instead, letting callers distinguish
    // "confirmed no pending work" from "could not tell".
    val reconcileSucceeded: Boolean = true
)

class BackgroundAnalysisScheduler(
    context: Context,
    private val workManager: WorkManager = WorkManager.getInstance(context.applicationContext),
    private val modelDownloadManager: ModelDownloadManager =
        ModelDownloadManager(context.applicationContext, PreferencesManager(context.applicationContext)),
    // Bounded deadline for every blocking WorkManager observation. Overridable from tests so the
    // timeout path can be exercised without waiting the full production window.
    private val workManagerDeadlineMs: Long = WORK_MANAGER_DEADLINE_MS
) {
    companion object {
        private const val TAG = "BackgroundAnalysisScheduler"
        const val UNIQUE_WORK_NAME = "calendaradd-background-analysis"
        const val WORK_TAG = "calendaradd-background-analysis"

        const val KEY_INPUT_TYPE = "input_type"
        const val KEY_INPUT_PATH = "input_path"
        const val KEY_MODEL_ID = "model_id"

        /**
         * Hard ceiling (R3) for any single WorkManager query. WorkManager's ListenableFuture
         * normally completes in milliseconds, but an unbounded `Future.get()` can wedge a
         * reconcile call indefinitely if the database/executor stalls. 10s is generous
         * enough never to trip in practice and tight enough that callers recover quickly.
         */
        const val WORK_MANAGER_DEADLINE_MS = 10_000L

        /**
         * R1: process-wide coordinator. Every scheduler instance shares this single mutex —
         * MainActivity, ShareImportActivity and worker-created instances all live in one
         * process, so a companion-level Mutex is genuinely process-wide.
         *
         * It serializes the three critical sections that race against each other:
         *   1. persist input file + enqueue work,
         *   2. reconcile (read WorkInfo, classify, release stale inputs),
         *   3. release a finished worker's input.
         * Without it a reconcile can delete a just-persisted file before its WorkInfo exists
         * (X10 race). Final input release by the worker currently runs outside this lock; the
         * X6 race (a finishing RUNNING worker whose input it just deleted) is instead defused
         * by [isStale] never treating a RUNNING worker as stale. P6 will route the worker's
         * release through [coordinatorMutex] as well.
         */
        private val coordinatorMutex = Mutex()
    }

    private val appContext = context.applicationContext

    suspend fun enqueueText(input: String, model: LiteRtModelConfig): UUID = withContext(Dispatchers.IO) {
        persistAndEnqueue(AnalysisInputType.TEXT, model) { persistText(input) }
    }

    suspend fun enqueueImage(bitmap: Bitmap, model: LiteRtModelConfig): UUID = withContext(Dispatchers.IO) {
        persistAndEnqueue(AnalysisInputType.IMAGE, model) { persistBitmap(bitmap) }
    }

    suspend fun enqueueAudio(
        audioData: ByteArray,
        model: LiteRtModelConfig,
        mimeType: String? = null
    ): UUID = withContext(Dispatchers.IO) {
        val resolvedMimeType = audioData.inferAudioMimeType(mimeType)
        persistAndEnqueue(AnalysisInputType.AUDIO, model) {
            persistBytes(audioData, "audio", resolvedMimeType.audioFileExtension())
        }
    }

    fun promoteInputToEventSource(inputFile: File, inputType: AnalysisInputType): SourceAttachment? {
        if (inputType != AnalysisInputType.IMAGE && inputType != AnalysisInputType.AUDIO) return null
        if (!inputFile.exists()) return null

        val extension = when (inputType) {
            AnalysisInputType.IMAGE -> ".jpg"
            AnalysisInputType.AUDIO -> inputFile.extension.takeIf { it.isNotBlank() }?.let { ".$it" } ?: ".wav"
            AnalysisInputType.TEXT -> ".txt"
        }
        val prefix = when (inputType) {
            AnalysisInputType.IMAGE -> "source-image"
            AnalysisInputType.AUDIO -> "source-audio"
            AnalysisInputType.TEXT -> "source-text"
        }
        val mimeType = when (inputType) {
            AnalysisInputType.IMAGE -> "image/jpeg"
            AnalysisInputType.AUDIO -> inputFile.extension.audioMimeTypeForExtension()
            AnalysisInputType.TEXT -> "text/plain"
        }
        val targetDir = eventSourceStorageDir().apply { mkdirs() }
        val targetFile = File.createTempFile(prefix, extension, targetDir)
        inputFile.copyTo(targetFile, overwrite = true)
        return SourceAttachment(
            path = targetFile.absolutePath,
            mimeType = mimeType,
            displayName = inputFile.name
        )
    }

    suspend fun hasPendingWork(): Boolean {
        return reconcilePendingWork().hasPendingWork
    }

    suspend fun getPendingModels(): Set<LiteRtModelConfig> = withContext(Dispatchers.IO) {
        when (val query = getPendingInfos()) {
            is PendingInfoQuery.RecoverableError -> {
                // Conservative (A2): if we cannot read the queue we cannot know which models
                // are pending, so keep ALL managed models. Returning empty here would let
                // cleanupUnusedModelFiles delete a model a pending job still needs (and such
                // a job may have been enqueued NetworkType.NOT_REQUIRED, so it could not even
                // recover). A later successful query self-heals (AC3).
                LiteRtModelCatalog.models.toSet()
            }
            is PendingInfoQuery.Success -> query.infos
                .asSequence()
                .mapNotNull { info ->
                    val modelId = info.tags.firstOrNull { it.startsWith(MODEL_TAG_PREFIX) }
                        ?.removePrefix(MODEL_TAG_PREFIX)
                    LiteRtModelCatalog.models.firstOrNull { it.id == modelId }
                }
                .toSet()
        }
    }

    suspend fun reconcilePendingWork(): PendingWorkStatus = withContext(Dispatchers.IO) {
        coordinatorMutex.withLock { reconcileLocked() }
    }

    /**
     * R2: per-item reconciliation. Each pending WorkInfo is classified independently; only a
     * genuinely stale item is cancelled by id and (if its input still exists) has that single
     * input deleted. We never issue chain-wide [WorkManager.cancelUniqueWork], never prune the
     * whole chain, and never wipe the input directory.
     *
     * A1: cancelling one node in a unique-work chain cascades forward to every node appended
     * after it. getWorkInfosForUniqueWork() does not guarantee dependency/enqueue order, so a
     * stale item anywhere in the returned list may have cascade-cancelled any valid item.
     * Every valid non-RUNNING item is therefore re-enqueued ([reEnqueueSurvivor]); RUNNING
     * items head the pending chain and a cancel cascades only forward, never back to the
     * running head, so they are left untouched. Must run under [coordinatorMutex].
     */
    private suspend fun reconcileLocked(): PendingWorkStatus {
        val query = getPendingInfos()
        if (query is PendingInfoQuery.RecoverableError) {
            // R3: could not read the queue within the deadline. Do not claim work was cleared
            // and do not claim nothing is pending; leave WorkManager untouched so callers can
            // retry safely. A1: surface reconcileSucceeded=false so callers do not mistake the
            // unknown state for a clean "no pending work".
            return PendingWorkStatus(
                hasPendingWork = false,
                clearedStaleWork = false,
                reconcileSucceeded = false
            )
        }

        val infos = (query as PendingInfoQuery.Success).infos
        if (infos.isEmpty()) {
            sweepUnreferencedInputs(emptySet())
            return PendingWorkStatus(hasPendingWork = false, clearedStaleWork = false)
        }

        val referencedInputs = mutableSetOf<String>()
        val staleIds = mutableSetOf<UUID>()
        for (info in infos) {
            val inputPath = extractInputPath(info)
            if (inputPath != null) referencedInputs += inputPath
            if (isStale(info, inputPath)) {
                staleIds += info.id
            }
        }

        if (staleIds.isNotEmpty()) {
            // Cancel every stale node and delete its input.
            for (info in infos) {
                if (info.id in staleIds) {
                    clearStaleItem(info, extractInputPath(info))
                }
            }
            // A1 cascade recovery. Cancelling a stale node by id cascades forward through the
            // unique-work chain to every node appended after it. getWorkInfosForUniqueWork()
            // does NOT guarantee dependency/enqueue order, so a survivor's list position cannot
            // tell us whether the cascade reached it. The sound fix is to re-enqueue every
            // valid non-RUNNING item; [reEnqueueSurvivor] cancels the old id first, which is a
            // harmless no-op for items the cascade already cancelled. RUNNING items head the
            // pending chain and a cancel cascades only forward, never back to the running head,
            // so they are left untouched.
            for (info in infos) {
                if (info.id !in staleIds && info.state != WorkInfo.State.RUNNING) {
                    reEnqueueSurvivor(info)
                }
            }
        }

        sweepUnreferencedInputs(referencedInputs)
        return PendingWorkStatus(
            hasPendingWork = infos.size - staleIds.size > 0,
            clearedStaleWork = staleIds.isNotEmpty()
        )
    }

    /**
     * Re-enqueues a valid WorkInfo whose chain position was cancelled as collateral damage of a
     * stale-node cancellation (A1). Its persisted input is reused as-is; only the WorkManager
     * request is rebuilt (with a fresh id) and re-appended to the unique-work chain. The input
     * type is recovered from the file extension — every app-persisted input uses a distinctive
     * one. If reconstruction is impossible the survivor is skipped and left for the input sweep.
     */
    private suspend fun reEnqueueSurvivor(info: WorkInfo) {
        val inputPath = extractInputPath(info)
        val inputFile = inputPath?.let { File(it) }?.takeIf { it.exists() }
        if (inputFile == null) {
            AppLog.w(TAG, "Cannot re-enqueue survivor id=${info.id}: input unavailable")
            return
        }
        val modelId = info.tags.firstOrNull { it.startsWith(MODEL_TAG_PREFIX) }
            ?.removePrefix(MODEL_TAG_PREFIX)
        val model = LiteRtModelCatalog.models.firstOrNull { it.id == modelId }
        val inputType = inferInputTypeFromFile(inputFile)
        if (model == null || inputType == null) {
            AppLog.w(TAG, "Cannot re-enqueue survivor id=${info.id}: model=$modelId type=$inputType")
            return
        }
        try {
            AppLog.i(
                TAG,
                "Re-enqueueing survivor after stale-node cancellation id=${info.id} type=$inputType model=${model.id}"
            )
            // Cancel the original BEFORE re-enqueueing. Otherwise APPEND_OR_REPLACE would chain
            // the new request after this id and the new request would be cancelled by the very
            // cascade we are recovering from; cancelling first also makes this a no-op when the
            // stale-node cancel already cascade-cancelled this id.
            workManager.cancelWorkById(info.id)
            enqueueWorkAwaiting(inputType, inputFile, model)
        } catch (e: TimeoutCancellationException) {
            AppLog.w(TAG, "Re-enqueue of survivor id=${info.id} timed out", e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.w(TAG, "Failed to re-enqueue survivor id=${info.id}", e)
        }
    }

    private fun inferInputTypeFromFile(file: File): AnalysisInputType? = when (file.extension.lowercase()) {
        "txt" -> AnalysisInputType.TEXT
        "jpg", "jpeg" -> AnalysisInputType.IMAGE
        "wav", "mp3", "m4a", "mp4", "aac", "ogg", "flac", "audio" -> AnalysisInputType.AUDIO
        else -> null
    }

    /**
     * An item is stale only when it is not actively executing AND its persisted input is gone
     * (or it never carried input metadata). RUNNING workers are always treated as in-flight:
     * they delete their own input in a `finally` block as they finish, so a missing input on a
     * RUNNING worker is expected, not corrupt (X6 race).
     */
    private fun isStale(info: WorkInfo, inputPath: String?): Boolean {
        if (info.state == WorkInfo.State.RUNNING) return false
        if (inputPath == null) return true
        return !File(inputPath).exists()
    }

    private fun clearStaleItem(info: WorkInfo, inputPath: String?) {
        val reason = if (inputPath == null) "invalid-metadata" else "missing-input"
        AppLog.w(TAG, "Clearing stale background analysis item id=${info.id} reason=$reason")
        // Per-node cancel by id (never cancelUniqueWork). WorkManager propagates this cancel
        // to every node appended after this one, so valid successors are re-enqueued by the
        // caller (reconcileLocked) to undo the cascade (A1).
        workManager.cancelWorkById(info.id)
        inputPath?.let { path ->
            File(path).takeIf { it.exists() }?.let { file ->
                if (!file.delete()) {
                    AppLog.w(TAG, "Failed to delete stale input ${file.absolutePath}")
                }
            }
        }
    }

    /**
     * R1/R4: persist-then-enqueue is atomic under [coordinatorMutex]. If enqueue fails for any
     * reason after the file was written, only the just-persisted input is rolled back.
     */
    private suspend fun persistAndEnqueue(
        inputType: AnalysisInputType,
        model: LiteRtModelConfig,
        persist: () -> File
    ): UUID = coordinatorMutex.withLock {
        val inputFile = persist()
        try {
            enqueueWorkAwaiting(inputType, inputFile, model)
        } catch (e: TimeoutCancellationException) {
            // A3: the bounded enqueue observation timed out, so the operation's commit state is
            // unknown. Roll back the just-persisted input like any other enqueue failure —
            // TimeoutCancellationException is a CancellationException and would otherwise skip
            // the rollback branch below and orphan the input.
            AppLog.w(TAG, "Enqueue observation timed out; rolling back input ${inputFile.absolutePath}", e)
            rollbackInput(inputFile)
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.w(TAG, "Enqueue failed; rolling back input ${inputFile.absolutePath}", e)
            rollbackInput(inputFile)
            throw e
        }
    }

    private fun rollbackInput(inputFile: File) {
        if (!inputFile.delete()) {
            AppLog.w(TAG, "Rollback could not delete input ${inputFile.absolutePath}")
        }
    }

    private suspend fun enqueueWorkAwaiting(
        inputType: AnalysisInputType,
        inputFile: File,
        model: LiteRtModelConfig
    ): UUID {
        // Network is only required when the worker may still need to (re-)download the
        // model. Extraction itself is 100% on-device, so requiring connectivity when the
        // model is already cached just blocks jobs on a flaky/offline link unnecessarily.
        val modelAlreadyOnDisk = runCatching { modelDownloadManager.isModelDownloaded(model) }
            .getOrElse { error ->
                AppLog.w(TAG, "Could not probe model presence; assuming network required", error)
                false
            }
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(
                if (modelAlreadyOnDisk) NetworkType.NOT_REQUIRED else NetworkType.CONNECTED
            )
            .setRequiresBatteryNotLow(true)
            .build()

        val request = OneTimeWorkRequestBuilder<BackgroundAnalysisWorker>()
            .setInputData(
                Data.Builder()
                    .putString(KEY_INPUT_TYPE, inputType.name)
                    .putString(KEY_INPUT_PATH, inputFile.absolutePath)
                    .putString(KEY_MODEL_ID, model.id)
                    .build()
            )
            .setConstraints(constraints)
            .setBackoffCriteria(
                androidx.work.BackoffPolicy.EXPONENTIAL,
                1, TimeUnit.MINUTES
            )
            .addTag(WORK_TAG)
            .addTag("$MODEL_TAG_PREFIX${model.id}")
            .addTag("$INPUT_TAG_PREFIX${inputFile.absolutePath}")
            .build()

        val operation = workManager.enqueueUniqueWork(
            UNIQUE_WORK_NAME,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request
        )
        // getResult() yields SUCCESS on success and completes exceptionally on failure, so a
        // failed enqueue surfaces here as an exception that persistAndEnqueue rolls back (R4).
        withTimeout(workManagerDeadlineMs) { operation.result.awaitCancellable() }
        return request.id
    }

    private fun persistText(text: String): File {
        val file = createInputFile("text", ".txt")
        file.writeText(text, StandardCharsets.UTF_8)
        return file
    }

    private fun persistBitmap(bitmap: Bitmap): File {
        val file = createInputFile("image", ".jpg")
        FileOutputStream(file).use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)) {
                "Failed to persist image input"
            }
        }
        return file
    }

    private fun persistBytes(bytes: ByteArray, prefix: String, suffix: String): File {
        val file = createInputFile(prefix, suffix)
        file.writeBytes(bytes)
        return file
    }

    private fun createInputFile(prefix: String, suffix: String): File {
        val dir = inputStorageDir().apply { mkdirs() }
        return File.createTempFile(prefix, suffix, dir)
    }

    private fun inputStorageDir(): File {
        return File(appContext.noBackupFilesDir, ANALYSIS_INPUT_DIR)
    }

    private fun eventSourceStorageDir(): File {
        return File(appContext.filesDir, EVENT_SOURCE_DIR)
    }

    /**
     * R5: sweep only aged, demonstrably unowned files. Young files (< grace period) are kept so
     * a just-persisted input that has not yet been reconciled into a WorkInfo is never deleted
     * (X10 race); files referenced by a live WorkInfo are always kept. Must run under
     * [coordinatorMutex] so it cannot race a concurrent persist+enqueue.
     */
    private fun sweepUnreferencedInputs(referencedPaths: Set<String>) {
        val now = System.currentTimeMillis()
        inputStorageDir().listFiles()?.forEach { file ->
            val isTooYoungToSweep = now - file.lastModified() < INPUT_SWEEP_GRACE_PERIOD_MS
            if (file.isFile && !isTooYoungToSweep && file.absolutePath !in referencedPaths && !file.delete()) {
                AppLog.w(TAG, "Failed to delete orphan queued input ${file.absolutePath}")
            }
        }
    }

    /**
     * R3: bounded, cancellable WorkManager observation. Replaces the blocking unbounded
     * `Future.get()`: the query is awaited on a [suspendCancellableCoroutine] that resumes the
     * instant the future completes, is hard-bounded by [workManagerDeadlineMs], and cancels the
     * future if the awaiting coroutine is itself cancelled. Any timeout or exception yields a
     * typed [PendingInfoQuery.RecoverableError] instead of propagating an uncaught exception.
     */
    private suspend fun getPendingInfos(): PendingInfoQuery {
        val future = workManager.getWorkInfosForUniqueWork(UNIQUE_WORK_NAME)
        val infos = try {
            withTimeout(workManagerDeadlineMs) { future.awaitCancellable() }
        } catch (e: TimeoutCancellationException) {
            AppLog.w(TAG, "WorkInfo query timed out after ${workManagerDeadlineMs}ms", e)
            return PendingInfoQuery.RecoverableError(e)
        } catch (e: CancellationException) {
            // Real coroutine cancellation: propagate so structured concurrency tears down.
            throw e
        } catch (e: Exception) {
            AppLog.w(TAG, "WorkInfo query failed", e)
            return PendingInfoQuery.RecoverableError(e)
        }
        return PendingInfoQuery.Success(
            infos.filter { it.state in PENDING_STATES }
        )
    }

    private fun extractInputPath(info: WorkInfo): String? {
        return info.tags.firstOrNull { it.startsWith(INPUT_TAG_PREFIX) }
            ?.removePrefix(INPUT_TAG_PREFIX)
    }

    private sealed interface PendingInfoQuery {
        data class Success(val infos: List<WorkInfo>) : PendingInfoQuery
        data class RecoverableError(val cause: Throwable? = null) : PendingInfoQuery
    }
}

private val PENDING_STATES = setOf(
    WorkInfo.State.ENQUEUED,
    WorkInfo.State.RUNNING,
    WorkInfo.State.BLOCKED
)

/** Runs the future's completion listener inline on whatever thread completes it. */
private val DIRECT_EXECUTOR = Executor { command: Runnable -> command.run() }

/**
 * Bridges a [ListenableFuture] into coroutine cancellation semantics: resumes with the future's
 * value when it completes, and cancels the future (best-effort) if the coroutine is cancelled.
 */
private suspend fun <T> ListenableFuture<T>.awaitCancellable(): T = suspendCancellableCoroutine { cont ->
    addListener(
        Runnable {
            try {
                cont.resume(get())
            } catch (e: Exception) {
                cont.resumeWithException(e)
            }
        },
        DIRECT_EXECUTOR
    )
    cont.invokeOnCancellation { runCatching { cancel(false) } }
}

private fun ByteArray.inferAudioMimeType(declaredMimeType: String?): String {
    return declaredMimeType
        ?.takeIf { it.startsWith("audio/", ignoreCase = true) }
        ?: if (hasWavHeader()) "audio/wav" else "audio/mpeg"
}

private fun String.audioFileExtension(): String {
    return when (lowercase()) {
        "audio/wav", "audio/wave", "audio/x-wav" -> ".wav"
        "audio/mpeg", "audio/mp3" -> ".mp3"
        "audio/mp4", "audio/aac", "audio/x-m4a" -> ".m4a"
        "audio/ogg" -> ".ogg"
        "audio/flac" -> ".flac"
        else -> ".audio"
    }
}

private fun String.audioMimeTypeForExtension(): String {
    return when (lowercase()) {
        "wav" -> "audio/wav"
        "mp3" -> "audio/mpeg"
        "m4a", "mp4", "aac" -> "audio/mp4"
        "ogg" -> "audio/ogg"
        "flac" -> "audio/flac"
        else -> "audio/*"
    }
}

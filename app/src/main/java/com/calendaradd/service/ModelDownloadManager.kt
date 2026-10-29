package com.calendaradd.service

import android.app.DownloadManager
import android.content.Context
import android.database.Cursor
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Environment
import android.telephony.TelephonyManager
import androidx.core.net.toUri
import com.calendaradd.BuildConfig
import com.calendaradd.usecase.PreferencesManager
import com.calendaradd.util.AppLog
import java.io.BufferedInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import java.util.Properties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlin.concurrent.withLock

/**
 * Manages downloads for selectable LiteRT-LM models.
 *
 * Storage model (R1): every operation resolves a single storage root — the app-specific
 * external Downloads dir when available, internal app storage otherwise — and uses that
 * same root for the final file, free-space check, active-download matching, verification,
 * quarantine, and the download destination.
 *
 * Readiness (R2): a model counts as downloaded only when its file passes length + streamed
 * SHA-256. A verified outcome is persisted as a sidecar so it is recognised across launches
 * without re-hashing. Any mismatch atomically quarantines the file (it is never deleted) and
 * blocks implicit retry.
 */
class ModelDownloadManager(
    private val context: Context,
    private val preferencesManager: PreferencesManager,
    private val connectionFactory: (String) -> HttpURLConnection = { url ->
        URL(url).openConnection() as HttpURLConnection
    },
    private val activeStatusQueryOverride: (() -> Cursor?)? = null,
    private val sdkIntProvider: () -> Int = { Build.VERSION.SDK_INT }
) {
    companion object {
        private const val TAG = "ModelDownloadManager"
        private const val QUARANTINE_DIR_NAME = ".model-quarantine"
        private const val VERIFIED_SUFFIX = ".verified"
        private const val DIAG_SUFFIX = ".diag"
        private const val INTERNAL_PART_SUFFIX = ".part"
        private const val CONNECT_TIMEOUT_MS = 30_000
        private const val READ_TIMEOUT_MS = 60_000
        private const val STREAM_BUFFER_SIZE = 64 * 1024

        /** Returned by [startDownload] when external storage is unavailable and the file is
         *  streamed into internal storage; [trackProgress] recognises it and runs the stream. */
        internal const val INTERNAL_STREAM_ID = -2L

        private val ACTIVE_STATUSES = DownloadManager.STATUS_PENDING or
            DownloadManager.STATUS_RUNNING or
            DownloadManager.STATUS_PAUSED

        private val LEGACY_MODEL_FILE_NAMES = setOf(
            "qwen3.5-0.8b-model_multimodal.litertlm",
            "qwen3.5-4b-model_multimodal.litertlm",
            "qwen35_mm_q8_ekv4096.litertlm",
            // Gemma 3n entries removed from the catalogue; leftover files stay eligible for cleanup.
            "gemma-3n-E2B-it-int4.litertlm",
            "gemma-3n-E4B-it-int4.litertlm"
        )
    }

    private val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

    /** All DownloadManager status lookups route through this single seam so the operation is
     *  defensive (a query failure degrades to "no active downloads") and testable. The default
     *  runs the real DownloadManager query; tests inject a cursor provider. */
    private val activeStatusQuery: () -> Cursor? = activeStatusQueryOverride ?: {
        runCatching { downloadManager.query(DownloadManager.Query().setFilterByStatus(ACTIVE_STATUSES)) }.getOrNull()
    }

    /**
     * Single resolved storage root used by every operation (R1): app-specific external
     * Downloads when available, internal app storage otherwise.
     */
    private fun resolveStorageRoot(): File =
        context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir

    private fun resolveRootAndMode(): Pair<File, Boolean> {
        val external = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        return (external ?: context.filesDir) to (external != null)
    }

    private fun quarantineDir(root: File): File =
        File(root, QUARANTINE_DIR_NAME).apply { if (!exists()) mkdirs() }

    /**
     * Returns the local file path where the model should be stored, under the resolved root.
     */
    fun getModelFile(model: LiteRtModelConfig = getSelectedModel()): File {
        val dir = resolveStorageRoot()
        if (!dir.exists()) dir.mkdirs()
        return File(dir, model.fileName)
    }

    fun getSelectedModel(): LiteRtModelConfig {
        return LiteRtModelCatalog.find(preferencesManager.selectedModelId)
    }

    /**
     * Checks if the model is present AND verified (length + streamed SHA-256). A verified file
     * is recognised across launches via its persisted sidecar. A complete-but-invalid file is
     * atomically quarantined (never deleted) so it is excluded from lookup. In-progress
     * downloads are left untouched.
     */
    fun isModelDownloaded(model: LiteRtModelConfig = getSelectedModel()): Boolean {
        val root = resolveStorageRoot()
        return isModelDownloadedIn(root, model)
    }

    private fun isModelDownloadedIn(root: File, model: LiteRtModelConfig): Boolean {
        val file = File(root, model.fileName)
        if (!file.exists()) return false
        if (cachedVerificationValid(model, file)) return true
        if (isDownloadActiveForFile(file)) return false // never touch a download in progress
        val outcome = verifyLengthAndDigest(model, file)
        return if (outcome.verified) {
            recordVerification(model, file)
            true
        } else {
            quarantineFile(root, model, file, outcome.reason ?: "verification failed")
            false
        }
    }

    /**
     * Estimates if there is enough disk space for the model on the resolved root.
     */
    fun hasEnoughSpace(model: LiteRtModelConfig = getSelectedModel()): Boolean {
        return resolveStorageRoot().usableSpace > model.requiredFreeSpaceBytes
    }

    /**
     * Removes stale model files managed by the app while preserving the active ones.
     *
     * A3 (TOCTOU on model selection): the read of the live selection and the delete loop run
     * inside [PreferencesManager.modelSelectionLock], the same lock acquired by the
     * [PreferencesManager.selectedModelId] setter (the only writer). A model the user selects
     * while cleanup runs therefore cannot land between this read and the deletes: the setter
     * blocks until cleanup releases the lock, so cleanup either sees the new selection (and
     * keeps it) or finishes before the selection changes. The caller's [keepModels] snapshot —
     * which may be seconds stale (the WorkManager pending-model query has a 10s deadline) — is
     * still unioned with the live selection under the lock as defense-in-depth. The lock covers
     * only ms-scale work (one DownloadManager query + a few unlink()s), so the UI-thread setter
     * stalls at most one frame.
     */
    fun cleanupUnusedModelFiles(keepModels: Collection<LiteRtModelConfig> = emptyList()) {
        val dir = resolveStorageRoot()
        if (!dir.exists()) return
        preferencesManager.modelSelectionLock.withLock {
            val keepFileNames = (keepModels + getSelectedModel()).mapTo(mutableSetOf()) { it.fileName }
            // R5: never delete the destination of a pending/running DownloadManager job, even if it
            // is no longer in keepModels (e.g. the user switched model mid-download). Matched on raw
            // rows rather than activeDownloads() so legacy (non-catalogue) targets are protected too.
            val activeTargetNames = activeRows().mapNotNullTo(mutableSetOf()) { row ->
                uriToPath(row.localUri)?.let { File(it) }?.takeIf { it.parentFile?.absolutePath == dir.absolutePath }?.name
            }

            val managedFileNames = LiteRtModelCatalog.models.mapTo(mutableSetOf<String>()) { it.fileName }.apply {
                addAll(LEGACY_MODEL_FILE_NAMES)
            }

            dir.listFiles()
                ?.filter { file ->
                    file.isFile &&
                        file.name in managedFileNames &&
                        file.name !in keepFileNames &&
                        file.name !in activeTargetNames
                }
                ?.forEach { file ->
                    if (file.delete()) {
                        AppLog.i(TAG, "Removed unused model file ${file.name}")
                    } else {
                        AppLog.w(TAG, "Failed to remove unused model file ${file.name}")
                    }
                }
        }
    }

    /**
     * Resolves a single download plan (R1): one root, one destination, one network policy.
     * External mode hands the destination to DownloadManager; internal mode streams into a
     * bounded temp file and atomically promotes verified content (R4).
     */
    internal fun downloadPlan(
        model: LiteRtModelConfig = getSelectedModel(),
        policy: NetworkPolicy = NetworkPolicy.CONSERVATIVE
    ): DownloadPlan {
        val (root, useExternal) = resolveRootAndMode()
        val target = File(root, model.fileName)
        val part = if (useExternal) null else File(root, model.fileName + INTERNAL_PART_SUFFIX)
        return DownloadPlan(
            model = model,
            policy = policy,
            useExternal = useExternal,
            resolvedRoot = root,
            targetFile = target,
            partFile = part,
            allowMetered = policy.allowMetered,
            allowRoaming = policy.allowRoaming
        )
    }

    /**
     * Starts the download of the selected LiteRT-LM model under the explicit [policy].
     * Returns the DownloadManager id, [INTERNAL_STREAM_ID] for an internal-streamed download,
     * or -1 if the model is already verified and nothing needs to happen.
     */
    fun startDownload(
        model: LiteRtModelConfig = getSelectedModel(),
        policy: NetworkPolicy = NetworkPolicy.CONSERVATIVE
    ): Long {
        val plan = downloadPlan(model, policy)

        if (plan.resolvedRoot.usableSpace <= model.requiredFreeSpaceBytes) {
            throw IllegalStateException(
                "Not enough disk space to download ${model.displayName} (requires ~${model.requiredFreeSpaceLabel} free)."
            )
        }

        if (isModelDownloadedIn(plan.resolvedRoot, model)) {
            AppLog.i(TAG, "Model ${model.shortName} is already verified, skipping re-download")
            return -1L
        }

        findActiveDownloadId(plan.targetFile, model)?.let { downloadId ->
            AppLog.i(TAG, "Re-attaching to active model download id=$downloadId file=${plan.targetFile.name}")
            preferencesManager.activeModelDownloadId = downloadId
            preferencesManager.activeModelDownloadModelId = model.id
            return downloadId
        }

        // Make room without deleting: quarantine any unverified leftover so DownloadManager
        // won't flag ERROR_FILE_ALREADY_EXISTS and so a corrupt file is never lost for diagnosis.
        if (plan.targetFile.exists()) {
            quarantineFile(plan.resolvedRoot, model, plan.targetFile, "stale leftover before re-download")
        }

        return if (plan.useExternal) {
            val request = DownloadManager.Request(model.downloadUrl.toUri())
                .setTitle("Downloading ${model.displayName}")
                .setDescription("Required for local ${model.capabilitySummary.lowercase(Locale.ROOT)} extraction")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, model.fileName)
                .setAllowedOverMetered(policy.allowMetered)
                .setAllowedOverRoaming(policy.allowRoaming)
            downloadManager.enqueue(request).also { downloadId ->
                preferencesManager.activeModelDownloadId = downloadId
                preferencesManager.activeModelDownloadModelId = model.id
            }
        } else {
            AppLog.i(TAG, "External storage unavailable; streaming ${model.shortName} into internal storage")
            preferencesManager.activeModelDownloadModelId = model.id
            INTERNAL_STREAM_ID
        }
    }

    /**
     * Tracks the progress of a download id (DownloadManager job, [INTERNAL_STREAM_ID], or -1).
     */
    fun trackProgress(
        downloadId: Long,
        model: LiteRtModelConfig,
        policy: NetworkPolicy = NetworkPolicy.CONSERVATIVE
    ): Flow<DownloadStatus> = when {
        downloadId == -1L -> flowOf(DownloadStatus.Success)
        downloadId == INTERNAL_STREAM_ID -> streamInternalFlow(downloadPlan(model, policy))
        else -> downloadManagerFlow(downloadId, model)
    }

    private fun downloadManagerFlow(downloadId: Long, model: LiteRtModelConfig): Flow<DownloadStatus> = flow {
        var isDownloading = true
        while (isDownloading) {
            val query = DownloadManager.Query().setFilterById(downloadId)
            downloadManager.query(query)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    val bytesDownloaded = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                    val bytesTotal = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))

                    when (status) {
                        DownloadManager.STATUS_SUCCESSFUL -> {
                            val root = resolveStorageRoot()
                            val file = File(root, model.fileName)
                            val outcome = verifyLengthAndDigest(model, file)
                            if (outcome.verified) {
                                recordVerification(model, file)
                                preferencesManager.clearActiveModelDownload(downloadId)
                                emit(DownloadStatus.Success)
                            } else {
                                quarantineFile(root, model, file, outcome.reason ?: "verification failed")
                                preferencesManager.clearActiveModelDownload(downloadId)
                                emit(DownloadStatus.Failed("Downloaded model failed verification and was quarantined. Try again."))
                            }
                            isDownloading = false
                        }
                        DownloadManager.STATUS_FAILED -> {
                            val reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                            val message = when (reason) {
                                DownloadManager.ERROR_CANNOT_RESUME -> "Cannot resume download."
                                DownloadManager.ERROR_DEVICE_NOT_FOUND -> "Device not found."
                                DownloadManager.ERROR_FILE_ALREADY_EXISTS -> "File already exists."
                                DownloadManager.ERROR_FILE_ERROR -> "File error."
                                DownloadManager.ERROR_HTTP_DATA_ERROR -> "HTTP data error."
                                DownloadManager.ERROR_INSUFFICIENT_SPACE -> "Insufficient disk space."
                                DownloadManager.ERROR_TOO_MANY_REDIRECTS -> "Too many redirects."
                                DownloadManager.ERROR_UNHANDLED_HTTP_CODE -> "Unhandled HTTP code."
                                else -> "Download failed (error code: $reason). Please check your connection."
                            }
                            preferencesManager.clearActiveModelDownload(downloadId)
                            emit(DownloadStatus.Failed(message))
                            isDownloading = false
                        }
                        DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PAUSED -> {
                            val progress = if (bytesTotal > 0) (bytesDownloaded * 100 / bytesTotal).toInt() else 0
                            emit(DownloadStatus.Progress(progress))
                        }
                    }
                } else {
                    emit(DownloadStatus.Failed("Download was not found by the system downloader."))
                    isDownloading = false
                }
            }
            if (isDownloading) delay(1000)
        }
    }.flowOn(Dispatchers.IO)

    /** R4: stream into a bounded temp file under the resolved internal root, verify, then
     *  atomically promote only verified content; quarantine on mismatch. */
    private fun streamInternalFlow(plan: DownloadPlan): Flow<DownloadStatus> = flow {
        if (!isNetworkAllowed(plan.policy)) {
            preferencesManager.clearActiveModelDownload()
            emit(DownloadStatus.Failed("Download blocked: the current network does not satisfy the selected policy (it is metered or roaming). Change networks or allow metered downloads."))
            return@flow
        }
        val part = plan.partFile ?: error("Internal stream plan must define a part file")
        part.parentFile?.mkdirs()
        part.delete()
        var connection: HttpURLConnection? = null
        try {
            connection = connectionFactory(plan.model.downloadUrl).apply {
                instanceFollowRedirects = true
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                requestMethod = "GET"
                setRequestProperty("User-Agent", "CalendarAdd/${BuildConfig.VERSION_NAME}")
            }
            val code = connection.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("HTTP $code")
            }
            val total = plan.model.sizeBytes
            // R4: bound the temp file. Reject any response that claims to exceed the expected
            // model size before we write a byte (Content-Length is -1 when unknown/chunked).
            val declared = connection.contentLengthLong
            if (total > 0L && declared > 0L && declared > total) {
                throw IllegalStateException("declared Content-Length $declared exceeds expected ${total}")
            }
            var downloaded = 0L
            connection.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buffer = ByteArray(STREAM_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        out.write(buffer, 0, read)
                        downloaded += read
                        if (total > 0L) {
                            if (downloaded > total) {
                                throw IllegalStateException("streamed $downloaded bytes exceeds expected model size $total")
                            }
                            emit(DownloadStatus.Progress(((downloaded * 100L) / total).toInt().coerceIn(0, 100)))
                        }
                    }
                }
            }
            val outcome = verifyLengthAndDigest(plan.model, part)
            if (outcome.verified) {
                if (plan.targetFile.exists()) plan.targetFile.delete()
                if (!part.renameTo(plan.targetFile)) {
                    part.copyTo(plan.targetFile, overwrite = true)
                    part.delete()
                }
                recordVerification(plan.model, plan.targetFile)
                preferencesManager.clearActiveModelDownload()
                emit(DownloadStatus.Success)
            } else {
                quarantineFile(plan.resolvedRoot, plan.model, part, outcome.reason ?: "verification failed")
                preferencesManager.clearActiveModelDownload()
                emit(DownloadStatus.Failed("Downloaded model failed verification and was quarantined. Try again."))
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "Internal model download failed", e)
            part.delete()
            preferencesManager.clearActiveModelDownload()
            emit(DownloadStatus.Failed("Internal download failed: ${e.message}"))
        } finally {
            connection?.disconnect()
        }
    }.flowOn(Dispatchers.IO)

    /**
     * R5: active download targets so cleanup can protect in-progress downloads. Returns the
     * target model files that have a pending/running/paused DownloadManager job.
     */
    fun activeDownloads(): List<ActiveDownload> {
        val root = resolveStorageRoot()
        return activeRows().mapNotNull { row ->
            val path = uriToPath(row.localUri) ?: return@mapNotNull null
            val absolutePath = File(path).absolutePath
            val matchedModel = LiteRtModelCatalog.models.firstOrNull {
                File(root, it.fileName).absolutePath == absolutePath
            } ?: return@mapNotNull null
            ActiveDownload(File(root, matchedModel.fileName), row.id)
        }
    }

    /** Snapshot of pending/running/paused DownloadManager jobs via the single query seam. */
    private fun activeRows(): List<ActiveRow> {
        val cursor = activeStatusQuery() ?: return emptyList()
        return cursor.use { c ->
            val idColumn = c.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)
            val localUriColumn = c.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI)
            val rows = ArrayList<ActiveRow>()
            while (c.moveToNext()) {
                rows.add(ActiveRow(c.getLong(idColumn), c.getString(localUriColumn)))
            }
            rows
        }
    }

    private fun findActiveDownloadId(targetFile: File, model: LiteRtModelConfig): Long? {
        val rows = activeRows()
        val savedId = preferencesManager.activeModelDownloadId
        if (savedId > 0L && preferencesManager.activeModelDownloadModelId == model.id) {
            val stillActive = rows.any { it.id == savedId && uriMatchesFile(it.localUri, targetFile) }
            if (stillActive) return savedId
            preferencesManager.clearActiveModelDownload(savedId)
        }
        return rows.firstOrNull { uriMatchesFile(it.localUri, targetFile) }?.id
    }

    private fun isDownloadActiveForFile(file: File): Boolean =
        activeRows().any { uriMatchesFile(it.localUri, file) }

    private fun uriMatchesFile(localUri: String?, targetFile: File): Boolean {
        val path = uriToPath(localUri) ?: return false
        return File(path).absolutePath == targetFile.absolutePath
    }

    /** Parses a DownloadManager local URI to a filesystem path using java.net.URI so it works
     *  without the Android framework (and in local JVM tests). Our destinations are file:// URIs. */
    private fun uriToPath(localUri: String?): String? {
        if (localUri.isNullOrBlank()) return null
        return runCatching {
            val uri = URI(localUri)
            uri.path ?: uri.schemeSpecificPart
        }.getOrNull()
    }

    private fun verifyLengthAndDigest(model: LiteRtModelConfig, file: File): VerifyOutcome {
        if (!file.exists()) {
            return VerifyOutcome(false, "file missing")
        }
        val actualLength = file.length()
        if (actualLength != model.sizeBytes) {
            return VerifyOutcome(false, "size mismatch (actual=$actualLength expected=${model.sizeBytes})")
        }
        val actualSha256 = sha256(file)
        return if (actualSha256.equals(model.sha256, ignoreCase = true)) {
            VerifyOutcome(true, null)
        } else {
            VerifyOutcome(false, "sha256 mismatch (actual=$actualSha256 expected=${model.sha256})")
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        BufferedInputStream(file.inputStream()).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * A verified model is recorded as a sidecar keyed by model id + expected metadata +
     * canonical path + length + last-modified, so it is recognised across launches without
     * re-hashing. ponytail: length+mtime is the cache-invalidation heuristic; if a file is
     * tampered with but preserves both, the stale record holds. Upgrade to per-file xattr or a
     * content-addressed store if that becomes a threat.
     */
    private fun recordVerification(model: LiteRtModelConfig, file: File) {
        val sidecar = File(file.parentFile, file.name + VERIFIED_SUFFIX)
        runCatching {
            Properties().apply {
                setProperty("modelId", model.id)
                setProperty("expectedSha256", model.sha256)
                setProperty("expectedSize", model.sizeBytes.toString())
                setProperty("canonicalPath", file.absolutePath)
                setProperty("verifiedLength", file.length().toString())
                setProperty("verifiedLastModified", file.lastModified().toString())
            }.store(sidecar.outputStream(), "verified model ${model.id}")
        }.onFailure { AppLog.w(TAG, "Failed to record model verification sidecar", it) }
    }

    private fun cachedVerificationValid(model: LiteRtModelConfig, file: File): Boolean {
        val sidecar = File(file.parentFile, file.name + VERIFIED_SUFFIX)
        if (!sidecar.exists()) return false
        return runCatching {
            val props = Properties().apply { sidecar.inputStream().use { load(it) } }
            props.getProperty("modelId") == model.id &&
                props.getProperty("expectedSha256").equals(model.sha256, ignoreCase = true) &&
                props.getProperty("expectedSize")?.toLongOrNull() == model.sizeBytes &&
                props.getProperty("canonicalPath") == file.absolutePath &&
                props.getProperty("verifiedLength")?.toLongOrNull() == file.length() &&
                props.getProperty("verifiedLastModified")?.toLongOrNull() == file.lastModified()
        }.getOrDefault(false)
    }

    /**
     * Atomically moves a completed-but-invalid file into the app-private quarantine directory
     * (inside the resolved root, so the rename is guaranteed atomic) and records a user-visible
     * diagnostic. Never deletes. Returns the quarantine destination, or null if nothing moved.
     */
    private fun quarantineFile(root: File, model: LiteRtModelConfig, file: File, reason: String): File? {
        if (!file.exists()) return null
        val dir = quarantineDir(root)
        val stamp = System.currentTimeMillis()
        val actualLength = file.length()
        val destination = File(dir, "${file.name}.$stamp")
        val moved = file.renameTo(destination)
        if (!moved) {
            // Same-filesystem rename should always succeed here; copy+delete fallback if it ever
            // does not, but verify the copy before deleting the original to avoid data loss.
            runCatching {
                file.copyTo(destination, overwrite = true)
                if (destination.length() == actualLength) file.delete() else destination.delete()
            }.onFailure {
                AppLog.w(TAG, "Failed to quarantine model file ${file.name}", it)
                return null
            }
        }
        val diagnostic = File(dir, "${destination.name}$DIAG_SUFFIX")
        runCatching {
            diagnostic.writeText(
                buildString {
                    appendLine("modelId=${model.id}")
                    appendLine("fileName=${file.name}")
                    appendLine("expectedSha256=${model.sha256}")
                    appendLine("expectedSize=${model.sizeBytes}")
                    appendLine("actualSize=$actualLength")
                    appendLine("canonicalPath=${file.absolutePath}")
                    appendLine("reason=$reason")
                    appendLine("timestamp=$stamp")
                }
            )
        }
        AppLog.w(TAG, "Quarantined model file ${file.name}: $reason")
        return destination
    }

    /** R3/R4: block when the network is confirmed metered (unless [NetworkPolicy.allowMetered])
     *  and, for the internal path that bypasses DownloadManager, when it is roaming and the
     *  policy disallows roaming. */
    private fun isNetworkAllowed(policy: NetworkPolicy): Boolean {
        if (policy.allowMetered && policy.allowRoaming) return true
        return runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
            val network = cm.activeNetwork ?: return true
            val caps = cm.getNetworkCapabilities(network) ?: return true
            if (!policy.allowMetered && !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) return false
            if (!policy.allowRoaming && isRoaming(caps)) return false
            true
        }.getOrDefault(true)
    }

    /** R3: true when the active network is roaming, across the full supported OS range (minSdk 26).
     *  API 28+: [NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING]. API 26/27: that capability does
     *  not exist, so fall back to [TelephonyManager.isNetworkRoaming]; non-cellular devices report
     *  no telephony service and are treated as not roaming. */
    private fun isRoaming(caps: NetworkCapabilities): Boolean {
        if (sdkIntProvider() >= Build.VERSION_CODES.P) {
            return !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING)
        }
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return false
        return tm.isNetworkRoaming
    }
}

/** R3: explicit network policy for a download attempt. CONSERVATIVE (default) blocks metered
 *  and roaming; UNRESTRICTED is used only for a confirmed/consented attempt. */
enum class NetworkPolicy(val allowMetered: Boolean, val allowRoaming: Boolean) {
    CONSERVATIVE(allowMetered = false, allowRoaming = false),
    UNRESTRICTED(allowMetered = true, allowRoaming = true)
}

/** R5: a model file with an active DownloadManager job, exposed for cleanup protection. */
data class ActiveDownload(val targetFile: File, val downloadId: Long)

/** Testable, Android-free description of a resolved download operation (R1). */
internal data class DownloadPlan(
    val model: LiteRtModelConfig,
    val policy: NetworkPolicy,
    val useExternal: Boolean,
    val resolvedRoot: File,
    val targetFile: File,
    val partFile: File?,
    val allowMetered: Boolean,
    val allowRoaming: Boolean
)

private data class VerifyOutcome(val verified: Boolean, val reason: String?)

private data class ActiveRow(val id: Long, val localUri: String?)

sealed class DownloadStatus {
    data class Progress(val percentage: Int) : DownloadStatus()
    object Success : DownloadStatus()
    data class Failed(val error: String) : DownloadStatus()
}

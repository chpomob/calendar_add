package com.calendaradd.usecase

import android.content.Context
import android.content.SharedPreferences
import android.os.Environment
import androidx.core.content.edit
import com.calendaradd.service.LiteRtModelCatalog
import com.calendaradd.service.deviceMemoryGb
import java.io.File
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Manages user preferences persistence.
 */
class PreferencesManager(
    context: Context,
    private val deviceMemoryGbProvider: () -> Double? = { context.deviceMemoryGb() },
    /** Must match ModelDownloadManager's storage root so an existing E2B file is detected. */
    private val modelStorageRootProvider: () -> File = {
        context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
    }
) {
    private val prefs: SharedPreferences = context.getSharedPreferences("calendar_add_prefs", Context.MODE_PRIVATE)

    /**
     * Coordinates model-selection writes with
     * [com.calendaradd.service.ModelDownloadManager.cleanupUnusedModelFiles]. Cleanup acquires
     * this lock for its whole read-selection-then-delete critical section, and the
     * [selectedModelId] setter acquires the same lock to write, so a selection change cannot
     * land between cleanup reading the selection and deleting unused files. Closes the A3
     * startup-cleanup TOCTOU. Held only for ms-scale work, so the UI-thread setter stalls at
     * most one frame.
     */
    val modelSelectionLock: ReentrantLock = ReentrantLock()

    companion object {
        private const val KEY_AUTO_ADD = "auto_add_to_calendar"
        private const val KEY_TARGET_CALENDAR_ID = "target_calendar_id"
        private const val KEY_TARGET_CALENDAR_NAME = "target_calendar_name"
        private const val KEY_SELECTED_MODEL_ID = "selected_model_id"
        private const val KEY_HEAVY_ANALYSIS = "heavy_analysis"
        private const val KEY_DEFAULT_REMINDER_MINUTES = "default_reminder_minutes"
        private const val KEY_DEBUG_FAILURE_JSON = "debug_failure_json"
        private const val KEY_PENDING_DEBUG_FAILURE_NONCE = "pending_debug_failure_nonce"
        private const val KEY_PENDING_DEBUG_FAILURE_NONCES = "pending_debug_failure_nonces"
        private const val KEY_ACTIVE_MODEL_DOWNLOAD_ID = "active_model_download_id"
        private const val KEY_ACTIVE_MODEL_DOWNLOAD_MODEL_ID = "active_model_download_model_id"
        private const val KEY_PENDING_CALENDAR_MIGRATION_TO = "pending_calendar_migration_to"
        const val DEFAULT_REMINDER_MINUTES = 10
        private const val MAX_PENDING_DEBUG_FAILURE_NONCES = 8
        private const val DEBUG_FAILURE_NONCE_TTL_MS = 24 * 60 * 60 * 1000L
    }

    var isAutoAddEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_ADD, false)
        set(value) = prefs.edit { putBoolean(KEY_AUTO_ADD, value) }

    var targetCalendarId: Long
        get() = prefs.getLong(KEY_TARGET_CALENDAR_ID, -1L)
        set(value) = prefs.edit { putLong(KEY_TARGET_CALENDAR_ID, value) }

    var targetCalendarName: String?
        get() = prefs.getString(KEY_TARGET_CALENDAR_NAME, null)
        set(value) = prefs.edit { putString(KEY_TARGET_CALENDAR_NAME, value) }

    /**
     * The selected model id. When no valid choice is stored (first launch, or a removed
     * catalogue id), resolves one via [LiteRtModelCatalog.resolveSelectedModelId] — memory-aware
     * first selection, or Gemma 4 E2B when its file is already on disk — and persists it once so
     * later reads are stable.
     */
    var selectedModelId: String
        get() {
            val stored = prefs.getString(KEY_SELECTED_MODEL_ID, null)
            if (stored != null && LiteRtModelCatalog.models.any { it.id == stored }) return stored
            return modelSelectionLock.withLock {
                val current = prefs.getString(KEY_SELECTED_MODEL_ID, null)
                val resolved = LiteRtModelCatalog.resolveSelectedModelId(
                    storedId = current,
                    deviceMemoryGb = deviceMemoryGbProvider,
                    e2bFilePresent = {
                        val e2b = LiteRtModelCatalog.find(LiteRtModelCatalog.E2B_MODEL_ID)
                        runCatching { File(modelStorageRootProvider(), e2b.fileName).isFile }.getOrDefault(false)
                    }
                )
                if (resolved != current) {
                    prefs.edit { putString(KEY_SELECTED_MODEL_ID, resolved) }
                }
                resolved
            }
        }
        set(value) {
            modelSelectionLock.withLock {
                prefs.edit { putString(KEY_SELECTED_MODEL_ID, value) }
            }
        }

    var isHeavyAnalysisEnabled: Boolean
        get() = prefs.getBoolean(KEY_HEAVY_ANALYSIS, false)
        set(value) = prefs.edit { putBoolean(KEY_HEAVY_ANALYSIS, value) }

    var defaultReminderMinutes: Int
        get() = prefs.getInt(KEY_DEFAULT_REMINDER_MINUTES, DEFAULT_REMINDER_MINUTES).coerceAtLeast(0)
        set(value) = prefs.edit { putInt(KEY_DEFAULT_REMINDER_MINUTES, value.coerceAtLeast(0)) }

    var isFailureJsonDebugEnabled: Boolean
        get() = prefs.getBoolean(KEY_DEBUG_FAILURE_JSON, false)
        set(value) = prefs.edit { putBoolean(KEY_DEBUG_FAILURE_JSON, value) }

    fun createDebugFailureNonce(): String {
        val nonce = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val pending = readPendingDebugFailureNonces(now)
            .plus(nonce to now)
            .sortedByDescending { it.second }
            .take(MAX_PENDING_DEBUG_FAILURE_NONCES)
        prefs.edit {
            remove(KEY_PENDING_DEBUG_FAILURE_NONCE)
            putString(KEY_PENDING_DEBUG_FAILURE_NONCES, pending.encodePendingNonces())
        }
        return nonce
    }

    fun consumeDebugFailureNonce(nonce: String?): Boolean {
        if (nonce.isNullOrBlank()) return false
        val now = System.currentTimeMillis()
        val pending = readPendingDebugFailureNonces(now)
        val matched = pending.any { it.first == nonce }
        if (!matched) return false

        prefs.edit {
            remove(KEY_PENDING_DEBUG_FAILURE_NONCE)
            putString(
                KEY_PENDING_DEBUG_FAILURE_NONCES,
                pending.filterNot { it.first == nonce }.encodePendingNonces()
            )
        }
        return matched
    }

    private fun readPendingDebugFailureNonces(now: Long): List<Pair<String, Long>> {
        val fromSet = prefs.getString(KEY_PENDING_DEBUG_FAILURE_NONCES, null)
            .orEmpty()
            .lineSequence()
            .mapNotNull { encoded ->
                val nonce = encoded.substringBefore('|')
                val createdAt = encoded.substringAfter('|', "").toLongOrNull()
                if (nonce.isBlank() || createdAt == null) null else nonce to createdAt
            }
            .filter { (_, createdAt) -> now - createdAt <= DEBUG_FAILURE_NONCE_TTL_MS }
            .toList()

        val legacy = prefs.getString(KEY_PENDING_DEBUG_FAILURE_NONCE, null)
            ?.takeIf { it.isNotBlank() }
            ?.let { it to now }

        return (fromSet + listOfNotNull(legacy))
            .distinctBy { it.first }
            .sortedByDescending { it.second }
            .take(MAX_PENDING_DEBUG_FAILURE_NONCES)
    }

    private fun List<Pair<String, Long>>.encodePendingNonces(): String {
        return joinToString(separator = "\n") { (nonce, createdAt) -> "$nonce|$createdAt" }
    }

    /**
     * R6/AC16: calendar id that synced events must still be migrated to. Set when the target
     * calendar changes and cleared only after a fully successful migration pass, so a failed
     * move can be retried on the next launch without re-triggering a duplicate move.
     */
    var pendingCalendarMigrationTo: Long
        get() = prefs.getLong(KEY_PENDING_CALENDAR_MIGRATION_TO, -1L)
        set(value) = prefs.edit { putLong(KEY_PENDING_CALENDAR_MIGRATION_TO, value) }

    var activeModelDownloadId: Long
        get() = prefs.getLong(KEY_ACTIVE_MODEL_DOWNLOAD_ID, -1L)
        set(value) = prefs.edit { putLong(KEY_ACTIVE_MODEL_DOWNLOAD_ID, value) }

    var activeModelDownloadModelId: String?
        get() = prefs.getString(KEY_ACTIVE_MODEL_DOWNLOAD_MODEL_ID, null)
        set(value) = prefs.edit { putString(KEY_ACTIVE_MODEL_DOWNLOAD_MODEL_ID, value) }

    fun clearActiveModelDownload(downloadId: Long? = null) {
        if (downloadId != null && activeModelDownloadId != downloadId) return
        prefs.edit {
            remove(KEY_ACTIVE_MODEL_DOWNLOAD_ID)
            remove(KEY_ACTIVE_MODEL_DOWNLOAD_MODEL_ID)
        }
    }
}

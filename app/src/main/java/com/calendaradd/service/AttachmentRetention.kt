package com.calendaradd.service

import android.content.Context
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.calendaradd.usecase.BatchStatus
import com.calendaradd.usecase.EventDao
import com.calendaradd.util.AppLog
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * P11 (F12): bounds total queued/attached media under app storage to [QUOTA_BYTES] with
 * oldest-first eviction. Scans the same two directories [BackgroundAnalysisScheduler] writes
 * queued inputs (including shared images/audio, R1) and promoted event-source attachments into,
 * and never removes a file that is still meaningfully referenced (R2):
 *
 * - a batch journal row (P7) that is still PENDING (unfinished) protects its attachment forever;
 * - a batch journal row that reached COMMITTED protects its attachment forever too, since a real
 *   `events` row now points at it;
 * - any `events` row directly referencing the path protects it forever, independent of batch
 *   status — a retry reuses its batch key but promotes a fresh attachment path per attempt, so
 *   the journal row's path can lag behind the path a committed event actually references;
 * - a WorkManager job in ENQUEUED, RUNNING or BLOCKED state protects the input file its tag
 *   names, covering both an actively running worker and one still waiting on a model download;
 * - everything else — a ROLLED_BACK batch's attachment, or a file with no journal row at all
 *   (an orphaned queue input) — is only evicted once it is older than [GRACE_PERIOD_MS], oldest
 *   file first, and only until the total drops back under quota.
 *
 * Eviction only ever deletes the file plus (R3) marks the owning journal row via
 * [EventDao.markBatchAttachmentEvicted]; it never touches `events` rows or cascades into the
 * journal item rows.
 */
class AttachmentRetention(
    context: Context,
    private val eventDao: EventDao,
    private val workManager: WorkManager = WorkManager.getInstance(context.applicationContext),
    private val nowMillis: () -> Long = System::currentTimeMillis
) {
    companion object {
        private const val TAG = "AttachmentRetention"

        const val QUOTA_BYTES = 512L * 1024 * 1024
        const val GRACE_PERIOD_MS = 24 * 60 * 60 * 1000L

        // Mirrors BackgroundAnalysisScheduler's private directory/tag constants. They cannot be
        // imported (private to that file, which P11 is not allowed to modify), so the same
        // literal values are duplicated here; both name the same on-disk contract.
        private const val ANALYSIS_INPUT_DIR = "background-analysis-inputs"
        private const val EVENT_SOURCE_DIR = "event-source-files"
        private const val INPUT_TAG_PREFIX = "calendaradd-input:"

        private val ACTIVE_WORK_STATES = setOf(
            WorkInfo.State.ENQUEUED,
            WorkInfo.State.RUNNING,
            WorkInfo.State.BLOCKED
        )
    }

    private val appContext = context.applicationContext

    suspend fun enforceQuota(): AttachmentEvictionResult = withContext(Dispatchers.IO) {
        val candidates = collectCandidateFiles()
        val totalBefore = candidates.sumOf { it.length() }

        if (totalBefore <= QUOTA_BYTES) {
            return@withContext AttachmentEvictionResult(
                totalBytesBefore = totalBefore,
                totalBytesAfter = totalBefore,
                evictedPaths = emptyList(),
                bytesFreed = 0
            )
        }

        val activeInputPaths = activeWorkInputPaths()
        val now = nowMillis()
        var remainingBytes = totalBefore
        val evicted = mutableListOf<String>()
        var freed = 0L

        for (file in candidates.sortedBy { it.lastModified() }) {
            if (remainingBytes <= QUOTA_BYTES) break

            val path = file.absolutePath
            // Conservative: an unreadable WorkManager query means we cannot tell whether this
            // file is a live worker's input, so nothing is evicted this pass (R2).
            if (activeInputPaths == null || path in activeInputPaths) continue

            val matches = eventDao.findBatchesByAttachmentPath(path)
            val isPending = matches.any { it.status == BatchStatus.PENDING.name }
            val isCommitted = matches.any { it.status == BatchStatus.COMMITTED.name }
            // A2: a retry reuses the same batch key but promotes a fresh attachment path each
            // attempt, so the journal row's sourceAttachmentPath can point at an earlier
            // attempt's (already-gone) file while a committed `events` row references this
            // path directly. The batch-status check above cannot see that; a direct reference
            // check against `events` can.
            val hasLiveEventReference = eventDao.countEventsWithSourceAttachmentPath(path) > 0
            if (isPending || isCommitted || hasLiveEventReference) continue

            if (now - file.lastModified() < GRACE_PERIOD_MS) continue

            val size = file.length()
            if (!file.delete()) {
                AppLog.w(TAG, "Failed to evict attachment $path")
                continue
            }
            // A3: sourceAttachmentPath is not unique, so more than one rolled-back batch row
            // can share this now-deleted path; mark every one of them, not just the first.
            matches.filter { it.status == BatchStatus.ROLLED_BACK.name }.forEach { batch ->
                eventDao.markBatchAttachmentEvicted(batch.id)
            }
            evicted += path
            freed += size
            remainingBytes -= size
        }

        AttachmentEvictionResult(
            totalBytesBefore = totalBefore,
            totalBytesAfter = remainingBytes,
            evictedPaths = evicted,
            bytesFreed = freed
        )
    }

    private fun collectCandidateFiles(): List<File> {
        return (queueInputDir().listFiles().orEmpty().asSequence() + promotedAttachmentDir().listFiles().orEmpty().asSequence())
            .filter { it.isFile }
            .toList()
    }

    /**
     * Returns every input path referenced by a WorkManager job still in an active state
     * ([ACTIVE_WORK_STATES]), or null if the query could not be completed within the deadline —
     * callers must treat null as "unknown" and skip eviction, never as "nothing is active".
     */
    private fun activeWorkInputPaths(): Set<String>? {
        return try {
            val infos = workManager.getWorkInfosByTag(BackgroundAnalysisScheduler.WORK_TAG)
                .get(BackgroundAnalysisScheduler.WORK_MANAGER_DEADLINE_MS, TimeUnit.MILLISECONDS)
            infos.asSequence()
                .filter { it.state in ACTIVE_WORK_STATES }
                .flatMap { it.tags.asSequence() }
                .filter { it.startsWith(INPUT_TAG_PREFIX) }
                .map { it.removePrefix(INPUT_TAG_PREFIX) }
                .toSet()
        } catch (e: Exception) {
            AppLog.w(TAG, "Could not query active WorkManager jobs; skipping eviction pass", e)
            null
        }
    }

    private fun queueInputDir(): File = File(appContext.noBackupFilesDir, ANALYSIS_INPUT_DIR)

    private fun promotedAttachmentDir(): File = File(appContext.filesDir, EVENT_SOURCE_DIR)
}

/**
 * Outcome of one [AttachmentRetention.enforceQuota] pass.
 */
data class AttachmentEvictionResult(
    val totalBytesBefore: Long,
    val totalBytesAfter: Long,
    val evictedPaths: List<String>,
    val bytesFreed: Long
)

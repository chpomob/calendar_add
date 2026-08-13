package com.calendaradd.usecase

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * DAO for Event entity operations.
 */
@Dao
interface EventDao {

    // CRUD Operations
    @Query("SELECT * FROM events ORDER BY createdAt DESC")
    fun getAllEvents(): Flow<List<Event>>

    @Query("SELECT * FROM events ORDER BY createdAt DESC")
    suspend fun getAllEventsOnce(): List<Event>

    @Query("SELECT * FROM events WHERE id = :eventId")
    fun getEventById(eventId: Long): Flow<Event?>

    @Query("SELECT * FROM events WHERE id = :eventId")
    suspend fun getEventByIdOnce(eventId: Long): Event?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: Event): Long

    @Query("DELETE FROM events WHERE id = :eventId")
    suspend fun deleteEvent(eventId: Long)

    @Update
    suspend fun update(event: Event)

    // Utility Queries
    @Query("SELECT * FROM events WHERE startTime >= :now ORDER BY startTime ASC")
    fun getUpcomingEvents(now: Long = System.currentTimeMillis()): Flow<List<Event>>

    @Query("SELECT * FROM events WHERE title LIKE '%' || :search || '%' OR description LIKE '%' || :search || '%'")
    suspend fun searchEvents(search: String): List<Event>

    @Query("SELECT COUNT(*) FROM events WHERE sourceAttachmentPath = :path")
    suspend fun countEventsWithSourceAttachmentPath(path: String): Int

    @Query("DELETE FROM events")
    suspend fun deleteAll()

    // ---- Extraction batch journal (durable idempotent batch primitives) ----

    @Query("SELECT * FROM extraction_batches WHERE batchKey = :batchKey")
    suspend fun findBatchByKey(batchKey: String): ExtractionBatchEntity?

    @Query("SELECT COUNT(*) FROM extraction_batches WHERE batchKey = :batchKey")
    suspend fun countBatchesForKey(batchKey: String): Int

    @Query("SELECT * FROM extraction_batches WHERE id = :batchId")
    suspend fun getBatchById(batchId: Long): ExtractionBatchEntity?

    @Query("SELECT * FROM extraction_batches WHERE status NOT IN ('COMMITTED', 'ROLLED_BACK') ORDER BY createdAt ASC")
    suspend fun getUnfinishedBatches(): List<ExtractionBatchEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertBatchIgnore(batch: ExtractionBatchEntity): Long

    /**
     * Idempotently creates (or reuses) the journal row for [batchKey]. A retry or restart
     * that supplies the same deterministic key resolves to the same journal row rather than
     * inserting a duplicate.
     */
    @Transaction
    suspend fun getOrCreateBatch(
        batchKey: String,
        sourceType: String = "manual",
        sourceAttachmentPath: String = "",
        sourceAttachmentMimeType: String = "",
        sourceAttachmentName: String = ""
    ): ExtractionBatchEntity {
        findBatchByKey(batchKey)?.let { return it }
        insertBatchIgnore(
            ExtractionBatchEntity(
                batchKey = batchKey,
                sourceType = sourceType,
                sourceAttachmentPath = sourceAttachmentPath,
                sourceAttachmentMimeType = sourceAttachmentMimeType,
                sourceAttachmentName = sourceAttachmentName,
                status = BatchStatus.PENDING.name,
                createdAt = System.currentTimeMillis(),
                completedAt = null
            )
        )
        return requireNotNull(findBatchByKey(batchKey)) {
            "Batch journal row missing immediately after idempotent insert for key $batchKey"
        }
    }

    @Query("SELECT * FROM extraction_batch_items WHERE batchId = :batchId AND ordinal = :ordinal")
    suspend fun findBatchItem(batchId: Long, ordinal: Int): ExtractionBatchItemEntity?

    @Query("SELECT * FROM extraction_batch_items WHERE batchId = :batchId ORDER BY ordinal ASC")
    suspend fun getBatchItems(batchId: Long): List<ExtractionBatchItemEntity>

    @Insert
    suspend fun insertEventForJournal(event: Event): Long

    @Insert
    suspend fun insertBatchItem(item: ExtractionBatchItemEntity): Long

    /**
     * Commits every item in [items] plus its local `events` row inside one Room
     * transaction: either all event rows and all journal item rows land, or (on any
     * failure, e.g. a constraint violation) none of them do. Items whose (batchId, ordinal)
     * already has a journal row are left untouched and their existing row is reused, so
     * replaying the same deterministic batch never duplicates rows.
     *
     * A batch that already reached a terminal status (COMMITTED or ROLLED_BACK) is immutable:
     * previously journaled ordinals still replay from their existing row, but an ordinal that
     * was never journaled is rejected rather than silently inserted, since that would reopen
     * an outcome the journal already considers final.
     */
    @Transaction
    suspend fun commitBatchItems(
        batchId: Long,
        items: List<PendingBatchItem>
    ): List<ExtractionBatchItemEntity> {
        val batch = getBatchById(batchId)
        val isTerminal = batch != null &&
            (batch.status == BatchStatus.COMMITTED.name || batch.status == BatchStatus.ROLLED_BACK.name)
        val committed = mutableListOf<ExtractionBatchItemEntity>()
        for (item in items) {
            val existing = findBatchItem(batchId, item.ordinal)
            if (existing != null) {
                committed += existing
                continue
            }
            check(!isTerminal) {
                "Batch $batchId is already ${batch?.status}; refusing to journal new ordinal ${item.ordinal}"
            }
            val eventId = insertEventForJournal(item.event)
            val row = ExtractionBatchItemEntity(
                batchId = batchId,
                ordinal = item.ordinal,
                eventId = eventId,
                destination = item.destination.name,
                syncState = BatchSyncState.LOCAL_COMMITTED.name,
                providerId = null,
                recoveryStatus = BatchRecoveryStatus.PENDING.name,
                createdAt = System.currentTimeMillis()
            )
            val itemId = insertBatchItem(row)
            committed += row.copy(id = itemId)
        }
        return committed
    }

    @Query(
        "UPDATE extraction_batch_items SET providerId = :providerId, syncState = :syncState, " +
            "recoveryStatus = :recoveryStatus WHERE id = :itemId"
    )
    suspend fun recordProviderOutcome(
        itemId: Long,
        providerId: Long?,
        syncState: String,
        recoveryStatus: String
    )

    @Query("UPDATE extraction_batches SET status = :status, completedAt = :completedAt WHERE id = :batchId")
    suspend fun setBatchStatus(batchId: Long, status: String, completedAt: Long?)

    /**
     * Idempotent: marking an already-completed batch complete again is a no-op. A batch that
     * already reached the opposite terminal status (ROLLED_BACK) is left untouched too — a
     * terminal outcome is final and must never be reversed by a late or repeated call.
     */
    @Transaction
    suspend fun completeBatch(batchId: Long) {
        val batch = getBatchById(batchId) ?: return
        if (batch.status == BatchStatus.COMMITTED.name || batch.status == BatchStatus.ROLLED_BACK.name) return
        setBatchStatus(batchId, BatchStatus.COMMITTED.name, System.currentTimeMillis())
    }

    /**
     * Idempotent: rolling back an already-rolled-back batch again is a no-op. A batch that
     * already reached the opposite terminal status (COMMITTED) is left untouched too — a
     * terminal outcome is final and must never be reversed by a late or repeated call.
     */
    @Transaction
    suspend fun rollbackBatch(batchId: Long) {
        val batch = getBatchById(batchId) ?: return
        if (batch.status == BatchStatus.COMMITTED.name || batch.status == BatchStatus.ROLLED_BACK.name) return
        setBatchStatus(batchId, BatchStatus.ROLLED_BACK.name, System.currentTimeMillis())
    }

    /**
     * Deletes the journal rows for [batchId] (batch + its items). The `eventId` foreign key
     * uses SET_NULL, and there is no foreign key from `events` to the batch tables, so this
     * never deletes user event rows.
     */
    @Query("DELETE FROM extraction_batches WHERE id = :batchId")
    suspend fun deleteBatchJournal(batchId: Long)

    // ---- P11: attachment retention (oldest-first eviction of queued media) ----

    /**
     * Looks up every batch journal row still pointing at [path] as its source attachment.
     * [AttachmentRetention] uses this to decide whether a queued/promoted media file on disk
     * is a live reference (PENDING or COMMITTED batch — never evict) or safe to reclaim
     * (ROLLED_BACK, past its grace period) versus a plain orphan (no row at all).
     */
    @Query("SELECT * FROM extraction_batches WHERE sourceAttachmentPath = :path")
    suspend fun findBatchesByAttachmentPath(path: String): List<ExtractionBatchEntity>

    /**
     * Marks a batch's source attachment as evicted by clearing [ExtractionBatchEntity.sourceAttachmentPath]
     * (no schema change needed: an empty path is otherwise never written by [getOrCreateBatch]
     * for a real attachment). This only ever runs against a terminal (ROLLED_BACK) batch whose
     * file [AttachmentRetention] already deleted, so it is pure bookkeeping — the row itself,
     * its items, and any `events` rows are left untouched (R3: mark, never cascade).
     */
    @Query("UPDATE extraction_batches SET sourceAttachmentPath = '' WHERE id = :batchId")
    suspend fun markBatchAttachmentEvicted(batchId: Long)
}

/**
 * One pending item to persist via [EventDao.commitBatchItems]: the event payload, its
 * ordinal within the batch, and its intended destination.
 */
data class PendingBatchItem(
    val ordinal: Int,
    val event: Event,
    val destination: BatchDestination = BatchDestination.LOCAL_ONLY
)

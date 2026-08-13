package com.calendaradd.usecase

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Lifecycle of an extraction batch journal row.
 */
enum class BatchStatus {
    PENDING,
    COMMITTED,
    ROLLED_BACK
}

/**
 * Where a batch item is meant to land: local Room storage only, or local plus the
 * Android system calendar. P8A owns the actual system-calendar marker behavior; this
 * column only records intent so that layer has somewhere to read/write it.
 */
enum class BatchDestination {
    LOCAL_ONLY,
    LOCAL_AND_SYSTEM
}

/**
 * Sync state of a single batch item against its destination(s).
 */
enum class BatchSyncState {
    PENDING,
    LOCAL_COMMITTED,
    PROVIDER_COMMITTED,
    PROVIDER_FAILED,
    SKIPPED
}

/**
 * Recovery status of a batch item, used to enumerate/replay unfinished work after restart.
 */
enum class BatchRecoveryStatus {
    PENDING,
    COMMITTED,
    ROLLED_BACK
}

/**
 * Durable journal row for one extraction batch (one call to save-and-sync a set of
 * extracted events). [batchKey] is a caller-supplied deterministic identity derived from
 * the source input (not from a timestamp), so retries and restarts of the same logical
 * batch resolve to the same journal row instead of creating duplicates.
 */
@Entity(
    tableName = "extraction_batches",
    indices = [
        Index(value = ["batchKey"], unique = true)
    ]
)
data class ExtractionBatchEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val batchKey: String,

    val sourceType: String,
    val sourceAttachmentPath: String,
    val sourceAttachmentMimeType: String,
    val sourceAttachmentName: String,

    val status: String,

    val createdAt: Long,
    val completedAt: Long? = null
)

/**
 * Durable journal row for one item (one extracted event) within an [ExtractionBatchEntity].
 * [batchId] + [ordinal] form the deterministic identity for the item: reprocessing the same
 * batch with the same item ordering reuses these rows instead of duplicating them.
 *
 * [eventId] references the local `events` row this item produced, once committed. The
 * foreign key back to `events` uses SET_NULL on delete (not CASCADE) so that deleting a
 * user event never touches this journal, and deleting/cleaning up a journal batch never
 * cascades into user event rows.
 */
@Entity(
    tableName = "extraction_batch_items",
    indices = [
        Index(value = ["batchId", "ordinal"], unique = true),
        Index(value = ["eventId"])
    ],
    foreignKeys = [
        ForeignKey(
            entity = ExtractionBatchEntity::class,
            parentColumns = ["id"],
            childColumns = ["batchId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = Event::class,
            parentColumns = ["id"],
            childColumns = ["eventId"],
            onDelete = ForeignKey.SET_NULL
        )
    ]
)
data class ExtractionBatchItemEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val batchId: Long,
    val ordinal: Int,

    val eventId: Long? = null,

    val destination: String,
    val syncState: String,
    val providerId: Long? = null,
    val recoveryStatus: String,

    val createdAt: Long
)

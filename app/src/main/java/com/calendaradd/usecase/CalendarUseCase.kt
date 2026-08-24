package com.calendaradd.usecase

import android.graphics.Bitmap
import com.calendaradd.service.*
import com.calendaradd.util.AppLog
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeParseException
import java.util.Locale
import kotlinx.coroutines.CancellationException

/**
 * Use case for creating calendar events from various inputs.
 */
class CalendarUseCase(
    private val textAnalysisService: TextAnalysisService,
    private val eventDatabase: EventDatabase,
    private val systemCalendarService: SystemCalendarService,
    private val preferencesManager: PreferencesManager
) {
    companion object {
        private const val TAG = "CalendarUseCase"

        /**
         * R1: hard cap on how many events a single extraction may persist. Applied before any
         * Room insert or system-calendar call, over the model's own output order (first
         * [MAX_EVENTS_PER_EXTRACTION] kept), so a runaway extraction can't flood local storage
         * or the system calendar.
         */
        const val MAX_EVENTS_PER_EXTRACTION = 20
    }

    suspend fun createEventFromText(
        input: String,
        context: InputContext = InputContext()
    ): EventResult {
        return try {
            AppLog.i(TAG, "[${context.traceId}] createEventFromText chars=${input.length}")
            val analyses = textAnalysisService.analyzeText(input, context)
            saveAndSyncExtractions(analyses, "text", context)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.e(TAG, "[${context.traceId}] Text event creation failed", e)
            EventResult.Failure(e.message ?: "Unknown error")
        }
    }

    suspend fun createEventFromImage(
        bitmap: Bitmap,
        context: InputContext = InputContext(),
        sourceAttachment: SourceAttachment? = null
    ): EventResult {
        return try {
            AppLog.i(TAG, "[${context.traceId}] createEventFromImage bitmap=${bitmap.width}x${bitmap.height}")
            val analyses = textAnalysisService.analyzeImage(bitmap, context)
            saveAndSyncExtractions(analyses, "image", context, sourceAttachment)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.e(TAG, "[${context.traceId}] Image event creation failed", e)
            EventResult.Failure(e.message ?: "Unknown error")
        }
    }

    suspend fun createEventFromAudio(
        audioData: ByteArray,
        context: InputContext = InputContext(),
        sourceAttachment: SourceAttachment? = null
    ): EventResult {
        return try {
            AppLog.i(TAG, "[${context.traceId}] createEventFromAudio bytes=${audioData.size}")
            val analyses = textAnalysisService.analyzeAudio(audioData, context)
            saveAndSyncExtractions(analyses, "audio", context, sourceAttachment)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.e(TAG, "[${context.traceId}] Audio event creation failed", e)
            EventResult.Failure(e.message ?: "Unknown error")
        }
    }

    private suspend fun saveAndSyncExtractions(
        analyses: List<EventExtraction>,
        sourceType: String,
        context: InputContext,
        sourceAttachment: SourceAttachment? = null
    ): EventResult {
        val validAnalyses = analyses.filter { it.hasMeaningfulContent() }
        if (validAnalyses.isEmpty()) {
            AppLog.w(TAG, "[${context.traceId}] No valid events extracted from $sourceType input")
            return EventResult.Failure(
                message = "Could not extract enough event details from the $sourceType input.",
                debug = buildFailureDebug(
                    message = "No usable event details were extracted from the $sourceType input.",
                    sourceType = sourceType,
                    context = context
                )
            )
        }

        // R1: cap applied before any Room insert or system-calendar call, over the model's own
        // (deterministic) output order, so replaying the same extraction always keeps the same
        // first MAX_EVENTS_PER_EXTRACTION events in the same order (R3).
        val cappedAnalyses = validAnalyses.take(MAX_EVENTS_PER_EXTRACTION)
        val droppedCount = validAnalyses.size - cappedAnalyses.size
        if (droppedCount > 0) {
            AppLog.w(
                TAG,
                "[${context.traceId}] Extraction produced ${validAnalyses.size} events; capping to " +
                    "$MAX_EVENTS_PER_EXTRACTION and dropping $droppedCount source=$sourceType"
            )
        }

        val dao = eventDatabase.eventDao()
        // R3: derived from context.requestId (stable per logical request; see
        // InputContext.requestId) plus the capped extraction payload, never from
        // context.timestamp/traceId, so the same logical extraction replayed by a worker retry
        // resolves to the same journal row. requestId — not sourceAttachment.path, which
        // BackgroundAnalysisWorker recreates via a fresh File.createTempFile on every retry
        // attempt (A1) — is what makes that stable across retries, and it's also what keeps two
        // independent interactive requests with identical extracted content from colliding into
        // the same batch (A2).
        val batchKey = extractionBatchKey(context.requestId, sourceType, sourceAttachment, cappedAnalyses)

        val calendars = if (preferencesManager.isAutoAddEnabled && systemCalendarService.hasCalendarPermissions()) {
            systemCalendarService.getAvailableCalendars()
        } else {
            emptyList()
        }
        val preferredCalendarId = preferencesManager.targetCalendarId
            .takeIf { id -> id != -1L && calendars.any { it.id == id } }
            ?: calendars.find { it.isPrimary }?.id
            ?: calendars.firstOrNull()?.id

        return try {
            val batch = dao.getOrCreateBatch(
                batchKey = batchKey,
                sourceType = sourceType,
                sourceAttachmentPath = sourceAttachment?.path.orEmpty(),
                sourceAttachmentMimeType = sourceAttachment?.mimeType.orEmpty(),
                sourceAttachmentName = sourceAttachment?.displayName.orEmpty()
            )

            if (batch.status == BatchStatus.ROLLED_BACK.name) {
                AppLog.w(TAG, "[${context.traceId}] Batch id=${batch.id} already rolled back; refusing to retry source=$sourceType")
                return EventResult.Failure(
                    message = "Could not save extracted events for the $sourceType input.",
                    debug = buildFailureDebug(
                        message = "The extraction batch journal already recorded this batch as rolled back.",
                        sourceType = sourceType,
                        context = context
                    )
                )
            }

            val pendingItems = buildPendingItems(cappedAnalyses, sourceType, context, sourceAttachment, preferredCalendarId)
            if (pendingItems.isEmpty()) {
                AppLog.w(TAG, "[${context.traceId}] No extracted events had a valid start time source=$sourceType")
                // A4: nothing was ever (or will ever be) journaled under this batchKey — this
                // batch is a permanent dead end, not an in-progress one. Delete the PENDING row
                // rather than leaving it in getUnfinishedBatches forever.
                deleteEmptyBatchQuietly(dao, batch.id, context, sourceType)
                return EventResult.Failure(
                    message = "Could not extract a valid event date and time from the $sourceType input.",
                    debug = buildFailureDebug(
                        message = "The model response did not contain any event with a parseable absolute start time.",
                        sourceType = sourceType,
                        context = context
                    )
                )
            }

            AppLog.i(
                TAG,
                "[${context.traceId}] Saving ${pendingItems.size} extracted event(s) source=$sourceType autoSync=${preferredCalendarId != null}"
            )

            // R2: every event row plus its journal item lands in one Room transaction (P7's
            // commitBatchItems). A failure here leaves zero rows for this batch and the batch
            // row stays PENDING, so a later call with the same deterministic batchKey resumes
            // cleanly (idempotent per-ordinal reuse) instead of duplicating work.
            val committedItems = dao.commitBatchItems(batch.id, pendingItems)

            // Provider sync + outcome bookkeeping happen after local persistence has already
            // committed. That part is best-effort only (P8A owns the system-calendar provider
            // itself): a sync or journal-update failure here is logged and skipped rather than
            // unwinding the already-committed local event.
            val savedEvents = syncCommittedItems(dao, committedItems, preferredCalendarId, context, sourceType)

            // A3: the local event rows (and any provider outcomes) are already committed by
            // this point. Marking the batch COMMITTED is bookkeeping on top of that durable
            // state, not a precondition for it — a failure here must not turn an already-saved
            // extraction into a reported failure that invites a duplicate-causing retry.
            try {
                dao.completeBatch(batch.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.w(TAG, "[${context.traceId}] Failed to mark batch id=${batch.id} complete; events already committed source=$sourceType", e)
            }

            if (savedEvents.isEmpty()) {
                AppLog.w(TAG, "[${context.traceId}] Batch id=${batch.id} committed with no readable events source=$sourceType")
                return EventResult.Failure(
                    message = "Could not extract a valid event date and time from the $sourceType input.",
                    debug = buildFailureDebug(
                        message = "The model response did not contain any event with a parseable absolute start time.",
                        sourceType = sourceType,
                        context = context
                    )
                )
            }

            AppLog.i(TAG, "[${context.traceId}] Saved ${savedEvents.size} event(s) source=$sourceType")
            EventResult.Success(savedEvents)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.e(TAG, "[${context.traceId}] Failed to persist extracted events source=$sourceType", e)
            EventResult.Failure(
                message = "Database error: ${e.message}",
                debug = buildFailureDebug(
                    message = "Persistence failed after model extraction.",
                    sourceType = sourceType,
                    context = context
                )
            )
        }
    }

    private fun buildPendingItems(
        cappedAnalyses: List<EventExtraction>,
        sourceType: String,
        context: InputContext,
        sourceAttachment: SourceAttachment?,
        preferredCalendarId: Long?
    ): List<PendingBatchItem> {
        val destination = if (preferredCalendarId != null) {
            BatchDestination.LOCAL_AND_SYSTEM
        } else {
            BatchDestination.LOCAL_ONLY
        }
        val items = mutableListOf<PendingBatchItem>()
        cappedAnalyses.forEachIndexed { ordinal, analysis ->
            val event = analysis.toEventOrNull(sourceType, context, sourceAttachment, ::parseIso8601)
            if (event == null) {
                AppLog.w(
                    TAG,
                    "[${context.traceId}] Skipping extracted event without parseable start time title=${analysis.title.ifBlank { "<untitled>" }}"
                )
                return@forEachIndexed
            }
            items += PendingBatchItem(ordinal = ordinal, event = event, destination = destination)
        }
        return items
    }

    private suspend fun syncCommittedItems(
        dao: EventDao,
        committedItems: List<ExtractionBatchItemEntity>,
        preferredCalendarId: Long?,
        context: InputContext,
        sourceType: String
    ): List<Event> {
        return committedItems.sortedBy { it.ordinal }.mapNotNull { item ->
            val eventId = item.eventId ?: return@mapNotNull null
            val event = dao.getEventByIdOnce(eventId) ?: return@mapNotNull null

            if (preferredCalendarId == null) return@mapNotNull event
            if (item.syncState == BatchSyncState.PROVIDER_COMMITTED.name && item.providerId != null) {
                return@mapNotNull event.copy(systemCalendarEventId = item.providerId)
            }

            val systemCalendarEventId = try {
                syncEventToSystem(event, preferredCalendarId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.w(TAG, "[${context.traceId}] System calendar sync failed for event id=$eventId source=$sourceType", e)
                null
            }

            try {
                dao.recordProviderOutcome(
                    itemId = item.id,
                    providerId = systemCalendarEventId,
                    syncState = if (systemCalendarEventId != null) {
                        BatchSyncState.PROVIDER_COMMITTED.name
                    } else {
                        BatchSyncState.PROVIDER_FAILED.name
                    },
                    recoveryStatus = BatchRecoveryStatus.COMMITTED.name
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.w(TAG, "[${context.traceId}] Failed to record provider outcome for item id=${item.id} source=$sourceType", e)
            }

            event.copy(systemCalendarEventId = systemCalendarEventId)
        }
    }

    /**
     * A4: best-effort cleanup of a PENDING batch row that will never receive any journaled
     * items (buildPendingItems returned empty for this batchKey, and batchKey is deterministic
     * over that same content, so it never will). Failure here is logged and swallowed — the
     * batch row would simply persist as an ignorable empty PENDING row, which is the pre-fix
     * behavior, not a data-loss risk.
     */
    private suspend fun deleteEmptyBatchQuietly(
        dao: EventDao,
        batchId: Long,
        context: InputContext,
        sourceType: String
    ) {
        try {
            dao.deleteBatchJournal(batchId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.w(TAG, "[${context.traceId}] Failed to delete empty batch journal id=$batchId source=$sourceType", e)
        }
    }

    /**
     * SHA-256 digest over the request's stable identity ([InputContext.requestId]) and the
     * capped extraction payload (never [InputContext.timestamp]/[InputContext.traceId]) so the
     * same logical extraction always resolves to the same journal row, regardless of when or how
     * many times it's replayed. [SourceAttachment.path] is deliberately excluded: for
     * image/audio it is a fresh temp-file path minted per attempt and is not stable across
     * worker retries (A1); mimeType/displayName are stable and kept for extra collision safety.
     */
    private fun extractionBatchKey(
        requestId: String,
        sourceType: String,
        sourceAttachment: SourceAttachment?,
        cappedAnalyses: List<EventExtraction>
    ): String {
        val builder = StringBuilder()
        builder.append(requestId).append('')
        builder.append(sourceType).append('')
        builder.append(sourceAttachment?.mimeType.orEmpty()).append('')
        builder.append(sourceAttachment?.displayName.orEmpty())
        cappedAnalyses.forEach { analysis ->
            builder.append('')
            builder.append(analysis.title).append('')
            builder.append(analysis.description).append('')
            builder.append(analysis.startTime).append('')
            builder.append(analysis.endTime).append('')
            builder.append(analysis.location).append('')
            builder.append(analysis.attendees.joinToString(",")).append('')
            builder.append(analysis.confidence?.toString().orEmpty())
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(builder.toString().toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun buildFailureDebug(
        message: String,
        sourceType: String,
        context: InputContext
    ): AnalysisFailureDebug? {
        if (!preferencesManager.isFailureJsonDebugEnabled) {
            textAnalysisService.consumeLastDebugSnapshot()
            return null
        }

        val snapshot = textAnalysisService.consumeLastDebugSnapshot()
        val response = snapshot?.cleanedResponse
            ?.takeIf { it.isNotBlank() }
            ?: snapshot?.rawResponse?.takeIf { it.isNotBlank() }
            ?: "<no model response available>"

        val body = buildString {
            appendLine("Source: $sourceType")
            appendLine("Trace: ${snapshot?.traceId ?: context.traceId}")
            appendLine("Failure: $message")
            snapshot?.issue?.let { issue ->
                appendLine("Model issue: $issue")
            }
            appendLine()
            appendLine("Model response:")
            append(response)
        }.trim().take(12_000)

        return AnalysisFailureDebug(
            title = "Failure Debug JSON",
            body = body
        )
    }

    suspend fun syncEventToSystem(event: Event, calendarId: Long): Long? {
        val attendees = parseAttendees(event.attendees)
        event.systemCalendarEventId?.let { existingSystemEventId ->
            val updateResult = systemCalendarService.updateEvent(
                systemEventId = existingSystemEventId,
                calendarId = calendarId,
                title = event.title,
                description = event.description,
                startTimeMillis = event.startTime,
                endTimeMillis = event.endTime,
                location = event.location,
                attendees = attendees
            )
            when (updateResult) {
                SystemCalendarUpdateResult.UPDATED -> return existingSystemEventId
                SystemCalendarUpdateResult.ERROR -> {
                    AppLog.w(TAG, "System calendar update failed for id=$existingSystemEventId; keeping local link")
                    return null
                }
                SystemCalendarUpdateResult.MISSING -> Unit
            }
            if (event.id != 0L) {
                AppLog.w(TAG, "Clearing stale system calendar id=$existingSystemEventId for local event id=${event.id}")
                eventDatabase.eventDao().update(event.copy(systemCalendarEventId = null))
            }
        }

        // R3 crash-adopt: if a previous attempt already inserted a provider row for this local
        // event but died before persisting the link, adopt that row instead of inserting a
        // duplicate. Only keyed when a local id exists (persisted event) — that id is what the
        // DESCRIPTION marker embeds.
        if (event.id != 0L) {
            when (val lookup = systemCalendarService.findEventBySyncMarker(calendarId = calendarId, localEventId = event.id)) {
                is SyncMarkerLookup.Found -> {
                    val adoptedId = lookup.systemEventId
                    AppLog.i(TAG, "Adopting orphaned provider event id=$adoptedId for local event id=${event.id}")
                    // Persist the adopted link first, then reconcile child rows below. If a crash
                    // hits between the two, the id is already durable so the next sync takes the
                    // normal update path and reconciles there.
                    eventDatabase.eventDao().update(event.copy(systemCalendarEventId = adoptedId))
                    // A3: the orphan may have been inserted without its child rows (crash between
                    // insertEvent and writeChildRows). Reconcile them now so AC8/AC12 hold on the
                    // retry. Best-effort: the adopted id is already correct, so a failed reconcile
                    // is logged and the next edit re-runs the normal update path.
                    val reconciled = try {
                        systemCalendarService.updateEvent(
                            systemEventId = adoptedId,
                            calendarId = calendarId,
                            title = event.title,
                            description = event.description,
                            startTimeMillis = event.startTime,
                            endTimeMillis = event.endTime,
                            location = event.location,
                            attendees = attendees
                        ) == SystemCalendarUpdateResult.UPDATED
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        AppLog.w(TAG, "Reconcile of adopted event id=$adoptedId failed; next update will retry", e)
                        false
                    }
                    if (!reconciled) {
                        AppLog.w(TAG, "Adopted event id=$adoptedId left unreconciled")
                    }
                    return adoptedId
                }
                SyncMarkerLookup.NotFound -> Unit // confirmed absence — safe to insert below
                SyncMarkerLookup.LookupError -> {
                    // R3: absence is unconfirmed; an orphan may exist. Inserting now could
                    // duplicate it, so fail this sync and let the retry re-run the lookup.
                    AppLog.w(TAG, "Sync marker lookup failed for local event id=${event.id}; skipping insert to avoid a duplicate")
                    return null
                }
            }
        }

        val providerDescription = if (event.id != 0L) {
            event.description + SystemCalendarService.syncMarker(event.id)
        } else {
            event.description
        }

        val newSystemEventId = systemCalendarService.insertEvent(
            calendarId = calendarId,
            title = event.title,
            description = providerDescription,
            startTimeMillis = event.startTime,
            endTimeMillis = event.endTime,
            location = event.location,
            attendees = attendees
        ) ?: return null

        if (event.id != 0L && event.systemCalendarEventId != newSystemEventId) {
            eventDatabase.eventDao().update(event.copy(systemCalendarEventId = newSystemEventId))
        }

        return newSystemEventId
    }

    suspend fun updateEvent(event: Event): EventUpdateResult {
        return try {
            val updatedEvent = event.copy(updatedAt = System.currentTimeMillis())
            eventDatabase.eventDao().update(updatedEvent)

            if (!preferencesManager.isAutoAddEnabled) {
                return EventUpdateResult.Success(updatedEvent, syncedToSystem = false)
            }

            if (!systemCalendarService.hasCalendarPermissions()) {
                return EventUpdateResult.Success(
                    event = updatedEvent,
                    syncedToSystem = false,
                    warning = "Saved locally. Calendar permission is required to update the system calendar."
                )
            }

            val calendars = systemCalendarService.getAvailableCalendars()
            val preferredCalendarId = preferencesManager.targetCalendarId
                .takeIf { id -> id != -1L && calendars.any { it.id == id } }
                ?: calendars.find { it.isPrimary }?.id
                ?: calendars.firstOrNull()?.id

            if (preferredCalendarId == null) {
                return EventUpdateResult.Success(
                    event = updatedEvent,
                    syncedToSystem = false,
                    warning = "Saved locally. No system calendar was found for auto-sync."
                )
            }

            val systemEventId = syncEventToSystem(updatedEvent, preferredCalendarId)
                ?: return EventUpdateResult.Success(
                    event = updatedEvent,
                    syncedToSystem = false,
                    warning = "Saved locally. System calendar update failed."
                )

            val syncedEvent = updatedEvent.copy(systemCalendarEventId = systemEventId)
            EventUpdateResult.Success(syncedEvent, syncedToSystem = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.e(TAG, "Failed to update event id=${event.id}", e)
            EventUpdateResult.Failure("Could not save event changes: ${e.message ?: e::class.java.simpleName}")
        }
    }

    /**
     * Robust ISO-8601 and common date format parser.
     */
    private fun parseIso8601(dateString: String?, zoneId: ZoneId = ZoneId.systemDefault()): Long? {
        if (dateString.isNullOrBlank()) return null
        val cleaned = dateString.trim()
        
        // Try various formats from most specific to least
        val parsers = listOf<() -> Long?>(
            { Instant.parse(cleaned).toEpochMilli() },
            { OffsetDateTime.parse(cleaned).toInstant().toEpochMilli() },
            { LocalDateTime.parse(cleaned).atZone(zoneId).toInstant().toEpochMilli() },
            { LocalDate.parse(cleaned).atStartOfDay(zoneId).toInstant().toEpochMilli() },
            // Handle common AI shorthand if it leaks through
            { 
                if (cleaned.length == 10 && cleaned.count { it == '-' } == 2) {
                    LocalDate.parse(cleaned).atStartOfDay(zoneId).toInstant().toEpochMilli()
                } else null
            }
        )

        for (parser in parsers) {
            try {
                return parser()
            } catch (e: Exception) {
                // Continue to next parser
            }
        }
        return null
    }

    fun getAllEvents() = eventDatabase.eventDao().getAllEvents()

    fun getEventByIdFlow(id: Long) = eventDatabase.eventDao().getEventById(id)

    suspend fun deleteEvent(id: Long) {
        val dao = eventDatabase.eventDao()
        val event = dao.getEventByIdOnce(id)
        dao.deleteEvent(id)

        // Drop the matching CalendarProvider row so an event removed locally never
        // lingers as an orphan in the system calendar. Failures (missing permission,
        // already deleted) are logged inside SystemCalendarService; nothing to recover.
        event?.systemCalendarEventId?.let { systemEventId ->
            systemCalendarService.deleteEvent(systemEventId)
        }

        val sourcePath = event?.sourceAttachmentPath?.takeIf { it.isNotBlank() } ?: return
        if (dao.countEventsWithSourceAttachmentPath(sourcePath) == 0) {
            File(sourcePath).delete()
        }
    }

    suspend fun getAvailableCalendars() = systemCalendarService.getAvailableCalendars()

    /**
     * R6b/F08: outcome of one migration pass. [failedCount] > 0 or a non-null [error] means
     * the pass is not complete — [PreferencesManager.pendingCalendarMigrationTo] stays set
     * so the next launch/selection retries it.
     */
    data class MigrationResult(
        val movedCount: Int,
        val failedCount: Int,
        val error: String? = null
    ) {
        val allMoved: Boolean get() = failedCount == 0 && error == null
    }

    /**
     * R6: moves every synced local event (a row with a linked provider event) to
     * [toCalendarId]. Returns the per-pass failure counts so the caller can surface an error
     * state and retry. A per-event failure is recorded by counting it in
     * [MigrationResult.failedCount] while the event's existing link is left intact (AC10b),
     * so the migration stays retriable (AC16b). [syncEventToSystem] does the actual move now
     * that [SystemCalendarService.updateEvent] writes CALENDAR_ID.
     */
    suspend fun migrateSyncedEvents(toCalendarId: Long): MigrationResult {
        val dao = eventDatabase.eventDao()
        val synced = dao.getAllEventsOnce().filter { it.systemCalendarEventId != null }
        var movedCount = 0
        var failedCount = 0
        for (event in synced) {
            val moved = try {
                syncEventToSystem(event, toCalendarId) != null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.w(TAG, "Migration failed for local event id=${event.id}", e)
                false
            }
            if (moved) movedCount++ else failedCount++
        }
        return MigrationResult(movedCount, failedCount)
    }

    /**
     * AC16b: runs any migration still recorded in
     * [PreferencesManager.pendingCalendarMigrationTo], clearing the flag only after a fully
     * successful pass. Returns a failed result (leaving the flag set) when permission is
     * missing or any event failed to move, so the next launch, permission grant, or target
     * change can retry.
     */
    suspend fun retryPendingMigration(): MigrationResult {
        val target = preferencesManager.pendingCalendarMigrationTo
        if (target == -1L) return MigrationResult(0, 0)
        if (!systemCalendarService.hasCalendarPermissions()) {
            // F08: flag stays set so the pass resumes once permission is granted.
            return MigrationResult(0, 0, error = "Calendar permission is required to finish moving events to the new calendar.")
        }
        val result = migrateSyncedEvents(target)
        // AC16b: only clear the flag when the target is still the one we just migrated. A newer
        // target written while this pass was suspending must survive so the queued pass migrates
        // to it instead of leaving events stranded in the old calendar.
        if (result.allMoved && preferencesManager.pendingCalendarMigrationTo == target) {
            preferencesManager.pendingCalendarMigrationTo = -1L
        }
        return if (result.allMoved) {
            result
        } else {
            result.copy(
                error = "${result.failedCount} event(s) could not be fully moved; they may still be in the previous calendar and will be retried."
            )
        }
    }
}

/**
 * R7: splits the stored attendee string back into individual names. The extraction path joins
 * its distinct attendee list with ", ", and manual edits reuse the same comma-separated field;
 * newlines are also accepted so multi-line "People" input survives. Empty and duplicate entries
 * are dropped so N parsed attendees become exactly N provider rows.
 */
private fun parseAttendees(raw: String): List<String> =
    raw.split(',', '\n', ';')
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()

private fun EventExtraction.toEventOrNull(
    sourceType: String,
    context: InputContext,
    sourceAttachment: SourceAttachment?,
    parseIso8601: (String?, ZoneId) -> Long?
): Event? {
    val zoneId = context.zoneIdOrDefault()
    val timePolicy = EventTimePolicy.from(this)
    val startTimeMillis = resolveStartTime(zoneId, timePolicy, parseIso8601) ?: return null
    val parsedEndTime = parseIso8601(endTime, zoneId)
    val endTimeMillis = resolveEndTime(
        startTimeMillis = startTimeMillis,
        parsedEndTime = parsedEndTime,
        timePolicy = timePolicy
    )

    return Event(
        title = title.takeIf { it.isNotBlank() } ?: "New Event",
        description = description,
        startTime = startTimeMillis,
        endTime = endTimeMillis,
        location = location,
        attendees = attendees.joinToString(", "),
        sourceType = sourceType,
        sourceAttachmentPath = sourceAttachment?.path.orEmpty(),
        sourceAttachmentMimeType = sourceAttachment?.mimeType.orEmpty(),
        sourceAttachmentName = sourceAttachment?.displayName.orEmpty(),
        aiConfidence = confidence ?: 1.0f
    )
}

private fun InputContext.zoneIdOrDefault(): ZoneId {
    return try {
        ZoneId.of(timezone)
    } catch (e: DateTimeParseException) {
        ZoneId.systemDefault()
    } catch (e: RuntimeException) {
        ZoneId.systemDefault()
    }
}

private fun EventExtraction.resolveStartTime(
    zoneId: ZoneId,
    timePolicy: EventTimePolicy,
    parseIso8601: (String?, ZoneId) -> Long?
): Long? {
    val cleanedStart = startTime.trim()
    val dateOnly = cleanedStart.toIsoDateOrNull()
    if (dateOnly != null && timePolicy.inferredStartTime != null) {
        return dateOnly.atTime(timePolicy.inferredStartTime)
            .atZone(zoneId)
            .toInstant()
            .toEpochMilli()
    }
    return parseIso8601(cleanedStart, zoneId)
}

private fun resolveEndTime(
    startTimeMillis: Long,
    parsedEndTime: Long?,
    timePolicy: EventTimePolicy
): Long {
    val fallbackEndTime = startTimeMillis + timePolicy.defaultDurationMillis
    if (parsedEndTime == null || parsedEndTime <= startTimeMillis) {
        return fallbackEndTime
    }

    val parsedDuration = parsedEndTime - startTimeMillis
    return if (parsedDuration > timePolicy.maximumDurationMillis) {
        startTimeMillis + timePolicy.maximumDurationMillis
    } else {
        parsedEndTime
    }
}

private fun String.toIsoDateOrNull(): LocalDate? {
    val cleaned = trim()
    if (!Regex("\\d{4}-\\d{2}-\\d{2}").matches(cleaned)) return null
    return try {
        LocalDate.parse(cleaned)
    } catch (e: DateTimeParseException) {
        null
    }
}

private data class EventTimePolicy(
    val inferredStartTime: LocalTime?,
    val defaultDurationMillis: Long,
    val maximumDurationMillis: Long
) {
    companion object {
        private const val HOUR_MS = 3_600_000L

        fun from(event: EventExtraction): EventTimePolicy {
            val text = listOf(event.title, event.description, event.location)
                .joinToString(" ")
                .lowercase(Locale.ROOT)

            return when {
                text.containsAnyWords("festival", "fest") -> EventTimePolicy(
                    inferredStartTime = LocalTime.of(20, 0),
                    defaultDurationMillis = 4 * HOUR_MS,
                    maximumDurationMillis = 8 * HOUR_MS
                )
                text.containsAnyWords(
                    "concert",
                    "show",
                    "live music",
                    "gig",
                    "lineup",
                    "line-up",
                    "dj set",
                    "party"
                ) -> EventTimePolicy(
                    inferredStartTime = LocalTime.of(20, 0),
                    defaultDurationMillis = 3 * HOUR_MS,
                    maximumDurationMillis = 6 * HOUR_MS
                )
                text.containsAnyWords("theatre", "theater", "cinema", "film", "movie", "screening") -> EventTimePolicy(
                    inferredStartTime = LocalTime.of(19, 30),
                    defaultDurationMillis = 2 * HOUR_MS,
                    maximumDurationMillis = 5 * HOUR_MS
                )
                text.containsAnyWords("dinner") -> EventTimePolicy(
                    inferredStartTime = LocalTime.of(19, 0),
                    defaultDurationMillis = 2 * HOUR_MS,
                    maximumDurationMillis = 4 * HOUR_MS
                )
                text.containsAnyWords("lunch") -> EventTimePolicy(
                    inferredStartTime = LocalTime.of(12, 0),
                    defaultDurationMillis = HOUR_MS + HOUR_MS / 2,
                    maximumDurationMillis = 3 * HOUR_MS
                )
                text.containsAnyWords("market", "picnic", "family day") -> EventTimePolicy(
                    inferredStartTime = LocalTime.of(14, 0),
                    defaultDurationMillis = 3 * HOUR_MS,
                    maximumDurationMillis = 6 * HOUR_MS
                )
                text.containsAnyWords("appointment", "meeting", "class", "workshop") -> EventTimePolicy(
                    inferredStartTime = LocalTime.of(9, 0),
                    defaultDurationMillis = HOUR_MS,
                    maximumDurationMillis = 4 * HOUR_MS
                )
                else -> EventTimePolicy(
                    inferredStartTime = null,
                    defaultDurationMillis = HOUR_MS,
                    maximumDurationMillis = 24 * HOUR_MS
                )
            }
        }
    }
}

private fun String.containsAnyWords(vararg needles: String): Boolean {
    return needles.any { needle ->
        Regex("""\b${Regex.escape(needle)}s?\b""").containsMatchIn(this)
    }
}

data class SourceAttachment(
    val path: String,
    val mimeType: String,
    val displayName: String
)

sealed class EventUpdateResult {
    data class Success(
        val event: Event,
        val syncedToSystem: Boolean,
        val warning: String? = null
    ) : EventUpdateResult()

    data class Failure(val message: String) : EventUpdateResult()
}

/**
 * Result of event creation operation.
 */
sealed class EventResult {
    data class Success(val events: List<Event>) : EventResult() {
        val event: Event get() = events.firstOrNull() ?: throw NoSuchElementException("EventResult.Success with empty events list")
    }
    data class Failure(
        val message: String,
        val debug: AnalysisFailureDebug? = null
    ) : EventResult()
}

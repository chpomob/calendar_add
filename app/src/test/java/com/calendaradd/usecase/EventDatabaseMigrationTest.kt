package com.calendaradd.usecase

import android.content.Context
import androidx.room.Room
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Covers P7 (F13): the durable extraction-batch journal primitives added to
 * [EventDao]/[EventDatabase] in Room migration 3 -> 4.
 *
 * These tests run on the plain JVM (no Robolectric/instrumentation) using Room's
 * bundled-SQLite driver. Room still asks the [Context] to resolve the database file
 * path even with a driver set, so [fakeContext] stubs just that one call.
 */
class EventDatabaseMigrationTest {

    /**
     * A relaxed mock is not enough here: Room's file-backed [Room.databaseBuilder] still
     * resolves the database name through [Context.getDatabasePath] even when a driver is
     * set, and a relaxed auto-mock returns an unrelated proxy File, silently redirecting
     * Room to the wrong file. Real Android's [Context.getDatabasePath] returns the path
     * as-is when it is already absolute, so mirror that here.
     */
    private fun fakeContext(): Context {
        val context = mockk<Context>(relaxed = true)
        every { context.applicationContext } returns context
        every { context.getDatabasePath(any()) } answers { File(firstArg<String>()) }
        return context
    }

    private inline fun SQLiteStatement.closing(block: (SQLiteStatement) -> Unit) {
        try {
            block(this)
        } finally {
            close()
        }
    }

    private fun inMemoryDatabase(): EventDatabase =
        Room.inMemoryDatabaseBuilder(fakeContext(), EventDatabase::class.java)
            .setDriver(BundledSQLiteDriver())
            .build()

    /** Builds a v3-schema (pre-journal) database file on disk, matching MIGRATION_1_2/2_3. */
    private fun seedVersion3Database(path: String, seedEventTitle: String? = null) {
        val driver = BundledSQLiteDriver()
        val connection = driver.open(path)
        try {
            connection.prepare(
                "CREATE TABLE IF NOT EXISTS `events` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`title` TEXT NOT NULL, `description` TEXT NOT NULL, " +
                    "`startTime` INTEGER NOT NULL, `endTime` INTEGER NOT NULL, " +
                    "`location` TEXT NOT NULL, `attendees` TEXT NOT NULL, " +
                    "`isFavorite` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, " +
                    "`updatedAt` INTEGER NOT NULL, `sourceType` TEXT NOT NULL, " +
                    "`sourceAttachmentPath` TEXT NOT NULL, `sourceAttachmentMimeType` TEXT NOT NULL, " +
                    "`sourceAttachmentName` TEXT NOT NULL, `aiConfidence` REAL NOT NULL, " +
                    "`aiSourceModel` TEXT NOT NULL, `systemCalendarEventId` INTEGER)"
            ).closing { it.step() }
            if (seedEventTitle != null) {
                connection.prepare(
                    "INSERT INTO events (title, description, startTime, endTime, location, " +
                        "attendees, isFavorite, createdAt, updatedAt, sourceType, " +
                        "sourceAttachmentPath, sourceAttachmentMimeType, sourceAttachmentName, " +
                        "aiConfidence, aiSourceModel, systemCalendarEventId) VALUES " +
                        "(?, '', 1000, 2000, '', '', 0, 1000, 1000, 'manual', '', '', '', 1.0, '', NULL)"
                ).closing {
                    it.bindText(1, seedEventTitle)
                    it.step()
                }
            }
            connection.prepare("PRAGMA user_version = 3").closing { it.step() }
        } finally {
            connection.close()
        }
    }

    private fun openMigratedDatabase(path: String): EventDatabase =
        Room.databaseBuilder(fakeContext(), EventDatabase::class.java, path)
            .setDriver(BundledSQLiteDriver())
            .addMigrations(EventDatabase.MIGRATION_3_4)
            .build()

    // ---- R2: migration 3 -> 4 is additive and preserves existing data ----

    @Test
    fun `migration 3 to 4 preserves existing events and adds journal tables`() {
        val path = File.createTempFile("event-db-migration", ".db").apply { delete() }.absolutePath
        seedVersion3Database(path, seedEventTitle = "Pre-existing event")

        val db = openMigratedDatabase(path)
        try {
            runBlocking {
                val events = db.eventDao().getEventByIdOnce(1L)
                assertNotNull("existing v3 event row must survive an additive migration", events)
                assertEquals("Pre-existing event", events?.title)

                assertTrue(db.eventDao().getUnfinishedBatches().isEmpty())
                assertNull(db.eventDao().findBatchByKey("any-key"))
            }
        } finally {
            db.close()
            File(path).delete()
        }
    }

    // ---- AC3 (R2): the database domain must stay out of backup allowlists ----

    @Test
    fun `backup and data extraction rules do not allowlist the database domain`() {
        val backupRules = parseXml(resolveResXml("backup_rules.xml"))
        val dataExtractionRules = parseXml(resolveResXml("data_extraction_rules.xml"))

        assertTrue(
            "backup_rules.xml must not reference the database domain",
            domainsOf(backupRules).none { it.equals("database", ignoreCase = true) }
        )
        assertTrue(
            "data_extraction_rules.xml must not reference the database domain",
            domainsOf(dataExtractionRules).none { it.equals("database", ignoreCase = true) }
        )
    }

    private fun resolveResXml(fileName: String): File {
        val candidates = listOf(
            File("app/src/main/res/xml/$fileName"),
            File("src/main/res/xml/$fileName"),
            File("../app/src/main/res/xml/$fileName")
        )
        return candidates.firstOrNull { it.exists() }
            ?: error(
                "Could not locate $fileName near working directory " +
                    File(".").absoluteFile.normalize()
            )
    }

    private fun parseXml(file: File) =
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)

    private fun domainsOf(document: org.w3c.dom.Document): List<String> {
        val nodes = document.getElementsByTagName("*")
        val domains = mutableListOf<String>()
        for (i in 0 until nodes.length) {
            val attributes = nodes.item(i).attributes ?: continue
            val domainAttr = attributes.getNamedItem("domain") ?: continue
            domains += domainAttr.nodeValue
        }
        return domains
    }

    // ---- R3/R4/AC2: idempotent batch journal identity ----

    @Test
    fun `getOrCreateBatch is idempotent for the same deterministic key`() = runBlocking {
        val db = inMemoryDatabase()
        try {
            val dao = db.eventDao()

            val first = dao.getOrCreateBatch(batchKey = "batch-key-1", sourceType = "text")
            val second = dao.getOrCreateBatch(batchKey = "batch-key-1", sourceType = "text")
            val other = dao.getOrCreateBatch(batchKey = "batch-key-2", sourceType = "text")

            assertEquals(first.id, second.id)
            assertEquals(1, dao.countBatchesForKey("batch-key-1"))
            assertTrue(other.id != first.id)
        } finally {
            db.close()
        }
    }

    @Test
    fun `commitBatchItems is idempotent across repeated calls for the same ordinals`() = runBlocking {
        val db = inMemoryDatabase()
        try {
            val dao = db.eventDao()
            val batch = dao.getOrCreateBatch(batchKey = "batch-key-3", sourceType = "text")
            val items = listOf(
                PendingBatchItem(ordinal = 0, event = Event(title = "Lunch", startTime = 1L, endTime = 2L)),
                PendingBatchItem(ordinal = 1, event = Event(title = "Dentist", startTime = 3L, endTime = 4L))
            )

            val firstCommit = dao.commitBatchItems(batch.id, items)
            val secondCommit = dao.commitBatchItems(batch.id, items)

            assertEquals(2, dao.getBatchItems(batch.id).size)
            assertEquals(
                firstCommit.map { it.id }.sorted(),
                secondCommit.map { it.id }.sorted()
            )
            assertEquals(listOf(0, 1), dao.getBatchItems(batch.id).map { it.ordinal })
        } finally {
            db.close()
        }
    }

    @Test
    fun `reprocessing a deterministic batch key after reopen reuses the same journal rows`() = runBlocking {
        val path = File.createTempFile("event-db-reopen", ".db").apply { delete() }.absolutePath
        try {
            val firstOpen = openMigratedDatabase(path)
            val batchId: Long
            try {
                val dao = firstOpen.eventDao()
                val batch = dao.getOrCreateBatch(batchKey = "reopen-key", sourceType = "text")
                batchId = batch.id
                dao.commitBatchItems(
                    batch.id,
                    listOf(PendingBatchItem(ordinal = 0, event = Event(title = "Lunch", startTime = 1L, endTime = 2L)))
                )
            } finally {
                firstOpen.close()
            }

            val secondOpen = openMigratedDatabase(path)
            try {
                val dao = secondOpen.eventDao()
                val reopenedBatch = dao.getOrCreateBatch(batchKey = "reopen-key", sourceType = "text")
                dao.commitBatchItems(
                    reopenedBatch.id,
                    listOf(PendingBatchItem(ordinal = 0, event = Event(title = "Lunch", startTime = 1L, endTime = 2L)))
                )

                assertEquals(batchId, reopenedBatch.id)
                assertEquals(1, dao.countBatchesForKey("reopen-key"))
                assertEquals(1, dao.getBatchItems(reopenedBatch.id).size)
            } finally {
                secondOpen.close()
            }
        } finally {
            File(path).delete()
        }
    }

    @Test
    fun `completeBatch and rollbackBatch transitions are idempotent`() = runBlocking {
        val db = inMemoryDatabase()
        try {
            val dao = db.eventDao()
            val batch = dao.getOrCreateBatch(batchKey = "complete-key", sourceType = "text")

            dao.completeBatch(batch.id)
            dao.completeBatch(batch.id)
            val completed = dao.getBatchById(batch.id)
            assertEquals(BatchStatus.COMMITTED.name, completed?.status)

            val other = dao.getOrCreateBatch(batchKey = "rollback-key", sourceType = "text")
            dao.rollbackBatch(other.id)
            dao.rollbackBatch(other.id)
            val rolledBack = dao.getBatchById(other.id)
            assertEquals(BatchStatus.ROLLED_BACK.name, rolledBack?.status)
        } finally {
            db.close()
        }
    }

    // ---- AC1/R3: all-or-nothing transaction on failure ----

    @Test
    fun `commitBatchItems rolls back the whole transaction when an event insert fails`() = runBlocking {
        val db = inMemoryDatabase()
        try {
            val dao = db.eventDao()
            val batch = dao.getOrCreateBatch(batchKey = "failure-key-events", sourceType = "text")

            // Pre-occupy primary key 1 so the batch's own event insert collides on id.
            val preExistingId = dao.insert(Event(id = 1L, title = "Existing", startTime = 1L, endTime = 2L))
            assertEquals(1L, preExistingId)

            val items = listOf(
                PendingBatchItem(ordinal = 0, event = Event(title = "Lunch", startTime = 1L, endTime = 2L)),
                PendingBatchItem(ordinal = 1, event = Event(id = 1L, title = "Colliding", startTime = 1L, endTime = 2L))
            )

            var threw = false
            try {
                dao.commitBatchItems(batch.id, items)
            } catch (_: Exception) {
                threw = true
            }

            assertTrue("expected the colliding insert to raise a constraint failure", threw)
            assertTrue(
                "no partial item rows should remain after rollback",
                dao.getBatchItems(batch.id).isEmpty()
            )
            assertEquals(
                "only the pre-existing event should remain; the batch's own insert must be rolled back",
                1,
                dao.getAllEventsOnce().size
            )
        } finally {
            db.close()
        }
    }

    @Test
    fun `commitBatchItems rolls back the event insert when the item insert violates a foreign key`() = runBlocking {
        val db = inMemoryDatabase()
        try {
            val dao = db.eventDao()
            val nonExistentBatchId = 999_999L

            var threw = false
            try {
                dao.commitBatchItems(
                    nonExistentBatchId,
                    listOf(PendingBatchItem(ordinal = 0, event = Event(title = "Orphan", startTime = 1L, endTime = 2L)))
                )
            } catch (_: Exception) {
                threw = true
            }

            assertTrue("expected a foreign key violation for a non-existent batchId", threw)
            assertTrue(
                "the event insert that preceded the failing item insert must also be rolled back",
                dao.getAllEventsOnce().isEmpty()
            )
        } finally {
            db.close()
        }
    }
}

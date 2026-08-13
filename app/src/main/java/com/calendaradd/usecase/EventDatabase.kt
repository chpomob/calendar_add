package com.calendaradd.usecase

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Room database for storing calendar events.
 */
@Database(
    entities = [Event::class, ExtractionBatchEntity::class, ExtractionBatchItemEntity::class],
    version = 4,
    exportSchema = false
)
abstract class EventDatabase : RoomDatabase() {

    abstract fun eventDao(): EventDao

    companion object {
        @Volatile
        private var INSTANCE: EventDatabase? = null

        fun getDatabase(context: Context): EventDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    EventDatabase::class.java,
                    "calendar_add_database"
                )
                    .addMigrations(MIGRATION_1_2)
                    .addMigrations(MIGRATION_2_3)
                    .addMigrations(MIGRATION_3_4)
                    .build()
                    .also { INSTANCE = it }
            }
        }

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE events ADD COLUMN sourceAttachmentPath TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE events ADD COLUMN sourceAttachmentMimeType TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE events ADD COLUMN sourceAttachmentName TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE events ADD COLUMN systemCalendarEventId INTEGER")
            }
        }

        /**
         * Additive: introduces the durable extraction-batch journal (F13/P7). Adds two new
         * tables only; the existing `events` table and its rows are untouched.
         *
         * Overrides the connection-based [Migration.migrate] (rather than the legacy
         * [SupportSQLiteDatabase] overload) so this migration runs identically whether Room
         * is backed by the platform's framework SQLite implementation (production) or a
         * [androidx.sqlite.SQLiteDriver] such as the bundled driver used by JVM unit tests.
         */
        internal val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSql(
                    "CREATE TABLE IF NOT EXISTS `extraction_batches` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`batchKey` TEXT NOT NULL, " +
                        "`sourceType` TEXT NOT NULL, " +
                        "`sourceAttachmentPath` TEXT NOT NULL, " +
                        "`sourceAttachmentMimeType` TEXT NOT NULL, " +
                        "`sourceAttachmentName` TEXT NOT NULL, " +
                        "`status` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, " +
                        "`completedAt` INTEGER)"
                )
                connection.execSql(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_extraction_batches_batchKey` " +
                        "ON `extraction_batches` (`batchKey`)"
                )
                connection.execSql(
                    "CREATE TABLE IF NOT EXISTS `extraction_batch_items` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`batchId` INTEGER NOT NULL, " +
                        "`ordinal` INTEGER NOT NULL, " +
                        "`eventId` INTEGER, " +
                        "`destination` TEXT NOT NULL, " +
                        "`syncState` TEXT NOT NULL, " +
                        "`providerId` INTEGER, " +
                        "`recoveryStatus` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, " +
                        "FOREIGN KEY(`batchId`) REFERENCES `extraction_batches`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE , " +
                        "FOREIGN KEY(`eventId`) REFERENCES `events`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE SET NULL )"
                )
                connection.execSql(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_extraction_batch_items_batchId_ordinal` " +
                        "ON `extraction_batch_items` (`batchId`, `ordinal`)"
                )
                connection.execSql(
                    "CREATE INDEX IF NOT EXISTS `index_extraction_batch_items_eventId` " +
                        "ON `extraction_batch_items` (`eventId`)"
                )
            }
        }

        private fun SQLiteConnection.execSql(sql: String) {
            val statement = prepare(sql)
            try {
                statement.step()
            } finally {
                statement.close()
            }
        }
    }
}

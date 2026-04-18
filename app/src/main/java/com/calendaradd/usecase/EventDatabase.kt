package com.calendaradd.usecase

import android.content.Context
import androidx.room.*
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/**
 * Room database for storing calendar events.
 */
@Database(
    entities = [Event::class],
    version = 1
)
@TypeConverters(DateConverter::class, TimeConverter::class)
abstract class EventDatabase : RoomDatabase() {

    abstract fun eventDao(): EventDao

    companion object {
        @Volatile
        private var INSTANCE: EventDatabase? = null

        fun getDatabase(context: Context): EventDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    EventDatabase::class.java,
                    "calendar_add_database"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

/**
 * Type converter for date strings.
 */
@TypeConverter
fun fromDate(date: Date): String {
    return date.time.toString()
}

@TypeConverter
fun toDate(dateString: String): Date {
    return Date(dateString.toLongOrNull())
}

/**
 * Type converter for time strings.
 */
@TypeConverter
fun fromTime(time: String): String {
    return time
}

@TypeConverter
fun toTime(timeString: String): String {
    return timeString
}

/**
 * Type converter for boolean flags.
 */
@TypeConverter
fun fromBoolean(value: Boolean): Boolean {
    return value
}

@TypeConverter
fun toBoolean(value: String): Boolean {
    return value.lowercase() == "true"
}

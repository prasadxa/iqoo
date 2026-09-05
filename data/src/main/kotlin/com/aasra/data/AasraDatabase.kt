package com.aasra.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Single Room database for Track E.
 *
 * Version 1. On schema change bump the version and add a real Migration;
 * [fallbackToDestructiveMigration] is a hackathon shortcut so a stale demo
 * DB never crashes the app on stage.
 */
@Database(
    entities = [Contact::class, Reminder::class, ConversationTurn::class],
    version = 1,
    exportSchema = false,
)
abstract class AasraDatabase : RoomDatabase() {

    abstract fun contacts(): ContactDao
    abstract fun reminders(): ReminderDao
    abstract fun conversation(): ConversationDao

    companion object {
        @Volatile
        private var instance: AasraDatabase? = null

        fun get(context: Context): AasraDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AasraDatabase::class.java,
                    "aasra.db",
                ).fallbackToDestructiveMigration(dropAllTables = true).build().also { instance = it }
            }
    }
}

package com.aasra.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface ReminderDao {

    /** New rows (id = 0) insert; daily roll-forward copies overwrite by id. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(reminder: Reminder): Long

    @Update
    suspend fun update(reminder: Reminder)

    @Query("SELECT * FROM reminders WHERE id = :id")
    suspend fun byId(id: Long): Reminder?

    @Query("SELECT * FROM reminders ORDER BY triggerAtMillis ASC")
    suspend fun all(): List<Reminder>

    /** Not yet fired, soonest first. Backs list_reminders. */
    @Query("SELECT * FROM reminders WHERE triggerAtMillis >= :now ORDER BY triggerAtMillis ASC")
    suspend fun upcoming(now: Long): List<Reminder>

    @Query("UPDATE reminders SET taken = 1, missed = 0 WHERE id = :id")
    suspend fun markTaken(id: Long)

    @Query("UPDATE reminders SET taken = 0, missed = 1 WHERE id = :id")
    suspend fun markMissed(id: Long)

    @Query("DELETE FROM reminders WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** Fired long ago and already acked; safe to prune. */
    @Query("DELETE FROM reminders WHERE triggerAtMillis < :before AND (taken = 1 OR missed = 1)")
    suspend fun deleteAckedBefore(before: Long)

    // --- Caregiver stats helpers (one day window = [dayStart, dayEnd)) ---

    @Query("SELECT COUNT(*) FROM reminders WHERE taken = 1 AND triggerAtMillis >= :dayStart AND triggerAtMillis < :dayEnd")
    suspend fun todayTaken(dayStart: Long, dayEnd: Long): Int

    @Query("SELECT COUNT(*) FROM reminders WHERE missed = 1 AND triggerAtMillis >= :dayStart AND triggerAtMillis < :dayEnd")
    suspend fun todayMissed(dayStart: Long, dayEnd: Long): Int

    @Query("SELECT COUNT(*) FROM reminders WHERE triggerAtMillis >= :now AND taken = 0 AND missed = 0")
    suspend fun countPending(now: Long): Int
}

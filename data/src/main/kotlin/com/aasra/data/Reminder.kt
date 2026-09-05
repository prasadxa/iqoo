package com.aasra.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A spoken reminder ("dawai", water, walk). The alarm itself is scheduled
 * with AlarmManager by ReminderTools; this row is the source of truth the
 * caregiver view and cancel/list tools read from.
 *
 * `repeat` is "once" or "daily" (see [REPEAT_ONCE]/[REPEAT_DAILY]).
 * `taken` / `missed` are flipped by the caregiver UI after the reminder fires.
 */
@Entity(tableName = "reminders", indices = [Index("triggerAtMillis")])
data class Reminder(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    val triggerAtMillis: Long,
    val repeat: String = REPEAT_ONCE,
    val taken: Boolean = false,
    val missed: Boolean = false,
    val createdAtMillis: Long = System.currentTimeMillis(),
) {
    companion object {
        const val REPEAT_ONCE = "once"
        const val REPEAT_DAILY = "daily"
    }
}

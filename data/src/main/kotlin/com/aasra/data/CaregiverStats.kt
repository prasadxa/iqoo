package com.aasra.data

import android.content.Context
import java.util.Calendar

/**
 * Backing data for the optional caregiver view (PLAN 6.2): today's reminders
 * taken/missed plus the last SOS. Plain data class + provider on purpose:
 * no @DatabaseView, so app/ can observe it with a one-shot load or poll
 * without adding a Room view to the schema.
 */
data class CaregiverStats(
    val todayTaken: Int,
    val todayMissed: Int,
    val pendingReminders: Int,
    /** Wall-clock millis of the last SOS, or null if none recorded. */
    val lastSosAtMillis: Long?,
)

/** Persists the last-SOS timestamp (SOS fires from tools/, reads land here). */
class CaregiverStatsStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getLastSosAtMillis(): Long? {
        val v = prefs.getLong(KEY_LAST_SOS, 0L)
        return if (v == 0L) null else v
    }

    fun recordSosNow(nowMillis: Long = System.currentTimeMillis()) {
        prefs.edit().putLong(KEY_LAST_SOS, nowMillis).apply()
    }

    companion object {
        private const val PREFS = "aasra_caregiver"
        private const val KEY_LAST_SOS = "last_sos_at"
    }
}

class CaregiverStatsProvider(
    private val reminders: ReminderDao,
    private val store: CaregiverStatsStore,
) {

    suspend fun load(nowMillis: Long = System.currentTimeMillis()): CaregiverStats {
        val dayStart = startOfDay(nowMillis)
        val dayEnd = dayStart + DAY_MILLIS
        return CaregiverStats(
            todayTaken = reminders.todayTaken(dayStart, dayEnd),
            todayMissed = reminders.todayMissed(dayStart, dayEnd),
            pendingReminders = reminders.countPending(nowMillis),
            lastSosAtMillis = store.getLastSosAtMillis(),
        )
    }

    private fun startOfDay(nowMillis: Long): Long {
        val cal = Calendar.getInstance().apply {
            timeInMillis = nowMillis
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return cal.timeInMillis
    }

    companion object {
        private const val DAY_MILLIS = 24L * 60 * 60 * 1000
    }
}

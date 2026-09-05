package com.aasra.tools

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.aasra.data.AasraDatabase
import com.aasra.data.Reminder
import com.aasra.data.ReminderDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * set/list/cancel_reminder (PLAN 6.1): exact AlarmManager alarm + Room record
 * + spoken fire path.
 *
 * Android 12–14 quirks, all handled with graceful fallback:
 * - API 31+: SCHEDULE_EXACT_ALARM may be denied (Android 14 denies by
 *   default). Then we schedule an inexact alarm, keep the Room record, and
 *   say so in plain words — the reminder still fires, possibly minutes late.
 * - API 33+: our fire notification needs POST_NOTIFICATIONS; without it the
 *   reminder only speaks through [ReminderReceiver.onFired] (foreground TTS).
 * - API 31+: PendingIntent must be FLAG_IMMUTABLE.
 * - SecurityException from setExact* (permission revoked on Android 14)
 *   falls back to inexact instead of crashing.
 */
class ReminderTools(
    private val context: Context,
    private val reminders: ReminderDao,
) {

    suspend fun setReminder(
        text: String,
        triggerAtMillis: Long,
        repeat: String = Reminder.REPEAT_ONCE,
    ): ToolResult = withContext(Dispatchers.IO) {
        val clean = text.trim()
        if (clean.isEmpty()) {
            return@withContext ToolResult(false, "What should I remind you about?")
        }
        if (triggerAtMillis <= System.currentTimeMillis()) {
            return@withContext ToolResult(false, "That time has already passed. Please tell me a future time.")
        }
        val saneRepeat = if (repeat == Reminder.REPEAT_DAILY) Reminder.REPEAT_DAILY else Reminder.REPEAT_ONCE
        val row = Reminder(text = clean, triggerAtMillis = triggerAtMillis, repeat = saneRepeat)
        val id = reminders.upsert(row)
        val scheduled = schedule(id, triggerAtMillis)
        val whenSpoken = formatSpoken(triggerAtMillis)
        if (scheduled == ScheduleResult.EXACT) {
            ToolResult(true, "Done. I will remind you to $clean, $whenSpoken.", data = id.toString())
        } else {
            ToolResult(
                true,
                "Done. I will remind you to $clean, $whenSpoken. " +
                    "Your phone settings do not allow exact alarms, so it may come a few minutes late.",
                data = id.toString(),
            )
        }
    }

    suspend fun listReminders(): ToolResult = withContext(Dispatchers.IO) {
        val up = reminders.upcoming(System.currentTimeMillis())
        if (up.isEmpty()) return@withContext ToolResult(true, "You have no upcoming reminders.")
        val spoken = up.take(5).joinToString("; ") { "${it.text}, ${formatSpoken(it.triggerAtMillis)}" }
        ToolResult(true, "Your reminders: $spoken.", data = up.joinToString("\n") { "${it.id}: ${it.text} @ ${it.triggerAtMillis}" })
    }

    suspend fun cancelReminder(id: Long): ToolResult = withContext(Dispatchers.IO) {
        val row = reminders.byId(id)
            ?: return@withContext ToolResult(false, "I could not find that reminder. It may already be gone.")
        cancelScheduled(id)
        reminders.deleteById(id)
        ToolResult(true, "Cancelled the reminder to ${row.text}.")
    }

    // --- scheduling internals ---

    internal enum class ScheduleResult { EXACT, INEXACT }

    internal fun schedule(id: Long, triggerAtMillis: Long): ScheduleResult {
        val am = context.getSystemService(AlarmManager::class.java) ?: return ScheduleResult.INEXACT
        val pi = firePendingIntent(id, cancelFirst = true)
        if (exactAlarmGranted(context)) {
            try {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
                return ScheduleResult.EXACT
            } catch (e: SecurityException) {
                // Revoked between check and call (Android 14). Fall through.
            }
        }
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
        return ScheduleResult.INEXACT
    }

    private fun cancelScheduled(id: Long) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        am.cancel(firePendingIntent(id, cancelFirst = false))
        firePendingIntent(id, cancelFirst = false).cancel()
    }

    private fun firePendingIntent(id: Long, cancelFirst: Boolean): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ACTION_REMINDER_FIRED
            putExtra(EXTRA_REMINDER_ID, id)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        if (cancelFirst) PendingIntent.getBroadcast(context, id.toInt(), intent, flags)?.cancel()
        return PendingIntent.getBroadcast(context, id.toInt(), intent, flags)
    }

    companion object {
        const val ACTION_REMINDER_FIRED = "com.aasra.tools.REMINDER_FIRED"
        const val EXTRA_REMINDER_ID = "reminder_id"

        fun exactAlarmGranted(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < 31) return true
            val am = context.getSystemService(AlarmManager::class.java) ?: return false
            return am.canScheduleExactAlarms()
        }

        private val SPOKEN_FMT = DateTimeFormatter.ofPattern("h:mm a, d MMMM")

        fun formatSpoken(triggerAtMillis: Long): String {
            val dt = LocalDateTime.ofInstant(Instant.ofEpochMilli(triggerAtMillis), ZoneId.systemDefault())
            return "at ${dt.format(SPOKEN_FMT)}"
        }

        internal fun nextDailyTrigger(
            triggerAtMillis: Long,
            now: Long = System.currentTimeMillis(),
            zoneId: ZoneId = ZoneId.systemDefault(),
        ): Long {
            var next = Instant.ofEpochMilli(triggerAtMillis).atZone(zoneId)
            val current = Instant.ofEpochMilli(now)
            do {
                next = next.plusDays(1)
            } while (!next.toInstant().isAfter(current))
            return next.toInstant().toEpochMilli()
        }
    }
}

/**
 * Alarm fire path. Runs on the broadcast thread: re-reads the Room row
 * (source of truth — a cancelled reminder simply isn't there), speaks it
 * through [onFired] (wired by app/ to the TTS queue), posts a notification
 * as backup, and rolls daily reminders one day forward.
 *
 * Must stay synchronous-safe: work goes through goAsync + IO scope.
 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ReminderTools.ACTION_REMINDER_FIRED) return
        val id = intent.getLongExtra(ReminderTools.EXTRA_REMINDER_ID, -1L)
        if (id < 0) return
        val pending = goAsync()
        scope.launch {
            try {
                val db = AasraDatabase.get(context)
                val row = db.reminders().byId(id) ?: return@launch
                val text = "Reminder: ${row.text}."
                try {
                    onFired?.invoke(row) ?: speakFallback(context, text)
                } catch (e: Exception) {
                    speakFallback(context, text)
                }
                postNotification(context, row)
                if (row.repeat == Reminder.REPEAT_DAILY) {
                    val nextAt = ReminderTools.nextDailyTrigger(row.triggerAtMillis)
                    val next = row.copy(triggerAtMillis = nextAt, taken = false, missed = false)
                    val nextId = db.reminders().upsert(next)
                    ReminderTools(context, db.reminders()).schedule(nextId, next.triggerAtMillis)
                }
            } finally {
                pending.finish()
            }
        }
    }

    private fun speakFallback(context: Context, text: String) {
        // TTS hook not wired (e.g. process restarted): notification carries it.
    }

    private fun postNotification(context: Context, row: Reminder) {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val nm = NotificationManagerCompat.from(context)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Reminders", NotificationManager.IMPORTANCE_HIGH),
            )
        }
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Aasra reminder")
            .setContentText(row.text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        nm.notify(row.id.toInt(), n)
    }

    companion object {
        private const val CHANNEL = "aasra_reminders"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /**
         * Wired once by app/ (or core-pipeline) to the live TTS queue:
         * `ReminderReceiver.onFired = { tts.speak("Reminder: ${it.text}") }`.
         * Null-safe: without it, the status-bar notification is the fallback.
         */
        @Volatile
        var onFired: ((Reminder) -> Unit)? = null
    }
}

/** Restores persisted alarms after reboot or an app update. */
class ReminderRescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return
        val pending = goAsync()
        scope.launch {
            try {
                val dao = AasraDatabase.get(context).reminders()
                val tools = ReminderTools(context, dao)
                val now = System.currentTimeMillis()
                dao.all().filter { !it.taken && !it.missed }.forEach { reminder ->
                    when {
                        reminder.triggerAtMillis > now ->
                            tools.schedule(reminder.id, reminder.triggerAtMillis)
                        reminder.repeat == Reminder.REPEAT_DAILY -> {
                            val next = reminder.copy(
                                triggerAtMillis = ReminderTools.nextDailyTrigger(
                                    reminder.triggerAtMillis,
                                    now,
                                ),
                            )
                            dao.upsert(next)
                            tools.schedule(next.id, next.triggerAtMillis)
                        }
                        else -> dao.markMissed(reminder.id)
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}

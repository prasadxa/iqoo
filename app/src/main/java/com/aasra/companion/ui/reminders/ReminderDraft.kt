package com.aasra.companion.ui.reminders

import com.aasra.data.Reminder
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

internal data class ReminderDraft(
    val text: String,
    val date: String,
    val hour: String,
    val minute: String,
    val daily: Boolean,
)

internal enum class ReminderInputError { TEXT, DATE, HOUR, MINUTE, FUTURE, CLOCK_CHANGE, ZONE_CHANGED }

internal data class ReminderValidation(val reminder: Reminder? = null, val error: ReminderInputError? = null)

internal fun validateReminder(draft: ReminderDraft, zone: ZoneId, now: Long): ReminderValidation {
    if (draft.text.isBlank()) return ReminderValidation(error = ReminderInputError.TEXT)
    val date = try {
        LocalDate.parse(draft.date)
    } catch (_: DateTimeException) {
        return ReminderValidation(error = ReminderInputError.DATE)
    }
    // Accept local-script decimal digits, but never signs, fractions or silent truncation.
    fun number(value: String): Int? = value.trim().takeIf { it.length in 1..2 }
        ?.map { it.digitToIntOrNull() ?: return null }
        ?.fold(0) { result, digit -> result * 10 + digit }

    val hour = number(draft.hour)?.takeIf { it in 0..23 }
        ?: return ReminderValidation(error = ReminderInputError.HOUR)
    val minute = number(draft.minute)?.takeIf { it in 0..59 }
        ?: return ReminderValidation(error = ReminderInputError.MINUTE)
    val local = date.atTime(LocalTime.of(hour, minute))
    val offsets = zone.rules.getValidOffsets(local)
    // A nonexistent or repeated wall-clock time must not silently select another instant.
    if (offsets.size != 1) return ReminderValidation(error = ReminderInputError.CLOCK_CHANGE)
    val at = local.toInstant(offsets.single()).toEpochMilli()
    if (at <= now) return ReminderValidation(error = ReminderInputError.FUTURE)
    return ReminderValidation(Reminder(
        text = draft.text.trim(), triggerAtMillis = at,
        repeat = if (draft.daily) Reminder.REPEAT_DAILY else Reminder.REPEAT_ONCE,
    ))
}

internal fun activeReminders(rows: List<Reminder>, now: Long): List<Reminder> = rows
    .filter { !it.taken && !it.missed && (it.triggerAtMillis >= now || it.repeat == Reminder.REPEAT_DAILY) }
    .sortedBy { it.triggerAtMillis }

internal fun formatReminderTime(at: Long, zone: ZoneId, locale: Locale): String {
    val time = Instant.ofEpochMilli(at).atZone(zone)
    val formatted = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.FULL, FormatStyle.SHORT)
        .withLocale(locale).format(time)
    val offset = if (time.offset.totalSeconds == 0) "" else time.offset.id
    return "$formatted (UTC$offset)"
}

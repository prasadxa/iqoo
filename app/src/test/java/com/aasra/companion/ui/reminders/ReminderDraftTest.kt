package com.aasra.companion.ui.reminders

import com.aasra.data.Reminder
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class ReminderDraftTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val now = Instant.parse("2026-09-05T00:00:00Z").toEpochMilli()
    private val draft = ReminderDraft("  Drink water  ", "2026-09-05", "9", "05", true)

    @Test fun validDailyReminderUsesSelectedDateAndLocalTime() {
        val result = validateReminder(draft, zone, now)
        assertNull(result.error)
        assertEquals("Drink water", result.reminder!!.text)
        assertEquals(Reminder.REPEAT_DAILY, result.reminder.repeat)
        assertEquals(Instant.parse("2026-09-05T03:35:00Z").toEpochMilli(), result.reminder.triggerAtMillis)
    }

    @Test fun validatesTextDateAndClockFieldsWithoutNormalizingInvalidValues() {
        listOf(
            draft.copy(text = " \n") to ReminderInputError.TEXT,
            draft.copy(date = "2026-02-30") to ReminderInputError.DATE,
            draft.copy(hour = "24") to ReminderInputError.HOUR,
            draft.copy(hour = "-1") to ReminderInputError.HOUR,
            draft.copy(hour = "") to ReminderInputError.HOUR,
            draft.copy(minute = "60") to ReminderInputError.MINUTE,
            draft.copy(minute = "1.5") to ReminderInputError.MINUTE,
        ).forEach { (input, expected) ->
            assertEquals(expected, validateReminder(input, zone, now).error)
        }
    }

    @Test fun acceptsHindiDigitsAndOneTimeRepeat() {
        val result = validateReminder(draft.copy(hour = "०९", minute = "०५", daily = false), zone, now)
        assertNull(result.error)
        assertEquals(Reminder.REPEAT_ONCE, result.reminder!!.repeat)
    }

    @Test fun pastAndEqualTimesAreRejectedEvenForDailyReminders() {
        val at = validateReminder(draft, zone, now).reminder!!.triggerAtMillis
        assertEquals(ReminderInputError.FUTURE, validateReminder(draft, zone, at).error)
        assertEquals(ReminderInputError.FUTURE, validateReminder(draft, zone, at + 1).error)
    }

    @Test fun daylightSavingGapAndOverlapRequireAnotherTimeInsteadOfSilentAdjustment() {
        val dstZone = ZoneId.of("America/New_York")
        val before = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli()
        for (input in listOf(
            draft.copy(date = "2026-03-08", hour = "2", minute = "30"),
            draft.copy(date = "2026-11-01", hour = "1", minute = "30"),
        )) {
            assertEquals(ReminderInputError.CLOCK_CHANGE, validateReminder(input, dstZone, before).error)
        }
    }

    @Test fun activeRowsExcludeAcknowledgedAndOldOneTimeRowsButKeepPendingDailyRows() {
        val future = Reminder(id = 1, text = "Water", triggerAtMillis = now + 60_000)
        val overdueDaily = future.copy(id = 2, repeat = Reminder.REPEAT_DAILY, triggerAtMillis = now - 60_000)
        val rows = listOf(future, overdueDaily, future.copy(id = 3, taken = true),
            future.copy(id = 4, missed = true), future.copy(id = 5, triggerAtMillis = now - 1))
        assertEquals(listOf(2L, 1L), activeReminders(rows, now).map { it.id })
        assertFalse(rows[0].taken)
        assertFalse(rows[0].missed)
    }

    @Test fun datesIncludeYearAndZoneWithHindiLocalization() {
        val at = Instant.parse("2026-09-05T23:00:00Z").toEpochMilli()
        val english = formatReminderTime(at, zone, Locale.ENGLISH)
        val hindi = formatReminderTime(at, zone, Locale.forLanguageTag("hi"))
        assertTrue(english.contains("2026"))
        assertTrue(english.contains("6"))
        assertTrue(english.contains("+05:30"))
        assertNotEquals(english, hindi)
    }
}

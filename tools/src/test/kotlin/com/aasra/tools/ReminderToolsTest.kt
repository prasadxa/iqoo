package com.aasra.tools

import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class ReminderToolsTest {

    @Test
    fun dailyReminderKeepsWallClockTimeAcrossDaylightSavingChange() {
        val zone = ZoneId.of("America/New_York")
        val original = LocalDateTime.of(2025, 3, 8, 9, 0)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()

        val next = ReminderTools.nextDailyTrigger(original, original, zone)
        val nextLocal = java.time.Instant.ofEpochMilli(next).atZone(zone)

        assertEquals(9, nextLocal.hour)
        assertEquals(9, nextLocal.dayOfMonth)
    }
}

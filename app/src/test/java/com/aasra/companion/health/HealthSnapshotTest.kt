package com.aasra.companion.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class HealthSnapshotTest {
    private val now = Instant.parse("2026-09-05T12:00:00Z")

    @Test fun permissionNeverGrantedIsNotNoDataOrRevoked() {
        assertEquals(HealthMetric.PermissionRequired, missingHealthPermission(false))
    }

    @Test fun aPreviouslyGrantedPermissionIsRevokedWhenMissing() {
        assertEquals(HealthMetric.Revoked, missingHealthPermission(true))
    }

    @Test fun zeroIsDataNotAnEmptyResult() {
        val metric = HealthMetric.Data(StepsSummary(0, emptySet(), null))
        assertEquals(0L, metric.value.count)
    }

    @Test fun freshnessUsesMeasurementTimeNotRefreshOrSyncTime() {
        assertEquals(HealthFreshness.RECENT, healthFreshness(now.minusSeconds(900), now))
        assertEquals(HealthFreshness.OLDER, healthFreshness(now.minusSeconds(901), now))
        assertEquals(HealthFreshness.RECENT, healthFreshness(now, now))
        assertEquals(HealthFreshness.CLOCK_MISMATCH, healthFreshness(now.plusSeconds(1), now))
    }

    @Test fun latestSampleDoesNotDependOnRecordOrPageOrder() {
        val newest = reading(60)
        val result = latestHeartReading(sequenceOf(reading(120), newest, reading(7200)), now.minusSeconds(86400), now)
        assertEquals(newest, result)
    }

    @Test fun sampleWindowIsStartInclusiveEndExclusive() {
        val start = now.minusSeconds(86400)
        val boundary = reading(86400)
        assertEquals(boundary, latestHeartReading(sequenceOf(reading(86401), boundary, reading(0), reading(-60)), start, now))
        assertNull(latestHeartReading(sequenceOf(reading(86401), reading(0), reading(-60)), start, now))
    }

    @Test fun emptySeriesIsNoReadingNotZeroBeats() {
        assertNull(latestHeartReading(emptySequence(), now.minusSeconds(86400), now))
    }

    @Test fun matchingSampleTimesPreferMostRecentlyUpdatedRecord() {
        val older = reading(60).copy(lastRecordUpdate = now.minusSeconds(30))
        val updated = reading(60)
        assertEquals(updated, latestHeartReading(sequenceOf(updated, older), now.minusSeconds(86400), now))
    }

    private fun reading(secondsAgo: Long) = HeartReading(72, now.minusSeconds(secondsAgo), "test.source", now)
}

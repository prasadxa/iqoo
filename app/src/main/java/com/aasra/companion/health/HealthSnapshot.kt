package com.aasra.companion.health

import java.time.Duration
import java.time.Instant

internal enum class HealthProvider { CHECKING, AVAILABLE, INSTALL_REQUIRED, UNAVAILABLE, ERROR }

internal sealed interface HealthMetric<out T> {
    data object PermissionRequired : HealthMetric<Nothing>
    data object Revoked : HealthMetric<Nothing>
    data object NoData : HealthMetric<Nothing>
    data object Error : HealthMetric<Nothing>
    data class Data<T>(val value: T) : HealthMetric<T>
}

internal data class StepsSummary(
    val count: Long,
    val origins: Set<String>,
    val lastRecordUpdate: Instant?,
)

internal data class HeartReading(
    val beatsPerMinute: Long,
    val recordedAt: Instant,
    val origin: String,
    val lastRecordUpdate: Instant,
)

internal data class HealthSnapshot(
    val provider: HealthProvider = HealthProvider.CHECKING,
    val steps: HealthMetric<StepsSummary> = HealthMetric.PermissionRequired,
    val heartRate: HealthMetric<HeartReading> = HealthMetric.PermissionRequired,
    val checkedAt: Instant? = null,
)

internal fun missingHealthPermission(previouslyGranted: Boolean): HealthMetric<Nothing> =
    if (previouslyGranted) HealthMetric.Revoked else HealthMetric.PermissionRequired

// This describes data age, never the user's medical condition.
internal enum class HealthFreshness { RECENT, OLDER, CLOCK_MISMATCH }

internal fun healthFreshness(recordedAt: Instant, now: Instant): HealthFreshness = when {
    recordedAt > now -> HealthFreshness.CLOCK_MISMATCH
    Duration.between(recordedAt, now) <= Duration.ofMinutes(15) -> HealthFreshness.RECENT
    else -> HealthFreshness.OLDER
}

/** Record start order is not sample order; a page can contain overlapping series. */
internal fun latestHeartReading(
    readings: Sequence<HeartReading>,
    start: Instant,
    end: Instant,
): HeartReading? = readings.filter { it.recordedAt >= start && it.recordedAt < end }
    .maxWithOrNull(compareBy<HeartReading> { it.recordedAt }.thenBy { it.lastRecordUpdate })

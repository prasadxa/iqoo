package com.aasra.companion.health

import android.content.Context
import androidx.core.content.edit
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.coroutines.coroutineContext

/** No health values are persisted, logged, or sent to the voice/cloud pipeline. */
internal class HealthRepository(context: Context) {
    private val context = context.applicationContext
    private val client by lazy { HealthConnectClient.getOrCreate(this.context) }
    // Only consent history, not health data, survives leaving the screen.
    private val consent = this.context.getSharedPreferences("health_permission_history", Context.MODE_PRIVATE)

    suspend fun read(): HealthSnapshot = withContext(Dispatchers.IO) {
        try {
            val provider = when (HealthConnectClient.getSdkStatus(context)) {
                HealthConnectClient.SDK_AVAILABLE -> HealthProvider.AVAILABLE
                HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> HealthProvider.INSTALL_REQUIRED
                else -> HealthProvider.UNAVAILABLE
            }
            if (provider != HealthProvider.AVAILABLE) return@withContext HealthSnapshot(provider)
            val client = this@HealthRepository.client
            val now = Instant.now()
            val steps = readGranted(client, stepsPermission) {
                readSteps(client, now)
            }
            val heart = readGranted(client, heartPermission) {
                readHeartRate(client, now)
            }
            // A grant can be revoked during a multi-page read. Do not publish those values.
            val stillGranted = client.permissionController.getGrantedPermissions()
            HealthSnapshot(
                provider = provider,
                steps = if (stepsPermission in stillGranted) steps else missingPermission(stepsPermission),
                heartRate = if (heartPermission in stillGranted) heart else missingPermission(heartPermission),
                checkedAt = Instant.now(),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            HealthSnapshot(provider = HealthProvider.ERROR)
        }
    }

    private fun missingPermission(permission: String) =
        missingHealthPermission(consent.getBoolean(permission, false))

    private suspend fun <T> readGranted(
        client: HealthConnectClient,
        permission: String,
        read: suspend () -> T?,
    ): HealthMetric<T> {
        coroutineContext.ensureActive()
        return try {
            if (permission !in client.permissionController.getGrantedPermissions()) {
                missingPermission(permission)
            } else {
                if (!consent.getBoolean(permission, false)) {
                    consent.edit { putBoolean(permission, true) }
                }
                read()?.let { HealthMetric.Data(it) } ?: HealthMetric.NoData
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: SecurityException) {
            // A foreground restriction can also throw SecurityException, so recheck consent.
            if (permission !in client.permissionController.getGrantedPermissions()) {
                missingPermission(permission)
            } else {
                HealthMetric.Error
            }
        } catch (_: Exception) {
            HealthMetric.Error
        }
    }

    private suspend fun readSteps(client: HealthConnectClient, now: Instant): StepsSummary? {
        val start = now.atZone(ZoneId.systemDefault()).toLocalDate()
            .atStartOfDay(ZoneId.systemDefault()).toInstant()
        if (start >= now) return null
        val range = TimeRangeFilter.between(start, now)
        // Health Connect resolves overlapping sources. Never sum raw step records ourselves.
        val aggregate = client.aggregate(AggregateRequest(setOf(StepsRecord.COUNT_TOTAL), range))
        val count = aggregate[StepsRecord.COUNT_TOTAL] ?: return null
        val origins = aggregate.dataOrigins.map { it.packageName }.toSet()
        var lastUpdate: Instant? = null
        var pageToken: String? = null
        do {
            coroutineContext.ensureActive()
            val page = client.readRecords(
                ReadRecordsRequest(StepsRecord::class, range, pageToken = pageToken),
            )
            page.records.filter { it.metadata.dataOrigin.packageName in origins }.forEach {
                val updated = it.metadata.lastModifiedTime
                if (lastUpdate?.let { updated > it } != false) lastUpdate = updated
            }
            pageToken = page.pageToken?.takeIf { it.isNotEmpty() }
        } while (pageToken != null)
        return StepsSummary(count, origins, lastUpdate)
    }

    private suspend fun readHeartRate(client: HealthConnectClient, now: Instant): HeartReading? {
        val start = now.minus(Duration.ofHours(24))
        val range = TimeRangeFilter.between(start, now)
        var latest: HeartReading? = null
        var pageToken: String? = null
        do {
            coroutineContext.ensureActive()
            val page = client.readRecords(
                ReadRecordsRequest(HeartRateRecord::class, range, pageToken = pageToken),
            )
            val candidates = page.records.asSequence().flatMap { record ->
                record.samples.asSequence().map { sample ->
                    HeartReading(
                        sample.beatsPerMinute, sample.time,
                        record.metadata.dataOrigin.packageName, record.metadata.lastModifiedTime,
                    )
                }
            }
            latest = latestHeartReading(listOfNotNull(latest).asSequence() + candidates, start, now)
            pageToken = page.pageToken?.takeIf { it.isNotEmpty() }
        } while (pageToken != null)
        return latest
    }

    companion object {
        const val PROVIDER_PACKAGE = "com.google.android.apps.healthdata"
        val stepsPermission = HealthPermission.getReadPermission(StepsRecord::class)
        val heartPermission = HealthPermission.getReadPermission(HeartRateRecord::class)
    }
}

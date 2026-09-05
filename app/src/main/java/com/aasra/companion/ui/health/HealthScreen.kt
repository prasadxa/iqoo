package com.aasra.companion.ui.health

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.aasra.companion.R
import com.aasra.companion.ui.components.PageLayout
import com.aasra.companion.health.HealthFreshness
import com.aasra.companion.health.HealthMetric
import com.aasra.companion.health.HealthProvider
import com.aasra.companion.health.HealthRepository
import com.aasra.companion.health.HealthSnapshot
import com.aasra.companion.health.healthFreshness
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Navigation supplies the app's localized context and Material3 theme. */
@Composable
fun HealthScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val repository = remember(context.applicationContext) { HealthRepository(context) }
    val refreshRequests = remember { Channel<Unit>(Channel.CONFLATED) }
    var snapshot by remember { mutableStateOf(HealthSnapshot()) }
    var refreshing by remember { mutableStateOf(false) }
    var actionFailed by remember { mutableStateOf(false) }
    var showGuide by remember { mutableStateOf(false) }
    var showDetails by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract(),
    ) {
        // Query the authoritative grants again, including partial grants or a cancelled dialog.
        refreshRequests.trySend(Unit)
    }

    LaunchedEffect(repository, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            try {
                while (isActive) {
                    refreshing = true
                    snapshot = repository.read()
                    refreshing = false
                    withTimeoutOrNull(30_000) { refreshRequests.receive() }
                }
            } finally {
                // No background reads or retained health values behind another screen/dialog.
                snapshot = HealthSnapshot()
                refreshing = false
            }
        }
    }

    fun open(intent: Intent) {
        actionFailed = false
        try {
            context.startActivity(intent)
        } catch (_: Exception) {
            actionFailed = true
        }
    }

    PageLayout(stringResource(R.string.health_title), stringResource(R.string.health_back), onBack,
        actions = {
            HealthButton(stringResource(if (refreshing) R.string.health_checking else R.string.health_refresh),
                onClick = { refreshRequests.trySend(Unit) }, enabled = !refreshing, tonal = true)
        }) {
            Text(stringResource(R.string.health_refresh_explanation), style = MaterialTheme.typography.bodyMedium)
            when (snapshot.provider) {
                HealthProvider.CHECKING -> Text(stringResource(R.string.health_checking))
                HealthProvider.UNAVAILABLE -> Text(stringResource(R.string.health_unavailable))
                HealthProvider.ERROR -> Text(stringResource(R.string.health_provider_error), color = MaterialTheme.colorScheme.error)
                HealthProvider.INSTALL_REQUIRED -> {
                    Text(stringResource(R.string.health_install_explanation))
                    HealthButton(stringResource(R.string.health_install), onClick = {
                        actionFailed = false
                        try {
                            context.startActivity(Intent(Intent.ACTION_VIEW,
                                "market://details?id=${HealthRepository.PROVIDER_PACKAGE}".toUri(),
                            ).setPackage("com.android.vending"))
                        } catch (_: ActivityNotFoundException) {
                            open(Intent(Intent.ACTION_VIEW,
                                "https://play.google.com/store/apps/details?id=${HealthRepository.PROVIDER_PACKAGE}".toUri(),
                            ))
                        } catch (_: Exception) {
                            actionFailed = true
                        }
                    })
                }
                HealthProvider.AVAILABLE -> {
                    val missing = buildSet {
                        if (snapshot.steps.needsPermission()) add(HealthRepository.stepsPermission)
                        if (snapshot.heartRate.needsPermission()) add(HealthRepository.heartPermission)
                    }
                    if (missing.isNotEmpty()) {
                        Text(stringResource(R.string.health_permission_explanation), style = MaterialTheme.typography.bodyMedium)
                        HealthButton(stringResource(R.string.health_grant), onClick = {
                            actionFailed = false
                            try {
                                permissionLauncher.launch(missing)
                            } catch (_: Exception) {
                                actionFailed = true
                            }
                        }, enabled = !refreshing)
                    }
                    if (!snapshot.steps.needsPermission()) HealthMetricSection(stringResource(R.string.health_steps_title), snapshot.steps) { steps ->
                        Text(localNumber(steps.count), style = MaterialTheme.typography.headlineLarge)
                        Text(stringResource(R.string.health_steps_detail), style = MaterialTheme.typography.bodyMedium)
                        if (showDetails) { HealthSources(steps.origins); RecordUpdate(steps.lastRecordUpdate) }
                    }
                    if (!snapshot.heartRate.needsPermission()) HealthMetricSection(stringResource(R.string.health_heart_title), snapshot.heartRate) { heart ->
                        Text(stringResource(R.string.health_bpm, localNumber(heart.beatsPerMinute)), style = MaterialTheme.typography.headlineLarge)
                        Text(stringResource(R.string.health_measured_at, localTime(heart.recordedAt)), style = MaterialTheme.typography.bodyMedium)
                        val freshness = healthFreshness(heart.recordedAt, snapshot.checkedAt ?: Instant.now())
                        Text(stringResource(when (freshness) {
                            HealthFreshness.RECENT -> R.string.health_recent
                            HealthFreshness.OLDER -> R.string.health_older
                            HealthFreshness.CLOCK_MISMATCH -> R.string.health_clock_mismatch
                        }), style = MaterialTheme.typography.bodyMedium)
                        if (showDetails) { HealthSources(setOf(heart.origin)); RecordUpdate(heart.lastRecordUpdate) }
                    }
                    if (showDetails) snapshot.checkedAt?.let {
                        Text(stringResource(R.string.health_last_checked, localTime(it)), style = MaterialTheme.typography.bodyMedium)
                    }
                    if (missing.size < 2) TextButton(onClick = { showDetails = !showDetails }, modifier = Modifier.heightIn(min = 64.dp)) {
                        Text(stringResource(if (showDetails) R.string.health_hide_details else R.string.health_show_details))
                    }
                    HealthButton(stringResource(R.string.health_settings), onClick = {
                        open(Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS).apply {
                            if (Build.VERSION.SDK_INT < 34) setPackage(HealthRepository.PROVIDER_PACKAGE)
                        })
                    }, tonal = true)
                }
            }
            if (actionFailed) {
                Text(
                    stringResource(R.string.health_action_error), color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            TextButton(onClick = { showGuide = !showGuide }, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) {
                Text(stringResource(R.string.health_watch_title))
            }
            if (showGuide) {
                Text(stringResource(R.string.health_watch_explanation), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.health_sync_explanation), style = MaterialTheme.typography.bodyMedium)
            }
            Text(stringResource(R.string.health_medical_notice), style = MaterialTheme.typography.bodyMedium)
            HealthButton(stringResource(R.string.health_privacy_title), onClick = {
                open(Intent(context, HealthPermissionsActivity::class.java))
            }, tonal = true)
    }
}

private fun HealthMetric<*>.needsPermission() =
    this == HealthMetric.PermissionRequired || this == HealthMetric.Revoked

@Composable
private fun <T> HealthMetricSection(title: String, metric: HealthMetric<T>, content: @Composable (T) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.large) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            when (metric) {
                is HealthMetric.Data -> content(metric.value)
                else -> Text(stringResource(when (metric) {
                    HealthMetric.PermissionRequired -> R.string.health_permission_required
                    HealthMetric.Revoked -> R.string.health_permission_revoked
                    HealthMetric.NoData -> R.string.health_no_data
                    else -> R.string.health_read_error
                }), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun HealthSources(origins: Set<String>) {
    Text(
        if (origins.isEmpty()) stringResource(R.string.health_source_unknown)
        else stringResource(R.string.health_sources, origins.sorted().joinToString("\n")),
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun RecordUpdate(time: Instant?) {
    Text(
        if (time == null) stringResource(R.string.health_record_update_unknown)
        else stringResource(R.string.health_record_updated, localTime(time)),
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun localNumber(value: Long): String =
    NumberFormat.getIntegerInstance(LocalConfiguration.current.locales[0]).format(value)

@Composable
private fun localTime(value: Instant): String {
    val zone = ZoneId.systemDefault()
    val formatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
        .withLocale(LocalConfiguration.current.locales[0]).withZone(zone)
    return stringResource(R.string.health_timestamp, formatter.format(value), zone.id)
}

@Composable
internal fun HealthButton(label: String, onClick: () -> Unit, enabled: Boolean = true, tonal: Boolean = false) {
    val modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)
    if (tonal) {
        FilledTonalButton(
            onClick, modifier, enabled = enabled, shape = MaterialTheme.shapes.medium,
            colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        ) {
            Text(label, textAlign = TextAlign.Center)
        }
    } else {
        Button(onClick, modifier, enabled = enabled, shape = MaterialTheme.shapes.medium) {
            Text(label, textAlign = TextAlign.Center)
        }
    }
}

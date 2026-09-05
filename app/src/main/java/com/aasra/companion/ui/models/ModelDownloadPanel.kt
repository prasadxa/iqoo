package com.aasra.companion.ui.models

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.aasra.companion.R
import com.aasra.llama.RamTier
import com.aasra.models.ModelDownloader
import com.aasra.models.ModelPaths
import com.aasra.models.ModelRegistry
import java.io.File

/**
 * Reusable content: the host supplies scrolling and window/IME insets.
 * Downloads belong to this composition, not a background service. To leave only
 * after cancellation completes, set [stopRequested] and navigate in [onStopped].
 */
@Composable
fun ModelDownloadPanel(
    onAllDoneChange: (Boolean) -> Unit = {},
    onModelsChanged: () -> Unit = {},
    stopRequested: Boolean = false,
    onStopped: () -> Unit = {},
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val scope = rememberCoroutineScope()
    val downloader = remember(appContext) { ModelDownloader(appContext) }
    val controller = remember(appContext, scope) {
        ModelDownloadController(
            ModelRegistry.defaultSet(RamTier.decide(appContext) == RamTier.LlmTier.SMALL),
            scope,
            inspect = { entry ->
                val target = ModelPaths.downloadFileFor(appContext, entry)
                LocalModelFiles(
                    ModelPaths.isInstalled(appContext, entry),
                    File(target.parentFile, target.name + ModelDownloader.PART_SUFFIX).length(),
                )
            },
            download = { entry, wifiOnly, progress -> downloader.download(entry, wifiOnly, progress) },
        )
    }
    val state by controller.state.collectAsState()
    val allDoneChange by rememberUpdatedState(onAllDoneChange)
    val modelsChanged by rememberUpdatedState(onModelsChanged)
    val stopped by rememberUpdatedState(onStopped)
    var onWifi by remember { mutableStateOf(downloader.isWifiConnected()) }
    var mobileConsent by rememberSaveable { mutableStateOf(false) }
    var askMobileData by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(controller) { controller.refresh() }
    LaunchedEffect(state.allDone) { allDoneChange(state.allDone) }
    // Loading every engine after each downloaded file wastes memory and races
    // the remaining downloads. Reload once after the batch/verification ends.
    LaunchedEffect(state.revision, state.busy) {
        if (state.revision > 0 && !state.busy) modelsChanged()
    }
    LaunchedEffect(stopRequested) {
        if (stopRequested) {
            controller.cancel()
            controller.awaitIdle()
            stopped()
        }
    }

    val failed = state.rows.any { it.phase == ModelPhase.FAILED }
    val verifying = state.rows.any { it.phase == ModelPhase.VERIFYING }
    val total = state.rows.sumOf { it.entry.sizeBytes }.coerceAtLeast(1)
    val progress = (state.rows.sumOf { it.doneBytes }.toFloat() / total).coerceIn(0f, 1f)
    Column(Modifier.fillMaxWidth().padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp)) {
        if (state.busy) CircularProgressIndicator(Modifier.size(48.dp), strokeWidth = 4.dp)
        Text(stringResource(when {
            state.stopping || stopRequested -> R.string.setup_models_stopping
            state.allDone -> R.string.setup_simple_ready
            verifying -> R.string.setup_simple_finishing
            state.busy -> R.string.setup_simple_downloading
            failed -> R.string.setup_simple_failed
            else -> R.string.setup_simple_start
        }), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        if (state.busy || progress > 0f && !state.allDone) {
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        }
        if (state.busy && !stopRequested) {
            Text(stringResource(R.string.setup_simple_keep_open), textAlign = TextAlign.Center)
            TextButton(onClick = controller::cancel, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) {
                Text(stringResource(R.string.setup_models_cancel))
            }
        } else if (!state.allDone && !stopRequested) {
            Button(onClick = {
                onWifi = downloader.isWifiConnected()
                if (!onWifi && !mobileConsent) askMobileData = true
                else controller.start(state.rows.map { it.entry }, wifiOnly = !mobileConsent)
            }, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) {
                Text(stringResource(if (failed) R.string.setup_simple_retry else R.string.setup_simple_download))
            }
        }
        if (state.allDone) TextButton(onClick = { controller.refresh() },
            modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) {
            Text(stringResource(R.string.setup_simple_recheck))
        }
    }
    if (askMobileData) AlertDialog(
        onDismissRequest = { askMobileData = false },
        title = { Text(stringResource(R.string.setup_simple_wifi_title)) },
        text = { Text(stringResource(R.string.setup_simple_mobile_warning, Formatter.formatShortFileSize(context, total))) },
        confirmButton = {
            TextButton(onClick = {
                askMobileData = false
                mobileConsent = true
                controller.start(state.rows.map { it.entry }, wifiOnly = false)
            }, modifier = Modifier.heightIn(min = 64.dp)) { Text(stringResource(R.string.setup_models_allow_mobile)) }
        },
        dismissButton = {
            TextButton(onClick = { askMobileData = false }, modifier = Modifier.heightIn(min = 64.dp)) {
                Text(stringResource(R.string.setup_simple_wait_wifi))
            }
        },
    )
}

@Composable
internal fun ModelDownloadRow(row: ModelRowUi, actionsEnabled: Boolean, onRetry: () -> Unit) {
    val status = when (row.phase) {
        ModelPhase.CHECKING -> stringResource(R.string.setup_models_checking)
        ModelPhase.WAITING -> stringResource(R.string.setup_models_waiting)
        ModelPhase.DOWNLOADING -> stringResource(R.string.setup_models_progress, (row.progress * 100).toInt())
        ModelPhase.VERIFYING -> stringResource(R.string.setup_models_verifying)
        ModelPhase.DONE -> stringResource(R.string.setup_models_ready)
        ModelPhase.FAILED -> stringResource(R.string.setup_models_failed)
        ModelPhase.PAUSED -> stringResource(R.string.setup_models_paused)
    }
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(stringResource(modelTitle(row.entry)), style = MaterialTheme.typography.titleMedium)
        Text(row.entry.fileName.substringAfterLast('/'), style = MaterialTheme.typography.bodySmall)
        Text(status, style = MaterialTheme.typography.bodyMedium)
        Text(
            stringResource(R.string.setup_models_bytes,
                Formatter.formatShortFileSize(LocalContext.current, row.doneBytes),
                Formatter.formatShortFileSize(LocalContext.current, row.entry.sizeBytes)),
            style = MaterialTheme.typography.bodySmall,
        )
        when (row.phase) {
            ModelPhase.CHECKING, ModelPhase.VERIFYING -> LinearProgressIndicator(Modifier.fillMaxWidth())
            ModelPhase.DOWNLOADING -> LinearProgressIndicator(progress = { row.progress }, modifier = Modifier.fillMaxWidth())
            else -> Unit
        }
        if (row.phase == ModelPhase.FAILED) {
            Text(
                stringResource(if (row.error?.startsWith(ModelDownloader.CHECKSUM_PREFIX) == true)
                    R.string.setup_models_checksum_error else R.string.setup_models_retry_help),
                color = MaterialTheme.colorScheme.error,
            )
            row.error?.let { Text(stringResource(R.string.setup_models_diagnostic, it), style = MaterialTheme.typography.bodySmall) }
            TextButton(onClick = onRetry, enabled = actionsEnabled, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) {
                Text(stringResource(R.string.setup_models_retry))
            }
        }
    }
}

private fun modelTitle(entry: ModelRegistry.Entry): Int = when (entry) {
    ModelRegistry.SILERO_VAD -> R.string.setup_model_vad
    ModelRegistry.STT_ZIPFORMER_ENCODER -> R.string.setup_model_en_encoder
    ModelRegistry.STT_ZIPFORMER_DECODER -> R.string.setup_model_en_decoder
    ModelRegistry.STT_ZIPFORMER_JOINER -> R.string.setup_model_en_joiner
    ModelRegistry.STT_ZIPFORMER_TOKENS -> R.string.setup_model_en_tokens
    ModelRegistry.STT_INDICCONFORMER_HI -> R.string.setup_model_hi
    ModelRegistry.STT_INDICCONFORMER_TOKENS -> R.string.setup_model_hi_tokens
    ModelRegistry.SPOKEN_LANGUAGE_ID -> R.string.setup_model_language
    ModelRegistry.KEYWORD_SPOTTER -> R.string.setup_model_wake
    ModelRegistry.KOKORO -> R.string.setup_model_kokoro
    ModelRegistry.PIPER_HI -> R.string.setup_model_piper_hi
    ModelRegistry.PIPER_EN -> R.string.setup_model_piper_en
    ModelRegistry.QWEN_LOW_MEMORY -> R.string.setup_model_qwen_small
    else -> R.string.setup_model_qwen
}

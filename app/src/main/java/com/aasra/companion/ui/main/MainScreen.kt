package com.aasra.companion.ui.main

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.aasra.companion.R
import com.aasra.companion.pipeline.ReadinessStatus
import com.aasra.companion.pipeline.Route
import com.aasra.companion.pipeline.RunMode
import com.aasra.companion.pipeline.VoiceState
import com.aasra.companion.prefs.UserPrefs
import com.aasra.companion.service.VoiceService
import com.aasra.companion.service.PhoneAccessBus
import com.aasra.companion.ui.components.PhoneAccessPanel
import com.aasra.companion.ui.theme.*
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun MainScreen(
    viewModel: MainViewModel,
    onOpenSettings: () -> Unit,
    onOpenModels: () -> Unit = {},
    onOpenHealth: () -> Unit = {},
    onOpenReminders: () -> Unit = {},
) {
    val localState by viewModel.voiceState.collectAsState()
    val turn by viewModel.turn.collectAsState()
    val mode by viewModel.runMode.collectAsState()
    val localLevel by viewModel.audioLevel.collectAsState()
    val cloudState by viewModel.cloudState.collectAsState()
    val cloudUserText by viewModel.cloudUserText.collectAsState()
    val cloudAssistantText by viewModel.cloudAssistantText.collectAsState()
    val cloudLevel by viewModel.cloudAudioLevel.collectAsState()
    val readiness by viewModel.readiness.collectAsState()
    val paused by VoiceService.paused.collectAsState()
    val phoneAccessRequest by PhoneAccessBus.request.collectAsState()
    val latency by viewModel.lastLatencyMs.collectAsState()
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val haptics = LocalHapticFeedback.current
    fun micGranted() = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    var microphoneGranted by remember { mutableStateOf(micGranted()) }
    var permissionDenied by rememberSaveable { mutableStateOf(false) }
    var showSos by rememberSaveable { mutableStateOf(false) }
    var sosPermissionError by rememberSaveable { mutableStateOf(false) }
    var showHelp by rememberSaveable { mutableStateOf(false) }
    var showTranscript by rememberSaveable { mutableStateOf(false) }
    val cloudActive = mode == RunMode.CLOUD && cloudState in setOf("connecting", "listening", "thinking", "speaking") && !paused
    val connecting = cloudActive && cloudState == "connecting"
    val state = if (cloudActive) when (cloudState) {
        "speaking" -> VoiceState.SPEAKING
        "thinking" -> VoiceState.THINKING
        "listening" -> VoiceState.LISTENING
        else -> VoiceState.IDLE
    } else localState
    val visiblyPaused = paused && state == VoiceState.IDLE
    val userText = if (cloudActive) cloudUserText else turn.transcript
    val answer = if (cloudActive) cloudAssistantText else turn.answer
    val stateLabel = stringResource(when {
        connecting -> R.string.home_connecting
        visiblyPaused -> R.string.home_paused
        else -> when (state) {
            VoiceState.IDLE -> R.string.home_ready
            VoiceState.LISTENING -> R.string.home_listening
            VoiceState.THINKING -> R.string.home_thinking
            VoiceState.SPEAKING -> R.string.home_speaking
        }
    })
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        microphoneGranted = granted
        permissionDenied = !granted
        if (granted) VoiceService.start(context)
    }
    fun executeSos() {
        VoiceService.stop(context)
        viewModel.onSos()
    }
    val sosLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        val allowed = listOf(Manifest.permission.CALL_PHONE, Manifest.permission.SEND_SMS).all {
            grants[it] == true || ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        sosPermissionError = !allowed
        if (allowed) executeSos()
    }
    DisposableEffect(lifecycle, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) microphoneGranted = micGranted()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(state) {
        if (state == VoiceState.LISTENING || state == VoiceState.SPEAKING) {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }

    Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
      // Only conversation content scrolls. Voice, SOS and care actions stay put.
      LazyColumn(
        modifier = Modifier.weight(1f).fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("Aasra", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.semantics { heading() })
                    Text(stringResource(R.string.home_companion), style = MaterialTheme.typography.bodySmall, color = InkSoft)
                }
                IconButton(onClick = onOpenSettings, modifier = Modifier.size(64.dp)) {
                    Icon(Icons.Default.Settings, stringResource(R.string.home_settings), Modifier.size(30.dp))
                }
                IconButton(onClick = { showHelp = true }, modifier = Modifier.size(64.dp)) {
                    Icon(Icons.Default.HelpOutline, stringResource(R.string.home_help), Modifier.size(28.dp))
                }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                ReactiveVoiceOrb(state, if (cloudActive) cloudLevel else localLevel,
                    stringResource(R.string.home_state_description, stateLabel.lowercase()))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stateLabel, style = MaterialTheme.typography.headlineMedium,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    Text(stringResource(when {
                        connecting -> R.string.home_connecting_detail
                        visiblyPaused -> R.string.home_paused_detail
                        state == VoiceState.SPEAKING -> R.string.home_echo_detail
                        state == VoiceState.THINKING -> R.string.home_thinking_detail
                        cloudActive -> R.string.home_cloud_listening_detail
                        state == VoiceState.LISTENING -> R.string.home_listening_detail
                        else -> R.string.home_ready_detail
                    }), style = MaterialTheme.typography.bodyMedium, color = InkSoft)
                }
            }
        }
        if (phoneAccessRequest != null) item {
            PhoneAccessPanel(showSms = phoneAccessRequest == PhoneAccessBus.Request.SMS)
        }
        if (!microphoneGranted) item {
            Text(stringResource(R.string.home_mic_explanation), style = MaterialTheme.typography.bodyMedium)
            if (permissionDenied) {
                Text(stringResource(R.string.home_mic_denied), color = SosRed)
                TextButton(onClick = {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                }, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) { Text(stringResource(R.string.home_permissions)) }
            }
        }
        if (sosPermissionError) item {
            Text(stringResource(R.string.home_sos_permissions), color = SosRed,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
        if (readiness != null && readiness?.canAssist != true && mode != RunMode.CLOUD) item {
            Surface(color = PaperRaised, shape = MaterialTheme.shapes.medium) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(when (readiness?.status) {
                        ReadinessStatus.LOADING -> R.string.home_models_loading
                        ReadinessStatus.FAILED -> R.string.home_models_failed
                        else -> R.string.home_models_missing
                    }), style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = onOpenModels, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) {
                        Text(stringResource(R.string.home_models))
                    }
                }
            }
        }
        if (userText.isNotBlank()) item {
            TextButton(onClick = { showTranscript = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) {
                Text(stringResource(R.string.home_heard, userText), maxLines = 2, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (answer.isNotBlank()) item {
            TranscriptBlock("Aasra", answer, PineInk, TextOnPine, false)
        }
        item {
            Text(stringResource(when (mode) {
                RunMode.OFFLINE -> R.string.home_mode_offline
                RunMode.HYBRID -> R.string.home_mode_hybrid
                RunMode.CLOUD -> R.string.home_mode_cloud
            }), style = MaterialTheme.typography.bodySmall, color = InkSoft)
            if (!cloudActive && turn.answer.isNotBlank() && turn.route != Route.UNAVAILABLE) {
                Text(stringResource(if (turn.route == Route.LOCAL) R.string.home_local_answer else R.string.home_cloud_answer),
                    style = MaterialTheme.typography.bodySmall, color = InkSoft)
            }
        }
      }
      Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
          verticalArrangement = Arrangement.spacedBy(10.dp)) {
        HomeAction(stringResource(when {
            visiblyPaused -> R.string.home_resume
            connecting -> R.string.home_connecting
            state == VoiceState.SPEAKING -> R.string.home_interrupt
            state == VoiceState.THINKING -> R.string.home_thinking
            cloudActive -> R.string.home_listening
            state == VoiceState.LISTENING -> R.string.home_finish
            else -> R.string.home_talk
        }), Icons.Default.Mic,
            enabled = !connecting && state != VoiceState.THINKING && (!cloudActive || state == VoiceState.SPEAKING),
            onClick = {
                when {
                    !microphoneGranted -> permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    visiblyPaused || state == VoiceState.IDLE -> VoiceService.start(context)
                    else -> viewModel.onTalk()
                }
            })
        AdaptiveActions(
            first = { HomeAction(stringResource(R.string.home_repeat), Icons.Default.Replay,
                enabled = answer.isNotBlank() && !cloudActive && state != VoiceState.SPEAKING && state != VoiceState.THINKING,
                color = PaperRaised, ink = PineInk, vertical = true, onClick = viewModel::onRepeat) },
            second = { HomeAction(stringResource(R.string.home_sos), Icons.Default.Call, color = SosRed, vertical = true, onClick = { showSos = true }) },
        )
        AdaptiveActions(
            first = { HomeAction(stringResource(R.string.home_reminders), Icons.Default.Alarm, color = PaperRaised, ink = PineInk, vertical = true, onClick = onOpenReminders) },
            second = { HomeAction(stringResource(R.string.home_health), Icons.Default.Favorite, color = PaperRaised, ink = PineInk, vertical = true, onClick = onOpenHealth) },
        )
        if (cloudActive || state != VoiceState.IDLE) TextButton(onClick = { VoiceService.stop(context) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) {
            Icon(Icons.Default.Stop, null)
            Spacer(Modifier.width(12.dp))
            Text(stringResource(R.string.home_stop), textAlign = TextAlign.Center)
        }
      }
    }
    if (showSos) AlertDialog(
        onDismissRequest = { showSos = false },
        title = { Text(stringResource(R.string.home_sos_title)) },
        text = { Text(stringResource(R.string.home_sos_detail), modifier = Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = {
            TextButton(onClick = {
                showSos = false
                val required = arrayOf(Manifest.permission.CALL_PHONE, Manifest.permission.SEND_SMS)
                if (required.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }) executeSos()
                else sosLauncher.launch(required)
            }, modifier = Modifier.heightIn(min = 64.dp)) { Text(stringResource(R.string.home_sos_confirm), color = SosRed) }
        },
        dismissButton = {
            TextButton(onClick = { showSos = false }, modifier = Modifier.heightIn(min = 64.dp)) { Text(stringResource(R.string.home_cancel)) }
        },
    )
    if (showHelp) AlertDialog(
        onDismissRequest = { showHelp = false },
        title = { Text(stringResource(R.string.home_help)) },
        text = { Text(stringResource(R.string.home_help_detail), modifier = Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = {
            TextButton(onClick = { showHelp = false }, modifier = Modifier.heightIn(min = 64.dp)) { Text(stringResource(R.string.home_close)) }
        },
    )
    if (showTranscript) AlertDialog(
        onDismissRequest = { showTranscript = false },
        title = { Text(stringResource(R.string.home_you)) },
        text = { Text(userText, modifier = Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onClick = { showTranscript = false }, modifier = Modifier.heightIn(min = 64.dp)) { Text(stringResource(R.string.home_close)) } },
    )
}

@Composable
private fun AdaptiveActions(first: @Composable () -> Unit, second: @Composable () -> Unit) {
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth < 280.dp || fontScale > 1.45f) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { first(); second() }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.weight(1f)) { first() }
                Box(Modifier.weight(1f)) { second() }
            }
        }
    }
}

@Composable
private fun HomeAction(label: String, icon: ImageVector, enabled: Boolean = true,
    color: Color = PineInk, ink: Color = TextOnPine, vertical: Boolean = false, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, shape = MaterialTheme.shapes.medium,
        colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = ink),
        modifier = Modifier.fillMaxWidth().heightIn(min = if (vertical) 80.dp else 72.dp),
        contentPadding = if (vertical) PaddingValues(horizontal = 12.dp, vertical = 10.dp) else PaddingValues(16.dp)) {
      if (vertical) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(icon, null, Modifier.size(24.dp))
            Text(label, textAlign = TextAlign.Center, style = MaterialTheme.typography.labelLarge)
        }
      } else {
        Icon(icon, null, Modifier.size(26.dp))
        Spacer(Modifier.width(12.dp))
        Text(label, modifier = Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelLarge)
      }
    }
}

@Composable
private fun TranscriptBlock(speaker: String, text: String, background: Color, foreground: Color, user: Boolean) {
    Surface(color = background, contentColor = foreground, shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(speaker, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
            Text(text, fontSize = if (user) 28.sp else 24.sp, lineHeight = if (user) 38.sp else 34.sp)
        }
    }
}

@Composable
private fun ReactiveVoiceOrb(state: VoiceState, audioLevel: Float, description: String) {
    val smoothed by animateFloatAsState(audioLevel.coerceIn(0f, 1f), tween(90), label = "voice level")
    val transition = rememberInfiniteTransition(label = "voice phase")
    val phase by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(1400), RepeatMode.Reverse), label = "voice phase")
    val activity = when (state) {
        VoiceState.LISTENING -> smoothed
        VoiceState.THINKING, VoiceState.SPEAKING -> 0.2f + phase * 0.2f
        VoiceState.IDLE -> 0f
    }
    Canvas(Modifier.size(88.dp).semantics { contentDescription = description }) {
        val center = Offset(size.width / 2, size.height / 2)
        repeat(7) { index ->
            val angle = index * (2f * PI.toFloat() / 7f)
            val distance = size.minDimension * (0.16f + activity * 0.03f)
            drawCircle(if (state == VoiceState.LISTENING) MarigoldDeep else PineInk,
                size.minDimension * (0.19f + activity * 0.04f), center + Offset(cos(angle), sin(angle)) * distance)
        }
        drawCircle(PineDeep, size.minDimension * 0.2f, center)
        drawCircle(TextOnPine, size.minDimension * 0.055f, center)
    }
}

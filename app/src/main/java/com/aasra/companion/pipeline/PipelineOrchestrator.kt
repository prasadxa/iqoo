package com.aasra.companion.pipeline

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Pipeline state driving the giant mic indicator on MainScreen. */
enum class VoiceState { IDLE, LISTENING, THINKING, SPEAKING }

/** Which execution path handled the last turn; shown as plain text, never a pill. */
enum class Route { LOCAL, CLOUD_LLM, CLOUD_VOICE_AGENT, UNAVAILABLE }

/** Run modes from PLAN.md section 1; auto-selected by connectivity elsewhere. */
enum class RunMode { OFFLINE, HYBRID, CLOUD }

enum class ReadinessStatus { LOADING, MISSING, FAILED, READY }
enum class LocalCapability { VAD, STT, LLM, TTS, WAKE_WORD, LANGUAGE_ID }

data class CapabilityReadiness(
    val status: ReadinessStatus,
    val installed: Boolean,
    val message: String,
)

/** Installed files and working engines are deliberately separate facts. STT/TTS use the selected language. */
data class LocalReadiness(
    val capabilities: Map<LocalCapability, CapabilityReadiness> = emptyMap(),
) {
    val installedCapabilities: Set<LocalCapability>
        get() = capabilities.filterValues { it.installed }.keys
    val missingCapabilities: Set<LocalCapability>
        get() = capabilities.filterValues { !it.installed && it.status == ReadinessStatus.MISSING }.keys
    val status: ReadinessStatus
        get() {
            val required = listOf(LocalCapability.STT, LocalCapability.LLM, LocalCapability.TTS)
                .map { capabilities[it]?.status ?: ReadinessStatus.LOADING }
            return when {
                ReadinessStatus.LOADING in required -> ReadinessStatus.LOADING
                ReadinessStatus.FAILED in required -> ReadinessStatus.FAILED
                ReadinessStatus.MISSING in required -> ReadinessStatus.MISSING
                else -> ReadinessStatus.READY
            }
        }
    val canAssist: Boolean get() = status == ReadinessStatus.READY
    val message: String get() = if (canAssist) "Local voice is ready." else
        capabilities.values.filter { it.status != ReadinessStatus.READY }
            .joinToString(" ") { it.message }.ifBlank { "Loading local voice models." }
}

internal class CaptureGate {
    @Volatile private var enabled = false
    @Volatile private var stopped = false
    fun setEnabled(value: Boolean) {
        if (value && !enabled) stopped = false
        enabled = value
    }
    fun stop() { stopped = true }
    fun resume() { stopped = false }
    fun allowed(mode: RunMode): Boolean = enabled && !stopped && mode != RunMode.CLOUD
}

internal fun cloudAllowed(mode: RunMode, configured: Boolean, networkAvailable: Boolean): Boolean =
    mode != RunMode.OFFLINE && configured && networkAvailable

data class ConversationTurn(
    val transcript: String = "",
    val answer: String = "",
    val route: Route = Route.LOCAL
)

/**
 * Contract for the voice loop. Track A owns this interface; the real
 * implementation arrives from the core-pipeline / engine tracks and is
 * swapped in via [com.aasra.companion.AasraApp] without touching UI code.
 */
interface PipelineOrchestrator {
    val voiceState: StateFlow<VoiceState>
    val turn: StateFlow<ConversationTurn>
    val runMode: StateFlow<RunMode>
    val audioLevel: StateFlow<Float>

    /** Tap Talk: start listening, or stop early and answer what was heard. */
    fun toggleTalk()

    /** Re-speak the last answer through TTS. */
    fun repeatLast()

    /** Speak an external status or reminder through the active voice output. */
    fun speak(text: String)

    /** Call the primary emergency contact + SMS all emergency contacts. */
    fun triggerSos()

    fun setRunMode(mode: RunMode)

    fun setAppForeground(foreground: Boolean)

    /** Service admission after setup, microphone permission and foreground-service startup. Defaults closed in Real. */
    fun setCaptureEnabled(enabled: Boolean) = Unit

    /** Stop active recording, generation, and playback without destroying the app container. */
    fun stop()
}

/**
 * Test double only. Never select this as a production fallback for missing models.
 */
class FakePipelineOrchestrator(
    private val scope: CoroutineScope
) : PipelineOrchestrator {

    private val _voiceState = MutableStateFlow(VoiceState.IDLE)
    override val voiceState: StateFlow<VoiceState> = _voiceState.asStateFlow()

    private val _turn = MutableStateFlow(ConversationTurn())
    override val turn: StateFlow<ConversationTurn> = _turn.asStateFlow()

    private val _runMode = MutableStateFlow(RunMode.HYBRID)
    override val runMode: StateFlow<RunMode> = _runMode.asStateFlow()

    private val _audioLevel = MutableStateFlow(0f)
    override val audioLevel: StateFlow<Float> = _audioLevel.asStateFlow()

    private var job: Job? = null

    override fun toggleTalk() {
        if (_voiceState.value == VoiceState.LISTENING) {
            finishListening()
        } else if (_voiceState.value == VoiceState.IDLE) {
            job?.cancel()
            job = scope.launch {
                _voiceState.value = VoiceState.LISTENING
                _turn.value = ConversationTurn()
            }
        }
        // THINKING/SPEAKING: taps are ignored (barge-in arrives with real impl).
    }

    private fun finishListening() {
        job?.cancel()
        job = scope.launch {
            _voiceState.value = VoiceState.THINKING
            delay(900)
            _turn.value = ConversationTurn(
                transcript = "Aasra, what time is it?",
                answer = "It is morning. Your engine is still on its way.",
                route = Route.LOCAL
            )
            _voiceState.value = VoiceState.SPEAKING
            delay(2_000)
            _voiceState.value = VoiceState.IDLE
        }
    }

    override fun repeatLast() {
        if (_turn.value.answer.isEmpty() || _voiceState.value != VoiceState.IDLE) return
        job?.cancel()
        job = scope.launch {
            _voiceState.value = VoiceState.SPEAKING
            delay(2_000)
            _voiceState.value = VoiceState.IDLE
        }
    }

    override fun speak(text: String) {
        if (text.isBlank()) return
        job?.cancel()
        _turn.value = ConversationTurn(answer = text, route = Route.LOCAL)
        job = scope.launch {
            _voiceState.value = VoiceState.SPEAKING
            delay(2_000)
            _voiceState.value = VoiceState.IDLE
        }
    }

    override fun triggerSos() {
        // Real impl (tools track): ACTION_CALL + SmsManager + location.
        // Stub keeps the last answer slot for the spoken confirmation.
        job?.cancel()
        _turn.value = ConversationTurn(
            transcript = "",
            answer = "SOS pressed. Calling for help.",
            route = Route.LOCAL
        )
    }

    override fun setRunMode(mode: RunMode) {
        _runMode.value = mode
    }

    override fun setAppForeground(foreground: Boolean) = Unit

    override fun stop() {
        job?.cancel()
        job = null
        _audioLevel.value = 0f
        _voiceState.value = VoiceState.IDLE
    }
}

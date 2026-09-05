package com.aasra.companion.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.aasra.companion.pipeline.PipelineOrchestrator
import com.aasra.companion.pipeline.RealAasraOrchestrator
import com.aasra.companion.pipeline.VoiceState
import com.aasra.companion.pipeline.LocalReadiness
import com.aasra.companion.prefs.UserPreferencesRepository
import com.aasra.companion.service.CloudVoiceBus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class MainViewModel(
    val orchestrator: PipelineOrchestrator,
    private val prefs: UserPreferencesRepository
) : ViewModel() {

    val voiceState = orchestrator.voiceState
    val turn = orchestrator.turn
    val runMode = orchestrator.runMode
    val audioLevel = orchestrator.audioLevel
    val cloudState = CloudVoiceBus.agentState
    val cloudUserText = CloudVoiceBus.userText
    val cloudAssistantText = CloudVoiceBus.assistantText
    val cloudAudioLevel = CloudVoiceBus.audioLevel
    val userPrefs = prefs.prefs
    val readiness: StateFlow<LocalReadiness?> =
        (orchestrator as? RealAasraOrchestrator)?.readiness ?: MutableStateFlow(null)

    /** Last vad_end_to_first_audio ms (PLAN 4.7); null until the first real turn. */
    val lastLatencyMs: StateFlow<Long?> =
        (orchestrator as? RealAasraOrchestrator)?.latencyMs
            ?: MutableStateFlow(null)

    init {
        // Settings persists the mode; the real orchestrator owns the loop, so
        // push the stored mode into it on every change (guarded: no loop, the
        // orchestrator only writes prefs when the value actually differs).
        viewModelScope.launch {
            prefs.prefs.collect { p ->
                if (orchestrator.runMode.value != p.runMode) {
                    orchestrator.setRunMode(p.runMode)
                }
            }
        }
        viewModelScope.launch {
            CloudVoiceBus.speakRequests.collect(orchestrator::speak)
        }
    }

    fun onTalk() = orchestrator.toggleTalk()

    fun onRepeat() = orchestrator.repeatLast()

    fun onSos() {
        orchestrator.triggerSos()
    }

}

class MainViewModelFactory(
    private val orchestrator: PipelineOrchestrator,
    private val prefs: UserPreferencesRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return MainViewModel(orchestrator, prefs) as T
    }
}

fun voiceStateLabel(state: VoiceState): String = when (state) {
    VoiceState.IDLE -> "Ready"
    VoiceState.LISTENING -> "Listening"
    VoiceState.THINKING -> "Thinking"
    VoiceState.SPEAKING -> "Speaking"
}

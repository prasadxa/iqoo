package com.aasra.companion

import android.app.Application
import android.util.Log
import com.aasra.cloud.CallMissedClient
import com.aasra.companion.pipeline.PipelineOrchestrator
import com.aasra.companion.pipeline.RealAasraOrchestrator
import com.aasra.companion.prefs.UserPreferencesRepository
import com.aasra.companion.service.VoiceService
import com.aasra.models.ModelPaths
import com.aasra.tools.ReminderReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Manual DI container. Chosen over Hilt so the skeleton compiles with zero
 * annotation processing while other tracks are still scaffolding; the
 * swap point is [AppContainer.orchestrator], which the engine tracks
 * replace with the real VAD -> STT -> LLM -> TTS loop.
 */
class AppContainer(app: Application) {
    val appScope = CoroutineScope(SupervisorJob())
    val prefs = UserPreferencesRepository(app)

    /** Cloud key from BuildConfig (local.properties -> "placeholder" fallback). */
    val callmissedApiKey: String = BuildConfig.CALLMISSED_API_KEY

    /**
     * Live cloud client, or null when no real key is configured (fresh
     * checkout with the "placeholder" fallback, or blank). Never crashes:
     * every cloud feature checks this for null and degrades to offline.
     */
    val cloud: CallMissedClient? = run {
        val key = callmissedApiKey.trim().removeSurrounding("\"")
        if (key.isBlank() || key == "placeholder") return@run null
        try {
            CallMissedClient(apiKey = key)
        } catch (_: Exception) {
            null
        }
    }

    // Engine failures are exposed by Real.readiness. Never simulate a successful
    // conversation or emergency action when native initialization fails.
    val orchestrator: PipelineOrchestrator = RealAasraOrchestrator(app, appScope, prefs, cloud)
}

class AasraApp : Application() {
    lateinit var container: AppContainer
        private set

    var isAppForeground: Boolean = false
        private set
    private val lifecycleSync = MutableStateFlow(0)

    fun setAppForeground(foreground: Boolean) {
        isAppForeground = foreground
        // The orchestrator's background flag enables wake-word capture. Only
        // an admitted microphone foreground service may enable that path.
        container.orchestrator.setAppForeground(
            foreground || !VoiceService.isRunning || !VoiceService.hasMicrophonePermission(this),
        )
        lifecycleSync.value += 1
    }

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.orchestrator.setAppForeground(true)
        ReminderReceiver.onFired = { reminder ->
            container.orchestrator.speak("Reminder: ${reminder.text}.")
        }
        container.appScope.launch(Dispatchers.Main.immediate) {
            combine(
                container.prefs.prefs.map { Triple(it.onboardingDone, it.runMode, it.language) }.distinctUntilChanged(),
                lifecycleSync,
            ) { _, _ -> Unit }.collectLatest {
                // Usage counters and voice changes must not restart a stopped session.
                VoiceService.synchronize(this@AasraApp)
            }
        }
        container.appScope.launch(Dispatchers.IO) {
            if (ModelPaths.adoptAdbPushedFiles(this@AasraApp) > 0) {
                (container.orchestrator as? RealAasraOrchestrator)?.reloadModels()
            }
        }
    }
}

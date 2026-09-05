package com.aasra.companion.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.aasra.companion.AasraApp
import com.aasra.companion.MainActivity
import com.aasra.companion.pipeline.RunMode
import com.aasra.companion.prefs.AppLanguage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

internal fun voiceStartAllowed(
    onboardingDone: Boolean,
    microphoneGranted: Boolean,
    appForeground: Boolean,
    serviceRunning: Boolean,
): Boolean = onboardingDone && microphoneGranted && (appForeground || serviceRunning)

/**
 * Keeps an admitted voice session alive with the screen off. Microphone
 * access requires completed onboarding, RECORD_AUDIO, and either a visible
 * Activity or an already-running microphone foreground service.
 *
 * Track C: also owns the Cloud-mode session ([CloudVoiceSession]). When the
 * user selects Cloud run-mode and a cloud client is configured, call
 * [startCloud] (or send [ACTION_START_CLOUD]); the session streams mic PCM
 * to the Managed Voice Agent and plays its replies. Leaving Cloud mode or
 * stopping the service tears the session down via [stopCloud].
 */
class VoiceService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var commandJob: Job? = null
    @Volatile private var foregroundStarted = false

    /** Active cloud session; null when in Offline/Hybrid mode or cloud is unconfigured. */
    @Volatile
    private var cloudSession: CloudVoiceSession? = null
    private var cloudStartJob: Job? = null
    private var cloudLanguage: AppLanguage? = null
    private val cloudGeneration = AtomicLong(0)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        activeService = this
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stoppedByUser = true
                startGeneration.incrementAndGet()
                stopCapture()
                return START_NOT_STICKY
            }
            ACTION_STOP_CLOUD -> {
                commandJob?.cancel()
                stopCloud()
                if (!foregroundStarted) stopSelf()
                return START_NOT_STICKY
            }
        }
        commandJob?.cancel()
        if (stoppedByUser || !hasMicrophonePermission(this)) {
            stopCapture()
            return START_NOT_STICKY
        }
        commandJob = serviceScope.launch(Dispatchers.Main.immediate) {
            val app = application as AasraApp
            try {
                val preferences = app.container.prefs.prefs.first()
                if (stoppedByUser || !voiceStartAllowed(
                        preferences.onboardingDone, hasMicrophonePermission(this@VoiceService),
                        app.isAppForeground, foregroundStarted,
                    )
                ) {
                    stopCapture()
                    return@launch
                }
                ensureForeground()
                foregroundStarted = true
                if (preferences.runMode != RunMode.CLOUD || (cloudLanguage != null && cloudLanguage != preferences.language)) stopCloud()
                app.container.orchestrator.setRunMode(preferences.runMode)
                app.container.orchestrator.setAppForeground(app.isAppForeground)
                app.container.orchestrator.setCaptureEnabled(preferences.runMode != RunMode.CLOUD)
                if (preferences.runMode == RunMode.CLOUD) startCloud()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("VoiceService", "Voice startup failed; retry from the foreground", e)
                stopCapture()
            }
        }
        // A killed microphone service must be re-admitted from a visible Activity.
        return START_NOT_STICKY
    }

    private fun stopCapture() {
        commandJob?.cancel()
        foregroundStarted = false
        stopCloud()
        (application as AasraApp).container.orchestrator.apply {
            setCaptureEnabled(false)
            setAppForeground(true)
            stop()
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        foregroundStarted = false
        if (activeService === this) activeService = null
        stopCloud()
        (application as? AasraApp)?.container?.orchestrator?.apply {
            setCaptureEnabled(false)
            setAppForeground(true)
            stop()
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    /**
     * Enter Cloud mode: open the Managed Voice Agent session when a cloud
     * client is configured. No key reports unavailable without changing the
     * user's saved mode.
     */
    @Synchronized
    fun startCloud() {
        val container = (application as AasraApp).container
        if (!foregroundStarted || !hasMicrophonePermission(this) || stoppedByUser) return
        if (cloudSession != null || cloudStartJob?.isActive == true) return
        container.orchestrator.setCaptureEnabled(false)
        container.orchestrator.stop()
        val cloud = container.cloud
        if (cloud == null) {
            CloudVoiceBus.requestSpeak(CloudVoiceSession.SPEAK_CLOUD_UNAVAILABLE)
            CloudVoiceBus.pushState("unavailable")
            return
        }
        val generation = cloudGeneration.incrementAndGet()
        cloudStartJob = serviceScope.launch {
            var session: CloudVoiceSession? = null
            var keepSession = false
            try {
                val preferences = container.prefs.prefs.first()
                synchronized(this@VoiceService) {
                    if (generation != cloudGeneration.get() || !foregroundStarted || stoppedByUser ||
                        !preferences.onboardingDone || preferences.runMode != RunMode.CLOUD ||
                        !hasMicrophonePermission(this@VoiceService)
                    ) return@launch
                    // Serialize admission with stopCloud so a cancelled startup cannot
                    // open a microphone after Stop has already returned.
                    val candidate = CloudVoiceSession(applicationContext, cloud)
                    session = candidate
                    if (candidate.start(preferences.language)) {
                        cloudLanguage = preferences.language
                        cloudSession = candidate
                        keepSession = true
                    }
                }
                if (keepSession) container.prefs.incrementCloudUsage()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("VoiceService", "Cloud voice startup failed", e)
                CloudVoiceBus.pushState("unavailable")
            } finally {
                if (!keepSession) session?.stop()
            }
        }
    }

    /** Leave Cloud mode (or service stop): clean disconnect, mic/player released. */
    @Synchronized
    fun stopCloud() {
        cloudGeneration.incrementAndGet()
        cloudStartJob?.cancel()
        cloudStartJob = null
        val session = cloudSession
        cloudSession = null
        cloudLanguage = null
        try {
            session?.stop()
        } catch (_: Exception) {
        }
    }

    private fun ensureForeground() {
        ensureForeground(buildNotification())
    }

    private fun ensureForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 30) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun ensureChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Aasra listening",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, VoiceService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Aasra is listening")
            .setContentText("Tap to open Aasra. Stop pauses microphone access.")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(openApp)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stop)
            .setOngoing(true)
            .build()
    }

    companion object {
        @Volatile private var activeService: VoiceService? = null
        private val _paused = MutableStateFlow(false)
        val paused = _paused.asStateFlow()
        private var stoppedByUser: Boolean
            get() = _paused.value
            set(value) { _paused.value = value }
        private val startGeneration = AtomicLong(0)
        internal val isRunning: Boolean get() = activeService?.foregroundStarted == true
        const val CHANNEL_ID = "aasra_voice"
        const val NOTIFICATION_ID = 11
        const val ACTION_START = "com.aasra.companion.action.START_VOICE"
        const val ACTION_STOP = "com.aasra.companion.action.STOP_VOICE"
        const val ACTION_START_CLOUD = "com.aasra.companion.action.START_CLOUD"
        const val ACTION_STOP_CLOUD = "com.aasra.companion.action.STOP_CLOUD"

        /** Explicitly resume the saved mode after the UI has obtained RECORD_AUDIO. */
        fun start(context: Context) {
            val app = context.applicationContext as AasraApp
            app.container.appScope.launch(Dispatchers.Main.immediate) {
                synchronize(app, userInitiated = true)
            }
        }

        /** Pause this process's session, not its saved mode. Only an explicit start resumes it. */
        fun stop(context: Context) {
            stoppedByUser = true
            startGeneration.incrementAndGet()
            val app = context.applicationContext as AasraApp
            app.container.orchestrator.setCaptureEnabled(false)
            app.container.orchestrator.setAppForeground(true)
            app.container.orchestrator.stop()
            activeService?.stopCapture()
            context.stopService(Intent(context, VoiceService::class.java))
        }

        /** Resume the saved mode after permission is granted; never changes preferences. */
        fun startCloud(context: Context) {
            start(context)
        }

        /** Leave Cloud mode without creating a service from the background. */
        @Suppress("UNUSED_PARAMETER")
        fun stopCloud(context: Context) {
            activeService?.apply {
                commandJob?.cancel()
                stopCloud()
            }
        }

        internal fun hasMicrophonePermission(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED

        internal suspend fun synchronize(context: Context, userInitiated: Boolean = false) {
            val app = context.applicationContext as AasraApp
            val generation = startGeneration.get()
            try {
                val preferences = app.container.prefs.prefs.first()
                if (generation != startGeneration.get()) return
                val microphoneGranted = hasMicrophonePermission(app)
                if (!preferences.onboardingDone || !microphoneGranted) {
                    app.container.orchestrator.setCaptureEnabled(false)
                    app.container.orchestrator.setAppForeground(true)
                    app.container.orchestrator.stop()
                    activeService?.stopCapture()
                    return
                }
                if (preferences.runMode != RunMode.CLOUD) stopCloud(app)
                if ((!userInitiated && stoppedByUser) || !voiceStartAllowed(
                        preferences.onboardingDone, microphoneGranted, app.isAppForeground, isRunning,
                    )
                ) return
                if (userInitiated) stoppedByUser = false
                val action = if (preferences.runMode == RunMode.CLOUD) ACTION_START_CLOUD else ACTION_START
                app.startForegroundService(Intent(app, VoiceService::class.java).setAction(action))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Activity visibility / while-in-use permission can change during dispatch.
                Log.w("VoiceService", "Voice start deferred until the app is foreground", e)
            }
        }
    }
}

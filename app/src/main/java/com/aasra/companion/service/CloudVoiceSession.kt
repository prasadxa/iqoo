package com.aasra.companion.service

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.aasra.audio.AudioPlayer
import com.aasra.audio.EchoController
import com.aasra.cloud.AgentEvent
import com.aasra.cloud.CallMissedClient
import com.aasra.cloud.CallMissedConfig
import com.aasra.cloud.CallMissedException
import com.aasra.cloud.CloudAction
import com.aasra.cloud.VoiceAgentSocket
import com.aasra.companion.prefs.AppLanguage
import com.aasra.data.AasraDatabase
import com.aasra.data.CaregiverStatsStore
import com.aasra.tools.AasraNotificationListener
import com.aasra.tools.ContactTools
import com.aasra.tools.ReminderTools
import com.aasra.tools.SmsTools
import com.aasra.tools.SosTools
import com.aasra.tools.SystemTools
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.ArrayDeque

/**
 * Track C: Cloud-mode session over the Managed Voice Agent
 * (`wss://api.callmissed.com/v2/voice/agent`, PLAN 5.4).
 *
 * Owns the full session lifecycle: socket (via [VoiceAgentSocket]'s public
 * API) -> mic streaming (AudioRecord 16 kHz) -> playback (AudioTrack 24 kHz)
 * -> tool dispatch. The UI observes [CloudVoiceBus] (no UI files touched).
 *
 * Event handling (verified live 2026-09-03):
 * - UserStartedSpeaking is accepted only outside speaker playback / echo tail.
 *   Speaker mode is half-duplex; an explicit talk tap can still interrupt.
 * - ConversationText -> [CloudVoiceBus] + broadcast (transcript UI).
 * - FunctionCallRequest -> device tool (tools/ APIs, unmodified), reply
 *   FunctionCallResponse within 30 s.
 * - LatencyReport -> logged + bus (demo slide).
 * - Warning -> logged, session continues. Error -> reconnect, except
 *   402/quota which stops permanently (no reconnect, no retry).
 */
object CloudVoiceBus {
    /** Existing talk control routes here without starting a second local mic. */
    @Volatile internal var interruptPlayback: (() -> Boolean)? = null
    const val ACTION_TRANSCRIPT = "com.aasra.companion.CLOUD_TRANSCRIPT"
    const val ACTION_STATE = "com.aasra.companion.CLOUD_STATE"
    const val ACTION_LATENCY = "com.aasra.companion.CLOUD_LATENCY"
    const val ACTION_SPEAK = "com.aasra.companion.CLOUD_SPEAK"

    /** Human-readable agent phase for the mic indicator. */
    private val _agentState = MutableStateFlow("idle")
    val agentState: StateFlow<String> = _agentState.asStateFlow()

    /** Latest transcript line (role + text already merged by [pushTranscript]). */
    private val _transcript = MutableStateFlow("")
    val transcript: StateFlow<String> = _transcript.asStateFlow()

    private val _userText = MutableStateFlow("")
    val userText: StateFlow<String> = _userText.asStateFlow()

    private val _assistantText = MutableStateFlow("")
    val assistantText: StateFlow<String> = _assistantText.asStateFlow()

    private val _audioLevel = MutableStateFlow(0f)
    val audioLevel: StateFlow<Float> = _audioLevel.asStateFlow()

    /** Raw LatencyReport payload for the demo slide. */
    private val _lastLatency = MutableStateFlow("")
    val lastLatency: StateFlow<String> = _lastLatency.asStateFlow()

    /**
     * Spoken-fallback requests: "cloud unavailable" and other non-technical
     * lines for the local TTS queue to speak (the service itself owns no TTS).
     * Also rebroadcast as [ACTION_SPEAK] for receivers outside the process.
     */
    private val _speakRequests = Channel<String>(Channel.BUFFERED)
    val speakRequests = _speakRequests.receiveAsFlow()

    fun pushTranscript(role: String, text: String) {
        _transcript.value = if (role.isBlank()) text else "$role: $text"
        when (role.lowercase()) {
            "user" -> _userText.value = text
            "assistant", "agent" -> _assistantText.value = text
        }
    }

    fun pushAudioLevel(level: Float) {
        _audioLevel.value = level.coerceIn(0f, 1f)
    }

    fun pushState(state: String) {
        _agentState.value = state
    }

    fun pushLatency(raw: String) {
        _lastLatency.value = raw
    }

    fun requestSpeak(line: String) {
        _speakRequests.trySend(line)
    }

    internal fun sendBroadcast(context: Context, intent: Intent) {
        try {
            intent.setPackage(context.packageName)
            context.sendBroadcast(intent)
        } catch (_: Exception) {
            // Broadcasts are best-effort; the StateFlows above stay authoritative.
        }
    }
}

class CloudVoiceSession(
    private val appContext: Context,
    private val cloud: CallMissedClient,
) {
    private val tag = "CloudVoice"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var socket: VoiceAgentSocket? = null
    @Volatile private var settingsJson: String = ""
    @Volatile private var running = false
    @Volatile private var fatalStop = false
    private var restartAttempt = 0

    private var mic: AudioRecord? = null
    private var micThread: Thread? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private val echoController = EchoController()
    private val player = AudioPlayer().apply {
        playbackListener = object : AudioPlayer.PlaybackListener {
            override fun onPlayingChanged(playing: Boolean) {
                echoController.playbackPlaying = playing
            }
        }
    }
    private var playerThread: Thread? = null
    private val playQueue = ArrayDeque<ByteArray>()
    private val playLock = Object()
    @Volatile private var playbackGeneration = 0L
    @Volatile private var discardAgentAudio = false
    private val tapInterrupt: () -> Boolean = ::interruptFromUi

    private var eventJob: Job? = null
    private var stateJob: Job? = null
    private var keepAliveJob: Job? = null
    @Volatile private var agentAudioDone = false

    /** Pending voice-confirms for call/SMS (PLAN 6.1): key -> first-seen time. */
    private var conversationLanguage = "hi"
    private val contactTools by lazy {
        ContactTools(appContext, AasraDatabase.get(appContext).contacts(), PhoneAccessBus::contactsAndCalls)
    }
    private val contactActions by lazy {
        ContactActionCoordinator(
            lookup = { name -> contactTools.findCandidates(name).also {
                if (it.isEmpty() && !contactTools.hasPhoneContactsPermission()) PhoneAccessBus.contactsAndCalls()
            } },
            execute = { kind, contact, message ->
                if (kind == "call") {
                    VoiceService.stop(appContext)
                    contactTools.placeCall(contact)
                } else {
                    val sms = SmsTools(appContext)
                    if (!sms.hasPermission()) PhoneAccessBus.sms()
                    sms.sendSms(contact, message, confirmed = true)
                }
            },
            language = { conversationLanguage },
        )
    }

    companion object {
        const val SPEAK_CLOUD_UNAVAILABLE =
            "Maaf kijiye, cloud se connection nahi ho pa raha hai. " +
                "Main phone par hi aapki madad karunga."
        private const val TOOL_TIMEOUT_MS = 25_000L
        private const val KEEPALIVE_MS = 25_000L
    }

    /** Start the session. Returns false when cloud is unreachable (caller stays local). */
    fun start(language: AppLanguage): Boolean {
        conversationLanguage = if (language == AppLanguage.ENGLISH) "en" else "hi"
        if (running) return true
        if (!isOnline()) {
            announceUnavailable("no network")
            return false
        }
        settingsJson = VoiceAgentSocket.defaultSettings(
            greeting = if (language == AppLanguage.ENGLISH) "Hello, I am Aasra, your AI companion." else "Namaste, main Aasra hoon, aapki AI saathi.",
            language = if (language == AppLanguage.ENGLISH) "en-IN" else "hi-IN",
        )
        running = true
        CloudVoiceBus.interruptPlayback = tapInterrupt
        fatalStop = false
        restartAttempt = 0
        openSocket()
        startMic()
        if (!running) return false
        startPlayer()
        watchEvents()
        watchState()
        startKeepAlive()
        CloudVoiceBus.pushState("connecting")
        return true
    }

    fun stop() {
        contactActions.cancel()
        if (!running) return
        running = false
        if (CloudVoiceBus.interruptPlayback === tapInterrupt) CloudVoiceBus.interruptPlayback = null
        try {
            socket?.disconnect()
        } catch (_: Exception) {
        }
        socket = null
        stopAudio()
        eventJob?.cancel()
        stateJob?.cancel()
        keepAliveJob?.cancel()
        scope.cancel()
        if (!fatalStop) {
            CloudVoiceBus.pushState("idle")
            broadcastState("idle")
        }
    }

    // -- socket lifecycle ----------------------------------------------------

    private fun openSocket() {
        val s = cloud.voiceAgent()
        socket = s
        try {
            s.connect(settingsJson)
        } catch (e: Exception) {
            Log.w(tag, "connect failed: ${e.message}")
        }
    }

    private fun watchEvents() {
        eventJob?.cancel()
        eventJob = scope.launch {
            val s = socket ?: return@launch
            s.events.collect { event ->
                if (!isActive || !running) return@collect
                when (event) {
                    is AgentEvent.Welcome -> CloudVoiceBus.pushState("connecting")
                    is AgentEvent.SettingsApplied -> run {
                        flushPlayback()
                        discardAgentAudio = false
                        restartAttempt = 0
                        CloudVoiceBus.pushState("listening")
                        broadcastState("listening")
                    }
                    is AgentEvent.UserStartedSpeaking -> run {
                        synchronized(playLock) {
                            // Late server VAD events are not proof of a human interrupt.
                            if (!echoController.shouldFeedVad(0f)) return@collect
                            flushPlayback()
                            CloudVoiceBus.pushState("listening")
                            broadcastState("listening")
                        }
                    }
                    is AgentEvent.ConversationText -> run {
                        if (event.role.equals("user", ignoreCase = true)) {
                            contactActions.recordUserResponse(event.text)
                        }
                        CloudVoiceBus.pushTranscript(event.role, event.text)
                        CloudVoiceBus.sendBroadcast(
                            appContext,
                            Intent(CloudVoiceBus.ACTION_TRANSCRIPT)
                                .putExtra("role", event.role)
                                .putExtra("text", event.text),
                        )
                    }
                    is AgentEvent.AgentThinking -> run {
                        CloudVoiceBus.pushState("thinking")
                        broadcastState("thinking")
                    }
                    is AgentEvent.AgentStartedSpeaking -> run {
                        synchronized(playLock) {
                            discardAgentAudio = false
                            echoController.ttsPlaying = true
                            agentAudioDone = false
                            CloudVoiceBus.pushState("speaking")
                            broadcastState("speaking")
                        }
                    }
                    is AgentEvent.AgentAudioDone -> run {
                        synchronized(playLock) {
                            // Last packet sent is not the last sample heard.
                            agentAudioDone = true
                            playLock.notifyAll()
                        }
                    }
                    is AgentEvent.PcmOut -> enqueuePcm(event.pcm24k)
                    is AgentEvent.FunctionCallRequest -> handleFunctionCall(event)
                    is AgentEvent.LatencyReport -> run {
                        Log.i(tag, "latency ttfb=${event.ttfbMs} total=${event.totalMs}")
                        CloudVoiceBus.pushLatency(event.raw)
                        CloudVoiceBus.sendBroadcast(
                            appContext,
                            Intent(CloudVoiceBus.ACTION_LATENCY)
                                .putExtra("raw", event.raw)
                                .putExtra("ttfb_ms", event.ttfbMs ?: -1.0)
                                .putExtra("total_ms", event.totalMs ?: -1.0),
                        )
                    }
                    is AgentEvent.Warning -> Log.w(tag, "server warning: ${event.message}")
                    is AgentEvent.Error -> onSocketError(event.message, event.willReconnect)
                }
            }
        }
    }

    private fun watchState() {
        stateJob?.cancel()
        stateJob = scope.launch {
            val s = socket ?: return@launch
            s.state.collect { state ->
                if (!isActive || !running) return@collect
                if (state.name == "FAILED") {
                    if (fatalStop) {
                        Log.i(tag, "fatal error: staying stopped (no reconnect on 402/quota)")
                        stop()
                    } else {
                        scheduleRestart()
                    }
                }
            }
        }
    }

    private fun onSocketError(message: String, willReconnect: Boolean) {
        val lower = message.lowercase()
        val fatal = !willReconnect ||
            "402" in message ||
            "quota" in lower ||
            "out of credit" in lower ||
            "out-of-credit" in lower
        if (fatal) {
            // 402/quota: stop permanently. Disconnect first so the socket's own
            // reconnect loop is cancelled (wantClose), then close the session.
            fatalStop = true
            Log.w(tag, "fatal cloud error, stopping: ${message.take(200)}")
            CloudVoiceBus.pushState("unavailable")
            broadcastState("unavailable")
            // Tear down cloud capture before any local (including platform) TTS
            // can consume the fallback request.
            stop()
            CloudVoiceBus.requestSpeak(SPEAK_CLOUD_UNAVAILABLE)
            CloudVoiceBus.sendBroadcast(
                appContext,
                Intent(CloudVoiceBus.ACTION_SPEAK).putExtra("text", SPEAK_CLOUD_UNAVAILABLE),
            )
        } else {
            Log.w(tag, "cloud error (socket reconnects): ${message.take(200)}")
        }
    }

    /** Session-level restart with exponential backoff for FAILED sockets. */
    private fun scheduleRestart() {
        scope.launch {
            restartAttempt++
            val waitMs = minOf(2_000L shl restartAttempt.coerceAtMost(4), 30_000L)
            Log.i(tag, "restarting cloud session in ${waitMs}ms (attempt $restartAttempt)")
            delay(waitMs)
            if (!running || fatalStop) return@launch
            try {
                socket?.disconnect()
            } catch (_: Exception) {
            }
            openSocket()
            watchEvents()
            watchState()
        }
    }

    private fun startKeepAlive() {
        keepAliveJob?.cancel()
        keepAliveJob = scope.launch {
            while (isActive && running) {
                delay(KEEPALIVE_MS)
                if (!running) return@launch
                try {
                    socket?.sendKeepAlive()
                } catch (_: Exception) {
                }
            }
        }
    }

    // -- mic (16 kHz PCM16 -> sendMicPcm) -------------------------------------

    private fun startMic() {
        val minBuf = try {
            AudioRecord.getMinBufferSize(
                16000,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
        } catch (_: Exception) {
            -1
        }
        if (minBuf <= 0) {
            Log.w(tag, "mic unavailable on this device")
            return
        }
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                16000,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf * 4,
            )
        } catch (e: SecurityException) {
            Log.w(tag, "mic permission missing")
            announceUnavailable("mic permission: ${e.message}")
            return
        } catch (e: Exception) {
            Log.w(tag, "mic init failed: ${e.message}")
            return
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            try {
                record.release()
            } catch (_: Exception) {
            }
            return
        }
        mic = record
        echoCanceler = if (AcousticEchoCanceler.isAvailable()) {
            runCatching { AcousticEchoCanceler.create(record.audioSessionId)?.apply { enabled = true } }.getOrNull()
        } else null
        noiseSuppressor = if (NoiseSuppressor.isAvailable()) {
            runCatching { NoiseSuppressor.create(record.audioSessionId)?.apply { enabled = true } }.getOrNull()
        } else null
        Log.i(tag, "audio effects: aec=${echoCanceler?.enabled == true} ns=${noiseSuppressor?.enabled == true}")
        val thread = Thread({
            try {
                record.startRecording()
            } catch (e: Exception) {
                Log.w(tag, "mic start failed: ${e.message}")
                return@Thread
            }
            // 20 ms frames at 16 kHz mono PCM16 = 320 samples = 640 bytes.
            val frame = ByteArray(640)
            while (running && !Thread.currentThread().isInterrupted) {
                val feedBeforeRead = echoController.shouldFeedVad(0f)
                val n = try {
                    record.read(frame, 0, frame.size)
                } catch (_: Exception) {
                    break
                }
                if (n <= 0) continue
                val level = pcmLevel(frame, n)
                CloudVoiceBus.pushAudioLevel(level)
                try {
                    synchronized(playLock) {
                        if (!running) return@Thread
                        // Preserve server audio timing, but never uplink our speaker,
                        // including reads which straddle the end of the echo tail.
                        socket?.sendMicPcm(echoController.microphonePcm(frame, n, feedBeforeRead))
                    }
                } catch (_: Exception) {
                }
            }
        }, "aasra-cloud-mic")
        micThread = thread
        thread.start()
    }

    // -- playback (binary PCM16-24k -> AudioTrack) -----------------------------

    private fun startPlayer() {
        val thread = Thread({
            while (running && !Thread.currentThread().isInterrupted) {
                val queued = synchronized(playLock) {
                    if (playQueue.isEmpty() && running) {
                        try {
                            playLock.wait(20)
                        } catch (_: InterruptedException) {
                            Thread.currentThread().interrupt()
                        }
                    }
                    if (!running || playQueue.isEmpty()) null else playQueue.removeFirst() to playbackGeneration
                }
                if (queued != null) {
                    val (chunk, generation) = queued
                    try {
                        val samples = ShortArray(chunk.size / 2) { i ->
                            ((chunk[i * 2].toInt() and 0xff) or (chunk[i * 2 + 1].toInt() shl 8)).toShort()
                        }
                        player.play(samples) { running && generation == playbackGeneration }
                    } catch (error: Exception) {
                        Log.w(tag, "audio playback failed: ${error.message}")
                        flushPlayback()
                    }
                }
                synchronized(playLock) {
                    if (running && agentAudioDone && playQueue.isEmpty() && !player.isPlaying &&
                        echoController.ttsPlaying
                    ) {
                        echoController.ttsPlaying = false
                        CloudVoiceBus.pushState("listening")
                        broadcastState("listening")
                    }
                }
            }
        }, "aasra-cloud-player")
        playerThread = thread
        thread.start()
    }

    private fun pcmLevel(bytes: ByteArray, length: Int): Float {
        if (length < 2) return 0f
        var sum = 0.0
        var count = 0
        var index = 0
        while (index + 1 < length) {
            val sample = ((bytes[index].toInt() and 0xff) or (bytes[index + 1].toInt() shl 8)).toShort()
            val value = sample / 32768.0
            sum += value * value
            count++
            index += 2
        }
        return (kotlin.math.sqrt(sum / count) / 0.12).toFloat().coerceIn(0f, 1f)
    }

    private fun enqueuePcm(pcm24k: ByteArray) {
        if (pcm24k.isEmpty() || !running) return
        synchronized(playLock) {
            if (discardAgentAudio || !running) return
            // PCM can arrive before the speaking hint. Gate before queueing it.
            echoController.ttsPlaying = true
            // Bound the queue (~6 s at 24 kHz mono 16-bit) so a stall can't OOM.
            while (playQueue.size > 300) playQueue.removeFirst()
            playQueue.addLast(pcm24k)
            playLock.notifyAll()
        }
    }

    /** Barge-in: drop queued + in-flight audio immediately. */
    private fun flushPlayback() {
        synchronized(playLock) {
            playbackGeneration++
            playQueue.clear()
            player.flush()
            agentAudioDone = true
            echoController.ttsPlaying = false
            playLock.notifyAll()
        }
    }

    private fun interruptFromUi(): Boolean = synchronized(playLock) {
        if (!running) return false
        // There is no documented client cancel message. Drop the current reply's
        // remaining PCM until the next AgentStartedSpeaking; new mic input can
        // reach server VAD after the acoustic tail. A tap never starts a local mic.
        discardAgentAudio = true
        flushPlayback()
        CloudVoiceBus.pushState("listening")
        broadcastState("listening")
        true
    }

    private fun stopAudio() {
        micThread?.interrupt()
        playerThread?.interrupt()
        flushPlayback()
        try {
            mic?.stop()
        } catch (_: Exception) {
        }
        runCatching { echoCanceler?.release() }
        runCatching { noiseSuppressor?.release() }
        echoCanceler = null
        noiseSuppressor = null
        try {
            mic?.release()
        } catch (_: Exception) {
        }
        player.release()
        mic = null
        micThread = null
        playerThread = null
    }

    // -- function calls -------------------------------------------------------

    private fun handleFunctionCall(req: AgentEvent.FunctionCallRequest) {
        scope.launch {
            val content = withTimeoutOrNull(TOOL_TIMEOUT_MS) {
                withContext(Dispatchers.IO) { runTool(req.name, req.arguments) }
            } ?: jsonContent(false, "Sorry, that took too long. Please try again.")
            try {
                socket?.sendFunctionCallResponse(req.id, req.name, content)
            } catch (e: Exception) {
                Log.w(tag, "FunctionCallResponse send failed: ${e.message}")
            }
        }
    }

    /**
     * Dispatch one cloud function call to the existing tools/ APIs.
     * call_contact / send_sms keep the PLAN 6.1 voice-confirm: the first
     * request asks the agent to confirm with the user. A repeated request only
     * executes after a later user transcript contains an explicit yes.
     */
    private suspend fun runTool(name: String, argumentsJson: String): String {
        val args = try {
            JSONObject(argumentsJson)
        } catch (_: Exception) {
            JSONObject()
        }
        return try {
            when (name) {
                "get_time" -> run {
                    val r = SystemTools(appContext).getTime("hi")
                    jsonContent(r.ok, r.spokenReply)
                }
                "get_date" -> run {
                    val r = SystemTools(appContext).getDate("hi")
                    jsonContent(r.ok, r.spokenReply)
                }
                "set_volume" -> run {
                    var level = args.optInt("level", 5)
                    if (level > 10) level /= 10
                    val r = SystemTools(appContext).setVolume(level)
                    jsonContent(r.ok, r.spokenReply)
                }
                "flashlight" -> run {
                    val on = optBool(args, "on", false)
                    val r = SystemTools(appContext).setFlashlight(on)
                    jsonContent(r.ok, r.spokenReply)
                }
                "call_contact" -> run {
                    val spokenName = args.optString("name")
                    val r = contactActions.request(ContactActionCoordinator.Request("call", spokenName))
                    if (r.pending) jsonNeedsConfirm(r.spoken) else jsonContent(r.ok, r.spoken)
                }
                "send_sms" -> run {
                    val spokenName = args.optString("name")
                    val message = args.optString("message")
                    val r = contactActions.request(ContactActionCoordinator.Request("sms", spokenName, message))
                    if (r.pending) jsonNeedsConfirm(r.spoken) else jsonContent(r.ok, r.spoken)
                }
                "set_reminder" -> run {
                    val text = args.optString("text")
                    val atMillis = parseTimeToMillis(args.optString("time"))
                    if (text.isBlank() || atMillis == null) {
                        return jsonContent(false, "Please tell me what to remind you about and when.")
                    }
                    val db = AasraDatabase.get(appContext)
                    val repeat = args.optString("repeat", "once")
                    val r = ReminderTools(appContext, db.reminders())
                        .setReminder(text, atMillis, repeat)
                    jsonContent(r.ok, r.spokenReply, r.data?.let { mapOf("id" to it) })
                }
                "list_reminders" -> run {
                    val db = AasraDatabase.get(appContext)
                    val r = ReminderTools(appContext, db.reminders()).listReminders()
                    jsonContent(r.ok, r.spokenReply, r.data?.let { mapOf("reminders" to it) })
                }
                "cancel_reminder" -> run {
                    val id = args.optString("id").toLongOrNull()
                        ?: args.optLong("id", -1L).takeIf { it >= 0 }
                        ?: return jsonContent(false, "Which reminder should I cancel?")
                    val db = AasraDatabase.get(appContext)
                    val r = ReminderTools(appContext, db.reminders()).cancelReminder(id)
                    jsonContent(r.ok, r.spokenReply)
                }
                "sos" -> run {
                    val db = AasraDatabase.get(appContext)
                    val r = SosTools(
                        appContext,
                        db.contacts(),
                        SmsTools(appContext),
                        CaregiverStatsStore(appContext),
                        ContactTools(appContext, db.contacts()),
                    ).sos()
                    jsonContent(r.ok, r.spokenReply)
                }
                "read_notifications" -> run {
                    val limit = args.optInt("limit", 3).coerceIn(1, 5)
                    val aloud = AasraNotificationListener.aloudText(limit, "hi")
                    if (aloud.isBlank()) {
                        jsonContent(true, "Abhi koi naya notification nahi hai.")
                    } else {
                        jsonContent(true, aloud)
                    }
                }
                "web_search" -> run {
                    val query = args.optString("query")
                    if (query.isBlank()) return jsonContent(false, "What should I look up?")
                    try {
                        val hits = cloud.search.search(query, hl = "hi")
                        if (hits.isEmpty()) {
                            jsonContent(true, "Is baare mein kuch taaza jaankari nahi mili.")
                        } else {
                            val summary = hits.take(3).joinToString(" ") { it.snippet.ifBlank { it.title } }
                            jsonContent(true, summary.take(600), mapOf("sources" to hits.take(3).joinToString("; ") { it.url }))
                        }
                    } catch (e: CallMissedException) {
                        if (e.action == CloudAction.STOP) {
                            jsonContent(false, "Maaf kijiye, cloud se connection nahi ho pa raha hai.")
                        } else {
                            jsonContent(false, "Search abhi kaam nahi kar raha hai. Please thodi der baad poochhiye.")
                        }
                    }
                }
                "escalate" -> jsonContent(true, "Already on the cloud voice path; handling directly.")
                else -> jsonContent(false, "Unknown tool $name; please answer without tools.")
            }
        } catch (e: Exception) {
            Log.w(tag, "tool $name failed: ${e.message}")
            jsonContent(false, "Maaf kijiye, ye kaam nahi ho paaya. Please dobara kahiye.")
        }
    }

    // -- helpers --------------------------------------------------------------

    private fun isOnline(): Boolean {
        return try {
            val cm = appContext.getSystemService(ConnectivityManager::class.java) ?: return false
            val net = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(net) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } catch (_: Exception) {
            false
        }
    }

    private fun announceUnavailable(reason: String) {
        Log.i(tag, "cloud unavailable ($reason): staying on local path")
        if (running) {
            fatalStop = true
            stop()
        }
        CloudVoiceBus.pushState("unavailable")
        broadcastState("unavailable")
        CloudVoiceBus.requestSpeak(SPEAK_CLOUD_UNAVAILABLE)
        CloudVoiceBus.sendBroadcast(
            appContext,
            Intent(CloudVoiceBus.ACTION_SPEAK).putExtra("text", SPEAK_CLOUD_UNAVAILABLE),
        )
    }

    private fun broadcastState(state: String) {
        CloudVoiceBus.sendBroadcast(
            appContext,
            Intent(CloudVoiceBus.ACTION_STATE).putExtra("state", state),
        )
    }

    private fun jsonContent(ok: Boolean, spoken: String, extra: Map<String, String>? = null): String {
        val o = JSONObject()
        o.put("ok", ok)
        o.put("spoken", spoken)
        extra?.forEach { (k, v) -> o.put(k, v) }
        return o.toString()
    }

    private fun jsonNeedsConfirm(instruction: String): String {
        val o = JSONObject()
        o.put("ok", false)
        o.put("needs_confirmation", true)
        o.put("spoken", instruction)
        o.put("instruction", "Ask the spoken question, then wait for the user's answer. Call the same function again with the original name and unchanged message. The app validates selection and confirmation. Do not claim an action succeeded before ok is true.")
        return o.toString()
    }

    private fun optBool(args: JSONObject, key: String, default: Boolean): Boolean {
        if (args.isNull(key)) return default
        return try {
            args.optBoolean(key, default)
        } catch (_: Exception) {
            when (args.optString(key).lowercase()) {
                "true", "1", "yes", "on" -> true
                "false", "0", "no", "off" -> false
                else -> default
            }
        }
    }

    /** Accepts epoch millis, ISO instant, or local ISO date-time. Null = unparseable/past-adjacent free. */
    private fun parseTimeToMillis(raw: String): Long? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        s.toLongOrNull()?.let { return it }
        return try {
            java.time.Instant.parse(s).toEpochMilli()
        } catch (_: Exception) {
            try {
                LocalDateTime.parse(s).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            } catch (_: Exception) {
                null
            }
        }
    }
}

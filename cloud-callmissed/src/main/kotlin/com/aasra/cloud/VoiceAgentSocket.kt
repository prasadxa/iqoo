package com.aasra.cloud

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString

private val wsJson = Json { ignoreUnknownKeys = true }

enum class AgentConnectionState { IDLE, CONNECTING, WAITING_SETTINGS, LIVE, RECONNECTING, CLOSED, FAILED }

/** Inbound agent events (managed-voice-agent docs + PLAN 5.4). Binary PCM16-24k audio arrives as [PcmOut]. */
sealed interface AgentEvent {
    data object Welcome : AgentEvent
    data object SettingsApplied : AgentEvent
    /** Mic PCM16-16k should be streamed now; playback buffer already flushed upstream. */
    data object UserStartedSpeaking : AgentEvent
    data class ConversationText(val role: String, val text: String) : AgentEvent
    /** One entry per element of the server's `functions[]` array; `arguments` is a JSON string. */
    data class FunctionCallRequest(val id: String, val name: String, val arguments: String) : AgentEvent
    data class LatencyReport(val ttfbMs: Double?, val totalMs: Double?, val raw: String) : AgentEvent
    data class PcmOut(val pcm24k: ByteArray) : AgentEvent
    /** Server is working on the turn. UI hint only. */
    data object AgentThinking : AgentEvent
    /** First audio of a turn; carries turn latency when the server measured it. */
    data class AgentStartedSpeaking(val latencyMs: Double?) : AgentEvent
    /** Last audio chunk SENT for this turn (playback buffer may still be draining). */
    data object AgentAudioDone : AgentEvent
    /** Non-fatal server notice (e.g. tool timeout, model kept). Session continues. */
    data class Warning(val message: String) : AgentEvent
    data class Error(val message: String, val willReconnect: Boolean) : AgentEvent
}

/**
 * Managed Voice Agent WebSocket (PLAN 5.4): `wss://.../v2/voice/agent` with
 * header `Authorization: Token cm_...` (Token, NOT Bearer).
 *
 * Flow: connect → wait `Welcome` → send `Settings` (verbatim below) →
 * on `SettingsApplied` stream mic PCM16-16k binary frames, play received
 * binary PCM16-24k frames. `FunctionCallResponse` must go out within 30 s.
 * Call [checkModelEligibility] once at startup (GET /api/v1/voice/models).
 */
class VoiceAgentSocket(
    private val http: OkHttpClient,
    private val apiKey: String,
    private val wsUrl: String = CallMissedConfig.WS_AGENT_URL,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(AgentConnectionState.IDLE)
    val state: StateFlow<AgentConnectionState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<AgentEvent>(extraBufferCapacity = 128)
    val events: SharedFlow<AgentEvent> = _events.asSharedFlow()

    @Volatile private var ws: WebSocket? = null
    @Volatile private var settingsJson: String = defaultSettings()
    @Volatile private var wantClose = false
    private var reconnectAttempt = 0

    /** Connect (or reconnect). [settingsOverride] for tests; default is PLAN 5.4 verbatim. */
    fun connect(settingsOverride: String? = null) {
        settingsOverride?.let { settingsJson = it }
        wantClose = false
        _state.value = AgentConnectionState.CONNECTING
        // Eligibility is checked explicitly via checkModelEligibility() at startup;
        // it stays out of the hot connect path so a slow check never blocks audio.
        openSocket()
    }

    /** Stream one mic frame: PCM16 mono 16 kHz. No-op unless LIVE. */
    fun sendMicPcm(frame16k: ByteArray): Boolean {
        if (_state.value != AgentConnectionState.LIVE) return false
        return ws?.send(frame16k.toByteString(0, frame16k.size)) ?: false
    }

    /**
     * Reply to a [AgentEvent.FunctionCallRequest]. Must be sent within 30 s
     * (server docs) — run the device tool first, then call this with its
     * result. Wire shape: `{type, id, name, content}`; `content` is a JSON
     * string. A late tool does not hang the call (server emits `Warning`).
     */
    fun sendFunctionCallResponse(callId: String, name: String, content: String): Boolean {
        val msg = buildJsonObject {
            put("type", "FunctionCallResponse")
            put("id", callId)
            put("name", name)
            put("content", content)
        }.toString()
        return ws?.send(msg) ?: false
    }

    /** Hold an idle socket open (server docs: `KeepAlive`). */
    fun sendKeepAlive(): Boolean =
        ws?.send("""{"type":"KeepAlive"}""") ?: false

    /** Append to the live session's system prompt (server docs: `UpdatePrompt`). */
    fun sendUpdatePrompt(appendText: String): Boolean {
        val msg = buildJsonObject {
            put("type", "UpdatePrompt")
            put("text", appendText)
        }.toString()
        return ws?.send(msg) ?: false
    }

    fun disconnect() {
        wantClose = true
        _state.value = AgentConnectionState.CLOSED
        ws?.close(1000, "client bye")
        ws = null
    }

    // -- internals ---------------------------------------------------------

    private fun openSocket() {
        val req = Request.Builder()
            .url(wsUrl)
            .header("Authorization", "Token $apiKey") // NOTE: Token, not Bearer, on this endpoint.
            .build()
        ws = http.newWebSocket(req, listener)
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            reconnectAttempt = 0
            _state.value = AgentConnectionState.WAITING_SETTINGS
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            handleText(text)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            // Binary frames = TTS PCM16 24 kHz → AudioTrack.
            _events.tryEmit(AgentEvent.PcmOut(bytes.toByteArray()))
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (wantClose) return
            val body = try { response?.body?.string().orEmpty() } catch (_: Exception) { "" }
            val code = response?.code ?: -1
            val errCode = CloudErrors.parseCode(body)
            // 402 / quota: stop, never reconnect (quota burn + pointless).
            val fatal = code == 402 || (code == 429 && errCode == CloudErrors.CODE_QUOTA_EXCEEDED)
            _events.tryEmit(AgentEvent.Error(t.message ?: body.take(300), willReconnect = !fatal && !wantClose))
            if (fatal || wantClose) {
                _state.value = AgentConnectionState.FAILED
                return
            }
            scheduleReconnect()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (!wantClose && _state.value != AgentConnectionState.CLOSED) scheduleReconnect()
        }
    }

    private fun handleText(text: String) {
        val type = try {
            wsJson.parseToJsonElement(text).jsonObject["type"]?.jsonPrimitive?.contentOrNull
        } catch (_: Exception) { null } ?: return
        Log.d(TAG, "event=$type bytes=${text.length}")
        when (type) {
            "Welcome" -> {
                _events.tryEmit(AgentEvent.Welcome)
                ws?.send(settingsJson)
            }
            "SettingsApplied" -> {
                _state.value = AgentConnectionState.LIVE
                _events.tryEmit(AgentEvent.SettingsApplied)
            }
            "UserStartedSpeaking" -> {
                // Caller flushes the playback buffer immediately (barge-in).
                _events.tryEmit(AgentEvent.UserStartedSpeaking)
            }
            "ConversationText" -> {
                try {
                    val o = wsJson.parseToJsonElement(text).jsonObject
                    val role = o["role"]?.jsonPrimitive?.contentOrNull
                        ?: o["speaker"]?.jsonPrimitive?.contentOrNull
                        ?: ""
                    val content = o["text"]?.jsonPrimitive?.contentOrNull
                        ?: o["content"]?.jsonPrimitive?.contentOrNull
                        ?: o["transcript"]?.jsonPrimitive?.contentOrNull
                        ?: ""
                    Log.d(TAG, "conversation role=$role chars=${content.length}")
                    _events.tryEmit(AgentEvent.ConversationText(role, content))
                } catch (_: Exception) { }
            }
            "FunctionCallRequest" -> {
                // Wire shape (server docs): {type, functions:[{id, name,
                // arguments (JSON STRING), client_side}]}. Emit one event per
                // function; keep the legacy top-level fallback for tolerance.
                try {
                    val o = wsJson.parseToJsonElement(text).jsonObject
                    val fns = try {
                        o["functions"]?.let {
                            wsJson.parseToJsonElement(it.toString()).jsonArray
                        }
                    } catch (_: Exception) { null }
                    if (fns != null) {
                        for (fnEl in fns) {
                            try {
                                val fn = fnEl.jsonObject
                                _events.tryEmit(
                                    AgentEvent.FunctionCallRequest(
                                        fn["id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                                        fn["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                                        fn["arguments"]?.jsonPrimitive?.contentOrNull
                                            ?: (fn["arguments"]?.toString().orEmpty()),
                                    ),
                                )
                            } catch (_: Exception) { }
                        }
                    } else {
                        _events.tryEmit(
                            AgentEvent.FunctionCallRequest(
                                o["id"]?.jsonPrimitive?.contentOrNull
                                    ?: o["call_id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                                o["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                                o["arguments"]?.jsonPrimitive?.contentOrNull
                                    ?: (o["arguments"]?.toString().orEmpty()),
                            ),
                        )
                    }
                } catch (_: Exception) { }
            }
            "LatencyReport" -> {
                try {
                    val o = wsJson.parseToJsonElement(text).jsonObject
                    _events.tryEmit(
                        AgentEvent.LatencyReport(
                            o["ttfb_ms"]?.jsonPrimitive?.doubleOrNull,
                            o["total_ms"]?.jsonPrimitive?.doubleOrNull,
                            text.take(500),
                        ),
                    )
                } catch (_: Exception) { }
            }
            "Error" -> {
                _events.tryEmit(AgentEvent.Error(text.take(500), willReconnect = true))
                scheduleReconnect()
            }
            "AgentThinking" -> _events.tryEmit(AgentEvent.AgentThinking)
            "AgentAudioDone" -> _events.tryEmit(AgentEvent.AgentAudioDone)
            "AgentStartedSpeaking" -> {
                val latency = try {
                    wsJson.parseToJsonElement(text).jsonObject["latency_ms"]
                        ?.jsonPrimitive?.doubleOrNull
                } catch (_: Exception) { null }
                _events.tryEmit(AgentEvent.AgentStartedSpeaking(latency))
            }
            "Warning" -> _events.tryEmit(AgentEvent.Warning(text.take(500)))
            // Unknown future events are ignored (wsJson already ignores unknown keys).
        }
    }

    private fun scheduleReconnect() {
        if (wantClose) return
        _state.value = AgentConnectionState.RECONNECTING
        val attempt = ++reconnectAttempt
        scope.launch {
            delay(minOf(1000L * attempt + CloudErrors.retryDelayMs(), 10_000L))
            if (!wantClose) {
                _state.value = AgentConnectionState.CONNECTING
                openSocket()
            }
        }
    }

    companion object {
        private const val TAG = "VoiceAgentSocket"

        /**
         * `Settings` message verbatim from PLAN 5.4, plus the `tools` array
         * carrying the same tool schema (PLAN 5.4: cloud mode can also call
         * contacts, set reminders, etc.).
         */
        fun defaultSettings(
            systemPrompt: String = AasraPrompts.SYSTEM,
            greeting: String = AasraPrompts.AGENT_GREETING,
            language: String = AasraPrompts.AGENT_LANGUAGE,
            llmModel: String = CallMissedConfig.CHAT_MODEL_PRIMARY,
        ): String = buildJsonObject {
            put("type", "Settings")
            put("audio", buildJsonObject {
                put("input", buildJsonObject {
                    put("encoding", "linear16")
                    put("sample_rate", CallMissedConfig.STT_SAMPLE_RATE)
                })
                put("output", buildJsonObject {
                    put("encoding", "linear16")
                    put("sample_rate", CallMissedConfig.TTS_SAMPLE_RATE)
                })
            })
            put("agent", buildJsonObject {
                put("prompt", systemPrompt)
                put("greeting", SpeechInput.normalize(greeting))
                put("language", language)
                put("llm", buildJsonObject {
                    put("model", llmModel)
                    put("temperature", CallMissedConfig.CHAT_TEMPERATURE)
                })
                put("stt", buildJsonObject { put("model", CallMissedConfig.STT_MODEL) })
                put("tts", buildJsonObject {
                    put("model", CallMissedConfig.TTS_MODEL)
                    put("voice", CallMissedConfig.TTS_VOICE)
                })
            })
            put("tags", wsJson.parseToJsonElement("""["aasra"]"""))
            put("tools", AasraTools.schemas())
        }.toString()

        /**
         * GET /api/v1/voice/models once at startup (Bearer auth). Real wire
         * shape (verified 2026-09-03): `{turn_budget_ms, llm:[...], stt:[...],
         * tts:[...]}` where each entry is `{id, eligible (bool),
         * verdict (eligible|too_slow|unsupported|unmeasured), ...}`.
         *
         * Rule: the wanted LLM must be `eligible`. STT/TTS entries that are
         * `unmeasured` are tolerated (selectable, just not benchmarked yet);
         * `unsupported` (e.g. maintenance) fails the check. Unknown shapes
         * fail open — the WS handshake is authoritative, this never blocks
         * startup.
         */
        suspend fun checkModelEligibility(
            http: OkHttpClient,
            apiKey: String,
            url: String = CallMissedConfig.VOICE_MODELS_URL,
            llmModel: String = CallMissedConfig.CHAT_MODEL_PRIMARY,
            sttModel: String = CallMissedConfig.STT_MODEL,
            ttsModel: String = CallMissedConfig.TTS_MODEL,
        ): Boolean = kotlinx.coroutines.withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $apiKey")
                .get()
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext false
                val raw = resp.body?.string().orEmpty()
                try {
                    val root = wsJson.parseToJsonElement(raw).jsonObject
                    fun entry(section: String, id: String): kotlinx.serialization.json.JsonObject? {
                        val arr = try {
                            root[section]?.let {
                                wsJson.parseToJsonElement(it.toString()).jsonArray
                            }
                        } catch (_: Exception) { null } ?: return null
                        for (el in arr) {
                            try {
                                val o = el.jsonObject
                                if (o["id"]?.jsonPrimitive?.contentOrNull == id) return o
                            } catch (_: Exception) { }
                        }
                        return null
                    }
                    // Wanted LLM must be measured-eligible (latency gate).
                    val llm = entry("llm", llmModel) ?: return@withContext true
                    val llmEligible = try {
                        llm["eligible"]?.jsonPrimitive?.contentOrNull == "true"
                    } catch (_: Exception) { true }
                    if (!llmEligible) return@withContext false
                    // STT/TTS: only a hard `unsupported` verdict fails.
                    for ((section, id) in listOf("stt" to sttModel, "tts" to ttsModel)) {
                        val verdict = try {
                            entry(section, id)
                                ?.get("verdict")?.jsonPrimitive?.contentOrNull
                        } catch (_: Exception) { null }
                        if (verdict == "unsupported") return@withContext false
                    }
                    true
                } catch (_: Exception) {
                    true // unparseable: don't block startup; WS handshake is authoritative
                }
            }
        }
    }
}

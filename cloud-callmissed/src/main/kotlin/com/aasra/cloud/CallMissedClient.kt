package com.aasra.cloud

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Single client for the CallMissed cloud layer (PLAN 5).
 * Owns one OkHttpClient and wires chat / STT / TTS / search / voice-agent
 * plus the shared [UsageCounter]. REST uses Bearer auth; the voice-agent
 * socket uses `Token` auth internally (see [VoiceAgentSocket]).
 *
 * @param apiKey defaults to BuildConfig.CALLMISSED_API_KEY — never hardcode one.
 */
class CallMissedClient(
    val apiKey: String = CallMissedConfig.requireKey(),
    http: OkHttpClient? = null,
    val baseUrl: String = CallMissedConfig.BASE_URL,
    val usage: UsageCounter = UsageCounter(),
) {
    val http: OkHttpClient = http ?: defaultHttp()

    val chat = ChatApi(this.http, apiKey, baseUrl, usage)
    val stt = SttApi(this.http, apiKey, baseUrl, usage)
    val tts = TtsApi(this.http, apiKey, baseUrl, usage)
    val search = SearchApi(this.http, apiKey, baseUrl, usage)
    val sessions = VoiceSessionsApi(this.http, apiKey, baseUrl, usage)

    /** New managed voice-agent socket (caller owns its lifecycle). */
    fun voiceAgent(): VoiceAgentSocket =
        VoiceAgentSocket(http, apiKey)

    /** Startup eligibility check: GET /api/v1/voice/models (PLAN 5.4). */
    suspend fun checkVoiceEligibility(): Boolean =
        VoiceAgentSocket.checkModelEligibility(http, apiKey)

    fun close() {
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
    }

    companion object {
        fun defaultHttp(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS) // SSE streams stay open
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}

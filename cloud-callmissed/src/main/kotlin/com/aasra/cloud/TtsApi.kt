package com.aasra.cloud

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * POST /v1/audio/speech: Sonic 3.6 with Preeti only,
 * language hi/en, speed 0.9, response_format pcm,
 * speech_sample_rate 24000. Returns raw PCM16 mono bytes for AudioTrack.
 *
 * Cloud fallback policy lives in app/: use this only when the phone is online
 * and local Kokoro/Piper is unavailable.
 */
class TtsApi(
    private val http: OkHttpClient,
    private val apiKey: String,
    private val baseUrl: String = CallMissedConfig.BASE_URL,
    private val usage: UsageCounter = UsageCounter(),
) {
    enum class Voice(val id: String) {
        PREETI(CallMissedConfig.TTS_VOICE),
    }

    suspend fun synthesize(
        text: String,
        voice: Voice = Voice.PREETI,
        language: String = CallMissedConfig.TTS_LANG_HI,
        speed: Double = CallMissedConfig.TTS_SPEED,
    ): ByteArray = withContext(Dispatchers.IO) {
        val speech = SpeechInput.normalize(text)
        require(speech.isNotBlank()) { "Speech input must not be empty" }
        require(speed.isFinite()) { "Speech speed must be finite" }
        val bodyJson = buildJsonObject {
            put("model", CallMissedConfig.TTS_MODEL)
            put("input", speech)
            put("voice", voice.id)
            put("language", language.substringBefore('-').lowercase())
            put("speed", speed.coerceIn(0.6, 1.5))
            // We already normalized formatting; preserve pronunciation, numbers and negation.
            put("humanize", false)
            put("response_format", "pcm")
            put("speech_sample_rate", CallMissedConfig.TTS_SAMPLE_RATE)
        }.toString()
        val req = Request.Builder()
            .url("$baseUrl/v1/audio/speech")
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(bodyJson.toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { resp ->
            usage.record(resp.headers)
            if (!resp.isSuccessful) {
                // Error bodies are small; success bodies are PCM — only read text on error.
                val raw = resp.body?.string().orEmpty()
                throw CallMissedException(resp.code, CloudErrors.parseCode(raw), raw.take(500))
            }
            resp.body!!.bytes()
        }
    }

    companion object {
        /** Cloud TTS only when online and local speech synthesis is unavailable. */
        fun shouldUseCloudTts(localTtsReady: Boolean, online: Boolean): Boolean =
            online && !localTtsReady
    }
}

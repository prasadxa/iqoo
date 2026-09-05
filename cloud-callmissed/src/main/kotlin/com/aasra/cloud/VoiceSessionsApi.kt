package com.aasra.cloud

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

private val sessionsJson = Json { ignoreUnknownKeys = true }

/** One voice session as returned by POST/GET /v1/voice/sessions. */
data class VoiceSession(
    val id: String,
    /** Media-server URL — issued once at creation, never hardcoded. */
    val wsUrl: String?,
    /** Connection JWT (1 h TTL) — issued once at creation. */
    val token: String?,
    val status: String?,
    val raw: String,
)

/** One transcript turn (format=json). */
data class TranscriptTurn(
    val index: Int,
    val userTranscript: String,
    val agentResponse: String,
    val interrupted: Boolean,
    val sttMs: Long?,
    val firstTokenMs: Long?,
    val firstAudioMs: Long?,
    val totalMs: Long?,
    val llmModel: String?,
)

/**
 * Voice Session API (server docs: WebRTC path).
 * `POST /v1/voice/sessions` returns a per-session `ws_url` + `token`; the
 * client connects with the `livekit-client` SDK and the agent joins
 * automatically. This class covers the REST surface (create/list/get/
 * transcript/delete). Live media needs the LiveKit SDK, which is outside
 * this module — the phone uses the Managed Voice Agent WebSocket
 * ([VoiceAgentSocket]) instead, so this API is used for caregivers/
 * dashboards (e.g. fetching a transcript) rather than live calls.
 *
 * Auth: `Authorization: Bearer cm_...`; the key needs `stt`+`tts`+`llm`
 * permissions to create a session.
 */
class VoiceSessionsApi(
    private val http: OkHttpClient,
    private val apiKey: String,
    private val baseUrl: String = CallMissedConfig.BASE_URL,
    private val usage: UsageCounter = UsageCounter(),
) {
    suspend fun create(
        systemPrompt: String = AasraPrompts.SYSTEM,
        language: String = AasraPrompts.AGENT_LANGUAGE,
        llmModel: String = CallMissedConfig.CHAT_MODEL_PRIMARY,
        maxDurationSeconds: Int = 300,
    ): VoiceSession = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("system_prompt", systemPrompt)
            put("voice", CallMissedConfig.TTS_VOICE)
            put("language", language)
            put("llm_model", llmModel)
            put("tts_provider", "cartesia")
            put("max_duration_seconds", maxDurationSeconds)
        }.toString()
        val req = Request.Builder()
            .url("$baseUrl/v1/voice/sessions")
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { resp ->
            usage.record(resp.headers)
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw CallMissedException(resp.code, CloudErrors.parseCode(raw), raw.take(500))
            }
            parseSession(raw)
        }
    }

    suspend fun list(status: String? = null, limit: Int = 50, offset: Int = 0): List<VoiceSession> =
        withContext(Dispatchers.IO) {
            val url = buildString {
                append("$baseUrl/v1/voice/sessions?limit=$limit&offset=$offset")
                if (status != null) append("&status=$status")
            }
            val req = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $apiKey")
                .get()
                .build()
            http.newCall(req).execute().use { resp ->
                usage.record(resp.headers)
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw CallMissedException(resp.code, CloudErrors.parseCode(raw), raw.take(500))
                }
                try {
                    val el = sessionsJson.parseToJsonElement(raw)
                    val arr = try { el.jsonArray } catch (_: Exception) {
                        el.jsonObject["sessions"]?.jsonArray ?: el.jsonObject["data"]?.jsonArray
                    } ?: return@withContext emptyList()
                    arr.map { parseSession(it.toString()) }
                } catch (_: Exception) {
                    emptyList()
                }
            }
        }

    suspend fun get(id: String): VoiceSession = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("$baseUrl/v1/voice/sessions/$id")
            .header("Authorization", "Bearer $apiKey")
            .get()
            .build()
        http.newCall(req).execute().use { resp ->
            usage.record(resp.headers)
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw CallMissedException(resp.code, CloudErrors.parseCode(raw), raw.take(500))
            }
            parseSession(raw)
        }
    }

    /** Transcript turns (`format=json`). Also supports `txt`/`srt` as raw text. */
    suspend fun transcript(id: String, format: String = "json"): List<TranscriptTurn> =
        withContext(Dispatchers.IO) {
            require(format == "json") { "Only format=json is parsed; fetch txt/srt raw via transcriptRaw()." }
            val req = Request.Builder()
                .url("$baseUrl/v1/voice/sessions/$id/transcript?format=json")
                .header("Authorization", "Bearer $apiKey")
                .get()
                .build()
            http.newCall(req).execute().use { resp ->
                usage.record(resp.headers)
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw CallMissedException(resp.code, CloudErrors.parseCode(raw), raw.take(500))
                }
                try {
                    sessionsJson.parseToJsonElement(raw).jsonArray.mapIndexed { i, el ->
                        val o = el.jsonObject
                        TranscriptTurn(
                            index = o["turn_index"]?.jsonPrimitive?.intOrNull ?: i,
                            userTranscript = o["user_transcript"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                            agentResponse = o["agent_response"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                            interrupted = o["interrupted"]?.jsonPrimitive?.contentOrNull == "true",
                            sttMs = o["stt_ms"]?.jsonPrimitive?.contentOrNull?.toLongOrNull(),
                            firstTokenMs = o["first_token_ms"]?.jsonPrimitive?.contentOrNull?.toLongOrNull(),
                            firstAudioMs = o["first_audio_ms"]?.jsonPrimitive?.contentOrNull?.toLongOrNull(),
                            totalMs = o["total_ms"]?.jsonPrimitive?.contentOrNull?.toLongOrNull(),
                            llmModel = o["llm_model"]?.jsonPrimitive?.contentOrNull,
                        )
                    }
                } catch (_: Exception) {
                    emptyList()
                }
            }
        }

    suspend fun delete(id: String): Boolean = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("$baseUrl/v1/voice/sessions/$id")
            .header("Authorization", "Bearer $apiKey")
            .delete()
            .build()
        http.newCall(req).execute().use { resp ->
            usage.record(resp.headers)
            resp.isSuccessful || resp.code == 204
        }
    }

    private fun parseSession(raw: String): VoiceSession {
        val o = sessionsJson.parseToJsonElement(raw).jsonObject
        return VoiceSession(
            id = o["id"]?.jsonPrimitive?.contentOrNull
                ?: o["session_id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            wsUrl = o["ws_url"]?.jsonPrimitive?.contentOrNull,
            token = o["token"]?.jsonPrimitive?.contentOrNull,
            status = o["status"]?.jsonPrimitive?.contentOrNull,
            raw = raw.take(2000),
        )
    }
}

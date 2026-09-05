package com.aasra.cloud

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

private val sttJson = Json { ignoreUnknownKeys = true }

/** Why a turn is being re-listened in the cloud (PLAN 5.6 router rows). */
enum class SttTrigger {
    /** Local STT confidence below threshold. */
    LOW_CONFIDENCE,

    /** Detected language outside {en, hi}. */
    UNSUPPORTED_LANGUAGE,

    /** User said "repeat / samjha nahi" twice in a row. */
    REPEAT_TWICE,
}

@Serializable
data class SttResult(val text: String, val language: String? = null)

/**
 * POST /v1/audio/transcriptions, multipart (PLAN 5.2):
 * `file` = WAV 16 kHz of the last VAD segment (kept in a ring buffer),
 * model saaras:v4, `language` omitted for auto, mode=codemix for Hinglish.
 */
class SttApi(
    private val http: OkHttpClient,
    private val apiKey: String,
    private val baseUrl: String = CallMissedConfig.BASE_URL,
    private val usage: UsageCounter = UsageCounter(),
) {
    suspend fun transcribe(
        wav16k: ByteArray,
        fileName: String = "segment.wav",
        language: String? = null,
        hinglish: Boolean = false,
        model: String = CallMissedConfig.STT_MODEL,
    ): SttResult = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart(
                "file", fileName,
                wav16k.toRequestBody("audio/wav".toMediaType()),
            )
            .addFormDataPart("model", model)
            .apply {
                // `language` omitted for auto-detect.
                if (language != null) addFormDataPart("language", language)
                if (hinglish) addFormDataPart("mode", CallMissedConfig.STT_MODE_CODEMIX)
            }
            .build()
        val req = Request.Builder()
            .url("$baseUrl/v1/audio/transcriptions")
            .header("Authorization", "Bearer $apiKey")
            .post(body)
            .build()
        http.newCall(req).execute().use { resp ->
            usage.record(resp.headers)
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw CallMissedException(resp.code, CloudErrors.parseCode(raw), raw.take(500))
            }
            sttJson.decodeFromString(SttResult.serializer(), raw)
        }
    }

    companion object {
        private val SUPPORTED_LOCAL = setOf("en", "hi")

        /**
         * Router predicate for the 5.2 re-listen (PLAN 5.6):
         * low confidence, language not in {en, hi}, or repeat-twice.
         * Returns the trigger, or null when local STT stands.
         */
        fun shouldRelisten(
            confidence: Float,
            confidenceThreshold: Float = 0.5f,
            language: String?,
            repeatCount: Int,
        ): SttTrigger? = when {
            repeatCount >= 2 -> SttTrigger.REPEAT_TWICE
            language != null && language.lowercase().take(2) !in SUPPORTED_LOCAL -> SttTrigger.UNSUPPORTED_LANGUAGE
            confidence < confidenceThreshold -> SttTrigger.LOW_CONFIDENCE
            else -> null
        }
    }
}

package com.aasra.cloud

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Static config for the CallMissed cloud layer (PLAN 5 + 11).
 *
 * Auth: REST uses `Authorization: Bearer <cm_key>`; the voice-agent
 * WebSocket uses `Authorization: Token <cm_key>` (NOT Bearer).
 * The key comes from BuildConfig (local.properties `CALLMISSED_API_KEY`,
 * see module build.gradle.kts). It is never hardcoded here.
 */
object CallMissedConfig {
    const val BASE_URL = "https://api.callmissed.com"
    const val WS_AGENT_URL = "wss://api.callmissed.com/v2/voice/agent"
    const val VOICE_MODELS_URL = "https://api.callmissed.com/api/v1/voice/models"

    // Chat (PLAN 5.1 / 11)
    const val CHAT_MODEL_PRIMARY = "sarvam-105b-conversations"
    const val CHAT_MODEL_FALLBACK_1 = "sarvam-105b"
    const val CHAT_MODEL_FALLBACK_2 = "kimi-k2.5"
    val CHAT_FALLBACK_CHAIN = listOf(CHAT_MODEL_PRIMARY, CHAT_MODEL_FALLBACK_1, CHAT_MODEL_FALLBACK_2)
    const val CHAT_TEMPERATURE = 0.4
    const val CHAT_MAX_TOKENS = 300
    const val CHAT_REASONING_EFFORT = "low"
    const val CHAT_HISTORY_MESSAGES = 20

    // STT (PLAN 5.2 / 11)
    const val STT_MODEL = "saaras:v4"
    const val STT_MODE_CODEMIX = "codemix"
    const val STT_SAMPLE_RATE = 16000

    // TTS (PLAN 5.3 / 11)
    const val TTS_MODEL = "sonic-3.6"
    // Preeti, verified from CallMissed's public Sonic voice catalog. A name handle
    // not in its featured list would silently select a different voice.
    const val TTS_VOICE = "92da9281-7cf3-4c61-be0f-face03a3312f"
    const val TTS_LANG_HI = "hi"
    const val TTS_LANG_EN = "en"
    const val TTS_SPEED = 0.9
    const val TTS_SAMPLE_RATE = 24000

    // Search (PLAN 5.5 / 11)
    const val SEARCH_MODE = "shorter"
    const val SEARCH_GL = "in"
    const val SEARCH_NUM_RESULTS = 5

    /** Key injected from local.properties at build time. Blank = not configured. */
    val apiKey: String get() = BuildConfig.CALLMISSED_API_KEY

    val hasKey: Boolean get() = apiKey.isNotBlank() && apiKey != "\"\""

    fun requireKey(): String {
        check(hasKey) { "CALLMISSED_API_KEY missing: set it in local.properties (never commit it)." }
        return apiKey
    }
}

/**
 * Spoken-style system prompt shared by local LLM and cloud LLM (PLAN 4.5 / 5.1).
 * Short, no markdown, no emoji, respectful, confirm-before-action, never dosages.
 */
object AasraPrompts {
    const val SYSTEM =
        "You are Aasra, an AI voice companion for an elderly person, not a clinician or a human. " +
            "Reply in 1-2 short spoken sentences, plain words, no markdown, no emoji. " +
            "Address the user respectfully. Use a calm, kind conversational tone, natural punctuation and one question at a time. Do not output SSML or stage directions. " +
            "Always confirm by voice before calling, messaging, or setting anything. " +
            "Never give medicine dosages; always say to ask their doctor. " +
            "Match their language: Hindi, Hinglish, or simple English."
    const val AGENT_GREETING = "Namaste, main Aasra hoon."
    const val AGENT_LANGUAGE = "hi-IN"
}

/**
 * Device-tool JSON schema shared by local + cloud LLMs and the voice agent
 * (PLAN 6.1). OpenAI function-tool shape: {type:function, function:{name, parameters}}.
 */
object AasraTools {
    fun schemas(): JsonArray = buildJsonArray {
        add(fn("call_contact", "Call a contact. The app asks for number selection and confirmation. After each user reply invoke again with the original name; never claim success before the tool succeeds.", mapOf("name" to "string")))
        add(fn("send_sms", "Message a contact. The app confirms the exact recipient and text. After each user reply invoke again with the original name and unchanged message.", mapOf("name" to "string", "message" to "string")))
        add(
            fn(
                "set_reminder",
                "Set a reminder or alarm.",
                mapOf("text" to "string", "time" to "string", "repeat" to "string"),
            ),
        )
        add(fn("list_reminders", "List saved reminders.", emptyMap()))
        add(fn("cancel_reminder", "Cancel a reminder by id.", mapOf("id" to "string")))
        add(fn("sos", "Call the emergency contact and SMS location to all emergency contacts.", emptyMap()))
        add(fn("get_time", "Get the current time.", emptyMap()))
        add(fn("get_date", "Get today's date.", emptyMap()))
        add(fn("set_volume", "Set speaker volume 0-100.", mapOf("level" to "integer")))
        add(fn("flashlight", "Turn the flashlight on or off.", mapOf("on" to "boolean")))
        add(fn("read_notifications", "Read the last 3 notifications aloud.", emptyMap()))
        add(fn("web_search", "Search the web. Cloud only.", mapOf("query" to "string")))
    }

    private fun fn(name: String, description: String, params: Map<String, String>) =
        buildJsonObject {
            put("type", "function")
            put("function", buildJsonObject {
                put("name", name)
                put("description", description)
                put("parameters", buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        for ((k, t) in params) put(k, buildJsonObject { put("type", t) })
                    })
                    put("required", buildJsonArray {
                        for (key in params.keys) add(JsonPrimitive(key))
                    })
                })
            })
        }
}

package com.aasra.cloud

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class VoiceSpeechSettingsTest {
    @Test fun greetingUsesPlainSpeechButSystemPromptIsNotRewritten() {
        val settings = VoiceAgentSocket.defaultSettings(
            systemPrompt = "Keep <context> as instructions.",
            greeting = "<speak>दवा **नहीं** लें।</speak>",
        )
        val agent = Json.parseToJsonElement(settings).jsonObject.getValue("agent").jsonObject
        assertEquals("दवा नहीं लें।", agent.getValue("greeting").jsonPrimitive.content)
        assertEquals("Keep <context> as instructions.", agent.getValue("prompt").jsonPrimitive.content)
        assertEquals(setOf("model", "voice"), agent.getValue("tts").jsonObject.keys)
    }

    @Test fun blankGreetingStaysBlankAndUnsafeGreetingIsRejected() {
        val agent = Json.parseToJsonElement(VoiceAgentSocket.defaultSettings(greeting = "")).jsonObject
            .getValue("agent").jsonObject
        assertEquals("", agent.getValue("greeting").jsonPrimitive.content)
        assertTrue(runCatching { VoiceAgentSocket.defaultSettings(greeting = "<speak>not") }.isFailure)
    }
}

package com.aasra.cloud

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class TtsApiTest {
    @Test fun normalizesAtTheHttpBoundaryWithoutUnverifiedControls() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("pcm"))
            val api = api(server)
            api.synthesize(
                "<speak><emotion value=\"calm\"/>Do **not** take it.<break time=\"2s\"/>Ask your doctor.</speak>",
                voice = TtsApi.Voice.PREETI, language = "hi-IN", speed = 0.8,
            )
            val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
            assertEquals("Do not take it. Ask your doctor.", body.getValue("input").jsonPrimitive.content)
            assertEquals(CallMissedConfig.TTS_VOICE, body.getValue("voice").jsonPrimitive.content)
            assertEquals("hi", body.getValue("language").jsonPrimitive.content)
            assertEquals("0.8", body.getValue("speed").jsonPrimitive.content)
            assertEquals("false", body.getValue("humanize").jsonPrimitive.content)
            assertEquals("pcm", body.getValue("response_format").jsonPrimitive.content)
            assertEquals("24000", body.getValue("speech_sample_rate").jsonPrimitive.content)
            assertFalse(body.keys.any { it in setOf("emotion", "instructions", "generation_config", "temperature", "stream") })
        }
    }

    @Test fun preservesHindiNegationPronunciationAndEscapesJson() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("pcm"))
            api(server).synthesize("<speak>दवा <emphasis>नहीं</emphasis> लें। &quot;naa-hee&quot; &amp; 2 &lt; 3, C:\\notes</speak>")
            val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
            assertEquals("दवा नहीं लें। \"naa-hee\" & 2 < 3, C:\\notes", body.getValue("input").jsonPrimitive.content)
        }
    }

    @Test fun rejectsUnsafeOrEmptyInputBeforeNetworkIo() = runBlocking {
        MockWebServer().use { server ->
            repeat(8) { server.enqueue(MockResponse().setBody("pcm")) }
            for (text in listOf(
                "", "  ", "<speak><break time=\"1s\"/></speak>", "a".repeat(4097),
                "<speak>Take it<emphasis> not</speak>",
                "<!DOCTYPE speak [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><speak>&x;</speak>",
                "<speak>".repeat(33) + "not" + "</speak>".repeat(33),
                "Take it <prosody rate=\"slow\"",
            )) {
                assertTrue("Must reject unsafe input", runCatching { api(server).synthesize(text) }.exceptionOrNull() is IllegalArgumentException)
            }
            assertEquals(0, server.requestCount)
        }
    }

    @Test fun speedUsesDocumentedSonicLimits() = runBlocking {
        MockWebServer().use { server ->
            for ((requested, expected) in listOf(0.1 to "0.6", 4.0 to "1.5", 0.9 to "0.9")) {
                server.enqueue(MockResponse().setBody("pcm"))
                api(server).synthesize("Hello.", speed = requested)
                val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
                assertEquals(expected, body.getValue("speed").jsonPrimitive.content)
            }
            for (speed in listOf(Double.NaN, Double.POSITIVE_INFINITY)) {
                assertTrue(runCatching { api(server).synthesize("Hello.", speed = speed) }.isFailure)
            }
            assertEquals(3, server.requestCount)
        }
    }

    private fun api(server: MockWebServer) = TtsApi(
        OkHttpClient(), "test-key", server.url("/").toString().removeSuffix("/"),
    )
}

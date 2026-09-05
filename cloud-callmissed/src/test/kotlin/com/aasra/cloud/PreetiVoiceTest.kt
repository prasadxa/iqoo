package com.aasra.cloud

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Test

class PreetiVoiceTest {
    @Test fun restUsesOnlySonicAndVerifiedPreetiUuid() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("pcm"))
            TtsApi(OkHttpClient(), "test", server.url("/").toString().trimEnd('/')).synthesize("Namaste.")
            val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
            assertEquals("sonic-3.6", body.getValue("model").jsonPrimitive.content)
            assertEquals("92da9281-7cf3-4c61-be0f-face03a3312f", body.getValue("voice").jsonPrimitive.content)
        }
    }

    @Test fun managedVoiceUsesSamePreetiVoice() {
        val tts = Json.parseToJsonElement(VoiceAgentSocket.defaultSettings()).jsonObject
            .getValue("agent").jsonObject.getValue("tts").jsonObject
        assertEquals("sonic-3.6", tts.getValue("model").jsonPrimitive.content)
        assertEquals("92da9281-7cf3-4c61-be0f-face03a3312f", tts.getValue("voice").jsonPrimitive.content)
    }
}

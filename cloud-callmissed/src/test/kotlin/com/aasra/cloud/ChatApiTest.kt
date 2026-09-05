package com.aasra.cloud

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatApiTest {

    @Test
    fun incompleteEventStreamFailsInsteadOfReportingDone() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\n"),
        )
        server.start()

        try {
            val api = ChatApi(
                http = OkHttpClient(),
                apiKey = "test-key",
                baseUrl = server.url("/").toString().removeSuffix("/"),
            )
            val result = runCatching {
                api.stream(listOf(ChatMessage("user", "hello"))).toList()
            }

            assertTrue("Premature EOF must fail the stream", result.isFailure)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun requestKeepsTenCompleteConversationTurns() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: [DONE]\n\n"),
        )
        server.start()

        try {
            val api = ChatApi(
                http = OkHttpClient(),
                apiKey = "test-key",
                baseUrl = server.url("/").toString().removeSuffix("/"),
            )
            val history = (1..22).map {
                ChatMessage(if (it % 2 == 0) "assistant" else "user", "turn-$it")
            }
            api.stream(history).toList()

            val body = server.takeRequest().body.readUtf8()
            val messages = Json.parseToJsonElement(body).jsonObject.getValue("messages").jsonArray
                .map { it.jsonObject.getValue("content").jsonPrimitive.content }
            assertTrue("turn-1" !in messages)
            assertTrue("turn-2" !in messages)
            assertTrue("turn-3" in messages)
            assertTrue("turn-22" in messages)
        } finally {
            server.shutdown()
        }
    }
}

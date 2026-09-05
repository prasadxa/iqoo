package com.aasra.companion.mcp

import com.aasra.companion.ui.tools.ExternalToolsController
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class ExternalToolsControllerTest {
    @Test fun explicitDiscoveryReviewCancelAndApproveHaveDistinctNetworkEffects() = runBlocking {
        val methods = mutableListOf<String>()
        var expired = false
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val buffer = Buffer()
            request.body!!.writeTo(buffer)
            val json = Json.parseToJsonElement(buffer.readUtf8()).jsonObject
            val method = json["method"]!!.jsonPrimitive.content
            methods += method
            val result = when (method) {
                "initialize" -> """{"protocolVersion":"2025-11-25","capabilities":{"tools":{}},"serverInfo":{"name":"test","version":"1"}}"""
                "tools/list" -> """{"tools":[{"name":"write","inputSchema":{"type":"object"}}]}"""
                else -> """{"content":[{"type":"text","text":"actual response"}]}"""
            }
            val notification = method == "notifications/initialized"
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(if (notification) 202 else if (method == "tools/call" && expired) 404 else 200).message("test")
                .apply { if (method == "initialize") header("Mcp-Session-Id", "controller-session") }
                .body((if (notification) "" else """{"jsonrpc":"2.0","id":${json["id"]},"result":$result}""").toResponseBody("application/json".toMediaType()))
                .build()
        }.build()
        val controller = ExternalToolsController(this) { McpClient(it, http) }
        controller.setEndpoint("https://tools.example/mcp")
        assertTrue(methods.isEmpty())
        controller.discover()!!.join()
        controller.selectTool("write")
        controller.setArguments("[]")
        controller.review()!!.join()
        assertEquals(McpError.INVALID_ARGUMENTS, controller.state.value.error?.kind)
        assertNull(controller.state.value.approval)
        controller.setArguments("{\"recipient\":\"caregiver\"}")
        controller.review()!!.join()
        val cancelled = controller.state.value.approval!!
        assertEquals(3, methods.size)
        controller.cancelReview()
        assertNull(controller.approve(cancelled))
        controller.review()!!.join()
        val approved = controller.state.value.approval!!
        val job = controller.approve(approved)!!
        assertNull(controller.approve(approved))
        job.join()
        assertEquals(1, methods.count { it == "tools/call" })
        assertTrue(controller.state.value.result!!.json.contains("actual response"))
        controller.setEndpoint("https://another.example/mcp")
        assertTrue(controller.state.value.tools.isEmpty())
        assertNull(controller.state.value.result)
        assertNull(controller.approve(approved))
        assertEquals(4, methods.size)
        controller.discover()!!.join()
        controller.selectTool("write")
        controller.review()!!.join()
        expired = true
        controller.approve(controller.state.value.approval!!)!!.join()
        assertEquals(McpError.SESSION_EXPIRED, controller.state.value.error?.kind)
        assertNull(controller.state.value.connectedEndpoint)
        assertTrue(controller.state.value.tools.isEmpty())
        assertNull(controller.review())
        controller.close()
    }
}

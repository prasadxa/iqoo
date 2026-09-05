package com.aasra.companion.mcp

import java.io.Closeable
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionSpec
import okhttp3.CookieJar
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink

/**
 * Explicit, ephemeral MCP tools client. Constructing it performs no IO.
 * Never share the app's cloud HTTP client or credentials with this client.
 * See README.md for supported versions, bounds and intentional transport limitations.
 */
class McpClient internal constructor(endpoint: String, httpClient: OkHttpClient) : Closeable {
    constructor(endpoint: String) : this(endpoint, OkHttpClient())

    val endpoint: String = endpointUrl(endpoint).toString()
    private val http = httpClient.newBuilder()
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE)
        .cookieJar(CookieJar.NO_COOKIES).cache(null)
        .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS))
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS).build()
    private val operations = Mutex()
    private val lifecycle = Any()
    @Volatile private var closed = false
    private var activeCall: Call? = null
    @Volatile private var version: String? = null
    @Volatile private var session: String? = null
    @Volatile private var tools: List<McpTool> = emptyList()
    @Volatile private var pending: McpCallApproval? = null
    private var nextId = 0L

    /** Connect and discover only in response to an explicit user action. No partial list on failure. */
    suspend fun discoverTools(): List<McpTool> = operations.withLock {
        checkOpen()
        pending = null
        tools = emptyList()
        try {
            if (version == null) {
                val init = rpc("initialize", buildJsonObject {
                    put("protocolVersion", SUPPORTED_VERSIONS.first())
                    put("capabilities", JsonObject(emptyMap()))
                    put("clientInfo", buildJsonObject { put("name", "aasra-external-tools"); put("version", "1.0") })
                }, initializing = true)
                val negotiated = init["protocolVersion"].stringValue()
                if (negotiated !in SUPPORTED_VERSIONS) throw McpException(McpError.PROTOCOL, "Unsupported protocol version")
                val capabilities = init["capabilities"] as? JsonObject
                if (capabilities?.get("tools") !is JsonObject || init["serverInfo"] !is JsonObject) {
                    throw McpException(McpError.PROTOCOL, "Server does not declare tools support")
                }
                version = negotiated
                post(buildJsonObject { put("jsonrpc", "2.0"); put("method", "notifications/initialized") }, null)
            }
            val found = mutableListOf<McpTool>()
            val cursors = mutableSetOf<String>()
            var cursor: String? = null
            for (page in 1..MAX_PAGES) {
                val result = rpc("tools/list", buildJsonObject { cursor?.let { put("cursor", it) } })
                val entries = result["tools"] as? JsonArray ?: throw McpException(McpError.PROTOCOL, "Missing tools array")
                if (entries.size + found.size > MAX_TOOLS) throw McpException(McpError.LIMIT, "Too many tools")
                for (entry in entries) {
                    val item = entry as? JsonObject ?: throw McpException(McpError.PROTOCOL, "Invalid tool")
                    if (item.toString().toByteArray().size > 16_384) throw McpException(McpError.LIMIT, "Tool metadata exceeds 16 KiB")
                    val name = item["name"].stringValue()
                    val schema = item["inputSchema"] as? JsonObject
                    if (name == null || !TOOL_NAME.matches(name) || schema == null || schema["type"].stringValue() != "object" ||
                        found.any { it.name == name }) throw McpException(McpError.PROTOCOL, "Invalid or duplicate tool definition")
                    val description = item["description"].stringValue()
                    if ("description" in item && description == null) throw McpException(McpError.PROTOCOL, "Invalid description")
                    val execution = item["execution"] as? JsonObject
                    found += McpTool(name, description, schema.toString(), execution?.get("taskSupport").stringValue() == "required")
                }
                if ("nextCursor" !in result) {
                    synchronized(lifecycle) { checkOpen(); tools = found.toList() }
                    return@withLock found.toList()
                }
                cursor = result["nextCursor"].stringValue() ?: throw McpException(McpError.PROTOCOL, "Invalid pagination cursor")
                if (cursor.length > 4096 || !cursors.add(cursor)) throw McpException(McpError.LIMIT, "Repeated or oversized cursor")
            }
            throw McpException(McpError.LIMIT, "Tool discovery exceeds $MAX_PAGES pages")
        } catch (e: Exception) {
            close()
            throw e
        }
    }

    /** No IO. Present ALL snapshot fields to the user, then call callTool only after explicit consent. */
    suspend fun prepareCall(toolName: String, argumentsJson: String): McpCallApproval = operations.withLock {
        checkOpen()
        pending = null
        val tool = tools.singleOrNull { it.name == toolName } ?: throw McpException(McpError.INVALID_ARGUMENTS, "Tool not discovered")
        if (tool.requiresTask) throw McpException(McpError.PROTOCOL, "This tool requires unsupported task execution")
        if (argumentsJson.toByteArray().size > MAX_ARGUMENT_BYTES) throw McpException(McpError.LIMIT, "Arguments exceed 16 KiB")
        val arguments = parseObject(argumentsJson, McpError.INVALID_ARGUMENTS)
        val approval = McpCallApproval(endpoint, toolName, arguments.toString())
        synchronized(lifecycle) { checkOpen(); pending = approval }
        approval
    }

    /** Consumes approval even on failure. Never retry automatically: the remote action may have occurred. */
    suspend fun callTool(approval: McpCallApproval): McpToolResult = operations.withLock {
        synchronized(lifecycle) {
            checkOpen()
            if (pending !== approval) throw McpException(McpError.APPROVAL_REQUIRED)
            pending = null
        }
        val result = rpc("tools/call", buildJsonObject {
            put("name", approval.toolName)
            put("arguments", parseObject(approval.argumentsJson, McpError.INVALID_ARGUMENTS))
        })
        if (result["content"] !is JsonArray || ("structuredContent" in result && result["structuredContent"] !is JsonObject)) {
            throw McpException(McpError.PROTOCOL, "Invalid tool result")
        }
        val error = result["isError"] as? JsonPrimitive
        if ("isError" in result && (error == null || error.isString || error.booleanOrNull == null)) {
            throw McpException(McpError.PROTOCOL, "Invalid tool error flag")
        }
        checkOpen()
        McpToolResult(result.toString(), error?.booleanOrNull ?: false)
    }

    fun discardApproval(approval: McpCallApproval) {
        synchronized(lifecycle) { if (pending === approval) pending = null }
    }

    /** Forget the local session and cancel pending IO; does not claim to undo remote effects. No network DELETE. */
    override fun close() {
        synchronized(lifecycle) {
            closed = true
            activeCall?.cancel()
            activeCall = null
            pending = null
            session = null
            version = null
            tools = emptyList()
        }
        http.connectionPool.evictAll()
    }

    private fun checkOpen() { if (closed) throw McpException(McpError.DISCONNECTED) }

    private suspend fun rpc(method: String, params: JsonObject, initializing: Boolean = false): JsonObject {
        val id = JsonPrimitive(++nextId)
        return post(buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("method", method); put("params", params) }, id, initializing)!!
    }

    private suspend fun post(message: JsonObject, id: JsonPrimitive?, initializing: Boolean = false): JsonObject? {
        checkOpen()
        val bytes = message.toString().toByteArray(Charsets.UTF_8)
        val body = object : RequestBody() {
            override fun contentType() = "application/json; charset=utf-8".toMediaType()
            override fun contentLength() = bytes.size.toLong()
            override fun writeTo(sink: BufferedSink) { sink.write(bytes) }
            // Also prevents OkHttp's status-based follow-ups (e.g. 503 Retry-After: 0).
            override fun isOneShot() = true
        }
        val request = Request.Builder().url(endpoint).post(body)
            .header("Accept", "application/json, text/event-stream")
            .header("Accept-Encoding", "identity")
            .header("Cache-Control", "no-store")
            .apply {
                if (!initializing) {
                    version?.let { header("MCP-Protocol-Version", it) }
                    session?.let { header("Mcp-Session-Id", it) }
                }
            }.build()
        return suspendCancellableCoroutine { continuation ->
            val call = http.newCall(request)
            synchronized(lifecycle) { checkOpen(); activeCall = call }
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    synchronized(lifecycle) { if (activeCall === call) activeCall = null }
                    continuation.resumeWithException(McpException(McpError.NETWORK, e.javaClass.simpleName))
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val result = response.use {
                            try { readResponse(it, id, initializing) } finally {
                                // Do not drain an open SSE stream or wait for a peer's TLS close_notify.
                                call.cancel()
                            }
                        }
                        checkOpen()
                        continuation.resume(result)
                    } catch (e: Exception) {
                        continuation.resumeWithException(when (e) {
                            is McpException, is CancellationException -> e
                            is IOException -> McpException(McpError.NETWORK, e.javaClass.simpleName)
                            else -> McpException(McpError.PROTOCOL, "Invalid response")
                        })
                    } finally {
                        synchronized(lifecycle) { if (activeCall === call) activeCall = null }
                    }
                }
            })
        }
    }

    private fun readResponse(response: Response, id: JsonPrimitive?, initializing: Boolean): JsonObject? {
        if (response.code == 401 || response.code == 403) throw McpException(McpError.AUTH_UNSUPPORTED, "HTTP ${response.code}")
        if (response.code == 404 && response.request.header("Mcp-Session-Id") != null) {
            close()
            throw McpException(McpError.SESSION_EXPIRED)
        }
        if (!response.isSuccessful) throw McpException(McpError.HTTP, "HTTP ${response.code}")
        val body = response.body ?: throw McpException(McpError.PROTOCOL, "Missing response body")
        if (body.contentLength() > MAX_RESPONSE_BYTES) throw McpException(McpError.LIMIT, "Response exceeds 256 KiB")
        if (response.header("Content-Encoding")?.let { it != "identity" } == true) {
            throw McpException(McpError.PROTOCOL, "Compressed responses are unsupported")
        }
        val sessionHeaders = response.headers.values("Mcp-Session-Id")
        val receivedSession = sessionHeaders.singleOrNull()
        if (sessionHeaders.size > 1 || receivedSession?.let { it.isEmpty() || it.length > 1024 || it.any { c -> c.code !in 0x21..0x7e } } == true) {
            throw McpException(McpError.PROTOCOL, "Invalid session header")
        }
        if (!initializing && receivedSession != null && receivedSession != session) throw McpException(McpError.PROTOCOL, "Unexpected session change")
        if (id == null) {
            if (response.code != 202 || body.byteStream().read() != -1) throw McpException(McpError.PROTOCOL, "Expected an empty HTTP 202 acknowledgement")
            return null
        }
        val type = body.contentType()
        if (type?.charset(Charsets.UTF_8) != Charsets.UTF_8) throw McpException(McpError.PROTOCOL, "Expected UTF-8")
        val mime = "${type.type}/${type.subtype}"
        if (mime != "application/json" && mime != "text/event-stream") throw McpException(McpError.PROTOCOL, "Unsupported response content type")
        val result = readMcpResult(body.byteStream(), mime == "text/event-stream", id)
        if (initializing) synchronized(lifecycle) { checkOpen(); session = receivedSession }
        return result
    }

    companion object {
        val SUPPORTED_VERSIONS = listOf("2025-11-25", "2025-06-18")
        const val MAX_RESPONSE_BYTES = 256 * 1024
        const val MAX_ARGUMENT_BYTES = 16 * 1024
        const val MAX_PAGES = 8
        const val MAX_TOOLS = 64
        private val TOOL_NAME = Regex("[A-Za-z0-9_.-]{1,128}")
    }
}

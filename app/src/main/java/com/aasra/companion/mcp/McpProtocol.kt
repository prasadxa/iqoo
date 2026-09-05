package com.aasra.companion.mcp

import java.io.FilterInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.CodingErrorAction
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

enum class McpError {
    INVALID_ENDPOINT, INVALID_ARGUMENTS, APPROVAL_REQUIRED, DISCONNECTED,
    AUTH_UNSUPPORTED, SESSION_EXPIRED, HTTP, NETWORK, PROTOCOL, LIMIT, REMOTE,
}

/** Details may be server supplied: display as untrusted plain text, never interpret. */
class McpException(val kind: McpError, val detail: String = "") : Exception("$kind: $detail")

data class McpTool(val name: String, val description: String?, val inputSchemaJson: String, val requiresTask: Boolean)

/** An immutable review snapshot, bound by identity to one client and consumed before sending. */
class McpCallApproval internal constructor(val endpoint: String, val toolName: String, val argumentsJson: String)

/** Raw JSON is untrusted data, not instructions, HTML, links to fetch, or executable content. */
data class McpToolResult(val json: String, val isError: Boolean)

internal fun endpointUrl(value: String): HttpUrl {
    val raw = value.trim()
    if (raw.length > 2048 || raw.any { it.code < 0x21 || it.code > 0x7e || it == '\\' } ||
        !raw.startsWith("https://", ignoreCase = true) ||
        '@' in raw.substringAfter("://").substringBefore('/') || '?' in raw || '#' in raw
    ) throw McpException(McpError.INVALID_ENDPOINT)
    val url = raw.toHttpUrlOrNull() ?: throw McpException(McpError.INVALID_ENDPOINT)
    if (!url.isHttps || url.username.isNotEmpty() || url.password.isNotEmpty()) {
        throw McpException(McpError.INVALID_ENDPOINT)
    }
    return url
}

internal fun parseObject(text: String, error: McpError = McpError.PROTOCOL): JsonObject {
    // Bound nesting before the recursive JSON parser sees untrusted input.
    var depth = 0
    var quoted = false
    var escaped = false
    for (c in text) {
        if (quoted) {
            if (c.code < 0x20) throw McpException(error, "Unescaped control character in JSON string")
            if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') quoted = false
        } else when (c) {
            '"' -> quoted = true
            '{', '[' -> if (++depth > 32) throw McpException(McpError.LIMIT, "JSON nesting exceeds 32")
            '}', ']' -> depth--
        }
    }
    return try {
        val result = Json.parseToJsonElement(text) as? JsonObject ?: throw McpException(error, "Expected a JSON object")
        validateLiterals(result, error)
        result
    } catch (e: IllegalArgumentException) {
        throw McpException(error, "Invalid JSON object")
    }
}

// JsonElement parsing accepts unquoted literals such as NaN; MCP and approval inputs require JSON.
private fun validateLiterals(value: JsonElement, error: McpError) {
    when (value) {
        is JsonObject -> value.values.forEach { validateLiterals(it, error) }
        is JsonArray -> value.forEach { validateLiterals(it, error) }
        is JsonPrimitive -> if (!value.isString && value.content !in listOf("null", "true", "false") &&
            !JSON_NUMBER.matches(value.content)) throw McpException(error, "Invalid JSON literal")
    }
}

private val JSON_NUMBER = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")

internal fun JsonElement?.stringValue(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

/** Escape invisible formatting controls so server text cannot reorder the approval display. */
fun mcpDisplayText(text: String): String = buildString {
    text.forEach { c ->
        if (Character.getType(c) == Character.FORMAT.toInt() ||
            (c.isISOControl() && c != '\n' && c != '\t') || c == '\u2028' || c == '\u2029'
        ) append("\\u" + c.code.toString(16).padStart(4, '0')) else append(c)
    }
}

internal class BoundedMcpInput(input: InputStream) : FilterInputStream(input) {
    private var count = 0
    override fun read(): Int = super.read().also { if (it >= 0) add(1) }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val read = `in`.read(buffer, offset, minOf(length, McpClient.MAX_RESPONSE_BYTES - count + 1))
        if (read > 0) add(read)
        return read
    }
    private fun add(n: Int) {
        count += n
        if (count > McpClient.MAX_RESPONSE_BYTES) throw McpException(McpError.LIMIT, "Response exceeds 256 KiB")
    }
}

internal fun readMcpResult(input: InputStream, sse: Boolean, id: JsonPrimitive): JsonObject {
    val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
    val reader = InputStreamReader(BoundedMcpInput(input), decoder).buffered()
    if (!sse) return rpcResult(parseObject(reader.readText()), id, allowNotification = false)!!
    var data = StringBuilder()
    var event = ""
    var events = 0
    var first = true
    while (true) {
        var line = reader.readLine() ?: throw McpException(McpError.PROTOCOL, "SSE ended before a complete response; outcome may be unknown")
        if (first) { line = line.removePrefix("\uFEFF"); first = false }
        if (line.isEmpty()) {
            if (++events > 128) throw McpException(McpError.LIMIT, "Too many SSE events")
            if (data.isNotBlank()) {
                if (event.isNotEmpty() && event != "message") throw McpException(McpError.PROTOCOL, "Unsupported SSE event")
                rpcResult(parseObject(data.toString()), id, allowNotification = true)?.let { return it }
            }
            data = StringBuilder()
            event = ""
        } else if (!line.startsWith(':')) {
            val field = line.substringBefore(':')
            val value = line.substringAfter(':', "").removePrefix(" ")
            when (field) {
                "data" -> data.append(value).append('\n')
                "event" -> event = value
                // IDs/retry are deliberately not used: this client never resumes or replays.
            }
        }
    }
}

private fun rpcResult(message: JsonObject, id: JsonPrimitive, allowNotification: Boolean): JsonObject? {
    if (message["jsonrpc"].stringValue() != "2.0") throw McpException(McpError.PROTOCOL, "Expected JSON-RPC 2.0")
    if ("method" in message) {
        if (allowNotification && message["method"].stringValue() != null && "id" !in message &&
            "result" !in message && "error" !in message) return null
        throw McpException(McpError.PROTOCOL, "Server-initiated requests are unsupported")
    }
    if (message["id"] != id || (("result" in message) == ("error" in message))) {
        throw McpException(McpError.PROTOCOL, "Mismatched or malformed JSON-RPC response")
    }
    if ("error" in message) {
        val error = message["error"] as? JsonObject ?: throw McpException(McpError.PROTOCOL, "Invalid RPC error")
        val code = error["code"] as? JsonPrimitive
        if (code == null || code.isString || code.intOrNull == null || error["message"].stringValue() == null) {
            throw McpException(McpError.PROTOCOL, "Invalid RPC error")
        }
        throw McpException(McpError.REMOTE, mcpDisplayText(error.toString()).take(4096))
    }
    return message["result"] as? JsonObject ?: throw McpException(McpError.PROTOCOL, "Expected an object result")
}

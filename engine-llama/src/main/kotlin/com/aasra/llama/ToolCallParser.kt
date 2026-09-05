package com.aasra.llama

/**
 * Parses Qwen ChatML `<tool_call>` JSON into typed calls.
 *
 * Covers every tool in PLAN 6.1 plus the local-only `escalate(reason)`.
 * Unknown / malformed calls degrade to [ToolCall.Escalate] with a reason —
 * the router then sends the turn to the cloud LLM instead of dropping it.
 *
 * Accepted shapes (the small model emits either):
 *   <tool_call>{"name": "get_time", "arguments": {}}</tool_call>
 *   <tool_call>{"name": "...", "arguments": "{...}"}</tool_call>  (stringified args)
 *   {"name": "...", "arguments": {...}}  (bare JSON, no wrapper)
 */
object ToolCallParser {

    /** Every tool name the local model is allowed to emit (PLAN 6.1 + escalate). */
    val KNOWN_TOOLS: Set<String> = setOf(
        "call_contact",
        "send_sms",
        "set_reminder",
        "list_reminders",
        "cancel_reminder",
        "sos",
        "get_time",
        "get_date",
        "set_volume",
        "flashlight",
        "read_notifications",
        "web_search",
        "escalate",
    )

    sealed interface ToolCall {
        val name: String
        val arguments: Map<String, String>

        data class Device(
            override val name: String,
            override val arguments: Map<String, String> = emptyMap(),
        ) : ToolCall

        /** Local-only: hand this turn to the cloud LLM (PLAN 5.6). */
        data class Escalate(val reason: String) : ToolCall {
            override val name: String = "escalate"
            override val arguments: Map<String, String> = mapOf("reason" to reason)
        }
    }

    /** Extracts ALL tool calls from a full assistant message. */
    fun parseAll(message: String): List<ToolCall> {
        val calls = mutableListOf<ToolCall>()
        var rest = message
        while (true) {
            val open = rest.indexOf(StreamingGenerator.TOOL_OPEN)
            if (open == -1) break
            val close = rest.indexOf(StreamingGenerator.TOOL_CLOSE, open)
            val json = if (close == -1) {
                rest.substring(open + StreamingGenerator.TOOL_OPEN.length)
            } else {
                rest.substring(open + StreamingGenerator.TOOL_OPEN.length, close)
            }
            calls += parseOne(json)
            if (close == -1) break
            rest = rest.substring(close + StreamingGenerator.TOOL_CLOSE.length)
        }
        // Bare-JSON fallback when the model skipped the wrapper entirely.
        if (calls.isEmpty() && message.trimStart().startsWith("{")) {
            calls += parseOne(message.trim())
        }
        return calls
    }

    /** Convenience for the common single-call case; null when no call present. */
    fun parseFirst(message: String): ToolCall? = parseAll(message).firstOrNull()

    private fun parseOne(json: String): ToolCall {
        return try {
            val obj = org.json.JSONObject(json.trim())
            val name = obj.optString("name", "").trim()
            val args = readArguments(obj)
            when {
                name == "escalate" ->
                    ToolCall.Escalate(args["reason"].takeUnless { it.isNullOrBlank() } ?: "unspecified")
                name in KNOWN_TOOLS -> ToolCall.Device(name, args)
                name.isEmpty() -> ToolCall.Escalate("empty_tool_name")
                else -> ToolCall.Escalate("unknown_tool:$name")
            }
        } catch (_: org.json.JSONException) {
            ToolCall.Escalate("malformed_tool_json")
        }
    }

    private fun readArguments(obj: org.json.JSONObject): Map<String, String> {
        val raw = obj.opt("arguments") ?: return emptyMap()
        val argObj = when (raw) {
            is org.json.JSONObject -> raw
            is String -> try {
                org.json.JSONObject(raw)
            } catch (_: org.json.JSONException) {
                return mapOf("value" to raw)
            }
            else -> return mapOf("value" to raw.toString())
        }
        return buildMap {
            val keys = argObj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                put(k, argObj.optString(k, ""))
            }
        }
    }
}

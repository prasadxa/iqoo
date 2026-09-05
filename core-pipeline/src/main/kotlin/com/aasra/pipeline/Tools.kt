package com.aasra.pipeline

/**
 * Shared tool schema for the local (Qwen) and cloud (sarvam/kimi) LLMs
 * (PLAN 6.1) + the ChatML <tool_call> parser. Uses org.json (in-framework),
 * so core-pipeline needs no serialization plugin.
 *
 * [ToolCallParser.parse] extracts every <tool_call>{...}</tool_call> JSON
 * object from a generated turn. Qwen emits {"name": ..., "arguments": {...}}.
 */
data class ToolDefinition(
    val name: String,
    val description: String,
    /** JSON-Schema object string, sent verbatim to both LLMs. */
    val parametersJson: String,
)

object SharedTools {
    val GET_TIME = ToolDefinition(
        "get_time", "Current time as a spoken string.",
        """{"type":"object","properties":{}}""",
    )
    val CALL_CONTACT = ToolDefinition(
        "call_contact", "Call a saved contact by name or nickname.",
        """{"type":"object","properties":{"name":{"type":"string"}},"required":["name"]}""",
    )
    val SEND_SMS = ToolDefinition(
        "send_sms", "Send an SMS. Confirm with the user first.",
        """{"type":"object","properties":{"name":{"type":"string"},"message":{"type":"string"}},"required":["name","message"]}""",
    )
    val SET_REMINDER = ToolDefinition(
        "set_reminder", "Set a spoken reminder.",
        """{"type":"object","properties":{"text":{"type":"string"},"time":{"type":"string"},"repeat":{"type":"string"}},"required":["text","time"]}""",
    )
    val LIST_REMINDERS = ToolDefinition(
        "list_reminders", "List saved reminders.",
        """{"type":"object","properties":{}}""",
    )
    val CANCEL_REMINDER = ToolDefinition(
        "cancel_reminder", "Cancel a reminder by id.",
        """{"type":"object","properties":{"id":{"type":"string"}},"required":["id"]}""",
    )
    val SOS = ToolDefinition(
        "sos", "Emergency: call primary contact and SMS location to all emergency contacts.",
        """{"type":"object","properties":{}}""",
    )
    val WEB_SEARCH = ToolDefinition(
        "web_search", "Cloud only. Search the web for current info.",
        """{"type":"object","properties":{"query":{"type":"string"}},"required":["query"]}""",
    )
    /** Local-only; the cloud model never sees it. Handled by the Router. */
    val ESCALATE = ToolDefinition(
        "escalate", "Local model only: hand this turn to the cloud LLM.",
        """{"type":"object","properties":{"reason":{"type":"string","enum":["medical","current_info","long_complex","low_confidence","other"]}},"required":["reason"]}""",
    )

    /** Sent to the LOCAL model. */
    val LOCAL: List<ToolDefinition> = listOf(
        GET_TIME, CALL_CONTACT, SEND_SMS, SET_REMINDER, LIST_REMINDERS,
        CANCEL_REMINDER, SOS, ESCALATE,
    )

    /** Sent to the CLOUD model (escalate is meaningless there). */
    val CLOUD: List<ToolDefinition> = listOf(
        GET_TIME, CALL_CONTACT, SEND_SMS, SET_REMINDER, LIST_REMINDERS,
        CANCEL_REMINDER, SOS, WEB_SEARCH,
    )
}

object ToolCallParser {
    private val TOOL_CALL_RE = Regex("<tool_call>(.*?)</tool_call>", RegexOption.DOT_MATCHES_ALL)

    /** Returns parsed calls; malformed blocks are skipped, never throw. */
    fun parse(generatedText: String): List<ToolCall> {
        val out = ArrayList<ToolCall>()
        for (m in TOOL_CALL_RE.findAll(generatedText)) {
            try {
                val obj = org.json.JSONObject(m.groupValues[1])
                val name = obj.optString("name", "")
                if (name.isBlank()) continue
                val args = obj.opt("arguments")?.toString() ?: "{}"
                out.add(ToolCall(name, args))
            } catch (_: org.json.JSONException) {
                continue
            }
        }
        return out
    }

    /** Strips tool-call blocks so the remainder can be spoken. */
    fun stripToolCalls(generatedText: String): String =
        TOOL_CALL_RE.replace(generatedText, "").trim()
}

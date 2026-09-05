package com.aasra.tools

/**
 * Shared JSON tool schema for the local LLM (Qwen3.5 ChatML `<tool_call>`,
 * PLAN 4.5) and the cloud path (chat completions `tools` array + Managed
 * Voice Agent `Settings`, PLAN 5.1/5.4).
 *
 * Both brains get the same names + parameters so a conversation behaves
 * identically whichever model is driving. Two deliberate asymmetries
 * (PLAN 6.1):
 * - `web_search` is cloud-only (costs 1 credit, needs network).
 * - `escalate` is local-only (a no-op for the cloud model).
 *
 * Parameters are raw JSON strings: neither tools/ nor engine-llama takes a
 * serialization plugin, and cloud-callmissed wraps these into its own
 * request bodies with JSONObject/OkHttp.
 */
data class ToolResult(
    /** True if the action completed (or safely did nothing, e.g. cancelled). */
    val ok: Boolean,
    /**
     * Short spoken-style reply for the TTS queue. Always non-technical and
     * in the user's language — never an exception message or HTTP code.
     */
    val spokenReply: String,
    /**
     * Machine-readable side channel: "needs-confirmation" for the
     * confirm-before-send/call flow, human summaries for list calls.
     */
    val data: String? = null,
)

data class ToolDefinition(
    val name: String,
    val description: String,
    /** OpenAI function-style `parameters` object, as a JSON string. */
    val parametersJson: String,
) {
    /** Wraps into {"type":"function","function":{...}} with plain maps. */
    fun toFunctionMap(): Map<String, Any?> = mapOf(
        "type" to "function",
        "function" to mapOf(
            "name" to name,
            "description" to description,
            "parameters" to parametersJson,
        ),
    )
}

object ToolSchema {

    const val CALL_CONTACT = "call_contact"
    const val SEND_SMS = "send_sms"
    const val SET_REMINDER = "set_reminder"
    const val LIST_REMINDERS = "list_reminders"
    const val CANCEL_REMINDER = "cancel_reminder"
    const val SOS = "sos"
    const val GET_TIME = "get_time"
    const val GET_DATE = "get_date"
    const val SET_VOLUME = "set_volume"
    const val FLASHLIGHT = "flashlight"
    const val READ_NOTIFICATIONS = "read_notifications"
    const val WEB_SEARCH = "web_search"
    const val ESCALATE = "escalate"

    val LOCAL_ONLY = setOf(ESCALATE)
    val CLOUD_ONLY = setOf(WEB_SEARCH)

    private fun strProp(desc: String): String = """{"type":"string","description":"$desc"}"""
    private fun intProp(desc: String): String = """{"type":"integer","description":"$desc"}"""
    private fun boolProp(desc: String): String = """{"type":"boolean","description":"$desc"}"""
    private fun enumProp(desc: String, vararg values: String): String =
        """{"type":"string","description":"$desc","enum":[${values.joinToString(",") { "\"$it\"" }}]}"""

    private fun obj(
        vararg props: Pair<String, String>,
        required: List<String> = emptyList(),
    ): String {
        val body = props.joinToString(",") { (k, v) -> "\"$k\":$v" }
        val req = if (required.isEmpty()) "" else ""","required":[${required.joinToString(",") { "\"$it\"" }}]"""
        return """{"type":"object","properties":{$body}$req}"""
    }

    val CALL_CONTACT_DEF = ToolDefinition(
        CALL_CONTACT,
        "Call someone by spoken name. The app asks which number when ambiguous, then asks confirmation. After each user reply, invoke again with the original name. Never claim success until the tool succeeds.",
        obj("name" to strProp("Who to call, exactly as the user said it (name or nickname like beta, bahu)"), required = listOf("name")),
    )

    val SEND_SMS_DEF = ToolDefinition(
        SEND_SMS,
        "Send a text message. The app resolves the recipient and confirms the exact number and message. After a user reply, invoke again with the original name and unchanged message.",
        obj(
            "name" to strProp("Recipient name or nickname"),
            "message" to strProp("Message text to send"),
            required = listOf("name", "message"),
        ),
    )

    val SET_REMINDER_DEF = ToolDefinition(
        SET_REMINDER,
        "Set a spoken reminder (medicine, water, walk). Time is local ISO-8601, e.g. 2026-09-04T08:00:00.",
        obj(
            "text" to strProp("What the reminder is about, e.g. dawai lena"),
            "time" to strProp("Local ISO-8601 date-time when it should fire"),
            "repeat" to enumProp("Once or every day", "once", "daily"),
            required = listOf("text", "time"),
        ),
    )

    val LIST_REMINDERS_DEF = ToolDefinition(
        LIST_REMINDERS,
        "List upcoming reminders with their ids.",
        obj(),
    )

    val CANCEL_REMINDER_DEF = ToolDefinition(
        CANCEL_REMINDER,
        "Cancel a reminder by the id returned from list_reminders.",
        obj("id" to intProp("Reminder id"), required = listOf("id")),
    )

    val SOS_DEF = ToolDefinition(
        SOS,
        "Emergency: call the primary emergency contact and text the user's location to ALL emergency contacts. Use immediately when the user asks for help or says an emergency word.",
        obj(),
    )

    val GET_TIME_DEF = ToolDefinition(
        GET_TIME,
        "Get the current local time. Use for 'what time is it' questions.",
        obj(),
    )

    val GET_DATE_DEF = ToolDefinition(
        GET_DATE,
        "Get today's date and day of the week.",
        obj(),
    )

    val SET_VOLUME_DEF = ToolDefinition(
        SET_VOLUME,
        "Set the media/speech volume.",
        obj("level" to intProp("Volume from 0 (silent) to 10 (loudest)"), required = listOf("level")),
    )

    val FLASHLIGHT_DEF = ToolDefinition(
        FLASHLIGHT,
        "Turn the phone torch on or off.",
        obj("on" to boolProp("True to switch on, false to switch off"), required = listOf("on")),
    )

    val READ_NOTIFICATIONS_DEF = ToolDefinition(
        READ_NOTIFICATIONS,
        "Read recent phone notifications aloud (last 3 by default). Only works if notification access was granted in Settings during onboarding.",
        obj("limit" to intProp("How many recent notifications to read, max 5")),
    )

    val WEB_SEARCH_DEF = ToolDefinition(
        WEB_SEARCH,
        "Search the web for current information (news, weather, prices). Cloud model only.",
        obj("query" to strProp("Search query"), required = listOf("query")),
    )

    val ESCALATE_DEF = ToolDefinition(
        ESCALATE,
        "Hand this turn to the cloud model. Local model only; never emitted by the cloud model.",
        obj(
            "reason" to enumProp(
                "Why this needs the cloud model",
                "medical", "low_confidence", "needs_current_info", "complex_request", "other",
            ),
            required = listOf("reason"),
        ),
    )

    private val COMMON: List<ToolDefinition> = listOf(
        CALL_CONTACT_DEF, SEND_SMS_DEF, SET_REMINDER_DEF, LIST_REMINDERS_DEF,
        CANCEL_REMINDER_DEF, SOS_DEF, GET_TIME_DEF, GET_DATE_DEF,
        SET_VOLUME_DEF, FLASHLIGHT_DEF, READ_NOTIFICATIONS_DEF,
    )

    /** Schema for the on-device Qwen model: everything common + escalate, no web_search. */
    fun localTools(): List<ToolDefinition> = COMMON + ESCALATE_DEF

    /** Schema for chat completions + Managed Voice Agent Settings: everything common + web_search, no escalate. */
    fun cloudTools(): List<ToolDefinition> = COMMON + WEB_SEARCH_DEF

    fun byName(name: String): ToolDefinition? =
        (COMMON + WEB_SEARCH_DEF + ESCALATE_DEF).find { it.name == name }
}

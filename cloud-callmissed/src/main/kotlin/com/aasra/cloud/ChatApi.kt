package com.aasra.cloud

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.EOFException

private val chatJson = Json { ignoreUnknownKeys = true; explicitNulls = false }

@Serializable
data class ChatMessage(
    val role: String,
    val content: String,
    val tool_call_id: String? = null,
    val name: String? = null,
)

@Serializable
data class ChatToolCall(
    val id: String,
    val name: String,
    val arguments: String,
)

/** Stream events for one chat turn. `Content` deltas feed the sentence chunker. */
sealed interface ChatStreamEvent {
    data class Content(val delta: String) : ChatStreamEvent
    data class ToolCalls(val calls: List<ChatToolCall>) : ChatStreamEvent
    data class Usage(val promptTokens: Int, val completionTokens: Int, val totalTokens: Int) : ChatStreamEvent
    data object Done : ChatStreamEvent
}

/**
 * POST /v1/chat/completions with SSE streaming (PLAN 5.1).
 * model sarvam-105b-conversations, stream=true, temp 0.4, max_tokens 300,
 * reasoning_effort low, stream_options.include_usage=true.
 * Sends system prompt + last 10 turns + tools array.
 *
 * Fallback chain on 429/503: sarvam-105b-conversations -> kimi-k2.5 -> gpt-oss-120b.
 * Never retries 402 or 429 quota_exceeded ([CloudErrors]).
 */
class ChatApi(
    private val http: OkHttpClient,
    private val apiKey: String,
    private val baseUrl: String = CallMissedConfig.BASE_URL,
    private val usage: UsageCounter = UsageCounter(),
) {
    fun stream(
        history: List<ChatMessage>,
        systemPrompt: String = AasraPrompts.SYSTEM,
        tools: JsonArray = AasraTools.schemas(),
        model: String = CallMissedConfig.CHAT_MODEL_PRIMARY,
    ): Flow<ChatStreamEvent> = flow {
        var current = model
        while (true) {
            try {
                emitAll(streamOnce(current, history, systemPrompt, tools))
                return@flow
            } catch (e: CallMissedException) {
                when (e.action) {
                    CloudAction.STOP -> throw e // 401/402/quota: never retry
                    CloudAction.RETRY_WITH_JITTER -> {
                        // Honor the server's Retry-After hint when present
                        // (docs: sent on 429s), else a single jittered retry,
                        // then fall through the model chain.
                        val waitMs = e.retryAfterMs.takeIf { it > 0 }
                            ?: CloudErrors.retryDelayMs()
                        delay(waitMs)
                        try {
                            emitAll(streamOnce(current, history, systemPrompt, tools))
                            return@flow
                        } catch (retry: CallMissedException) {
                            if (retry.action == CloudAction.STOP) throw retry
                        }
                        current = CloudErrors.nextFallback(current) ?: throw e
                    }
                    CloudAction.SWITCH_MODEL, CloudAction.FALLBACK ->
                        current = CloudErrors.nextFallback(current) ?: throw e
                }
            }
        }
    }

    private fun streamOnce(
        model: String,
        history: List<ChatMessage>,
        systemPrompt: String,
        tools: JsonArray,
    ): Flow<ChatStreamEvent> = callbackFlow {
        val messages = buildList {
            add(ChatMessage("system", systemPrompt))
            addAll(history.takeLast(CallMissedConfig.CHAT_HISTORY_MESSAGES))
        }
        val bodyJson = buildJsonObject {
            put("model", model)
            put("stream", true)
            put("temperature", CallMissedConfig.CHAT_TEMPERATURE)
            put("max_tokens", CallMissedConfig.CHAT_MAX_TOKENS)
            put("reasoning_effort", CallMissedConfig.CHAT_REASONING_EFFORT)
            putJsonObject("stream_options") { put("include_usage", true) }
            put("messages", chatJson.encodeToJsonElement(
                kotlinx.serialization.builtins.ListSerializer(ChatMessage.serializer()), messages,
            ))
            put("tools", tools)
        }.toString()

        val req = Request.Builder()
            .url("$baseUrl/v1/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(bodyJson.toRequestBody("application/json".toMediaType()))
            .build()
        val call = http.newCall(req)
        val onIo = kotlinx.coroutines.CoroutineScope(Dispatchers.IO)
        onIo.launch {
            var response: okhttp3.Response? = null
            try {
                response = call.execute()
                usage.record(response.headers)
                if (!response.isSuccessful) {
                    val errBody = response.body?.string().orEmpty()
                    close(
                        CallMissedException(
                            response.code,
                            CloudErrors.parseCode(errBody),
                            errBody.take(500),
                            CloudErrors.retryAfterMs(response.headers),
                        ),
                    )
                    return@launch
                }
                val source = response.body!!.source()
                val pendingTools = mutableMapOf<Int, ToolCallFrag>()
                var completed = false
                while (!source.exhausted()) {
                    val line = source.readUtf8Line() ?: break
                    val data = line.trim()
                    if (!data.startsWith("data:")) continue
                    val payload = data.removePrefix("data:").trim()
                    if (payload == "[DONE]") {
                        completed = true
                        break
                    }
                    if (payload.isEmpty()) continue
                    try {
                        parseChunk(payload, pendingTools)
                    } catch (_: Exception) {
                        // Skip malformed SSE chunks; keep the stream alive.
                    }
                }
                if (!completed) throw EOFException("Chat stream ended before [DONE]")
                val calls = pendingTools.toSortedMap().values.mapNotNull { it.build() }
                if (calls.isNotEmpty()) trySend(ChatStreamEvent.ToolCalls(calls))
                trySend(ChatStreamEvent.Done)
                close()
            } catch (e: Exception) {
                if (e is CallMissedException) close(e) else close(e)
            } finally {
                response?.close()
            }
        }
        awaitClose { call.cancel() }
    }

    private suspend fun kotlinx.coroutines.channels.SendChannel<ChatStreamEvent>.parseChunk(
        payload: String,
        pendingTools: MutableMap<Int, ToolCallFrag>,
    ) {
        val el = chatJson.parseToJsonElement(payload).jsonObject
        el["usage"]?.let {
            try {
                val u = it.jsonObject
                send(
                    ChatStreamEvent.Usage(
                        u["prompt_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
                        u["completion_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
                        u["total_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
                    ),
                )
            } catch (_: Exception) { }
        }
        val choices = el["choices"]?.jsonArray ?: return
        for (ch in choices) {
            val o = ch.jsonObject
            o["delta"]?.jsonObject?.let { delta ->
                delta["content"]?.jsonPrimitive?.contentOrNull?.let { text ->
                    if (text.isNotEmpty()) send(ChatStreamEvent.Content(text))
                }
                delta["tool_calls"]?.jsonArray?.forEach { tc ->
                    val t = tc.jsonObject
                    val idx = t["index"]?.jsonPrimitive?.intOrNull ?: 0
                    val frag = pendingTools.getOrPut(idx) { ToolCallFrag() }
                    t["id"]?.jsonPrimitive?.contentOrNull?.let { frag.id = it }
                    t["function"]?.jsonObject?.let { fn ->
                        fn["name"]?.jsonPrimitive?.contentOrNull?.let { frag.name = it }
                        fn["arguments"]?.jsonPrimitive?.contentOrNull?.let { frag.args += it }
                    }
                }
            }
            // Non-streaming-shaped finish carrying full tool_calls also handled.
            if (o["finish_reason"]?.jsonPrimitive?.contentOrNull == "tool_calls") {
                o["message"]?.jsonObject?.get("tool_calls")?.jsonArray?.forEach { tc ->
                    val t = tc.jsonObject
                    val fn = t["function"]?.jsonObject ?: JsonObject(emptyMap())
                    pendingTools[pendingTools.size] = ToolCallFrag(
                        id = t["id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        name = fn["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        args = fn["arguments"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    )
                }
            }
        }
    }

    private class ToolCallFrag(var id: String = "", var name: String = "", var args: String = "") {
        fun build(): ChatToolCall? =
            if (name.isNotEmpty()) ChatToolCall(id, name, args) else null
    }
}

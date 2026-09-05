package com.aasra.cloud

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Offline mock of the cloud layer for tests and airplane-mode development.
 * Emits canned data, performs no network I/O, burns no quota.
 * Mirrors the [CallMissedClient] surface (chat / STT / TTS / search) so
 * pipeline tests can swap it in without touching production code.
 */
class FakeCallMissedClient {
    /** Canned streaming reply, chunked word-by-word like SSE deltas. */
    fun stream(
        history: List<ChatMessage>,
        cannedReply: String = "Namaste! Main Aasra hoon. Aap kaise hain?",
    ): Flow<ChatStreamEvent> = flow {
        for (word in cannedReply.split(" ")) {
            delay(20)
            emit(ChatStreamEvent.Content("$word "))
        }
        emit(ChatStreamEvent.ToolCalls(emptyList()))
        emit(ChatStreamEvent.Usage(10, cannedReply.length / 4, 10 + cannedReply.length / 4))
        emit(ChatStreamEvent.Done)
    }

    /** Canned tool-call turn for tool-path tests. */
    fun streamToolCall(name: String = "get_time", arguments: String = "{}"): Flow<ChatStreamEvent> = flow {
        emit(
            ChatStreamEvent.ToolCalls(
                listOf(ChatToolCall(id = "fake-1", name = name, arguments = arguments)),
            ),
        )
        emit(ChatStreamEvent.Done)
    }

    suspend fun transcribe(wav16k: ByteArray, hinglish: Boolean = false): SttResult {
        check(wav16k.isNotEmpty()) { "empty audio" }
        return SttResult(text = "what time is it", language = if (hinglish) "hi" else "en")
    }

    /** Silent PCM16-24k mono, ~100 ms per 10 chars of text. No quota burned. */
    suspend fun synthesize(text: String): ByteArray {
        val ms = (text.length.coerceAtLeast(10) / 10) * 100
        return ByteArray(ms * CallMissedConfig.TTS_SAMPLE_RATE * 2 / 1000)
    }

    suspend fun search(query: String): List<SearchHit> {
        require(query.isNotBlank())
        return listOf(SearchHit(title = "Fake result for $query", url = "https://example.com", snippet = "offline stub"))
    }
}

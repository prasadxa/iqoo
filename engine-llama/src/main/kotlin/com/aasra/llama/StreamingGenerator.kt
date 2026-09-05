package com.aasra.llama

/**
 * Splits a streaming token flow into speakable sentences.
 *
 * The pipeline feeds partial LLM text token-by-token; TTS must start on the
 * FIRST complete sentence, not at the end of the reply (PLAN 4.5). This class
 * is pure Kotlin (no Android deps) so the orchestrator and unit tests share it.
 *
 * Boundaries: `.` `!` `?` `।` followed by whitespace/end. A trailing chunk
 * shorter than [minSentenceLength] is held back until [flush] so TTS never
 * speaks "Mr." style fragments — with one guard: [maxBufferedChars] forces an
 * emit so a run-on sentence cannot stall speech forever.
 *
 * `<tool_call>…</tool_call>` blocks are swallowed whole and delivered via
 * [onToolCallXml], never to [onSentence], so the user never hears JSON.
 */
class StreamingGenerator(
    private val minSentenceLength: Int = 8,
    private val maxBufferedChars: Int = 220,
    private val onSentence: (String) -> Unit = {},
    private val onToolCallXml: (String) -> Unit = {},
) {
    private val buffer = StringBuilder()
    private val pending = StringBuilder()
    private var blockEnd: String? = null

    /** Feed one decoded token (or token batch). */
    fun accept(token: String) {
        pending.append(token)
        while (pending.isNotEmpty()) {
            val end = blockEnd
            if (end != null) {
                val close = pending.indexOf(end)
                if (close < 0) break
                val length = close + end.length
                if (end == TOOL_CLOSE) onToolCallXml(pending.substring(0, length))
                pending.delete(0, length)
                blockEnd = null
                continue
            }
            val open = listOf(TOOL_OPEN, "<think>").map { it to pending.indexOf(it) }
                .filter { it.second >= 0 }.minByOrNull { it.second }
            if (open != null) {
                buffer.append(pending.substring(0, open.second))
                pending.delete(0, open.second)
                drain()
                blockEnd = if (open.first == TOOL_OPEN) TOOL_CLOSE else "</think>"
                continue
            }
            // Keep a possible tag prefix until the next token, even if '<' arrived alone.
            val retained = (1 until TOOL_OPEN.length).filter { count ->
                count <= pending.length && listOf(TOOL_OPEN, "<think>").any {
                    it.startsWith(pending.takeLast(count).toString())
                }
            }.maxOrNull() ?: 0
            buffer.append(pending.substring(0, pending.length - retained))
            pending.delete(0, pending.length - retained)
            drain()
            break
        }
        if (buffer.length >= maxBufferedChars) emitForced()
    }

    /** Call when generation finishes: speaks whatever text remains. */
    fun flush() {
        val tail = buffer.toString().trim()
        buffer.clear()
        if (tail.isNotEmpty()) onSentence(tail)
        pending.clear() // Incomplete control blocks are not speech or valid tool calls.
        blockEnd = null
    }

    fun reset() {
        buffer.clear()
        pending.clear()
        blockEnd = null
    }

    private fun drain() {
        var text = buffer.toString()
        var idx = findBoundary(text)
        while (idx != -1) {
            val sentence = text.substring(0, idx + 1).trim()
            text = text.substring(idx + 1)
            if (sentence.length >= minSentenceLength) {
                onSentence(sentence)
            } else {
                // Too short: keep it buffered with what follows.
                text = "$sentence $text".trim()
                break
            }
            idx = findBoundary(text)
        }
        buffer.clear()
        buffer.append(text)
    }

    private fun emitForced() {
        val forced = buffer.toString().trim()
        buffer.clear()
        if (forced.isNotEmpty()) onSentence(forced)
    }

    /** Index of the last char of the first complete sentence, or -1. */
    private fun findBoundary(text: String): Int {
        for (i in text.indices) {
            val c = text[i]
            if (c == '.' || c == '!' || c == '?' || c == '।') {
                val next = text.getOrNull(i + 1)
                if (next == null || next.isWhitespace() || next == '"' || next == '\'') return i
            }
        }
        return -1
    }

    companion object {
        const val TOOL_OPEN = "<tool_call>"
        const val TOOL_CLOSE = "</tool_call>"
    }
}

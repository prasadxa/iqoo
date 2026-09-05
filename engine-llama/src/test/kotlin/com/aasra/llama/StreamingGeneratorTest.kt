package com.aasra.llama

import org.junit.Assert.*
import org.junit.Test

class StreamingGeneratorTest {
    @Test fun splitToolAndThinkingTagsNeverReachSpeech() {
        val sentences = mutableListOf<String>()
        val tools = mutableListOf<String>()
        val generator = StreamingGenerator(onSentence = sentences::add, onToolCallXml = tools::add)
        val call = "<tool_call>{\"name\":\"get_time\",\"arguments\":{}}</tool_call>"
        val input = "<think>Private analysis. <tool_call>not a tool</tool_call></think>Let me check. $call"
        input.forEach { generator.accept(it.toString()) }
        generator.flush()
        assertEquals(listOf("Let me check."), sentences)
        assertEquals(listOf(call), tools)
    }

    @Test fun unfinishedControlBlocksAreDiscardedNotSpokenOrExecuted() {
        val spoken = mutableListOf<String>()
        val tools = mutableListOf<String>()
        val generator = StreamingGenerator(onSentence = spoken::add, onToolCallXml = tools::add)
        generator.accept("<think>Unfinished internal text.")
        generator.flush()
        generator.reset()
        generator.accept("<tool_call>{\"name\":\"sos\"}")
        generator.flush()
        assertTrue(spoken.isEmpty())
        assertTrue(tools.isEmpty())
    }

    @Test fun plainSpeechStillStreamsBeforeFlush() {
        val spoken = mutableListOf<String>()
        val generator = StreamingGenerator(onSentence = spoken::add)
        generator.accept("Good morning. ")
        assertEquals(listOf("Good morning."), spoken)
        generator.accept("How are you?")
        generator.flush()
        assertEquals(listOf("Good morning.", "How are you?"), spoken)
    }
}

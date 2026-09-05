package com.aasra.llama

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QwenChatTemplateTest {
    @Test
    fun voiceReplyUsesQwen35NonThinkingPrefix() {
        assertTrue(QwenChatTemplate.build("Hello").endsWith("<|im_start|>assistant\n<think>\n\n</think>\n\n"))
    }

    @Test
    fun localPromptHasEscalationButNotCloudSearch() {
        val prompt = QwenChatTemplate.build("What is the weather?")

        assertTrue(prompt.contains("\"escalate\""))
        assertFalse(prompt.contains("\"web_search\""))
    }

    @Test
    fun historyIsLimitedToTenTurns() {
        val history = (1..22).map { if (it % 2 == 0) "assistant" to "turn-$it" else "user" to "turn-$it" }
        val prompt = QwenChatTemplate.build("latest", history)

        assertFalse(prompt.contains("turn-1\n"))
        assertFalse(prompt.contains("turn-2\n"))
        assertTrue(prompt.contains("turn-3\n"))
        assertTrue(prompt.contains("<|im_start|>assistant\nturn-22"))
    }
}

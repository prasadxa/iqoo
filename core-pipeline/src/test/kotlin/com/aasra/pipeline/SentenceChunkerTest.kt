package com.aasra.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SentenceChunkerTest {

    @Test
    fun splitsOnPeriodsAndDanda() {
        val chunker = SentenceChunker(minSentenceChars = 8)
        val out1 = chunker.push("Hello there! ")
        assertEquals(listOf("Hello there!"), out1)

        val out2 = chunker.push("Yeh ek sentence hai। ")
        assertEquals(listOf("Yeh ek sentence hai।"), out2)

        val out3 = chunker.push("What time is it? ")
        assertEquals(listOf("What time is it?"), out3)
    }

    @Test
    fun guardsAbbreviations() {
        val chunker = SentenceChunker(minSentenceChars = 8)
        val out1 = chunker.push("Dr. Sharma is arriving today. ")
        assertEquals(listOf("Dr. Sharma is arriving today."), out1)

        val out2 = chunker.push("Meet Mr. Verma at 5 pm. ")
        assertEquals(listOf("Meet Mr. Verma at 5 pm."), out2)
    }

    @Test
    fun handlesStreamingTokensAndFlush() {
        val chunker = SentenceChunker(minSentenceChars = 8)
        val tokens = listOf("Aap ", "kaise ", "hain? ", "Main ", "theek ", "hoon")
        val emitted = mutableListOf<String>()
        for (t in tokens) {
            emitted += chunker.push(t)
        }
        assertEquals(listOf("Aap kaise hain?"), emitted)

        val tail = chunker.flush()
        assertEquals(listOf("Main theek hoon"), tail)
    }
}

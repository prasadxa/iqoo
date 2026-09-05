package com.aasra.sherpa

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SherpaVadTest {
    @Test
    fun manualFinishReturnsCapturedSpeechAndClearsIt() {
        val vad = SherpaVad(File("unused"))
        repeat(20) { vad.acceptSamples(ShortArray(320) { 4000 }) }
        assertEquals(6400, vad.finishSegment().size)
        assertTrue(vad.finishSegment().isEmpty())
    }

    @Test
    fun fallbackUsesSampleDurationNotAssumedTwentyMillisecondFrames() {
        val events = mutableListOf<SherpaVad.VadEvent>()
        val vad = SherpaVad(File("unused"))
        vad.listener = object : SherpaVad.Listener {
            override fun onEvent(event: SherpaVad.VadEvent) { events += event }
        }
        repeat(3) { vad.acceptSamples(ShortArray(1600) { 4000 }) }
        repeat(13) { vad.acceptSamples(ShortArray(1600)) }
        assertEquals(1, events.filterIsInstance<SherpaVad.VadEvent.SpeechStart>().size)
        assertEquals(1, events.filterIsInstance<SherpaVad.VadEvent.SpeechEnd>().size)
    }

    @Test
    fun continuousNoiseCannotGrowTheSegmentWithoutBound() {
        val events = mutableListOf<SherpaVad.VadEvent>()
        val vad = SherpaVad(File("unused"))
        vad.listener = object : SherpaVad.Listener {
            override fun onEvent(event: SherpaVad.VadEvent) { events += event }
        }
        repeat(1600) { vad.acceptSamples(ShortArray(320) { 4000 }) }
        val ended = events.filterIsInstance<SherpaVad.VadEvent.SpeechEnd>()
        assertTrue(ended.isNotEmpty())
        assertTrue(ended.all { it.samples.size <= 16_000 * 30 })
    }
}

package com.aasra.sherpa

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class PiperFallbackTtsTest {
    @Test fun synthesisDoesNotLoadModelFilesOnTheAudioWorker() {
        var reads = 0
        val tts = PiperFallbackTts(File("missing-models"), openAsset = { reads++; null })
        assertEquals(0, tts.synthesize("Hello", "en").samples.size)
        assertEquals(0, reads)
    }
}

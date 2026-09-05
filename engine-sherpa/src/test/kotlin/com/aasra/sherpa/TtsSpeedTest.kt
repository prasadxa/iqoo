package com.aasra.sherpa

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class TtsSpeedTest {
    @Test fun nativeSpeedIsSlowerByDefaultAndIncreasesWithPreference() {
        assertEquals(1f / 1.1f, SherpaTts.speedFor(1f), 0.0001f)
        assertEquals(0.9f, PiperFallbackTts.speedFor(1f), 0.0001f)
        for (speed in listOf<(Float) -> Float>(SherpaTts::speedFor, PiperFallbackTts::speedFor)) {
            assertTrue(speed(0.8f) < speed(1f))
            assertTrue(speed(1.2f) > speed(1f))
            assertEquals(speed(0.5f), speed(-1f), 0f)
            assertEquals(speed(1.5f), speed(99f), 0f)
            assertEquals(speed(1f), speed(Float.NaN), 0f)
            assertEquals(speed(1f), speed(Float.POSITIVE_INFINITY), 0f)
        }
    }

    @Test fun emptyAndUnsafeInputDoesNotStartOrInvalidateLocalModels() {
        val kokoro = SherpaTts(File("missing-models"))
        val piper = PiperFallbackTts(File("missing-models"))
        for (text in listOf("", "<speak><break/></speak>", "<prosody rate='slow'", "a".repeat(4097))) {
            assertEquals(0, kokoro.synthesize(text, "hi").samples.size)
            assertEquals(0, piper.synthesize(text, "hi").samples.size)
        }
        assertFalse(kokoro.ready)
        assertFalse(piper.ready)
    }
}

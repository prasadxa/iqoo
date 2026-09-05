package com.aasra.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

class EchoControllerTest {
    @Test
    fun cloudUplinkSendsSameLengthSilenceForLoudEchoAndStraddlingReads() {
        var nanos = 0L
        val echo = EchoController { nanos }
        val loudPcm = ByteArray(640) { 0x7f }
        echo.ttsPlaying = true
        assertArrayEquals(ByteArray(320), echo.microphonePcm(loudPcm, 320, true))
        assertFalse("The same gate rejects server barge-in", echo.shouldFeedVad(1f))
        echo.ttsPlaying = false
        nanos += EchoController.ECHO_TAIL_MS * 1_000_000
        assertArrayEquals(ByteArray(640), echo.microphonePcm(loudPcm, 640, false))
        val resumed = echo.microphonePcm(loudPcm, 640, true)
        assertArrayEquals(loudPcm, resumed)
        assertNotSame("Capture reuses its buffer; the socket needs its own copy", loudPcm, resumed)
        assertTrue(echo.shouldFeedVad(1f))
    }

    @Test
    fun cloudDoneAndServerBargeInCannotOpenCaptureBeforeHardwareDrain() {
        var nanos = 0L
        val echo = EchoController { nanos }
        echo.ttsPlaying = true // AgentStartedSpeaking, including gaps before PCM.
        assertFalse(echo.shouldFeedVad(1f))
        echo.playbackPlaying = true
        echo.ttsPlaying = false // AgentAudioDone means sent, not played.
        nanos += 10_000_000_000L
        assertFalse("Mic uplink and server barge-in must both remain blocked", echo.shouldFeedVad(1f))
        echo.playbackPlaying = false
        assertFalse(echo.shouldFeedVad(1f))
        nanos += EchoController.ECHO_TAIL_MS * 1_000_000 - 1
        assertFalse(echo.shouldFeedVad(1f))
        echo.playbackPlaying = false // Polling must not extend the tail forever.
        nanos++
        assertTrue(echo.shouldFeedVad(1f))
    }

    @Test
    fun aNewSentenceDuringTheTailRearmsTheGate() {
        var nanos = 0L
        val echo = EchoController { nanos }
        echo.playbackPlaying = true
        echo.playbackPlaying = false
        nanos += 300_000_000L
        echo.playbackPlaying = true
        nanos += 500_000_000L
        assertFalse(echo.shouldFeedVad(1f))
        echo.playbackPlaying = false
        assertFalse(echo.shouldFeedVad(1f))
        nanos += EchoController.ECHO_TAIL_MS * 1_000_000
        assertTrue(echo.shouldFeedVad(1f))
    }

    @Test
    fun evenFullScaleSpeakerEchoCannotInterruptPlayback() {
        val echo = EchoController()
        echo.ttsPlaying = true
        for (energy in listOf(0f, 0.08f, 0.5f, 1f, Float.NaN)) {
            assertFalse("Speaker audio must not pass at energy $energy", echo.shouldFeedVad(energy))
        }
    }

    @Test
    fun playbackCompletionKeepsAnEchoTailBeforeListeningAgain() {
        val echo = EchoController()
        assertTrue(echo.shouldFeedVad(1f))
        echo.ttsPlaying = true
        echo.ttsPlaying = false
        assertFalse("Buffered capture and room echo outlive playback", echo.shouldFeedVad(1f))
        Thread.sleep(600)
        assertTrue(echo.shouldFeedVad(1f))
    }
}

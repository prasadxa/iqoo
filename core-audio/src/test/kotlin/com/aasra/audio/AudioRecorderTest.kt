package com.aasra.audio

import android.media.AudioRecord
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class AudioRecorderTest {
    @Test
    fun speakerAudioIsAlsoRemovedFromTheCloudRelistenRing() {
        val echo = EchoController().apply { ttsPlaying = true }
        val recorder = AudioRecorder(echoController = echo) { _, feedVad -> assertFalse(feedVad) }
        captureOneFrame(recorder) { }
        assertArrayEquals(ShortArray(AudioRecorder.FRAME_SAMPLES), recorder.lastSeconds(1))
    }

    @Test
    fun captureStraddlingTheEndOfTheTailIsStillSuppressed() {
        var nanos = 0L
        val echo = EchoController { nanos }
        echo.ttsPlaying = true
        echo.ttsPlaying = false
        val recorder = AudioRecorder(echoController = echo) { _, feedVad ->
            assertFalse("The read began with echo still in the microphone buffer", feedVad)
        }
        captureOneFrame(recorder) { nanos += 1_000_000_000L }
    }

    private fun captureOneFrame(recorder: AudioRecorder, duringRead: () -> Unit) {
        val record = mock(AudioRecord::class.java)
        val running = AudioRecorder::class.java.getDeclaredField("running").apply { isAccessible = true }
        AudioRecorder::class.java.getDeclaredField("recorder").apply { isAccessible = true }.set(recorder, record)
        running.setBoolean(recorder, true)
        `when`(record.read(any(ShortArray::class.java), anyInt(), anyInt())).thenAnswer {
            it.getArgument<ShortArray>(0).fill(Short.MAX_VALUE)
            duringRead()
            running.setBoolean(recorder, false)
            AudioRecorder.FRAME_SAMPLES
        }
        AudioRecorder::class.java.getDeclaredMethod("captureLoop").apply { isAccessible = true }.invoke(recorder)
    }
}

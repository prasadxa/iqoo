package com.aasra.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder

/**
 * Mic capture: 16 kHz mono PCM16, VOICE_RECOGNITION source, 20 ms frames
 * (PLAN 4.2). Frames are appended to [ring] and delivered to [onFrame] with the
 * [EchoController] verdict attached, so the caller knows whether to feed VAD.
 *
 * CALLER (app module, Track A) must hold RECORD_AUDIO before [start]; this
 * class only suppresses the lint check, it does not request permission.
 * Foreground-service lifetime is also owned by app/.
 */
class AudioRecorder(
    private val echoController: EchoController = EchoController(),
    private val ringSeconds: Int = 30,
    private val onFrame: ((frame: ShortArray, feedVad: Boolean) -> Unit)? = null,
) {
    private var recorder: AudioRecord? = null
    @Volatile private var running = false
    private var thread: Thread? = null

    /** Rolling window of recent mic audio; backs cloud re-listen (PLAN 5.2). */
    val ring = RingBuffer(SAMPLE_RATE_HZ * ringSeconds)

    val isRunning: Boolean get() = running

    /**
     * Opens the mic and starts the capture thread. Returns false if the device
     * refused initialisation (bad state / another app holds the mic).
     */
    @SuppressLint("MissingPermission") // permission owned by app/ (see kdoc)
    fun start(): Boolean {
        if (running) return true
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf == AudioRecord.ERROR || minBuf == AudioRecord.ERROR_BAD_VALUE) return false
        // 4x the 20 ms frame: absorbs scheduler jitter without adding latency.
        val bufferBytes = maxOf(minBuf, FRAME_SAMPLES * BYTES_PER_SAMPLE * 4)
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferBytes,
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return false
        }
        return try {
            rec.startRecording()
            recorder = rec
            running = true
            thread = Thread(::captureLoop, "aasra-mic").apply {
                isDaemon = true
                start()
            }
            true
        } catch (_: IllegalStateException) {
            rec.release()
            false
        }
    }

    fun stop() {
        running = false
        try {
            thread?.join(500)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            thread = null
        }
        try {
            recorder?.stop()
        } catch (_: IllegalStateException) {
            // Already stopped; safe to ignore.
        } finally {
            recorder?.release()
            recorder = null
        }
    }

    /** Most recent [seconds] of mic audio at 16 kHz (for saaras:v4 re-listen). */
    fun lastSeconds(seconds: Int): ShortArray {
        val all = ring.copyAll()
        val want = (seconds * SAMPLE_RATE_HZ).coerceAtLeast(0)
        if (all.size <= want) return all
        return all.copyOfRange(all.size - want, all.size)
    }

    fun setTtsPlaying(playing: Boolean) {
        echoController.ttsPlaying = playing
    }

    private fun captureLoop() {
        val frame = ShortArray(FRAME_SAMPLES)
        while (running) {
            val rec = recorder ?: break
            val feedBeforeRead = echoController.shouldFeedVad(0f)
            val n = try {
                rec.read(frame, 0, FRAME_SAMPLES)
            } catch (_: IllegalStateException) {
                break
            }
            if (n <= 0) continue // overrun / invalid op; keep looping, don't spin on error codes
            val energy = Resampler.rms(frame, 0, n)
            val feedVad = feedBeforeRead && echoController.shouldFeedVad(energy)
            // Re-listen uploads this ring too: gating only VAD still leaks TTS to STT.
            ring.write(if (feedVad) frame else ShortArray(n), 0, n)
            try {
                onFrame?.invoke(frame.copyOf(n), feedVad)
            } catch (_: Exception) {
                // A throwing consumer must never kill the mic thread.
            }
        }
    }

    companion object {
        const val SAMPLE_RATE_HZ = 16_000
        const val FRAME_MS = 20
        /** 16 kHz * 20 ms = 320 samples per frame. */
        const val FRAME_SAMPLES = SAMPLE_RATE_HZ * FRAME_MS / 1000
        private const val BYTES_PER_SAMPLE = 2
    }
}

package com.aasra.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

/**
 * Speaker playback: 24 kHz mono PCM16 (Kokoro / bulbul native rate),
 * PERFORMANCE_MODE_LOW_LATENCY (PLAN 4.2). Streamed writes, so the first
 * synthesized sentence starts playing before the rest of the reply exists.
 *
 * [flush] is the barge-in primitive: it discards buffered-but-unplayed audio
 * immediately (PLAN 4.7 interrupt test). [playbackListener] mirrors state into
 * [EchoController.playbackPlaying]. Callers poll [isPlaying] through drain.
 */
class AudioPlayer(
    private val outputRateHz: Int = OUTPUT_RATE_HZ,
) {
    interface PlaybackListener {
        fun onPlayingChanged(playing: Boolean)
    }

    var playbackListener: PlaybackListener? = null

    private var track: AudioTrack? = null
    private val lock = Any()
    private var totalFramesWritten: Long = 0L
    private var baseHeadPosition: Long = 0L
    private var writing = false
    private var reportedPlaying = false
    private var generation = 0L

    val isPlaying: Boolean
        get() = synchronized(lock) {
            val t = track ?: return false
            if (t.playState != AudioTrack.PLAYSTATE_PLAYING) return false
            val head = ((t.playbackHeadPosition.toLong() and 0xFFFFFFFFL) - baseHeadPosition) and 0xFFFFFFFFL
            val playing = writing || head < totalFramesWritten
            reportPlaying(playing)
            playing
        }

    private fun reportPlaying(playing: Boolean) {
        if (reportedPlaying == playing) return
        reportedPlaying = playing
        playbackListener?.onPlayingChanged(playing)
    }

    private fun ensureTrack(): AudioTrack {
        synchronized(lock) {
            track?.let { return it }
            val minBuf = AudioTrack.getMinBufferSize(
                outputRateHz,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            ).coerceAtLeast(4 * 1024)
            val t = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(outputRateHz)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(minBuf * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
            track = t
            return t
        }
    }

    /** Streams PCM16 samples; starts playback on first write. Non-blocking-ish. */
    @Synchronized
    fun play(
        samples: ShortArray,
        offset: Int = 0,
        length: Int = samples.size - offset,
        shouldContinue: () -> Boolean = { true },
    ) {
        if (length <= 0 || !shouldContinue()) return
        val t = ensureTrack()
        val playbackGeneration = synchronized(lock) {
            if (!shouldContinue()) return
            writing = true
            reportPlaying(true)
            generation
        }
        try {
            synchronized(lock) {
                if (!shouldContinue() || playbackGeneration != generation) return
                if (t.playState != AudioTrack.PLAYSTATE_PLAYING) t.play()
            }
            var written = 0
            while (written < length) {
                val chunkSize = minOf(WRITE_CHUNK_SAMPLES, length - written)
                val n = synchronized(lock) {
                    if (!shouldContinue() || playbackGeneration != generation) return
                    val samplesWritten = t.write(samples, offset + written, chunkSize)
                    if (samplesWritten > 0) totalFramesWritten += samplesWritten
                    samplesWritten
                }
                if (n < 0) throw IllegalStateException("AudioTrack write failed: $n")
                if (n == 0) break
                written += n
            }
        } catch (error: Exception) {
            synchronized(lock) {
                if (playbackGeneration == generation) flush()
            }
            throw error
        } finally {
            synchronized(lock) {
                if (playbackGeneration == generation) {
                    writing = false
                    // Pollers observe drain; zero-length/failed writes release the gate now.
                    if (totalFramesWritten == 0L) reportPlaying(false)
                }
            }
        }
    }

    /**
     * Barge-in: stop NOW and drop everything buffered. After flush the next
     * [play] restarts cleanly for the new turn's reply.
     */
    fun flush() {
        synchronized(lock) {
            generation++
            try {
                track?.pause()
                track?.flush()
                baseHeadPosition = track?.playbackHeadPosition?.toLong()?.and(0xFFFFFFFFL) ?: 0L
            } catch (_: IllegalStateException) {
                // A failed flush must not leave an audible track behind an open gate.
                runCatching { track?.release() }
                track = null
                baseHeadPosition = 0L
            } finally {
                writing = false
                totalFramesWritten = 0L
                reportPlaying(false)
            }
        }
    }

    fun stop() {
        // AudioTrack.stop() drains MODE_STREAM asynchronously. Pause + flush is
        // required before declaring silence and reopening microphone capture.
        flush()
    }

    fun release() {
        synchronized(lock) {
            generation++
            try {
                track?.release()
            } catch (_: Exception) {
            } finally {
                track = null
                writing = false
                totalFramesWritten = 0L
                baseHeadPosition = 0L
                reportPlaying(false)
            }
        }
    }

    companion object {
        /** Kokoro-82M / sonic-3.6 native output rate. */
        const val OUTPUT_RATE_HZ = 24_000
        private const val WRITE_CHUNK_SAMPLES = 1024
    }
}

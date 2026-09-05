package com.aasra.audio

/**
 * Pure-Kotlin sample-rate conversion (linear interpolation, no native deps).
 *
 * Used at the pipeline seams: mic/VAD/STT run at 16 kHz, Kokoro/bulbul output
 * is 24 kHz ([AudioPlayer.outputRateHz]). Linear interpolation is cheap enough
 * for 20 ms frames and avoids pulling a DSP lib into core-audio. Testable on
 * JVM unit tests — no Android framework calls here.
 */
object Resampler {

    /** Generic Short PCM resample from [fromRateHz] to [toRateHz]. */
    fun resample(input: ShortArray, fromRateHz: Int, toRateHz: Int): ShortArray {
        require(fromRateHz > 0 && toRateHz > 0) { "rates must be positive" }
        if (input.isEmpty()) return ShortArray(0)
        if (fromRateHz == toRateHz) return input.copyOf()
        val ratio = toRateHz.toDouble() / fromRateHz
        val outSize = (input.size * ratio).toInt().coerceAtLeast(1)
        val out = ShortArray(outSize)
        for (i in out.indices) {
            val srcPos = i / ratio
            val idx = srcPos.toInt().coerceIn(0, input.size - 1)
            val frac = (srcPos - idx).toFloat()
            val a = input[idx].toInt()
            val b = input[minOf(idx + 1, input.size - 1)].toInt()
            out[i] = (a + (b - a) * frac).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return out
    }

    /** 16 kHz mic/VAD audio up to the 24 kHz TTS/playback domain. */
    fun upsample16kTo24k(input: ShortArray): ShortArray = resample(input, 16_000, 24_000)

    /** 24 kHz audio down to the 16 kHz recognition domain. */
    fun downsample24kTo16k(input: ShortArray): ShortArray = resample(input, 24_000, 16_000)

    /**
     * RMS energy of a frame, normalised to 0..1 (Short full-scale = 1.0).
     * Drives [EchoController] barge-in gating and is cheap enough per 20 ms frame.
     */
    fun rms(frame: ShortArray, offset: Int = 0, length: Int = frame.size - offset): Float {
        if (length <= 0) return 0f
        var sum = 0.0
        for (i in offset until offset + length) {
            val s = frame[i] / 32768.0
            sum += s * s
        }
        return kotlin.math.sqrt(sum / length).toFloat()
    }
}

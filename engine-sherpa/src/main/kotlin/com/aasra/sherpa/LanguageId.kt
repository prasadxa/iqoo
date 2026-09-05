package com.aasra.sherpa

import com.k2fsa.sherpa.onnx.SpokenLanguageIdentification
import com.k2fsa.sherpa.onnx.SpokenLanguageIdentificationConfig
import com.k2fsa.sherpa.onnx.SpokenLanguageIdentificationWhisperConfig
import java.io.File

/**
 * Spoken language ID (PLAN 4.4): sherpa-onnx whisper-tiny LID.
 *
 * Selection order: user setting first (EN/HI); if AUTO, [identify] runs on the
 * FIRST segment of each session and the result sticks for the session (running
 * LID per segment would flip-flop on short utterances). Only "en"/"hi" route
 * to local engines; anything else is a Router re-listen trigger.
 *
 * No native in AUTO mode: returns "unknown" (never guesses), which routes to
 * cloud re-listen when online per Router.LOCAL_LANGUAGES.
 */
class LanguageId(
    private val filesDir: File,
    var mode: LidMode = LidMode.AUTO,
    private val openAsset: ((String) -> java.io.InputStream?)? = null,
) {
    enum class LidMode { EN, HI, AUTO }

    @Volatile var ready: Boolean = false
        private set

    private var nativeLid: SpokenLanguageIdentification? = null

    @Synchronized
    fun init(): Boolean {
        if (mode != LidMode.AUTO) return true // setting-driven; no model needed
        close()
        ready = try {
            val encoder = ModelAssets.ensureFile(filesDir, ENCODER_PATH, openAsset) ?: return false
            val decoder = ModelAssets.ensureFile(filesDir, DECODER_PATH, openAsset) ?: return false
            nativeLid = SpokenLanguageIdentification(
                assetManager = null,
                config = SpokenLanguageIdentificationConfig(
                    whisper = SpokenLanguageIdentificationWhisperConfig(
                        encoder = encoder.absolutePath,
                        decoder = decoder.absolutePath,
                    ),
                    numThreads = 1,
                ),
            )
            nativeLid != null
        } catch (error: Throwable) {
            android.util.Log.e(TAG, "Initialization failed", error)
            nativeLid = null
            false
        }
        return ready
    }

    /**
     * @param samples first-session-segment audio at 16 kHz.
     * @return "en", "hi", or "unknown".
     */
    @Synchronized
    fun identify(samples: ShortArray): String {
        when (mode) {
            LidMode.EN -> return "en"
            LidMode.HI -> return "hi"
            LidMode.AUTO -> Unit
        }
        val lid = nativeLid
        if (!ready || lid == null || samples.isEmpty()) return "unknown"
        return try {
            val stream = lid.createStream()
            try {
                stream.acceptWaveform(FloatArray(samples.size) { samples[it] / 32768f }, SAMPLE_RATE_HZ)
                val raw = lid.compute(stream).lowercase()
                when {
                    raw.startsWith("en") || raw.contains("english") -> "en"
                    raw.startsWith("hi") || raw.contains("hindi") -> "hi"
                    else -> "unknown"
                }
            } finally {
                stream.release()
            }
        } catch (error: Throwable) {
            android.util.Log.e(TAG, "Inference failed", error)
            "unknown"
        }
    }

    @Synchronized
    fun close() {
        runCatching { nativeLid?.release() }
        nativeLid = null
        ready = false
    }

    companion object {
        const val MODEL_DIR = "models/sherpa-onnx-whisper-tiny"
        const val ENCODER_PATH = "$MODEL_DIR/tiny-encoder.int8.onnx"
        const val DECODER_PATH = "$MODEL_DIR/tiny-decoder.int8.onnx"
        const val SAMPLE_RATE_HZ = 16_000
        private const val TAG = "LanguageId"
    }
}

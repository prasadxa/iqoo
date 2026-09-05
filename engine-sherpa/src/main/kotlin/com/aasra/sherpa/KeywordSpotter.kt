package com.aasra.sherpa

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter as SherpaKeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File

/**
 * Keyword spotter (PLAN 4.3): "aasra" / "help" / "madad".
 *
 * Policy (PLAN 4.3): wake word ONLY when the app is in background; foreground
 * uses continuous VAD. That policy is enforced by app/ (which enables either
 * this or SherpaVad) — this class just reports detections.
 *
 * Without native: ready=false and [acceptSamples] always returns null; the
 * background path then stays deaf rather than guessing. (Unlike VAD, an
 * energy fallback would false-trigger constantly, so there is none.)
 */
class KeywordSpotter(
    private val filesDir: File,
    private val keywords: List<String> = DEFAULT_KEYWORDS,
    private val openAsset: ((String) -> java.io.InputStream?)? = null,
) {
    @Volatile var ready: Boolean = false
        private set

    private var nativeKws: SherpaKeywordSpotter? = null
    private var stream: OnlineStream? = null

    @Synchronized
    fun init(): Boolean {
        close()
        ready = try {
            val encoder = ModelAssets.ensureFile(filesDir, ENCODER_PATH, openAsset) ?: return false
            val decoder = ModelAssets.ensureFile(filesDir, DECODER_PATH, openAsset) ?: return false
            val joiner = ModelAssets.ensureFile(filesDir, JOINER_PATH, openAsset) ?: return false
            val tokens = ModelAssets.ensureFile(filesDir, TOKENS_PATH, openAsset) ?: return false
            nativeKws = SherpaKeywordSpotter(
                assetManager = null,
                config = KeywordSpotterConfig(
                    featConfig = FeatureConfig(sampleRate = SAMPLE_RATE_HZ, featureDim = 80),
                    modelConfig = OnlineModelConfig(
                        transducer = OnlineTransducerModelConfig(
                            encoder = encoder.absolutePath,
                            decoder = decoder.absolutePath,
                            joiner = joiner.absolutePath,
                        ),
                        tokens = tokens.absolutePath,
                        numThreads = 1,
                        modelType = "zipformer2",
                    ),
                    keywordsScore = 1.5f,
                    keywordsThreshold = KEYWORD_THRESHOLD,
                ),
            )
            stream = nativeKws?.createStream(ENCODED_KEYWORDS)
            stream?.ptr?.let { it != 0L } == true
        } catch (error: Throwable) {
            android.util.Log.e(TAG, "Initialization failed", error)
            nativeKws = null
            stream = null
            false
        }
        return ready
    }

    /**
     * Feeds one 16 kHz PCM16 frame.
     * @return the matched keyword, or null.
     */
    @Synchronized
    fun acceptSamples(frame: ShortArray): String? {
        val kws = nativeKws
        val activeStream = stream
        if (!ready || kws == null || activeStream == null) return null
        return try {
            activeStream.acceptWaveform(FloatArray(frame.size) { frame[it] / 32768f }, SAMPLE_RATE_HZ)
            while (kws.isReady(activeStream)) {
                kws.decode(activeStream)
                val keyword = kws.getResult(activeStream).keyword.trim().lowercase()
                if (keyword.isNotEmpty()) {
                    kws.reset(activeStream)
                    return keywords.firstOrNull { keyword.contains(it) } ?: keyword
                }
            }
            null
        } catch (error: Throwable) {
            android.util.Log.e(TAG, "Inference failed", error)
            null
        }
    }

    @Synchronized
    fun reset() {
        val kws = nativeKws ?: return
        val activeStream = stream ?: return
        runCatching { kws.reset(activeStream) }
    }

    @Synchronized
    fun close() {
        runCatching { stream?.release() }
        runCatching { nativeKws?.release() }
        stream = null
        nativeKws = null
        ready = false
    }

    companion object {
        val DEFAULT_KEYWORDS: List<String> = listOf("aasra", "help", "madad")
        const val MODEL_DIR = "models/sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01-mobile"
        const val ENCODER_PATH = "$MODEL_DIR/encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx"
        const val DECODER_PATH = "$MODEL_DIR/decoder-epoch-12-avg-2-chunk-16-left-64.onnx"
        const val JOINER_PATH = "$MODEL_DIR/joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx"
        const val TOKENS_PATH = "$MODEL_DIR/tokens.txt"
        const val KEYWORD_THRESHOLD = 0.5f
        const val SAMPLE_RATE_HZ = 16_000
        const val ENCODED_KEYWORDS =
            "▁A AS RA #0.5 @aasra/▁HELP #0.5 @help/▁MA D AD #0.5 @madad"
        private const val TAG = "KeywordSpotter"
    }
}

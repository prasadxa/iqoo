package com.aasra.sherpa

import com.aasra.pipeline.RecognitionResult
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import java.io.File

class IndicConformerStt(
    private val filesDir: File,
    private val openAsset: ((String) -> java.io.InputStream?)? = null,
) {
    @Volatile var ready = false
        private set
    private var recognizer: OfflineRecognizer? = null

    @Synchronized
    fun init(): Boolean {
        close()
        ready = try {
            val model = ModelAssets.ensureFile(filesDir, MODEL_PATH, openAsset) ?: return false
            val tokens = ModelAssets.ensureFile(filesDir, TOKENS_PATH, openAsset) ?: return false
            recognizer = OfflineRecognizer(
                config = OfflineRecognizerConfig(
                    featConfig = FeatureConfig(sampleRate = SAMPLE_RATE_HZ, featureDim = 80),
                    modelConfig = OfflineModelConfig(
                        nemo = OfflineNemoEncDecCtcModelConfig(model = model.absolutePath),
                        tokens = tokens.absolutePath,
                        numThreads = 2,
                    ),
                ),
            )
            true
        } catch (error: Throwable) {
            android.util.Log.e(TAG, "Initialization failed", error)
            recognizer = null
            false
        }
        return ready
    }

    @Synchronized
    fun decodeSegment(samples: ShortArray): RecognitionResult {
        val activeRecognizer = recognizer
        if (!ready || activeRecognizer == null || samples.isEmpty()) return emptyResult()
        return try {
            val stream = activeRecognizer.createStream()
            try {
                stream.acceptWaveform(FloatArray(samples.size) { samples[it] / 32768f }, SAMPLE_RATE_HZ)
                activeRecognizer.decode(stream)
                RecognitionResult(activeRecognizer.getResult(stream).text, true, 1f, "hi")
            } finally { stream.release() }
        } catch (error: Throwable) {
            android.util.Log.e(TAG, "Inference failed", error)
            ready = false
            emptyResult()
        }
    }

    @Synchronized
    fun close() {
        runCatching { recognizer?.release() }
        recognizer = null
        ready = false
    }

    private fun emptyResult() = RecognitionResult("", true, 0f, "hi")

    companion object {
        const val SAMPLE_RATE_HZ = 16_000
        const val MODEL_PATH = "models/indicconformer-hi-int8/model.onnx"
        const val TOKENS_PATH = "models/indicconformer-hi-int8/tokens.txt"
        private const val TAG = "IndicConformerStt"
    }
}

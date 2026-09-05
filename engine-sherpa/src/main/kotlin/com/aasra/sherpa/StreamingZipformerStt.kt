package com.aasra.sherpa

import com.aasra.pipeline.RecognitionResult
import com.aasra.pipeline.SpeechRecognizer
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File

class StreamingZipformerStt(
    private val filesDir: File,
    private val openAsset: ((String) -> java.io.InputStream?)? = null,
) : SpeechRecognizer {
    private var listener: ((RecognitionResult) -> Unit)? = null
    private var recognizer: OnlineRecognizer? = null
    private var stream: OnlineStream? = null
    @Volatile var ready = false
        private set

    @Synchronized
    fun init(): Boolean {
        close()
        ready = try {
            val encoder = ModelAssets.ensureFile(filesDir, "$MODEL_DIR/encoder.onnx", openAsset)
            val decoder = ModelAssets.ensureFile(filesDir, "$MODEL_DIR/decoder.onnx", openAsset)
            val joiner = ModelAssets.ensureFile(filesDir, "$MODEL_DIR/joiner.onnx", openAsset)
            val tokens = ModelAssets.ensureFile(filesDir, "$MODEL_DIR/tokens.txt", openAsset)
            if (encoder == null || decoder == null || joiner == null || tokens == null) return false
            recognizer = OnlineRecognizer(
                config = OnlineRecognizerConfig(
                    featConfig = FeatureConfig(sampleRate = SAMPLE_RATE_HZ, featureDim = 80),
                    modelConfig = OnlineModelConfig(
                        transducer = OnlineTransducerModelConfig(
                            encoder = encoder.absolutePath,
                            decoder = decoder.absolutePath,
                            joiner = joiner.absolutePath,
                        ),
                        tokens = tokens.absolutePath,
                        numThreads = 2,
                        modelType = "zipformer2",
                    ),
                    enableEndpoint = false,
                ),
            )
            stream = recognizer?.createStream()
            stream != null
        } catch (error: Throwable) {
            android.util.Log.e(TAG, "Initialization failed", error)
            recognizer = null
            stream = null
            false
        }
        return ready
    }

    override fun setListener(listener: ((RecognitionResult) -> Unit)?) { this.listener = listener }

    @Synchronized
    override fun acceptAudio(frame: ShortArray) {
        val activeRecognizer = recognizer ?: return
        val activeStream = stream ?: return
        if (!ready) return
        try {
            activeStream.acceptWaveform(FloatArray(frame.size) { frame[it] / 32768f }, SAMPLE_RATE_HZ)
            while (activeRecognizer.isReady(activeStream)) activeRecognizer.decode(activeStream)
            activeRecognizer.getResult(activeStream).text.takeIf(String::isNotBlank)?.let {
                emit(RecognitionResult(it, false, 1f, "en"))
            }
        } catch (error: Throwable) {
            android.util.Log.e(TAG, "Inference failed", error)
            ready = false
        }
    }

    @Synchronized
    fun finalizeSegment(): RecognitionResult {
        val activeRecognizer = recognizer
        val activeStream = stream
        if (!ready || activeRecognizer == null || activeStream == null) return emptyResult()
        return try {
            activeStream.inputFinished()
            while (activeRecognizer.isReady(activeStream)) activeRecognizer.decode(activeStream)
            val result = RecognitionResult(activeRecognizer.getResult(activeStream).text, true, 1f, "en")
            emit(result)
            reset()
            result
        } catch (error: Throwable) {
            android.util.Log.e(TAG, "Finalization failed", error)
            ready = false
            emptyResult()
        }
    }

    @Synchronized
    override fun reset() {
        try {
            val activeRecognizer = recognizer ?: return
            stream?.release()
            stream = activeRecognizer.createStream()
        } catch (error: Throwable) {
            android.util.Log.e(TAG, "Reset failed", error)
            ready = false
        }
    }

    @Synchronized
    override fun close() {
        runCatching { stream?.release() }
        runCatching { recognizer?.release() }
        stream = null
        recognizer = null
        ready = false
    }

    private fun emptyResult() = RecognitionResult("", true, 0f, "en").also(::emit)
    private fun emit(result: RecognitionResult) { runCatching { listener?.invoke(result) } }

    companion object {
        const val SAMPLE_RATE_HZ = 16_000
        const val MODEL_DIR = "models/sherpa-onnx-streaming-zipformer-en-2023-06-26-int8"
        private const val TAG = "StreamingZipformerStt"
    }
}

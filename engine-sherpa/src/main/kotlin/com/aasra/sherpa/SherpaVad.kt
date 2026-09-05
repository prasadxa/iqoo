package com.aasra.sherpa

import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.File

class SherpaVad(
    private val filesDir: File,
    private val config: VadConfig = VadConfig(),
    private val openAsset: ((String) -> java.io.InputStream?)? = null,
) {
    data class VadConfig(
        val sampleRateHz: Int = 16_000,
        val threshold: Float = 0.5f,
        val minSilenceSec: Float = 1.3f,
        val minSpeechSec: Float = 0.25f,
        val modelPath: String = "models/silero_vad.onnx",
    )

    sealed class VadEvent {
        data object SpeechStart : VadEvent()
        data class SpeechEnd(val samples: ShortArray) : VadEvent()
        data object Silence : VadEvent()
    }

    interface Listener { fun onEvent(event: VadEvent) }

    var listener: Listener? = null
    @Volatile var ready = false
        private set
    private var nativeVad: Vad? = null
    private var inSpeech = false
    private var speechSamples = 0
    private var silenceSamples = 0
    private val segment = ArrayList<Short>()

    @Synchronized
    fun init(): Boolean {
        close()
        ready = try {
            val model = ModelAssets.ensureFile(filesDir, config.modelPath, openAsset) ?: return false
            nativeVad = Vad(
                config = VadModelConfig(
                    sileroVadModelConfig = SileroVadModelConfig(
                        model = model.absolutePath,
                        threshold = config.threshold,
                        minSilenceDuration = config.minSilenceSec,
                        minSpeechDuration = config.minSpeechSec,
                        windowSize = 512,
                        maxSpeechDuration = 30f,
                    ),
                    sampleRate = config.sampleRateHz,
                    numThreads = 2,
                ),
            )
            true
        } catch (error: Throwable) {
            android.util.Log.e(TAG, "Initialization failed", error)
            nativeVad = null
            false
        }
        return ready
    }

    @Synchronized
    fun acceptSamples(frame: ShortArray) {
        val vad = nativeVad
        if (!ready || vad == null) {
            route(energy(frame) > 0.02f, frame)
            return
        }
        try {
            vad.acceptWaveform(FloatArray(frame.size) { frame[it] / 32768f })
            if (vad.isSpeechDetected() && !inSpeech) {
                inSpeech = true
                emit(VadEvent.SpeechStart)
            }
            // Consume native segments: retaining only current frames loses speech
            // onset and leaves sherpa's completed-segment queue growing forever.
            while (!vad.empty()) {
                val samples = vad.front().samples
                vad.pop()
                inSpeech = false
                emit(VadEvent.SpeechEnd(ShortArray(samples.size) {
                    (samples[it] * 32767).toInt().coerceIn(-32768, 32767).toShort()
                }))
            }
        } catch (error: Throwable) {
            android.util.Log.e(TAG, "Inference failed", error)
            ready = false
            route(energy(frame) > 0.02f, frame)
        }
    }

    @Synchronized
    fun finishSegment(): ShortArray {
        val samples = ArrayList<Short>()
        try {
            val vad = nativeVad
            if (ready && vad != null) {
                vad.flush()
                while (!vad.empty()) {
                    samples.addAll(vad.front().samples.map {
                        (it * 32767).toInt().coerceIn(-32768, 32767).toShort()
                    })
                    vad.pop()
                }
            } else {
                samples.addAll(segment)
            }
        } finally { reset() }
        return samples.toShortArray()
    }

    @Synchronized
    fun reset() {
        inSpeech = false
        speechSamples = 0
        silenceSamples = 0
        segment.clear()
        runCatching { nativeVad?.reset() }
    }

    @Synchronized
    fun close() {
        runCatching { nativeVad?.release() }
        nativeVad = null
        ready = false
        reset()
    }

    private fun route(speech: Boolean, frame: ShortArray) {
        if (speech) {
            silenceSamples = 0
            speechSamples += frame.size
            segment.addAll(frame.asIterable())
            if (!inSpeech && speechSamples.toFloat() / config.sampleRateHz >= config.minSpeechSec) {
                inSpeech = true
                emit(VadEvent.SpeechStart)
            }
        } else if (!inSpeech) {
            speechSamples = 0
            segment.clear()
        } else {
            silenceSamples += frame.size
            segment.addAll(frame.asIterable())
        }
        // A stuck microphone/noisy room must not accumulate PCM indefinitely.
        if (inSpeech && (silenceSamples.toFloat() / config.sampleRateHz >= config.minSilenceSec ||
                segment.size >= config.sampleRateHz * 30)) {
            val samples = segment.take(config.sampleRateHz * 30).toShortArray()
            reset()
            emit(VadEvent.SpeechEnd(samples))
        }
    }

    private fun energy(frame: ShortArray): Float {
        if (frame.isEmpty()) return 0f
        return kotlin.math.sqrt(frame.sumOf { val value = it / 32768.0; value * value } / frame.size).toFloat()
    }

    private fun emit(event: VadEvent) { runCatching { listener?.onEvent(event) } }

    private companion object { const val TAG = "SherpaVad" }
}

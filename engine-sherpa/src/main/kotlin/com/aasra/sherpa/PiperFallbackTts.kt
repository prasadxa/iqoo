package com.aasra.sherpa

import android.os.SystemClock
import com.aasra.pipeline.SpeechAudio
import com.aasra.pipeline.SpeechSynthesizer
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File
import java.util.Properties

class PiperFallbackTts(
    private val filesDir: File,
    private val openAsset: ((String) -> java.io.InputStream?)? = null,
) : SpeechSynthesizer {

    @Volatile var speechSpeed: Float = 1f
    override val sampleRateHz: Int get() = tts?.sampleRate() ?: SAMPLE_RATE_HZ
    @Volatile var ready = false
        private set
    private var tts: OfflineTts? = null
    private var activeLanguage = ""

    /** Model IO: call on Dispatchers.IO before starting the audio worker. */
    @Synchronized
    fun init(language: String = "hi"): Boolean {
        if (ready && activeLanguage == language) return true
        close()
        ready = try {
            val directory = if (language == "hi") MODEL_DIR_HI else MODEL_DIR_EN
            val modelName = if (language == "hi") "hi_IN-pratham-medium.onnx" else "en_US-lessac-medium.onnx"
            val model = ModelAssets.ensureFile(filesDir, "$directory/$modelName", openAsset) ?: return false
            val tokens = ModelAssets.ensureFile(filesDir, "$directory/tokens.txt", openAsset) ?: return false
            val dataDir = File(filesDir, "$directory/espeak-ng-data")
            if (!dataDir.isDirectory) return false
            tts = OfflineTts(
                config = OfflineTtsConfig(
                    model = OfflineTtsModelConfig(
                        vits = OfflineTtsVitsModelConfig(
                            model = model.absolutePath,
                            tokens = tokens.absolutePath,
                            dataDir = dataDir.absolutePath,
                        ),
                        numThreads = 2,
                    ),
                ),
            )
            activeLanguage = language
            true
        } catch (error: Throwable) {
            android.util.Log.e(TAG, "Initialization failed", error)
            tts = null
            false
        }
        return ready
    }

    fun supports(language: String): Boolean = ready && activeLanguage == if (language == "hi") "hi" else "en"

    @Synchronized
    override fun synthesize(text: String, language: String): SpeechAudio {
        val speech = try {
            SpeechInput.normalize(text)
        } catch (_: IllegalArgumentException) {
            return SpeechAudio(ShortArray(0), sampleRateHz)
        }
        if (speech.isBlank()) return SpeechAudio(ShortArray(0), sampleRateHz)
        val requested = if (language == "hi") "hi" else "en"
        if (!ready || activeLanguage != requested) return SpeechAudio(ShortArray(0), sampleRateHz)
        return try {
            val audio = requireNotNull(tts).generate(text = speech, sid = 0, speed = speedFor(speechSpeed))
            if (audio.samples.isEmpty()) ready = false
            SpeechAudio(
                ShortArray(audio.samples.size) {
                    (audio.samples[it] * 32767).toInt().coerceIn(-32768, 32767).toShort()
                },
                audio.sampleRate,
            )
        } catch (error: Throwable) {
            android.util.Log.e(TAG, "Inference failed", error)
            ready = false
            SpeechAudio(ShortArray(0), sampleRateHz)
        }
    }

    @Synchronized
    fun close() {
        runCatching { tts?.release() }
        tts = null
        activeLanguage = ""
        ready = false
    }

    companion object {
        const val SAMPLE_RATE_HZ = 24_000
        const val KOKORO_RTF_CUTOFF = 1.0f
        const val MODEL_DIR_HI = "models/vits-piper-hi_IN-pratham-medium"
        const val MODEL_DIR_EN = "models/vits-piper-en_US-lessac-medium"
        private const val RTF_FILE = "tts_rtf.properties"
        private const val KEY_KOKORO_RTF = "kokoro_rtf"
        private const val PROBE_TEXT = "Namaste. Main Aasra hoon."
        private const val TAG = "PiperFallbackTts"

        /** Keep the 0.9 baseline; higher preference must mean faster, not slower. */
        internal fun speedFor(preference: Float): Float =
            0.9f * (if (preference.isFinite()) preference.coerceIn(0.5f, 1.5f) else 1f)

        fun measureAndPersist(filesDir: File, kokoro: SherpaTts): Float? {
            if (!kokoro.ready) return null
            return runCatching {
                val started = SystemClock.elapsedRealtime()
                val audio = kokoro.synthesize(PROBE_TEXT, "hi")
                val seconds = audio.samples.size.toFloat() / kokoro.sampleRateHz
                if (seconds <= 0f) return null
                val rtf = ((SystemClock.elapsedRealtime() - started) / 1000f) / seconds
                val properties = Properties().apply { setProperty(KEY_KOKORO_RTF, rtf.toString()) }
                File(filesDir, RTF_FILE).outputStream().use { properties.store(it, null) }
                rtf
            }.getOrNull()
        }

        fun shouldUsePiper(filesDir: File): Boolean = readPersisted(filesDir)?.let { it > KOKORO_RTF_CUTOFF } ?: false

        fun readPersisted(filesDir: File): Float? = runCatching {
            val file = File(filesDir, RTF_FILE)
            if (!file.isFile) return null
            Properties().apply { file.inputStream().use(::load) }.getProperty(KEY_KOKORO_RTF)?.toFloatOrNull()
        }.getOrNull()
    }
}

package com.aasra.sherpa

import com.aasra.pipeline.SpeechAudio
import com.aasra.pipeline.SpeechSynthesizer
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.GenerationConfig
import java.io.File

/**
 * Local TTS (PLAN 4.6): Kokoro-82M multi-lang v1.1 ONNX via sherpa-onnx
 * OfflineTts. Voices: af_heart (en), hf_alpha / hm_omega (hi); length_scale
 * 1.1 (slightly slower for elderly listeners). Male/female choice from
 * onboarding maps here: female -> af_heart / hf_alpha, male -> hm_omega.
 *
 * Output is 24 kHz mono — the AudioPlayer native rate, so no resample on the
 * hot path. When [ready] is false (or Kokoro RTF > 1.0 per PiperFallbackTts),
 * app/ routes sentences to PiperFallbackTts instead; this class never throws
 * out of [synthesize] — it returns silence on failure so the TTS worker keeps
 * draining the queue.
 */
class SherpaTts(
    private val filesDir: File,
    private val openAsset: ((String) -> java.io.InputStream?)? = null,
) : SpeechSynthesizer {

    @Volatile var malePreferred: Boolean = false
    @Volatile var speechSpeed: Float = 1f

    override val sampleRateHz: Int = SAMPLE_RATE_HZ

    @Volatile var ready: Boolean = false
        private set

    private var nativeTts: OfflineTts? = null

    @Synchronized
    fun init(): Boolean {
        close()
        ready = try {
            val model = ModelAssets.ensureFile(filesDir, MODEL_PATH, openAsset) ?: return false
            val voices = ModelAssets.ensureFile(filesDir, VOICES_PATH, openAsset) ?: return false
            val tokens = ModelAssets.ensureFile(filesDir, TOKENS_PATH, openAsset) ?: return false
            val dataDir = File(filesDir, DATA_DIR)
            val lexicon = File(filesDir, LEXICON_PATH)
            if (!dataDir.isDirectory || !lexicon.isFile) return false
            nativeTts = OfflineTts(
                config = OfflineTtsConfig(
                    model = OfflineTtsModelConfig(
                        kokoro = OfflineTtsKokoroModelConfig(
                            model = model.absolutePath,
                            voices = voices.absolutePath,
                            tokens = tokens.absolutePath,
                            dataDir = dataDir.absolutePath,
                            lexicon = lexicon.absolutePath,
                            // Apply the slower baseline through generate(speed), not twice.
                            lengthScale = 1f,
                        ),
                        numThreads = 2,
                    ),
                ),
            )
            nativeTts != null
        } catch (error: Throwable) {
            android.util.Log.e(TAG, "Initialization failed", error)
            nativeTts = null
            false
        }
        return ready
    }

    @Synchronized
    override fun synthesize(text: String, language: String): SpeechAudio {
        val speech = try {
            SpeechInput.normalize(text)
        } catch (_: IllegalArgumentException) {
            return SpeechAudio(ShortArray(0), sampleRateHz)
        }
        if (speech.isBlank()) return SpeechAudio(ShortArray(0), sampleRateHz)
        val tts = nativeTts
        if (!ready || tts == null) return SpeechAudio(ShortArray(0), sampleRateHz)
        return try {
            val audio = tts.generateWithConfig(
                text = speech,
                config = generationConfig(language, malePreferred, speechSpeed),
            )
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
        runCatching { nativeTts?.release() }
        nativeTts = null
        ready = false
    }

    data class KokoroVoice(val id: String, val sid: Int)

    companion object {
        const val SAMPLE_RATE_HZ = 24_000

        /** Slightly slower than 1.0 for elderly listeners (PLAN 4.6). */
        const val LENGTH_SCALE = 1.1f

        /** sherpa generate takes speed, not duration/length scale. */
        internal fun speedFor(preference: Float): Float =
            (if (preference.isFinite()) preference.coerceIn(0.5f, 1.5f) else 1f) / LENGTH_SCALE

        internal fun generationConfig(language: String, male: Boolean, speed: Float) = GenerationConfig(
            sid = voiceFor(language, male).sid,
            speed = speedFor(speed),
            // Speaker identity does not select the multilingual phonemizer.
            extra = mapOf("lang" to if (language.substringBefore('-') in setOf("hi", "unknown")) "hi" else "en-us"),
        )

        const val MODEL_PATH = "models/kokoro-int8-multi-lang-v1_0/model.int8.onnx"
        const val VOICES_PATH = "models/kokoro-int8-multi-lang-v1_0/voices.bin"
        const val TOKENS_PATH = "models/kokoro-int8-multi-lang-v1_0/tokens.txt"
        const val DATA_DIR = "models/kokoro-int8-multi-lang-v1_0/espeak-ng-data"
        const val LEXICON_PATH = "models/kokoro-int8-multi-lang-v1_0/lexicon-us-en.txt"
        private const val TAG = "SherpaTts"

        val VOICE_EN_FEMALE = KokoroVoice("af_heart", 3)
        val VOICE_EN_MALE = KokoroVoice("am_adam", 11)
        val VOICE_HI_FEMALE = KokoroVoice("hf_alpha", 31)
        val VOICE_HI_MALE = KokoroVoice("hm_omega", 33)

        /** Onboarding voice choice -> Kokoro voice. Defaults to female/Hindi. */
        fun voiceFor(language: String, malePreferred: Boolean = false): KokoroVoice =
            if (language.substringBefore('-') in setOf("hi", "unknown")) {
                if (malePreferred) VOICE_HI_MALE else VOICE_HI_FEMALE
            } else {
                if (malePreferred) VOICE_EN_MALE else VOICE_EN_FEMALE
            }
    }
}

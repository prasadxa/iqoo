package com.aasra.models

/**
 * Canonical catalog of every downloadable on-device model artifact.
 *
 * Each entry maps one verified public URL to its exact runtime path under
 * [ModelPaths.modelsDir]. Checksums come from the publishers' release metadata.
 */
object ModelRegistry {

    enum class Tier {
        DEFAULT,
        LOW_RAM,
        TTS_FALLBACK,
        STRETCH,
    }

    data class Entry(
        val fileName: String,
        val url: String,
        val sizeBytes: Long,
        val sha256: String,
        val tier: Tier,
        val usedFor: String,
        val installDirectory: String? = null,
    )

    val SILERO_VAD = Entry(
        fileName = "silero_vad.onnx",
        url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad_v5.onnx",
        sizeBytes = 2_313_101,
        sha256 = "6b99cbfd39246b6706f98ec13c7c50c6b299181f2474fa05cbc8046acc274396",
        tier = Tier.DEFAULT,
        usedFor = "Voice activity detection",
    )

    val STT_ZIPFORMER_ENCODER = Entry(
        fileName = "sherpa-onnx-streaming-zipformer-en-2023-06-26-int8/encoder.onnx",
        url = "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-06-26/resolve/main/encoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx",
        sizeBytes = 71_083_163,
        sha256 = "563fde436d16cf7607cf408cd6b30909819d03162652ef389c2450ced3f45ac1",
        tier = Tier.DEFAULT,
        usedFor = "English speech recognition: encoder",
    )

    val STT_ZIPFORMER_DECODER = Entry(
        fileName = "sherpa-onnx-streaming-zipformer-en-2023-06-26-int8/decoder.onnx",
        url = "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-06-26/resolve/main/decoder-epoch-99-avg-1-chunk-16-left-128.onnx",
        sizeBytes = 2_092_621,
        sha256 = "7bf787f90b194b307e5a4ad6a34fadb4e748304c35f78a8d66358a05b13ee6ef",
        tier = Tier.DEFAULT,
        usedFor = "English speech recognition: decoder",
    )

    val STT_ZIPFORMER_JOINER = Entry(
        fileName = "sherpa-onnx-streaming-zipformer-en-2023-06-26-int8/joiner.onnx",
        url = "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-06-26/resolve/main/joiner-epoch-99-avg-1-chunk-16-left-128.onnx",
        sizeBytes = 1_026_405,
        sha256 = "210591f72b3c56b8364f85f345dca240bc2b4c00632848f4aa923630d5639d3b",
        tier = Tier.DEFAULT,
        usedFor = "English speech recognition: joiner",
    )

    val STT_ZIPFORMER_TOKENS = Entry(
        fileName = "sherpa-onnx-streaming-zipformer-en-2023-06-26-int8/tokens.txt",
        url = "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-06-26/resolve/main/tokens.txt",
        sizeBytes = 5_048,
        sha256 = "49e3c2646595fd907228b3c6787069658f67b17377c60aeb8619c4551b2316fb",
        tier = Tier.DEFAULT,
        usedFor = "English speech recognition: vocabulary",
    )

    val STT_INDICCONFORMER_HI = Entry(
        fileName = "indicconformer-hi-int8/model.onnx",
        url = "https://huggingface.co/parismitaglobalsolutions/indicconformer-sherpa-onnx/resolve/main/hi/model.int8.onnx",
        sizeBytes = 197_595_593,
        sha256 = "915c71e04dd7e5378a4057fdebb252b3a587188e4e99db6d7ce0909ad5ad05fa",
        tier = Tier.DEFAULT,
        usedFor = "Hindi speech recognition",
    )

    val STT_INDICCONFORMER_TOKENS = Entry(
        fileName = "indicconformer-hi-int8/tokens.txt",
        url = "https://huggingface.co/parismitaglobalsolutions/indicconformer-sherpa-onnx/resolve/main/tokens.txt",
        sizeBytes = 67_605,
        sha256 = "ee60967630213f31951817ac8b402b92ec18cce80718a24a49b388e56672dfb2",
        tier = Tier.DEFAULT,
        usedFor = "Hindi speech recognition: vocabulary",
    )

    val SPOKEN_LANGUAGE_ID = Entry(
        fileName = "sherpa-onnx-whisper-tiny.tar.bz2",
        url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-tiny.tar.bz2",
        sizeBytes = 116_204_861,
        sha256 = "c46116994e539aa165266d96b325252728429c12535eb9d8b6a2b10f129e66b1",
        tier = Tier.DEFAULT,
        usedFor = "Spoken language identification",
        installDirectory = "sherpa-onnx-whisper-tiny",
    )

    private const val HINGLISH_URL = "https://huggingface.co/parismitaglobalsolutions/indicconformer-sherpa-onnx/resolve/2d3ac712ec3a672444297555208dd030060962e3/hi-hinglish-swift"
    val HINGLISH_ENCODER = Entry(
        "hi-hinglish-swift/encoder.int8.onnx", "$HINGLISH_URL/encoder.int8.onnx", 29_122_029,
        "817f10919051ba8c784935a54da22683fceefd45a8c3547e68369204fc36c5ff", Tier.DEFAULT, "Hinglish recognition",
    )
    val HINGLISH_DECODER = Entry(
        "hi-hinglish-swift/decoder.int8.onnx", "$HINGLISH_URL/decoder.int8.onnx", 130_781_044,
        "19e88530a38a00af1744bc2c9340e72d4687f017e19b37b1b6c69c2b5128fc12", Tier.DEFAULT, "Hinglish recognition",
    )
    val HINGLISH_TOKENS = Entry(
        "hi-hinglish-swift/tokens.txt", "$HINGLISH_URL/tokens.txt", 816_730,
        "b34b360dbb493e781e479794586d661700670d65564001f23024971d1f2fa126", Tier.DEFAULT, "Hinglish vocabulary",
    )
    val HINGLISH_FILES = listOf(HINGLISH_ENCODER, HINGLISH_DECODER, HINGLISH_TOKENS)

    val KEYWORD_SPOTTER = Entry(
        fileName = "sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01-mobile.tar.bz2",
        url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/kws-models/sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01-mobile.tar.bz2",
        sizeBytes = 15_667_804,
        sha256 = "2e6ac2577310bfa2f4b6b5fab0478b868c9d0b2cb2c51b3e13b50581b588864d",
        tier = Tier.DEFAULT,
        usedFor = "Background wake words",
        installDirectory = "sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01-mobile",
    )

    val KOKORO = Entry(
        fileName = "kokoro-int8-multi-lang-v1_0.tar.bz2",
        url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-int8-multi-lang-v1_0.tar.bz2",
        sizeBytes = 131_839_838,
        sha256 = "75654a84864be26f345f020f4070c2c019e96dd1b7f9bf6e2ffd59efac6aa5a3",
        tier = Tier.DEFAULT,
        usedFor = "Primary Hindi and English offline voice",
        installDirectory = "kokoro-int8-multi-lang-v1_0",
    )

    val PIPER_HI = Entry(
        fileName = "vits-piper-hi_IN-pratham-medium.tar.bz2",
        url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-hi_IN-pratham-medium.tar.bz2",
        sizeBytes = 67_238_438,
        sha256 = "2084d321e1d2752f2b64ed3012ba27751df01a80da46f52920098cdcb7e35648",
        tier = Tier.DEFAULT,
        usedFor = "Hindi offline voice",
        installDirectory = "vits-piper-hi_IN-pratham-medium",
    )

    val PIPER_EN = Entry(
        fileName = "vits-piper-en_US-lessac-medium.tar.bz2",
        url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-en_US-lessac-medium.tar.bz2",
        sizeBytes = 67_230_653,
        sha256 = "9e3febfacf0abf4270172d2958bcec246032b7e88efc2720840cc80c93de334e",
        tier = Tier.DEFAULT,
        usedFor = "English offline voice",
        installDirectory = "vits-piper-en_US-lessac-medium",
    )

    val QWEN_2B = Entry(
        fileName = "Qwen3.5-2B-Instruct-Q4_K_M.gguf",
        url = "https://huggingface.co/TheStageAI/Qwen3.5-2B-GGUF/resolve/e42cdd7a10a99b70833c43bb1516a696c8075ea8/Qwen3.5-2B-M-TS-Q4_K_M.gguf",
        sizeBytes = 1_073_069_440,
        sha256 = "8d497863b95e392baf022258f864c34f4a28613df340c500bb647486c52657ae",
        tier = Tier.DEFAULT,
        usedFor = "Offline assistant",
    )

    val QWEN_LOW_MEMORY = Entry(
        fileName = "Qwen3.5-0.8B-Instruct-Q4_K_M.gguf",
        url = "https://huggingface.co/TheStageAI/Qwen3.5-0.8B-GGUF/resolve/fff685b81430bd58e703547bb6014f7b5d482f48/Qwen3.5-0.8B-M-TS-Q4_K_M.gguf",
        sizeBytes = 436_743_552,
        // HF lfs.oid / X-Linked-ETag is the file SHA-256; X-Xet-Hash is NOT.
        sha256 = "635788bdc1b0ba1335e47cca0159e531811c722ffad4e3c7b363c2b55ecc26c8",
        tier = Tier.LOW_RAM,
        usedFor = "Offline assistant: low-memory fallback",
    )

    val ALL: List<Entry> = listOf(
        SILERO_VAD,
        STT_ZIPFORMER_ENCODER,
        STT_ZIPFORMER_DECODER,
        STT_ZIPFORMER_JOINER,
        STT_ZIPFORMER_TOKENS,
        STT_INDICCONFORMER_HI,
        STT_INDICCONFORMER_TOKENS,
        HINGLISH_ENCODER,
        HINGLISH_DECODER,
        HINGLISH_TOKENS,
        SPOKEN_LANGUAGE_ID,
        KEYWORD_SPOTTER,
        KOKORO,
        PIPER_HI,
        PIPER_EN,
        QWEN_2B,
        QWEN_LOW_MEMORY,
    )

    val DEFAULT_SET: List<Entry> = ALL.filter { it.tier == Tier.DEFAULT }

    fun defaultSet(lowMemory: Boolean): List<Entry> =
        DEFAULT_SET.filterNot { it == QWEN_2B } + if (lowMemory) QWEN_LOW_MEMORY else QWEN_2B

    fun byFileName(name: String): Entry? = ALL.find { it.fileName == name }
}

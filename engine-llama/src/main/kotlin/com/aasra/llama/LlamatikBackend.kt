package com.aasra.llama

import com.llamatik.library.platform.GenStream
import com.llamatik.library.platform.LlamaBridge

class LlamatikBackend : LlamaEngine.LlamaBackend {
    // 1.7.0's Android AAR packages ggml-cpu, not ggml-vulkan. GPU layer
    // requests otherwise silently use CPU and produce misleading readiness logs.
    override val supportsVulkan: Boolean = false
    @Volatile
    private var loaded = false

    override val isLoaded: Boolean get() = loaded

    @Synchronized
    override fun load(params: LlamaEngine.LlamaLoadParams) {
        if (loaded) return
        LlamaBridge.updateGenerateParams(
            temperature = 0.4f,
            maxTokens = 300,
            topP = 0.9f,
            topK = 40,
            repeatPenalty = 1.1f,
            contextLength = params.nCtx,
            numThreads = params.nThreads,
            useMmap = true,
            flashAttention = true,
            batchSize = 256,
            gpuLayers = 0,
        )
        loaded = LlamaBridge.initGenerateModel(params.modelFile.absolutePath)
        check(loaded) { "llama.cpp could not load ${params.modelFile.name}" }
    }

    @Synchronized
    override fun unload() {
        if (loaded) LlamaBridge.shutdown()
        loaded = false
    }

    @Synchronized
    override fun generate(prompt: String, onToken: (String) -> Unit) {
        check(loaded) { "LLM is not loaded" }
        var failure: String? = null
        LlamaBridge.generateStream(prompt, object : GenStream {
            override fun onDelta(text: String) = onToken(text)
            override fun onComplete() = Unit
            override fun onError(message: String) {
                failure = message
            }
        })
        failure?.let { error(it) }
    }

    override fun cancel() {
        if (loaded) LlamaBridge.nativeCancelGenerate()
    }
}

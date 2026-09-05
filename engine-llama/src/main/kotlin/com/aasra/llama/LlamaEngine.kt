package com.aasra.llama

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Local LLM engine: Qwen3.5 GGUF via llama.cpp (PLAN 4.5).
 *
 * Load contract:
 * - `n_ctx = 4096`, `n_threads = big cores`. The shipped Llamatik AAR uses CPU;
 *   a backend declaring Vulkan support can opt into GPU-first/CPU-fallback.
 * - Loading and file inspection always run on IO. The app initializes speech
 *   engines before asking for the first load.
 * - Idle unload: a 5-minute countdown restarts on every [generate]; on expiry
 *   the context is freed. Next turn reloads transparently.
 *
 * Compiles and runs with NO GGUF files and NO native lib present: every entry
 * point returns [LlmResult.SpokenFallback] carrying a short non-technical
 * sentence (via [QwenChatTemplate.missingModelMessage]) that the pipeline can
 * speak directly in the user's language.
 */
class LlamaEngine(
    private val context: android.content.Context,
    private val scope: CoroutineScope,
    /** Resolves the GGUF file for a tier; defaults to filesDir/models. */
    private val modelFileFor: (RamTier.LlmTier) -> File = { tier ->
        File(modelDir(context), tier.modelFileName)
    },
    /** Created once the Llamatik/JNI binding is wired (Option A/B in build.gradle.kts). */
    var backend: LlamaBackend = LlamatikBackend(),
    @Volatile private var forcedTier: RamTier.LlmTier? = null,
    private val idleUnloadMs: Long = IDLE_UNLOAD_MS,
    private val verifyModel: (File) -> Boolean = { it.isFile && it.length() > 0L },
) {
    data class LlamaLoadParams(
        val modelFile: File,
        val nCtx: Int = N_CTX,
        val nThreads: Int = bigCores(),
        val preferVulkan: Boolean = true,
    )

    sealed interface LlmResult {
        /** Streamed text is flowing via [StreamingGenerator]; nothing to speak yet. */
        data object Streaming : LlmResult

        /**
         * Nothing was generated — speak [spokenText] (already localized,
         * non-technical) instead of the model reply.
         */
        data class SpokenFallback(val spokenText: String) : LlmResult
    }

    /** Minimal surface the native binding must implement. Llamatik adapts here. */
    interface LlamaBackend {
        val isLoaded: Boolean
        val supportsVulkan: Boolean get() = false

        @Throws(Exception::class)
        fun load(params: LlamaLoadParams)

        fun unload()

        /** Blocking streaming decode; calls [onToken] per decoded piece. */
        @Throws(Exception::class)
        fun generate(prompt: String, onToken: (String) -> Unit)

        /** Interrupts a blocking decode when the backend supports it. */
        fun cancel() = Unit
    }

    /**
     * Explicit no-op backend for tests and degraded builds. It reports
     * not-loaded so every path degrades to [LlmResult.SpokenFallback].
     */
    class NoopLlamaBackend : LlamaBackend {
        override val isLoaded: Boolean = false
        override fun load(params: LlamaLoadParams) =
            throw java.io.FileNotFoundException("LLM backend not wired yet: ${params.modelFile.name}")
        override fun unload() = Unit
        override fun generate(prompt: String, onToken: (String) -> Unit) =
            throw IllegalStateException("LLM backend not wired yet")
        override fun cancel() = Unit
    }

    private val mutex = Mutex()
    enum class LoadStatus { LOADING, MISSING, FAILED, READY }
    data class Readiness(val status: LoadStatus, val message: String)
    private val _readiness = MutableStateFlow(Readiness(LoadStatus.LOADING, "Loading offline assistant."))
    val readiness = _readiness.asStateFlow()
    private var activeTier: RamTier.LlmTier? = null
    private var usedVulkan = false
    private var idleJob: Job? = null
    private var language: String = QwenChatTemplate.LANGUAGE_EN
    private var userName: String? = null

    fun setUser(language: String, userName: String?) {
        this.language = language
        this.userName = userName
    }

    fun setForcedTier(tier: RamTier.LlmTier?) {
        forcedTier = tier
    }

    /**
     * Loads the tier-appropriate GGUF if needed. Returns a [LlmResult] so the
     * caller can speak a fallback when the model file is absent — never throws
     * for missing files.
     */
    suspend fun ensureLoaded(): LlmResult = withContext(Dispatchers.IO) { mutex.withLock {
        if (backend.isLoaded) {
            pokeIdleTimerLocked()
            return@withLock LlmResult.Streaming
        }
        val tier = forcedTier ?: RamTier.decide(context)
        val file = modelFileFor(tier)
        if (!verifyModel(file)) {
            // Small-tier fallback: a partial download may still leave the 0.8B usable.
            if (tier != RamTier.LlmTier.SMALL) {
                val small = modelFileFor(RamTier.LlmTier.SMALL)
                if (verifyModel(small)) return@withLock loadLocked(RamTier.LlmTier.SMALL, small)
            }
            android.util.Log.w(TAG, "GGUF missing: ${file.absolutePath}")
            _readiness.value = Readiness(LoadStatus.MISSING, "Install ${file.name} in ${file.parent}.")
            return@withLock LlmResult.SpokenFallback(QwenChatTemplate.missingModelMessage(language))
        }
        loadLocked(tier, file)
    } }

    /**
     * Full turn: ensures load, builds the ChatML prompt, streams tokens into
     * [chunker], and returns tool calls found in the reply. Missing model or
     * load failure → [LlmResult.SpokenFallback], empty tool list.
     */
    suspend fun generate(
        userText: String,
        history: List<Pair<String, String>> = emptyList(),
        chunker: StreamingGenerator,
        onToken: (String) -> Unit = {},
    ): Pair<LlmResult, List<ToolCallParser.ToolCall>> {
        val ready = ensureLoaded()
        if (ready is LlmResult.SpokenFallback) return ready to emptyList()
        val prompt = QwenChatTemplate.build(userText, history, userName, language)
        val fullText = StringBuilder()
        val toolXml = mutableListOf<String>()
        val genChunker = StreamingGenerator(
            onSentence = chunker::accept,
            onToolCallXml = { xml ->
                toolXml += xml
                chunker.accept(xml) // Routed to chunker's tool channel, never spoken.
            },
        )
        try {
            // Keep decode structured so backend failures and caller cancellation
            // propagate to this turn instead of escaping into the engine scope.
            withContext(Dispatchers.Default) { mutex.withLock {
                idleJob?.cancel()
                val context = currentCoroutineContext()
                backend.generate(prompt) { token ->
                    context.ensureActive()
                    fullText.append(token)
                    genChunker.accept(token)
                    onToken(token)
                }
            } }
            genChunker.flush()
        } catch (e: CancellationException) {
            throw e
        } catch (e: LinkageError) {
            _readiness.value = Readiness(LoadStatus.FAILED, "Native generation is unavailable. Repair the Llamatik runtime: ${e.message}")
            return LlmResult.SpokenFallback(QwenChatTemplate.missingModelMessage(language)) to emptyList()
        } catch (e: OutOfMemoryError) {
            _readiness.value = Readiness(LoadStatus.FAILED, "Not enough memory for offline generation. Install the small model and reload.")
            return LlmResult.SpokenFallback(QwenChatTemplate.missingModelMessage(language)) to emptyList()
        } catch (e: Exception) {
            android.util.Log.e(TAG, "generate failed (vulkan=$usedVulkan)", e)
            if (usedVulkan && fullText.isEmpty()) {
                // Retry only before any output: replaying partial speech/tools is unsafe.
                return retryOnCpu(userText, history, chunker, onToken)
            }
            chunker.flush()
            _readiness.value = Readiness(LoadStatus.FAILED, "Offline generation failed. Reload models: ${e.message}")
            return LlmResult.SpokenFallback(QwenChatTemplate.missingModelMessage(language)) to emptyList()
        }
        if (fullText.isBlank()) {
            _readiness.value = Readiness(LoadStatus.FAILED, "Offline model returned no answer. Reload models or use Hybrid with internet.")
            return LlmResult.SpokenFallback(QwenChatTemplate.missingModelMessage(language)) to emptyList()
        }
        mutex.withLock { pokeIdleTimerLocked() }
        val calls = toolXml.flatMap { ToolCallParser.parseAll(it) }
        return LlmResult.Streaming to calls
    }

    fun cancel() {
        runCatching { backend.cancel() }
    }

    /** Frees the context immediately (foreground-service shutdown, demo reset). */
    suspend fun unload() = withContext(Dispatchers.IO) { mutex.withLock {
        idleJob?.cancel()
        idleJob = null
        try {
            backend.unload()
        } catch (e: Exception) {
            android.util.Log.w(TAG, "unload failed", e)
        }
        activeTier = null
    } }

    // ── internals ──

    private fun loadLocked(tier: RamTier.LlmTier, file: File): LlmResult {
        _readiness.value = Readiness(LoadStatus.LOADING, "Loading ${file.name}.")
        val params = LlamaLoadParams(modelFile = file, preferVulkan = backend.supportsVulkan)
        // Try Vulkan offload first; any GPU failure → CPU retry (PLAN 4.5).
        if (params.preferVulkan) {
            try {
                backend.load(params)
                check(backend.isLoaded) { "Native backend returned without loading a model" }
                activeTier = tier
                usedVulkan = true
                _readiness.value = Readiness(LoadStatus.READY, "Offline assistant loaded: ${file.name}.")
                pokeIdleTimerLocked()
                android.util.Log.i(TAG, "loaded ${file.name} n_ctx=${params.nCtx} " +
                    "threads=${params.nThreads} gpu=vulkan")
                return LlmResult.Streaming
            } catch (e: CancellationException) {
                throw e
            } catch (e: LinkageError) {
                return nativeLoadFailed(file, e)
            } catch (e: OutOfMemoryError) {
                return nativeLoadFailed(file, e)
            } catch (e: Exception) {
                android.util.Log.w(TAG, "Vulkan load failed, falling back to CPU", e)
            }
        }
        return try {
            backend.load(params.copy(preferVulkan = false))
            check(backend.isLoaded) { "Native backend returned without loading a model" }
            activeTier = tier
            usedVulkan = false
            _readiness.value = Readiness(LoadStatus.READY, "Offline assistant loaded on CPU: ${file.name}.")
            pokeIdleTimerLocked()
            android.util.Log.i(TAG, "loaded ${file.name} n_ctx=${params.nCtx} " +
                "threads=${params.nThreads} gpu=cpu")
            LlmResult.Streaming
        } catch (e: CancellationException) {
            throw e
        } catch (e: LinkageError) {
            nativeLoadFailed(file, e)
        } catch (e: OutOfMemoryError) {
            nativeLoadFailed(file, e)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "CPU load failed for ${file.name}", e)
            nativeLoadFailed(file, e)
        }
    }

    private fun nativeLoadFailed(file: File, error: Throwable): LlmResult.SpokenFallback {
        _readiness.value = Readiness(LoadStatus.FAILED,
            "Could not load ${file.name}. Verify the model and arm64 Llamatik runtime, then reload. ${error.message.orEmpty()}")
        return LlmResult.SpokenFallback(QwenChatTemplate.missingModelMessage(language))
    }

    private suspend fun retryOnCpu(
        userText: String,
        history: List<Pair<String, String>>,
        chunker: StreamingGenerator,
        onToken: (String) -> Unit,
    ): Pair<LlmResult, List<ToolCallParser.ToolCall>> {
        val tier = activeTier ?: return LlmResult.SpokenFallback(
            QwenChatTemplate.missingModelMessage(language),
        ) to emptyList()
        val loaded = withContext(Dispatchers.IO) { mutex.withLock {
            val file = modelFileFor(tier)
            try {
                backend.unload()
            } catch (_: Exception) {
            }
            try {
                backend.load(LlamaLoadParams(modelFile = file, preferVulkan = false))
                usedVulkan = false
                backend.isLoaded
            } catch (e: CancellationException) {
                throw e
            } catch (e: LinkageError) {
                nativeLoadFailed(file, e)
                false
            } catch (e: OutOfMemoryError) {
                nativeLoadFailed(file, e)
                false
            } catch (e: Exception) {
                android.util.Log.e(TAG, "CPU retry failed", e)
                nativeLoadFailed(file, e)
                usedVulkan = false
                false
            }
        } }
        if (!loaded) return LlmResult.SpokenFallback(QwenChatTemplate.missingModelMessage(language)) to emptyList()
        return generate(userText, history, chunker, onToken)
    }

    /** Must hold [mutex]. Restarts the 5-minute idle countdown (PLAN 7). */
    private fun pokeIdleTimerLocked() {
        idleJob?.cancel()
        idleJob = scope.launch(Dispatchers.IO) {
            delay(idleUnloadMs)
            mutex.withLock {
                try {
                    backend.unload()
                } catch (e: Exception) {
                    android.util.Log.w(TAG, "idle unload failed", e)
                }
                activeTier = null
                android.util.Log.i(TAG, "LLM unloaded after ${idleUnloadMs / 60_000} min idle")
            }
        }
    }

    companion object {
        const val N_CTX = 4096
        const val IDLE_UNLOAD_MS = 5L * 60L * 1000L

        /** big-core count heuristic: leave 2 cores for audio/pipeline threads. */
        fun bigCores(): Int = maxOf(2, Runtime.getRuntime().availableProcessors() - 2)

        internal fun modelDir(context: android.content.Context): File =
            File(context.filesDir, "models")
        private const val TAG = "LlamaEngine"
    }
}

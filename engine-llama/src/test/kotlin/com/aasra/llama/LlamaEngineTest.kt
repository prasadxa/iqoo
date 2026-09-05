package com.aasra.llama

import android.content.Context
import java.util.concurrent.CancellationException
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock

class LlamaEngineTest {
    @get:Rule val files = TemporaryFolder()

    @Test fun packagedBackendDoesNotClaimVulkan() {
        assertFalse(LlamatikBackend().supportsVulkan)
    }

    @Test fun rejectedIntegrityNeverReachesNativeLoader() = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        val file = files.newFile("bad.gguf")
        var loads = 0
        val engine = LlamaEngine(mock(Context::class.java), scope, { file },
            Backend(onLoad = { loads++ }), RamTier.LlmTier.SMALL, verifyModel = { false })
        try {
            assertTrue(engine.ensureLoaded() is LlamaEngine.LlmResult.SpokenFallback)
            assertEquals(0, loads)
            assertEquals(LlamaEngine.LoadStatus.MISSING, engine.readiness.value.status)
        } finally { scope.cancel() }
    }

    @Test fun backendThatReturnsWithoutLoadingIsNotReady() = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        val file = files.newFile("false.gguf").apply { writeText("GGUF") }
        val backend = object : LlamaEngine.LlamaBackend {
            override val isLoaded = false
            override fun load(params: LlamaEngine.LlamaLoadParams) = Unit
            override fun unload() = Unit
            override fun generate(prompt: String, onToken: (String) -> Unit) = Unit
        }
        val engine = LlamaEngine(mock(Context::class.java), scope, { file }, backend, RamTier.LlmTier.SMALL)
        try {
            assertTrue(engine.ensureLoaded() is LlamaEngine.LlmResult.SpokenFallback)
            assertEquals(LlamaEngine.LoadStatus.FAILED, engine.readiness.value.status)
        } finally { scope.cancel() }
    }

    @Test fun loadingNeverRunsOnTheCallerThread() = runBlocking {
        val caller = Thread.currentThread()
        var loader: Thread? = null
        val backend = Backend(onLoad = { loader = Thread.currentThread() })
        val scope = CoroutineScope(SupervisorJob())
        val file = files.newFile("test.gguf").apply { writeText("GGUF") }
        val engine = LlamaEngine(mock(Context::class.java), scope, { file }, backend, RamTier.LlmTier.SMALL)
        try {
            assertEquals(LlamaEngine.LlmResult.Streaming, engine.ensureLoaded())
            assertNotEquals(caller, loader)
        } finally { scope.cancel() }
    }

    @Test fun missingJniBecomesFailureInsteadOfCrashingStartup() = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        val file = files.newFile("native.gguf").apply { writeText("GGUF") }
        val backend = Backend(onLoad = { throw UnsatisfiedLinkError("llama_jni unavailable") })
        val engine = LlamaEngine(mock(Context::class.java), scope, { file }, backend, RamTier.LlmTier.SMALL)
        try {
            assertTrue(engine.ensureLoaded() is LlamaEngine.LlmResult.SpokenFallback)
        } finally { scope.cancel() }
    }

    @Test fun cancellationDoesNotRetryOrReturnASpokenSuccess() = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        val file = files.newFile("cancel.gguf").apply { writeText("GGUF") }
        var attempts = 0
        val backend = Backend(onGenerate = { attempts++; throw CancellationException("stop") })
        val engine = LlamaEngine(mock(Context::class.java), scope, { file }, backend, RamTier.LlmTier.SMALL)
        try {
            try {
                engine.generate("hello", chunker = StreamingGenerator(onSentence = {}, onToolCallXml = {}))
                fail("Cancellation must propagate")
            } catch (_: CancellationException) { }
            assertEquals(1, attempts)
        } finally { scope.cancel() }
    }

    @Test fun partialGenerationIsNotReplayedAfterGpuFailure() = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        val file = files.newFile("partial.gguf").apply { writeText("GGUF") }
        var attempts = 0
        val backend = object : LlamaEngine.LlamaBackend {
            override var isLoaded = false
            override val supportsVulkan = true
            override fun load(params: LlamaEngine.LlamaLoadParams) { isLoaded = true }
            override fun unload() { isLoaded = false }
            override fun generate(prompt: String, onToken: (String) -> Unit) {
                attempts++
                onToken("Already spoken. ")
                error("decode failed")
            }
        }
        val engine = LlamaEngine(mock(Context::class.java), scope, { file }, backend, RamTier.LlmTier.SMALL)
        try {
            assertTrue(engine.generate("hello", chunker = StreamingGenerator(onSentence = {}, onToolCallXml = {})).first
                is LlamaEngine.LlmResult.SpokenFallback)
            assertEquals(1, attempts)
        } finally { scope.cancel() }
    }

    @Test fun emptyNativeCompletionIsNotASuccessfulReply() = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        val file = files.newFile("empty.gguf").apply { writeText("GGUF") }
        val engine = LlamaEngine(mock(Context::class.java), scope, { file }, Backend(), RamTier.LlmTier.SMALL)
        try {
            assertTrue(engine.generate("hello", chunker = StreamingGenerator(onSentence = {}, onToolCallXml = {})).first
                is LlamaEngine.LlmResult.SpokenFallback)
            assertEquals(LlamaEngine.LoadStatus.FAILED, engine.readiness.value.status)
        } finally { scope.cancel() }
    }

    @Test fun nativeGenerationLinkageFailureIsReported() = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        val file = files.newFile("jni.gguf").apply { writeText("GGUF") }
        val engine = LlamaEngine(mock(Context::class.java), scope, { file },
            Backend(onGenerate = { throw UnsatisfiedLinkError("missing generation symbol") }), RamTier.LlmTier.SMALL)
        try {
            assertTrue(engine.generate("hello", chunker = StreamingGenerator(onSentence = {}, onToolCallXml = {})).first
                is LlamaEngine.LlmResult.SpokenFallback)
            assertEquals(LlamaEngine.LoadStatus.FAILED, engine.readiness.value.status)
        } finally { scope.cancel() }
    }

    @Test fun modelAllocationFailureIsReportedWithoutRetrying() = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        val file = files.newFile("oom.gguf").apply { writeText("GGUF") }
        var attempts = 0
        val engine = LlamaEngine(mock(Context::class.java), scope, { file },
            Backend(onLoad = { attempts++; throw OutOfMemoryError("model allocation failed") }), RamTier.LlmTier.SMALL)
        try {
            assertTrue(engine.ensureLoaded() is LlamaEngine.LlmResult.SpokenFallback)
            assertEquals(1, attempts)
            assertEquals(LlamaEngine.LoadStatus.FAILED, engine.readiness.value.status)
        } finally { scope.cancel() }
    }

    private class Backend(
        val onLoad: () -> Unit = {},
        val onGenerate: () -> Unit = {},
    ) : LlamaEngine.LlamaBackend {
        override var isLoaded = false
        override val supportsVulkan = true
        override fun load(params: LlamaEngine.LlamaLoadParams) { onLoad(); isLoaded = true }
        override fun unload() { isLoaded = false }
        override fun generate(prompt: String, onToken: (String) -> Unit) { onGenerate() }
    }
}

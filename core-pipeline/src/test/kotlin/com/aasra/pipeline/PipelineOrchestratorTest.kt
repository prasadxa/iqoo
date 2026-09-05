package com.aasra.pipeline

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PipelineOrchestratorTest {
    @Test
    fun cancellationAtPlaybackStartCannotLeaveTheMicrophoneMuted() {
        val cancelled = CountDownLatch(1)
        lateinit var orchestrator: PipelineOrchestrator
        orchestrator = PipelineOrchestrator(
            llm = CapturingLanguageModel(),
            tts = object : SpeechSynthesizer {
                override val sampleRateHz = 24_000
                override fun synthesize(text: String, language: String) =
                    SpeechAudio(shortArrayOf(1), sampleRateHz)
            },
            chunker = SentenceChunker(),
            audioOutput = BlockingAudioOutput(),
            listener = object : PipelineOrchestrator.Listener {
                override fun onState(state: PipelineState) {
                    if (state is PipelineState.Speaking) {
                        orchestrator.cancelCurrentTurn()
                        cancelled.countDown()
                    }
                }
            },
        )
        try {
            orchestrator.speakCloudSentence("Cancel before writing")
            assertTrue(cancelled.await(2, TimeUnit.SECONDS))
            Thread.sleep(100)
            assertFalse(orchestrator.echoController.ttsPlaying)
        } finally {
            orchestrator.close()
        }
    }


    @Test
    fun echoGateStaysClosedUntilBufferedSpeakerAudioDrains() {
        val written = CountDownLatch(1)
        val draining = java.util.concurrent.atomic.AtomicBoolean(true)
        val output = object : AudioOutput {
            override val sampleRateHz = 24_000
            override val isPlaying: Boolean get() = draining.get()
            override fun play(samples: ShortArray, shouldContinue: () -> Boolean) {
                written.countDown() // Write returned, but the speaker is still playing.
            }
            override fun flush() { draining.set(false) }
            override fun stop() = flush()
        }
        val orchestrator = PipelineOrchestrator(
            llm = CapturingLanguageModel(),
            tts = object : SpeechSynthesizer {
                override val sampleRateHz = 24_000
                override fun synthesize(text: String, language: String) =
                    SpeechAudio(shortArrayOf(1), sampleRateHz)
            },
            chunker = SentenceChunker(),
            audioOutput = output,
        )
        try {
            orchestrator.speakCloudSentence("Still speaking")
            assertTrue(written.await(2, TimeUnit.SECONDS))
            Thread.sleep(100)
            assertTrue("Write completion is not playback completion", orchestrator.echoController.ttsPlaying)
            assertFalse(orchestrator.echoController.shouldFeedVad(1f))
            draining.set(false)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (orchestrator.echoController.ttsPlaying && System.nanoTime() < deadline) Thread.sleep(10)
            assertFalse(orchestrator.echoController.ttsPlaying)
        } finally {
            orchestrator.close()
        }
    }

    @Test
    fun localAnswerUsesTheRecognizedLanguageForSpeech() {
        val llm = CapturingLanguageModel()
        val languageRef = AtomicReference<String>()
        val spoken = CountDownLatch(1)
        val orchestrator = PipelineOrchestrator(
            llm = llm,
            tts = object : SpeechSynthesizer {
                override val sampleRateHz = 24_000
                override fun synthesize(text: String, language: String): SpeechAudio {
                    languageRef.set(language)
                    return SpeechAudio(shortArrayOf(1), sampleRateHz)
                }
            },
            chunker = SentenceChunker(minSentenceChars = 1),
            audioOutput = object : AudioOutput {
                override val sampleRateHz = 24_000
                override val isPlaying = false
                override fun play(samples: ShortArray, shouldContinue: () -> Boolean) {
                    spoken.countDown()
                }
                override fun flush() = Unit
                override fun stop() = Unit
            },
        )

        try {
            orchestrator.onVadSegmentEnd("hello", 1f, "en")
            llm.emit("Hello there. ")
            assertTrue(spoken.await(2, TimeUnit.SECONDS))
            assertEquals("en", languageRef.get())
        } finally {
            orchestrator.close()
        }
    }

    @Test
    fun bargeInRejectsTokensFromInterruptedGeneration() {
        val llm = CapturingLanguageModel()
        val output = BlockingAudioOutput()
        val orchestrator = PipelineOrchestrator(
            llm = llm,
            tts = object : SpeechSynthesizer {
                override val sampleRateHz = 24_000
                override fun synthesize(text: String, language: String) =
                    SpeechAudio(shortArrayOf(1), sampleRateHz)
            },
            chunker = SentenceChunker(minSentenceChars = 1),
            audioOutput = output,
        )

        try {
            orchestrator.onVadSegmentEnd("hello", 1f, "en")
            llm.emit("First sentence. ")
            assertTrue(output.firstPlaybackStarted.await(2, TimeUnit.SECONDS))

            orchestrator.onUserSpeechStarted()
            llm.emit("Stale sentence. ")
            llm.finish()

            Thread.sleep(200)
            assertEquals(1, output.playCount.get())
        } finally {
            output.releasePlayback.countDown()
            orchestrator.close()
        }
    }

    private class CapturingLanguageModel : LanguageModel {
        private var tokenCallback: (String) -> Unit = {}
        private var doneCallback: () -> Unit = {}

        override fun generateStream(
            prompt: String,
            systemPrompt: String,
            onToken: (String) -> Unit,
            onToolCall: (ToolCall) -> Unit,
            onDone: () -> Unit,
            onError: (Throwable) -> Unit,
        ) {
            tokenCallback = onToken
            doneCallback = onDone
        }

        override fun cancel() = Unit

        fun emit(token: String) = tokenCallback(token)
        fun finish() = doneCallback()
    }

    private class BlockingAudioOutput : AudioOutput {
        override val sampleRateHz = 24_000
        override val isPlaying: Boolean get() = playCount.get() > 0
        val playCount = AtomicInteger()
        val firstPlaybackStarted = CountDownLatch(1)
        val releasePlayback = CountDownLatch(1)

        override fun play(samples: ShortArray, shouldContinue: () -> Boolean) {
            if (!shouldContinue()) return
            playCount.incrementAndGet()
            firstPlaybackStarted.countDown()
            releasePlayback.await(2, TimeUnit.SECONDS)
        }

        override fun flush() {
            releasePlayback.countDown()
        }

        override fun stop() {
            releasePlayback.countDown()
        }
    }
}

package com.aasra.pipeline

import android.os.SystemClock
import com.aasra.audio.EchoController
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Turn orchestrator: VAD -> STT -> Router -> LLM -> chunker -> TTS (PLAN 1).
 *
 * Boundary design: VAD and the mic live outside (engine-sherpa / app drive
 * this class through three entry points). This keeps core-pipeline engine-free
 * and unit-testable:
 *
 *  - [onVadSegmentEnd]: VAD closed a segment; STT has the final transcript.
 *  - [onPartial]: streaming STT partial; forwarded to UI only, never answered.
 *  - [onUserSpeechStarted]: VAD saw speech while SPEAKING -> barge-in flush.
 *
 * Answer path per turn: [Router] picks Local vs cloud; for Local this class
 * runs the LLM itself, streams tokens into the [TextChunker], synthesises each
 * sentence and plays the FIRST one ASAP (PLAN 4.6). For cloud routes it emits
 * [Listener.onCloudHandoff] and app/ (Track C/D) runs PLAN 5, feeding cloud
 * sentences back via [speakCloudSentence] so barge-in + first-audio timing
 * stay in one place.
 *
 * M1 timing (PLAN 4.7): [Listener.onLatencyMs] reports vad_end->first_audio
 * per turn; target < 1500 ms on the iQOO device.
 */
class PipelineOrchestrator(
    private val llm: LanguageModel,
    private val tts: SpeechSynthesizer,
    private val chunker: TextChunker,
    private val audioOutput: AudioOutput,
    private val decideRoute: (transcript: String, confidence: Float, language: String) -> Route =
        { _, _, _ -> Route.Local },
    private val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    private val listener: Listener = object : Listener {},
) {
    interface Listener {
        fun onState(state: PipelineState) {}
        fun onTranscript(text: String, isFinal: Boolean) {}
        /** Stageuetiming, e.g. ("vad_end_to_first_audio", 900). */
        fun onLatencyMs(stage: String, ms: Long) {}
        /** Non-Local route: app/ executes the PLAN 5 cloud path for this turn. */
        fun onCloudHandoff(route: Route, transcript: String, confidence: Float) {}
        fun onToolCall(call: ToolCall) {}
        /** Every failure gets a spoken, non-technical message via [speakError]. */
        fun onError(stage: String, error: Throwable) {}
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val ttsQueue = Channel<QueuedSpeech>(Channel.UNLIMITED)
    private val utteranceIds = AtomicLong(0)

    @Volatile private var state: PipelineState = PipelineState.Idle
    @Volatile private var vadEndNanos: Long = 0L
    @Volatile private var firstAudioReportedFor: Long = -1L

    /** Producer hold here; app wires actual playback to playbackPlaying separately. */
    val echoController = EchoController()

    init {
        scope.launch { ttsWorker() }
    }

    // ── VAD / STT entry points ───────────────────────────────────────────────

    /** Streaming partial: UI only. */
    fun onPartial(text: String) {
        safe { listener.onTranscript(text, false) }
    }

    /**
     * VAD closed a segment and STT delivered the final transcript.
     * Starts the answer path unless we're mid-barge-in.
     */
    fun onVadSegmentEnd(transcript: String, confidence: Float, language: String) {
        if (transcript.isBlank()) {
            setState(PipelineState.Listening)
            return
        }
        vadEndNanos = SystemClock.elapsedRealtimeNanos()
        utteranceIds.incrementAndGet()
        safe { listener.onTranscript(transcript, true) }
        when (val route = decideRoute(transcript, confidence, language)) {
            is Route.Local -> answerLocal(transcript, language)
            else -> safe { listener.onCloudHandoff(route, transcript, confidence) }
        }
    }

    /**
     * Barge-in (PLAN 4.7 interrupt test): user speech while SPEAKING.
     * Flushes playback NOW, cancels generation + queued sentences, listens.
     */
    fun onUserSpeechStarted() {
        if (state !is PipelineState.Speaking) return
        cancelCurrentTurn()
    }

    /** Invalidates callbacks and audio from the active turn, regardless of current state. */
    @Synchronized
    fun cancelCurrentTurn() {
        utteranceIds.incrementAndGet()
        try {
            llm.cancel()
        } catch (_: Exception) {
        }
        // Drain sentences queued but not yet synthesised.
        while (ttsQueue.tryReceive().isSuccess) {
        }
        try {
            audioOutput.flush()
        } catch (_: Exception) {
        }
        setTtsPlaying(false)
        setState(PipelineState.Listening)
    }

    /** Cloud path (app/) feeds synthesised-ready sentences back through here. */
    fun speakCloudSentence(sentence: String, language: String = "hi") {
        if (sentence.isBlank()) return
        val utteranceId = utteranceIds.get()
        scope.launch { ttsQueue.send(QueuedSpeech(utteranceId, sentence, language)) }
    }

    fun close() {
        cancelCurrentTurn()
        scope.cancel()
    }

    // ── Local answer path ────────────────────────────────────────────────────

    private fun answerLocal(transcript: String, language: String) {
        setState(PipelineState.Thinking)
        chunker.reset()
        val utteranceId = utteranceIds.incrementAndGet()
        llm.generateStream(
            prompt = transcript,
            systemPrompt = systemPrompt,
            onToken = { token ->
                if (utteranceId != utteranceIds.get()) return@generateStream // barged-in
                val sentences = try {
                    chunker.push(token)
                } catch (_: Exception) {
                    emptyList()
                }
                for (s in sentences) {
                    scope.launch { ttsQueue.send(QueuedSpeech(utteranceId, s, language)) }
                }
            },
            onToolCall = { call ->
                if (utteranceId == utteranceIds.get()) safe { listener.onToolCall(call) }
            },
            onDone = {
                if (utteranceId != utteranceIds.get()) return@generateStream
                val tail = try {
                    chunker.flush()
                } catch (_: Exception) {
                    emptyList()
                }
                for (s in tail) {
                    scope.launch { ttsQueue.send(QueuedSpeech(utteranceId, s, language)) }
                }
            },
            onError = { e ->
                if (utteranceId == utteranceIds.get()) {
                    safe { listener.onError("llm", e) }
                    speakError()
                }
            },
        )
    }

    /** Serial TTS worker: first sentence starts playback ASAP (PLAN 4.6). */
    private suspend fun ttsWorker() {
        for (queued in ttsQueue) {
            if (queued.utteranceId != utteranceIds.get()) continue
            val audio = try {
                tts.synthesize(queued.text, queued.language)
            } catch (e: Exception) {
                safe { listener.onError("tts", e) }
                continue
            }
            val current = synchronized(this) {
                if (queued.utteranceId != utteranceIds.get()) {
                    false
                } else {
                    setTtsPlaying(true)
                    setState(PipelineState.Speaking(queued.utteranceId))
                    true
                }
            }
            if (!current) continue
            try {
                // 24 kHz synth -> player rate (normally identity).
                val out = if (audio.sampleRateHz == audioOutput.sampleRateHz) {
                    audio.samples
                } else {
                    com.aasra.audio.Resampler.resample(
                        audio.samples, audio.sampleRateHz, audioOutput.sampleRateHz,
                    )
                }
                if (queued.utteranceId == utteranceIds.get()) {
                    reportFirstAudio()
                    audioOutput.play(out) { queued.utteranceId == utteranceIds.get() }
                    // A blocking write only enqueues PCM; it does not await the speaker.
                    while (queued.utteranceId == utteranceIds.get() && audioOutput.isPlaying) {
                        delay(20)
                    }
                }
            } catch (e: Exception) {
                safe { listener.onError("playback", e) }
            } finally {
                // Cancellation already cleared this producer's hold. An old worker
                // must not overwrite the state of a newer turn.
                synchronized(this) {
                    if (queued.utteranceId == utteranceIds.get()) {
                        setTtsPlaying(false)
                    }
                }
            }
        }
    }

    private fun reportFirstAudio() {
        val id = utteranceIds.get()
        if (firstAudioReportedFor == id || vadEndNanos == 0L) return
        firstAudioReportedFor = id
        val ms = (SystemClock.elapsedRealtimeNanos() - vadEndNanos) / 1_000_000
        safe { listener.onLatencyMs("vad_end_to_first_audio", ms) }
    }

    private fun speakError() {
        // Non-technical, speakable fallback (PLAN 7 error UX). Language choice
        // is app/'s; Hindi default matches the primary user.
        val utteranceId = utteranceIds.get()
        scope.launch {
            ttsQueue.send(
                QueuedSpeech(
                    utteranceId,
                    "Maaf kijiye, kuch gadbad ho gayi. Kripya dobara boliye.",
                    "hi",
                ),
            )
        }
    }

    private fun setTtsPlaying(playing: Boolean) {
        echoController.ttsPlaying = playing
    }

    private fun setState(s: PipelineState) {
        state = s
        safe { listener.onState(s) }
    }

    private inline fun safe(block: () -> Unit) {
        try {
            block()
        } catch (_: Exception) {
        }
    }

    private data class QueuedSpeech(
        val utteranceId: Long,
        val text: String,
        val language: String,
    )

    companion object {
        /** Short, spoken-style, no markdown/emoji (PLAN 4.5). Full prompt owned by app/. */
        const val DEFAULT_SYSTEM_PROMPT =
            "You are Aasra, a kind voice companion for elderly users. " +
                "Reply in one or two short spoken sentences. No markdown, no emoji. " +
                "Be respectful. Confirm before any action. " +
                "Never give medicine dosages; always say to ask their doctor."
    }
}

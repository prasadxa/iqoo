package com.aasra.pipeline

/**
 * Stage interfaces of the speech-to-speech loop (PLAN 4.1: kept from the
 * loyality7/speech-to-speech-mobile fork). Engines implement these; the
 * [PipelineOrchestrator] wires them. All callbacks may arrive on background
 * threads — implementations must be thread-safe.
 */

/** Mic side. Implemented by an adapter over core-audio AudioRecorder (app/). */
interface AudioInput {
    fun start(): Boolean
    fun stop()
    fun setTtsPlaying(playing: Boolean)
}

/** Speaker side. Implemented by an adapter over core-audio AudioPlayer (app/). */
interface AudioOutput {
    /** Streams PCM16 at [sampleRateHz]; starts playback on first write. */
    fun play(samples: ShortArray, shouldContinue: () -> Boolean = { true })
    /** Barge-in: halt now, drop buffered audio. */
    fun flush()
    fun stop()
    val isPlaying: Boolean
    val sampleRateHz: Int
}

/** One recognition update. Confidence is a 0..1 proxy (avg token logprob or
 *  Zipformer endpoint confidence, PLAN 4.4); low values trigger re-listen. */
data class RecognitionResult(
    val text: String,
    val isFinal: Boolean,
    val confidence: Float,
    /** "en" / "hi" / "unknown" — from user setting or LanguageId auto mode. */
    val language: String = "unknown",
)

interface SpeechRecognizer {
    fun setListener(listener: ((RecognitionResult) -> Unit)?)
    /** Feeds one 16 kHz PCM16 frame (20 ms from the recorder). */
    fun acceptAudio(frame: ShortArray)
    /** Clears decoder state between turns. */
    fun reset()
    fun close()
}

/** A tool invocation parsed out of an LLM turn (Qwen ChatML <tool_call> JSON). */
data class ToolCall(
    val name: String,
    /** Raw JSON object string of arguments. */
    val argumentsJson: String,
)

interface LanguageModel {
    /**
     * Streams a reply. [onToken] receives content deltas in order (feed these to
     * the [TextChunker]); [onToolCall] fires per parsed tool call, including
     * escalate(reason). Terminal state is signalled by [onDone].
     */
    fun generateStream(
        prompt: String,
        systemPrompt: String,
        onToken: (String) -> Unit,
        onToolCall: (ToolCall) -> Unit,
        onDone: () -> Unit,
        onError: (Throwable) -> Unit,
    )
    /** Cancels in-flight generation (barge-in). */
    fun cancel()
}

/** PCM16 at [sampleRateHz] (24 kHz for Kokoro/Piper/bulbul). */
data class SpeechAudio(
    val samples: ShortArray,
    val sampleRateHz: Int,
)

interface SpeechSynthesizer {
    val sampleRateHz: Int
    /** Synthesises one complete sentence (chunker output). Blocking. */
    fun synthesize(text: String, language: String): SpeechAudio
}

/**
 * Splits the LLM token stream into speakable sentences so TTS starts before
 * the reply finishes (PLAN 4.5/4.6: first-sentence playback).
 */
interface TextChunker {
    /** Pushes a token delta; returns sentences that completed. */
    fun push(token: String): List<String>
    /** End of turn: returns any trailing partial as a final chunk. */
    fun flush(): List<String>
    fun reset()
}

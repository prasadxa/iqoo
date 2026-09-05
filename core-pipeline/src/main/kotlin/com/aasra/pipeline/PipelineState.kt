package com.aasra.pipeline

/**
 * Voice-loop state. Drives the giant mic indicator in the Compose UI
 * (PLAN 6.2: listening / thinking / speaking) — app/ observes it and must
 * render text at 28 sp+, tap targets >= 64 dp.
 */
sealed class PipelineState {
    /** Idle: wake-word only (background) or waiting for Talk press. */
    data object Idle : PipelineState()

    /** Mic open, VAD + streaming STT active. */
    data object Listening : PipelineState()

    /** Final transcript in hand; LLM generating (local or cloud). */
    data object Thinking : PipelineState()

    /** TTS audio playing; barge-in armed via EchoController. */
    data class Speaking(val utteranceId: Long = 0L) : PipelineState()
}

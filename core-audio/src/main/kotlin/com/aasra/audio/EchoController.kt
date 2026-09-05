package com.aasra.audio

/**
 * Speaker-safe half-duplex gate for local recognition and cloud mic uplink.
 * Energy cannot distinguish a loud speaker from a user, even with device AEC.
 * Voice interruption is disabled during output; explicit tap interruption stays
 * available. Keep capture closed through hardware drain and a short acoustic /
 * capture-buffer tail. The producer and player have independent ownership so
 * an empty synthesis queue cannot unmute a still-draining AudioTrack.
 */
class EchoController(
    private val nowNanos: () -> Long = System::nanoTime,
) {
    private var producing = false
    private var playing = false
    private var outputEndedAt: Long? = null

    /** Producer hold, including gaps between cloud PCM chunks. */
    var ttsPlaying: Boolean
        @Synchronized get() = producing
        @Synchronized set(value) {
            val wasActive = producing || playing
            producing = value
            if (wasActive && !producing && !playing) outputEndedAt = nowNanos()
        }

    /** Actual speaker state, cleared only on drain or an immediate flush. */
    var playbackPlaying: Boolean
        @Synchronized get() = playing
        @Synchronized set(value) {
            val wasActive = producing || playing
            playing = value
            if (wasActive && !producing && !playing) outputEndedAt = nowNanos()
        }

    /**
     * Energy is deliberately not an override. Also use this verdict for cloud
     * PCM and server speech-start events, not only local VAD/STT.
     */
    @Synchronized
    @Suppress("UNUSED_PARAMETER")
    fun shouldFeedVad(frameEnergyRms: Float): Boolean {
        if (producing || playing) return false
        return outputEndedAt?.let { nowNanos() - it >= ECHO_TAIL_MS * 1_000_000 } ?: true
    }

    /** Cloud uplink preserves timing with silence, including a read spanning the tail. */
    @Synchronized
    fun microphonePcm(frame: ByteArray, length: Int, feedBeforeRead: Boolean): ByteArray =
        if (feedBeforeRead && shouldFeedVad(0f)) frame.copyOf(length) else ByteArray(length)

    companion object {
        const val ECHO_TAIL_MS = 400L
    }
}

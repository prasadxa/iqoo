package com.aasra.companion.service

import com.aasra.companion.pipeline.ConfirmationAnswer
import com.aasra.companion.pipeline.classifyConfirmation

internal class ConfirmationGate(
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private data class Pending(val requestedAt: Long, val userResponseVersion: Long)

    private val pending = mutableMapOf<String, Pending>()
    private var userResponseVersion = 0L
    private var lastResponse = ConfirmationAnswer.UNKNOWN

    @Synchronized
    fun recordUserResponse(text: String) {
        userResponseVersion++
        lastResponse = classifyConfirmation(text)
    }

    @Synchronized
    fun confirmed(key: String): Boolean {
        val now = nowMillis()
        pending.entries.removeIf { now - it.value.requestedAt > CONFIRM_WINDOW_MS }
        val previous = pending[key]
        if (previous != null &&
            userResponseVersion > previous.userResponseVersion &&
            lastResponse == ConfirmationAnswer.YES
        ) {
            pending.remove(key)
            lastResponse = ConfirmationAnswer.UNKNOWN
            return true
        }
        pending[key] = Pending(now, userResponseVersion)
        return false
    }

    private companion object {
        const val CONFIRM_WINDOW_MS = 5 * 60 * 1000L
    }
}

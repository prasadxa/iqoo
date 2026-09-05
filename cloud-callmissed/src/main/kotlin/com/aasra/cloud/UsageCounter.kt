package com.aasra.cloud

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Headers

/**
 * Tracks `X-RateLimit-Remaining` (and limit/reset) from CallMissed responses
 * for the Settings cloud-usage counter (PLAN 3: watch remaining; 6.2: counter).
 * Thread-safe; safe to share across all API classes via [CallMissedClient].
 */
class UsageCounter {
    private val _remaining = MutableStateFlow<Int?>(null)
    /** Latest remaining quota, null until the first cloud response. */
    val remaining: StateFlow<Int?> = _remaining.asStateFlow()

    private val _limit = MutableStateFlow<Int?>(null)
    val limit: StateFlow<Int?> = _limit.asStateFlow()

    private val _resetAfterSeconds = MutableStateFlow<Long?>(null)
    val resetAfterSeconds: StateFlow<Long?> = _resetAfterSeconds.asStateFlow()

    /** Feed response headers from any CallMissed REST call. Header names are case-insensitive. */
    fun record(headers: Headers) {
        headerInt(headers, "X-RateLimit-Remaining")?.let { _remaining.value = it }
        headerInt(headers, "X-RateLimit-Limit")?.let { _limit.value = it }
        headerLong(headers, "X-RateLimit-Reset")?.let { _resetAfterSeconds.value = it }
            ?: headerLong(headers, "Retry-After")?.let { _resetAfterSeconds.value = it }
    }

    /** Manual override (e.g. restoring a persisted value at startup). */
    fun recordRemaining(value: Int?) {
        _remaining.value = value
    }

    private fun headerInt(headers: Headers, name: String): Int? =
        headers[name]?.trim()?.toIntOrNull()

    private fun headerLong(headers: Headers, name: String): Long? =
        headers[name]?.trim()?.toLongOrNull()
}

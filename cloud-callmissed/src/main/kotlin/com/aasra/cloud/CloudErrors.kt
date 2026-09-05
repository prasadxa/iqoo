package com.aasra.cloud

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException

private val lenientJson = Json { ignoreUnknownKeys = true }

/** What the caller should do after a CallMissed failure (PLAN 11). */
enum class CloudAction {
    /** Stop, surface a spoken error. Used for 401, 402, 429 quota_exceeded. */
    STOP,

    /** Paid model on free plan: move to the next model in the fallback chain. */
    SWITCH_MODEL,

    /** 429 concurrent-requests: retry once with jitter, then fall through. */
    RETRY_WITH_JITTER,

    /** 503: move to the next model in the fallback chain. */
    FALLBACK,
}

/** Typed CallMissed failure. Never retry when [action] is STOP. */
class CallMissedException(
    val httpCode: Int,
    val code: String?,
    message: String,
    /** Server's Retry-After hint in ms (0 when absent/unparseable). */
    val retryAfterMs: Long = 0L,
) : IOException("CallMissed $httpCode [$code]: $message") {
    val action: CloudAction get() = CloudErrors.actionFor(httpCode, code)
}

/** Error mapping per PLAN 11. */
object CloudErrors {
    const val CODE_QUOTA_EXCEEDED = "quota_exceeded"
    const val CODE_TOO_MANY_CONCURRENT = "too_many_concurrent_requests"
    const val CODE_MODEL_NOT_AVAILABLE = "model_not_available"

    fun actionFor(httpCode: Int, code: String?): CloudAction = when (httpCode) {
        401 -> CloudAction.STOP // bad key
        402 -> CloudAction.STOP // out of credits: stop, never retry
        403 -> CloudAction.SWITCH_MODEL // paid model on free plan
        429 -> if (code == CODE_QUOTA_EXCEEDED) {
            CloudAction.STOP // monthly cap: stop, never retry
        } else {
            CloudAction.RETRY_WITH_JITTER
        }
        503 -> CloudAction.FALLBACK
        else -> CloudAction.STOP
    }

    /** Next model after [current], or null when the chain is exhausted. */
    fun nextFallback(current: String): String? {
        val i = CallMissedConfig.CHAT_FALLBACK_CHAIN.indexOf(current)
        if (i < 0) return CallMissedConfig.CHAT_MODEL_FALLBACK_1
        return CallMissedConfig.CHAT_FALLBACK_CHAIN.getOrNull(i + 1)
    }

    /**
     * Pull the machine-readable error `code` out of an error body.
     * Handles both `{"error":{"code":...}}` and `{"code":...}` shapes.
     */
    fun parseCode(body: String): String? = try {
        val root = lenientJson.parseToJsonElement(body).jsonObject
        val err = root["error"]?.let {
            try { it.jsonObject["code"]?.jsonPrimitive?.content } catch (_: Exception) { null }
        }
        err ?: try { root["code"]?.jsonPrimitive?.content } catch (_: Exception) { null }
    } catch (_: Exception) {
        // Fall back to substring matching on non-JSON bodies.
        when {
            CODE_QUOTA_EXCEEDED in body -> CODE_QUOTA_EXCEEDED
            CODE_TOO_MANY_CONCURRENT in body -> CODE_TOO_MANY_CONCURRENT
            CODE_MODEL_NOT_AVAILABLE in body -> CODE_MODEL_NOT_AVAILABLE
            else -> null
        }
    }

    /** Jittered delay for the single 429-concurrent retry: 400-1200 ms. */
    fun retryDelayMs(): Long = 400L + (Math.random() * 800L).toLong()

    /**
     * Parse the `Retry-After` response header (docs: sent on 429s) to
     * milliseconds, capped at 30 s. Returns 0 when absent or unparseable.
     */
    fun retryAfterMs(headers: okhttp3.Headers): Long {
        val raw = headers["Retry-After"]?.trim().orEmpty()
        val seconds = raw.toLongOrNull() ?: return 0L
        if (seconds <= 0) return 0L
        return minOf(seconds, 30L) * 1000L
    }
}

package com.aasra.pipeline

import android.os.SystemClock

/** Connectivity as seen by the router. Evaluated per turn, not cached. */
enum class NetworkState {
    AVAILABLE,
    UNAVAILABLE,
}

/** Memory snapshot at decision time (PLAN 4.5 tier fallback: < 5 GB free). */
data class RamState(
    val freeGb: Float,
    val totalGb: Float = 12f,
)

/** Why a turn left the device. Mirrors the escalate(reason) enum. */
enum class EscalationReason {
    MEDICAL,
    CURRENT_INFO,
    LONG_COMPLEX,
    LOW_CONFIDENCE,
    OTHER,
}

/**
 * Where a turn goes. The orchestrator / cloud layer maps these 1:1 to PLAN 5:
 * Relisten -> 5.2 saaras:v4, CloudLlm -> 5.1 chat, WebSearchThenCloud -> 5.5+5.1,
 * CloudMode -> 5.4 managed voice agent.
 */
sealed class Route {
    /** Fully offline loop (also the no-network answer, PLAN 5.6 row 1). */
    data object Local : Route()

    /** PLAN 5.2: re-listen the ring-buffer audio with saaras:v4. */
    data class CloudSttRelisten(val reason: String) : Route()

    /** PLAN 5.1: stream the turn through the cloud LLM, speak via Kokoro. */
    data class CloudLlm(val reason: EscalationReason) : Route()

    /** PLAN 5.5 then 5.1: search first, feed results to the cloud LLM. */
    data class WebSearchThenCloud(val query: String) : Route()

    /** PLAN 5.4: local brain dead — whole conversation over the voice agent WS. */
    data object CloudMode : Route()
}

/** A decision plus its timing for the demo dashboard (PLAN 4.7 / 5.6). */
data class RouteDecision(
    val route: Route,
    /** Router compute time. */
    val latencyMs: Long,
    /** One-line human-readable log, e.g. "route=CloudLlm(medical) conf=0.91 net=up". */
    val logLine: String,
)

/**
 * Router (PLAN 5.6). Pure function of (transcript, confidence, network,
 * ramState) plus the local-LLM escalate signal and model health. Rule order
 * follows the PLAN table top-to-bottom; first match wins.
 *
 * [logger] receives every [RouteDecision.logLine] — app/ forwards these to the
 * demo dashboard with the M1 vad_end->first_audio timings.
 */
object Router {

    /** Below this STT confidence we re-listen (PLAN 5.2 trigger). */
    const val LOW_CONFIDENCE_THRESHOLD = 0.55f

    /** Above this the turn is "long" (PLAN 5.6 row 5). */
    const val LONG_TRANSCRIPT_WORDS = 60

    /** Medical triggers force the safety-first cloud prompt (PLAN 5.6 row 3). */
    val MEDICAL_KEYWORDS: Set<String> = setOf(
        // en
        "medicine", "dose", "dosage", "doctor", "symptom", "pain", "bp",
        "sugar", "diabetes", "tablet", "paracetamol", "pill", "prescription",
        "hospital", "fever", "cough", "treatment",
        // hi / transliterated
        "dawai", "dawa", "dard", "doctor", "bukhar", "khansi", "ilaj",
        " BP ", "sugar",
    )

    /** "Needs current info" triggers (PLAN 5.6 row 4). Checked case-insensitively. */
    val CURRENT_INFO_MARKERS: Set<String> = setOf(
        "news", "weather", "price", "rate today", "kab hai", "kab hoga",
        "mausam", "khabar", "score", "election", "train", "flight",
    )

    /** Multi-step instruction markers (second half of PLAN 5.6 row 5). */
    private val MULTI_STEP_MARKERS: Set<String> = setOf(
        "first", "then", "after that", "finally", "step 1",
        "pehle", "phir", "uske baad", "aur phir",
    )

    /** Languages the local STT covers (PLAN 4.4). Anything else re-listens. */
    val LOCAL_LANGUAGES: Set<String> = setOf("en", "hi")

    fun decide(
        transcript: String,
        confidence: Float,
        network: NetworkState,
        ramState: RamState,
        language: String = "en",
        localEscalation: EscalationReason? = null,
        modelHealthy: Boolean = true,
        logger: ((String) -> Unit)? = null,
    ): RouteDecision {
        val t0 = SystemClock.elapsedRealtime()
        val text = transcript.trim()
        val lower = " $text ".lowercase()
        val wordCount = if (text.isEmpty()) 0 else text.split("\\s+".toRegex()).size

        // Row 7: local brain dead / OOM — whole conversation to cloud mode.
        // Checked first: nothing else is answerable without a model.
        val route: Route = if (!modelHealthy) {
            if (network == NetworkState.AVAILABLE) Route.CloudMode else Route.Local
        } else if (network == NetworkState.UNAVAILABLE) {
            // Row 1: no network — stay local; the LLM says cloud is unavailable
            // if the request needs it.
            Route.Local
        } else if (confidence < LOW_CONFIDENCE_THRESHOLD ||
            language.lowercase() !in LOCAL_LANGUAGES
        ) {
            // Row 2: low confidence or unsupported language -> saaras:v4 re-listen.
            Route.CloudSttRelisten(
                "conf=%.2f lang=%s".format(confidence, language),
            )
        } else if (MEDICAL_KEYWORDS.any { lower.contains(it) }) {
            // Row 3: medical — cloud LLM with the safety-first prompt.
            Route.CloudLlm(EscalationReason.MEDICAL)
        } else if (CURRENT_INFO_MARKERS.any { lower.contains(it) }) {
            // Row 4: needs current info -> web_search then cloud LLM.
            Route.WebSearchThenCloud(text)
        } else if (wordCount > LONG_TRANSCRIPT_WORDS || isMultiStep(lower)) {
            // Row 5: long or multi-step instruction.
            Route.CloudLlm(EscalationReason.LONG_COMPLEX)
        } else if (localEscalation != null) {
            // Row 6: the local model itself asked to escalate.
            Route.CloudLlm(localEscalation)
        } else {
            Route.Local
        }

        val latencyMs = SystemClock.elapsedRealtime() - t0
        val decision = RouteDecision(
            route = route,
            latencyMs = latencyMs,
            logLine = "route=%s words=%d conf=%.2f net=%s freeRam=%.1fGB lang=%s decideMs=%d".format(
                routeLabel(route), wordCount, confidence, network, ramState.freeGb, language, latencyMs,
            ),
        )
        try {
            logger?.invoke(decision.logLine)
        } catch (_: Exception) {
        }
        return decision
    }

    private fun isMultiStep(lowerPadded: String): Boolean {
        var hits = 0
        for (m in MULTI_STEP_MARKERS) {
            if (lowerPadded.contains(m)) {
                hits++
                if (hits >= 2) return true
            }
        }
        return false
    }

    private fun routeLabel(route: Route): String = when (route) {
        is Route.Local -> "Local"
        is Route.CloudSttRelisten -> "Relisten"
        is Route.CloudLlm -> "CloudLlm(${route.reason})"
        is Route.WebSearchThenCloud -> "WebSearch"
        is Route.CloudMode -> "CloudMode"
    }
}

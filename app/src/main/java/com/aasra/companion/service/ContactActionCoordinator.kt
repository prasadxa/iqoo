package com.aasra.companion.service

import com.aasra.companion.pipeline.ConfirmationAnswer
import com.aasra.companion.pipeline.classifyConfirmation
import com.aasra.data.Contact
import com.aasra.tools.ContactCandidate
import com.aasra.tools.ContactTools
import com.aasra.tools.ToolResult
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.concurrent.atomic.AtomicLong

/** One shared selection/confirmation flow for local, hybrid and managed voice. */
internal class ContactActionCoordinator(
    private val lookup: suspend (String) -> List<ContactCandidate>,
    private val execute: suspend (String, Contact, String) -> ToolResult,
    private val language: () -> String = { "en" },
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    data class Request(val kind: String, val name: String, val message: String = "")
    data class Reply(val spoken: String, val pending: Boolean, val ok: Boolean = false)
    private data class Pending(
        val request: Request, val choices: List<ContactCandidate>, val selected: ContactCandidate?,
        val responseVersion: Long, val startedAt: Long,
    )
    private val mutex = Mutex()
    private val generation = AtomicLong()
    @Volatile private var pending: Pending? = null
    @Volatile private var response = 0L to ""
    val hasPending: Boolean get() = pending != null

    @Synchronized fun recordUserResponse(text: String) { response = (response.first + 1) to text }
    fun cancel() { generation.incrementAndGet(); pending = null }

    suspend fun respond(text: String): Reply? {
        val action = pending?.request ?: return null
        recordUserResponse(text)
        return request(action)
    }

    suspend fun request(request: Request): Reply = mutex.withLock {
        val token = generation.get()
        val previous = pending
        if (previous != null && previous.request.kind == request.kind && previous.request.message == request.message &&
            ContactTools.normalize(previous.request.name) == ContactTools.normalize(request.name)) {
            if (now() - previous.startedAt > 120_000) {
                pending = null
                return@withLock Reply(line("That request expired. Please ask again.", "उस अनुरोध का समय समाप्त हो गया। फिर कहें।"), false)
            }
            val (version, text) = response
            if (version <= previous.responseVersion) return@withLock prompt(previous)
            if (classifyConfirmation(text) == ConfirmationAnswer.NO) {
                pending = null
                return@withLock Reply(line("Cancelled. Nothing was sent or called.", "रद्द किया। कॉल या संदेश नहीं भेजा।"), false)
            }
            if (previous.selected == null) {
                val selected = select(text, previous.choices)
                val next = previous.copy(selected = selected, responseVersion = version)
                if (token != generation.get()) return@withLock Reply("", false)
                pending = next
                return@withLock prompt(next)
            }
            if (classifyConfirmation(text) != ConfirmationAnswer.YES) {
                val next = previous.copy(responseVersion = version)
                pending = next
                return@withLock prompt(next)
            }
            // Consume approval before the side effect: duplicate model/tool events
            // cannot replay it. The exact resolved number/message is frozen.
            pending = null
            if (token != generation.get()) return@withLock Reply("", false)
            currentCoroutineContext().ensureActive()
            val result = execute(request.kind, previous.selected.contact, request.message)
            return@withLock Reply(result.spokenReply, false, result.ok)
        }
        pending = null
        if (request.name.isBlank() || request.kind !in setOf("call", "sms")) {
            return@withLock Reply(line("Whom should I contact?", "किससे संपर्क करूँ?"), false)
        }
        if (request.kind == "sms" && request.message.isBlank()) {
            return@withLock Reply(line("What message should I send?", "क्या संदेश भेजूँ?"), false)
        }
        val choices = lookup(request.name)
        currentCoroutineContext().ensureActive()
        if (token != generation.get()) return@withLock Reply("", false)
        if (choices.isEmpty()) return@withLock Reply(line(
            "I could not find ${request.name}. Allow phone contacts in Settings, or check the saved name.",
            "${request.name} नहीं मिले। सेटिंग्स में फ़ोन के संपर्कों की अनुमति दें या नाम जाँचें।",
        ), false)
        if (choices.size > 5) return@withLock Reply(line(
            "Several contacts match. Please say the full name so I can narrow it down.",
            "इस नाम के कई संपर्क हैं। कृपया पूरा नाम बताएँ।",
        ), false)
        val next = Pending(request, choices, choices.singleOrNull(), response.first, now())
        pending = next
        prompt(next)
    }

    private fun prompt(value: Pending): Reply {
        val selected = value.selected
        val text = if (selected == null) {
            val options = value.choices.mapIndexed { index, candidate -> "${index + 1}. ${candidate.description}" }.joinToString(". ")
            line("Which number? $options. Say the option number, mobile, home or work.",
                "कौन सा नंबर चुनें? $options. विकल्प का नंबर, मोबाइल, घर या ऑफिस कहें।")
        } else if (value.request.kind == "call") {
            line("Should I call ${selected.description}? Say yes or cancel.",
                "क्या ${selected.description} पर कॉल करूँ? हाँ या रद्द कहें।")
        } else {
            line("Send to ${selected.description}: ${value.request.message}? Say yes or cancel.",
                "${selected.description} को यह संदेश भेजूँ: ${value.request.message}? हाँ या रद्द कहें।")
        }
        return Reply(text, true)
    }

    private fun line(english: String, hindi: String) = if (language() == "hi") hindi else english

    companion object {
        /** A selection is never an approval. Ambiguous labels/suffixes still need clarification. */
        internal fun select(text: String, choices: List<ContactCandidate>): ContactCandidate? {
            val normalized = ContactTools.normalize(text)
            val suffixes = Regex("[0-9]{4,15}").findAll(normalized).map { it.value }.distinct().toList()
            if (suffixes.isNotEmpty()) return if (suffixes.size == 1) {
                choices.filter { it.contact.phone.endsWith(suffixes.single()) }.singleOrNull()
            } else null
            val ordinal = mapOf(
                "1" to 0, "one" to 0, "first" to 0, "पहला" to 0, "पहले" to 0, "एक" to 0, "१" to 0,
                "2" to 1, "two" to 1, "second" to 1, "दूसरा" to 1, "दूसरे" to 1, "दो" to 1, "२" to 1,
                "3" to 2, "three" to 2, "third" to 2, "तीसरा" to 2, "तीन" to 2, "३" to 2,
                "4" to 3, "four" to 3, "fourth" to 3, "चौथा" to 3, "चार" to 3, "४" to 3,
                "5" to 4, "five" to 4, "fifth" to 4, "पाँच" to 4, "पांच" to 4, "५" to 4,
            )
            val indices = normalized.split(' ').mapNotNull { ordinal[it] }.distinct()
            if (indices.size == 1) return choices.getOrNull(indices.single())
            if (indices.size > 1) return null
            val label = when (normalized) {
                "mobile", "mobile number", "मोबाइल" -> "mobile"
                "home", "home number", "घर" -> "home"
                "work", "work number", "office", "ऑफिस", "दफ्तर" -> "work"
                else -> normalized
            }
            return choices.filter {
                ContactTools.normalize(it.numberLabel) == label || ContactTools.normalize(it.contact.name) == normalized ||
                    (normalized.matches(Regex("[0-9]{4,15}")) && it.contact.phone.endsWith(normalized))
            }.singleOrNull()
        }
    }
}

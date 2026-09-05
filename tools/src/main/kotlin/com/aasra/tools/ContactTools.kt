package com.aasra.tools

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Phone
import androidx.core.content.ContextCompat
import com.aasra.data.Contact
import com.aasra.data.ContactDao
import com.aasra.data.normalizePhoneNumber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.min
import java.util.Locale
import java.text.Normalizer
import android.icu.text.Transliterator

data class ContactCandidate(val contact: Contact, val numberLabel: String = "saved number") {
    val description: String get() = "${contact.name}, $numberLabel, ending ${contact.phone.takeLast(4)}"
}

/**
 * call_contact: match Room and permission-gated phone contacts, voice confirm,
 * then ACTION_CALL with CALL_PHONE, ACTION_DIAL fallback.
 *
 * Matching order: exact name -> exact nickname -> kinship alias ("beta" finds
 * the contact nicknamed "son", and vice versa) -> substring -> token
 * bounded Levenshtein matching. Hindi marks are preserved; Android ICU provides
 * a low-ranked transliteration fallback. Ambiguous numbers never resolve to one.
 *
 * Voice confirm: the pipeline speaks "Should I call <name>?" and listens;
 * pass the answer in as [confirm]. A missing confirmation returns a prompt,
 * never a call. Already-confirmed coordinators use [placeCall] with the frozen number.
 */
class ContactTools(
    private val context: Context,
    private val contacts: ContactDao,
    private val onNeedsCallPermission: () -> Unit = {},
) {

    /** Two-step flow for the pipeline: resolve, speak confirm, then [placeCall]. */
    suspend fun resolve(spokenName: String): Contact? = findCandidates(spokenName).singleOrNull()?.contact

    fun hasPhoneContactsPermission(): Boolean = ContextCompat.checkSelfPermission(
        context, Manifest.permission.READ_CONTACTS,
    ) == PackageManager.PERMISSION_GRANTED

    /** Read on demand. Never imports the address book or uploads it anywhere. */
    suspend fun findCandidates(spokenName: String): List<ContactCandidate> = withContext(Dispatchers.IO) {
        val spoken = normalize(spokenName).replace(Regex("^(my|mera|meri|mere|मेरा|मेरी|मेरे)\\s+"), "")
        val labelSuffix = Regex("\\s+(mobile|work|home|office|मोबाइल|ऑफिस|घर)(?: (?:number|नंबर))?$").find(spoken)
        val requestedLabel = when (labelSuffix?.groupValues?.get(1)) {
            "mobile", "मोबाइल" -> "mobile"
            "work", "office", "ऑफिस" -> "work"
            "home", "घर" -> "home"
            else -> null
        }
        val norm = if (labelSuffix != null) spoken.substring(0, labelSuffix.range.first).trim() else spoken
        if (norm.isEmpty()) return@withContext emptyList()
        val candidates = contacts.all().mapNotNull { contact ->
            normalizePhoneNumber(contact.phone)?.let { ContactCandidate(contact.copy(phone = it)) }
        }.filter { score(norm, it.contact) <= MAX_SCORE }.distinctBy { it.contact.phone }.toMutableList()
        val localBest = candidates.minOfOrNull { score(norm, it.contact) }
        if (localBest != null) candidates.removeAll { score(norm, it.contact) != localBest }
        if (hasPhoneContactsPermission()) {
            try {
                context.contentResolver.query(Phone.CONTENT_URI, arrayOf(
                    Phone._ID, Phone.DISPLAY_NAME_PRIMARY, Phone.NUMBER, Phone.NORMALIZED_NUMBER, Phone.TYPE, Phone.LABEL,
                ), null, null, null)?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val name = cursor.getString(1).orEmpty().trim()
                        val raw = cursor.getString(3)?.takeIf { it.isNotBlank() } ?: cursor.getString(2).orEmpty()
                        val number = normalizePhoneNumber(raw.replace(Regex("[().]"), "")) ?: continue
                        val contact = Contact(id = -cursor.getLong(0), name = name, phone = number)
                        val rank = score(norm, contact)
                        val best = candidates.minOfOrNull { score(norm, it.contact) } ?: Int.MAX_VALUE
                        if (rank > MAX_SCORE || rank > best) continue
                        if (rank < best) candidates.clear()
                        val label = when (cursor.getInt(4)) {
                            Phone.TYPE_MOBILE -> "mobile"
                            Phone.TYPE_HOME -> "home"
                            Phone.TYPE_WORK, Phone.TYPE_WORK_MOBILE -> "work"
                            Phone.TYPE_CUSTOM -> cursor.getString(5)?.take(40)?.ifBlank { null } ?: "other"
                            else -> "other"
                        }
                        val duplicate = candidates.indexOfFirst { it.contact.phone == number }
                        if (duplicate >= 0 && candidates[duplicate].numberLabel == "saved number") {
                            candidates[duplicate] = candidates[duplicate].copy(numberLabel = label)
                        } else if (duplicate < 0 && candidates.size < 100) {
                            candidates += ContactCandidate(contact, label)
                        }
                    }
                }
            } catch (_: SecurityException) {
                // Access may be revoked between the permission check and the query.
            }
        }
        val best = candidates.minOfOrNull { score(norm, it.contact) } ?: return@withContext emptyList()
        candidates.filter { score(norm, it.contact) == best && (requestedLabel == null || normalize(it.numberLabel) == requestedLabel) }
            .distinctBy { it.contact.phone }
            .sortedWith(compareBy({ normalize(it.contact.name) }, { it.numberLabel }, { it.contact.phone }))
    }

    /**
     * Full flow: resolve + optional voice confirm + dial.
     * Returns ToolResult with data="needs-confirmation" when the pipeline
     * should ask first and re-invoke with confirmed=true.
     */
    suspend fun callContact(
        spokenName: String,
        confirm: (suspend (Contact) -> Boolean)? = null,
    ): ToolResult {
        val candidates = findCandidates(spokenName)
        if (candidates.size > 1) return ToolResult(false,
            "I found several numbers for $spokenName. Please choose a person and number before calling.", "needs-selection")
        val contact = candidates.singleOrNull()?.contact
            ?: return ToolResult(
                false,
                "Sorry, I could not find $spokenName in the contacts. Please check the name and try again.",
            )
        if (confirm == null) return ToolResult(false, "Should I call ${contact.name}?", "needs-confirmation")
        if (!confirm(contact)) {
            return ToolResult(false, "Okay, I did not call ${contact.name}.", data = "cancelled")
        }
        return placeCall(contact)
    }

    /** Dial [contact]. CALL_PHONE granted -> ACTION_CALL, else ACTION_DIAL. */
    fun placeCall(contact: Contact): ToolResult {
        val phone = normalizePhoneNumber(contact.phone) ?: return ToolResult(
            false,
            "${contact.name} does not have a valid phone number. Please fix it in Settings before calling.",
            data = "invalid-phone",
        )
        val hasDirect = ContextCompat.checkSelfPermission(
            context, Manifest.permission.CALL_PHONE,
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasDirect) onNeedsCallPermission()
        val action = if (hasDirect) Intent.ACTION_CALL else Intent.ACTION_DIAL
        return try {
            val intent = Intent(action, Uri.parse("tel:$phone")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            val how = if (hasDirect) "Calling" else "Opening the dialer for"
            ToolResult(true, "$how ${contact.name} now.", if (hasDirect) "call-requested" else "dialer-opened")
        } catch (e: SecurityException) {
            ToolResult(false, "I could not place the call. Please tap the call button to try again.")
        } catch (e: Exception) {
            ToolResult(false, "I could not open the phone app. Please try again.")
        }
    }

    companion object {
        private const val MAX_SCORE = 4
        private val nameTransliterator by lazy { Transliterator.getInstance("Any-Latin; Latin-ASCII") }

        @Synchronized
        private fun romanName(name: String): String = runCatching {
            normalize(nameTransliterator.transliterate(name)).replace("ee", "i").replace("oo", "u").replace("aa", "a")
        }.getOrDefault(name)

        /**
         * Family words mapped to their counterpart, both directions, so
         * "call beta" finds nickname "son" and "call bahu" finds "bahu".
         * Extend per household in onboarding; matching stays offline.
         */
        val KINDRED_ALIASES: Map<String, List<String>> = mapOf(
            "beta" to listOf("son", "बेटा", "बेटे"),
            "son" to listOf("beta"),
            "bahu" to listOf("daughter-in-law", "daughter in law", "bahu", "बहू"),
            "papa" to listOf("father", "dad", "papa", "पापा", "पिताजी"),
            "mummy" to listOf("mother", "mom", "mum", "mummy", "मम्मी", "माँ", "मां"),
            "dada" to listOf("grandfather", "dada"),
            "dadi" to listOf("grandmother", "dadi"),
            "nana" to listOf("grandfather", "nana"),
            "nani" to listOf("grandmother", "nani"),
            "bhai" to listOf("brother", "bhai", "भाई"),
            "behen" to listOf("sister", "behen", "didi", "बहन", "दीदी"),
            "didi" to listOf("sister", "didi"),
            "doctor" to listOf("doctor", "daktar"),
        )

        fun normalize(raw: String): String =
            Normalizer.normalize(raw, Normalizer.Form.NFC).lowercase(Locale.ROOT)
                .replace(Regex("[^\\p{L}\\p{M}\\p{N} ]"), " ").split(" ")
                .filter { it.isNotBlank() }.joinToString(" ").trim()

        /** 0 exact … 4 fuzzy; higher = no match. */
        fun score(spoken: String, contact: Contact): Int {
            val name = normalize(contact.name)
            if (spoken == name) return 0
            val nick = contact.nickname?.let(::normalize).orEmpty()
            if (nick.isNotEmpty() && spoken == nick) return 1
            val aliases = KINDRED_ALIASES[spoken].orEmpty() + KINDRED_ALIASES.entries
                .filter { spoken in it.value }.flatMap { listOf(it.key) + it.value }
            if (aliases.any { it == name || it == nick }) return 2
            if (name.contains(spoken) || nick.contains(spoken)) return 3
            val distance = if (spoken.length >= 6) 2 else if (spoken.length >= 3) 1 else 0
            val tokenHit = (name.split(" ") + nick.split(" "))
                .filter { it.isNotEmpty() }
                .any { levenshtein(spoken, it) <= distance }
            if (tokenHit) return 4
            // Hindi recognition may produce Devanagari for an English address-book
            // entry. Transliteration is only a low-ranked candidate, never approval.
            val crossScript = spoken.any { it in '\u0900'..'\u097f' } != name.any { it in '\u0900'..'\u097f' }
            if (crossScript) {
                val romanSpoken = romanName(spoken)
                val romanContact = romanName(name)
                if (romanSpoken == romanContact || romanContact.split(' ').any {
                    romanSpoken.length >= 3 && levenshtein(romanSpoken, it) <= if (romanSpoken.length >= 6) 2 else 1
                }) return 4
            }
            return Int.MAX_VALUE
        }

        fun levenshtein(a: String, b: String): Int {
            if (a == b) return 0
            var prev = IntArray(b.length + 1) { it }
            for (i in a.indices) {
                val cur = IntArray(b.length + 1) { it }
                cur[0] = i + 1
                for (j in b.indices) {
                    cur[j + 1] = min(min(cur[j] + 1, prev[j + 1] + 1), prev[j] + if (a[i] == b[j]) 0 else 1)
                }
                prev = cur
            }
            return prev[b.length]
        }
    }
}

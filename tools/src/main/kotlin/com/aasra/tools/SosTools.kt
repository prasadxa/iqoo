package com.aasra.tools

import android.content.Context
import android.os.SystemClock
import com.aasra.data.CaregiverStatsStore
import com.aasra.data.ContactDao
import com.aasra.data.normalizePhoneNumber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext

/**
 * sos (PLAN 6.1): call the primary emergency contact and SMS the user's
 * location to ALL emergency contacts. Also bound to the big red button.
 *
 * Order matters: the phone call goes out first (best effort, never blocks),
 * then location SMS to everyone — even if the call fails or the number is
 * busy, the texts still go out. Last-SOS time is recorded for the caregiver
 * view after any send is attempted.
 *
 * Location is injected ([locationText], provided by app/ from FusedLocation
 * or a cached fix) so tools/ takes no Play-services dependency. Null means
 * "location unavailable" and the SMS says so plainly.
 *
 * 3x power-button hook (note): Android delivers power-button presses to
 * foreground apps only via an AccessibilityService (or screen on/off
 * broadcasts in app/). That listener lives in app/ and feeds presses into
 * [SosTrigger]; when it fires, app/ calls [sos]. This module owns counting
 * + execution so the logic is unit-testable with zero Android framework.
 */
class SosTools(
    private val context: Context,
    private val contacts: ContactDao,
    private val sms: SmsTools,
    private val statsStore: CaregiverStatsStore,
    private val call: ContactTools? = null,
    private val locationText: suspend () -> String? = { null },
) {

    suspend fun sos(): ToolResult = withContext(Dispatchers.IO) {
        val saved = contacts.emergency()
        if (saved.isEmpty()) {
            return@withContext ToolResult(
                false,
                "No emergency contact is saved yet. Please add one in Settings first, so I can help in an emergency.",
            )
        }
        // Legacy rows can contain names in the phone field. Never dial those,
        // and let the next valid emergency contact take over from a bad primary.
        val emergency = saved.mapNotNull { contact ->
            normalizePhoneNumber(contact.phone)?.let { contact.copy(phone = it) }
        }
        if (emergency.isEmpty()) {
            return@withContext ToolResult(
                false,
                "No emergency contact has a valid phone number. Please fix the emergency contacts in Settings.",
                data = "invalid-phone",
            )
        }
        val primary = emergency.first()

        supervisorScope {
            // Call first (best effort), texts in parallel — one failing leg
            // must never cancel the others.
            val callJob = async {
                try {
                    (call ?: ContactTools(context, contacts)).placeCall(primary)
                } catch (e: Exception) {
                    null
                }
            }
            val location = try {
                locationText()
            } catch (e: Exception) {
                null
            }
            val body = if (location != null) {
                "SOS from Aasra: I need help. My location: $location"
            } else {
                "SOS from Aasra: I need help. My location is not available right now. Please call me back."
            }
            val textJobs = emergency.map { c ->
                async {
                    try {
                        sms.sendDirect(c.phone, body)
                    } catch (e: Exception) {
                        false
                    }
                }
            }
            val callResult = callJob.await()
            val sent = textJobs.count { it.await() }
            try {
                statsStore.recordSosNow()
            } catch (e: Exception) {
                // Stats must never fail an SOS.
            }
            if (callResult?.ok == true && sent > 0) {
                ToolResult(
                    true,
                    "${callResult.spokenReply.removeSuffix(".")} and submitted an emergency message for $sent contacts.",
                )
            } else if (callResult?.ok == true) {
                ToolResult(
                    true,
                    "${callResult.spokenReply} The emergency messages could not go out.",
                )
            } else if (sent > 0) {
                ToolResult(
                    true,
                    "I could not place the call, but I submitted an emergency message for $sent contacts.",
                )
            } else {
                ToolResult(
                    false,
                    "I could not contact ${primary.name} or send the emergency messages. Please check the signal and press SOS again.",
                )
            }
        }
    }
}

/**
 * Counts power-button presses inside a time window; fires once per [requiredPresses].
 * Pure logic — pass [SystemClock.elapsedRealtime] in production, fake clocks in tests.
 */
class SosTrigger(
    private val windowMs: Long = 3_000,
    private val requiredPresses: Int = 3,
    private val onTrigger: () -> Unit,
) {
    private var count = 0
    private var firstAt = 0L

    @Synchronized
    fun powerPressed(nowMs: Long = SystemClock.elapsedRealtime()): Boolean {
        if (firstAt == 0L || nowMs - firstAt > windowMs) {
            count = 0
            firstAt = nowMs
        }
        count++
        return if (count >= requiredPresses) {
            count = 0
            firstAt = 0L
            onTrigger()
            true
        } else {
            false
        }
    }
}

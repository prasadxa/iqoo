package com.aasra.tools

import android.content.Context
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import android.os.Build
import android.telephony.SmsManager
import com.aasra.data.Contact
import com.aasra.data.normalizePhoneNumber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * send_sms (PLAN 6.1): SmsManager with confirm-before-send.
 *
 * Normal flow is two turns: first call returns data="needs-confirmation"
 * with a spoken "Should I send this to X: <message>?" prompt; the pipeline
 * re-invokes with confirmed=true only on a yes. The SOS path bypasses this
 * via [sendDirect] — an SOS text is never gated on a second confirmation.
 */
class SmsTools(private val context: Context) {
    fun hasPermission(): Boolean = ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED

    suspend fun sendSms(to: Contact, message: String, confirmed: Boolean): ToolResult =
        withContext(Dispatchers.IO) {
            if (normalizePhoneNumber(to.phone) == null) {
                return@withContext ToolResult(
                    false,
                    "${to.name} does not have a valid phone number. Please fix it in Settings before sending a message.",
                    data = "invalid-phone",
                )
            }
            if (message.isBlank()) {
                return@withContext ToolResult(
                    false,
                    "What message should I send to ${to.name}?",
                    data = "needs-message",
                )
            }
            if (!confirmed) {
                return@withContext ToolResult(
                    false,
                    "Should I send this to ${to.name}: $message?",
                    data = "needs-confirmation",
                )
            }
            if (!hasPermission()) return@withContext ToolResult(false,
                "SMS permission is off. Allow messages in Settings, then ask again.", "needs-sms-permission")
            val ok = sendDirect(to.phone, message)
            if (ok) ToolResult(true, "Message submitted to your phone for ${to.name}.", "sms-submitted")
            else ToolResult(false, "I could not send the message. Please check the signal and try again.")
        }

    /**
     * Unconditional send. Only the SOS path and the confirmed second turn
     * may call this. Returns false (never throws) on denied permission,
     * bad number, or no radio — callers translate that into a spoken line.
     */
    suspend fun sendDirect(phone: String, message: String): Boolean =
        withContext(Dispatchers.IO) {
            val dest = normalizePhoneNumber(phone) ?: return@withContext false
            if (!hasPermission()) return@withContext false
            if (message.isEmpty()) return@withContext false
            try {
                val sm: SmsManager = if (Build.VERSION.SDK_INT >= 31) {
                    context.getSystemService(SmsManager::class.java) ?: return@withContext false
                } else {
                    @Suppress("DEPRECATION")
                    SmsManager.getDefault()
                }
                // Null PendingIntents: fire-and-forget. Delivery receipts would
                // need manifest receivers in app/; out of scope for Track E.
                val parts = sm.divideMessage(message)
                if (parts.size <= 1) sm.sendTextMessage(dest, null, message, null, null)
                else sm.sendMultipartTextMessage(dest, null, parts, null, null)
                true
            } catch (e: SecurityException) {
                false // SEND_SMS denied at runtime.
            } catch (e: IllegalArgumentException) {
                false // Malformed destination address.
            } catch (e: Exception) {
                false // No radio / internal error.
            }
        }
}

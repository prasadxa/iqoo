package com.aasra.tools

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat

/**
 * read_notifications (PLAN 6.1): NotificationListenerService that keeps the
 * last few notifications and reads the newest 3 aloud.
 *
 * ONBOARDING NOTE — there is no runtime grant for this. The user must be
 * taken to Settings once: check [NotificationAccess.granted], and if false,
 * fire [NotificationAccess.settingsIntent]. app/ onboarding explains this in
 * plain words ("to read your messages aloud, turn on Aasra on the next
 * screen") and re-checks on return. Nothing else in Track E can substitute.
 *
 * Android 12–14 quirks:
 * - The service component must stay enabled; after a reboot / force-stop the
 *   listener silently disconnects until the system rebinds it — [connected]
 *   exposes the bind state so the pipeline can say "I cannot see your
 *   messages right now" instead of reading stale ones.
 * - Apps targeting 33+ must not assume extras survive; we copy title/text
 *   strings at post time into [HeardNotification].
 * - Notification content the user marked sensitive arrives redacted; we read
 *   what the system gives us and never request hidden content.
 */
data class HeardNotification(
    val appLabel: String,
    val title: String?,
    val text: String?,
    val postedAtMillis: Long,
)

class AasraNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        connected = true
    }

    override fun onListenerDisconnected() {
        connected = false
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName in IGNORED_PACKAGES || sbn.packageName == packageName) return
        val extras = sbn.notification.extras
        val title = extras.getCharSequence("android.title")?.toString()
        val text = extras.getCharSequence("android.text")?.toString()
        if (title.isNullOrBlank() && text.isNullOrBlank()) return
        val app = try {
            packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(sbn.packageName, 0),
            ).toString()
        } catch (e: Exception) {
            sbn.packageName
        }
        synchronized(lock) {
            recent.addLast(HeardNotification(app, title?.take(120), text?.take(200), sbn.postTime))
            while (recent.size > MAX_KEPT) recent.removeFirst()
        }
    }

    companion object {
        private const val MAX_KEPT = 10
        private val lock = Any()
        private val recent = ArrayDeque<HeardNotification>()

        @Volatile
        var connected: Boolean = false
            private set

        private val IGNORED_PACKAGES = setOf("android", "com.android.systemui")

        fun last(n: Int): List<HeardNotification> = synchronized(lock) {
            recent.takeLast(n.coerceIn(1, MAX_KEPT)).reversed()
        }

        /**
         * Spoken form of the last [limit] notifications. Empty string when
         * there is nothing (recent) to read — the pipeline then says so.
         */
        fun aloudText(limit: Int = 3, lang: String = "en"): String {
            val items = last(limit.coerceIn(1, 5)).filter { !it.title.isNullOrBlank() || !it.text.isNullOrBlank() }
            if (items.isEmpty()) return ""
            return items.mapIndexed { i, h ->
                val what = listOfNotNull(h.title?.trim(), h.text?.trim()).joinToString(", ").ifBlank { "no text" }
                if (lang.startsWith("hi")) "${i + 1}. ${h.appLabel} se: $what."
                else "${i + 1}. From ${h.appLabel}: $what."
            }.joinToString(" ")
        }
    }
}

/** Settings-route helpers: the ONLY way to obtain notification access. */
object NotificationAccess {

    fun granted(context: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context.applicationContext)
            .contains(context.applicationContext.packageName)

    fun settingsIntent(): Intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

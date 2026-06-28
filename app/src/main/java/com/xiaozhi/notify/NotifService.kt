package com.xiaozhi.notify

import android.app.Notification
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** Reads phone notifications and forwards each to the clock via [Net]. */
class NotifService : NotificationListenerService() {

    private lateinit var prefs: Prefs
    private var lastKey: String = ""
    private var lastTime: Long = 0

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
    }

    override fun onListenerConnected() {
        // Warm up the cached address as soon as the listener binds.
        Net.discoverAsync(this) {}
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!prefs.enabled) return
        if (sbn.packageName == packageName) return

        // Per-app filter: only forward apps the user explicitly selected.
        // Empty selection = forward nothing (must pick apps first).
        if (sbn.packageName !in prefs.allowedApps) return

        val n = sbn.notification ?: return
        val flags = n.flags
        if (flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (!prefs.includeOngoing && flags and Notification.FLAG_ONGOING_EVENT != 0) return

        val (title, text) = extractContent(n)
        if (title.isBlank() && text.isBlank()) return

        // Drop duplicates the system re-posts within a short window.
        val key = "${sbn.packageName}|$title|$text"
        val now = System.currentTimeMillis()
        if (key == lastKey && now - lastTime < 4000) return
        lastKey = key
        lastTime = now

        Net.send(this, appLabel(sbn.packageName), title, text)
    }

    private fun appLabel(pkg: String): String = try {
        val pm = packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) {
        pkg
    }

    private data class Content(val title: String, val text: String)

    private fun extractContent(n: Notification): Content {
        val extras = n.extras
        fun cs(key: String): String = extras.getCharSequence(key)?.toString()?.trim().orEmpty()

        val title = firstNonBlank(
            cs(Notification.EXTRA_TITLE),
            cs(EXTRA_CONVERSATION_TITLE_KEY)
        )
        val text = firstNonBlank(
            cs(Notification.EXTRA_TEXT),
            latestMessageText(n),
            textLines(n),
            cs(Notification.EXTRA_BIG_TEXT),
            cs(Notification.EXTRA_SUB_TEXT),
            cs(Notification.EXTRA_SUMMARY_TEXT),
            cs(Notification.EXTRA_INFO_TEXT),
            n.tickerText?.toString()?.trim().orEmpty()
        )
        return Content(title, text)
    }

    private fun latestMessageText(n: Notification): String {
        val arr = n.extras.getParcelableArray(EXTRA_MESSAGES_KEY) ?: return ""
        for (i in arr.indices.reversed()) {
            val bundle = arr[i] as? Bundle ?: continue
            val text = bundle.getCharSequence("text")?.toString()?.trim().orEmpty()
            if (text.isNotEmpty()) return text
        }
        return ""
    }

    private fun textLines(n: Notification): String {
        val lines = n.extras.getCharSequenceArray(EXTRA_TEXT_LINES_KEY) ?: return ""
        return lines.mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotEmpty) }
            .takeLast(3)
            .joinToString("\n")
    }

    private fun firstNonBlank(vararg values: String): String =
        values.firstOrNull { it.isNotBlank() }.orEmpty()

    companion object {
        private const val EXTRA_CONVERSATION_TITLE_KEY = "android.conversationTitle"
        private const val EXTRA_MESSAGES_KEY = "android.messages"
        private const val EXTRA_TEXT_LINES_KEY = "android.textLines"
    }
}

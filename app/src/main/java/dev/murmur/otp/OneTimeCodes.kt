package dev.murmur.otp

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * One-time codes in the strip: when a text or notification brings a sign-in code, the keyboard offers it as a chip.
 * The code is kept in memory only, for [FRESH_MS], and used at most once. Needs notification access.
 */
object OneTimeCodes {
    class Code(val code: String, val from: String, val at: Long)

    private const val FRESH_MS = 3 * 60_000L
    private val main by lazy { Handler(Looper.getMainLooper()) }
    @Volatile private var latest: Code? = null
    /** Called on the main thread when a new code arrives (the keyboard shows it if a field is open). */
    @Volatile var onNew: ((Code) -> Unit)? = null

    private val CUE = Regex("""(?i)\b(code|passcode|verification|verify|otp|one[- ]time|pin|security|log ?in|sign[- ]?in|2fa|authenticat\w*|c[oó]digo|clave)\b""")
    // 4–8 digits, or 3+3 / 4+4 split by a space or dash ("482 913", "4829-1300")
    private val NUMBER = Regex("""(?<![\d.,/:$€£])(\d{3,4}[ -]\d{3,4}|\d{4,8})(?![\d,/:%]|\.\d|\s?(?:am|pm|AM|PM)\b)""")

    /** The code in [text], if it reads like a sign-in message. Skips years, prices, times and phone numbers. */
    fun extract(text: String): String? {
        if (!CUE.containsMatchIn(text)) return null
        return NUMBER.findAll(text).map { it.groupValues[1].replace(Regex("[ -]"), "") }
            .firstOrNull { it.length in 4..8 && !(it.length == 4 && it.toInt() in 1990..2099) }
    }

    fun offer(code: String, from: String) {
        val c = Code(code, from, System.currentTimeMillis())
        latest = c
        main.post { onNew?.invoke(c) }
    }

    /** The code still waiting to be used, if it arrived in the last few minutes. */
    fun fresh(): Code? = latest?.takeIf { System.currentTimeMillis() - it.at < FRESH_MS }

    fun consume() { latest = null }

    private const val TEST_EXTRA = "murmur_test_code"
    private const val CHANNEL = "murmur_test_code"

    /** Posts a fake sign-in text with a random 6-digit code, so the chip can be tried end to end. */
    fun sendTest(context: Context) {
        val nm = context.getSystemService(android.app.NotificationManager::class.java)
        nm.createNotificationChannel(android.app.NotificationChannel(CHANNEL, "Test one-time code", android.app.NotificationManager.IMPORTANCE_HIGH))
        val code = (100000..999999).random()
        val n = Notification.Builder(context, CHANNEL)
            .setSmallIcon(helium314.keyboard.latin.R.drawable.ic_murmur_key)
            .setContentTitle("Murmur test")
            .setContentText("Your Murmur verification code is $code. Open a text field to see it in the strip.")
            .setAutoCancel(true)
            .setTimeoutAfter(FRESH_MS)
            .addExtras(android.os.Bundle().apply { putBoolean(TEST_EXTRA, true) })
            .build()
        nm.notify(CHANNEL.hashCode(), n)
    }

    fun hasAccess(context: Context): Boolean {
        val enabled = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: return false
        return enabled.contains(ComponentName(context, Listener::class.java).flattenToString())
    }

    /** Reads new notifications for codes. Nothing is stored or sent anywhere. */
    class Listener : NotificationListenerService() {
        override fun onNotificationPosted(sbn: StatusBarNotification) {
            val e = sbn.notification?.extras ?: return
            if (sbn.packageName == packageName && !e.getBoolean(TEST_EXTRA)) return // only Murmur's own test code
            val text = listOfNotNull(e.getCharSequence(Notification.EXTRA_TITLE), e.getCharSequence(Notification.EXTRA_TEXT),
                e.getCharSequence(Notification.EXTRA_BIG_TEXT)).joinToString(" ")
            val code = extract(text) ?: return
            val from = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString() }
                .getOrDefault("a notification")
            // Messages: say who sent it ("Chase"), not "Messages"
            val sender = e.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.takeIf { it.isNotBlank() && it.length <= 24 && it.none(Char::isDigit) }
            offer(code, sender ?: from)
        }
    }
}

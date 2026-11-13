package dev.murmur.cleanup

import android.content.Context
import android.content.pm.ApplicationInfo

/**
 * Murmur: how dictation is written, by app (design/styles-snippets.html, 01).
 * Chat apps get Casual, email and writing apps get Polished, everything else Neutral (today's Light cleanup).
 * Tapping the style chip while dictating changes it for that app, and that choice is remembered.
 */
object WritingStyle {
    enum class Style(val label: String, val hint: String) {
        CASUAL("Casual", "lowercase, no end period"),
        NEUTRAL("Neutral", "proper sentences, as spoken"),
        POLISHED("Polished", "email-ready"),
    }

    enum class Kind(val label: String) { CHAT("chat apps"), EMAIL("email apps"), WRITING("writing apps"), OTHER("other apps") }

    private const val PREFS = "murmur"
    private const val DEFAULT = "style_default"
    private const val APP = "style_app_"
    const val LOWERCASE = "style_casual_lowercase"
    const val DROP_PERIOD = "style_casual_drop_period"

    val CHAT_APPS = listOf(
        "com.google.android.apps.messaging", "com.samsung.android.messaging", "com.whatsapp", "com.whatsapp.w4b",
        "org.telegram.messenger", "org.thoughtcrime.securesms", "com.facebook.orca", "com.instagram.android",
        "com.discord", "com.snapchat.android", "com.Slack", "com.google.android.apps.dynamite", "com.groupme.android",
        "com.twitter.android", "com.reddit.frontpage", "com.microsoft.teams", "com.viber.voip", "jp.naver.line.android",
        "com.kakao.talk", "com.tencent.mm", "com.beeper.android", "app.bluesky", "com.zhiliaoapp.musically",
    )
    val EMAIL_APPS = listOf(
        "com.google.android.gm", "com.microsoft.office.outlook", "com.samsung.android.email.provider", "com.yahoo.mobile.client.android.mail",
        "ch.protonmail.android", "me.proton.android.mail", "com.fastmail.app", "com.readdle.spark", "com.superhuman.mail",
        "com.google.android.gm.lite", "com.linkedin.android",
    )
    val WRITING_APPS = listOf(
        "com.google.android.apps.docs.editors.docs", "com.microsoft.office.word", "com.microsoft.office.officehubrow",
        "notion.id", "com.samsung.android.app.notes", "com.google.android.apps.docs", "com.dropbox.paper", "md.obsidian",
    )

    fun kindOf(context: Context, pkg: String?): Kind {
        pkg ?: return Kind.OTHER
        if (pkg in CHAT_APPS) return Kind.CHAT
        if (pkg in EMAIL_APPS) return Kind.EMAIL
        if (pkg in WRITING_APPS) return Kind.WRITING
        val category = runCatching { context.packageManager.getApplicationInfo(pkg, 0).category }.getOrDefault(ApplicationInfo.CATEGORY_UNDEFINED)
        return if (category == ApplicationInfo.CATEGORY_SOCIAL) Kind.CHAT else Kind.OTHER
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun default(context: Context): Style =
        prefs(context).getString(DEFAULT, null)?.let { runCatching { Style.valueOf(it) }.getOrNull() } ?: Style.NEUTRAL

    fun setDefault(context: Context, s: Style) = prefs(context).edit().putString(DEFAULT, s.name).apply()

    /** The style Murmur would pick for [pkg] on its own. */
    fun automatic(context: Context, pkg: String?): Style = when (kindOf(context, pkg)) {
        Kind.CHAT -> Style.CASUAL
        Kind.EMAIL, Kind.WRITING -> Style.POLISHED
        Kind.OTHER -> default(context)
    }

    fun chosen(context: Context, pkg: String?): Style? = pkg?.let {
        prefs(context).getString(APP + it, null)?.let { s -> runCatching { Style.valueOf(s) }.getOrNull() }
    }

    fun forApp(context: Context, pkg: String?): Style = chosen(context, pkg) ?: automatic(context, pkg)

    /** Remembers [s] for [pkg]; picking the automatic style forgets the choice. */
    fun setForApp(context: Context, pkg: String, s: Style) {
        val e = prefs(context).edit()
        if (s == automatic(context, pkg)) e.remove(APP + pkg) else e.putString(APP + pkg, s.name)
        e.apply()
    }

    /** Apps you've changed by hand. */
    fun chosenApps(context: Context): List<String> =
        prefs(context).all.keys.filter { it.startsWith(APP) }.map { it.removePrefix(APP) }

    fun next(s: Style) = Style.entries[(s.ordinal + 1) % Style.entries.size]

    fun lowercaseStart(context: Context) = prefs(context).getBoolean(LOWERCASE, true)
    fun dropPeriod(context: Context) = prefs(context).getBoolean(DROP_PERIOD, true)
    fun setFlag(context: Context, key: String, on: Boolean) = prefs(context).edit().putBoolean(key, on).apply()

    /** One line for Claude's prompt, or null for Neutral. */
    fun promptLine(s: Style): String? = when (s) {
        Style.CASUAL -> "Casual texting: start sentences in lowercase (but keep \"I\" and names capitalized), leave off the period at the very end, keep slang and contractions like \"gonna\"."
        Style.POLISHED -> "Polished, ready for email: complete sentences with full punctuation and capitals, \"gonna\"/\"wanna\"/\"gotta\" written out, texting slang like \"lol\" dropped. Keep the user's meaning and voice; don't add greetings, sign-offs or new content."
        Style.NEUTRAL -> null
    }

    // ---------------------------------------------------------------- on-device rules

    /** Applies [s] after cleanup (and after Claude), so the result is the same with or without Claude. */
    fun apply(context: Context, text: String, s: Style): String = when (s) {
        Style.NEUTRAL -> text
        Style.CASUAL -> casual(text, lowercaseStart(context), dropPeriod(context))
        Style.POLISHED -> polished(text)
    }

    private val KEEP_CAPS = setOf("i", "i'm", "i'll", "i've", "i'd", "i’m", "i’ll", "i’ve", "i’d")

    internal fun casual(text: String, lowercase: Boolean, dropPeriod: Boolean): String {
        var s = text.trim()
        if (s.isEmpty()) return s
        if (lowercase) {
            // First letter of each sentence, unless the word is "I…", an acronym, or has capitals inside ("iPhone", "McDonald")
            s = Regex("""(^|[.!?]\s+|\n\s*)([A-Z])([\w'’]*)""").replace(s) { m ->
                val word = m.groupValues[2] + m.groupValues[3]
                val keep = word.lowercase() in KEEP_CAPS || word.drop(1).any { it.isUpperCase() } || word.length == 1 && word != "A"
                m.groupValues[1] + (if (keep) m.groupValues[2] else m.groupValues[2].lowercase()) + m.groupValues[3]
            }
        }
        if (dropPeriod && s.endsWith('.') && !s.endsWith("..")) s = s.dropLast(1)
        return s
    }

    private val EXPAND = listOf(
        "gonna" to "going to", "wanna" to "want to", "gotta" to "have to", "kinda" to "kind of", "sorta" to "sort of",
        "dunno" to "don't know", "lemme" to "let me", "gimme" to "give me", "y'all" to "you all", "ya" to "you",
    )
    private val SLANG = Regex("""\s*,?\s*\b(?:lol|lmao|lmfao|rofl|omg|smh|ngl|jk|tbh|haha+|hehe+)\b\s*,?""", RegexOption.IGNORE_CASE)

    internal fun polished(text: String): String {
        var s = text.trim()
        if (s.isEmpty()) return s
        for ((a, b) in EXPAND) s = Regex("""\b${Regex.escape(a)}\b""", RegexOption.IGNORE_CASE).replace(s) { m ->
            if (m.value[0].isUpperCase()) b.replaceFirstChar { it.uppercase() } else b
        }
        s = SLANG.replace(s, " ").replace(Regex("""\s+([,.!?])"""), "$1").replace(Regex(""" {2,}"""), " ").trim()
        s = s.trimStart(',', ' ')
        s = Regex("""(^|[.!?]\s+)yeah\b""", RegexOption.IGNORE_CASE).replace(s) { it.groupValues[1] + "Yes" }
        // Capital at each sentence start, and end on punctuation (an emoji can follow it)
        s = Regex("""(^|[.!?]\s+)([a-z])""").replace(s) { it.groupValues[1] + it.groupValues[2].uppercase() }
        val core = s.trimEnd { !it.isLetterOrDigit() && it !in ".!?\"')" }
        if (core.isNotEmpty() && core.last().isLetterOrDigit()) s = core + "." + s.substring(core.length)
        return s
    }
}

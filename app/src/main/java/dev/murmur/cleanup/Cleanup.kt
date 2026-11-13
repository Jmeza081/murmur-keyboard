package dev.murmur.cleanup

import android.content.Context

/** The cleanup level you pick, and the one place dictated text gets cleaned. */
object Cleanup {
    enum class Level { OFF, LIGHT, CLAUDE }

    private const val PREFS = "murmur"
    private const val KEY = "cleanup_level"

    fun level(context: Context): Level =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            ?.let { runCatching { Level.valueOf(it) }.getOrNull() } ?: Level.LIGHT

    fun setLevel(context: Context, level: Level) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, level.name)
            .apply { if (level != Level.OFF) putString(KEY_ON, level.name) }.apply()

    private const val KEY_ON = "cleanup_level_on"
    /** The panel's cleanup button: off, or back to the level you had on (Light or Claude). Returns whether it's on now. */
    fun toggle(context: Context): Boolean {
        if (level(context) != Level.OFF) { setLevel(context, Level.OFF); return false }
        val back = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ON, null)
            ?.let { runCatching { Level.valueOf(it) }.getOrNull() } ?: Level.LIGHT
        setLevel(context, back)
        return true
    }

    /** Cleans raw dictation at the chosen level. Instant, so it can run on any thread. */
    fun process(context: Context, raw: String, pkg: String? = null): String = when (level(context)) {
        Level.OFF -> raw.trim()
        Level.LIGHT, Level.CLAUDE -> TextCleanup.clean(removeExtraFillers(raw, extraFillers(context)))
            .let { WritingStyle.apply(context, it, WritingStyle.forApp(context, pkg)) } // Murmur: the app's writing style
    }.let { dev.murmur.dictionary.MurmurDictionary.applyReplacements(context, it) } // your replacements apply at every level
        .let(SpokenEmoji::apply) // "heart emoji" becomes the emoji at every level
        .let { dev.murmur.snippets.Snippets.applySpoken(context, it) } // "paste my address" becomes the snippet

    private val polisher = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "murmur-claude") }

    /**
     * Like [process], but at the Claude level sends the text to Claude first, falling back to the Light rules
     * when that fails. [onDone] runs on a background thread.
     */
    fun processAsync(context: Context, raw: String, pkg: String?, onDone: (String) -> Unit) {
        val app = context.applicationContext
        if (level(app) != Level.CLAUDE || !ClaudeCleanup.active(app) || raw.isBlank()) {
            android.util.Log.i("MurmurClaude", "Skipped: level ${level(app)}, key ${ClaudeCleanup.active(app)}")
            return onDone(process(app, raw, pkg))
        }
        polisher.execute {
            val out = try {
                val vocabulary = runCatching { dev.murmur.dictionary.MurmurDictionary.words(app) }.getOrDefault(emptyList())
                val style = WritingStyle.forApp(app, pkg)
                ClaudeCleanup.polish(app, raw, vocabulary, extraFillers(app), WritingStyle.promptLine(style))
                    .let { WritingStyle.apply(app, it, style) }
                    .let { dev.murmur.dictionary.MurmurDictionary.applyReplacements(app, it) }
                    .let(SpokenEmoji::apply)
                    .let { dev.murmur.snippets.Snippets.applySpoken(app, it) }
            } catch (e: Exception) {
                process(app, raw, pkg)
            }
            onDone(out)
        }
    }

    /** The fillers Light always removes, shown on the Dictation screen. */
    val BUILT_IN_FILLERS = listOf("um", "uh", "erm", "like", "you know")
    private const val EXTRA = "extra_fillers"

    /** Your own filler words ("basically", "kind of"), removed whole-word with any commas around them. */
    fun extraFillers(context: Context): List<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(EXTRA, "")!!
            .split('\n').map { it.trim() }.filter { it.isNotEmpty() }

    fun setExtraFillers(context: Context, words: List<String>) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(EXTRA, words.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct().joinToString("\n")).apply()

    internal fun removeExtraFillers(text: String, words: List<String>): String = words.fold(text) { s, w ->
        s.replace(Regex("""(?:,\s*)?\b${Regex.escape(w)}\b(?:\s*,)?""", RegexOption.IGNORE_CASE), " ")
    }.replace(Regex(" {2,}"), " ")
}

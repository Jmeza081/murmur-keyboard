package dev.murmur.cleanup

import android.content.Context

/** The cleanup level you pick, and the one place dictated text gets cleaned. */
object Cleanup {
    enum class Level { OFF, LIGHT }

    private const val PREFS = "murmur"
    private const val KEY = "cleanup_level"

    fun level(context: Context): Level =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            ?.let { runCatching { Level.valueOf(it) }.getOrNull() } ?: Level.LIGHT

    fun setLevel(context: Context, level: Level) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, level.name).apply()

    /** Cleans raw dictation at the chosen level. Instant, so it can run on any thread. */
    fun process(context: Context, raw: String): String = when (level(context)) {
        Level.OFF -> raw.trim()
        Level.LIGHT -> TextCleanup.clean(removeExtraFillers(raw, extraFillers(context)))
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

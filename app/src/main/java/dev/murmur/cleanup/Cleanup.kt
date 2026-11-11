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
        Level.LIGHT -> TextCleanup.clean(raw)
    }
}

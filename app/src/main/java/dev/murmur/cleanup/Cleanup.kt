package dev.murmur.cleanup

import android.content.Context

/** The cleanup level you pick, and the one place dictated text gets cleaned. */
object Cleanup {
    enum class Level { OFF, LIGHT, SMART }

    private const val PREFS = "murmur"
    private const val KEY = "cleanup_level"

    fun level(context: Context): Level =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            ?.let { runCatching { Level.valueOf(it) }.getOrNull() } ?: Level.LIGHT

    fun setLevel(context: Context, level: Level) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, level.name).apply()

    /**
     * Cleans raw dictation at the chosen level. Smart runs the rules first, then Gemini Nano,
     * and keeps the rules' result if Gemini Nano fails or changes the meaning.
     * Blocking (Smart can take up to 1.5 s); call off the main thread.
     */
    fun process(context: Context, raw: String): String = when (level(context)) {
        Level.OFF -> raw.trim()
        Level.LIGHT -> TextCleanup.clean(raw)
        Level.SMART -> TextCleanup.clean(raw).let { light -> SmartCleanup.polish(light) ?: light }
    }
}

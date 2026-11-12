package dev.murmur.settings

import android.content.Context

/** Dictation settings that aren't cleanup or waveform: silence timeout, and the last run's timing for the model card. */
object DictationPrefs {
    private const val PREFS = "murmur"
    private const val SILENCE = "silence_stop_ms"
    private const val LAST_DECODE = "last_decode_ms"
    const val DEFAULT_SILENCE_MS = 5000
    val SILENCE_RANGE_MS = 1000..10000

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private const val TELEPROMPTER = "live_words_teleprompter"
    /** Live words (ElevenLabs only) shown as two big lines over a dimmed waveform; off shows the waveform alone. */
    fun teleprompter(context: Context) = prefs(context).getBoolean(TELEPROMPTER, true)
    fun setTeleprompter(context: Context, on: Boolean) = prefs(context).edit().putBoolean(TELEPROMPTER, on).apply()

    fun silenceMs(context: Context) = prefs(context).getInt(SILENCE, DEFAULT_SILENCE_MS).coerceIn(SILENCE_RANGE_MS)
    fun clearSilence(context: Context) = prefs(context).edit().remove(SILENCE).apply()
    fun setSilenceMs(context: Context, ms: Int) = prefs(context).edit().putInt(SILENCE, ms.coerceIn(SILENCE_RANGE_MS)).apply()

    /** How long the last transcription took, or -1 if none yet. */
    fun lastDecodeMs(context: Context) = prefs(context).getLong(LAST_DECODE, -1)
    fun setLastDecodeMs(context: Context, ms: Long) = prefs(context).edit().putLong(LAST_DECODE, ms).apply()

    /** Which engine ran last, how long it took, and why the cloud was skipped (null if it wasn't). */
    data class LastRun(val engine: String, val ms: Long, val fallbackReason: String?)
    fun lastRun(context: Context): LastRun? = prefs(context).getString("last_engine", null)?.let {
        LastRun(it, prefs(context).getLong("last_engine_ms", -1), prefs(context).getString("last_fallback", null))
    }
    fun setLastRun(context: Context, ms: Long, engine: String, fallbackReason: String?) = prefs(context).edit()
        .putString("last_engine", engine).putLong("last_engine_ms", ms).putString("last_fallback", fallbackReason).apply()
}

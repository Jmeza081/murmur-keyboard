package dev.murmur.settings

import android.content.Context

/** Dictation settings that aren't cleanup or waveform: silence timeout, and the last run's timing for the model card. */
object DictationPrefs {
    private const val PREFS = "murmur"
    private const val SILENCE = "silence_stop_ms"
    private const val LAST_DECODE = "last_decode_ms"
    const val DEFAULT_SILENCE_MS = 2000
    val SILENCE_RANGE_MS = 1000..4000

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun silenceMs(context: Context) = prefs(context).getInt(SILENCE, DEFAULT_SILENCE_MS).coerceIn(SILENCE_RANGE_MS)
    fun setSilenceMs(context: Context, ms: Int) = prefs(context).edit().putInt(SILENCE, ms.coerceIn(SILENCE_RANGE_MS)).apply()

    /** How long the last transcription took, or -1 if none yet. */
    fun lastDecodeMs(context: Context) = prefs(context).getLong(LAST_DECODE, -1)
    fun setLastDecodeMs(context: Context, ms: Long) = prefs(context).edit().putLong(LAST_DECODE, ms).apply()
}

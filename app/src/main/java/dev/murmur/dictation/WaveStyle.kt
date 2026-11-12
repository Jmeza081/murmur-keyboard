package dev.murmur.dictation

import android.content.Context
import kotlin.math.log10

/** How the listening waveform looks. Picked on the Dictation screen; Dot matrix by default. */
enum class WaveStyle(val label: String) {
    DOTS("Dot matrix"), THREAD("Thread"), BARS("Bars");

    companion object {
        private const val PREFS = "murmur"
        private const val KEY = "wave_style"

        fun get(context: Context): WaveStyle =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
                ?.let { runCatching { valueOf(it) }.getOrNull() } ?: DOTS

        fun set(context: Context, style: WaveStyle) =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, style.name).apply()

        /**
         * Raw mic RMS (0..1) to a 0..1 level on a decibel scale: -55 dB (room tone) is 0,
         * -18 dB (normal speech up close) is 1.
         */
        fun normalize(rms: Float): Float {
            val db = 20f * log10(rms.coerceAtLeast(1e-5f))
            return ((db + 55f) / 37f).coerceIn(0f, 1f)
        }

        /** Fast attack, softer release. */
        fun smooth(previous: Float, target: Float) =
            if (target > previous) target * 0.8f + previous * 0.2f else target * 0.45f + previous * 0.55f
    }
}

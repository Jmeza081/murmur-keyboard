package dev.murmur.settings

import android.content.SharedPreferences
import androidx.core.content.edit
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.createPrefKeyForBooleanSettings
import kotlin.math.abs

/** Key height steps, shared by the Theme screen and the keyboard's own height tool. */
object KeyHeight {
    val STEPS = listOf(0.85f to "Compact", 0.92f to "Small", 1.0f to "Medium", 1.08f to "Large", 1.16f to "Tall")

    /** HeliBoard stores a height per (landscape, folded); Murmur sets all four together. */
    private fun key(index: Int) = createPrefKeyForBooleanSettings(Settings.PREF_KEYBOARD_HEIGHT_SCALE_PREFIX, index, 2)

    fun nearest(scale: Float) = STEPS.indices.minBy { abs(STEPS[it].first - scale) }
    fun readIndex(prefs: SharedPreferences) = nearest(prefs.getFloat(key(0), 1f))
    fun write(prefs: SharedPreferences, index: Int) = prefs.edit { (0 until 4).forEach { putFloat(key(it), STEPS[index].first) } }
}

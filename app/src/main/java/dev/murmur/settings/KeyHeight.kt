package dev.murmur.settings

import android.content.SharedPreferences
import android.content.res.Resources
import androidx.core.content.edit
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.createPrefKeyForBooleanSettings
import helium314.keyboard.latin.utils.FoldableUtils
import kotlin.math.abs

/**
 * Key height steps, shared by the Theme screen and the keyboard's own height tool.
 * Each screen of a foldable keeps its own step, and the inner screen's steps are 20% shorter:
 * the inner screen uses tablet sizes, so the same step looked far taller there than on the cover screen.
 */
object KeyHeight {
    val STEPS = listOf(0.85f to "Compact", 0.92f to "Small", 1.0f to "Medium", 1.08f to "Large", 1.16f to "Tall")
    private const val INNER_FACTOR = 0.8f

    /** HeliBoard stores a height per (landscape, folded) as index bits 0 and 1. */
    private fun key(index: Int) = createPrefKeyForBooleanSettings(Settings.PREF_KEYBOARD_HEIGHT_SCALE_PREFIX, index, 2)

    fun isInner(res: Resources) = FoldableUtils.isFoldable && res.configuration.smallestScreenWidthDp >= 600
    fun screenFactor(res: Resources) = if (isInner(res)) INNER_FACTOR else 1f
    fun screenName(res: Resources) = if (!FoldableUtils.isFoldable) null else if (isInner(res)) "inner screen" else "cover screen"

    /** The stored values this screen reads: both orientations of the current screen, or all four on a normal phone. */
    private fun indexes(res: Resources) = when {
        !FoldableUtils.isFoldable -> 0..3
        isInner(res) -> 0..1
        else -> 2..3
    }

    fun nearest(scale: Float) = STEPS.indices.minBy { abs(STEPS[it].first - scale) }
    fun readIndex(prefs: SharedPreferences, res: Resources) = nearest(prefs.getFloat(key(indexes(res).first), 1f))
    fun write(prefs: SharedPreferences, res: Resources, index: Int) =
        prefs.edit { indexes(res).forEach { putFloat(key(it), STEPS[index].first) } }
}

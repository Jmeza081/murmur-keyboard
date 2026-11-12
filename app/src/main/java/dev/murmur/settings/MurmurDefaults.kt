package dev.murmur.settings

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import helium314.keyboard.keyboard.KeyboardActionListener
import helium314.keyboard.latin.common.Constants.Subtype.ExtraValue
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.SettingsSubtype.Companion.toSettingsSubtype
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.SubtypeUtilsAdditional
import helium314.keyboard.latin.utils.locale
import helium314.keyboard.latin.utils.prefs

/**
 * Murmur's defaults (PLAN.md, Phase 6 step 6). New installs get them from Defaults.kt; this applies
 * them once to existing installs, where HeliBoard's older values were already saved.
 * Bump [VERSION] when a default changes and should reach existing installs.
 */
object MurmurDefaults {
    private const val VERSION = 3
    private const val KEY = "murmur_defaults_version"

    fun applyOnce(context: Context) {
        val prefs = context.prefs()
        val applied = prefs.getInt(KEY, 0)
        if (applied >= VERSION) return
        if (applied < 3) prefs.edit {
            // v2, v3: autocorrect was too timid at HeliBoard's "Modest"; 0.7 is the strong end of "Aggressive"
            putFloat(Settings.PREF_AUTO_CORRECT_CONFIDENCE, AUTOCORRECT_AGGRESSIVE)
            putInt(KEY, VERSION)
        }
        if (applied >= 1) return
        prefs.edit {
            putBoolean(Settings.PREF_VIBRATE_ON, true)
            putInt(Settings.PREF_VIBRATION_DURATION_SETTINGS, LIGHT_HAPTIC_MS)
            putBoolean(Settings.PREF_SOUND_ON, false)
            putBoolean(Settings.PREF_POPUP_ON, true)
            putString(Settings.PREF_SPACE_HORIZONTAL_SWIPE, KeyboardActionListener.SwipeAction.MOVE_CURSOR.name)
            putInt(Settings.PREF_DELETE_SWIPE, DELETE_SWIPE_WORDS)
            putBoolean(Settings.PREF_SPACE_TO_CHANGE_LANG, true)
            putBoolean(Settings.PREF_KEY_USE_PERSONALIZED_DICTS, true)
            putBoolean(Settings.PREF_USE_APPS, true)
            putBoolean(Settings.PREF_USE_CONTACTS, false)
            putBoolean(Settings.PREF_SUGGEST_CLIPBOARD_CONTENT, true)
            putBoolean(Settings.PREF_BLOCK_POTENTIALLY_OFFENSIVE, false)
        }
        runCatching { addSpanish(context) }.onFailure { Log.w("MurmurDefaults", "Couldn't add Spanish", it) }
    }

    /** English and Spanish in one keyboard: Spanish as a secondary language of each English subtype. */
    private fun addSpanish(context: Context) {
        SubtypeSettings.getEnabledSubtypes().filter { it.locale().language == "en" }.forEach {
            val subtype = it.toSettingsSubtype()
            if (subtype.getExtraValueOf(ExtraValue.SECONDARY_LOCALES)?.contains("es") == true) return@forEach
            SubtypeUtilsAdditional.changeAdditionalSubtype(subtype, subtype.with(ExtraValue.SECONDARY_LOCALES, "es"), context)
        }
    }

    const val LIGHT_HAPTIC_MS = 15
    const val AUTOCORRECT_AGGRESSIVE = 0.7f
    const val DELETE_SWIPE_WORDS = 2
}

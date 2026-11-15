package dev.murmur.design

import android.graphics.Color

/**
 * Design tokens from design/mockups.html: the single source for Murmur's colors and sizes.
 * Keyboard themes, the strip, the dictation panel and the power-button sheet read from here.
 */
object MurmurTokens {
    /** Cobalt accent, used in both themes for Enter and the mic pill. */
    val ACCENT = Color.parseColor("#2F4BE0")
    val ON_ACCENT = Color.WHITE
    /**
     * Accent choices on the Theme screen (mockup swatches). Each keeps at least 4.5:1 contrast with the
     * white Enter and mic icons on top of it.
     */
    val ACCENTS = listOf(
        "Cobalt" to Color.parseColor("#2F4BE0"), "Teal" to Color.parseColor("#0B7A75"),
        "Ember" to Color.parseColor("#C2410C"), "Violet" to Color.parseColor("#7C3AED"), "Slate" to Color.parseColor("#4B5563"),
        // design/clipboard-themes.html: added alongside the first five
        "Rose" to Color.parseColor("#BE185D"), "Forest" to Color.parseColor("#2F7D4E"),
        "Plum" to Color.parseColor("#86198F"), "Gold" to Color.parseColor("#A16207"),
    )
    /** The accents added in the second set, marked "new" on the Theme screen. */
    val NEW_ACCENTS = setOf("Rose", "Forest", "Plum", "Gold")
    const val PREF_ACCENT = "murmur_accent"

    /** The accent picked on the Theme screen, cobalt by default. */
    fun accent(prefs: android.content.SharedPreferences) = MurmurThemes.current(prefs)?.accent ?: prefs.getInt(PREF_ACCENT, ACCENT)

    /** The listening color (dot, stop button, level bars, glow): the accent picked on the Theme screen. */
    fun live(context: android.content.Context) = accent(helium314.keyboard.latin.utils.DeviceProtectedUtils.getSharedPreferences(context))

    /** Recording orange: listening state, stop button, level bars. */
    val RECORDING = Color.parseColor("#E8590C")

    object Light {
        val BACKGROUND = Color.parseColor("#E8EBF1")
        val KEY = Color.parseColor("#FFFFFF")
        val FUNCTION_KEY = Color.parseColor("#CFD5E0")
        val TEXT = Color.parseColor("#191B21")
        val SUBTLE = Color.parseColor("#6B7282")
        /** Sheets and cards over the keyboard background. */
        val SHEET = Color.parseColor("#F1F3F7")
        val CHIP = Color.parseColor("#DDE2EA")
        val SECONDARY_TEXT = Color.parseColor("#5B6272")
    }

    /**
     * Black (design/clipboard-themes.html): near-black keys and background, each tinted with the accent
     * (background 6%, keys 13%, function keys 9%). A third mode next to Light and Dark.
     */
    object Black {
        private fun tint(base: Int, accent: Int, t: Float) = androidx.core.graphics.ColorUtils.blendARGB(base, accent, t)
        fun background(accent: Int) = tint(Color.BLACK, accent, 0.06f)
        fun key(accent: Int) = tint(Color.parseColor("#0A0A0A"), accent, 0.13f)
        fun functionKey(accent: Int) = tint(Color.parseColor("#050505"), accent, 0.09f)
        fun text(accent: Int) = tint(Color.parseColor("#F2F3F6"), accent, 0.06f)
        fun subtle(accent: Int) = tint(Color.parseColor("#8C92A0"), accent, 0.18f)
    }

    object Dark {
        val BACKGROUND = Color.parseColor("#121419")
        val KEY = Color.parseColor("#2A2E37")
        val FUNCTION_KEY = Color.parseColor("#1E2129")
        val TEXT = Color.parseColor("#ECEEF3")
        val SUBTLE = Color.parseColor("#9AA1B0")
        val SHEET = Color.parseColor("#1E2129")
        val CHIP = Color.parseColor("#2A2E37")
        val SECONDARY_TEXT = Color.parseColor("#A1A8B6")
    }

    // Sizes in dp
    const val KEY_RADIUS_DP = 8
    const val STRIP_HEIGHT_DP = 48
    const val MIC_PILL_WIDTH_DP = 56
    const val MIC_PILL_HEIGHT_DP = 40
    const val SHEET_RADIUS_DP = 28
    const val CHIP_RADIUS_DP = 14

    // Motion (ms), per the mockups: panel swap and sheet 200 ms ease-out; key presses never animate
    const val PANEL_MS = 200L
}

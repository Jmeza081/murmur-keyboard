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

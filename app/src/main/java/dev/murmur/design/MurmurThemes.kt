package dev.murmur.design

import android.graphics.Color

/**
 * Named themes with their own accent (design: "Murmur themes and feature ideas"). Each colors the keyboard,
 * the strip, the dictation panel and the power-button sheet; the accent replaces the Theme screen's swatch.
 */
object MurmurThemes {
    class Palette(
        val id: String, val name: String, val tag: String,
        val background: Int, val key: Int, val functionKey: Int, val text: Int, val subtle: Int, val accent: Int,
    ) {
        val dark get() = androidx.core.graphics.ColorUtils.calculateLuminance(background) < 0.5
    }

    private fun c(hex: String) = Color.parseColor(hex)

    val ALL = listOf(
        // Regolith Red: the Regolith app's palette (near-black ground, grey keys, one red)
        Palette("murmur_regolith", "Regolith", "Regolith Red · dark",
            c("#0A0A0A"), c("#1C1C1C"), c("#2E2E2E"), c("#FFFFFF"), c("#8A8A8A"), c("#E11B17")),
        Palette("murmur_regolith_dust", "Regolith Dust", "Regolith Red · light",
            c("#E6D7B4"), c("#F7F0DF"), c("#D3C29C"), c("#1F1F1F"), c("#5E523A"), c("#E11B17")),
        Palette("murmur_regolith_orbit", "Regolith Orbit", "Regolith Red · blue night",
            c("#121820"), c("#1C2228"), c("#243040"), c("#E8EEF7"), c("#9AA8BE"), c("#E11B17")),
        Palette("murmur_paper_cobalt", "Paper Cobalt", "Light",
            c("#E4E7EE"), c("#FFFFFF"), c("#CDD2DC"), c("#0B0D11"), c("#5B6270"), c("#2F4BE0")),
        Palette("murmur_moss", "Moss", "Dark · green",
            c("#0F1518"), c("#1A2420"), c("#1F3A33"), c("#E7F2EC"), c("#93AB9F"), c("#3DDC84")),
        Palette("murmur_mono", "Mono", "AMOLED",
            c("#000000"), c("#121212"), c("#0A0A0A"), c("#FFFFFF"), c("#8A8A8A"), c("#FFFFFF")),
    )

    fun byId(id: String?) = ALL.firstOrNull { it.id == id }

    /** The named theme in use, or null for the classic Light/Dark/Black/System themes. */
    fun current(prefs: android.content.SharedPreferences): Palette? {
        if (prefs.getBoolean(helium314.keyboard.latin.settings.Settings.PREF_THEME_DAY_NIGHT,
                helium314.keyboard.latin.settings.Defaults.PREF_THEME_DAY_NIGHT)) return null
        return byId(prefs.getString(helium314.keyboard.latin.settings.Settings.PREF_THEME_COLORS, null))
    }

    /** Text and icons on the accent: white, or near-black on light accents (Moss green, Mono white). */
    fun onAccent(accent: Int) = if (androidx.core.graphics.ColorUtils.calculateLuminance(accent) > 0.3) c("#0B0D11") else Color.WHITE
}

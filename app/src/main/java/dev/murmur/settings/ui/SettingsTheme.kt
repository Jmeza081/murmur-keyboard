package dev.murmur.settings.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import helium314.keyboard.latin.R

/** The settings app's tokens, from design/mockups.html (--s-* and --k-acc), light and dark. */
@Immutable
data class SettingsColors(
    val bg: Color, val surface: Color, val ink: Color, val sub: Color, val line: Color, val tint: Color,
    val accent: Color, val onAccent: Color,
)

val LightSettings = SettingsColors(
    bg = Color(0xFFF7F8FA), surface = Color(0xFFFFFFFF), ink = Color(0xFF191B21), sub = Color(0xFF5B6272),
    line = Color(0xFFE1E4EA), tint = Color(0xFFE4E9FF), accent = Color(0xFF2F4BE0), onAccent = Color.White,
)
val DarkSettings = SettingsColors(
    bg = Color(0xFF0F1115), surface = Color(0xFF1A1D23), ink = Color(0xFFECEEF3), sub = Color(0xFFA1A8B6),
    line = Color(0xFF2A2E36), tint = Color(0xFF232A4A), accent = Color(0xFF8EA0FF), onAccent = Color(0xFF0D1336),
)

val LocalSettingsColors = staticCompositionLocalOf { LightSettings }

/** Roboto Flex, bundled for the keyboard; the settings app uses it too. */
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private fun flex(weight: FontWeight) = Font(R.font.roboto_flex, weight,
    variationSettings = androidx.compose.ui.text.font.FontVariation.Settings(androidx.compose.ui.text.font.FontVariation.weight(weight.weight)))

/** Roboto Flex is a variable font: each weight sets the 'wght' axis, or everything renders at 400. */
val RobotoFlex = FontFamily(flex(FontWeight.Normal), flex(FontWeight.Medium), flex(FontWeight.SemiBold), flex(FontWeight.Bold))

@Composable
fun MurmurSettingsTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val c = if (dark) DarkSettings else LightSettings
    val scheme = (if (dark) darkColorScheme() else lightColorScheme()).copy(
        primary = c.accent, onPrimary = c.onAccent, background = c.bg, onBackground = c.ink,
        surface = c.surface, onSurface = c.ink, onSurfaceVariant = c.sub, outline = c.line,
        primaryContainer = c.tint, onPrimaryContainer = c.accent, surfaceContainerHigh = c.surface,
    )
    val base = MaterialTheme.typography
    val type = base.copy(
        headlineLarge = base.headlineLarge.copy(fontFamily = RobotoFlex),
        titleLarge = base.titleLarge.copy(fontFamily = RobotoFlex),
        bodyLarge = base.bodyLarge.copy(fontFamily = RobotoFlex),
        bodyMedium = base.bodyMedium.copy(fontFamily = RobotoFlex),
        labelLarge = base.labelLarge.copy(fontFamily = RobotoFlex),
    )
    CompositionLocalProvider(LocalSettingsColors provides c) {
        MaterialTheme(colorScheme = scheme, typography = type, content = content)
    }
}

package dev.murmur.settings.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.murmur.cleanup.Cleanup
import dev.murmur.speech.ModelManager
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.prefs

/** Settings home (mockup "Settings home"): dictation first, each row showing its current value. */
@Composable
fun HomeScreen(open: (Screen) -> Unit, openLegacy: () -> Unit) {
    val ctx = LocalContext.current
    LocalResumeTick.current // values change in Android's settings; re-read on return
    Column(Modifier.verticalScroll(rememberScrollState())) {
        BigTitle("Keyboard")
        Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Group {
                Item("Dictation", dictationValue(ctx), R.drawable.ic_murmur_mic, onClick = { open(Screen.DICTATION) })
                GroupDivider()
                Item("Power button", powerValue(ctx), R.drawable.ic_murmur_power, onClick = { open(Screen.POWER) })
            }
            Group {
                Item("Theme", "Murmur · Follows system", R.drawable.ic_murmur_palette, onClick = openLegacy)
                GroupDivider()
                Item("Typing", "Swipe, autocorrect, number row", R.drawable.ic_murmur_keyboard, onClick = openLegacy)
                GroupDivider()
                Item("Dictionary", "Your words · English and Spanish", R.drawable.ic_murmur_book, onClick = openLegacy)
            }
            Group {
                Item("Clipboard", clipboardValue(ctx), R.drawable.ic_murmur_clip, onClick = openLegacy)
                GroupDivider()
                Item("Emoji", "Skin tone default", R.drawable.ic_murmur_smile, onClick = openLegacy)
                GroupDivider()
                Item("Tablet layout", "Split in landscape", R.drawable.ic_murmur_split, onClick = openLegacy)
            }
            Group {
                Item("Setup checklist", if (dev.murmur.settings.SetupState.essentialsDone(ctx)) "All set" else "Steps left to finish",
                    onClick = { open(Screen.SETUP) })
                GroupDivider()
                Item("Advanced (temporary)", "Every other option, until these screens replace it", onClick = openLegacy)
            }
        }
    }
}

private fun dictationValue(ctx: Context): String {
    if (!ModelManager.isInstalled(ctx)) return "Speech model not installed"
    val cleanup = if (Cleanup.level(ctx) == Cleanup.Level.OFF) "No cleanup" else "Light cleanup"
    return "Parakeet v3 · $cleanup"
}

/** "On" when Murmur is the phone's digital assistant (what the power button opens). */
private fun powerValue(ctx: Context) =
    if (dev.murmur.settings.SetupState.isAssistant(ctx)) "On · Stops when you pause" else "Off · Set Murmur as the digital assistant"

private fun clipboardValue(ctx: Context): String {
    val minutes = ctx.prefs().getInt(Settings.PREF_CLIPBOARD_HISTORY_RETENTION_TIME, Defaults.PREF_CLIPBOARD_HISTORY_RETENTION_TIME)
    return when {
        minutes <= 0 || minutes >= 121 -> "Keep until cleared"
        minutes % 60 == 0 -> "Keep for ${minutes / 60} hour" + if (minutes > 60) "s" else ""
        else -> "Keep for $minutes minutes"
    }
}

enum class Screen { HOME, DICTATION, POWER, SETUP }

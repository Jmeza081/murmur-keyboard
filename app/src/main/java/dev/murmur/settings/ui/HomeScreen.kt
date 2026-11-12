package dev.murmur.settings.ui

import android.content.Context
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.contentDataType
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.sp
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
            GroupLabel("Try it")
            TryIt()
            Group {
                Item("Dictation", dictationValue(ctx), R.drawable.ic_murmur_mic, onClick = { open(Screen.DICTATION) })
                GroupDivider()
                Item("Power button", powerValue(ctx), R.drawable.ic_murmur_power, onClick = { open(Screen.POWER) })
            }
            Group {
                Item("Theme", themeValue(ctx), R.drawable.ic_murmur_palette, onClick = { open(Screen.THEME) })
                GroupDivider()
                Item("Typing", "Swipe, autocorrect, number row", R.drawable.ic_murmur_keyboard, onClick = { open(Screen.TYPING) })
                GroupDivider()
                Item("Dictionary", "Your words · English and Spanish", R.drawable.ic_murmur_book, onClick = openLegacy)
            }
            Group {
                Item("Clipboard", clipboardValue(ctx), R.drawable.ic_murmur_clip, onClick = { open(Screen.CLIPBOARD) })
                GroupDivider()
                Item("Emoji", emojiValue(ctx), R.drawable.ic_murmur_smile, onClick = { open(Screen.EMOJI) })
                GroupDivider()
                Item("Tablet layout", tabletValue(ctx), R.drawable.ic_murmur_split, onClick = { open(Screen.TABLET) })
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
        !ctx.prefs().getBoolean(Settings.PREF_ENABLE_CLIPBOARD_HISTORY, Defaults.PREF_ENABLE_CLIPBOARD_HISTORY) -> "History off"
        minutes <= 0 || minutes >= 121 -> "Keep until cleared"
        minutes % 60 == 0 -> "Keep for ${minutes / 60} hour" + if (minutes > 60) "s" else ""
        else -> "Keep for $minutes minutes"
    }
}

private fun themeValue(ctx: Context): String {
    val prefs = ctx.prefs()
    val accent = dev.murmur.design.MurmurTokens.ACCENTS.firstOrNull { it.second == dev.murmur.design.MurmurTokens.accent(prefs) }?.first ?: "Custom"
    val mode = when {
        prefs.getBoolean(Settings.PREF_THEME_DAY_NIGHT, Defaults.PREF_THEME_DAY_NIGHT) -> "Follows system"
        prefs.getString(Settings.PREF_THEME_COLORS, Defaults.PREF_THEME_COLORS) == helium314.keyboard.keyboard.KeyboardTheme.THEME_MURMUR_DARK -> "Dark"
        else -> "Light"
    }
    return "$accent · $mode"
}

private fun emojiValue(ctx: Context) = when (ctx.prefs().getString(Settings.PREF_EMOJI_SKIN_TONE, Defaults.PREF_EMOJI_SKIN_TONE)) {
    "", null -> "Default skin tone"
    else -> "Custom skin tone"
}

private fun tabletValue(ctx: Context): String {
    val p = ctx.prefs()
    val land = p.getBoolean(Settings.PREF_ENABLE_SPLIT_KEYBOARD_LANDSCAPE, Defaults.PREF_ENABLE_SPLIT_KEYBOARD)
    val port = p.getBoolean(Settings.PREF_ENABLE_SPLIT_KEYBOARD, Defaults.PREF_ENABLE_SPLIT_KEYBOARD)
    return when { land && port -> "Split always"; land -> "Split in landscape"; port -> "Split in portrait"; else -> "Not split" }
}

enum class Screen { HOME, DICTATION, POWER, SETUP, THEME, TYPING, CLIPBOARD, EMOJI, TABLET }

/** A small playground: a text field and a password field, to try the keyboard, dictation and autofill chips. */
@Composable
private fun TryIt() {
    var text by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    Group {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PlayField(text, { text = it }, "Type or dictate here")
            PlayField(password, { password = it }, "Password", password = true)
            Hint("Nothing typed here is saved. In the password field, Murmur shows no suggestions and learns nothing.")
        }
    }
}

@Composable
private fun PlayField(value: String, onChange: (String) -> Unit, placeholder: String, password: Boolean = false) {
    val c = LocalSettingsColors.current
    var focused by remember { mutableStateOf(false) }
    androidx.compose.foundation.text.BasicTextField(
        value = value, onValueChange = onChange,
        singleLine = password,
        textStyle = androidx.compose.ui.text.TextStyle(fontFamily = RobotoFlex, fontSize = 16.sp, color = c.ink),
        cursorBrush = androidx.compose.ui.graphics.SolidColor(c.accent),
        visualTransformation = if (password) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = if (password) androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Password, autoCorrectEnabled = false)
            else androidx.compose.foundation.text.KeyboardOptions(capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Sentences),
        // The text box isn't a login field: tell autofill so 1Password doesn't offer itself there.
        // The password box says it's a password, so it gets the 1Password chips.
        modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }.semantics {
            if (password) contentType = androidx.compose.ui.autofill.ContentType.Password
            else contentDataType = androidx.compose.ui.autofill.ContentDataType.None
        },
        decorationBox = { inner ->
            Box(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .border(if (focused) 1.5.dp else 1.dp, if (focused) c.accent else c.line, RoundedCornerShape(16.dp))
                .padding(horizontal = 16.dp, vertical = 13.dp), contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) Text(placeholder, fontFamily = RobotoFlex, fontSize = 16.sp, color = c.sub)
                inner()
            }
        },
    )
}

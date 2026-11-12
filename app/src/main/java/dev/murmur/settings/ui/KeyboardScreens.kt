package dev.murmur.settings.ui

import android.content.SharedPreferences
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import helium314.keyboard.keyboard.KeyboardActionListener
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.latin.database.ClipboardDao
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.createPrefKeyForBooleanSettings
import helium314.keyboard.latin.utils.prefs

/*
 * Murmur's screens for the keyboard options kept in Phase 6 (PLAN.md, Phase 7 step 4).
 * Each control writes HeliBoard's own preference, so the keyboard picks it up through its settings listener.
 */

/** Scrolling screen frame shared by the four screens. */
@Composable
private fun ScreenFrame(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.verticalScroll(rememberScrollState())) {
        TopBar(title, onBack)
        Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            content()
        }
    }
}

/** A switch row bound to a boolean preference. [reload] rebuilds the keys, for options that change the layout. */
@Composable
private fun PrefSwitch(prefs: SharedPreferences, key: String, default: Boolean, title: String, value: String? = null, reload: Boolean = false,
                       onChange: (Boolean) -> Unit = {}) {
    var on by remember { mutableStateOf(prefs.getBoolean(key, default)) }
    val set = { v: Boolean ->
        on = v; prefs.edit { putBoolean(key, v) }; onChange(v)
        if (reload) KeyboardSwitcher.getInstance().setThemeNeedsReload()
    }
    Item(title, value, onClick = { set(!on) }, chevron = false, trailing = { MurmurSwitch(on) { set(it) } })
}

// ---------------------------------------------------------------- Typing

private enum class Autocorrect(val label: String) { OFF("Off"), MODEST("Modest"), AGGRESSIVE("Aggressive") }
private const val MODEST_CONFIDENCE = 0.35f

@Composable
fun TypingScreen(onBack: () -> Unit) {
    val prefs = LocalContext.current.prefs()
    var autocorrect by remember {
        mutableStateOf(when {
            !prefs.getBoolean(Settings.PREF_AUTO_CORRECTION, Defaults.PREF_AUTO_CORRECTION) -> Autocorrect.OFF
            prefs.getFloat(Settings.PREF_AUTO_CORRECT_CONFIDENCE, Defaults.PREF_AUTO_CORRECT_CONFIDENCE) <= 0.4f -> Autocorrect.MODEST
            else -> Autocorrect.AGGRESSIVE
        })
    }
    ScreenFrame("Typing", onBack) {
        GroupLabel("Autocorrect")
        Group {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Segmented(Autocorrect.entries, autocorrect, { it.label }) { a ->
                    autocorrect = a
                    prefs.edit {
                        putBoolean(Settings.PREF_AUTO_CORRECTION, a != Autocorrect.OFF)
                        if (a == Autocorrect.MODEST) putFloat(Settings.PREF_AUTO_CORRECT_CONFIDENCE, MODEST_CONFIDENCE)
                        if (a == Autocorrect.AGGRESSIVE) putFloat(Settings.PREF_AUTO_CORRECT_CONFIDENCE, dev.murmur.settings.MurmurDefaults.AUTOCORRECT_AGGRESSIVE)
                    }
                }
                Hint(when (autocorrect) {
                    Autocorrect.OFF -> "Words are typed exactly as you tap them. Suggestions still show."
                    Autocorrect.MODEST -> "Fixes only clear typos, so unusual words are left alone."
                    Autocorrect.AGGRESSIVE -> "Fixes most typos as you hit space. Backspace right after undoes a fix."
                })
            }
        }
        Group {
            PrefSwitch(prefs, Settings.PREF_GESTURE_INPUT, Defaults.PREF_GESTURE_INPUT, "Swipe typing", "Glide through the letters of a word")
            GroupDivider()
            PrefSwitch(prefs, Settings.PREF_AUTO_CAP, Defaults.PREF_AUTO_CAP, "Capitalize sentences", "The first letter after a period")
            GroupDivider()
            PrefSwitch(prefs, Settings.PREF_KEY_USE_DOUBLE_SPACE_PERIOD, Defaults.PREF_KEY_USE_DOUBLE_SPACE_PERIOD, "Double space for a period")
            GroupDivider()
            PrefSwitch(prefs, Settings.PREF_SHOW_NUMBER_ROW, Defaults.PREF_SHOW_NUMBER_ROW, "Number row", "Digits above the letters", reload = true)
            GroupDivider()
            PrefSwitch(prefs, Settings.PREF_SHOW_EMOJI_KEY, Defaults.PREF_SHOW_EMOJI_KEY, "Emoji key", "Between the comma and the space bar", reload = true)
        }
        GroupLabel("Gestures")
        Group {
            var cursor by remember { mutableStateOf(prefs.getString(Settings.PREF_SPACE_HORIZONTAL_SWIPE, Defaults.PREF_SPACE_HORIZONTAL_SWIPE)
                == KeyboardActionListener.SwipeAction.MOVE_CURSOR.name) }
            Item("Move the cursor", "Slide along the space bar", onClick = null, chevron = false, trailing = {
                MurmurSwitch(cursor) {
                    cursor = it
                    prefs.edit { putString(Settings.PREF_SPACE_HORIZONTAL_SWIPE,
                        (if (it) KeyboardActionListener.SwipeAction.MOVE_CURSOR else KeyboardActionListener.SwipeAction.NONE).name) }
                }
            })
            GroupDivider()
            var words by remember { mutableStateOf(prefs.getInt(Settings.PREF_DELETE_SWIPE, Defaults.PREF_DELETE_SWIPE) != 0) }
            Item("Delete whole words", "Slide left from backspace", chevron = false, trailing = {
                MurmurSwitch(words) { words = it; prefs.edit { putInt(Settings.PREF_DELETE_SWIPE, if (it) dev.murmur.settings.MurmurDefaults.DELETE_SWIPE_WORDS else 0) } }
            })
            GroupDivider()
            PrefSwitch(prefs, Settings.PREF_SPACE_TO_CHANGE_LANG, Defaults.PREF_SPACE_TO_CHANGE_LANG, "Switch keyboards", "Long-press the space bar")
        }
        GroupLabel("Suggestions")
        Group {
            PrefSwitch(prefs, Settings.PREF_KEY_USE_PERSONALIZED_DICTS, Defaults.PREF_KEY_USE_PERSONALIZED_DICTS, "Learn from your typing", "Kept on this phone")
            GroupDivider()
            PrefSwitch(prefs, Settings.PREF_USE_APPS, Defaults.PREF_USE_APPS, "Suggest app names", "From apps installed on this phone")
            GroupDivider()
            PrefSwitch(prefs, Settings.PREF_BLOCK_POTENTIALLY_OFFENSIVE, Defaults.PREF_BLOCK_POTENTIALLY_OFFENSIVE, "Hide offensive words")
        }
        GroupLabel("Feel")
        Group {
            PrefSwitch(prefs, Settings.PREF_VIBRATE_ON, Defaults.PREF_VIBRATE_ON, "Vibrate on key press", "A light tap")
            GroupDivider()
            PrefSwitch(prefs, Settings.PREF_SOUND_ON, Defaults.PREF_SOUND_ON, "Key sound")
        }
        Group { Item("Languages", "English and Spanish together, no switching", chevron = false) }
    }
}

// ---------------------------------------------------------------- Clipboard

private val KEEP = listOf(15 to "15 min", 60 to "1 hour", 120 to "2 hours", 121 to "Always")

@Composable
fun ClipboardScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = ctx.prefs()
    var enabled by remember { mutableStateOf(prefs.getBoolean(Settings.PREF_ENABLE_CLIPBOARD_HISTORY, Defaults.PREF_ENABLE_CLIPBOARD_HISTORY)) }
    var keep by remember {
        val m = prefs.getInt(Settings.PREF_CLIPBOARD_HISTORY_RETENTION_TIME, Defaults.PREF_CLIPBOARD_HISTORY_RETENTION_TIME)
        mutableStateOf(KEEP.minBy { kotlin.math.abs(it.first - (if (m <= 0) 121 else m)) })
    }
    var cleared by remember { mutableStateOf(false) }
    ScreenFrame("Clipboard", onBack) {
        Group {
            PrefSwitch(prefs, Settings.PREF_ENABLE_CLIPBOARD_HISTORY, Defaults.PREF_ENABLE_CLIPBOARD_HISTORY, "Clipboard history",
                "Copied text in the keyboard's clipboard panel") { enabled = it }
        }
        if (enabled) {
            GroupLabel("Keep clips for")
            Group {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Segmented(KEEP, keep, { it.second }) { k ->
                        keep = k
                        prefs.edit { putInt(Settings.PREF_CLIPBOARD_HISTORY_RETENTION_TIME, k.first) }
                        ClipboardDao.getInstance(ctx)?.clearOldClips(true)
                    }
                    Hint("Pinned clips stay until you unpin them. Passwords copied from a password manager are never saved.")
                }
            }
            Group {
                Item("Clear unpinned clips", if (cleared) "Cleared" else "Pinned clips stay", chevron = false, trailing = {
                    SmallButton("Clear", tonal = true) { ClipboardDao.getInstance(ctx)?.clearNonPinned(); cleared = true }
                })
            }
        }
        Group {
            PrefSwitch(prefs, Settings.PREF_SUGGEST_CLIPBOARD_CONTENT, Defaults.PREF_SUGGEST_CLIPBOARD_CONTENT, "Offer what you just copied",
                "As a paste chip in the suggestion strip")
        }
    }
}

// ---------------------------------------------------------------- Emoji

private val TONES = listOf("" to "Default", "🏻" to "Light", "🏼" to "Medium light",
    "🏽" to "Medium", "🏾" to "Medium dark", "🏿" to "Dark")

@Composable
fun EmojiScreen(onBack: () -> Unit) {
    val prefs = LocalContext.current.prefs()
    val c = LocalSettingsColors.current
    var tone by remember { mutableStateOf(prefs.getString(Settings.PREF_EMOJI_SKIN_TONE, Defaults.PREF_EMOJI_SKIN_TONE) ?: "") }
    var recentsCleared by remember { mutableStateOf(false) }
    ScreenFrame("Emoji", onBack) {
        GroupLabel("Skin tone")
        Group {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TONES.forEach { (mod, name) ->
                        val on = mod == tone
                        Box(Modifier.size(44.dp).clip(CircleShape)
                            .background(if (on) c.tint else c.surface)
                            .border(if (on) 2.dp else 1.dp, if (on) c.accent else c.line, CircleShape)
                            .clickable(onClickLabel = name) {
                                tone = mod
                                prefs.edit { putString(Settings.PREF_EMOJI_SKIN_TONE, mod) }
                                KeyboardSwitcher.getInstance().setThemeNeedsReload()
                            }, contentAlignment = Alignment.Center) {
                            Text("👋$mod", fontSize = 20.sp)
                        }
                    }
                }
                Hint("${TONES.first { it.first == tone }.second}. Used for every emoji that has skin tones. Long-press one in the panel to pick another just once.")
            }
        }
        Group {
            Item("Clear recent emoji", if (recentsCleared) "Cleared" else "Or long-press the clock in the emoji panel", chevron = false, trailing = {
                SmallButton("Clear", tonal = true) {
                    prefs.edit { remove(Settings.PREF_RECENT_EMOJIS) }
                    KeyboardSwitcher.getInstance().setThemeNeedsReload()
                    recentsCleared = true
                }
            })
        }
    }
}

// ---------------------------------------------------------------- Tablet layout

private val GAPS = listOf(0.6f to "Narrow", 1.0f to "Medium", 1.6f to "Wide")

@Composable
fun TabletScreen(onBack: () -> Unit) {
    val prefs = LocalContext.current.prefs()
    val gapKey = { i: Int -> createPrefKeyForBooleanSettings(Settings.PREF_SPLIT_SPACER_SCALE_PREFIX, i, 2) }
    var gap by remember { mutableStateOf(GAPS.minBy { kotlin.math.abs(it.first - prefs.getFloat(gapKey(1), 1f)) }) }
    ScreenFrame("Tablet layout", onBack) {
        Group {
            PrefSwitch(prefs, Settings.PREF_ENABLE_SPLIT_KEYBOARD_LANDSCAPE, Defaults.PREF_ENABLE_SPLIT_KEYBOARD, "Split in landscape",
                "Two thumb zones when the phone is sideways", reload = true)
            GroupDivider()
            PrefSwitch(prefs, Settings.PREF_ENABLE_SPLIT_KEYBOARD, Defaults.PREF_ENABLE_SPLIT_KEYBOARD, "Split in portrait",
                "Useful on the open inner screen", reload = true)
        }
        GroupLabel("Gap between halves")
        Group {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Segmented(GAPS, gap, { it.second }) { g ->
                    gap = g
                    prefs.edit { (0 until 4).forEach { putFloat(gapKey(it), g.first) } }
                    KeyboardSwitcher.getInstance().setThemeNeedsReload()
                }
                Hint("A wider gap moves each half closer to the edge, under your thumbs.")
            }
        }
    }
}

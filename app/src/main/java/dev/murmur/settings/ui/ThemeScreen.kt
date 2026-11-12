package dev.murmur.settings.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import dev.murmur.design.MurmurTokens
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.keyboard.KeyboardTheme
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.settings.createPrefKeyForBooleanSettings
import helium314.keyboard.latin.utils.prefs

enum class ThemeMode(val label: String) { LIGHT("Light"), DARK("Dark"), SYSTEM("System") }

private val HEIGHTS = listOf(0.85f to "Compact", 0.92f to "Small", 1.0f to "Medium", 1.08f to "Large", 1.16f to "Tall")

/** Theme (mockup "Theme"): a live keyboard preview, light/dark/system, accent, key borders and height. */
@Composable
fun ThemeScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.prefs() }
    var mode by remember { mutableStateOf(readMode(prefs)) }
    var accent by remember { mutableIntStateOf(MurmurTokens.accent(prefs)) }
    var borders by remember { mutableStateOf(prefs.getBoolean(Settings.PREF_THEME_KEY_BORDERS, Defaults.PREF_THEME_KEY_BORDERS)) }
    var heightIndex by remember { mutableFloatStateOf(nearestHeight(prefs.getFloat(heightKey(0), 1f)).toFloat()) }
    val reload = { KeyboardSwitcher.getInstance().setThemeNeedsReload() }
    val systemDark = isSystemInDarkTheme()
    val previewDark = mode == ThemeMode.DARK || (mode == ThemeMode.SYSTEM && systemDark)

    Column(Modifier.verticalScroll(rememberScrollState())) {
        TopBar("Theme", onBack)
        Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            KeyboardPreview(previewDark, Color(accent), borders, HEIGHTS[heightIndex.toInt()].first)
            Group {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Segmented(ThemeMode.entries, mode, { it.label }) { mode = it; writeMode(prefs, it); reload() }
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Accent", fontFamily = RobotoFlex, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = LocalSettingsColors.current.ink)
                        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            MurmurTokens.ACCENTS.forEach { (name, color) ->
                                Swatch(Color(color), name, selected = color == accent) {
                                    accent = color; prefs.edit { putInt(MurmurTokens.PREF_ACCENT, color) }; reload()
                                }
                            }
                        }
                        Hint(MurmurTokens.ACCENTS.first { it.second == accent }.first +
                            ". Every accent keeps the white Enter and mic icons readable on light and dark keys.")
                    }
                }
            }
            Group {
                Item("Key borders", "Separate each key", chevron = false, trailing = {
                    MurmurSwitch(borders) { borders = it; prefs.edit { putBoolean(Settings.PREF_THEME_KEY_BORDERS, it) }; reload() }
                })
                GroupDivider()
                Item("Key height", HEIGHTS[heightIndex.toInt()].second, below = {
                    MurmurSlider(heightIndex, 0f..(HEIGHTS.size - 1).toFloat(), steps = HEIGHTS.size - 2,
                        onChange = { heightIndex = it },
                        onDone = { writeHeight(prefs, HEIGHTS[heightIndex.toInt()].first); reload() })
                })
            }
        }
    }
}

@Composable
private fun Swatch(color: Color, name: String, selected: Boolean, onClick: () -> Unit) {
    val c = LocalSettingsColors.current
    Box(Modifier.size(36.dp).then(if (selected) Modifier.border(2.dp, c.accent, CircleShape).padding(4.dp) else Modifier)
        .clip(CircleShape).background(color).clickable(onClickLabel = name, onClick = onClick), contentAlignment = Alignment.Center) {
        if (selected) Icon(painterResource(R.drawable.ic_murmur_check), name, tint = Color.White, modifier = Modifier.size(14.dp))
    }
}

/** A small keyboard drawn with Murmur's colors, so changes show before you open a text field. */
@Composable
private fun KeyboardPreview(dark: Boolean, accent: Color, borders: Boolean, heightScale: Float) {
    val bg = Color(if (dark) MurmurTokens.Dark.BACKGROUND else MurmurTokens.Light.BACKGROUND)
    val key = Color(if (dark) MurmurTokens.Dark.KEY else MurmurTokens.Light.KEY)
    val fn = Color(if (dark) MurmurTokens.Dark.FUNCTION_KEY else MurmurTokens.Light.FUNCTION_KEY)
    val ink = Color(if (dark) MurmurTokens.Dark.TEXT else MurmurTokens.Light.TEXT)
    val sub = Color(if (dark) MurmurTokens.Dark.SUBTLE else MurmurTokens.Light.SUBTLE)
    val onAccent = if (accent.luminance() > 0.3f) Color(0xFF0D1336) else Color.White
    val rowH = (30 * heightScale).dp
    val keyBg = if (borders) key else Color.Transparent
    val fnBg = if (borders) fn else Color.Transparent
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(bg).padding(horizontal = 4.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)) {
        // strip: three suggestions, middle bold, and the mic pill
        Row(Modifier.fillMaxWidth().height(28.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf("the" to false, "and" to true, "for" to false).forEach { (w, bold) ->
                Text(w, Modifier.weight(1f), fontFamily = RobotoFlex, fontSize = 12.sp, color = ink, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal)
            }
            Box(Modifier.width(36.dp).height(24.dp).clip(RoundedCornerShape(12.dp)).background(accent), contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ic_murmur_mic), null, tint = onAccent, modifier = Modifier.size(14.dp))
            }
            Spacer(Modifier.width(4.dp))
        }
        @Composable
        fun keys(labels: String, inset: Float = 0f, lead: String? = null, trail: Boolean = false) =
            Row(Modifier.fillMaxWidth().height(rowH).padding(horizontal = (inset * 14).dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (lead != null) PreviewKey(lead, fnBg, ink, 1.5f, small = true)
                labels.forEach { PreviewKey(it.toString(), keyBg, ink) }
                if (trail) PreviewKey("⌫", fnBg, ink, 1.5f, small = true)
            }
        keys("qwertyuiop")
        keys("asdfghjkl", inset = 1f)
        keys("zxcvbnm", lead = "⇧", trail = true)
        Row(Modifier.fillMaxWidth().height(rowH), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            PreviewKey("?123", fnBg, ink, 1.5f, small = true)
            PreviewKey(",", keyBg, ink)
            PreviewKey("English", keyBg, sub, 5f, small = true)
            PreviewKey(".", keyBg, ink)
            PreviewKey("↵", accent, onAccent, 1.5f)
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.PreviewKey(label: String, bg: Color, fg: Color, weight: Float = 1f, small: Boolean = false) {
    Box(Modifier.weight(weight).fillMaxHeight()
        .clip(RoundedCornerShape(5.dp)).background(bg), contentAlignment = Alignment.Center) {
        Text(label, fontFamily = RobotoFlex, fontSize = if (small) 9.sp else 12.sp, color = fg, maxLines = 1)
    }
}

private fun readMode(prefs: android.content.SharedPreferences): ThemeMode = when {
    prefs.getBoolean(Settings.PREF_THEME_DAY_NIGHT, Defaults.PREF_THEME_DAY_NIGHT) -> ThemeMode.SYSTEM
    prefs.getString(Settings.PREF_THEME_COLORS, Defaults.PREF_THEME_COLORS) == KeyboardTheme.THEME_MURMUR_DARK -> ThemeMode.DARK
    else -> ThemeMode.LIGHT
}

/** System follows the phone (light theme by day, dark at night); Light and Dark pin one Murmur theme. */
private fun writeMode(prefs: android.content.SharedPreferences, mode: ThemeMode) = prefs.edit {
    putBoolean(Settings.PREF_THEME_DAY_NIGHT, mode == ThemeMode.SYSTEM)
    putString(Settings.PREF_THEME_COLORS, if (mode == ThemeMode.DARK) KeyboardTheme.THEME_MURMUR_DARK else KeyboardTheme.THEME_MURMUR_LIGHT)
    putString(Settings.PREF_THEME_COLORS_NIGHT, KeyboardTheme.THEME_MURMUR_DARK)
}

/** HeliBoard stores height per (landscape, folded); one Murmur slider sets all four. */
private fun heightKey(index: Int) = createPrefKeyForBooleanSettings(Settings.PREF_KEYBOARD_HEIGHT_SCALE_PREFIX, index, 2)
private fun writeHeight(prefs: android.content.SharedPreferences, scale: Float) = prefs.edit { (0 until 4).forEach { putFloat(heightKey(it), scale) } }
private fun nearestHeight(scale: Float) = HEIGHTS.indices.minBy { kotlin.math.abs(HEIGHTS[it].first - scale) }

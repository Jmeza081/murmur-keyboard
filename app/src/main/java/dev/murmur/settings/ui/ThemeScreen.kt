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
import helium314.keyboard.latin.utils.prefs

enum class ThemeMode(val label: String) { LIGHT("Light"), DARK("Dark"), BLACK("Black"), SYSTEM("System") }

private val HEIGHTS = dev.murmur.settings.KeyHeight.STEPS

/** Theme (mockup "Theme"): a live keyboard preview, light/dark/system, accent, key borders and height. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ThemeScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.prefs() }
    var mode by remember { mutableStateOf(readMode(prefs)) }
    var named by remember { mutableStateOf(dev.murmur.design.MurmurThemes.current(prefs)) }
    var accent by remember { mutableIntStateOf(MurmurTokens.accent(prefs)) }
    var borders by remember { mutableStateOf(prefs.getBoolean(Settings.PREF_THEME_KEY_BORDERS, Defaults.PREF_THEME_KEY_BORDERS)) }
    var heightIndex by remember { mutableFloatStateOf(dev.murmur.settings.KeyHeight.readIndex(prefs, ctx.resources).toFloat()) }
    val reload = { KeyboardSwitcher.getInstance().setThemeNeedsReload() }
    val systemDark = isSystemInDarkTheme()
    val previewMode = if (mode == ThemeMode.SYSTEM) (if (systemDark) ThemeMode.DARK else ThemeMode.LIGHT) else mode

    Column(Modifier.verticalScroll(rememberScrollState())) {
        TopBar("Theme", onBack)
        Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            KeyboardPreview(previewMode, named?.accent ?: accent, borders, HEIGHTS[heightIndex.toInt()].first, named)
            // Named themes carry their own colors and accent; they also color dictation and the power-button sheet
            Group {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Themes", fontFamily = RobotoFlex, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = LocalSettingsColors.current.ink)
                    androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        item { ThemeCard("Classic", "Light · Dark · Black", MurmurTokens.Dark.BACKGROUND, MurmurTokens.Dark.KEY, accent, named == null) {
                            named = null; writeMode(prefs, mode); reload() } }
                        items(dev.murmur.design.MurmurThemes.ALL.size) { i ->
                            val t = dev.murmur.design.MurmurThemes.ALL[i]
                            ThemeCard(t.name, t.tag, t.background, t.key, t.accent, named?.id == t.id) {
                                named = t; writeNamed(prefs, t); reload() }
                        }
                    }
                }
            }
            if (named == null) Group {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Segmented(ThemeMode.entries, mode, { it.label }) { mode = it; writeMode(prefs, it); reload() }
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Accent", fontFamily = RobotoFlex, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = LocalSettingsColors.current.ink)
                        // nine accents: the original five, then the four added with Black (design/clipboard-themes.html)
                        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            MurmurTokens.ACCENTS.forEach { (name, color) ->
                                Swatch(Color(color), name, selected = color == accent) {
                                    accent = color; prefs.edit { putInt(MurmurTokens.PREF_ACCENT, color) }; reload()
                                }
                            }
                        }
                        Hint((MurmurTokens.ACCENTS.firstOrNull { it.second == accent }?.first ?: "Custom") +
                            ". Every accent keeps the white Enter and mic icons readable on light, dark and black keys.")
                    }
                }
            }
            Group {
                Item("Key borders", "Separate each key", chevron = false, trailing = {
                    MurmurSwitch(borders) { borders = it; prefs.edit { putBoolean(Settings.PREF_THEME_KEY_BORDERS, it) }; reload() }
                })
                GroupDivider()
                Item("Key height", HEIGHTS[heightIndex.toInt()].second + (dev.murmur.settings.KeyHeight.screenName(ctx.resources)?.let { " on the $it" } ?: ""), below = {
                    MurmurSlider(heightIndex, 0f..(HEIGHTS.size - 1).toFloat(), steps = HEIGHTS.size - 2,
                        onChange = { heightIndex = it },
                        onDone = { dev.murmur.settings.KeyHeight.write(prefs, ctx.resources, heightIndex.toInt()); reload() })
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

/** One named theme: its ground, a key and its accent, with the name below. */
@Composable
private fun ThemeCard(name: String, tag: String, bg: Int, key: Int, accent: Int, selected: Boolean, onClick: () -> Unit) {
    val c = LocalSettingsColors.current
    Column(Modifier.width(112.dp).clip(RoundedCornerShape(16.dp)).clickable(onClickLabel = name, onClick = onClick)
        .border(if (selected) 2.dp else 1.dp, if (selected) c.accent else c.line, RoundedCornerShape(16.dp)).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(10.dp)).background(Color(bg)).padding(6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Bottom) {
            Box(Modifier.weight(1f).height(18.dp).clip(RoundedCornerShape(4.dp)).background(Color(key)))
            Box(Modifier.weight(1f).height(18.dp).clip(RoundedCornerShape(4.dp)).background(Color(key)))
            Box(Modifier.weight(1.4f).height(18.dp).clip(RoundedCornerShape(4.dp)).background(Color(accent)))
        }
        Text(name, fontFamily = RobotoFlex, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = c.ink, maxLines = 1)
        Text(tag, fontFamily = RobotoFlex, fontSize = 11.sp, color = c.sub, maxLines = 1)
    }
}

/** A small keyboard drawn with Murmur's colors, so changes show before you open a text field. */
@Composable
private fun KeyboardPreview(mode: ThemeMode, accentInt: Int, borders: Boolean, heightScale: Float, named: dev.murmur.design.MurmurThemes.Palette? = null) {
    val accent = Color(accentInt)
    val dark = mode == ThemeMode.DARK
    val black = mode == ThemeMode.BLACK
    val bg = Color(named?.background ?: if (black) MurmurTokens.Black.background(accentInt) else if (dark) MurmurTokens.Dark.BACKGROUND else MurmurTokens.Light.BACKGROUND)
    val key = Color(named?.key ?: if (black) MurmurTokens.Black.key(accentInt) else if (dark) MurmurTokens.Dark.KEY else MurmurTokens.Light.KEY)
    val fn = Color(named?.functionKey ?: if (black) MurmurTokens.Black.functionKey(accentInt) else if (dark) MurmurTokens.Dark.FUNCTION_KEY else MurmurTokens.Light.FUNCTION_KEY)
    val ink = Color(named?.text ?: if (black) MurmurTokens.Black.text(accentInt) else if (dark) MurmurTokens.Dark.TEXT else MurmurTokens.Light.TEXT)
    val sub = Color(named?.subtle ?: if (black) MurmurTokens.Black.subtle(accentInt) else if (dark) MurmurTokens.Dark.SUBTLE else MurmurTokens.Light.SUBTLE)
    val onAccent = Color(dev.murmur.design.MurmurThemes.onAccent(accentInt))
    val rowH = (44 * heightScale).dp
    val keyBg = if (borders) key else Color.Transparent
    val fnBg = if (borders) fn else Color.Transparent
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(bg).padding(horizontal = 5.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)) {
        // strip: three suggestions, middle bold, and the mic pill
        Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf("the" to false, "and" to true, "for" to false).forEach { (w, bold) ->
                Text(w, Modifier.weight(1f), fontFamily = RobotoFlex, fontSize = 16.sp, color = ink, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal)
            }
            Box(Modifier.width(52.dp).height(34.dp).clip(RoundedCornerShape(17.dp)).background(accent), contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ic_murmur_mic), null, tint = onAccent, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(4.dp))
        }
        @Composable
        fun keys(labels: String, inset: Float = 0f, lead: String? = null, trail: Boolean = false) =
            Row(Modifier.fillMaxWidth().height(rowH).padding(horizontal = (inset * 14).dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                if (lead != null) PreviewKey("", fnBg, ink, 1.5f, icon = R.drawable.sym_keyboard_shift_lxx)
                labels.forEach { PreviewKey(it.toString(), keyBg, ink) }
                if (trail) PreviewKey("", fnBg, ink, 1.5f, icon = R.drawable.sym_keyboard_delete_lxx)
            }
        keys("qwertyuiop")
        keys("asdfghjkl", inset = 1f)
        keys("zxcvbnm", lead = "⇧", trail = true)
        Row(Modifier.fillMaxWidth().height(rowH), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            PreviewKey("?123", fnBg, ink, 1.5f, small = true)
            PreviewKey(",", keyBg, ink)
            PreviewKey("English", keyBg, sub, 5f, small = true)
            PreviewKey(".", keyBg, ink)
            PreviewKey("", accent, onAccent, 1.5f, icon = R.drawable.sym_keyboard_return_lxx)
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.PreviewKey(label: String, bg: Color, fg: Color, weight: Float = 1f, small: Boolean = false,
                                                                    @androidx.annotation.DrawableRes icon: Int? = null) {
    Box(Modifier.weight(weight).fillMaxHeight()
        .clip(RoundedCornerShape(7.dp)).background(bg), contentAlignment = Alignment.Center) {
        // the keyboard's own Material icons, sized like a letter (about 24 dp on a 44 dp key)
        if (icon != null) Icon(painterResource(icon), null, tint = fg, modifier = Modifier.size(24.dp))
        else Text(label, fontFamily = RobotoFlex, fontSize = if (small) 15.sp else 20.sp, color = fg, maxLines = 1)
    }
}

private fun readMode(prefs: android.content.SharedPreferences): ThemeMode = when {
    prefs.getBoolean(Settings.PREF_THEME_DAY_NIGHT, Defaults.PREF_THEME_DAY_NIGHT) -> ThemeMode.SYSTEM
    prefs.getString(Settings.PREF_THEME_COLORS, Defaults.PREF_THEME_COLORS) == KeyboardTheme.THEME_MURMUR_DARK -> ThemeMode.DARK
    prefs.getString(Settings.PREF_THEME_COLORS, Defaults.PREF_THEME_COLORS) == KeyboardTheme.THEME_MURMUR_BLACK -> ThemeMode.BLACK
    else -> ThemeMode.LIGHT
}

/** System follows the phone (light theme by day, dark at night); Light, Dark and Black pin one Murmur theme. */
private fun writeMode(prefs: android.content.SharedPreferences, mode: ThemeMode) = prefs.edit {
    putBoolean(Settings.PREF_THEME_DAY_NIGHT, mode == ThemeMode.SYSTEM)
    putString(Settings.PREF_THEME_COLORS, when (mode) {
        ThemeMode.DARK -> KeyboardTheme.THEME_MURMUR_DARK
        ThemeMode.BLACK -> KeyboardTheme.THEME_MURMUR_BLACK
        else -> KeyboardTheme.THEME_MURMUR_LIGHT
    })
    putString(Settings.PREF_THEME_COLORS_NIGHT, KeyboardTheme.THEME_MURMUR_DARK)
}

/** A named theme pins its own colors by day and night. */
private fun writeNamed(prefs: android.content.SharedPreferences, t: dev.murmur.design.MurmurThemes.Palette) = prefs.edit {
    putBoolean(Settings.PREF_THEME_DAY_NIGHT, false)
    putString(Settings.PREF_THEME_COLORS, t.id)
}

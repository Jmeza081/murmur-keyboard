package dev.murmur.speech

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.murmur.settings.ui.DictationScreen
import dev.murmur.settings.ui.HomeScreen
import dev.murmur.settings.ui.LocalSettingsColors
import dev.murmur.settings.ui.MurmurSettingsTheme
import dev.murmur.settings.ui.Screen
import dev.murmur.settings.ui.PowerScreen
import dev.murmur.settings.ui.SetupScreen
import dev.murmur.settings.ui.LocalResumeTick
import androidx.compose.runtime.CompositionLocalProvider

/**
 * Murmur's settings app (design/mockups.html, section 06): the launcher icon and the keyboard's gear.
 * Opens on the Dictation screen when [EXTRA_SCREEN] says so (the mic key's long-press, first dictation).
 */
class ModelActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        ModelManager.refresh(this)
        val start = intent.getStringExtra(EXTRA_SCREEN)?.let { runCatching { Screen.valueOf(it) }.getOrNull() }
            ?: if (!dev.murmur.settings.SetupState.essentialsDone(this) && !dev.murmur.settings.SetupState.skipped(this)) Screen.SETUP else Screen.HOME
        setContent {
            MurmurSettingsTheme {
                var screen by rememberSaveable { mutableStateOfScreen(start) }
                BackHandler(enabled = screen != Screen.HOME) {
                    if (screen == Screen.SETUP) dev.murmur.settings.SetupState.setSkipped(this@ModelActivity)
                    screen = Screen.HOME
                }
                CompositionLocalProvider(LocalResumeTick provides resumeTick.intValue) {
                    Box(Modifier.fillMaxSize().background(LocalSettingsColors.current.bg).safeDrawingPadding()) {
                        when (screen) {
                            Screen.HOME -> HomeScreen(open = { screen = it }, openLegacy = ::openLegacy)
                            Screen.DICTATION -> DictationScreen(onBack = { screen = Screen.HOME })
                            Screen.POWER -> PowerScreen(onBack = { screen = Screen.HOME })
                            Screen.THEME -> dev.murmur.settings.ui.ThemeScreen(onBack = { screen = Screen.HOME })
                            Screen.TYPING -> dev.murmur.settings.ui.TypingScreen(onBack = { screen = Screen.HOME })
                            Screen.CLIPBOARD -> dev.murmur.settings.ui.ClipboardScreen(onBack = { screen = Screen.HOME })
                            Screen.EMOJI -> dev.murmur.settings.ui.EmojiScreen(onBack = { screen = Screen.HOME })
                            Screen.TABLET -> dev.murmur.settings.ui.TabletScreen(onBack = { screen = Screen.HOME })
                            Screen.DICTIONARY -> dev.murmur.settings.ui.DictionaryScreen(onBack = { screen = Screen.HOME })
                            Screen.SETUP -> SetupScreen(onDone = { dev.murmur.settings.SetupState.setSkipped(this@ModelActivity); screen = Screen.HOME })
                        }
                    }
                }
            }
        }
    }

    private val resumeTick = androidx.compose.runtime.mutableIntStateOf(0)

    override fun onResume() {
        super.onResume()
        ModelManager.refresh(this)
        resumeTick.intValue++ // re-read keyboard, assistant and accessibility state after visiting Android's settings
    }

    private fun openLegacy() = startActivity(Intent(this, helium314.keyboard.settings.SettingsActivity2::class.java))

    companion object {
        const val EXTRA_SCREEN = "screen"
    }
}

private fun mutableStateOfScreen(s: Screen) = androidx.compose.runtime.mutableStateOf(s)

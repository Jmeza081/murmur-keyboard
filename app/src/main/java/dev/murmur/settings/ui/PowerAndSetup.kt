package dev.murmur.settings.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.murmur.settings.DictationPrefs
import dev.murmur.settings.SetupState
import dev.murmur.speech.ModelManager
import helium314.keyboard.latin.R

/** Bumped every time the activity resumes, so screens re-read Android's settings after you come back. */
val LocalResumeTick = compositionLocalOf { 0 }

/** Power button settings (mockup "Power button"): the assistant switch and where text goes. */
@Composable
fun PowerScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    LocalResumeTick.current // re-read on resume
    val assistant = SetupState.isAssistant(ctx)
    val keyboard = SetupState.keyboardIsDefault(ctx)
    val a11y = SetupState.accessibilityOn(ctx)
    val silence = "%.1f".format(DictationPrefs.silenceMs(ctx) / 1000f)
    Column(Modifier.verticalScroll(rememberScrollState())) {
        TopBar("Power button", onBack)
        Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Group {
                Item("Dictate with power button",
                    if (assistant) "Murmur is the digital assistant" else "Set Murmur as the digital assistant",
                    onClick = { SetupState.openAssistantSettings(ctx) }, chevron = false,
                    trailing = { MurmurSwitch(assistant) { SetupState.openAssistantSettings(ctx) } })
            }
            GroupLabel("How it listens")
            Group {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                    Hint("Hold the power button to open the sheet, then speak. It finishes when you pause for $silence seconds, " +
                        "or when you tap Send. Android doesn't tell apps when the button is let go, so it can't stop on release.")
                }
            }
            GroupLabel("Where text goes")
            Group {
                DeliveryItem("1. Through the keyboard", if (keyboard) "When it's open in a field" else "Needs Murmur as your keyboard",
                    R.drawable.ic_murmur_keyboard, ready = keyboard) { SetupState.openKeyboardPicker(ctx) }
                GroupDivider()
                DeliveryItem("2. Into the focused field", if (a11y) "With any keyboard, through accessibility" else "Needs accessibility access",
                    R.drawable.ic_murmur_a11y, ready = a11y, action = "Allow") { SetupState.openAccessibility(ctx) }
                GroupDivider()
                DeliveryItem("3. Copy to clipboard", "When nothing else works", R.drawable.ic_murmur_copy, ready = true) {}
            }
        }
    }
}

@Composable
private fun DeliveryItem(title: String, value: String, @DrawableRes icon: Int, ready: Boolean, action: String = "Set up", onFix: () -> Unit) {
    val c = LocalSettingsColors.current
    Item(title, value, icon, chevron = false, trailing = {
        if (ready) Icon(painterResource(R.drawable.ic_murmur_check), "Ready", tint = c.accent, modifier = Modifier.size(22.dp))
        else SmallButton(action, tonal = true, onClick = onFix)
    })
}

/** First run (mockup "Set up your keyboard"): each step opens the right Android screen and ticks itself when you return. */
@Composable
fun SetupScreen(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val c = LocalSettingsColors.current
    LocalResumeTick.current
    val status by ModelManager.status.collectAsState()
    val enabled = SetupState.keyboardEnabled(ctx)
    val isDefault = SetupState.keyboardIsDefault(ctx)
    val model = status is ModelManager.Status.Installed
    val assistant = SetupState.isAssistant(ctx)
    val a11y = SetupState.accessibilityOn(ctx)
    // the first unfinished step is "now"
    val steps = listOf(enabled, isDefault, model, assistant, a11y)
    val now = steps.indexOfFirst { !it }
    Column(Modifier.verticalScroll(rememberScrollState())) {
        BigTitle("Set up your keyboard", "Five steps. About 3 minutes plus the model download.")
        Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Group {
                SetupStep(1, "Turn on the keyboard", if (enabled) "Done in system settings" else "In Android's keyboard list", enabled, now == 0) {
                    SetupState.openKeyboardList(ctx) }
                GroupDivider()
                SetupStep(2, "Make it your keyboard", if (isDefault) "Selected as the default" else "Pick Murmur in the keyboard picker", isDefault, now == 1) {
                    SetupState.openKeyboardPicker(ctx) }
                GroupDivider()
                val s = status
                SetupStep(3, "Download the speech model", when (s) {
                    is ModelManager.Status.Installed -> "Installed on this phone"
                    is ModelManager.Status.Downloading -> "%,d of %,d MB · keep this screen open".format(s.doneBytes / 1_000_000, s.totalBytes / 1_000_000)
                    is ModelManager.Status.Failed -> s.message
                    else -> "%,d MB · Wi-Fi recommended".format(ModelManager.TOTAL_BYTES / 1_000_000)
                }, model, now == 2, action = if (s is ModelManager.Status.Downloading) null else "Download",
                    progress = (s as? ModelManager.Status.Downloading)?.let { it.doneBytes.toFloat() / it.totalBytes }) {
                    ModelManager.download(ctx) }
                GroupDivider()
                SetupStep(4, "Use power button for dictation", if (assistant) "Murmur is the digital assistant" else "Set as digital assistant", assistant, now == 3) {
                    SetupState.openAssistantSettings(ctx) }
                GroupDivider()
                SetupStep(5, "Type into any app", if (a11y) "On" else "Optional. Uses accessibility", a11y, now == 4) {
                    SetupState.openAccessibility(ctx) }
            }
            Text("Everything runs on this phone. The app uses the internet only to download the model.",
                fontFamily = RobotoFlex, fontSize = 12.5.sp, color = c.sub, lineHeight = 18.sp, modifier = Modifier.padding(horizontal = 8.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.End) {
                SmallButton(if (SetupState.essentialsDone(ctx)) "Done" else "Skip for now", tonal = !SetupState.essentialsDone(ctx), onClick = onDone)
            }
        }
    }
}

@Composable
private fun SetupStep(
    n: Int, title: String, value: String, done: Boolean, current: Boolean,
    action: String? = "Open", progress: Float? = null, onAction: () -> Unit,
) {
    val c = LocalSettingsColors.current
    Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = if (progress != null) Alignment.Top else Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        // check circle: done = filled accent with a tick, current = accent ring, else a neutral ring (mockup .check)
        Box(Modifier.size(32.dp).clip(CircleShape)
            .then(if (done) Modifier.background(c.accent) else Modifier.border(2.dp, if (current) c.accent else c.line, CircleShape)),
            contentAlignment = Alignment.Center) {
            if (done) Icon(painterResource(R.drawable.ic_murmur_check), null, tint = c.onAccent, modifier = Modifier.size(16.dp))
            else Text("$n", fontFamily = RobotoFlex, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (current) c.accent else c.sub)
        }
        Column(Modifier.weight(1f)) {
            Text(title, fontFamily = RobotoFlex, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = c.ink)
            Text(value, fontFamily = RobotoFlex, fontSize = 12.5.sp, color = c.sub, lineHeight = 17.sp)
            if (progress != null) LinearProgressIndicator(progress = { progress }, color = c.accent, trackColor = c.line,
                modifier = Modifier.padding(top = 8.dp).fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)))
        }
        if (!done && action != null) SmallButton(action, tonal = !current, onClick = onAction)
    }
}

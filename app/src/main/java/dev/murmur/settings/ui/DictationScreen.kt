package dev.murmur.settings.ui

import android.app.Activity
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.murmur.cleanup.Cleanup
import dev.murmur.dictation.WaveStyle
import dev.murmur.settings.DictationPrefs
import dev.murmur.speech.ModelManager
import helium314.keyboard.latin.R

/** Dictation settings (mockup "Dictation settings"). */
@Composable
fun DictationScreen(onBack: () -> Unit) {
    val c = LocalSettingsColors.current
    Column(Modifier.verticalScroll(rememberScrollState())) {
        TopBar("Dictation", onBack)
        Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ModelCard()
            CleanupGroup()
            GroupLabel("Listening")
            Group {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    WaveformChoice()
                }
                GroupDivider()
                SilenceItem()
                GroupDivider()
                Item("Language", "Detects English, Spanish and 23 more European languages automatically")
            }
            Text("Dictation runs entirely on this phone. The model is the only thing Murmur downloads.",
                fontFamily = RobotoFlex, fontSize = 12.5.sp, color = c.sub, lineHeight = 18.sp, modifier = Modifier.padding(horizontal = 8.dp))
        }
    }
}

@Composable
private fun ModelCard() {
    val ctx = LocalContext.current
    val c = LocalSettingsColors.current
    val status by ModelManager.status.collectAsState()
    var confirmMobileData by remember { mutableStateOf(false) }
    val downloading = status is ModelManager.Status.Downloading || status is ModelManager.Status.Checking
    LaunchedEffect(downloading) { // keep the screen on while downloading
        val w = (ctx as? Activity)?.window ?: return@LaunchedEffect
        if (downloading) w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else w.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.surface).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconTile(R.drawable.ic_murmur_cpu, size = 44, filled = true)
            Column(Modifier.weight(1f)) {
                Text("Parakeet TDT v3", fontFamily = RobotoFlex, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = c.ink)
                Text(statusLine(status), fontFamily = RobotoFlex, fontSize = 12.5.sp, color = c.sub)
            }
            if (status is ModelManager.Status.Installed) Badge("Active")
        }
        when (val s = status) {
            is ModelManager.Status.Installed -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Stat("Size", mb(s.bytes))
                Stat("Last run", DictationPrefs.lastDecodeMs(ctx).let { if (it < 0) "—" else "%.1f s".format(it / 1000.0) })
                Stat("Memory", "~1.4 GB") // measured on the Fold 8 in Phase 0
            }
            is ModelManager.Status.Downloading -> LinearProgressIndicator(
                progress = { s.doneBytes.toFloat() / s.totalBytes }, color = c.accent, trackColor = c.line,
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)))
            else -> Unit
        }
        when {
            downloading -> Row { SmallButton("Pause download", tonal = true) { ModelManager.cancel() } }
            status is ModelManager.Status.Installed -> Row { SmallButton("Delete model", tonal = true) { ModelManager.delete(ctx) } }
            confirmMobileData -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Hint("You're not on Wi-Fi. This uses ${mb(ModelManager.TOTAL_BYTES)} of mobile data.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SmallButton("Not now", tonal = true) { confirmMobileData = false }
                    SmallButton("Download anyway") { confirmMobileData = false; ModelManager.download(ctx) }
                }
            }
            else -> Row {
                SmallButton(if (status is ModelManager.Status.Failed) "Try again" else "Download · ${mb(ModelManager.TOTAL_BYTES)}") {
                    if (ModelManager.isOnUnmeteredNetwork(ctx)) ModelManager.download(ctx) else confirmMobileData = true
                }
            }
        }
    }
}

private fun statusLine(s: ModelManager.Status) = when (s) {
    is ModelManager.Status.Installed -> "Installed · INT8 · 25 languages"
    ModelManager.Status.NotInstalled -> "Not installed · INT8 · 25 languages"
    is ModelManager.Status.Downloading -> "Downloading ${mb(s.doneBytes)} of ${mb(s.totalBytes)}"
    ModelManager.Status.Checking -> "Checking files…"
    is ModelManager.Status.Failed -> s.message
}

@Composable
private fun CleanupGroup() {
    val ctx = LocalContext.current
    var level by remember { mutableStateOf(Cleanup.level(ctx)) }
    var extras by remember { mutableStateOf(Cleanup.extraFillers(ctx)) }
    var adding by remember { mutableStateOf(false) }
    GroupLabel("Cleanup")
    Group {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Segmented(Cleanup.Level.entries, level, { if (it == Cleanup.Level.OFF) "Off" else "Light" }) {
                level = it; Cleanup.setLevel(ctx, it)
            }
            Hint(when (level) {
                Cleanup.Level.OFF -> "Types exactly what the speech model heard."
                Cleanup.Level.LIGHT -> "Removes filler words, stutters and restarted phrases, applies \"scratch that\" and quick corrections " +
                    "like \"5, I mean 6\", and fixes spacing. Instant. Tap one of your words to remove it."
            })
            if (level == Cleanup.Level.LIGHT) TagRow(
                Cleanup.BUILT_IN_FILLERS + extras,
                onAdd = { adding = true },
                onRemove = { w -> if (w in extras) { extras = extras - w; Cleanup.setExtraFillers(ctx, extras) } },
            )
        }
    }
    if (adding) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text("Add a filler word") },
            text = { OutlinedTextField(text, { text = it }, singleLine = true, placeholder = { Text("basically") }) },
            confirmButton = { TextButton(onClick = {
                if (text.isNotBlank()) { extras = (extras + text.trim().lowercase()).distinct(); Cleanup.setExtraFillers(ctx, extras) }
                adding = false
            }) { Text("Add") } },
            dismissButton = { TextButton(onClick = { adding = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun WaveformChoice() {
    val ctx = LocalContext.current
    var style by remember { mutableStateOf(WaveStyle.get(ctx)) }
    Text("Waveform", fontFamily = RobotoFlex, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = LocalSettingsColors.current.ink)
    Segmented(WaveStyle.entries, style, { it.label }) { style = it; WaveStyle.set(ctx, it) }
}

@Composable
private fun SilenceItem() {
    val ctx = LocalContext.current
    var seconds by remember { mutableFloatStateOf(DictationPrefs.silenceMs(ctx) / 1000f) }
    Item("Stop after silence", "%.1f seconds".format(seconds), below = {
        MurmurSlider(seconds, 1f..4f, steps = 5, onChange = { seconds = it },
            onDone = { DictationPrefs.setSilenceMs(ctx, (seconds * 1000).toInt()) })
    })
}

private fun mb(bytes: Long) = "%,d MB".format(bytes / 1_000_000)

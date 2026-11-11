package dev.murmur.speech

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.runtime.rememberCoroutineScope
import com.google.mlkit.genai.common.DownloadStatus
import dev.murmur.cleanup.Cleanup
import dev.murmur.cleanup.SmartCleanup
import helium314.keyboard.latin.utils.Theme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Shows whether the speech model is installed, and downloads or deletes it. */
class ModelActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ModelManager.refresh(this)
        setContent { Theme { Surface(Modifier.fillMaxSize()) { ModelScreen() } } }
    }

    @Composable
    private fun ModelScreen() {
        val status by ModelManager.status.collectAsState()
        var confirmMobileData by remember { mutableStateOf(false) }
        val downloading = status is ModelManager.Status.Downloading || status is ModelManager.Status.Checking

        // Keep the screen on while downloading, so the phone doesn't sleep mid-download.
        LaunchedEffect(downloading) {
            if (downloading) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        Column(
            Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Dictation", style = MaterialTheme.typography.headlineMedium)
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Parakeet TDT v3", style = MaterialTheme.typography.titleMedium)
                    Text("INT8 · 25 European languages · ${mb(ModelManager.TOTAL_BYTES)}",
                        style = MaterialTheme.typography.bodyMedium)
                    when (val s = status) {
                        is ModelManager.Status.Installed -> Text("Installed · ${mb(s.bytes)} on this phone")
                        ModelManager.Status.NotInstalled -> Text("Not installed")
                        is ModelManager.Status.Downloading -> {
                            Text("Downloading ${mb(s.doneBytes)} of ${mb(s.totalBytes)}")
                            LinearProgressIndicator(
                                progress = { s.doneBytes.toFloat() / s.totalBytes },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        ModelManager.Status.Checking -> Text("Checking files…")
                        is ModelManager.Status.Failed -> Text(s.message, color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            when {
                downloading -> OutlinedButton(onClick = { ModelManager.cancel() }) { Text("Pause download") }
                status is ModelManager.Status.Installed ->
                    TextButton(onClick = { ModelManager.delete(this@ModelActivity) }) { Text("Delete model") }
                confirmMobileData -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("You're not on Wi-Fi. This uses ${mb(ModelManager.TOTAL_BYTES)} of mobile data.")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { confirmMobileData = false }) { Text("Not now") }
                        Button(onClick = { confirmMobileData = false; ModelManager.download(this@ModelActivity) }) {
                            Text("Download anyway")
                        }
                    }
                }
                else -> Button(onClick = {
                    if (ModelManager.isOnUnmeteredNetwork(this@ModelActivity)) ModelManager.download(this@ModelActivity)
                    else confirmMobileData = true
                }) { Text(if (status is ModelManager.Status.Failed) "Try again" else "Download") }
            }

            Text(
                "Dictation runs entirely on this phone. The model is the only thing Murmur downloads. " +
                    "A paused download picks up where it stopped.",
                style = MaterialTheme.typography.bodySmall,
            )

            CleanupSection()
        }
    }

    @Composable
    private fun CleanupSection() {
        var level by remember { mutableStateOf(Cleanup.level(this@ModelActivity)) }
        var nano by remember { mutableStateOf<SmartCleanup.Availability?>(null) }
        var nanoProgress by remember { mutableStateOf<String?>(null) }
        val scope = rememberCoroutineScope()
        LaunchedEffect(Unit) { nano = withContext(Dispatchers.IO) { SmartCleanup.availability() } }

        Text("Cleanup", style = MaterialTheme.typography.titleMedium)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            Cleanup.Level.entries.forEachIndexed { i, l ->
                SegmentedButton(
                    selected = level == l,
                    onClick = { level = l; Cleanup.setLevel(this@ModelActivity, l) },
                    shape = SegmentedButtonDefaults.itemShape(i, Cleanup.Level.entries.size),
                ) { Text(l.name.lowercase().replaceFirstChar { it.uppercase() }) }
            }
        }
        Text(
            when (level) {
                Cleanup.Level.OFF -> "Types exactly what the speech model heard."
                Cleanup.Level.LIGHT -> "Removes filler words and stutters, applies \"scratch that\" and quick corrections, and fixes spacing. Instant."
                Cleanup.Level.SMART -> "Light cleanup, then Gemini Nano on this phone tidies false starts. Adds up to 1.5 s. " +
                    "If Gemini Nano drops or changes your words, Murmur keeps the Light result."
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        if (level == Cleanup.Level.SMART) {
            val status = nanoProgress ?: when (nano) {
                null -> "Checking Gemini Nano…"
                SmartCleanup.Availability.AVAILABLE -> "Gemini Nano is ready."
                SmartCleanup.Availability.DOWNLOADABLE -> "Gemini Nano needs a one-time download through Google's AICore."
                SmartCleanup.Availability.DOWNLOADING -> "Gemini Nano is downloading…"
                SmartCleanup.Availability.UNAVAILABLE -> "Gemini Nano isn't available on this phone, so Smart works like Light."
            }
            Text(status, style = MaterialTheme.typography.bodySmall)
            if (nano == SmartCleanup.Availability.DOWNLOADABLE && nanoProgress == null) {
                OutlinedButton(onClick = {
                    nanoProgress = "Starting download…"
                    scope.launch(Dispatchers.IO) {
                        try {
                            SmartCleanup.download().collect { st ->
                                nanoProgress = when (st) {
                                    is DownloadStatus.DownloadProgress -> "Downloading Gemini Nano… ${st.totalBytesDownloaded / 1_000_000} MB"
                                    is DownloadStatus.DownloadCompleted -> null.also { nano = SmartCleanup.Availability.AVAILABLE }
                                    is DownloadStatus.DownloadFailed -> "Download failed. Try again later."
                                    else -> nanoProgress
                                }
                            }
                        } catch (e: Exception) {
                            nanoProgress = "Download failed. Try again later."
                        }
                    }
                }) { Text("Download Gemini Nano") }
            }
        }
    }

    private fun mb(bytes: Long) = "%,d MB".format(bytes / 1_000_000)
}

package dev.murmur.speech

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.concurrent.thread

/**
 * Downloads, checks and deletes the Parakeet model in the app's private storage.
 *
 * Each file downloads to a `.part` file (resumable), is checked against its SHA-256, then renamed.
 * The model is the only thing Murmur downloads.
 */
object ModelManager {
    sealed interface Status {
        data object NotInstalled : Status
        data class Downloading(val doneBytes: Long, val totalBytes: Long) : Status
        data object Checking : Status
        data class Installed(val bytes: Long) : Status
        data class Failed(val message: String) : Status
    }

    private class ModelFile(val name: String, val bytes: Long, val sha256: String)

    private const val BASE = "https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8/resolve/main"
    private const val TAG = "MurmurModel"
    private val FILES = listOf(
        ModelFile("encoder.int8.onnx", 652_282_294, "1afbe69c1ffdabee94755f42e4daf1d93549131a3da11356bda84fcef0f2c479"),
        ModelFile("decoder.int8.onnx", 11_845_274, "0222a86147299d4dee90edf95b6d13827edb42ad8b7c9cf0b2582f030937c0b5"),
        ModelFile("joiner.int8.onnx", 6_355_277, "cbd02f3696e9944970111fb439d9c3fd365a80374565f13f2b9c7007d253fc98"),
        ModelFile("tokens.txt", 93_939, "d58544679ea4bc6ac563d1f545eb7d474bd6cfa467f0a6e2c1dc1c7d37e3c35d"),
    )
    val TOTAL_BYTES = FILES.sumOf { it.bytes }

    private val _status = MutableStateFlow<Status>(Status.NotInstalled)
    val status: StateFlow<Status> = _status

    @Volatile private var cancelRequested = false
    @Volatile private var running = false

    /** Where the model lives. [SpeechEngine.load] reads from here. */
    fun dir(context: Context) = File(context.filesDir, "models/parakeet")

    fun isInstalled(context: Context) = SpeechEngine.missingFiles(dir(context)).isEmpty()

    /** Refreshes [status] from disk. Doesn't touch a download in progress. */
    fun refresh(context: Context) {
        if (running) return
        _status.value = if (isInstalled(context)) Status.Installed(installedBytes(context)) else Status.NotInstalled
    }

    fun isOnUnmeteredNetwork(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    /** Starts downloading in the background. Does nothing if a download is already running. */
    @Synchronized
    fun download(context: Context) {
        if (running) return
        running = true
        cancelRequested = false
        val dir = dir(context).apply { mkdirs() }
        thread(name = "murmur-model-download") {
            try {
                var done = FILES.filter { File(dir, it.name).length() == it.bytes }.sumOf { it.bytes }
                for (f in FILES) {
                    val target = File(dir, f.name)
                    if (target.length() == f.bytes) continue
                    val part = File(dir, f.name + ".part")
                    fetch("$BASE/${f.name}", part, f.bytes) { n -> _status.value = Status.Downloading(done + n, TOTAL_BYTES) }
                    if (cancelRequested) return@thread
                    _status.value = Status.Checking
                    if (sha256(part) != f.sha256) {
                        part.delete()
                        error("${f.name} didn't match its checksum. Try again.")
                    }
                    check(part.renameTo(target)) { "Couldn't save ${f.name}." }
                    done += f.bytes
                }
                _status.value = Status.Installed(installedBytes(context))
                Log.i(TAG, "Model installed")
            } catch (e: Exception) {
                Log.e(TAG, "Download failed", e)
                _status.value = Status.Failed(e.message ?: "Download failed. Check your connection and try again.")
            } finally {
                running = false
                if (cancelRequested) refresh(context)
            }
        }
    }

    fun cancel() { cancelRequested = true }

    /** Deletes the model and any partial downloads. */
    fun delete(context: Context) {
        if (running) return
        dir(context).deleteRecursively()
        refresh(context)
    }

    private fun installedBytes(context: Context) = FILES.sumOf { File(dir(context), it.name).length() }

    /** Downloads [url] into [part], resuming from its current length. */
    private fun fetch(url: String, part: File, expected: Long, onProgress: (Long) -> Unit) {
        var have = part.length().takeIf { it < expected } ?: 0L.also { part.delete() }
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            if (have > 0) setRequestProperty("Range", "bytes=$have-")
        }
        try {
            val code = conn.responseCode
            if (code == 200) have = 0 // server ignored the range: start over
            else if (code != 206) error("Server returned $code for ${part.name.removeSuffix(".part")}.")
            conn.inputStream.use { input ->
                FileOutputStream(part, have > 0).use { out ->
                    val buf = ByteArray(1 shl 16)
                    var lastReport = 0L
                    while (!cancelRequested) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        have += n
                        if (have - lastReport > 1_000_000) { onProgress(have); lastReport = have }
                    }
                }
            }
            onProgress(have)
        } finally {
            conn.disconnect()
        }
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}

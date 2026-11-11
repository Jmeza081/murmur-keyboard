package dev.murmur.speech

import android.app.Activity
import android.os.Bundle
import android.os.Debug
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import kotlin.concurrent.thread

/**
 * Debug-only screen that loads Parakeet and transcribes a WAV file, logging timings under the
 * "MurmurSpeech" tag. Start it with:
 *
 *   adb shell am start -n dev.murmur.keyboard.debug/dev.murmur.speech.SpeechBenchmarkActivity [--es wav name.wav]
 *
 * The model and WAV files live in the app's files folder; see scripts/push-model.sh.
 */
class SpeechBenchmarkActivity : Activity() {
    private lateinit var out: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        out = TextView(this).apply { setPadding(48, 96, 48, 48); textSize = 15f }
        setContentView(ScrollView(this).apply { addView(out) })

        val dir = File(filesDir, "models/parakeet")
        val wav = File(dir, intent.getStringExtra("wav") ?: "test.wav")
        thread(name = "speech-benchmark") { run(dir, wav) }
    }

    private fun run(dir: File, wavFile: File) {
        try {
            say("Model folder: $dir")
            val missing = SpeechEngine.missingFiles(dir)
            if (missing.isNotEmpty()) return say("Missing: ${missing.joinToString()}. Run scripts/push-model.sh.")

            val engine = SpeechEngine.load(dir)
            say("Loaded in ${engine.loadMs} ms")
            val wav = WavReader.read(wavFile)
            repeat(3) { i ->
                val r = engine.transcribe(wav.samples, wav.sampleRate)
                say("Run ${i + 1}: %.1f s of audio in %d ms (RTF %.3f)".format(r.audioSeconds, r.decodeMs, r.rtf))
                if (i == 0) say("Text: ${r.text}")
            }
            val mem = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
            say("Memory: %.0f MB total PSS".format(mem.totalPss / 1024.0))
            engine.release()
            say("Done")
        } catch (e: Throwable) {
            Log.e(TAG, "Benchmark failed", e)
            say("Failed: $e")
        }
    }

    private fun say(line: String) {
        Log.i(TAG, line)
        runOnUiThread { out.append(line + "\n\n") }
    }

    private companion object { const val TAG = "MurmurSpeech" }
}

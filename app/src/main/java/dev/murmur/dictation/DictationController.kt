package dev.murmur.dictation

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import dev.murmur.speech.ModelActivity
import dev.murmur.speech.ModelManager
import dev.murmur.speech.SpeechEngine
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.R
import java.util.concurrent.Executors
import kotlin.math.sqrt

/**
 * Runs keyboard dictation: records from the mic, transcribes with [SpeechEngine] when you stop
 * (or pause), and types the text into the current field.
 *
 * The panel covers the key area at the same height, so the app above doesn't move.
 * Audio stays in memory and is dropped after transcription.
 */
class DictationController(private val ime: LatinIME) {

    private enum class State { IDLE, LOADING, LISTENING, TRANSCRIBING }

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "murmur-speech") }
    private val modelDir get() = ModelManager.dir(ime)

    @Volatile private var engine: SpeechEngine? = null
    @Volatile private var recording = false
    /** Set when the recording should be transcribed; false when it was cancelled or failed. */
    @Volatile private var deliver = false
    private var state = State.IDLE
    private var panel: DictationPanel? = null
    private var recorderThread: Thread? = null
    private var startedAt = 0L

    /** Loads the model in the background so the first tap is fast. Safe to call often. */
    fun preload() {
        if (engine != null || SpeechEngine.missingFiles(modelDir).isNotEmpty()) return
        worker.execute { ensureEngine() }
    }

    /** The toolbar mic key: starts dictation, or stops it if already listening. */
    fun onMicKey() {
        when (state) {
            State.IDLE -> start()
            State.LOADING, State.LISTENING -> stop(cancel = false)
            State.TRANSCRIBING -> Unit
        }
    }

    /** Called when the keyboard hides or the field changes: discards any recording. */
    fun cancel() {
        if (state != State.IDLE) stop(cancel = true)
    }

    fun release() {
        cancel()
        worker.execute { engine?.release(); engine = null }
        worker.shutdown()
    }

    private fun start() {
        if (ime.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ime.startActivity(Intent(ime, MicPermissionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        if (!ModelManager.isInstalled(ime)) {
            // First run: open the model screen, which explains the download.
            ime.startActivity(Intent(ime, ModelActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        val panel = showPanel() ?: return
        state = if (engine == null) State.LOADING else State.LISTENING
        panel.showListening(loading = engine == null)
        recording = true
        deliver = false
        startedAt = SystemClock.elapsedRealtime()
        recorderThread = Thread({ record() }, "murmur-mic").also { it.start() }
        tick()
    }

    private fun stop(cancel: Boolean) {
        recording = false
        deliver = !cancel
        if (cancel) {
            state = State.IDLE
            closePanel()
        } else {
            state = State.TRANSCRIBING
            panel?.showMessage(ime.getString(R.string.murmur_dictation_transcribing))
        }
    }

    /** Records 16 kHz mono audio until [recording] goes false, then hands it to the worker. */
    @SuppressLint("MissingPermission") // checked in start()
    private fun record() {
        val rate = SpeechEngine.SAMPLE_RATE
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val chunk = ShortArray(rate / 10) // 100 ms
        val rec = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, chunk.size * 4))
        } catch (e: Exception) {
            Log.e(TAG, "Mic unavailable", e)
            return main.post { fail(ime.getString(R.string.murmur_dictation_mic_busy)) }.let { }
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return main.post { fail(ime.getString(R.string.murmur_dictation_mic_busy)) }.let { }
        }
        val audio = FloatArrayBuilder()
        var heardSpeech = false
        var quietMs = 0
        rec.startRecording()
        try {
            while (recording) {
                val n = rec.read(chunk, 0, chunk.size)
                if (n <= 0) continue
                var sum = 0.0
                for (i in 0 until n) {
                    val s = chunk[i] / 32768f
                    audio.add(s)
                    sum += s * s
                }
                val rms = sqrt(sum / n).toFloat()
                main.post { panel?.addLevel(rms) }
                // Auto-stop: 2 s of quiet after speech, or 8 s with no speech at all.
                if (rms > SPEECH_RMS) { heardSpeech = true; quietMs = 0 } else quietMs += n * 1000 / rate
                if (heardSpeech && quietMs >= SILENCE_STOP_MS) main.post { if (state == State.LISTENING) stop(cancel = false) }
                if (!heardSpeech && quietMs >= NO_SPEECH_MS) main.post { if (recording) fail(ime.getString(R.string.murmur_dictation_nothing_heard)) }
                if (audio.size >= rate * MAX_SECONDS) main.post { if (state == State.LISTENING) stop(cancel = false) }
            }
        } finally {
            rec.stop()
            rec.release()
        }
        if (!deliver) return // cancelled or failed
        val samples = audio.toArray()
        worker.execute { transcribe(samples) }
    }

    private fun transcribe(samples: FloatArray) {
        val engine = ensureEngine() ?: return main.post { fail(ime.getString(R.string.murmur_dictation_model_missing)) }.let { }
        val result = engine.transcribe(samples)
        Log.i(TAG, "Transcribed %.1f s in %d ms (RTF %.3f)".format(result.audioSeconds, result.decodeMs, result.rtf))
        main.post {
            if (state != State.TRANSCRIBING) return@post
            state = State.IDLE
            closePanel()
            if (result.text.isNotEmpty()) insert(result.text)
        }
    }

    @Synchronized
    private fun ensureEngine(): SpeechEngine? {
        engine?.let { return it }
        return try {
            SpeechEngine.load(modelDir).also {
                engine = it
                Log.i(TAG, "Model loaded in ${it.loadMs} ms")
                main.post { if (state == State.LOADING) { state = State.LISTENING; panel?.showListening(loading = false) } }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Model failed to load", e)
            null
        }
    }

    /** Types [text] at the cursor, adding a space if it would touch the previous word. */
    private fun insert(text: String) {
        val before = ime.currentInputConnection?.getTextBeforeCursor(1, 0)
        val prefix = if (!before.isNullOrEmpty() && !before.last().isWhitespace()) " " else ""
        ime.onTextInput(prefix + text)
    }

    private fun fail(message: String) {
        recording = false
        deliver = false
        state = State.TRANSCRIBING // ignore mic taps while the message shows
        panel?.showMessage(message)
        main.postDelayed({ state = State.IDLE; closePanel() }, 2500)
    }

    private fun tick() {
        if (state != State.LISTENING && state != State.LOADING) return
        panel?.setElapsed(SystemClock.elapsedRealtime() - startedAt)
        main.postDelayed({ tick() }, 250)
    }

    // --- Panel: an overlay on the key area, same size as the keyboard ---

    private fun showPanel(): DictationPanel? {
        val keyboardView = KeyboardSwitcher.getInstance().mainKeyboardView ?: return null
        val wrapper = keyboardView.parent as? ViewGroup ?: return null
        val p = panel ?: DictationPanel(ime, onCancel = { cancel() }, onStop = { onMicKey() }).also { panel = it }
        if (p.parent == null) {
            wrapper.addView(p, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        p.reset()
        keyboardView.visibility = View.INVISIBLE // keeps its size, so the panel matches the key area
        return p
    }

    private fun closePanel() {
        panel?.let { (it.parent as? ViewGroup)?.removeView(it) }
        KeyboardSwitcher.getInstance().mainKeyboardView?.visibility = View.VISIBLE
    }

    private class FloatArrayBuilder {
        private var data = FloatArray(16_000 * 10)
        var size = 0; private set
        fun add(v: Float) {
            if (size == data.size) data = data.copyOf(data.size * 2)
            data[size++] = v
        }
        fun toArray() = data.copyOf(size)
    }

    private companion object {
        const val TAG = "MurmurDictation"
        const val SPEECH_RMS = 0.02f
        const val SILENCE_STOP_MS = 2000
        const val NO_SPEECH_MS = 8000
        const val MAX_SECONDS = 300
    }
}

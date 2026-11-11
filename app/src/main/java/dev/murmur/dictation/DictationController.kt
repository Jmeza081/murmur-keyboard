package dev.murmur.dictation

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import dev.murmur.speech.MicRecorder
import dev.murmur.speech.ModelActivity
import dev.murmur.speech.ModelManager
import dev.murmur.speech.SharedEngine
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.R

/**
 * Runs keyboard dictation: records from the mic, transcribes with [SharedEngine] when you stop
 * (or pause), and types the text into the current field.
 *
 * The panel covers the key area at the same height, so the app above doesn't move.
 * Audio stays in memory and is dropped after transcription.
 */
class DictationController(private val ime: LatinIME) : MicRecorder.Listener {

    private enum class State { IDLE, LISTENING, TRANSCRIBING }

    private val main = Handler(Looper.getMainLooper())
    private val recorder = MicRecorder(this)
    private var state = State.IDLE
    private var panel: DictationPanel? = null
    private var startedAt = 0L

    /** Loads the model in the background so the first tap is fast. Safe to call often. */
    fun preload() = SharedEngine.preload(ime)

    /** The toolbar mic key: starts dictation, or stops it if already listening. */
    fun onMicKey() {
        when (state) {
            State.IDLE -> start()
            State.LISTENING -> finish()
            State.TRANSCRIBING -> Unit
        }
    }

    /** Called when the keyboard hides or the field changes: discards any recording. */
    fun cancel() {
        if (state == State.IDLE) return
        recorder.cancel()
        state = State.IDLE
        closePanel()
    }

    fun release() = cancel()

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
        if (!recorder.start()) {
            showFailure(ime.getString(R.string.murmur_dictation_mic_busy))
            return
        }
        state = State.LISTENING
        val loaded = SharedEngine.isLoaded
        panel.showListening(loading = !loaded)
        if (!loaded) SharedEngine.preload(ime) { main.post { if (state == State.LISTENING) panel.showListening(loading = false) } }
        startedAt = SystemClock.elapsedRealtime()
        tick()
    }

    /** Stops recording, transcribes, and types the result. */
    private fun finish() {
        state = State.TRANSCRIBING
        panel?.showMessage(ime.getString(R.string.murmur_dictation_transcribing))
        recorder.stop { samples ->
            SharedEngine.transcribe(ime, samples) { result ->
                main.post {
                    if (state != State.TRANSCRIBING) return@post // cancelled meanwhile
                    if (result == null) return@post showFailure(ime.getString(R.string.murmur_dictation_model_missing))
                    state = State.IDLE
                    closePanel()
                    if (result.text.isNotEmpty()) insert(result.text)
                }
            }
        }
    }

    // MicRecorder.Listener: called on the recording thread

    override fun onLevel(rms: Float) { main.post { panel?.addLevel(rms) } }
    override fun onAutoStop() { main.post { if (state == State.LISTENING) finish() } }
    override fun onNoSpeech() { main.post { if (state == State.LISTENING) showFailure(ime.getString(R.string.murmur_dictation_nothing_heard)) } }

    /** Types [text] at the cursor, adding a space if it would touch the previous word. */
    private fun insert(text: String) {
        val before = ime.currentInputConnection?.getTextBeforeCursor(1, 0)
        val prefix = if (!before.isNullOrEmpty() && !before.last().isWhitespace()) " " else ""
        ime.onTextInput(prefix + text)
    }

    private fun showFailure(message: String) {
        recorder.cancel()
        state = State.TRANSCRIBING // ignore mic taps while the message shows
        panel?.showMessage(message)
        main.postDelayed({ if (state == State.TRANSCRIBING) { state = State.IDLE; closePanel() } }, 2500)
    }

    private fun tick() {
        if (state != State.LISTENING) return
        panel?.setElapsed(SystemClock.elapsedRealtime() - startedAt)
        main.postDelayed({ tick() }, 250)
    }

    // --- Panel: an overlay on the key area, same size as the keyboard ---

    private fun showPanel(): DictationPanel? {
        val keyboardView = KeyboardSwitcher.getInstance().mainKeyboardView ?: return null
        val wrapper = keyboardView.parent as? ViewGroup ?: return null
        val p = panel ?: DictationPanel(ime, onCancel = { cancel() }, onStop = { onMicKey() }).also { panel = it }
        // Exactly the keyboard's height: MATCH_PARENT would let the wrapper grow to fill the screen.
        val params = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, keyboardView.height)
        if (p.parent == null) wrapper.addView(p, params) else p.layoutParams = params
        p.reset()
        keyboardView.visibility = View.INVISIBLE // keeps its size, so the panel matches the key area
        return p
    }

    private fun closePanel() {
        panel?.let { (it.parent as? ViewGroup)?.removeView(it) }
        KeyboardSwitcher.getInstance().mainKeyboardView?.visibility = View.VISIBLE
    }
}

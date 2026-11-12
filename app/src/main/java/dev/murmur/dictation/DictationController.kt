package dev.murmur.dictation

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import android.view.inputmethod.EditorInfo
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import dev.murmur.cleanup.Cleanup
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

    init { active = this }

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

    fun release() {
        cancel()
        if (active === this) active = null
    }

    /** The last app field the keyboard attached to, ignoring Murmur's own assistant sheet. */
    private var lastField: EditorInfo? = null
    private var pending: Pending? = null

    private class Pending(val text: String, val pkg: String, val onTimeout: () -> Unit)

    /** Called by LatinIME for every new input connection. */
    fun onStartInput(info: EditorInfo?) {
        if (info == null || info.packageName == ime.packageName) return // our own sheet took focus
        lastField = info
        Log.i(TAG, "Input started in ${info.packageName}, type 0x${Integer.toHexString(info.inputType)}")
        val p = pending ?: return
        if (info.packageName != p.pkg || !isTextField(info)) return
        pending = null
        main.postDelayed({ commitForAssistant(p.text) }, 50) // let the connection settle
    }

    /**
     * The app whose text field had focus before the assistant sheet opened, or null if there was
     * none (or it's a password field). The keyboard can be hidden; the field just needs focus.
     */
    fun focusedFieldPackage(): String? {
        if (!isCurrentKeyboard()) return null // another keyboard is active; lastField is stale
        return lastField?.takeIf { isTextField(it) }?.packageName
    }

    private fun isCurrentKeyboard(): Boolean {
        val current = android.provider.Settings.Secure.getString(ime.contentResolver, android.provider.Settings.Secure.DEFAULT_INPUT_METHOD)
        return current?.substringBefore('/') == ime.packageName
    }

    /**
     * Types [text] into that field once focus returns to it after the sheet closes.
     * Calls [onTimeout] (on the main thread) if focus doesn't come back within 2 s.
     */
    fun typeWhenFieldReturns(text: String, onTimeout: () -> Unit) {
        val pkg = focusedFieldPackage() ?: return onTimeout()
        val current = ime.currentInputEditorInfo
        if (ime.currentInputConnection != null && current != null && current.packageName == pkg && isTextField(current)) {
            return commitForAssistant(text) // focus is already back
        }
        val p = Pending(text, pkg, onTimeout)
        pending = p
        main.postDelayed({ if (pending === p) { pending = null; Log.w(TAG, "Field didn't regain focus"); p.onTimeout() } }, 2000)
    }

    /** Commits straight to the connection, which works whether or not the keyboard is showing. */
    private fun commitForAssistant(text: String) {
        val ic = ime.currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(1, 0)
        val prefix = if (!before.isNullOrEmpty() && !before.last().isWhitespace()) " " else ""
        ic.finishComposingText()
        ic.commitText(prefix + text, 1)
        Log.i(TAG, "Typed ${text.length} chars into ${ime.currentInputEditorInfo?.packageName}")
    }

    private fun isTextField(info: EditorInfo) =
        info.inputType != android.text.InputType.TYPE_NULL && !isPassword(info.inputType)

    private fun start() {
        if (ime.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ime.startActivity(Intent(ime, MicPermissionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        if (!ModelManager.isInstalled(ime)) {
            // First run: open the model screen, which explains the download.
            ime.startActivity(Intent(ime, ModelActivity::class.java).putExtra(ModelActivity.EXTRA_SCREEN, "DICTATION").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        val panel = showPanel() ?: return
        recorder.silenceStopMs = dev.murmur.settings.DictationPrefs.silenceMs(ime)
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
        strip(ime.getString(R.string.murmur_dictation_transcribing))
        recorder.stop { samples ->
            SharedEngine.transcribe(ime, samples) { result ->
                val cleaned = result?.let { Cleanup.process(ime, it.text) }
                main.post {
                    if (state != State.TRANSCRIBING) return@post // cancelled meanwhile
                    if (result == null) return@post showFailure(ime.getString(R.string.murmur_dictation_model_missing))
                    state = State.IDLE
                    closePanel()
                    if (!cleaned.isNullOrEmpty()) insert(cleaned)
                }
            }
        }
    }

    // MicRecorder.Listener: called on the recording thread

    override fun onLevel(rms: Float) { main.post { panel?.addLevel(rms) } }
    override fun onAutoStop() { main.post { if (state == State.LISTENING) finish() } }
    override fun onNoSpeech() { main.post { if (state == State.LISTENING) showFailure(ime.getString(R.string.murmur_dictation_nothing_heard)) } }

    private fun isPassword(inputType: Int): Boolean {
        val variation = inputType and android.text.InputType.TYPE_MASK_VARIATION
        return when (inputType and android.text.InputType.TYPE_MASK_CLASS) {
            android.text.InputType.TYPE_CLASS_TEXT -> variation == android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == android.text.InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
            android.text.InputType.TYPE_CLASS_NUMBER -> variation == android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
    }

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
        strip("") // the panel says what happened; don't keep saying "Listening…"
        main.postDelayed({ if (state == State.TRANSCRIBING) { state = State.IDLE; closePanel() } }, 2500)
    }

    private fun tick() {
        if (state != State.LISTENING) return
        panel?.setElapsed(SystemClock.elapsedRealtime() - startedAt)
        main.postDelayed({ tick() }, 250)
    }

    companion object {
        /** The running keyboard's controller, used by the power-button assistant (same process). */
        @Volatile var active: DictationController? = null
            private set
        private const val TAG = "MurmurDictation"
    }

    // --- Panel: an overlay on the key area, same size as the keyboard ---

    private fun showPanel(): DictationPanel? {
        val keyboardView = KeyboardSwitcher.getInstance().mainKeyboardView ?: return null
        val wrapper = keyboardView.parent as? ViewGroup ?: return null
        val p = panel ?: DictationPanel(ime, onCancel = { cancel() }, onStop = { onMicKey() },
            onToggleCleanup = {
                val on = Cleanup.level(ime) == Cleanup.Level.OFF
                Cleanup.setLevel(ime, if (on) Cleanup.Level.LIGHT else Cleanup.Level.OFF)
                on
            },
            cleanupOn = Cleanup.level(ime) != Cleanup.Level.OFF,
        ).also { panel = it }
        // Exactly the keyboard's height: MATCH_PARENT would let the wrapper grow to fill the screen.
        val params = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, keyboardView.height)
        if (p.parent == null) wrapper.addView(p, params) else p.layoutParams = params
        p.reset()
        p.styleCleanup(Cleanup.level(ime) != Cleanup.Level.OFF) // may have changed in settings
        // Keep Cancel and Stop above Android's nav buttons, like the keys (the keyboard's bottom padding)
        val bottomPad = keyboardView.keyboard?.let { kb -> kb.mOccupiedHeight - (kb.sortedKeys.maxOfOrNull { it.y + it.height } ?: kb.mOccupiedHeight) }?.coerceAtLeast(0) ?: 0
        // Android draws its keyboard-switch and hide buttons ~48 dp tall over the keyboard's bottom edge
        val density = ime.resources.displayMetrics.density
        p.setPadding(p.paddingLeft, p.paddingTop, p.paddingRight, maxOf(bottomPad, (48 * density).toInt()) + (8 * density).toInt())
        keyboardView.visibility = View.INVISIBLE // keeps its size, so the panel matches the key area
        strip(ime.getString(R.string.murmur_dictation_listening_strip))
        return p
    }

    /** Murmur: the strip shows the dictation state; the keyboard button there cancels back to the keys. */
    private fun strip(status: String?) =
        KeyboardSwitcher.getInstance().murmurStrip?.setMurmurListening(status) { cancel() }

    private fun closePanel() {
        strip(null)
        panel?.let { (it.parent as? ViewGroup)?.removeView(it) }
        KeyboardSwitcher.getInstance().mainKeyboardView?.visibility = View.VISIBLE
    }
}

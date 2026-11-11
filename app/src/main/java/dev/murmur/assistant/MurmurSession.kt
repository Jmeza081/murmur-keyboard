package dev.murmur.assistant

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.voice.VoiceInteractionSession
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import dev.murmur.dictation.DictationController
import dev.murmur.dictation.DictationPanel
import dev.murmur.dictation.LevelBarsView
import dev.murmur.dictation.MicPermissionActivity
import dev.murmur.speech.MicRecorder
import dev.murmur.speech.ModelActivity
import dev.murmur.speech.ModelManager
import dev.murmur.speech.SharedEngine
import helium314.keyboard.latin.R

/**
 * The listening sheet shown when you hold the power button. It listens until you pause or tap
 * Done, transcribes on the device, and delivers the text.
 *
 * Text goes into the focused field through Murmur's keyboard, else through the optional
 * accessibility service (other keyboards), else to the clipboard.
 */
class MurmurSession(context: Context) : VoiceInteractionSession(context), MicRecorder.Listener {

    private enum class State { IDLE, LISTENING, TRANSCRIBING, DONE }

    private val main = Handler(Looper.getMainLooper())
    private val recorder = MicRecorder(this)
    private var state = State.IDLE
    private var startedAt = 0L

    private val dark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    private val sheetColor = if (dark) Color.parseColor("#1E2129") else Color.parseColor("#F1F3F7")
    private val ink = if (dark) Color.parseColor("#ECEEF3") else Color.parseColor("#191B21")
    private val subtle = if (dark) Color.parseColor("#A1A8B6") else Color.parseColor("#5B6272")
    private val chipColor = if (dark) Color.parseColor("#2A2E37") else Color.parseColor("#DDE2EA")

    private lateinit var status: TextView
    private lateinit var transcript: TextView
    private lateinit var bars: LevelBarsView
    private lateinit var hint: TextView
    private lateinit var target: TextView
    /** The field the accessibility service saw when the sheet opened, before the sheet took focus. */
    private var a11yTargetAtStart: String? = null

    override fun onCreateContentView(): View {
        val c = context
        status = TextView(c).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(DictationPanel.RECORDING)
        }
        val chip = TextView(c).apply {
            text = c.getString(R.string.murmur_dictation_on_device)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(ink)
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(chipColor) }
        }
        transcript = TextView(c).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTextColor(ink)
            minHeight = dp(56)
        }
        bars = LevelBarsView(c, DictationPanel.RECORDING, subtle)
        target = TextView(c).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setTextColor(subtle)
        }
        hint = TextView(c).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setTextColor(subtle)
            gravity = Gravity.CENTER
            text = c.getString(R.string.murmur_assistant_hint)
        }
        val cancel = ImageButton(c).apply {
            setImageResource(R.drawable.ic_murmur_close)
            setColorFilter(ink)
            contentDescription = c.getString(R.string.murmur_dictation_cancel)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(chipColor) }
            setOnClickListener { hide() }
        }
        val done = ImageButton(c).apply {
            setImageResource(R.drawable.ic_murmur_stop)
            setColorFilter(Color.WHITE)
            contentDescription = c.getString(R.string.murmur_dictation_stop)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(DictationPanel.RECORDING) }
            setOnClickListener { if (state == State.LISTENING) stopAndTranscribe() }
        }

        val sheet = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(20))
            background = GradientDrawable().apply {
                cornerRadii = floatArrayOf(dp(28f), dp(28f), dp(28f), dp(28f), 0f, 0f, 0f, 0f)
                setColor(sheetColor)
            }
            isClickable = true // taps on the sheet don't dismiss it
            addView(LinearLayout(c).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(status, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(chip)
            })
            addView(target, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
            addView(transcript, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
            addView(bars, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)))
            addView(FrameLayout(c).apply {
                addView(cancel, FrameLayout.LayoutParams(dp(52), dp(52), Gravity.START or Gravity.CENTER_VERTICAL))
                addView(done, FrameLayout.LayoutParams(dp(68), dp(68), Gravity.CENTER))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(72)))
            addView(hint, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        }

        return FrameLayout(c).apply {
            setBackgroundColor(Color.argb(110, 8, 10, 16)) // scrim; tapping it cancels
            setOnClickListener { hide() }
            addView(sheet, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        }
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        transcript.text = ""
        bars.clear()
        target.text = targetLine()
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            context.startActivity(Intent(context, MicPermissionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return hide()
        }
        if (!ModelManager.isInstalled(context)) {
            context.startActivity(Intent(context, ModelActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return hide()
        }
        if (!recorder.start()) return showResult(context.getString(R.string.murmur_dictation_mic_busy), close = true)
        state = State.LISTENING
        startedAt = SystemClock.elapsedRealtime()
        SharedEngine.preload(context)
        tick()
    }

    override fun onHide() {
        super.onHide()
        if (state == State.LISTENING || state == State.TRANSCRIBING) recorder.cancel()
        state = State.IDLE
        main.removeCallbacksAndMessages(null) // pending typing lives in the keyboard, not here
    }

    private fun stopAndTranscribe() {
        state = State.TRANSCRIBING
        status.setTextColor(ink)
        status.text = context.getString(R.string.murmur_dictation_transcribing)
        recorder.stop { samples ->
            SharedEngine.transcribe(context, samples) { result ->
                main.post {
                    if (state != State.TRANSCRIBING) return@post
                    val text = result?.text.orEmpty()
                    when {
                        result == null -> showResult(context.getString(R.string.murmur_dictation_model_missing), close = true)
                        text.isEmpty() -> showResult(context.getString(R.string.murmur_dictation_nothing_heard), close = true)
                        else -> deliver(text)
                    }
                }
            }
        }
    }

    /**
     * Delivers the text: Murmur's keyboard, then the accessibility service, then the clipboard.
     */
    private fun deliver(text: String) {
        transcript.text = text
        // 1. Murmur's keyboard, if it's the active keyboard and a field had focus.
        val keyboard = DictationController.active
        val keyboardTarget = keyboard?.focusedFieldPackage()
        if (keyboard != null && keyboardTarget != null) {
            return closeThenType(keyboardTarget) { keyboard.typeWhenFieldReturns(text) { copyToClipboard(text, toast = true) } }
        }
        // 2. The accessibility service, for fields using another keyboard.
        val a11y = MurmurAccessibilityService.instance
        val a11yTarget = a11y?.focusedFieldPackage() ?: a11yTargetAtStart
        if (a11y != null && a11yTarget != null) {
            return closeThenType(a11yTarget) { a11y.typeWhenFocusReturns(text, a11yTarget) { copyToClipboard(text, toast = true) } }
        }
        // 3. The clipboard.
        copyToClipboard(text, toast = false)
    }

    /** The field only gets focus back once this sheet closes, so confirm, close, then type. */
    private fun closeThenType(targetPackage: String, type: () -> Unit) {
        status.setTextColor(ink)
        status.text = context.getString(R.string.murmur_assistant_added, appLabel(targetPackage))
        state = State.DONE
        main.postDelayed({ hide(); type() }, 600)
    }

    private fun copyToClipboard(text: String, toast: Boolean) {
        context.getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.murmur_assistant_clip_label), text))
        if (toast) android.widget.Toast.makeText(context, R.string.murmur_assistant_copied_fallback, android.widget.Toast.LENGTH_LONG).show()
        else showResult(context.getString(R.string.murmur_assistant_copied), close = true)
    }

    /** Shown while listening, so you know where the text will go before you speak. */
    private fun targetLine(): String {
        a11yTargetAtStart = MurmurAccessibilityService.instance?.focusedFieldPackage()
        val target = DictationController.active?.focusedFieldPackage() ?: a11yTargetAtStart
        return if (target != null) context.getString(R.string.murmur_assistant_target, appLabel(target))
        else context.getString(R.string.murmur_assistant_target_clipboard)
    }

    private fun appLabel(pkg: String): String = try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: PackageManager.NameNotFoundException) { pkg }

    private fun showResult(message: String, close: Boolean) {
        state = State.DONE
        status.setTextColor(ink)
        status.text = message
        if (close) main.postDelayed({ hide() }, 1600)
    }

    private fun tick() {
        if (state != State.LISTENING) return
        val s = (SystemClock.elapsedRealtime() - startedAt) / 1000
        status.setTextColor(DictationPanel.RECORDING)
        status.text = "%s  %d:%02d".format(context.getString(R.string.murmur_dictation_listening), s / 60, s % 60)
        main.postDelayed({ tick() }, 250)
    }

    // MicRecorder.Listener: called on the recording thread

    override fun onLevel(rms: Float) { main.post { bars.add(rms) } }
    override fun onAutoStop() { main.post { if (state == State.LISTENING) stopAndTranscribe() } }
    override fun onNoSpeech() { main.post { if (state == State.LISTENING) showResult(context.getString(R.string.murmur_dictation_nothing_heard), close = true) } }

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()
    private fun dp(v: Float) = v * context.resources.displayMetrics.density
}

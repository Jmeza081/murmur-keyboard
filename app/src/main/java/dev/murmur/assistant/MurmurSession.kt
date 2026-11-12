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
import dev.murmur.cleanup.Cleanup
import dev.murmur.design.MurmurTokens
import dev.murmur.dictation.DictationController
import dev.murmur.dictation.DictationPanel
import dev.murmur.dictation.LevelBarsView
import dev.murmur.dictation.MicPermissionActivity
import dev.murmur.dictation.StopGlowView
import dev.murmur.dictation.WaveStyle
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
    private val sheetColor = if (dark) MurmurTokens.Dark.SHEET else MurmurTokens.Light.SHEET
    private val ink = if (dark) MurmurTokens.Dark.TEXT else MurmurTokens.Light.TEXT
    private val subtle = if (dark) MurmurTokens.Dark.SECONDARY_TEXT else MurmurTokens.Light.SECONDARY_TEXT
    private val chipColor = if (dark) MurmurTokens.Dark.CHIP else MurmurTokens.Light.CHIP

    private lateinit var status: TextView
    private lateinit var dot: View
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
        // Murmur design: red dot before the timer, shield on the chip, cursor before the target (mockup step 2)
        dot = View(c).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(DictationPanel.RECORDING) } }
        val chip = TextView(c).apply {
            text = c.getString(R.string.murmur_assistant_on_device)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(ink)
            gravity = Gravity.CENTER_VERTICAL
            compoundDrawablePadding = dp(6)
            setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_murmur_shield, 0, 0, 0)
            androidx.core.widget.TextViewCompat.setCompoundDrawableTintList(this, android.content.res.ColorStateList.valueOf(ink))
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(chipColor) }
        }
        transcript = TextView(c).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTextColor(ink)
            visibility = View.GONE // shown once there's text
        }
        bars = LevelBarsView(c, DictationPanel.RECORDING, subtle)
        glow = StopGlowView(c, DictationPanel.RECORDING, 34f)
        target = TextView(c).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setTextColor(subtle)
            gravity = Gravity.CENTER_VERTICAL
            compoundDrawablePadding = dp(8)
            setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_murmur_cursor, 0, 0, 0)
            androidx.core.widget.TextViewCompat.setCompoundDrawableTintList(this, android.content.res.ColorStateList.valueOf(subtle))
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
            setOnClickListener { dismiss() }
        }
        val done = ImageButton(c).apply {
            setImageResource(R.drawable.ic_murmur_stop)
            setColorFilter(Color.WHITE)
            contentDescription = c.getString(R.string.murmur_dictation_stop)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(DictationPanel.RECORDING) }
            setOnClickListener { if (state == State.LISTENING) stopAndTranscribe() }
        }

        val sheet = LinearLayout(c).apply {
            this@MurmurSession.sheet = this
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(16))
            clipChildren = false // Send's glow draws over the waveform and hint rows instead of being cut at its own row
            // Floating card, rounded on all corners, inset from the screen edges (mockup .sheet)
            background = GradientDrawable().apply { cornerRadius = dp(28f); setColor(sheetColor) }
            elevation = dp(12f)
            isClickable = true // taps on the sheet don't dismiss it
            addView(View(c).apply {
                background = GradientDrawable().apply { cornerRadius = dp(2f); setColor(subtle) }
                alpha = 0.4f
            }, LinearLayout.LayoutParams(dp(36), dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(12) })
            addView(LinearLayout(c).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(dot, LinearLayout.LayoutParams(dp(9), dp(9)).apply { marginEnd = dp(8) })
                addView(status, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(chip)
            })
            addView(target, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
            addView(transcript, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
            // The waveform takes whatever height the card's minimum leaves, so the card scales with the screen
            // The waveform grows on tall screens so the card is ~40% of the screen (the rest of the card is ~250 dp)
            val barsHeight = maxOf(dp(84), (c.resources.displayMetrics.heightPixels * 0.40f).toInt() - dp(286))
            addView(bars, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, barsHeight).apply { topMargin = dp(20); bottomMargin = dp(24) })
            addView(FrameLayout(c).apply {
                clipChildren = false
                addView(glow, FrameLayout.LayoutParams(dp(132), dp(132), Gravity.CENTER))
                addView(cancel, FrameLayout.LayoutParams(dp(52), dp(52), Gravity.START or Gravity.CENTER_VERTICAL))
                addView(done, FrameLayout.LayoutParams(dp(68), dp(68), Gravity.CENTER))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(72)))
            addView(hint, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        }

        // Opening motion (design/sheet-activation.html, option D): an orange listening capsule pops up,
        // then opens out into the sheet
        capsuleBars = LevelBarsView(c, Color.WHITE, Color.WHITE, WaveStyle.BARS)
        capsule = FrameLayout(c).apply {
            background = GradientDrawable().apply { cornerRadius = dp(CAPSULE_H_DP / 2f); setColor(DictationPanel.RECORDING) }
            setPadding(dp(16), dp(8), dp(16), dp(8))
            addView(capsuleBars, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            alpha = 0f
            elevation = dp(12f)
        }
        sheetBackground = sheet.background as GradientDrawable
        sheet.clipToOutline = true
        sheet.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) {
                val f = reveal
                val w = view.width; val h = view.height
                val cw = dp(CAPSULE_W_DP); val ch = dp(CAPSULE_H_DP)
                val left = lerp((w - cw) / 2f, 0f, f); val right = lerp((w + cw) / 2f, w.toFloat(), f)
                val top = lerp(h - ch - dp(4f), 0f, f); val bottom = lerp(h - dp(4f), h.toFloat(), f)
                outline.setRoundRect(left.toInt(), top.toInt(), right.toInt(), bottom.toInt(), lerp(dp(CAPSULE_H_DP / 2f), dp(28f), f))
            }
        }

        return FrameLayout(c).apply {
            scrim = android.graphics.drawable.ColorDrawable(Color.argb(110, 8, 10, 16)).also { it.alpha = 0 }
            background = scrim // tapping it cancels
            setOnClickListener { dismiss() }
            val lp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
                .apply { setMargins(dp(8), 0, dp(8), dp(8)) }
            addView(sheet, lp)
            val capLp = FrameLayout.LayoutParams(dp(CAPSULE_W_DP), dp(CAPSULE_H_DP), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
                .apply { bottomMargin = dp(8) + dp(4) }
            addView(capsule, capLp)
            // Keep the card above the gesture bar / nav buttons
            setOnApplyWindowInsetsListener { _, insets ->
                val nav = androidx.core.view.WindowInsetsCompat.toWindowInsetsCompat(insets)
                    .getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars()).bottom
                lp.bottomMargin = dp(8) + nav
                sheet.layoutParams = lp
                capLp.bottomMargin = dp(8) + nav + dp(4)
                capsule.layoutParams = capLp
                insets
            }
        }
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        transcript.text = ""
        transcript.visibility = View.GONE
        dot.visibility = View.VISIBLE
        bars.clear()
        capsuleBars.clear()
        glow.clear()
        playOpen()
        target.text = targetLine()
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            context.startActivity(Intent(context, MicPermissionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return hide()
        }
        if (!ModelManager.isInstalled(context)) {
            context.startActivity(Intent(context, ModelActivity::class.java).putExtra(ModelActivity.EXTRA_SCREEN, "DICTATION").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return hide()
        }
        recorder.silenceStopMs = dev.murmur.settings.DictationPrefs.silenceMs(context)
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
        dot.visibility = View.GONE
        status.text = context.getString(R.string.murmur_dictation_transcribing)
        recorder.stop { samples ->
            SharedEngine.transcribe(context, samples) { result ->
                val cleaned = result?.let { Cleanup.process(context, it.text) }
                main.post {
                    if (state != State.TRANSCRIBING) return@post
                    val text = cleaned.orEmpty()
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
        transcript.visibility = View.VISIBLE
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
        main.postDelayed({ dismiss { type() } }, 600)
    }

    private fun copyToClipboard(text: String, toast: Boolean) {
        context.getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.murmur_assistant_clip_label), text))
        if (toast) android.widget.Toast.makeText(context, R.string.murmur_assistant_copied_fallback, android.widget.Toast.LENGTH_LONG).show()
        else showResult(context.getString(R.string.murmur_assistant_copied), close = true)
    }

    /** Shown while listening, so you know where the text will go before you speak. */
    private fun targetLine(): CharSequence {
        a11yTargetAtStart = MurmurAccessibilityService.instance?.focusedFieldPackage()
        val target = DictationController.active?.focusedFieldPackage() ?: a11yTargetAtStart
        if (target == null) return context.getString(R.string.murmur_assistant_target_clipboard)
        val app = appLabel(target)
        val line = context.getString(R.string.murmur_assistant_target, app)
        val start = line.indexOf(app)
        return android.text.SpannableString(line).apply {
            if (start >= 0) {
                setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), start, start + app.length, 0)
                setSpan(android.text.style.ForegroundColorSpan(ink), start, start + app.length, 0)
            }
        }
    }

    private fun appLabel(pkg: String): String = try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: PackageManager.NameNotFoundException) { pkg }

    private fun showResult(message: String, close: Boolean) {
        state = State.DONE
        dot.visibility = View.GONE
        status.setTextColor(ink)
        status.text = message
        if (close) main.postDelayed({ dismiss() }, 1600)
    }

    private fun tick() {
        if (state != State.LISTENING) return
        val s = (SystemClock.elapsedRealtime() - startedAt) / 1000
        status.setTextColor(DictationPanel.RECORDING)
        status.text = "%d:%02d".format(s / 60, s % 60)
        main.postDelayed({ tick() }, 250)
    }

    // MicRecorder.Listener: called on the recording thread

    override fun onLevel(rms: Float) { main.post { bars.add(rms); capsuleBars.add(rms); glow.add(rms) } }
    override fun onAutoStop() { main.post { if (state == State.LISTENING) stopAndTranscribe() } }
    override fun onNoSpeech() { main.post { if (state == State.LISTENING) showResult(context.getString(R.string.murmur_dictation_nothing_heard), close = true) } }

    // --- Motion: capsule to sheet ---

    private lateinit var sheet: LinearLayout
    private lateinit var sheetBackground: GradientDrawable
    private lateinit var capsule: FrameLayout
    private lateinit var capsuleBars: LevelBarsView
    private lateinit var glow: StopGlowView
    private lateinit var scrim: android.graphics.drawable.ColorDrawable
    /** 0 = clipped to the capsule, 1 = the full sheet. */
    private var reveal = 1f
    private var motion: android.animation.Animator? = null
    private var dismissing = false

    private fun lerp(a: Float, b: Float, f: Float) = a + (b - a) * f

    /** Applies reveal fraction [f]: clip, color (orange to sheet), content fades in over the second half. */
    private fun applyReveal(f: Float) {
        reveal = f
        sheet.invalidateOutline()
        sheetBackground.setColor(androidx.core.graphics.ColorUtils.blendARGB(DictationPanel.RECORDING, sheetColor, f))
        val contentAlpha = ((f - 0.5f) * 2f).coerceIn(0f, 1f)
        for (i in 0 until sheet.childCount) sheet.getChildAt(i).alpha = contentAlpha
    }

    private fun playOpen() {
        motion?.removeAllListeners() // a leftover dismiss must not hide the new session
        motion?.cancel()
        dismissing = false
        sheet.alpha = 0f
        applyReveal(0f)
        capsule.alpha = 0f; capsule.scaleX = 0.9f; capsule.scaleY = 0.9f; capsule.translationY = dp(8f)
        val scrimIn = android.animation.ValueAnimator.ofInt(0, 255).apply {
            duration = 240; addUpdateListener { scrim.alpha = it.animatedValue as Int }
        }
        val pop = android.animation.AnimatorSet().apply {
            playTogether(
                android.animation.ObjectAnimator.ofFloat(capsule, View.ALPHA, 1f),
                android.animation.ObjectAnimator.ofFloat(capsule, View.SCALE_X, 1f),
                android.animation.ObjectAnimator.ofFloat(capsule, View.SCALE_Y, 1f),
                android.animation.ObjectAnimator.ofFloat(capsule, View.TRANSLATION_Y, 0f),
            )
            duration = 160; interpolator = EASE_OUT
        }
        val open = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 360; startDelay = 140; interpolator = DRAWER
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationStart(a: android.animation.Animator) { sheet.alpha = 1f }
            })
            addUpdateListener {
                val f = it.animatedValue as Float
                applyReveal(f)
                capsule.alpha = 1f - (f * 2.5f).coerceAtMost(1f) // hands off to the sheet early
            }
        }
        motion = android.animation.AnimatorSet().apply {
            play(scrimIn).with(pop)
            play(open).after(pop)
            start()
        }
    }

    /** Collapses back to the capsule and fades, then hides the session and runs [after]. */
    private fun dismiss(after: () -> Unit = {}) {
        if (dismissing) return
        dismissing = true
        motion?.cancel()
        val from = reveal
        motion = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 220; interpolator = EXIT
            addUpdateListener {
                val t = it.animatedValue as Float
                applyReveal(from * (1f - t))
                sheet.alpha = 1f - t
                scrim.alpha = ((1f - t) * 255).toInt()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) { hide(); after() }
            })
            start()
        }
    }

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()
    private fun dp(v: Float) = v * context.resources.displayMetrics.density

    private companion object {
        const val CAPSULE_W_DP = 168
        const val CAPSULE_H_DP = 52
        val EASE_OUT = android.view.animation.PathInterpolator(0.23f, 1f, 0.32f, 1f)
        val DRAWER = android.view.animation.PathInterpolator(0.32f, 0.72f, 0f, 1f)
        val EXIT = android.view.animation.PathInterpolator(0.4f, 0f, 1f, 1f)
    }
}

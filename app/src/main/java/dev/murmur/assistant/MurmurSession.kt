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
    /** Live ElevenLabs session while listening, if the cloud is on (live words, design/live-words.html B). */
    private var stream: dev.murmur.speech.CloudStream? = null
    private var state = State.IDLE
    private var startedAt = 0L

    /** The sheet follows the keyboard theme (colors, day/night), not fixed Murmur tokens. */
    private val theme = helium314.keyboard.keyboard.KeyboardTheme.getColorsForCurrentTheme(context)
    private val sheetColor = theme.get(helium314.keyboard.latin.common.ColorType.MAIN_BACKGROUND).let { bg ->
        val opaque = if (Color.alpha(bg) < 200) theme.get(helium314.keyboard.latin.common.ColorType.STRIP_BACKGROUND) else bg
        if (Color.alpha(opaque) < 200) (if (androidx.core.graphics.ColorUtils.calculateLuminance(bg) < 0.5) MurmurTokens.Dark.SHEET else MurmurTokens.Light.SHEET) else opaque
    }
    private val dark = androidx.core.graphics.ColorUtils.calculateLuminance(sheetColor) < 0.5
    private val ink = theme.get(helium314.keyboard.latin.common.ColorType.KEY_TEXT)
    private val subtle = theme.get(helium314.keyboard.latin.common.ColorType.KEY_HINT_TEXT)
    private val recording by lazy { MurmurTokens.live(context) }
    private val chipColor = theme.get(helium314.keyboard.latin.common.ColorType.FUNCTIONAL_KEY_BACKGROUND)
    private val accent = theme.get(helium314.keyboard.latin.common.ColorType.ACTION_KEY_BACKGROUND)

    private lateinit var status: TextView
    private lateinit var dot: View
    private lateinit var transcript: TextView
    private lateinit var bars: LevelBarsView
    /** Live words as a teleprompter over the dimmed waveform (design/live-words-v2.html, C). */
    private lateinit var live: dev.murmur.dictation.TeleprompterView
    private lateinit var hint: TextView
    private lateinit var target: TextView
    /** The field the accessibility service saw when the sheet opened, before the sheet took focus. */
    private var a11yTargetAtStart: String? = null
    /** The app the text will go to, for its writing style. */
    private var styleTarget: String? = null
    private lateinit var styleChip: dev.murmur.cleanup.StyleChip

    override fun onCreateContentView(): View {
        val c = context
        status = TextView(c).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(recording)
        }
        // Murmur design: red dot before the timer, shield on the chip, cursor before the target (mockup step 2)
        dot = View(c).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(recording) } }
        transcript = TextView(c).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTextColor(ink)
            visibility = View.GONE // shown once there's text
        }
        bars = LevelBarsView(c, recording, subtle)
        live = dev.murmur.dictation.TeleprompterView(c, 22f, ink, subtle, recording, lines = 3).apply { visibility = View.GONE }
        dictControls = dev.murmur.dictation.DictationControls(c, recording, chipColor, ink,
            onPause = { togglePause() }, onSend = { if (state == State.LISTENING) stopAndTranscribe() })
        glow = dictControls.glow
        ring = dictControls.ring
        sending = dev.murmur.dictation.SendingIndicator(ring, glow) { status.text = c.getString(R.string.murmur_dictation_slow) }
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
        // Murmur (option C): Cancel in the top-left corner, away from Send
        headerCancel = ImageButton(c).apply {
            setImageResource(R.drawable.ic_murmur_close)
            setColorFilter(ink)
            contentDescription = c.getString(R.string.murmur_dictation_cancel)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(chipColor) }
            setOnClickListener { dismiss() }
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
            // Murmur (design/dictation-layout.html): timer centered, writing style dropdown on the right
            addView(FrameLayout(c).apply {
                minimumHeight = dp(40)
                addView(headerCancel, FrameLayout.LayoutParams(dp(40), dp(40), Gravity.START or Gravity.CENTER_VERTICAL))
                addView(LinearLayout(c).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    addView(dot, LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(7) })
                    addView(status)
                }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
                styleChip = dev.murmur.cleanup.StyleChip(c, recording, chipColor, { styleTarget }, allowDefault = true)
                addView(styleChip, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END or Gravity.CENTER_VERTICAL))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(target, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
            addView(transcript, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
            // The waveform takes whatever height the card's minimum leaves, so the card scales with the screen
            // The waveform grows on tall screens so the card is ~40% of the screen (the rest of the card is ~250 dp)
            val barsHeight = maxOf(dp(136), (c.resources.displayMetrics.heightPixels * 0.40f).toInt() - dp(286)) // room for three teleprompter lines
            addView(reviewBox(c), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
            addView(android.widget.FrameLayout(c).apply {
                wave = this
                addView(bars, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                addView(live, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, android.view.Gravity.CENTER))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, barsHeight).apply { topMargin = dp(20); bottomMargin = dp(24) })
            controls = dictControls
            addView(dictControls, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(72)).apply { marginStart = dp(4); marginEnd = dp(4) })
            addView(hint, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        }

        // Opening motion (design/sheet-activation.html, option D): an orange listening capsule pops up,
        // then opens out into the sheet
        capsuleBars = LevelBarsView(c, Color.WHITE, Color.WHITE, WaveStyle.BARS)
        capsule = FrameLayout(c).apply {
            background = GradientDrawable().apply { cornerRadius = dp(CAPSULE_H_DP / 2f); setColor(recording) }
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
            root = this
            scrim = android.graphics.drawable.ColorDrawable(Color.argb(110, 8, 10, 16)).also { it.alpha = 0 }
            background = scrim // tapping it cancels
            setOnClickListener { dismiss() }
            // Full width on a phone screen; on the open inner screen, capped and centered so it reads like a card, not a banner
            val cardWidth = (c.resources.displayMetrics.widthPixels - dp(16)).let { if (it > dp(560)) dp(520) else ViewGroup.LayoutParams.MATCH_PARENT }
            val lp = FrameLayout.LayoutParams(cardWidth, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
                .apply { setMargins(dp(8), 0, dp(8), dp(8)) }
            addView(sheet, lp)
            val capLp = FrameLayout.LayoutParams(dp(CAPSULE_W_DP), dp(CAPSULE_H_DP), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
                .apply { bottomMargin = dp(8) + dp(4) }
            addView(capsule, capLp)
            // Keep the card above the gesture bar / nav buttons
            setOnApplyWindowInsetsListener { v, insets ->
                val nav = dev.murmur.design.SafeArea.bottom(v, insets)
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
        closeReview()
        dot.visibility = View.VISIBLE
        bars.clear()
        live.clear(); live.visibility = View.GONE
        sending.reset()
        dictControls.setPaused(false); dictControls.setPauseVisible(true)
        (dot.background as GradientDrawable).setColor(recording)
        headerCancel.visibility = View.VISIBLE
        bars.animate().cancel(); bars.alpha = 1f
        capsuleBars.clear()
        glow.clear()
        playOpen()
        target.text = targetLine()
        styleChip.refresh()
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            context.startActivity(Intent(context, MicPermissionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return hide()
        }
        if (!ModelManager.isInstalled(context) && !dev.murmur.speech.CloudEngine.active(context)) {
            context.startActivity(Intent(context, ModelActivity::class.java).putExtra(ModelActivity.EXTRA_SCREEN, "DICTATION").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return hide()
        }
        recorder.silenceStopMs = dev.murmur.settings.DictationPrefs.silenceMs(context)
        stream = dev.murmur.speech.CloudStream.start(context, object : dev.murmur.speech.CloudStream.Listener {
            override fun onWords(final: String, draft: String) {
                if (state != State.LISTENING && state != State.TRANSCRIBING) return
                if (!dev.murmur.settings.DictationPrefs.teleprompter(context)) return
                if (live.setWords(final, draft) && live.visibility != View.VISIBLE) {
                    live.visibility = View.VISIBLE; bars.animate().alpha(0.25f).setDuration(200).start()
                }
            }
        })
        recorder.onChunk = stream?.let { s -> { chunk: ShortArray -> s.send(chunk) } }
        if (!recorder.start()) { stream?.cancel(); stream = null; return showResult(context.getString(R.string.murmur_dictation_mic_busy), close = true) }
        state = State.LISTENING
        startedAt = SystemClock.elapsedRealtime()
        SharedEngine.preload(context)
        tick()
    }

    override fun onHide() {
        super.onHide()
        if (::styleChip.isInitialized) styleChip.dismissMenu()
        if (state == State.LISTENING || state == State.TRANSCRIBING) recorder.cancel()
        if (::root.isInitialized) dismissBrush()
        stream?.cancel(); stream = null
        state = State.IDLE
        if (::sending.isInitialized) sending.reset()
        main.removeCallbacksAndMessages(null) // pending typing lives in the keyboard, not here
    }

    /** Murmur: Pause keeps the recording open but stops listening and the auto-send; tap again to add more. */
    private fun togglePause() {
        if (state != State.LISTENING) return
        val now = SystemClock.elapsedRealtime()
        val paused = !recorder.paused
        if (paused) pausedAt = now else startedAt += now - pausedAt
        recorder.paused = paused
        dictControls.setPaused(paused, recording)
        (dot.background as GradientDrawable).setColor(if (paused) subtle else recording)
        if (paused) status.text = context.getString(R.string.murmur_dictation_paused)
        hint.text = if (paused) "Paused · nothing is lost · Send when you're ready" else context.getString(R.string.murmur_assistant_hint)
        bars.animate().alpha(if (paused) 0.35f else if (live.visibility == View.VISIBLE) 0.25f else 1f).setDuration(160).start()
    }
    private var pausedAt = 0L
    private lateinit var dictControls: dev.murmur.dictation.DictationControls
    private lateinit var headerCancel: ImageButton

    private fun stopAndTranscribe() {
        state = State.TRANSCRIBING
        dictControls.setPauseVisible(false)
        hint.text = context.getString(R.string.murmur_assistant_hint)
        status.setTextColor(ink)
        dot.visibility = View.GONE
        status.text = context.getString(R.string.murmur_dictation_transcribing)
        sending.start()
        val live = stream.also { stream = null }
        recorder.stop { samples ->
            SharedEngine.finish(context, live, samples) { result ->
                Cleanup.processAsync(context, result?.text.orEmpty(), styleTarget) { cleaned ->
                main.post {
                    if (state != State.TRANSCRIBING) return@post
                    val text = cleaned
                    when {
                        result == null -> showResult(context.getString(R.string.murmur_dictation_model_missing), close = true)
                        text.isEmpty() -> showResult(context.getString(R.string.murmur_dictation_nothing_heard), close = true)
                        else -> deliver(text)
                    }
                }
                }
            }
        }
    }

    /**
     * Delivers the text: Murmur's keyboard, then the accessibility service, then the clipboard.
     */
    private fun deliver(text: String) {
        sending.reset()
        if (dev.murmur.settings.DictationPrefs.needsReview(context, text)) return showReview(text)
        insert(text)
    }

    private fun insert(text: String) {
        // When the teleprompter showed the words, a second copy of the finished text isn't needed
        if (live.visibility != View.VISIBLE) { transcript.text = text; transcript.visibility = View.VISIBLE }
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

    // --- Review before inserting (design: "Murmur review before inserting") ---

    private lateinit var wave: FrameLayout
    private lateinit var controls: FrameLayout
    private lateinit var review: LinearLayout
    private lateinit var reviewText: TextView
    private lateinit var alts: LinearLayout
    private lateinit var rewrites: LinearLayout
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
    /** The text as cleanup left it, plus your word fixes: what rewrites start from. */
    private var base = ""
    private var current = ""
    private var rewrite: dev.murmur.cleanup.ClaudeCleanup.Rewrite? = null
    private val rewritten = HashMap<dev.murmur.cleanup.ClaudeCleanup.Rewrite, String>()
    private var loading: dev.murmur.cleanup.ClaudeCleanup.Rewrite? = null
    private var picked: IntRange? = null
    private var reviewToken = 0

    private fun chip(c: Context, label: String, on: Boolean = false, onClick: () -> Unit) = TextView(c).apply {
        text = label
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setTextColor(if (on) Color.WHITE else ink)
        gravity = Gravity.CENTER
        setPadding(dp(14), 0, dp(14), 0)
        minHeight = dp(36)
        background = GradientDrawable().apply { cornerRadius = dp(18f); setColor(if (on) accent else chipColor) }
        setOnClickListener { onClick() }
    }

    private fun scrollRow(c: Context, row: LinearLayout) = android.widget.HorizontalScrollView(c).apply {
        isHorizontalScrollBarEnabled = false
        row.orientation = LinearLayout.HORIZONTAL
        addView(row)
    }

    private fun reviewBox(c: Context): View {
        reviewText = TextView(c).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
            setTextColor(ink)
            setLineSpacing(0f, 1.2f)
            setPadding(0, dp(4), 0, dp(4))
        }
        attachBrush(reviewText)
        alts = LinearLayout(c)
        rewrites = LinearLayout(c)
        val insertButton = TextView(c).apply {
            text = "Insert"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { cornerRadius = dp(28f); setColor(accent) }
            setOnClickListener { if (loading == null && respeak == null) { val t = current; dismissBrush(); closeReview(keepText = true); insert(t) } }
        }
        val discard = ImageButton(c).apply {
            setImageResource(R.drawable.ic_murmur_close)
            setColorFilter(ink)
            contentDescription = c.getString(R.string.murmur_dictation_cancel)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(chipColor) }
            setOnClickListener { dismiss() }
        }
        review = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            // Long messages scroll: drag up or down to scroll, sweep sideways (or hold, then drag) to select
            addView(MaxHeightScroll(c, (c.resources.displayMetrics.heightPixels * 0.42f).toInt()).apply {
                isVerticalScrollBarEnabled = false
                addView(reviewText)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(scrollRow(c, alts).apply { visibility = View.GONE; tag = "alts" },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
            addView(scrollRow(c, rewrites).apply { tag = "rewrites" },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) })
            addView(LinearLayout(c).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(discard, LinearLayout.LayoutParams(dp(56), dp(56)))
                addView(insertButton, LinearLayout.LayoutParams(0, dp(56), 1f).apply { marginStart = dp(12) })
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(18) })
        }
        return review
    }

    private fun showReview(text: String) {
        state = State.DONE
        reviewToken++
        base = text; current = text; rewrite = null; rewritten.clear(); loading = null; picked = null
        dismissBrush()
        dot.visibility = View.GONE
        styleChip.dismissMenu(); styleChip.visibility = View.GONE
        headerCancel.visibility = View.GONE // the review has its own discard button
        status.setTextColor(ink)
        status.text = "Ready to insert"
        transcript.visibility = View.GONE
        wave.visibility = View.GONE
        controls.visibility = View.GONE
        hint.text = if (dev.murmur.cleanup.ClaudeCleanup.active(context)) "Tap a word to fix it · Sweep across words to select" else "Sweep across words to select · Tap outside to discard"
        review.visibility = View.VISIBLE
        renderText(); renderAlts(null); renderRewrites()
    }

    private fun closeReview(keepText: Boolean = false) {
        if (!::review.isInitialized) return
        reviewToken++
        dismissBrush()
        headerCancel.visibility = View.VISIBLE
        review.visibility = View.GONE
        wave.visibility = View.VISIBLE
        controls.visibility = View.VISIBLE
        hint.text = context.getString(R.string.murmur_assistant_hint)
        styleChip.visibility = View.VISIBLE
        if (!keepText) { base = ""; current = "" }
    }

    /** Each word is tappable; the picked one is highlighted. */
    private fun renderText() {
        val sp = android.text.SpannableString(current)
        picked?.let { r -> sp.setSpan(android.text.style.BackgroundColorSpan(androidx.core.graphics.ColorUtils.setAlphaComponent(accent, 110)), r.first, r.last + 1, 0) }
        brush?.let { r ->
            if (r.last < current.length) sp.setSpan(android.text.style.BackgroundColorSpan(androidx.core.graphics.ColorUtils.setAlphaComponent(accent, if (respeak != null) 150 else 80)), r.first, r.last + 1, 0)
        }
        reviewText.text = sp
        reviewText.alpha = if (loading != null || partLoading) 0.45f else 1f
    }

    /** The letters of a token without the punctuation around it, and where they sit in the text. */
    private fun core(range: IntRange): IntRange? {
        val token = current.substring(range)
        val start = token.indexOfFirst { it.isLetterOrDigit() }
        val end = token.indexOfLast { it.isLetterOrDigit() }
        if (start < 0) return null
        return (range.first + start)..(range.first + end)
    }

    private fun onWordTap(range: IntRange) {
        if (loading != null) return
        val word = core(range) ?: return
        picked = if (picked == range) null else range
        renderText()
        if (picked == null) return renderAlts(null)
        val token = ++reviewToken
        renderAlts(emptyList(), searching = dev.murmur.cleanup.ClaudeCleanup.active(context))
        if (!dev.murmur.cleanup.ClaudeCleanup.active(context)) return
        val text = current; val w = current.substring(word)
        worker.execute {
            val found = runCatching { dev.murmur.cleanup.ClaudeCleanup.alternatives(context, text, w) }.getOrDefault(emptyList())
            main.post { if (token == reviewToken && picked == range) renderAlts(found) }
        }
    }

    private fun renderAlts(options: List<String>?, searching: Boolean = false) {
        val row = review.findViewWithTag<View>("alts")
        alts.removeAllViews()
        if (options == null) { row.visibility = View.GONE; return }
        row.visibility = View.VISIBLE
        val gap = { v: View -> alts.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)).apply { marginEnd = dp(8) }) }
        if (searching) gap(chip(context, "Finding other words…") {})
        options.forEach { alt -> gap(chip(context, alt) { replacePicked(alt) }) }
        gap(chip(context, "Remove word") { replacePicked("") })
    }

    /** Swaps the picked word, keeping its punctuation. A fixed name or brand goes into your dictionary. */
    private fun replacePicked(with: String) {
        val range = picked ?: return
        val word = core(range) ?: return
        current = if (with.isEmpty()) {
            (current.substring(0, range.first).trimEnd() + " " + current.substring(range.last + 1).trimStart()).trim()
        } else current.replaceRange(word, with)
        if (with.any { it.isUpperCase() }) runCatching { dev.murmur.dictionary.MurmurDictionary.addWord(context, with) }
        if (rewrite == null) { base = current; rewritten.clear() } else rewritten[rewrite!!] = current
        picked = null
        renderText(); renderAlts(null)
    }

    private fun renderRewrites() {
        val row = review.findViewWithTag<View>("rewrites")
        rewrites.removeAllViews()
        if (!dev.murmur.cleanup.ClaudeCleanup.active(context)) { row.visibility = View.GONE; return }
        row.visibility = View.VISIBLE
        val add = { v: View -> rewrites.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)).apply { marginEnd = dp(8) }) }
        add(chip(context, "Original", on = rewrite == null) { pickRewrite(null) })
        dev.murmur.cleanup.ClaudeCleanup.Rewrite.entries.forEach { r ->
            add(chip(context, if (loading == r) r.label + "…" else r.label, on = rewrite == r || loading == r) { pickRewrite(r) })
        }
    }

    private fun pickRewrite(r: dev.murmur.cleanup.ClaudeCleanup.Rewrite?) {
        if (loading != null || partLoading || respeak != null || r == rewrite) return
        dismissBrush()
        picked = null; renderAlts(null)
        if (r == null) { rewrite = null; current = base; renderText(); renderRewrites(); return }
        rewritten[r]?.let { rewrite = r; current = it; renderText(); renderRewrites(); return }
        loading = r
        renderText(); renderRewrites()
        val token = ++reviewToken
        val from = base
        worker.execute {
            val out = runCatching { dev.murmur.cleanup.ClaudeCleanup.rewrite(context, from, r) }
            main.post {
                if (token != reviewToken) return@post
                loading = null
                out.onSuccess { rewritten[r] = it; rewrite = r; current = it }
                    .onFailure { status.text = "Couldn't rewrite: " + ((it as? dev.murmur.cleanup.ClaudeCleanup.Failure)?.reason ?: "error") }
                renderText(); renderRewrites()
            }
        }
    }

    // --- Brush to select (design: "Murmur dictation controls and editing", editor 2) ---

    /** A ScrollView that stops growing at [maxPx], so a long message scrolls inside the sheet. */
    private class MaxHeightScroll(c: Context, private val maxPx: Int) : android.widget.ScrollView(c) {
        override fun onMeasure(w: Int, h: Int) = super.onMeasure(w, MeasureSpec.makeMeasureSpec(maxPx, MeasureSpec.AT_MOST))
    }

    /** The swept words, snapped to whole words; also what Say again replaces while it listens. */
    private var brush: IntRange? = null
    private var respeak: IntRange? = null
    private var partLoading = false
    private var loupe: TextView? = null
    private var actionBar: View? = null
    private lateinit var root: FrameLayout

    private fun words(): List<IntRange> = Regex("\\S+").findAll(current).map { it.range }.toList()

    /** The word under (or nearest to) character [off]. */
    private fun wordAt(off: Int): IntRange? {
        val all = words()
        return all.firstOrNull { off in it.first..it.last + 1 } ?: all.minByOrNull { minOf(kotlin.math.abs(it.first - off), kotlin.math.abs(it.last - off)) }
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun attachBrush(text: TextView) {
        val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop
        var downX = 0f; var downY = 0f
        var mode = 0 // 0: not decided yet, 1: brushing, 2: scrolling (the ScrollView has it)
        var anchor: IntRange? = null
        fun begin() {
            mode = 1
            text.parent?.requestDisallowInterceptTouchEvent(true)
            anchor = wordAt(text.getOffsetForPosition(downX, downY))
            brush = anchor; picked = null; renderAlts(null); renderText()
            showLoupe(text, downX, downY)
        }
        val hold = Runnable { if (mode == 0) { text.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK); begin() } }
        text.setOnTouchListener { v, e ->
            if (loading != null || partLoading || respeak != null) return@setOnTouchListener true
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    downX = e.x; downY = e.y; mode = 0
                    dismissActions()
                    v.postDelayed(hold, 260)
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val dx = kotlin.math.abs(e.x - downX); val dy = kotlin.math.abs(e.y - downY)
                    if (mode == 0) {
                        if (dx > slop / 2f && dx >= dy) { v.removeCallbacks(hold); begin() } // a sideways sweep selects
                        else if (dy > slop) { v.removeCallbacks(hold); mode = 2; return@setOnTouchListener false } // up or down scrolls
                    }
                    if (mode == 1) {
                        val a = anchor; val w = wordAt(text.getOffsetForPosition(e.x, e.y))
                        if (a != null && w != null) {
                            val r = minOf(a.first, w.first)..maxOf(a.last, w.last)
                            if (r != brush) { brush = r; renderText(); v.performHapticFeedback(android.view.HapticFeedbackConstants.TEXT_HANDLE_MOVE) }
                        }
                        showLoupe(text, e.x, e.y)
                    }
                }
                android.view.MotionEvent.ACTION_UP -> {
                    v.removeCallbacks(hold); hideLoupe()
                    when (mode) {
                        0 -> { brush = null; wordAt(text.getOffsetForPosition(e.x, e.y))?.let { onWordTap(it) } } // a tap still fixes one word
                        1 -> brush?.let { showActions(text, it) }
                    }
                    mode = 0
                }
                android.view.MotionEvent.ACTION_CANCEL -> {
                    v.removeCallbacks(hold); hideLoupe()
                    if (mode == 1) { brush = null; renderText() }
                    mode = 0
                }
            }
            true
        }
    }

    /** Where [x], [y] inside [v] lands in the root frame. */
    private fun inRoot(v: View, x: Float, y: Float): Pair<Float, Float> {
        val a = IntArray(2); val b = IntArray(2)
        v.getLocationInWindow(a); root.getLocationInWindow(b)
        return (a[0] - b[0] + x) to (a[1] - b[1] + y)
    }

    /** A magnified copy of the word under your finger, just above the line, so the finger never hides it. */
    private fun showLoupe(text: TextView, x: Float, y: Float) {
        val layout = text.layout ?: return
        val w = wordAt(text.getOffsetForPosition(x, y)) ?: return
        val l = loupe ?: TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(if (dark) 0xFF0B0D11.toInt() else Color.WHITE)
            setPadding(dp(14), dp(6), dp(14), dp(6))
            background = GradientDrawable().apply { cornerRadius = dp(16f); setColor(if (dark) 0xFFF4F5F8.toInt() else 0xFF1A1D23.toInt()) }
            elevation = dp(20f)
            root.addView(this, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }.also { loupe = it }
        l.text = current.substring(w)
        l.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val line = layout.getLineForVertical((y - text.totalPaddingTop).toInt())
        val (rx, ry) = inRoot(text, x, (layout.getLineTop(line) + text.totalPaddingTop).toFloat())
        l.x = (rx - l.measuredWidth / 2f).coerceIn(dp(8f), root.width - l.measuredWidth - dp(8f))
        l.y = ry - l.measuredHeight - dp(12f)
        l.visibility = View.VISIBLE
    }

    private fun hideLoupe() { loupe?.visibility = View.GONE }

    /** Say again, Rewrite (with a Claude key), Delete and Copy, floating just above the selection. */
    private fun showActions(text: TextView, range: IntRange) {
        dismissActions()
        val layout = text.layout ?: return
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = GradientDrawable().apply { cornerRadius = dp(24f); setColor(0xFF2F333D.toInt()) }
            elevation = dp(20f)
        }
        fun action(icon: Int, label: String?, primary: Boolean = false, desc: String = label ?: "", onClick: () -> Unit) =
            bar.addView(TextView(context).apply {
                this.text = label ?: ""
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                compoundDrawablePadding = dp(6)
                setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0)
                androidx.core.widget.TextViewCompat.setCompoundDrawableTintList(this, android.content.res.ColorStateList.valueOf(Color.WHITE))
                setPadding(dp(14), 0, dp(if (label == null) 8 else 16), 0)
                minWidth = dp(44)
                if (primary) background = GradientDrawable().apply { cornerRadius = dp(20f); setColor(recording) }
                contentDescription = desc
                setOnClickListener { onClick() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)))
        action(R.drawable.ic_murmur_mic, "Say again", primary = true) { startRespeak(range) }
        if (dev.murmur.cleanup.ClaudeCleanup.active(context)) action(R.drawable.ic_murmur_spark, null, desc = "Rewrite") { rewriteBrush(range) }
        action(R.drawable.ic_murmur_trash, null, desc = "Delete") { replaceBrush(range, "") }
        action(R.drawable.ic_murmur_copy, null, desc = "Copy") {
            context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.murmur_assistant_clip_label), current.substring(range)))
            status.text = "Copied"; dismissBrush()
        }
        root.addView(bar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        actionBar = bar
        bar.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val firstLine = layout.getLineForOffset(range.first)
        val (rx, ry) = inRoot(text, layout.getPrimaryHorizontal(range.first), (layout.getLineTop(firstLine) + text.totalPaddingTop).toFloat())
        bar.x = rx.coerceIn(dp(8f), (root.width - bar.measuredWidth - dp(8f)).coerceAtLeast(dp(8f)))
        bar.y = (ry - bar.measuredHeight - dp(10f)).let { if (it < dp(8f)) {
            val lastLine = layout.getLineForOffset(range.last)
            inRoot(text, 0f, (layout.getLineBottom(lastLine) + text.totalPaddingTop).toFloat()).second + dp(10f)
        } else it }
    }

    private fun dismissActions() { actionBar?.let { root.removeView(it) }; actionBar = null }

    /** Clears the selection, the loupe and the actions; stops a Say again that's still listening. */
    private fun dismissBrush() {
        dismissActions(); hideLoupe()
        if (respeak != null) { recorder.cancel(); respeak = null }
        partLoading = false
        if (brush != null) { brush = null; if (::reviewText.isInitialized) renderText() }
    }

    /** Puts [with] where [range] was, tidying the spaces and punctuation around it, and keeps the edit. */
    private fun replaceBrush(range: IntRange, with: String) {
        if (range.last >= current.length) return dismissBrush()
        val before = current.substring(0, range.first).trimEnd()
        val after = current.substring(range.last + 1).trimStart()
        current = listOf(before, with.trim(), after).filter { it.isNotEmpty() }.joinToString(" ")
            .replace(Regex(" +([.,!?;:])"), "$1").trim()
        if (with.any { it.isUpperCase() } && !with.trim().contains(' ')) runCatching { dev.murmur.dictionary.MurmurDictionary.addWord(context, with.trim().trim('.', ',', '!', '?')) }
        if (rewrite == null) { base = current; rewritten.clear() } else rewritten[rewrite!!] = current
        dismissActions(); brush = null; picked = null
        renderText(); renderAlts(null)
    }

    private fun rewriteBrush(range: IntRange) {
        dismissActions()
        partLoading = true; renderText()
        status.text = "Rewriting…"
        val token = ++reviewToken
        val from = current
        worker.execute {
            val out = runCatching { dev.murmur.cleanup.ClaudeCleanup.rewritePart(context, from, range) }
            main.post {
                if (token != reviewToken || current != from) return@post
                partLoading = false
                out.onSuccess { status.text = "Ready to insert"; replaceBrush(range, it) }
                    .onFailure { status.text = "Couldn't rewrite: " + ((it as? dev.murmur.cleanup.ClaudeCleanup.Failure)?.reason ?: "error"); brush = null; renderText() }
            }
        }
    }

    /** Say again: listens for the new words and puts them in place of the selection. Stops when you go quiet. */
    private fun startRespeak(range: IntRange) {
        dismissActions()
        respeak = range; brush = range; renderText()
        recorder.silenceStopMs = dev.murmur.settings.DictationPrefs.silenceMs(context)
        recorder.onChunk = null
        if (!recorder.start()) { respeak = null; brush = null; renderText(); status.text = context.getString(R.string.murmur_dictation_mic_busy); return }
        dot.visibility = View.VISIBLE
        (dot.background as GradientDrawable).setColor(recording)
        status.text = "Say the new words…"
        // One button while it listens: Done (it also stops on its own when you go quiet)
        val done = TextView(context).apply {
            text = "Done"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(22), 0, dp(22), 0)
            background = GradientDrawable().apply { cornerRadius = dp(22f); setColor(recording) }
            elevation = dp(20f)
            setOnClickListener { finishRespeak() }
        }
        root.addView(done, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)))
        actionBar = done
        done.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val layout = reviewText.layout
        if (layout != null) {
            val line = layout.getLineForOffset(range.first)
            val (rx, ry) = inRoot(reviewText, layout.getPrimaryHorizontal(range.first), (layout.getLineTop(line) + reviewText.totalPaddingTop).toFloat())
            done.x = rx.coerceIn(dp(8f), (root.width - done.measuredWidth - dp(8f)).coerceAtLeast(dp(8f)))
            done.y = (ry - done.measuredHeight - dp(10f)).coerceAtLeast(dp(8f))
        }
    }

    private fun finishRespeak() {
        val range = respeak ?: return
        dismissActions()
        dot.visibility = View.GONE
        status.text = context.getString(R.string.murmur_dictation_transcribing)
        val token = reviewToken
        recorder.stop { samples ->
            SharedEngine.finish(context, null, samples) { result ->
                val said = result?.text.orEmpty().trim()
                val cleaned = if (said.isEmpty()) "" else Cleanup.process(context, said, styleTarget)
                main.post {
                    if (respeak != range || token != reviewToken) return@post
                    respeak = null
                    status.text = "Ready to insert"
                    if (cleaned.isEmpty()) { status.text = context.getString(R.string.murmur_dictation_nothing_heard); brush = null; renderText(); return@post }
                    replaceBrush(range, fitInto(cleaned, current.substring(range), range.first))
                }
            }
        }
    }

    /** Makes re-spoken words fit where they go: no sentence-ending period mid-sentence, and the old ending kept. */
    private fun fitInto(new: String, old: String, at: Int): String {
        var t = new.trim()
        val oldEnd = old.trimEnd().lastOrNull()
        t = if (oldEnd != null && oldEnd in ".!?,;:") t.trimEnd('.', '!', '?', ',', ';', ':') + oldEnd else t.trimEnd('.')
        val sentenceStart = current.substring(0, at).trimEnd().let { it.isEmpty() || it.last() in ".!?" }
        // a phrase mid-sentence starts lowercase; a single word keeps its capital (it may be a name)
        if (!sentenceStart && t.contains(' ') && t.first().isUpperCase() && t.getOrNull(1)?.isLowerCase() == true && !t.startsWith("I ")) t = t.replaceFirstChar { it.lowercase() }
        return t
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
        styleTarget = target ?: MurmurAccessibilityService.instance?.activeAppPackage()
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
        sending.reset()
        state = State.DONE
        dot.visibility = View.GONE
        styleChip.dismissMenu()
        styleChip.visibility = View.GONE // a long message gets the whole row
        status.setTextColor(ink)
        status.text = message
        if (close) main.postDelayed({ dismiss() }, 1600)
    }

    private fun tick() {
        if (state != State.LISTENING) return
        if (recorder.paused) { main.postDelayed({ tick() }, 250); return }
        val s = (SystemClock.elapsedRealtime() - startedAt) / 1000
        status.setTextColor(ink)
        status.text = "%d:%02d".format(s / 60, s % 60)
        main.postDelayed({ tick() }, 250)
    }

    // MicRecorder.Listener: called on the recording thread

    override fun onLevel(rms: Float) { main.post { bars.add(rms); capsuleBars.add(rms); glow.add(rms) } }
    override fun onAutoStop() { main.post { if (respeak != null) finishRespeak() else if (state == State.LISTENING) stopAndTranscribe() } }
    override fun onNoSpeech() { main.post {
        if (respeak != null) { respeak = null; dismissActions(); brush = null; dot.visibility = View.GONE; status.text = context.getString(R.string.murmur_dictation_nothing_heard); renderText() }
        else if (state == State.LISTENING) showResult(context.getString(R.string.murmur_dictation_nothing_heard), close = true)
    } }

    // --- Motion: capsule to sheet ---

    private lateinit var sheet: LinearLayout
    private lateinit var sheetBackground: GradientDrawable
    private lateinit var capsule: FrameLayout
    private lateinit var capsuleBars: LevelBarsView
    private lateinit var glow: StopGlowView
    private lateinit var ring: dev.murmur.dictation.SendRingView
    private lateinit var sending: dev.murmur.dictation.SendingIndicator
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
        sheetBackground.setColor(androidx.core.graphics.ColorUtils.blendARGB(recording, sheetColor, f))
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

package dev.murmur.dictation

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.settings.Settings

/** The listening panel shown in place of the keys: status, live level bars, cancel and stop. */
@SuppressLint("ViewConstructor")
class DictationPanel(
    context: Context,
    onCancel: () -> Unit,
    onStop: () -> Unit,
    /** Murmur: the cleanup toggle at the right of the controls; returns whether cleanup is now on. */
    private val onToggleCleanup: () -> Boolean,
    cleanupOn: Boolean,
    /** Murmur: the app being dictated into, for the writing style chip. */
    stylePkg: () -> String? = { null },
) : LinearLayout(context) {

    private val colors = Settings.getValues().mColors
    private val recording = dev.murmur.design.MurmurTokens.live(context)
    private val ink = colors.get(ColorType.KEY_TEXT)
    private val subtle = colors.get(ColorType.KEY_HINT_TEXT)
    private val fnBackground = colors.get(ColorType.FUNCTIONAL_KEY_BACKGROUND)

    private val status = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setTextColor(ink)
        isSingleLine = true
        fontFeatureSettings = "tnum"
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    /** Live words from ElevenLabs as a teleprompter over the dimmed waveform (design/live-words-v2.html, C). */
    private val words = TeleprompterView(context, 20f, ink, subtle, recording).apply { visibility = GONE }
    /** Red dot before the timer, as in the mockup; hidden for messages. */
    private val dot = android.view.View(context).apply {
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(recording) }
    }
    private val accent = colors.get(ColorType.ACTION_KEY_BACKGROUND)
    private val styleChip = dev.murmur.cleanup.StyleChip(context, accent, fnBackground, stylePkg)
    /** Messages ("Didn't catch that") sit in the middle of the teleprompter, not in the strip. */
    private val message = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setTextColor(ink)
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        gravity = Gravity.CENTER
        setPadding(dp(16), 0, dp(16), 0)
        visibility = GONE
    }

    /** Murmur (design/dictation-layout.html): the strip shows these while listening, timer centered and style on the right. */
    val headerCenter: android.view.View = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(dot, LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(7) })
        addView(status)
    }
    val headerEnd: android.view.View get() = styleChip
    private lateinit var cleanupButton: ImageButton
    private val waveform = LevelBarsView(context, recording, subtle)
    private val glow = StopGlowView(context, recording, 34f)
    /** Ring around Send while the transcript is on its way (design/send-loading.html, A). */
    private val ring = SendRingView(context, recording)
    private val sending = SendingIndicator(ring, glow) { status.text = context.getString(R.string.murmur_dictation_slow) }

    init {
        orientation = VERTICAL
        setPadding(dp(16), dp(4), dp(16), dp(12))
        val bg = colors.get(ColorType.MAIN_BACKGROUND)
        setBackgroundColor(if (Color.alpha(bg) < 200) colors.get(ColorType.STRIP_BACKGROUND) else bg)
        isClickable = true // keep touches from reaching the hidden keys

        // Breathing room above (status row) and below (the Send glow); live words sit centered over the waveform
        addView(FrameLayout(context).apply {
            addView(waveform, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            addView(words, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            addView(message, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        }, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(4); bottomMargin = dp(12) })
        clipChildren = false // the Send glow spills past the controls row
        clipToPadding = false // ...and into the bottom padding, instead of being cut where the padding starts
        addView(FrameLayout(context).apply {
            clipChildren = false
            addView(glow, FrameLayout.LayoutParams(dp(132), dp(132), Gravity.CENTER))
            addView(ring, FrameLayout.LayoutParams(dp(84), dp(84), Gravity.CENTER))
            addView(roundButton(context, R.drawable.ic_murmur_close, R.string.murmur_dictation_cancel, fnBackground, ink, 52, onCancel),
                FrameLayout.LayoutParams(dp(52), dp(52), Gravity.START or Gravity.CENTER_VERTICAL))
            addView(roundButton(context, R.drawable.ic_murmur_stop, R.string.murmur_dictation_stop, recording, Color.WHITE, 68, onStop),
                FrameLayout.LayoutParams(dp(68), dp(68), Gravity.CENTER))
            cleanupButton = roundButton(context, R.drawable.ic_murmur_spark, R.string.murmur_cleanup_on, fnBackground, ink, 52) {
                styleCleanup(onToggleCleanup()); styleChip.refresh()
            }
            addView(cleanupButton, FrameLayout.LayoutParams(dp(52), dp(52), Gravity.END or Gravity.CENTER_VERTICAL))
            styleCleanup(cleanupOn)
        }, LayoutParams(LayoutParams.MATCH_PARENT, dp(72)))
    }

    /** On: accent icon on an accent-tinted circle. Off: plain function-key circle. */
    fun styleCleanup(on: Boolean) {
        cleanupButton.setColorFilter(if (on) accent else subtle)
        (cleanupButton.background as GradientDrawable).setColor(
            if (on) androidx.core.graphics.ColorUtils.blendARGB(fnBackground, accent, 0.16f) else fnBackground)
        cleanupButton.contentDescription = context.getString(if (on) R.string.murmur_cleanup_on else R.string.murmur_cleanup_off)
    }

    fun showWords(final: String, draft: String) {
        if (!dev.murmur.settings.DictationPrefs.teleprompter(context)) return
        val show = words.setWords(final, draft)
        if (show && words.visibility != VISIBLE) { words.visibility = VISIBLE; waveform.animate().alpha(0.25f).setDuration(200).start() }
    }

    fun reset() {
        words.clear(); words.visibility = GONE
        sending.reset()
        waveform.animate().cancel(); waveform.alpha = 1f
        waveform.clear()
        glow.clear()
        message.visibility = GONE
        styleChip.dismissMenu()
    }

    private var timing = false

    /** The strip says "Listening…"; the panel shows the timer (or that the model is still loading). */
    fun showListening(loading: Boolean) {
        styleChip.refresh()
        message.visibility = GONE
        dot.visibility = VISIBLE
        timing = !loading
        status.text = if (loading) context.getString(R.string.murmur_dictation_loading) else "0:00"
    }

    fun setElapsed(ms: Long) {
        val s = ms / 1000
        if (timing) status.text = "%d:%02d".format(s / 60, s % 60)
    }

    /** After Send: "Transcribing…", and the ring if the wait passes 300 ms. */
    fun showSending() {
        timing = false
        dot.visibility = GONE
        status.text = context.getString(R.string.murmur_dictation_transcribing)
        styleChip.dismissMenu()
        sending.start()
    }

    fun showMessage(text: String) {
        sending.reset()
        timing = false
        dot.visibility = GONE
        status.text = ""
        styleChip.dismissMenu()
        words.visibility = GONE
        waveform.animate().alpha(0.25f).setDuration(200).start()
        message.text = text
        message.visibility = VISIBLE
    }

    fun addLevel(rms: Float) { waveform.add(rms); glow.add(rms) }

    private fun roundButton(context: Context, icon: Int, label: Int, fill: Int, tint: Int, sizeDp: Int, onClick: () -> Unit) =
        ImageButton(context).apply {
            setImageResource(icon)
            setColorFilter(tint)
            contentDescription = context.getString(label)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(fill) }
            minimumWidth = dp(sizeDp); minimumHeight = dp(sizeDp)
            setOnClickListener { onClick() }
        }

    private fun pill(color: Int) = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(color) }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent?) = true

    companion object {
        /** Recording orange; reads on both light and dark keyboards. */
    }
}

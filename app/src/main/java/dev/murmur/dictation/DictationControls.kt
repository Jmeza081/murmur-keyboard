package dev.murmur.dictation

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import helium314.keyboard.latin.R

/**
 * Murmur: the controls under the waveform, in the keyboard and the power-button sheet
 * (design: "Murmur dictation controls and editing", option C). Pause on the left, a labeled Send pill on the
 * right under the thumb. Cancel lives in the top-left corner instead, away from Send.
 * The voice glow and the waiting ring wrap the pill.
 */
@SuppressLint("ViewConstructor")
class DictationControls(
    context: Context, accent: Int, private val fill: Int, private val ink: Int,
    onPause: () -> Unit, onSend: () -> Unit,
) : FrameLayout(context) {
    private val d = resources.displayMetrics.density
    val glow = StopGlowView(context, accent, PILL_H / 2f, PILL_W.toFloat())
    val ring = SendRingView(context, accent)
    private val pause = ImageButton(context).apply {
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(fill) }
        setOnClickListener { onPause() }
    }
    val send = TextView(context).apply {
        text = context.getString(R.string.murmur_dictation_send)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        setTextColor(dev.murmur.design.MurmurThemes.onAccent(accent)) // dark text on light accents (Moss, Mono)
        androidx.core.widget.TextViewCompat.setCompoundDrawableTintList(this, android.content.res.ColorStateList.valueOf(dev.murmur.design.MurmurThemes.onAccent(accent)))
        gravity = Gravity.CENTER
        compoundDrawablePadding = px(8)
        setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_murmur_stop, 0, 0, 0)
        setPadding(px(20), 0, px(26), 0)
        background = GradientDrawable().apply { cornerRadius = px(PILL_H) / 2f; setColor(accent) }
        contentDescription = context.getString(R.string.murmur_dictation_stop)
        setOnClickListener { onSend() }
    }

    init {
        clipChildren = false
        val glowPad = 40
        addView(glow, LayoutParams(px(PILL_W + 2 * glowPad), px(PILL_H + 2 * glowPad), Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = -px(glowPad) })
        addView(ring, LayoutParams(px(PILL_W + 12), px(PILL_H + 12), Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = -px(6) })
        addView(pause, LayoutParams(px(52), px(52), Gravity.START or Gravity.CENTER_VERTICAL))
        addView(send, LayoutParams(px(PILL_W), px(PILL_H), Gravity.END or Gravity.CENTER_VERTICAL))
        setPaused(false)
    }

    /** Paused: the button turns into an accent-tinted mic that resumes. */
    fun setPaused(paused: Boolean, accent: Int = ink) {
        pause.setImageResource(if (paused) R.drawable.ic_murmur_mic else R.drawable.ic_murmur_pause)
        pause.setColorFilter(if (paused) accent else ink)
        (pause.background as GradientDrawable).setColor(if (paused) androidx.core.graphics.ColorUtils.blendARGB(fill, accent, 0.18f) else fill)
        pause.contentDescription = context.getString(if (paused) R.string.murmur_dictation_resume else R.string.murmur_dictation_pause)
    }

    /** Hidden while the text is on its way, so a second tap can't pause or send twice. */
    fun setPauseVisible(show: Boolean) { pause.visibility = if (show) VISIBLE else INVISIBLE }

    private fun px(v: Int) = (v * d).toInt()

    companion object { const val PILL_W = 148; const val PILL_H = 60 }
}

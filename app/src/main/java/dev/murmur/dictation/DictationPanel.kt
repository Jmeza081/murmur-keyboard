package dev.murmur.dictation

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
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
) : LinearLayout(context) {

    private val colors = Settings.getValues().mColors
    private val ink = colors.get(ColorType.KEY_TEXT)
    private val subtle = colors.get(ColorType.KEY_HINT_TEXT)
    private val fnBackground = colors.get(ColorType.FUNCTIONAL_KEY_BACKGROUND)

    private val status = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setTextColor(RECORDING)
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private val chip = TextView(context).apply {
        text = context.getString(R.string.murmur_dictation_on_device)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setTextColor(ink)
        setPadding(dp(10), dp(4), dp(10), dp(4))
        background = pill(fnBackground)
    }
    private val waveform = Waveform(context)

    init {
        orientation = VERTICAL
        setPadding(dp(16), dp(12), dp(16), dp(12))
        val bg = colors.get(ColorType.MAIN_BACKGROUND)
        setBackgroundColor(if (Color.alpha(bg) < 200) colors.get(ColorType.STRIP_BACKGROUND) else bg)
        isClickable = true // keep touches from reaching the hidden keys

        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(status, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            addView(chip)
        })
        addView(waveform, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        addView(FrameLayout(context).apply {
            addView(roundButton(context, R.drawable.ic_murmur_close, R.string.murmur_dictation_cancel, fnBackground, ink, 52, onCancel),
                FrameLayout.LayoutParams(dp(52), dp(52), Gravity.START or Gravity.CENTER_VERTICAL))
            addView(roundButton(context, R.drawable.ic_murmur_stop, R.string.murmur_dictation_stop, RECORDING, Color.WHITE, 68, onStop),
                FrameLayout.LayoutParams(dp(68), dp(68), Gravity.CENTER))
        }, LayoutParams(LayoutParams.MATCH_PARENT, dp(72)))
    }

    fun reset() {
        waveform.clear()
        chip.visibility = VISIBLE
    }

    fun showListening(loading: Boolean) {
        status.setTextColor(RECORDING)
        status.text = context.getString(if (loading) R.string.murmur_dictation_loading else R.string.murmur_dictation_listening)
    }

    fun setElapsed(ms: Long) {
        val s = ms / 1000
        if (status.text.startsWith(context.getString(R.string.murmur_dictation_listening))) {
            status.text = "%s  %d:%02d".format(context.getString(R.string.murmur_dictation_listening), s / 60, s % 60)
        }
    }

    fun showMessage(text: String) {
        status.setTextColor(ink)
        status.text = text
    }

    fun addLevel(rms: Float) = waveform.add(rms)

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

    /** Scrolling bars of recent mic levels. */
    private inner class Waveform(context: Context) : View(context) {
        private val levels = ArrayDeque<Float>()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = RECORDING }
        private val idle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = subtle; alpha = 90 }
        private val rect = RectF()

        fun clear() { levels.clear(); invalidate() }
        fun add(rms: Float) {
            levels.addLast(rms)
            while (levels.size > BARS) levels.removeFirst()
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val barW = dp(4).toFloat()
            val gap = dp(3).toFloat()
            val count = minOf(BARS, ((width + gap) / (barW + gap)).toInt())
            val startX = (width - (count * (barW + gap) - gap)) / 2
            val mid = height / 2f
            for (i in 0 until count) {
                val idx = levels.size - count + i
                val level = if (idx >= 0) levels.elementAt(idx) else 0f
                val h = (dp(6) + (height * 0.8f) * minOf(1f, level * 8f)).coerceAtMost(height.toFloat())
                val x = startX + i * (barW + gap)
                rect.set(x, mid - h / 2, x + barW, mid + h / 2)
                canvas.drawRoundRect(rect, barW / 2, barW / 2, if (idx >= 0) paint else idle)
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent?) = true

    private companion object {
        const val BARS = 48
        /** Recording orange; reads on both light and dark keyboards. */
        val RECORDING = Color.parseColor("#E8590C")
    }
}

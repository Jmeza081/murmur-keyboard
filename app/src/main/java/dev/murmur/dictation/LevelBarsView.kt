package dev.murmur.dictation

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/** Scrolling bars of recent mic levels, newest on the right. */
@SuppressLint("ViewConstructor")
class LevelBarsView(context: Context, barColor: Int, idleColor: Int) : View(context) {
    private val levels = ArrayDeque<Float>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = barColor }
    private val idle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = idleColor; alpha = 90 }
    private val rect = RectF()
    private val density = resources.displayMetrics.density

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    fun clear() { levels.clear(); invalidate() }

    fun add(rms: Float) {
        levels.addLast(rms)
        while (levels.size > BARS) levels.removeFirst()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val barW = 4 * density
        val gap = 3 * density
        val count = minOf(BARS, ((width + gap) / (barW + gap)).toInt())
        val startX = (width - (count * (barW + gap) - gap)) / 2
        val mid = height / 2f
        for (i in 0 until count) {
            val idx = levels.size - count + i
            val level = if (idx >= 0) levels.elementAt(idx) else 0f
            val h = (6 * density + height * 0.8f * minOf(1f, level * 8f)).coerceAtMost(height.toFloat())
            val x = startX + i * (barW + gap)
            rect.set(x, mid - h / 2, x + barW, mid + h / 2)
            canvas.drawRoundRect(rect, barW / 2, barW / 2, if (idx >= 0) paint else idle)
        }
    }

    private companion object { const val BARS = 48 }
}

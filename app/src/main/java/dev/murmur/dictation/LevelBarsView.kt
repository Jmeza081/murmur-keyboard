package dev.murmur.dictation

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The live listening waveform (design/waveforms.html): Dot matrix, Thread or Bars.
 * [style] defaults to the user's choice; the power-button capsule forces Bars.
 */
class LevelBarsView(
    context: Context, barColor: Int, idleColor: Int,
    /** Set for the power-button capsule, which always shows bars; otherwise the user's choice. */
    private val fixedStyle: WaveStyle? = null,
) : View(context) {
    private var style = fixedStyle ?: WaveStyle.get(context)
    private val levels = ArrayDeque<Float>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = barColor }
    private val idle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = idleColor; alpha = 90 }
    private val unlitDot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = idleColor; alpha = 46 }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = barColor; this.style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
    }
    private val rect = RectF()
    private val path = Path()
    private val density = resources.displayMetrics.density
    private val baseAlpha = paint.alpha
    private var smoothed = 0f
    /** Thread only: eased toward [smoothed] every frame, so the lines swell smoothly. */
    private var shown = 0f

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    /** Resets for a new recording and picks up a style changed in settings. */
    fun clear() { levels.clear(); smoothed = 0f; shown = 0f; style = fixedStyle ?: WaveStyle.get(context); invalidate() }

    fun add(rms: Float) {
        smoothed = WaveStyle.smooth(smoothed, WaveStyle.normalize(rms))
        levels.addLast(smoothed)
        while (levels.size > HISTORY) levels.removeFirst()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        when (style) {
            WaveStyle.BARS -> drawBars(canvas)
            WaveStyle.DOTS -> drawDots(canvas)
            WaveStyle.THREAD -> drawThread(canvas)
        }
    }

    private fun level(fromEnd: Int) = levels.elementAtOrNull(levels.size - 1 - fromEnd)

    /** Rounded bars scrolling left; older bars fade (25% to 100%). */
    private fun drawBars(canvas: Canvas) {
        val barW = 4 * density
        val gap = 3 * density
        val count = ((width + gap) / (barW + gap)).toInt().coerceAtMost(HISTORY)
        val startX = (width - (count * (barW + gap) - gap)) / 2
        val mid = height / 2f
        for (i in 0 until count) {
            val v = level(count - 1 - i)
            val h = 6 * density + (height - 6 * density) * (v ?: 0f)
            val x = startX + i * (barW + gap)
            rect.set(x, mid - h / 2, x + barW, mid + h / 2)
            val p = if (v != null) paint.apply { alpha = (baseAlpha * (0.25f + 0.75f * i / count)).toInt() } else idle
            canvas.drawRoundRect(rect, barW / 2, barW / 2, p)
        }
        paint.alpha = baseAlpha
    }

    /** 9 rows of dots; each column lights from the middle out by its level, newest at the right. */
    private fun drawDots(canvas: Canvas) {
        val gap = 9 * density
        val r = 2 * density
        val rows = ROWS
        val cols = (width / gap).toInt().coerceAtMost(HISTORY)
        val x0 = (width - (cols - 1) * gap) / 2
        val y0 = (height - (rows - 1) * gap) / 2
        val half = (rows - 1) / 2f
        for (c in 0 until cols) {
            val v = level(cols - 1 - c) ?: 0f
            val lit = (v * (half + 0.6f)).roundToInt()
            paint.alpha = (baseAlpha * (0.35f + 0.65f * c / cols)).toInt()
            for (row in 0 until rows) {
                val d = abs(row - half)
                val on = d <= lit - 1 || (d == 0f && v > 0.02f)
                canvas.drawCircle(x0 + c * gap, y0 + row * gap, r, if (on) paint else unlitDot)
            }
        }
        paint.alpha = baseAlpha
    }

    /** Three sine strokes that weave and swell with the voice, tapered at both ends. */
    private fun drawThread(canvas: Canvas) {
        shown += (smoothed - shown) * 0.25f
        val animate = ValueAnimator.areAnimatorsEnabled()
        val t = if (animate) System.nanoTime() / 1e9 else 0.0
        val mid = height / 2f
        val amp = (0.08f + shown * 0.92f) * (height / 2f - 6 * density)
        stroke.strokeWidth = 2 * density
        for ((alpha, k, phase) in THREADS) {
            stroke.alpha = (255 * alpha).toInt()
            path.reset()
            var x = 0f
            while (x <= width) {
                val u = x / width
                val taper = sin(PI * u).pow(1.6).toFloat()
                val y = mid + sin(u * PI * 2 * k + t * (2 + k) + phase).toFloat() * amp * taper * (if (alpha == 1f) 1f else 0.8f)
                if (x == 0f) path.moveTo(x, y) else path.lineTo(x, y)
                x += 2 * density
            }
            canvas.drawPath(path, stroke)
        }
        if (animate && isAttachedToWindow) postInvalidateOnAnimation()
    }

    private companion object {
        const val HISTORY = 64
        const val ROWS = 9
        val THREADS = listOf(Triple(1f, 1.0, 0.0), Triple(0.55f, 1.6, 2.1), Triple(0.3f, 2.3, 4.2))
    }
}

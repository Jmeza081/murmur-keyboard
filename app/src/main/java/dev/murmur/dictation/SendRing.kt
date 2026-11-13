package dev.murmur.dictation

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.TextView

/**
 * Murmur: an indeterminate progress ring drawn around the Send button while the transcript is on its way
 * (design/send-loading.html, option A). The arc grows and shrinks while it turns.
 */
class SendRingView(context: Context, private val color: Int) : View(context) {
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3 * density; strokeCap = Paint.Cap.ROUND; this.color = this@SendRingView.color
    }
    private val oval = RectF()
    private var startedAt = 0L
    private val ease = PathInterpolator(0.77f, 0f, 0.175f, 1f)

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO; alpha = 0f; visibility = INVISIBLE }

    fun appear() {
        startedAt = SystemClock.uptimeMillis()
        visibility = VISIBLE
        animate().cancel()
        scaleX = 0.92f; scaleY = 0.92f
        animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(220).setInterpolator(PathInterpolator(0.23f, 1f, 0.32f, 1f)).start()
        invalidate()
    }

    fun hide() { animate().cancel(); alpha = 0f; visibility = INVISIBLE }

    override fun onDraw(canvas: Canvas) {
        if (visibility != VISIBLE) return
        val t = SystemClock.uptimeMillis() - startedAt
        val cycle = t / CYCLE_MS
        val p = (t % CYCLE_MS) / CYCLE_MS.toFloat()
        // First half: the head runs ahead (arc grows). Second half: the tail catches up (arc shrinks).
        val grow = if (p < 0.5f) ease.getInterpolation(p * 2) else 1f
        val shrink = if (p < 0.5f) 0f else ease.getInterpolation((p - 0.5f) * 2)
        val sweep = MIN_SWEEP + (MAX_SWEEP - MIN_SWEEP) * (grow - shrink)
        val start = cycle * (MAX_SWEEP - MIN_SWEEP) + (MAX_SWEEP - MIN_SWEEP) * shrink + t * 360f / SPIN_MS - 90f
        val inset = paint.strokeWidth / 2
        oval.set(inset, inset, width - inset, height - inset)
        canvas.drawArc(oval, start % 360f, sweep, false, paint)
        postInvalidateOnAnimation()
    }

    private companion object {
        const val CYCLE_MS = 1400L
        const val SPIN_MS = 2200f
        const val MIN_SWEEP = 14f
        const val MAX_SWEEP = 270f
    }
}

/**
 * Murmur: the waiting state after Send. Shows the ring only if the wait passes 300 ms (so a fast reply never
 * flashes it), fades the voice glow, and after 3 s says the connection is slow.
 */
class SendingIndicator(private val ring: SendRingView, private val glow: View, private val onSlow: () -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val show = Runnable {
        ring.appear()
        glow.animate().alpha(0f).setDuration(200).start()
    }
    private val slow = Runnable { onSlow() }

    fun start() {
        reset()
        main.postDelayed(show, 300)
        main.postDelayed(slow, 3000)
    }

    fun reset() {
        main.removeCallbacks(show); main.removeCallbacks(slow)
        ring.hide()
        glow.animate().cancel(); glow.alpha = 1f
    }
}

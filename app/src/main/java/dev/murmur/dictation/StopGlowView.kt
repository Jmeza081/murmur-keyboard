package dev.murmur.dictation

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.View
import androidx.core.graphics.ColorUtils

/** A soft glow behind the Send button that swells with the voice. Draw it centered under the button. */
class StopGlowView(context: Context, private val color: Int, private val buttonRadiusDp: Float) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val density = resources.displayMetrics.density
    private var smoothed = 0f
    private var shown = 0f

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    fun clear() { smoothed = 0f; shown = 0f; invalidate() }

    fun add(rms: Float) {
        smoothed = WaveStyle.smooth(smoothed, WaveStyle.normalize(rms))
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        shown += (smoothed - shown) * 0.3f
        val base = buttonRadiusDp * density
        val outer = base + (6 + shown * 22) * density
        val cx = width / 2f
        val cy = height / 2f
        paint.shader = RadialGradient(cx, cy, outer,
            intArrayOf(ColorUtils.setAlphaComponent(color, (120 * (0.25f + 0.75f * shown)).toInt()), ColorUtils.setAlphaComponent(color, 0)),
            floatArrayOf(base / outer, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, outer, paint)
        if (kotlin.math.abs(smoothed - shown) > 0.01f) postInvalidateOnAnimation()
    }
}

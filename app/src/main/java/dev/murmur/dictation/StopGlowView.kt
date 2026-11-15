package dev.murmur.dictation

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.View
import androidx.core.graphics.ColorUtils

/** A soft glow behind the Send button that swells with the voice. Draw it centered under the button. */
class StopGlowView(context: Context, private val color: Int, private val buttonRadiusDp: Float,
                   /** Murmur: the Send pill's width; the glow becomes a soft pill around it. 0 for a round button. */
                   private val pillWidthDp: Float = 0f) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val density = resources.displayMetrics.density
    private var smoothed = 0f
    private var shown = 0f

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        if (pillWidthDp > 0) setLayerType(LAYER_TYPE_SOFTWARE, null) // BlurMaskFilter needs a software layer
    }
    private val rect = android.graphics.RectF()

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
        if (pillWidthDp > 0) {
            val grow = (4 + shown * 16) * density
            paint.shader = null
            paint.color = ColorUtils.setAlphaComponent(color, (110 * (0.3f + 0.7f * shown)).toInt())
            paint.maskFilter = android.graphics.BlurMaskFilter(grow + 4 * density, android.graphics.BlurMaskFilter.Blur.NORMAL)
            val hw = pillWidthDp * density / 2 + grow / 2; val hh = base + grow / 2
            rect.set(cx - hw, cy - hh, cx + hw, cy + hh)
            canvas.drawRoundRect(rect, hh, hh, paint)
            if (kotlin.math.abs(smoothed - shown) > 0.01f) postInvalidateOnAnimation()
            return
        }
        paint.shader = RadialGradient(cx, cy, outer,
            intArrayOf(ColorUtils.setAlphaComponent(color, (120 * (0.25f + 0.75f * shown)).toInt()), ColorUtils.setAlphaComponent(color, 0)),
            floatArrayOf(base / outer, 1f), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, outer, paint)
        if (kotlin.math.abs(smoothed - shown) > 0.01f) postInvalidateOnAnimation()
    }
}

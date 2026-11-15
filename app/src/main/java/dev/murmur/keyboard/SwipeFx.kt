package dev.murmur.keyboard

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.view.animation.PathInterpolator
import androidx.core.graphics.ColorUtils
import dev.murmur.design.MurmurTokens
import helium314.keyboard.keyboard.Key
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.settings.Settings
import java.lang.ref.WeakReference

/**
 * Overlays for the two key swipes (design: "Murmur gesture animations" canvas).
 * Delete: the backspace key turns red, a red trail follows the finger left, and a pill above it counts
 * the selected words. Cursor: the space bar becomes a window on the text, with the caret in the middle
 * and a ruler that scrolls under the thumb. Drawn by MainKeyboardView on top of the keys.
 */
object SwipeFx {
    enum class Mode { NONE, DELETE, CURSOR }

    private var mode = Mode.NONE
    private var view = WeakReference<View>(null)
    private var key: Key? = null
    private var x = 0
    private var startX = 0
    private var count = 0
    private var countIsWords = true
    private var before = ""
    private var after = ""
    private var alpha = 0f
    private var animator: ValueAnimator? = null

    private const val RED = 0xFFD93A2B.toInt()
    private val easeOut = PathInterpolator(0.23f, 1f, 0.32f, 1f)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.LEFT }
    private val rect = RectF()
    private val loupe = RectF()
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fade = Paint()

    val active get() = mode != Mode.NONE

    fun begin(v: View?, k: Key?, m: Mode, fingerX: Int) {
        if (v == null || k == null) return
        view = WeakReference(v); key = k; mode = m; x = fingerX; startX = fingerX
        count = 0; before = ""; after = ""
        fadeTo(1f, 120)
    }

    fun move(fingerX: Int) {
        if (mode == Mode.NONE) return
        x = fingerX
        view.get()?.invalidate()
    }

    /** Delete swipe: how much is selected, in words or letters (per the delete-swipe setting). */
    fun setSelected(selected: CharSequence?, words: Boolean) {
        if (mode != Mode.DELETE) return
        countIsWords = words
        count = if (selected == null) 0
            else if (words) selected.split(Regex("\\s+")).count { it.isNotEmpty() }
            else selected.toString().let { it.codePointCount(0, it.length) }
        view.get()?.invalidate()
    }

    /** Cursor swipe: the text on each side of the caret. */
    fun setContext(textBefore: CharSequence?, textAfter: CharSequence?) {
        if (mode != Mode.CURSOR) return
        before = clean(textBefore?.toString().orEmpty()).takeLast(7)
        after = clean(textAfter?.toString().orEmpty()).take(7)
        view.get()?.invalidate()
    }

    fun end() {
        if (mode == Mode.NONE) return
        fadeTo(0f, 140) { mode = Mode.NONE; key = null }
    }

    private fun clean(s: String) = s.replace('\n', '↵').replace('\t', ' ')

    private fun fadeTo(target: Float, ms: Long, done: (() -> Unit)? = null) {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(alpha, target).apply {
            duration = ms
            interpolator = easeOut
            addUpdateListener { alpha = it.animatedValue as Float; view.get()?.invalidate() }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                var cancelled = false
                override fun onAnimationCancel(a: android.animation.Animator) { cancelled = true }
                override fun onAnimationEnd(a: android.animation.Animator) { if (!cancelled) { done?.invoke(); view.get()?.invalidate() } }
            })
            start()
        }
    }

    fun draw(canvas: Canvas, v: View) {
        if (mode == Mode.NONE || v !== view.get() || alpha <= 0f) return
        val k = key ?: return
        val d = v.resources.displayMetrics.density
        val left = (k.drawX + v.paddingLeft).toFloat()
        val top = (k.y + v.paddingTop).toFloat()
        rect.set(left, top, left + k.drawWidth, top + k.height)
        rect.inset(0f, d)
        when (mode) {
            Mode.DELETE -> drawDelete(canvas, v, d)
            Mode.CURSOR -> drawCursor(canvas, v, d)
            Mode.NONE -> {}
        }
    }

    private fun a(color: Int, f: Float) = ColorUtils.setAlphaComponent(color, (android.graphics.Color.alpha(color) * f * alpha).toInt().coerceIn(0, 255))

    private fun drawDelete(canvas: Canvas, v: View, d: Float) {
        val r = 8 * d
        // the key turns red
        fill.color = a(RED, 0.55f)
        canvas.drawRoundRect(rect, r, r, fill)
        // a trail from the key to the finger
        val cx = rect.centerX(); val cy = rect.centerY()
        val fx = x.toFloat().coerceAtMost(cx)
        if (cx - fx > 4 * d) {
            fill.color = a(RED, 0.38f)
            canvas.drawRoundRect(fx, cy - 4 * d, cx, cy + 4 * d, 4 * d, 4 * d, fill)
        }
        if (count <= 0) return
        // the count rides above the finger
        val label = if (countIsWords) (if (count == 1) "1 word" else "$count words") else (if (count == 1) "1 letter" else "$count letters")
        text.textSize = 13 * d
        text.isFakeBoldText = true
        val w = text.measureText(label) + 24 * d
        val h = 28 * d
        val bx = fx.coerceIn(w / 2 + 4 * d, v.width - w / 2 - 4 * d)
        val by = (rect.top - 14 * d - h).coerceAtLeast(2 * d)
        fill.color = a(RED, 1f)
        canvas.drawRoundRect(bx - w / 2, by, bx + w / 2, by + h, h / 2, h / 2, fill)
        text.color = a(android.graphics.Color.WHITE, 1f)
        canvas.drawText(label, bx - w / 2 + 12 * d, by + h / 2 - (text.ascent() + text.descent()) / 2, text)
    }

    private fun drawCursor(canvas: Canvas, v: View, d: Float) {
        val colors = Settings.getValues().mColors
        val accent = MurmurTokens.live(v.context)
        val bg = colors.get(ColorType.SPACE_BAR_BACKGROUND)
        val ink = colors.get(ColorType.KEY_TEXT)
        val hint = colors.get(ColorType.KEY_HINT_TEXT)
        val r = 8 * d
        fill.color = a(ColorUtils.blendARGB(ColorUtils.setAlphaComponent(bg, 255), accent, 0.22f), 1f)
        canvas.drawRoundRect(rect, r, r, fill)
        canvas.save()
        canvas.clipRect(rect)
        // ruler ticks scroll with the thumb
        val gap = 10 * d
        var off = ((x - startX) % gap)
        if (off < 0) off += gap
        fill.color = a(accent, 0.45f)
        var tx = rect.left - gap + off
        while (tx < rect.right + gap) {
            canvas.drawRect(tx, rect.bottom - 6 * d, tx + 1.5f * d, rect.bottom - 2 * d, fill)
            tx += gap
        }
        canvas.restore()
        drawLoupe(canvas, v, d, accent, ink, hint)
    }

    /** A magnified window on the text, floating above the thumb: caret in the middle, a ruler along the bottom. */
    private fun drawLoupe(canvas: Canvas, v: View, d: Float, accent: Int, ink: Int, hint: Int) {
        val colors = Settings.getValues().mColors
        val w = 210 * d; val h = 66 * d; val r = 22 * d
        val cx = x.toFloat().coerceIn(w / 2 + 8 * d, v.width - w / 2 - 8 * d)
        val bottom = (rect.top - 22 * d).coerceAtLeast(h + 2 * d)
        val lift = (1f - alpha) * 10 * d
        val scale = 0.92f + 0.08f * alpha
        canvas.save()
        canvas.translate(0f, lift)
        canvas.scale(scale, scale, cx, bottom)
        loupe.set(cx - w / 2, bottom - h, cx + w / 2, bottom)
        val bg = ColorUtils.setAlphaComponent(colors.get(ColorType.KEY_BACKGROUND), 255)
        fill.color = a(bg, 1f)
        fill.setShadowLayer(14 * d, 0f, 6 * d, a(0x8C000000.toInt(), 1f))
        canvas.drawRoundRect(loupe, r, r, fill)
        fill.clearShadowLayer()
        stroke.color = a(accent, 0.6f); stroke.strokeWidth = 1.5f * d
        canvas.drawRoundRect(loupe, r, r, stroke)
        canvas.clipRect(loupe.left + 2 * d, loupe.top + 2 * d, loupe.right - 2 * d, loupe.bottom - 2 * d)
        // ruler, moving a little faster than the thumb
        val gap = 20 * d
        var off = ((x - startX) * 1.4f) % gap
        if (off < 0) off += gap
        fill.color = a(accent, 0.5f)
        var tx = loupe.left - gap + off
        while (tx < loupe.right + gap) {
            canvas.drawRect(tx, loupe.bottom - 10 * d, tx + 2 * d, loupe.bottom - 4 * d, fill)
            tx += gap
        }
        // text, about twice the key size; outer letters in the hint color
        text.textSize = 26 * d
        text.isFakeBoldText = false
        val mid = loupe.centerY() - 4 * d
        val base = mid - (text.ascent() + text.descent()) / 2
        val nearB = before.takeLast(4); val farB = before.dropLast(4)
        val nearA = after.take(4); val farA = after.drop(4)
        var px = cx - 4 * d - text.measureText(nearB)
        text.color = a(ink, 1f); canvas.drawText(nearB, px, base, text)
        px -= text.measureText(farB)
        text.color = a(hint, 0.85f); canvas.drawText(farB, px, base, text)
        px = cx + 4 * d
        text.color = a(ink, 1f); canvas.drawText(nearA, px, base, text)
        px += text.measureText(nearA)
        text.color = a(hint, 0.85f); canvas.drawText(farA, px, base, text)
        // edges fade into the loupe
        val edge = w * 0.22f
        fade.shader = android.graphics.LinearGradient(loupe.left, 0f, loupe.left + edge, 0f, a(bg, 1f), ColorUtils.setAlphaComponent(bg, 0), android.graphics.Shader.TileMode.CLAMP)
        canvas.drawRect(loupe.left, loupe.top, loupe.left + edge, loupe.bottom, fade)
        fade.shader = android.graphics.LinearGradient(loupe.right - edge, 0f, loupe.right, 0f, ColorUtils.setAlphaComponent(bg, 0), a(bg, 1f), android.graphics.Shader.TileMode.CLAMP)
        canvas.drawRect(loupe.right - edge, loupe.top, loupe.right, loupe.bottom, fade)
        // caret
        fill.color = a(accent, 1f)
        val ch = 30 * d
        canvas.drawRoundRect(cx - 1.25f * d, mid - ch / 2, cx + 1.25f * d, mid + ch / 2, d, d, fill)
        canvas.restore()
    }
}

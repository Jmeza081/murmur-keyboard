package dev.murmur.dictation

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.CharacterStyle
import android.text.style.ReplacementSpan
import android.graphics.RectF
import android.util.TypedValue
import android.view.View
import android.view.animation.PathInterpolator
import androidx.core.graphics.ColorUtils
import kotlin.math.abs
import kotlin.math.exp

/**
 * Murmur: live words as a teleprompter (design/live-words-v2.html, C), animated.
 * - Each new word fades in and rises a few dp; a draft word eases from grey to ink when it's final.
 * - The text block is centered as a whole and glides to its new position, instead of every word re-centering the line.
 * - When a third line starts, the lines scroll up smoothly and the old line fades out at the top.
 * - Live edits ([LiveMarks]): fillers and "scratch that" get a strike line, dim, then fold away; "heart emoji"
 *   is struck out while ❤️ pops in; "paste my address" becomes a snippet pill.
 */
class TeleprompterView(context: Context, sizeSp: Float, private val ink: Int, private val sub: Int, private val accent: Int = ink) : View(context) {
    private class Word(var text: String, var born: Long, var draft: Boolean, var finalAt: Long = 0L, var struckAt: Long = 0L)
    /** Emoji or snippet pills shown after a word, keyed "index|text", with when they appeared. */
    private val insertBorn = HashMap<String, Long>()
    private var inserts: Map<Int, LiveMarks.Insert> = emptyMap()

    private val density = resources.displayMetrics.density
    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sizeSp, resources.displayMetrics)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        color = ink
    }
    private val words = ArrayList<Word>()
    private var first = 0 // index of the first word still laid out (older lines are dropped)
    private var layout: StaticLayout? = null
    private var lineH = 0f
    private val fadeTop get() = lineH * 0.6f // room for the outgoing line to fade through

    // Smoothed block position (x offset, y offset) and their targets
    private var tx = 0f; private var ty = Float.NaN
    private var txTarget = 0f; private var tyTarget = 0f
    private var lastFrame = 0L

    private val ease = PathInterpolator(0.23f, 1f, 0.32f, 1f)
    private val maskPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        lineH = build("Ag", 1000).height.toFloat()
    }

    fun clear() {
        words.clear(); insertBorn.clear(); inserts = emptyMap(); first = 0; layout = null; tx = 0f; ty = Float.NaN
        contentDescription = null
        invalidate()
    }

    /** Shows [final] text in ink and the [draft] tail in grey. Returns whether there's anything to show. */
    fun setWords(final: String, draft: String): Boolean {
        val now = SystemClock.uptimeMillis()
        val f = final.split(' ').filter { it.isNotBlank() }
        val all = f + draft.split(' ').filter { it.isNotBlank() }
        for (i in all.indices) {
            val isDraft = i >= f.size
            val w = words.getOrNull(i)
            when {
                w == null -> words.add(Word(all[i], now, isDraft))
                w.text != all[i] -> {
                    // A word that just grew keeps its fade; a different word fades in again
                    if (!all[i].startsWith(w.text) && !w.text.startsWith(all[i])) w.born = now
                    w.text = all[i]
                    if (w.draft && !isDraft) w.finalAt = now
                    w.draft = isDraft
                }
                w.draft && !isDraft -> { w.draft = false; w.finalAt = now }
            }
        }
        while (words.size > all.size) words.removeAt(words.size - 1)
        if (first > words.size) first = 0
        // Live edits: strike what cleanup will remove, show what it will put in
        val marks = LiveMarks.mark(context, all)
        words.forEachIndexed { i, w -> if (i in marks.struck) { if (w.struckAt == 0L) w.struckAt = now } else w.struckAt = 0L }
        inserts = marks.inserts
        val keys = inserts.map { (i, ins) -> "$i|${ins.text}" }.toSet()
        insertBorn.keys.retainAll(keys)
        keys.forEach { insertBorn.getOrPut(it) { now } }
        contentDescription = all.joinToString(" ")
        relayout()
        return all.isNotEmpty()
    }

    private fun relayout() {
        val w = width - paddingLeft - paddingRight
        if (w <= 0) { layout = null; return }
        var l = layoutFrom(first, w)
        // Keep at most three lines laid out: drop whole lines from the top so the remaining breaks don't change
        if (l.lineCount > 3) {
            val dropLine = l.lineCount - 3
            val dropChar = l.getLineStart(dropLine)
            val removed = l.getLineTop(dropLine)
            first = starts.firstOrNull { it.first >= dropChar }?.second ?: words.size
            l = layoutFrom(first, w)
            if (!ty.isNaN()) ty += removed
        }
        layout = l
        val n = l.lineCount
        var widest = 0f
        for (i in maxOf(0, n - 2) until n) widest = maxOf(widest, l.getLineWidth(i))
        txTarget = (w - widest) / 2f
        // At rest the last two lines fill the box; a single line sits in the middle of it
        tyTarget = fadeTop - l.getLineTop(maxOf(0, n - 2)) + if (n == 1) lineH / 2f else 0f
        if (ty.isNaN()) { ty = tyTarget; tx = txTarget }
        invalidate()
    }

    /** (char offset, word index) for each word laid out, for dropping whole lines. */
    private var starts = ArrayList<Pair<Int, Int>>()

    private fun layoutFrom(start: Int, width: Int): StaticLayout {
        val now = SystemClock.uptimeMillis()
        val sb = SpannableStringBuilder()
        val st = ArrayList<Pair<Int, Int>>()
        for (i in start until words.size) {
            val w = words[i]
            if (w.struckAt > 0 && now - w.struckAt >= GONE_MS) { insertAfter(sb, i); continue } // folded away
            st += sb.length to i
            if (w.struckAt > 0) {
                // The space before it folds away with it
                val s = sb.length
                sb.append(if (sb.isNotEmpty()) " " else "").append(w.text)
                sb.setSpan(StrikeSpan(w), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            } else {
                if (sb.isNotEmpty()) sb.append(' ')
                val s = sb.length
                sb.append(w.text)
                sb.setSpan(WordSpan(w), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            insertAfter(sb, i)
        }
        starts = st
        return build(sb, width)
    }

    private fun insertAfter(sb: SpannableStringBuilder, i: Int) {
        val ins = inserts[i] ?: return
        val born = insertBorn["$i|${ins.text}"] ?: return
        val s = sb.length
        sb.append(if (sb.isNotEmpty()) " " else "").append(ins.text)
        sb.setSpan(InsertSpan(ins, born), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun edits(now: Long) = words.any { it.struckAt > 0 && now - it.struckAt < GONE_MS } ||
        insertBorn.values.any { now - it < STRIKE_MS + POP_MS }

    /** A word cleanup will remove: a strike line sweeps across, it dims, then its width folds to nothing. */
    private inner class StrikeSpan(private val w: Word) : ReplacementSpan() {
        private fun t() = (SystemClock.uptimeMillis() - w.struckAt).toFloat()
        private fun fold() = ease.getInterpolation(((t() - STRIKE_MS - HOLD_MS) / FOLD_MS).coerceIn(0f, 1f))

        override fun getSize(p: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
            fm?.let { p.getFontMetricsInt(it) }
            return (p.measureText(text, start, end) * (1 - fold())).toInt()
        }

        override fun draw(c: Canvas, text: CharSequence, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, p: Paint) {
            val full = p.measureText(text, start, end)
            val f = fold()
            val strike = ease.getInterpolation((t() / STRIKE_MS).coerceIn(0f, 1f))
            val alpha = (1f - 0.5f * strike) * (1f - f)
            c.save()
            c.clipRect(x, top.toFloat(), x + full * (1 - f) + 1, bottom.toFloat())
            val base = if (w.draft) sub else ink
            p.color = ColorUtils.setAlphaComponent(base, (255 * alpha).toInt())
            c.drawText(text, start, end, x, y.toFloat(), p)
            // The line skips the leading space
            val lead = if (text[start] == ' ') p.measureText(" ") else 0f
            val lineY = y - p.textSize * 0.3f
            strikePaint.color = ColorUtils.setAlphaComponent(accent, (255 * (1f - f)).toInt())
            c.drawLine(x + lead, lineY, x + lead + (full - lead) * strike, lineY, strikePaint)
            c.restore()
        }
    }

    /** An emoji or a snippet pill that cleanup will put in: grows in as the struck words fold away. */
    private inner class InsertSpan(private val ins: LiveMarks.Insert, private val born: Long) : ReplacementSpan() {
        private fun grow() = ease.getInterpolation(((SystemClock.uptimeMillis() - born - STRIKE_MS) / POP_MS).coerceIn(0f, 1f))
        private fun natural(p: Paint, text: CharSequence, start: Int, end: Int): Float {
            if (!ins.pill) return p.measureText(text, start, end)
            val lead = if (text[start] == ' ') p.measureText(" ") else 0f
            pillPaint.textSize = p.textSize * 0.72f
            return lead + pillPaint.measureText(ins.text) + 16 * density
        }

        override fun getSize(p: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
            fm?.let { p.getFontMetricsInt(it) }
            return (natural(p, text, start, end) * grow()).toInt()
        }

        override fun draw(c: Canvas, text: CharSequence, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, p: Paint) {
            val g = grow()
            if (g <= 0f) return
            val lead = if (text[start] == ' ') p.measureText(" ") else 0f
            val w = natural(p, text, start, end) - lead
            val cx = x + lead + w * g / 2f
            val cy = y - p.textSize * 0.35f
            c.save()
            val scale = 0.6f + 0.4f * g
            c.scale(scale, scale, cx, cy)
            if (ins.pill) {
                val h = pillPaint.textSize * 1.6f
                pillRect.set(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
                pillBg.color = ColorUtils.setAlphaComponent(accent, (60 * g).toInt())
                c.drawRoundRect(pillRect, h / 2f, h / 2f, pillBg)
                pillPaint.color = ColorUtils.setAlphaComponent(accent, (255 * g).toInt())
                c.drawText(ins.text, cx - pillPaint.measureText(ins.text) / 2f, cy + pillPaint.textSize * 0.35f, pillPaint)
            } else {
                p.color = ColorUtils.setAlphaComponent(ink, (255 * g).toInt())
                c.drawText(text, start + (if (lead > 0) 1 else 0), end, cx - w / 2f, y.toFloat(), p)
            }
            c.restore()
        }
    }

    private val strikePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 2 * density; strokeCap = Paint.Cap.ROUND }
    private val pillPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
    private val pillBg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pillRect = RectF()

    private fun build(text: CharSequence, width: Int) =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1.1f)
            .setIncludePad(false)
            .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
            .build()

    private inner class WordSpan(private val w: Word) : CharacterStyle() {
        override fun updateDrawState(tp: TextPaint) {
            val now = SystemClock.uptimeMillis()
            val inP = ease.getInterpolation(((now - w.born) / FADE_MS).coerceIn(0f, 1f))
            val base = when {
                w.draft -> sub
                w.finalAt > 0 -> ColorUtils.blendARGB(sub, ink, ((now - w.finalAt) / INK_MS).coerceIn(0f, 1f))
                else -> ink
            }
            tp.color = ColorUtils.setAlphaComponent(base, (android.graphics.Color.alpha(base) * inP).toInt())
            tp.baselineShift += (RISE_DP * density * (1 - inP)).toInt()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = (fadeTop + lineH * 2 + paddingTop + paddingBottom).toInt() + 1
        setMeasuredDimension(getDefaultSize(suggestedMinimumWidth, widthMeasureSpec), resolveSize(h, heightMeasureSpec))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (w != oldw) { ty = Float.NaN; first = 0; relayout() }
    }

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        if (edits(now) || words.any { it.struckAt > 0 && now - it.struckAt in GONE_MS..GONE_MS + 100 }) relayout() // widths are animating
        val l = layout ?: return
        val dt = if (lastFrame == 0L) 16L else (now - lastFrame).coerceIn(1L, 64L)
        lastFrame = now
        val k = 1f - exp(-dt / GLIDE_MS)
        tx += (txTarget - tx) * k
        ty += (tyTarget - ty) * k
        if (abs(txTarget - tx) < 0.3f) tx = txTarget
        if (abs(tyTarget - ty) < 0.3f) ty = tyTarget

        // Draw into a layer so the top edge can fade out the line scrolling away
        val save = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        canvas.save()
        canvas.translate(paddingLeft + tx, paddingTop + ty)
        l.draw(canvas)
        canvas.restore()
        if (maskPaint.shader == null) maskPaint.shader = LinearGradient(0f, paddingTop.toFloat(), 0f, paddingTop + fadeTop,
            0x00000000, 0xFF000000.toInt(), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, width.toFloat(), paddingTop + fadeTop, maskPaint)
        canvas.restoreToCount(save)

        val animating = tx != txTarget || ty != tyTarget ||
            words.any { now - it.born < FADE_MS || (it.finalAt > 0 && now - it.finalAt < INK_MS) } || edits(now) ||
            words.any { it.struckAt > 0 && now - it.struckAt < GONE_MS + 100 }
        if (animating) postInvalidateOnAnimation() else lastFrame = 0L
    }

    private companion object {
        const val FADE_MS = 260f
        const val INK_MS = 300f
        const val GLIDE_MS = 70f
        const val RISE_DP = 6f
        const val STRIKE_MS = 200f // the line sweeps across
        const val HOLD_MS = 260f // struck and dimmed, so you see what's going
        const val FOLD_MS = 280f // its width folds away
        const val GONE_MS = (STRIKE_MS + HOLD_MS + FOLD_MS).toLong()
        const val POP_MS = 320f // an emoji or pill grows in
    }
}

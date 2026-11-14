// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.keyboard.clipboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.Patterns
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import helium314.keyboard.latin.ClipboardHistoryEntry
import helium314.keyboard.latin.ClipboardHistoryManager
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.settings.Settings

/**
 * Murmur (design/clipboard-themes.html): "Pinned" with one scrolling row of chips, then "Recent" as a two-column
 * grid of cards that show what each clip is (code, link, email, phone, image, text), outline the clip you just
 * copied, and count down to the automatic clear. Rebuilt on every change; the list is small.
 */
class ClipboardAdapter(
       val clipboardLayoutParams: ClipboardLayoutParams,
       val keyEventListener: OnKeyEventListener,
       /** Holding a clip or pinned chip: Pin, Copy, Snippet, Delete. */
       private val onHold: (View, ClipboardHistoryEntry) -> Unit = { _, _ -> },
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    var clipboardHistoryManager: ClipboardHistoryManager? = null
        set(value) { field = value; refresh() }

    var pinnedIconResId = 0
    var itemBackgroundId = 0
    var itemTypeFace: Typeface? = null
    var itemTextColor = 0
    var itemTextSize = 0f

    private sealed class Row {
        class Header(val label: String, val count: Int, val icon: Int) : Row()
        class Pinned(val entries: List<ClipboardHistoryEntry>) : Row()
        class Clip(val entry: ClipboardHistoryEntry, val fresh: Boolean) : Row()
    }
    private var rows: List<Row> = emptyList()

    /** Rebuilds the sections from the history. */
    @SuppressLint("NotifyDataSetChanged")
    fun refresh() {
        val all = clipboardHistoryManager?.let { m -> (0 until m.getHistorySize()).mapNotNull { m.getHistoryEntry(it) } } ?: emptyList()
        val pinned = all.filter { it.isPinned }
        val recent = all.filterNot { it.isPinned }.sortedByDescending { it.timeStamp }
        val now = System.currentTimeMillis()
        rows = buildList {
            if (pinned.isNotEmpty()) { add(Row.Header("Pinned", pinned.size, R.drawable.ic_murmur_pushpin)); add(Row.Pinned(pinned)) }
            if (recent.isNotEmpty()) add(Row.Header("Recent", recent.size, R.drawable.ic_murmur_clock))
            recent.forEachIndexed { i, e -> add(Row.Clip(e, i == 0 && now - e.timeStamp < FRESH_MS)) }
        }
        notifyDataSetChanged()
    }

    /** The clip at [position], for swipe to delete; null for headers and the pinned row. */
    fun entryAt(position: Int): ClipboardHistoryEntry? = (rows.getOrNull(position) as? Row.Clip)?.entry

    fun hasUnpinned() = rows.any { it is Row.Clip }

    override fun getItemViewType(position: Int) = when (rows[position]) {
        is Row.Header -> TYPE_HEADER
        is Row.Pinned -> TYPE_PINNED
        is Row.Clip -> TYPE_CLIP
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder = when (viewType) {
        TYPE_HEADER -> HeaderHolder(parent.context)
        TYPE_PINNED -> PinnedHolder(parent.context)
        else -> ViewHolder(parent.context)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Row.Header -> (holder as HeaderHolder).bind(row)
            is Row.Pinned -> (holder as PinnedHolder).bind(row.entries)
            is Row.Clip -> (holder as ViewHolder).setContent(row.entry, row.fresh)
        }
    }

    override fun getItemCount() = rows.size

    private fun fullSpan(v: View) {
        v.layoutParams = StaggeredGridLayoutManager.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { isFullSpan = true }
    }

    private class Palette {
        val colors = Settings.getValues().mColors
        val key = colors.get(ColorType.KEY_BACKGROUND)
        val ink = colors.get(ColorType.KEY_TEXT)
        val sub = colors.get(ColorType.KEY_HINT_TEXT)
        val accent = colors.get(ColorType.ACTION_KEY_BACKGROUND)
        val light = ColorUtils.calculateLuminance(key) > 0.5
        /** The accent as text or an icon on a key-colored card. */
        val accentInk = if (light) ColorUtils.blendARGB(accent, 0xFF000000.toInt(), 0.15f) else ColorUtils.blendARGB(accent, 0xFFFFFFFF.toInt(), 0.35f)
    }

    private inner class HeaderHolder(context: Context) : RecyclerView.ViewHolder(TextView(context)) {
        private val text = itemView as TextView
        init {
            fullSpan(itemView)
            val d = context.resources.displayMetrics.density
            text.apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                letterSpacing = 0.06f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER_VERTICAL
                compoundDrawablePadding = (5 * d).toInt()
                setPadding((6 * d).toInt(), (6 * d).toInt(), 0, (2 * d).toInt())
            }
        }
        fun bind(row: Row.Header) {
            val p = Palette()
            text.setTextColor(p.sub)
            text.text = android.text.SpannableStringBuilder(row.label.uppercase()).append("  ").also {
                val s = it.length
                it.append(row.count.toString())
                it.setSpan(android.text.style.StyleSpan(Typeface.NORMAL), s, it.length, 0)
            }
            val icon = androidx.core.content.ContextCompat.getDrawable(text.context, row.icon)?.mutate()?.apply {
                val size = (13 * text.resources.displayMetrics.density).toInt()
                setBounds(0, 0, size, size); setTint(p.sub)
            }
            text.setCompoundDrawablesRelative(icon, null, null, null)
        }
    }

    private inner class PinnedHolder(context: Context) : RecyclerView.ViewHolder(HorizontalScrollView(context)) {
        private val row = LinearLayout(context)
        init {
            fullSpan(itemView)
            (itemView as HorizontalScrollView).apply {
                isHorizontalScrollBarEnabled = false
                clipToPadding = false
                val d = context.resources.displayMetrics.density
                setPadding((3 * d).toInt(), (2 * d).toInt(), (3 * d).toInt(), (4 * d).toInt())
                addView(row)
            }
        }
        fun bind(entries: List<ClipboardHistoryEntry>) {
            row.removeAllViews()
            val p = Palette()
            val d = row.resources.displayMetrics.density
            entries.forEach { e ->
                val kind = Kind.of(e)
                row.addView(TextView(row.context).apply {
                    text = if (kind == Kind.IMAGE) "Image" else e.text?.lineSequence()?.firstOrNull()?.trim()
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    setTextColor(p.ink)
                    maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                    maxWidth = (180 * d).toInt()
                    gravity = Gravity.CENTER_VERTICAL
                    compoundDrawablePadding = (7 * d).toInt()
                    setCompoundDrawablesRelativeWithIntrinsicBounds(kind.icon, 0, 0, 0)
                    androidx.core.widget.TextViewCompat.setCompoundDrawableTintList(this, android.content.res.ColorStateList.valueOf(p.accentInk))
                    setPadding((10 * d).toInt(), 0, (12 * d).toInt(), 0)
                    background = GradientDrawable().apply { cornerRadius = 12 * d; setColor(ColorUtils.blendARGB(p.key, p.accent, 0.14f)) }
                    contentDescription = "Pinned ${kind.label}: $text. Tap to paste, hold for options."
                    isHapticFeedbackEnabled = false
                    setOnTouchListener { _, ev -> if (ev.actionMasked == MotionEvent.ACTION_DOWN) keyEventListener.onKeyDown(e.id); false }
                    setOnClickListener { keyEventListener.onKeyUp(e.id) }
                    setOnLongClickListener { onHold(it, e); true }
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, (44 * d).toInt()).apply { marginEnd = (8 * d).toInt() })
            }
        }
    }

    inner class ViewHolder(context: Context) : RecyclerView.ViewHolder(FrameLayout(context)),
        View.OnClickListener, View.OnTouchListener, View.OnLongClickListener {

        private val d = context.resources.displayMetrics.density
        private val card = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        private val contentImageView = ImageView(context).apply { adjustViewBounds = true; scaleType = ImageView.ScaleType.CENTER_CROP }
        private val contentTextView = TextView(context).apply {
            maxLines = 3; ellipsize = TextUtils.TruncateAt.END; setLineSpacing(0f, 1.1f)
        }
        private val badge = ImageView(context)
        private val metaView = TextView(context).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f); maxLines = 1 }
        private val ring = RingView(context)
        private val freshTag = TextView(context).apply {
            text = "JUST NOW"; setTextSize(TypedValue.COMPLEX_UNIT_SP, 9.5f); typeface = Typeface.DEFAULT_BOLD; letterSpacing = 0.04f
        }
        private val textBox = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        private val metaRow = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }

        init {
            (itemView as FrameLayout).apply {
                layoutParams = StaggeredGridLayoutManager.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                isHapticFeedbackEnabled = false
                setOnClickListener(this@ViewHolder)
                setOnTouchListener(this@ViewHolder)
                setOnLongClickListener(this@ViewHolder)
                minimumHeight = (74 * d).toInt()
                addView(card, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                addView(freshTag, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END)
                    .apply { topMargin = (7 * d).toInt(); marginEnd = (9 * d).toInt() })
            }
            card.addView(contentImageView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (64 * d).toInt()))
            textBox.setPadding((10 * d).toInt(), (9 * d).toInt(), (10 * d).toInt(), 0)
            textBox.addView(contentTextView)
            card.addView(textBox)
            card.addView(View(context), LinearLayout.LayoutParams(1, 0, 1f))
            metaRow.setPadding((10 * d).toInt(), (6 * d).toInt(), (10 * d).toInt(), (8 * d).toInt())
            metaRow.addView(badge, LinearLayout.LayoutParams((18 * d).toInt(), (18 * d).toInt()).apply { marginEnd = (6 * d).toInt() })
            badge.setPadding((3 * d).toInt(), (3 * d).toInt(), (3 * d).toInt(), (3 * d).toInt())
            metaRow.addView(metaView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            metaRow.addView(ring, LinearLayout.LayoutParams((11 * d).toInt(), (11 * d).toInt()))
            card.addView(metaRow)
            clipboardLayoutParams.setItemProperties(itemView)
        }

        fun setContent(historyEntry: ClipboardHistoryEntry?, fresh: Boolean = false) {
            if (historyEntry == null) return
            val p = Palette()
            itemView.tag = historyEntry.id
            val kind = Kind.of(historyEntry)
            itemView.background = GradientDrawable().apply {
                cornerRadius = 12 * d; setColor(p.key)
                if (fresh) setStroke((1.5f * d).toInt(), p.accentInk)
            }
            itemView.clipToOutline = true
            freshTag.visibility = if (fresh) View.VISIBLE else View.GONE
            freshTag.setTextColor(p.accentInk)

            contentTextView.setTextColor(p.ink)
            if (historyEntry.filename != null) {
                historyEntry.setImageAndDescription(contentImageView, contentTextView)
                contentTextView.text = null
            } else {
                contentTextView.text = if (kind == Kind.CODE) Kind.spacedCode(historyEntry.text!!) else historyEntry.text?.take(1000)
            }
            if (kind == Kind.CODE) {
                contentTextView.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                contentTextView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                contentTextView.letterSpacing = 0.1f
                contentTextView.maxLines = 1
            } else {
                contentTextView.typeface = itemTypeFace ?: Typeface.DEFAULT
                contentTextView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                contentTextView.letterSpacing = 0f
                contentTextView.maxLines = 3
            }
            // keep the text clear of the "Just now" tag
            textBox.setPadding((10 * d).toInt(), ((if (fresh && kind != Kind.IMAGE) 20 else 9) * d).toInt(), (10 * d).toInt(), 0)
            contentImageView.visibility = if (kind == Kind.IMAGE) View.VISIBLE else View.GONE
            textBox.visibility = if (kind == Kind.IMAGE) View.GONE else View.VISIBLE

            badge.setImageResource(kind.icon)
            badge.setColorFilter(p.accentInk)
            badge.background = GradientDrawable().apply { cornerRadius = 6 * d; setColor(ColorUtils.setAlphaComponent(p.accent, 40)) }
            metaView.setTextColor(p.sub)
            metaView.text = kind.label + " · " + (if (kind == Kind.CODE && fresh) "tap to paste" else age(historyEntry.timeStamp))

            val retention = Settings.getValues()?.mClipboardHistoryRetentionTime ?: 121L
            if (retention <= 120 && !historyEntry.isPinned) {
                val left = 1f - (System.currentTimeMillis() - historyEntry.timeStamp) / (retention * 60_000f)
                ring.set(left.coerceIn(0f, 1f), p.sub)
                ring.visibility = View.VISIBLE
                ring.contentDescription = "Clears in ${((left.coerceAtLeast(0f) * retention).toInt()).coerceAtLeast(1)} min"
            } else ring.visibility = View.GONE
            itemView.contentDescription = "${kind.label}: ${contentTextView.text}. ${metaView.text}. Tap to paste, hold for options."
        }

        private fun age(ts: Long): String {
            val min = ((System.currentTimeMillis() - ts) / 60_000).toInt()
            return when {
                min < 1 -> "now"
                min < 60 -> "$min min"
                min < 60 * 24 -> "${min / 60} hr"
                else -> "${min / (60 * 24)} d"
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(view: View, event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                keyEventListener.onKeyDown(view.tag as Long)
            }
            return false
        }

        override fun onClick(view: View) {
            keyEventListener.onKeyUp(view.tag as Long)
        }

        override fun onLongClick(view: View): Boolean {
            val id = view.tag as Long
            val entry = clipboardHistoryManager?.getHistoryEntryContent(id) ?: return true
            onHold(view, entry)
            return true
        }
    }

    /** What a clip looks like it is, for its icon and label. */
    enum class Kind(val label: String, val icon: Int) {
        CODE("Code", R.drawable.ic_murmur_key), LINK("Link", R.drawable.ic_murmur_link), EMAIL("Email", R.drawable.ic_murmur_mail),
        PHONE("Phone", R.drawable.ic_murmur_phone), IMAGE("Image", R.drawable.ic_murmur_image), TEXT("Text", R.drawable.ic_murmur_text);

        companion object {
            private val CODE_RE = Regex("""^\d[\d \-]{2,10}\d$""")
            fun of(e: ClipboardHistoryEntry): Kind {
                if (e.filename != null) return IMAGE
                val t = e.text?.trim() ?: return TEXT
                val digits = t.count { it.isDigit() }
                return when {
                    CODE_RE.matches(t) && digits in 4..8 -> CODE
                    ' ' !in t && Patterns.EMAIL_ADDRESS.matcher(t).matches() -> EMAIL
                    ' ' !in t && t.contains('.') && Patterns.WEB_URL.matcher(t).matches() -> LINK
                    digits in 7..15 && t.length <= 20 && Patterns.PHONE.matcher(t).matches() -> PHONE
                    else -> TEXT
                }
            }
            /** "482913" reads as "482 913". */
            fun spacedCode(t: String): String {
                val d = t.filter { it.isDigit() }
                return if (d.length == 6 || d.length == 8) d.chunked(d.length / 2).joinToString(" ") else t.trim()
            }
        }
    }

    /** A small pie that empties as a clip gets closer to being cleared. */
    private class RingView(context: Context) : View(context) {
        private var left = 1f
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val box = RectF()
        fun set(fraction: Float, color: Int) { left = fraction; paint.color = color; invalidate() }
        override fun onDraw(canvas: Canvas) {
            box.set(0f, 0f, width.toFloat(), height.toFloat())
            paint.alpha = 64; canvas.drawOval(box, paint)
            paint.alpha = 255; canvas.drawArc(box, -90f, 360f * left, true, paint)
        }
    }

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_PINNED = 1
        private const val TYPE_CLIP = 2
        private const val FRESH_MS = 2 * 60_000L
    }
}

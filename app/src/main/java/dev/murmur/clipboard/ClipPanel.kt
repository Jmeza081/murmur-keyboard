package dev.murmur.clipboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import androidx.core.graphics.ColorUtils
import dev.murmur.snippets.Snippets
import helium314.keyboard.keyboard.clipboard.ClipboardAdapter
import helium314.keyboard.latin.ClipboardHistoryEntry
import helium314.keyboard.latin.ClipboardHistoryManager
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.settings.Settings

/**
 * Murmur (design/clipboard-themes.html): the clipboard's empty state (illustration, two tips, your snippets)
 * and the menu you get by holding a clip (Pin, Copy, Snippet, Delete).
 */
object ClipPanel {

    private class P(context: Context) {
        val d = context.resources.displayMetrics.density
        val colors = Settings.getValues().mColors
        val key = colors.get(ColorType.KEY_BACKGROUND)
        val fn = colors.get(ColorType.FUNCTIONAL_KEY_BACKGROUND)
        val ink = colors.get(ColorType.KEY_TEXT)
        val sub = colors.get(ColorType.KEY_HINT_TEXT)
        val accent = colors.get(ColorType.ACTION_KEY_BACKGROUND)
        val light = ColorUtils.calculateLuminance(key) > 0.5
        val accentInk = if (light) ColorUtils.blendARGB(accent, 0xFF000000.toInt(), 0.15f) else ColorUtils.blendARGB(accent, 0xFFFFFFFF.toInt(), 0.35f)
        fun px(v: Float) = (v * d).toInt()
    }

    /**
     * Fills [root] with the empty state. Snippets are left out of password fields; tapping one types it with [type].
     */
    fun fillEmpty(root: LinearLayout, password: Boolean, type: (String) -> Unit) {
        val c = root.context
        val p = P(c)
        root.removeAllViews()
        root.orientation = LinearLayout.VERTICAL
        // 8 dp grid: 16 at the sides; 44-56 dp targets for anything you tap
        root.setPadding(p.px(16f), p.px(8f), p.px(16f), p.px(12f))
        val snippets = if (password) emptyList() else Snippets.all(c)
        if (snippets.isEmpty()) quiet(root, p, showAdd = !password) else withSnippets(root, p, snippets, type)
    }

    /** A: icon tile, title, one sentence, one outlined button. Three stops for the eye. */
    private fun quiet(root: LinearLayout, p: P, showAdd: Boolean) {
        val c = root.context
        val wrap = { LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT) }
        val box = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        box.addView(ImageView(c).apply {
            setImageResource(R.drawable.ic_murmur_clip); setColorFilter(p.sub)
            scaleType = ImageView.ScaleType.CENTER
            background = GradientDrawable().apply { cornerRadius = 14 * p.d; setColor(p.fn) }
        }, LinearLayout.LayoutParams(p.px(44f), p.px(44f)).apply { bottomMargin = p.px(14f) })
        box.addView(text(c, "Nothing copied yet", 17f, p.ink, bold = true), wrap())
        val rule = clearTip()?.let { "; the rest clear after ${it.removePrefix("Others clear in ")}." } ?: "."
        box.addView(text(c, "Copied text and images show up here. Hold one to pin it$rule", 13.5f, p.sub).apply {
            gravity = Gravity.CENTER; maxWidth = p.px(300f); setPadding(0, p.px(6f), 0, 0); setLineSpacing(0f, 1.15f)
        }, wrap())
        if (showAdd) box.addView(text(c, "Add a snippet", 15f, p.accentInk, bold = true).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(p.px(14f), 0, p.px(16f), 0)
            compoundDrawablePadding = p.px(8f)
            val dr = androidx.core.content.ContextCompat.getDrawable(c, R.drawable.ic_murmur_plus)?.mutate()?.apply { setBounds(0, 0, p.px(16f), p.px(16f)); setTint(p.accentInk) }
            setCompoundDrawablesRelative(dr, null, null, null)
            background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(ColorUtils.setAlphaComponent(p.accent, 50)),
                GradientDrawable().apply { cornerRadius = 22 * p.d; setStroke(p.px(1f), ColorUtils.blendARGB(p.fn, p.accent, 0.35f)) }, null)
            setOnClickListener { openSnippets(c) }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, p.px(44f)).apply { topMargin = p.px(18f) })
        root.addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    /** B: one line saying nothing's copied, then your snippets as big cards, everything hanging from the top. */
    private fun withSnippets(root: LinearLayout, p: P, snippets: List<Snippets.Snippet>, type: (String) -> Unit) {
        val c = root.context
        root.addView(text(c, "Nothing copied yet. Copied text lands here.", 13.5f, p.sub).apply {
            gravity = Gravity.CENTER_VERTICAL
            compoundDrawablePadding = p.px(10f)
            val dr = androidx.core.content.ContextCompat.getDrawable(c, R.drawable.ic_murmur_clip)?.mutate()?.apply { setBounds(0, 0, p.px(16f), p.px(16f)); setTint(p.sub) }
            setCompoundDrawablesRelative(dr, null, null, null)
            setPadding(p.px(2f), p.px(6f), 0, p.px(12f))
        })
        root.addView(View(c).apply { setBackgroundColor(ColorUtils.setAlphaComponent(p.ink, 22)) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, p.px(1f)).apply { bottomMargin = p.px(8f) })
        val head = LinearLayout(c).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(text(c, "Snippets", 12.5f, p.sub, bold = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(text(c, "Edit", 14f, p.accentInk, bold = true).apply {
            gravity = Gravity.CENTER; minWidth = p.px(48f); minHeight = p.px(40f)
            setPadding(p.px(12f), 0, p.px(4f), 0)
            setOnClickListener { openSnippets(c) }
        })
        root.addView(head)
        val grid = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        snippets.chunked(2).forEach { pair ->
            val line = LinearLayout(c)
            pair.forEachIndexed { i, s ->
                line.addView(snippetCard(c, p, s, type), LinearLayout.LayoutParams(0, p.px(56f), 1f).apply { if (i == 1) marginStart = p.px(8f) })
            }
            if (pair.size == 1) line.addView(View(c), LinearLayout.LayoutParams(0, 1, 1f).apply { marginStart = p.px(8f) })
            grid.addView(line, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = p.px(8f) })
        }
        root.addView(android.widget.ScrollView(c).apply { isVerticalScrollBarEnabled = false; addView(grid) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    private fun snippetCard(c: Context, p: P, s: Snippets.Snippet, type: (String) -> Unit) = LinearLayout(c).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(p.px(12f), 0, p.px(12f), 0)
        background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(ColorUtils.setAlphaComponent(p.ink, 30)),
            GradientDrawable().apply { cornerRadius = 14 * p.d; setColor(p.key) }, null)
        addView(ImageView(c).apply { setImageResource(snippetIcon(s.kind)); setColorFilter(p.accentInk) },
            LinearLayout.LayoutParams(p.px(20f), p.px(20f)).apply { marginEnd = p.px(10f) })
        addView(LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            val value = s.text.lineSequence().first()
            addView(text(c, s.label.ifBlank { value }, 14f, p.ink, bold = true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
            if (s.label.isNotBlank()) addView(text(c, value, 12f, p.sub).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        contentDescription = "Insert ${s.label}"
        setOnClickListener { type(s.text) }
    }

    /** "Others clear in 1 hour", from the clipboard setting; null when clips are kept. */
    private fun clearTip(): String? {
        val min = Settings.getValues()?.mClipboardHistoryRetentionTime ?: 121L
        if (min > 120) return null
        return "Others clear in " + when {
            min % 60 == 0L -> (min / 60).let { if (it == 1L) "1 hour" else "$it hours" }
            else -> "$min min"
        }
    }

    private fun text(c: Context, s: String, sp: Float, color: Int, bold: Boolean = false) = TextView(c).apply {
        text = s; setTextSize(TypedValue.COMPLEX_UNIT_SP, sp); setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    }

    private fun snippetIcon(kind: Snippets.Kind) = when (kind) {
        Snippets.Kind.EMAIL -> R.drawable.ic_murmur_mail
        Snippets.Kind.PHONE -> R.drawable.ic_murmur_phone
        Snippets.Kind.ADDRESS -> R.drawable.ic_murmur_pin
        Snippets.Kind.LINK -> R.drawable.ic_murmur_link
        Snippets.Kind.NONE -> R.drawable.ic_murmur_snippet
    }

    private fun openSnippets(c: Context) {
        c.startActivity(Intent(c, dev.murmur.speech.ModelActivity::class.java)
            .putExtra(dev.murmur.speech.ModelActivity.EXTRA_SCREEN, "SNIPPETS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private var menu: PopupWindow? = null

    fun dismissMenu() { menu?.dismiss() }

    /** The menu over a held clip. [changed] runs after anything that changes the list. */
    fun showMenu(anchor: View, entry: ClipboardHistoryEntry, manager: ClipboardHistoryManager, changed: () -> Unit) {
        menu?.dismiss()
        val c = anchor.context
        val p = P(c)
        val isText = entry.filename == null && !entry.text.isNullOrEmpty()
        val bar = LinearLayout(c).apply {
            setPadding(p.px(4f), p.px(4f), p.px(4f), p.px(4f))
            background = GradientDrawable().apply { cornerRadius = 16 * p.d; setColor(p.ink) }
            elevation = 12 * p.d
        }
        lateinit var window: PopupWindow
        fun action(icon: Int, label: String, run: () -> Unit) {
            val onInk = if (ColorUtils.calculateLuminance(p.ink) > 0.5) 0xFF15171D.toInt() else 0xFFFFFFFF.toInt()
            bar.addView(LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                minimumWidth = p.px(56f); minimumHeight = p.px(48f)
                setPadding(p.px(8f), p.px(6f), p.px(8f), p.px(6f))
                background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(ColorUtils.setAlphaComponent(onInk, 50)), null,
                    GradientDrawable().apply { cornerRadius = 12 * p.d; setColor(0xFFFFFFFF.toInt()) })
                addView(ImageView(c).apply { setImageResource(icon); setColorFilter(onInk) }, LinearLayout.LayoutParams(p.px(18f), p.px(18f)))
                addView(text(c, label, 11f, onInk, bold = true).apply { setPadding(0, p.px(2f), 0, 0) })
                contentDescription = label
                setOnClickListener { window.dismiss(); run() }
            })
        }
        action(R.drawable.ic_murmur_pushpin, if (entry.isPinned) "Unpin" else "Pin") { manager.toggleClipPinned(entry.id); changed() }
        if (isText) action(R.drawable.ic_murmur_copy, "Copy") {
            (c.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("", entry.text))
        }
        if (isText) action(R.drawable.ic_murmur_snippet, "Snippet") {
            val t = entry.text!!.trim()
            val kind = when (ClipboardAdapter.Kind.of(entry)) {
                ClipboardAdapter.Kind.EMAIL -> Snippets.Kind.EMAIL
                ClipboardAdapter.Kind.PHONE -> Snippets.Kind.PHONE
                ClipboardAdapter.Kind.LINK -> Snippets.Kind.LINK
                else -> Snippets.Kind.NONE
            }
            Snippets.put(c, Snippets.Snippet(System.currentTimeMillis(), t.lineSequence().first().take(24), t, kind))
            Toast.makeText(c, "Saved as a snippet", Toast.LENGTH_SHORT).show()
        }
        action(R.drawable.ic_murmur_trash, "Delete") { manager.deleteClip(entry.id); changed() }

        window = PopupWindow(bar, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            isOutsideTouchable = true
            elevation = 12 * p.d
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0))
            animationStyle = android.R.style.Animation_Dialog
            setOnDismissListener { menu = null; anchor.animate().scaleX(1f).scaleY(1f).setDuration(120).start() }
        }
        menu = window
        anchor.animate().scaleX(1.03f).scaleY(1.03f).setDuration(120).start()
        bar.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        // Over the clip, centered on it; below it when there's no room above
        val loc = IntArray(2).also { anchor.getLocationInWindow(it) }
        val x = (anchor.width - bar.measuredWidth) / 2
        val above = loc[1] - bar.measuredHeight - p.px(6f) >= 0
        val y = if (above) -(anchor.height + bar.measuredHeight + p.px(6f)) else p.px(6f)
        window.showAsDropDown(anchor, x, y)
    }
}

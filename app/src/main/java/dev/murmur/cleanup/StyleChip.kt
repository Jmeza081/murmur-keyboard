package dev.murmur.cleanup

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import helium314.keyboard.latin.R

/**
 * Murmur: the writing style dropdown at the right of the listening strip and the power-button sheet
 * (design/dictation-layout.html). Shows the style for the app you're dictating into; tap for a menu of
 * Casual, Neutral and Polished. The pick is remembered for that app.
 */
class StyleChip(context: Context, private val accent: Int, private val surface: Int, private val target: () -> String?) : TextView(context) {
    private val d = resources.displayMetrics.density
    private val light = ColorUtils.calculateLuminance(surface) > 0.5
    private val ink = if (light) 0xFF191B21.toInt() else 0xFFECEEF3.toInt()
    private val sub = if (light) 0xFF6B7280.toInt() else 0xFF8A91A0.toInt()
    private val label = if (light) ColorUtils.blendARGB(accent, 0xFF000000.toInt(), 0.2f) else ColorUtils.blendARGB(accent, 0xFFFFFFFF.toInt(), 0.45f)
    private var popup: PopupWindow? = null

    init {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        gravity = Gravity.CENTER_VERTICAL
        isSingleLine = true
        minHeight = px(32); minimumHeight = px(32)
        compoundDrawablePadding = px(6)
        setPadding(px(10), 0, px(8), 0)
        setTextColor(label)
        setChevron(false)
        androidx.core.widget.TextViewCompat.setCompoundDrawableTintList(this, android.content.res.ColorStateList.valueOf(label))
        background = GradientDrawable().apply { cornerRadius = 16 * d; setColor(ColorUtils.blendARGB(surface, accent, 0.18f)) }
        setOnClickListener { if (popup?.isShowing == true) popup?.dismiss() else open() }
    }

    /** Re-reads the style for the current app; hidden when cleanup is off (styles need cleanup). */
    fun refresh() {
        val pkg = target()
        val on = Cleanup.level(context) != Cleanup.Level.OFF
        visibility = if (pkg != null && on) VISIBLE else GONE
        if (pkg == null) return
        val s = WritingStyle.forApp(context, pkg)
        text = s.label
        contentDescription = "Writing style ${s.label}. Opens a menu to change it for this app."
    }

    fun dismissMenu() { popup?.dismiss() }

    private fun open() {
        val pkg = target() ?: return
        val current = WritingStyle.forApp(context, pkg)
        val auto = WritingStyle.automatic(context, pkg)
        val app = runCatching { context.packageManager.run { getApplicationLabel(getApplicationInfo(pkg, 0)).toString() } }.getOrDefault("this app")
        val pop = if (light) 0xFFFFFFFF.toInt() else ColorUtils.blendARGB(surface, 0xFFFFFFFF.toInt(), 0.06f)

        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(6), px(6), px(6), px(6))
            background = GradientDrawable().apply {
                cornerRadius = 18 * d; setColor(pop); setStroke(px(1), ColorUtils.setAlphaComponent(ink, 18))
            }
            addView(text("Writing style in $app", 11.5f, sub).apply { setPadding(px(10), px(6), px(10), px(6)) })
        }
        lateinit var window: PopupWindow
        WritingStyle.Style.entries.forEach { s ->
            val chosen = s == current
            list.addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = px(48)
                setPadding(px(10), px(6), px(10), px(6))
                background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(ColorUtils.setAlphaComponent(ink, 30)), null,
                    GradientDrawable().apply { cornerRadius = 12 * d; setColor(0xFFFFFFFF.toInt()) })
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(LinearLayout(context).apply {
                        gravity = Gravity.CENTER_VERTICAL
                        addView(text(s.label, 14.5f, if (chosen) label else ink).apply { typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) })
                        if (s == auto) addView(text("Auto", 10.5f, sub).apply {
                            setPadding(px(6), px(1), px(6), px(1))
                            background = GradientDrawable().apply { cornerRadius = 6 * d; setColor(ColorUtils.setAlphaComponent(ink, 20)) }
                        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = px(6) })
                    })
                    addView(text(s.hint, 12f, sub))
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(ImageView(context).apply {
                    setImageResource(R.drawable.ic_murmur_check)
                    setColorFilter(label)
                    visibility = if (chosen) VISIBLE else INVISIBLE
                }, LinearLayout.LayoutParams(px(18), px(18)))
                contentDescription = s.label + (if (s == auto) ", automatic" else "") + (if (chosen) ", selected" else "")
                setOnClickListener {
                    WritingStyle.setForApp(context, pkg, s)
                    refresh()
                    window.dismiss()
                }
            }, LinearLayout.LayoutParams(px(232), ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        list.addView(View(context).apply { setBackgroundColor(ColorUtils.setAlphaComponent(ink, 20)) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(1)).apply { setMargins(px(4), px(4), px(4), 0) })
        list.addView(text("Remembered for this app.", 11.5f, sub).apply { setPadding(px(10), px(8), px(10), px(4)) })

        window = PopupWindow(list, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            elevation = 16 * d
            isOutsideTouchable = true
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0))
            animationStyle = android.R.style.Animation_Dialog
            setOnDismissListener { popup = null; setChevron(false) }
        }
        popup = window
        setChevron(true)
        list.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        // Right edges line up; drops just below the chip
        window.showAsDropDown(this, width - list.measuredWidth, px(6))
    }

    private fun setChevron(open: Boolean) =
        setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_murmur_pen, 0, if (open) R.drawable.ic_murmur_chev_up else R.drawable.ic_murmur_chev_down, 0)

    private fun text(s: String, sp: Float, color: Int) = TextView(context).apply {
        text = s; setTextSize(TypedValue.COMPLEX_UNIT_SP, sp); setTextColor(color)
    }

    private fun px(v: Int) = (v * d).toInt()
}

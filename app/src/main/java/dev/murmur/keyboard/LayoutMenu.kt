package dev.murmur.keyboard

import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import dev.murmur.design.MurmurTokens
import helium314.keyboard.keyboard.KeyboardSwitcher

/**
 * The layout key's menu on the open inner screen (design: "Murmur toolbar, rethought"):
 * Full, Split or Half screen, and Left or Right side for Half. Replaces cycling with toasts and the pane's bottom bar.
 */
object LayoutMenu {
    private var popup: PopupWindow? = null

    fun dismiss() { popup?.dismiss(); popup = null }

    fun show(anchor: View) {
        dismiss()
        val ctx = anchor.context
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Float) = (v * d).toInt()
        val dark = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val sheet = if (dark) MurmurTokens.Dark.SHEET else MurmurTokens.Light.SHEET
        val ink = if (dark) MurmurTokens.Dark.TEXT else MurmurTokens.Light.TEXT
        val sub = if (dark) MurmurTokens.Dark.SECONDARY_TEXT else MurmurTokens.Light.SECONDARY_TEXT
        val chip = if (dark) MurmurTokens.Dark.CHIP else MurmurTokens.Light.CHIP
        val accent = MurmurTokens.ACCENT
        val selected = (accent and 0x00FFFFFF) or 0x33000000
        val current = PaneMode.current(ctx)
        val left = PaneMode.isLeft(ctx)

        fun round(color: Int, r: Float) = GradientDrawable().apply { setColor(color); cornerRadius = dp(r).toFloat() }
        fun label(text: String, color: Int) = TextView(ctx).apply {
            this.text = text; setTextColor(color); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f); gravity = Gravity.CENTER
        }

        // A tiny picture of each layout: the bars are the keys
        fun glyph(layout: PaneMode.Layout, on: Boolean) = FrameLayout(ctx).apply {
            background = GradientDrawable().apply { cornerRadius = dp(6f).toFloat(); setStroke(dp(1f), (sub and 0x00FFFFFF) or 0x66000000) }
            val bar = if (on) (accent and 0x00FFFFFF) or 0xE6000000.toInt() else (sub and 0x00FFFFFF) or 0x88000000.toInt()
            val w = dp(64f); val h = dp(30f); val keysH = (h * 0.55f).toInt()
            fun keys(width: Int, g: Int) = addView(View(ctx).apply { background = round(bar, 3f) }, FrameLayout.LayoutParams(width, keysH, Gravity.BOTTOM or g))
            when (layout) {
                PaneMode.Layout.FULL -> keys(w, Gravity.START)
                PaneMode.Layout.SPLIT -> { keys((w * 0.38f).toInt(), Gravity.START); keys((w * 0.38f).toInt(), Gravity.END) }
                PaneMode.Layout.HALF -> keys(w / 2, if (left) Gravity.START else Gravity.END)
            }
            layoutParams = LinearLayout.LayoutParams(w, h)
        }

        fun apply() { dismiss(); KeyboardSwitcher.getInstance().murmurApplyLayout() }

        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = round(sheet, 20f)
            elevation = dp(10f).toFloat()
            setPadding(dp(10f), dp(10f), dp(10f), dp(10f))
        }
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        for ((layout, name) in listOf(PaneMode.Layout.FULL to "Full", PaneMode.Layout.SPLIT to "Split", PaneMode.Layout.HALF to "Half")) {
            val on = layout == current
            row.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(8f), dp(10f), dp(8f), dp(8f))
                background = if (on) round(selected, 14f) else null
                addView(glyph(layout, on))
                addView(label(name, if (on) ink else sub), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6f) })
                contentDescription = "$name keyboard"
                isClickable = true
                setOnClickListener { PaneMode.setLayout(ctx, layout); apply() }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(2f); marginEnd = dp(2f) })
        }
        content.addView(row)
        // Which side, for the half-screen keyboard; picking one also turns it on
        val sides = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        for ((isLeft, name) in listOf(true to "Left side", false to "Right side")) {
            val on = current == PaneMode.Layout.HALF && left == isLeft
            sides.addView(label(name, if (on) ink else sub).apply {
                background = round(if (on) chip else (chip and 0x00FFFFFF) or 0x55000000, 12f)
                minHeight = dp(40f)
                isClickable = true
                setOnClickListener { PaneMode.setLeft(ctx, isLeft); PaneMode.setLayout(ctx, PaneMode.Layout.HALF); apply() }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(2f); marginEnd = dp(2f) })
        }
        content.addView(sides, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8f) })
        if (current == PaneMode.Layout.HALF)
            content.addView(label("Or hold the toolbar and drag across the fold", sub).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8f) })

        val width = dp(300f)
        content.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        val p = PopupWindow(content, width, content.measuredHeight, false).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            isOutsideTouchable = true
            elevation = dp(10f).toFloat()
            setOnDismissListener { if (popup === this) popup = null }
        }
        // Above the key, kept on screen
        val loc = IntArray(2)
        anchor.getLocationInWindow(loc)
        val screenW = anchor.rootView.width
        val x = (loc[0] + anchor.width / 2 - width / 2).coerceIn(dp(8f), (screenW - width - dp(8f)).coerceAtLeast(dp(8f)))
        val y = (loc[1] - content.measuredHeight - dp(6f)).coerceAtLeast(0)
        p.showAtLocation(anchor, Gravity.NO_GRAVITY, x, y)
        popup = p
    }
}

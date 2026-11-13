package dev.murmur.autofill

import android.content.Context
import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.RequiresApi
import dev.murmur.design.MurmurTokens
import helium314.keyboard.keyboard.KeyboardSwitcher

/**
 * Every saved login for the field, full width, in the power-button sheet's floating card (design/logins-sheet.html),
 * styled like the power-button sheet. Opened from the "+N" chip. Picking a login fills the field, which
 * moves the cursor, and that closes the sheet (LatinIME.onUpdateSelection).
 */
object AutofillSheet {
    private var sheet: View? = null
    private const val LIST = "murmur_logins"

    val isShowing get() = sheet?.parent != null

    @RequiresApi(30)
    fun show(context: Context, logins: Array<View?>) = open(context, "Saved logins", "Tap one to fill it in") { list, width ->
        // The extra logins, already drawn by the password manager at this width (InlineAutofillUtils)
        val height = (40 * context.resources.displayMetrics.density).toInt()
        for (view in logins) {
            if (view == null) continue
            (view.parent as? ViewGroup)?.removeView(view)
            list.addView(view, LinearLayout.LayoutParams(width, height).apply { bottomMargin = (8 * context.resources.displayMetrics.density).toInt() })
        }
    }

    /**
     * The sheet itself, shared by saved logins and snippets: a floating card with a title, "Done", and rows
     * that [fill] adds to the list (given the list's width).
     */
    fun open(context: Context, title: String, subtitle: String, fill: (LinearLayout, Int) -> Unit) {
        dismiss()
        // Drawn in the keyboard's own window, which runs the full height of the screen; only the keys
        // normally show and take touches (design/logins-sheet.html)
        val switcher = KeyboardSwitcher.getInstance()
        val host = switcher.inputRoot as? ViewGroup ?: return
        val density = context.resources.displayMetrics.density
        fun dp(v: Float) = (v * density).toInt()
        val dark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val sheetColor = if (dark) MurmurTokens.Dark.SHEET else MurmurTokens.Light.SHEET
        val ink = if (dark) MurmurTokens.Dark.TEXT else MurmurTokens.Light.TEXT
        val subtle = if (dark) MurmurTokens.Dark.SECONDARY_TEXT else MurmurTokens.Light.SECONDARY_TEXT
        val medium = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)

        val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; tag = LIST }
        // The power-button sheet's card: floating, rounded on all corners, inset from the screen edges
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), dp(12f), dp(16f), dp(8f))
            background = GradientDrawable().apply { cornerRadius = dp(28f).toFloat(); setColor(sheetColor) }
            elevation = dp(12f).toFloat()
            isClickable = true // taps on the card don't close it
            addView(View(context).apply { background = GradientDrawable().apply { cornerRadius = dp(2f).toFloat(); setColor(subtle) }; alpha = 0.4f },
                LinearLayout.LayoutParams(dp(36f), dp(4f)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(12f) })
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(TextView(context).apply {
                        text = title; typeface = medium
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f); setTextColor(ink)
                    })
                    addView(TextView(context).apply {
                        text = subtitle
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f); setTextColor(subtle)
                    })
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(TextView(context).apply {
                    text = "Done"; typeface = medium
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f); setTextColor(MurmurTokens.live(context))
                    setPadding(dp(14f), dp(10f), dp(6f), dp(10f))
                    setOnClickListener { dismiss() }
                })
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(14f) })
            addView(list)
        }
        val scrim = android.graphics.drawable.ColorDrawable(android.graphics.Color.argb(110, 8, 10, 16)).also { it.alpha = 0 }
        val cardLp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
            .apply { setMargins(dp(8f), 0, dp(8f), dp(56f)) } // until the insets arrive
        val root = FrameLayout(context).apply {
            background = scrim
            setOnClickListener { dismiss() } // tap outside the card to close
            addView(card, cardLp)
            // Keep the card above the gesture bar / nav buttons, like the power-button sheet
            setOnApplyWindowInsetsListener { _, insets ->
                val nav = androidx.core.view.WindowInsetsCompat.toWindowInsetsCompat(insets)
                    .getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars()).bottom
                // Android draws its keyboard-switch and hide buttons ~48 dp tall over the keyboard's bottom edge
                cardLp.bottomMargin = dp(8f) + maxOf(nav, dp(48f))
                card.layoutParams = cardLp
                insets
            }
        }
        host.addView(root, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        sheet = root
        switcher.murmurOverlay = true
        root.requestApplyInsets()
        host.requestLayout() // recomputes the touchable area
        // Rise in like the power-button sheet: card slides up, scrim fades in
        card.translationY = dp(240f).toFloat()
        card.animate().translationY(0f).setDuration(260).setInterpolator(android.view.animation.PathInterpolator(0.32f, 0.72f, 0f, 1f)).start()
        android.animation.ValueAnimator.ofInt(0, 255).apply { duration = 200; addUpdateListener { scrim.alpha = it.animatedValue as Int } }.start()

        fill(list, context.resources.displayMetrics.widthPixels - dp(16f) - dp(32f))
    }

    fun dismiss() {
        val s = sheet ?: return
        sheet = null
        val host = s.parent as? ViewGroup
        host?.removeView(s)
        // let go of the login views, so the next "+N" can show them again
        (s.findViewWithTag<View>(LIST) as? ViewGroup)?.removeAllViews()
        KeyboardSwitcher.getInstance().murmurOverlay = false
        host?.requestLayout() // only the keys take touches again
    }
}

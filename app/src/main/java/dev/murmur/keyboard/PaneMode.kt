package dev.murmur.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Resources
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.CursorAnchorInfo
import androidx.core.content.edit
import dev.murmur.settings.KeyHeight
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.FloatingKeyboardUtils
import helium314.keyboard.latin.utils.ResourceUtils
import helium314.keyboard.latin.utils.prefs

/**
 * Murmur: pane mode (design: "Murmur on the inner screen" artifact). On the inner screen of a foldable, a normal
 * keyboard fills one panel of the fold and the other panel stays clear: taps there reach the app underneath.
 *
 * Built on HeliBoard's floating keyboard, which already places the input view anywhere and limits touches to it.
 * Pane mode pins it to the bottom of one panel, docks it under the text you're typing in, and pushes the app up
 * only when the cursor is on the keyboard's panel.
 */
object PaneMode {
    const val PREF = "murmur_pane_keyboard"
    private const val PREF_LEFT = "murmur_pane_left"

    /** Only when the fold runs down the middle: the inner screen held normally, wider than tall. */
    fun available(ctx: Context): Boolean {
        val dm = ctx.resources.displayMetrics
        return KeyHeight.isInner(ctx.resources) && dm.widthPixels > dm.heightPixels
    }

    fun enabled(ctx: Context, prefs: SharedPreferences) = available(ctx) && prefs.getBoolean(PREF, false)

    /** The keyboard's layout key on the inner screen: Full -> Split -> Half screen -> Full. Returns the new mode's name. */
    fun cycle(ctx: Context): String {
        val prefs = ctx.prefs()
        val split = prefs.getBoolean(Settings.PREF_ENABLE_SPLIT_KEYBOARD_LANDSCAPE, false) || prefs.getBoolean(Settings.PREF_ENABLE_SPLIT_KEYBOARD, false)
        val next = when {
            prefs.getBoolean(PREF, false) -> "full"
            split -> "half"
            else -> "split"
        }
        prefs.edit {
            putBoolean(Settings.PREF_ENABLE_SPLIT_KEYBOARD, next == "split")
            putBoolean(Settings.PREF_ENABLE_SPLIT_KEYBOARD_LANDSCAPE, next == "split")
            putBoolean(PREF, next == "half")
        }
        return when (next) { "split" -> "Split keyboard"; "half" -> "Half-screen keyboard"; else -> "Full-width keyboard" }
    }

    enum class Layout { FULL, SPLIT, HALF }

    fun current(ctx: Context): Layout {
        val prefs = ctx.prefs()
        return when {
            prefs.getBoolean(PREF, false) -> Layout.HALF
            prefs.getBoolean(Settings.PREF_ENABLE_SPLIT_KEYBOARD_LANDSCAPE, false) || prefs.getBoolean(Settings.PREF_ENABLE_SPLIT_KEYBOARD, false) -> Layout.SPLIT
            else -> Layout.FULL
        }
    }

    /** From the layout key's menu. Both orientations, because the Fold's inner screen reports landscape. */
    fun setLayout(ctx: Context, layout: Layout) = ctx.prefs().edit {
        putBoolean(Settings.PREF_ENABLE_SPLIT_KEYBOARD, layout == Layout.SPLIT)
        putBoolean(Settings.PREF_ENABLE_SPLIT_KEYBOARD_LANDSCAPE, layout == Layout.SPLIT)
        putBoolean(PREF, layout == Layout.HALF)
    }

    fun setEnabled(ctx: Context, on: Boolean) {
        ctx.prefs().edit { putBoolean(PREF, on) }
        KeyboardSwitcher.getInstance().setThemeNeedsReload()
    }

    fun isLeft(ctx: Context) = ctx.prefs().getBoolean(PREF_LEFT, false)
    fun setLeft(ctx: Context, left: Boolean) = ctx.prefs().edit { putBoolean(PREF_LEFT, left) }

    /** One panel: half the screen, split at the fold, which sits in the middle of the Fold's inner screen. */
    fun width(ctx: Context) = ResourceUtils.getDefaultKeyboardWidth(ctx) / 2

    /** Same key height as the full keyboard on this screen. */
    fun height(res: Resources, prefs: SharedPreferences, landscape: Boolean, folded: Boolean, numberRow: Boolean) =
        (ResourceUtils.getDefaultKeyboardHeight(res, numberRow) * Settings.readHeightScale(prefs, landscape, folded) * KeyHeight.screenFactor(res)).toInt()

    // Where the keyboard was last placed, for the touch region (FloatingKeyboardUtils.readPosition)
    var lastX = 0
    var lastY = 0

    fun position(ctx: Context, maxX: Int, maxY: Int): Pair<Int, Int> {
        if (maxX == Int.MAX_VALUE || maxY == Int.MAX_VALUE) return lastX to lastY
        lastX = if (isLeft(ctx)) 0 else maxX.coerceAtLeast(0)
        lastY = maxY.coerceAtLeast(0)
        return lastX to lastY
    }

    // ------------------------------------------------------------------ follow the text you type in

    private var followPending = false
    private var cursorX = Float.NaN

    fun onStartInput() { followPending = true; cursorX = Float.NaN }

    /** Docks under the cursor when a field is first focused. True if the keyboard should be placed again. */
    fun onCursor(ctx: Context, info: CursorAnchorInfo): Boolean {
        val pts = floatArrayOf(info.insertionMarkerHorizontal, info.insertionMarkerTop)
        if (pts[0].isNaN()) return false
        info.matrix.mapPoints(pts)
        cursorX = pts[0]
        if (!followPending) return false
        followPending = false
        val left = cursorX < ctx.resources.displayMetrics.widthPixels / 2f
        if (left != isLeft(ctx)) setLeft(ctx, left)
        return true
    }

    /** Push the app up only when the cursor would end up under the keyboard. */
    fun pushesApp(ctx: Context): Boolean =
        cursorX.isNaN() || (cursorX < ctx.resources.displayMetrics.widthPixels / 2f) == isLeft(ctx)

    // ------------------------------------------------------------------ no bar under the keyboard

    /**
     * Pane mode has no bar of its own (design: "Murmur toolbar, rethought"). The layout key's menu swaps sides and
     * goes back to full width; holding an empty part of the toolbar and dragging moves it across the fold.
     * The keyboard's bottom is pinned just above Android's keyboard row, whatever height its layout has.
     */
    @SuppressLint("ClickableViewAccessibility")
    fun setupHandle(root: View) {
        root.findViewById<View>(R.id.float_handle_container)?.visibility = View.GONE
        if (root.getTag(R.id.float_handle_container) == null) {
            root.setTag(R.id.float_handle_container, true)
            root.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> pinBottom(v) }
        }
        attachDrag(root)
        pinBottom(root)
    }

    private fun pinBottom(root: View) {
        if (!Settings.getValues().mIsPaneKeyboard || root.height == 0) return
        dropKeyPadding(root)
        val lp = root.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        // The keyboard window runs to the bottom of the screen; Android's keyboard row takes the last SafeArea of it.
        // (The visible frame already leaves that row out on One UI, so subtracting from it counted the row twice.)
        val window = (root.parent as? View)?.height?.takeIf { it > 0 } ?: root.rootView.height
        val bottom = window - dev.murmur.design.SafeArea.bottom(root)
        val top = (bottom - root.height).coerceAtLeast(0)
        if (lp.topMargin == top) return
        lp.topMargin = top
        lastY = top
        root.post { root.layoutParams = lp }
    }

    /** Hold an empty part of the toolbar, then drag sideways; lets go on the nearer side of the fold. */
    @SuppressLint("ClickableViewAccessibility")
    private fun attachDrag(root: View) {
        val strip = root.findViewById<View>(R.id.toolbar_container) ?: return
        val ctx = root.context
        val slop = android.view.ViewConfiguration.get(ctx).scaledTouchSlop
        val holdMs = android.view.ViewConfiguration.getLongPressTimeout().toLong()
        var downX = 0f
        var downY = 0f
        var startMargin = 0
        var dragging = false
        var held = false
        val hold = Runnable {
            held = true
            strip.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        }
        strip.setOnTouchListener { _, e ->
            if (!Settings.getValues().mIsPaneKeyboard) return@setOnTouchListener false
            val lp = root.layoutParams as? ViewGroup.MarginLayoutParams ?: return@setOnTouchListener false
            val screen = (root.parent as? View)?.width ?: ctx.resources.displayMetrics.widthPixels
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startMargin = lp.leftMargin; dragging = false; held = false
                    strip.postDelayed(hold, holdMs)
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!held) {
                        if (kotlin.math.abs(e.rawX - downX) > slop || kotlin.math.abs(e.rawY - downY) > slop) strip.removeCallbacks(hold)
                        return@setOnTouchListener false
                    }
                    dragging = true
                    lp.leftMargin = (startMargin + (e.rawX - downX)).toInt().coerceIn(0, (screen - root.width).coerceAtLeast(0))
                    root.layoutParams = lp
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    strip.removeCallbacks(hold)
                    if (!dragging) return@setOnTouchListener held
                    setLeft(ctx, lp.leftMargin + root.width / 2 < screen / 2)
                    place(root)
                    dragging = false; held = false
                    true
                }
                else -> dragging
            }
        }
    }

    /**
     * The key view arrives with ~56 dp of bottom padding for Android's keyboard row. The pane already sits above
     * that row (pinBottom), so in pane mode the padding is only an empty band under the keys.
     */
    private fun dropKeyPadding(root: View) {
        val keys = root.findViewById<View>(R.id.keyboard_view) ?: return
        if (keys.paddingBottom == 0) return
        keys.setTag(R.id.keyboard_view, keys.paddingBottom)
        keys.setPadding(keys.paddingLeft, keys.paddingTop, keys.paddingRight, 0)
    }

    private fun restoreKeyPadding(root: View) {
        val keys = root.findViewById<View>(R.id.keyboard_view) ?: return
        val saved = keys.getTag(R.id.keyboard_view) as? Int ?: return
        keys.setTag(R.id.keyboard_view, null)
        if (keys.paddingBottom == 0) keys.setPadding(keys.paddingLeft, keys.paddingTop, keys.paddingRight, saved)
    }

    /** Leaving pane mode: give the full-width keyboard its bottom padding back. */
    fun hideButtons(root: View) {
        restoreKeyPadding(root)
        root.findViewById<View>(R.id.resize_handle)?.visibility = View.VISIBLE
    }

    private fun place(root: View) {
        FloatingKeyboardUtils.setFloating(root)
        root.requestLayout()
    }

}

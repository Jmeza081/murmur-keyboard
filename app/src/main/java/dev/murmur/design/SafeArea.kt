package dev.murmur.design

import android.annotation.SuppressLint
import android.view.View
import android.view.WindowInsets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Murmur: how far anything we draw must stay above the bottom of the screen.
 *
 * Android draws its own buttons (switch keyboard, hide keyboard, the gesture bar) along the bottom edge, often over
 * the keyboard's window, and doesn't always report them as navigation-bar insets to a keyboard. So we take the
 * largest of every inset that can cover that edge, and never less than the system's nav bar height.
 * Every Murmur surface that sits at the bottom of the screen pads by this.
 */
object SafeArea {
    fun bottom(view: View, insets: WindowInsetsCompat? = ViewCompat.getRootWindowInsets(view)): Int {
        val reported = insets?.let {
            maxOf(
                it.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom,
                it.getInsets(WindowInsetsCompat.Type.tappableElement()).bottom,
                it.getInsets(WindowInsetsCompat.Type.mandatorySystemGestures()).bottom,
            )
        } ?: 0
        return maxOf(reported, systemNavBarHeight(view))
    }

    fun bottom(view: View, insets: WindowInsets) = bottom(view, WindowInsetsCompat.toWindowInsetsCompat(insets, view))

    /** The frame Android reserves for the nav buttons, including the keyboard's switch and hide buttons. */
    @SuppressLint("DiscouragedApi", "InternalInsetResource")
    private fun systemNavBarHeight(view: View): Int {
        val res = view.resources
        for (name in listOf("navigation_bar_frame_height", "navigation_bar_height")) {
            val id = res.getIdentifier(name, "dimen", "android")
            if (id != 0) return res.getDimensionPixelSize(id)
        }
        return (48 * res.displayMetrics.density).toInt()
    }
}

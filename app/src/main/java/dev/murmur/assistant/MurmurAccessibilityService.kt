package dev.murmur.assistant

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * Optional: lets power-button dictation type into fields when another keyboard is active.
 *
 * It reads nothing in the background. It only looks for the focused text field at the moment
 * you dictate, and never logs or stores what's on screen.
 */
class MurmurAccessibilityService : AccessibilityService() {

    private val main = Handler(Looper.getMainLooper())

    override fun onServiceConnected() { instance = this }
    override fun onUnbind(intent: android.content.Intent?): Boolean { instance = null; return super.onUnbind(intent) }
    override fun onDestroy() { instance = null; super.onDestroy() }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit // not used
    override fun onInterrupt() = Unit

    /** The app with a focused, editable, non-password field right now, or null. */
    fun focusedFieldPackage(): String? = focusedField()?.packageName?.toString()

    /**
     * Types [text] into the focused field once Murmur's sheet has closed and focus is back in the
     * app. Tries for up to 2 s, then calls [onTimeout].
     */
    fun typeWhenFocusReturns(text: String, expectedPackage: String, onTimeout: () -> Unit) {
        val deadline = android.os.SystemClock.elapsedRealtime() + 2000
        fun attempt() {
            val node = focusedField()
            if (node != null && node.packageName?.toString() == expectedPackage) {
                if (insert(node, text)) return
            }
            if (android.os.SystemClock.elapsedRealtime() > deadline) { Log.w(TAG, "No field to type into"); return onTimeout() }
            main.postDelayed({ attempt() }, 100)
        }
        attempt()
    }

    private fun focusedField(): AccessibilityNodeInfo? {
        // Look at app windows, not Murmur's own sheet.
        val roots = windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }.mapNotNull { it.root } +
            listOfNotNull(rootInActiveWindow)
        for (root in roots) {
            if (root.packageName?.toString() == packageName) continue
            val node = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: continue
            if (node.isEditable && !node.isPassword) return node
        }
        return null
    }

    /** Inserts at the cursor (or replaces the selection) with SET_TEXT, keeping the rest of the field. */
    private fun insert(node: AccessibilityNodeInfo, text: String): Boolean {
        val current = if (node.isShowingHintText) "" else node.text?.toString().orEmpty()
        var start = node.textSelectionStart
        var end = node.textSelectionEnd
        if (start < 0 || end < 0 || start > current.length || end > current.length) { start = current.length; end = current.length }
        if (start > end) start = end.also { end = start }
        val before = current.substring(0, start)
        val prefix = if (before.isNotEmpty() && !before.last().isWhitespace()) " " else ""
        val inserted = prefix + text
        val updated = before + inserted + current.substring(end)
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, updated)
        })
        if (ok) {
            val cursor = start + inserted.length
            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cursor)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursor)
            })
            Log.i(TAG, "Typed ${text.length} chars into ${node.packageName}")
        }
        return ok
    }

    companion object {
        private const val TAG = "MurmurA11y"
        /** Set while the service is turned on in Settings › Accessibility. */
        @Volatile var instance: MurmurAccessibilityService? = null
            private set
    }
}

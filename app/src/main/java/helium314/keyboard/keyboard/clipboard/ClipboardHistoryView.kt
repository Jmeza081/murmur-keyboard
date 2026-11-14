// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.keyboard.clipboard

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import helium314.keyboard.event.HapticEvent
import helium314.keyboard.keyboard.KeyboardActionListener
import helium314.keyboard.keyboard.KeyboardElement
import helium314.keyboard.keyboard.KeyboardLayoutSet
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.keyboard.KeyboardTypeface
import helium314.keyboard.keyboard.MainKeyboardView
import helium314.keyboard.keyboard.PointerTracker
import helium314.keyboard.keyboard.internal.KeyDrawParams
import helium314.keyboard.keyboard.internal.KeyVisualAttributes
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.ClipboardHistoryManager
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.common.Constants
import helium314.keyboard.latin.database.ClipboardDao
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.ResourceUtils
import helium314.keyboard.latin.utils.ToolbarKey
import helium314.keyboard.latin.utils.createToolbarKey
import helium314.keyboard.latin.utils.getEnabledClipboardToolbarKeys
import helium314.keyboard.latin.utils.onClickToolbarKey
import helium314.keyboard.latin.utils.onLongClickToolbarKey
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.setToolbarButtonsActivatedStateOnPrefChange

@SuppressLint("CustomViewStyleable")
class ClipboardHistoryView @JvmOverloads constructor(
        context: Context,
        attrs: AttributeSet?,
        defStyle: Int = R.attr.clipboardHistoryViewStyle
) : LinearLayout(context, attrs, defStyle), View.OnClickListener,
    ClipboardDao.Listener, OnKeyEventListener,
    View.OnLongClickListener, SharedPreferences.OnSharedPreferenceChangeListener {

    private val clipboardLayoutParams = ClipboardLayoutParams(context)
    private val pinIconId: Int
    private val keyBackgroundId: Int

    private lateinit var clipboardRecyclerView: ClipboardHistoryRecyclerView
    private lateinit var placeholderView: LinearLayout
    private val toolbarKeys = mutableListOf<ImageButton>()
    private var clearButton: TextView? = null
    private lateinit var clipboardAdapter: ClipboardAdapter

    lateinit var keyboardActionListener: KeyboardActionListener
    private lateinit var clipboardHistoryManager: ClipboardHistoryManager

    init {
        val clipboardViewAttr = context.obtainStyledAttributes(attrs,
                R.styleable.ClipboardHistoryView, defStyle, R.style.ClipboardHistoryView)
        pinIconId = clipboardViewAttr.getResourceId(R.styleable.ClipboardHistoryView_iconPinnedClip, 0)
        clipboardViewAttr.recycle()
        @SuppressLint("UseKtx") // suggestion does not work
        val keyboardViewAttr = context.obtainStyledAttributes(attrs, R.styleable.KeyboardView, defStyle, R.style.KeyboardView)
        keyBackgroundId = keyboardViewAttr.getResourceId(R.styleable.KeyboardView_keyBackground, 0)
        keyboardViewAttr.recycle()
        fitsSystemWindows = true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val res = context.resources
        // The main keyboard expands to the entire this {@link KeyboardView}.
        val width = ResourceUtils.getKeyboardWidth(context, Settings.getValues()) + paddingLeft + paddingRight
        val height = ResourceUtils.getSecondaryKeyboardHeight(res, Settings.getValues()) + paddingTop + paddingBottom
        setMeasuredDimension(width, height)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun initialize() { // needs to be delayed for access to ClipboardStrip, which is not a child of this view
        if (this::clipboardAdapter.isInitialized) return
        val colors = Settings.getValues().mColors
        clipboardAdapter = ClipboardAdapter(clipboardLayoutParams, this, { v, e ->
            dev.murmur.clipboard.ClipPanel.showMenu(v, e, clipboardHistoryManager) { onListChanged() }
        }).apply {
            itemBackgroundId = keyBackgroundId
            pinnedIconResId = pinIconId
        }
        placeholderView = findViewById(R.id.clipboard_empty_view)
        clipboardRecyclerView = findViewById<ClipboardHistoryRecyclerView>(R.id.clipboard_list).apply {
            val colCount = resources.getInteger(R.integer.config_clipboard_keyboard_col_count)
            layoutManager = StaggeredGridLayoutManager(colCount, StaggeredGridLayoutManager.VERTICAL)
            @Suppress("deprecation") // "no cache" should be fine according to warning in https://developer.android.com/reference/android/view/ViewGroup#setPersistentDrawingCache(int)
            persistentDrawingCache = PERSISTENT_NO_CACHE
            clipboardLayoutParams.setListProperties(this)
            placeholderView = this@ClipboardHistoryView.placeholderView
        }
        val clipboardStrip = KeyboardSwitcher.getInstance().clipboardStrip!!
        // Murmur (design: "why the empty clipboard feels busy", A): [< ABC] Clipboard ... [Clear]. Only the
        // two buttons look tappable; the title is plain text and Clear dims when there's nothing to clear.
        clipboardStrip.addView(stripButton("ABC", R.drawable.ic_murmur_chev_left, colors.get(ColorType.KEY_TEXT)).apply {
            tag = ToolbarKey.CLOSE_HISTORY
            contentDescription = "Back to keys"
            setOnClickListener(this@ClipboardHistoryView)
        })
        clipboardStrip.addView(TextView(context).apply {
            text = context.getString(R.string.murmur_clip_title)
            textSize = 16f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(colors.get(ColorType.KEY_TEXT))
            gravity = android.view.Gravity.CENTER_VERTICAL
            isFocusable = false
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT).apply { marginStart = (4 * resources.displayMetrics.density).toInt() })
        clipboardStrip.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        clearButton = stripButton("Clear", R.drawable.ic_murmur_trash, colors.get(ColorType.KEY_HINT_TEXT)).apply {
            tag = ToolbarKey.CLEAR_CLIPBOARD
            contentDescription = "Clear unpinned clips"
            setOnClickListener(this@ClipboardHistoryView)
            (layoutParams as LinearLayout.LayoutParams).marginEnd = (4 * resources.displayMetrics.density).toInt()
        }
        clipboardStrip.addView(clearButton)
        // Murmur: no ABC / space / backspace row; ABC lives in the strip and the list takes the space
        findViewById<View>(R.id.bottom_row_keyboard).visibility = View.GONE
    }

    /** Murmur: an unfilled strip button, icon then word, 40 dp tall with a 48 dp row around it. */
    private fun stripButton(label: String, icon: Int, color: Int) = TextView(context).apply {
        val density = resources.displayMetrics.density
        text = label
        textSize = 14f
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
        setTextColor(color)
        gravity = android.view.Gravity.CENTER
        minWidth = (56 * density).toInt()
        setPadding((10 * density).toInt(), 0, (14 * density).toInt(), 0)
        compoundDrawablePadding = (4 * density).toInt()
        val dr = androidx.core.content.ContextCompat.getDrawable(context, icon)?.mutate()?.apply {
            setBounds(0, 0, (18 * density).toInt(), (18 * density).toInt()); setTint(color)
        }
        setCompoundDrawablesRelative(dr, null, null, null)
        background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(androidx.core.graphics.ColorUtils.setAlphaComponent(color, 40)), null,
            android.graphics.drawable.GradientDrawable().apply { cornerRadius = 20 * density; setColor(0xFFFFFFFF.toInt()) })
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, (40 * density).toInt()).apply {
            gravity = android.view.Gravity.CENTER_VERTICAL
            marginStart = (4 * density).toInt()
        }
    }

    private fun setupClipKey(params: KeyDrawParams) {
        clipboardAdapter.apply {
            itemBackgroundId = keyBackgroundId
            itemTypeFace = params.mTypeface
            itemTextColor = params.mTextColor
            itemTextSize = params.mLabelSize.toFloat()
        }
    }

    private fun setupToolbarKeys() {
        // set layout params
        // Murmur: Clear is a 56 dp target with its icon 16 dp in from the edge, matching ABC on the left
        val d = resources.displayMetrics.density
        toolbarKeys.forEach {
            it.layoutParams = LayoutParams((56 * d).toInt(), LayoutParams.MATCH_PARENT)
            it.scaleType = android.widget.ImageView.ScaleType.CENTER
            it.setPadding(0, 0, 0, 0)
        }
    }

    private fun setupBottomRowKeyboard(editorInfo: EditorInfo, listener: KeyboardActionListener) {
        val keyboardView = findViewById<MainKeyboardView>(R.id.bottom_row_keyboard)
        keyboardView.setKeyboardActionListener(listener)
        PointerTracker.switchTo(keyboardView)
        val kls = KeyboardLayoutSet.Builder.buildEmojiClipBottomRow(context, editorInfo)
        val keyboard = kls.getKeyboard(KeyboardElement.CLIPBOARD_BOTTOM_ROW)
        keyboardView.setKeyboard(keyboard)
        // Murmur: same bottom padding as the main keyboard, so the row doesn't sit under the system's buttons
        keyboardView.setPadding(keyboardView.paddingLeft, keyboardView.paddingTop, keyboardView.paddingRight, clipboardLayoutParams.bottomPadding)
    }

    fun setHardwareAcceleratedDrawingEnabled(enabled: Boolean) {
        if (!enabled) return
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    fun startClipboardHistory(
            historyManager: ClipboardHistoryManager,
            keyVisualAttr: KeyVisualAttributes?,
            editorInfo: EditorInfo,
            keyboardActionListener: KeyboardActionListener
    ) {
        clipboardHistoryManager = historyManager
        initialize()
        setupToolbarKeys()
        historyManager.prepareClipboardHistory()
        historyManager.setHistoryChangeListener(this)
        clipboardAdapter.clipboardHistoryManager = historyManager
        onListChanged()

        val params = KeyDrawParams()
        params.updateParams(clipboardLayoutParams.bottomRowKeyboardHeight, keyVisualAttr)
        val settings = Settings.getInstance()
        KeyboardTypeface.customTypeface()?.let { params.mTypeface = it }
        setupClipKey(params)
        setupBottomRowKeyboard(editorInfo, keyboardActionListener)

        // Murmur: the empty state (design/clipboard-themes.html); snippets stay out of password fields
        dev.murmur.clipboard.ClipPanel.fillEmpty(placeholderView,
            helium314.keyboard.latin.utils.InputTypeUtils.isPasswordInputType(editorInfo.inputType)) { keyboardActionListener.onTextInput(it) }
        clipboardRecyclerView.apply {
            adapter = clipboardAdapter
            val keyboardWidth = ResourceUtils.getKeyboardWidth(context, settings.current)
            layoutParams.width = keyboardWidth
            // new ClipboardLayoutParams means ClipboardAdapter has wrong gaps, but that's ok (only relevant when resizing floating keyboard)
            ClipboardLayoutParams(context).setListProperties(this)

            // set side padding
            val keyboardAttr = context.obtainStyledAttributes(
                null, R.styleable.Keyboard, R.attr.keyboardStyle, R.style.Keyboard)
            val leftPadding = (keyboardAttr.getFraction(R.styleable.Keyboard_keyboardLeftPadding,
                keyboardWidth, keyboardWidth, 0f)
                    * settings.current.mSidePaddingScale).toInt()
            val rightPadding =  (keyboardAttr.getFraction(R.styleable.Keyboard_keyboardRightPadding,
                keyboardWidth, keyboardWidth, 0f)
                    * settings.current.mSidePaddingScale).toInt()
            keyboardAttr.recycle()
            setPadding(leftPadding, paddingTop, rightPadding, paddingBottom)
        }

        // absurd workaround so Android sets the correct color from stateList (depending on "activated")
        toolbarKeys.forEach { it.isEnabled = false; it.isEnabled = true }
    }

    /** Murmur: rebuild the sections, and dim Clear when there's nothing it would clear. */
    private fun onListChanged() {
        clipboardAdapter.refresh()
        clearButton?.apply { val on = clipboardAdapter.hasUnpinned(); alpha = if (on) 1f else 0.4f; isEnabled = on }
    }

    fun stopClipboardHistory() {
        if (!this::clipboardAdapter.isInitialized) return
        dev.murmur.clipboard.ClipPanel.dismissMenu()
        clipboardRecyclerView.adapter = null
        clipboardHistoryManager.setHistoryChangeListener(null)
        clipboardAdapter.clipboardHistoryManager = null
    }

    override fun onClick(view: View) {
        if (view.tag is ToolbarKey) {
            onClickToolbarKey(view) {
                keyboardActionListener.onCodeInput(it, Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, false)
            }
        }
    }

    override fun onLongClick(view: View): Boolean {
        if (view.tag is ToolbarKey) {
            onLongClickToolbarKey(view) { code, isRepeat ->
                keyboardActionListener.onCodeInput(code, Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, isRepeat)
            }
            return true
        }
        return false
    }

    override fun onKeyDown(clipId: Long) {
        keyboardActionListener.onPressKey(KeyCode.NOT_SPECIFIED, 0, 1, HapticEvent.KEY_PRESS)
    }

    override fun onKeyUp(clipId: Long) {
        val clipContent = clipboardHistoryManager.getHistoryEntryContent(clipId)
        if (clipContent?.filename != null) keyboardActionListener.onContent(clipContent.getContentInfo(context))
        else keyboardActionListener.onTextInput(clipContent?.text)
        keyboardActionListener.onReleaseKey(KeyCode.NOT_SPECIFIED, false)
        if (Settings.getValues().mAlphaAfterClipHistoryEntry)
            keyboardActionListener.onCodeInput(KeyCode.ALPHA, Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, false)
    }

    override fun onClipInserted(position: Int) {
        onListChanged()
        clipboardRecyclerView.scrollToPosition(0)
    }

    override fun onClipsRemoved(position: Int, count: Int) {
        onListChanged()
    }

    override fun onClipMoved(oldPosition: Int, newPosition: Int) {
        onListChanged()
    }

    override fun onSharedPreferenceChanged(prefs: SharedPreferences?, key: String?) {
        setToolbarButtonsActivatedStateOnPrefChange(KeyboardSwitcher.getInstance().clipboardStrip!!, key)

        // The setting can only be changed from a settings screen, but adding it to this listener seems necessary: https://github.com/HeliBorg/HeliBoard/pull/1903#issuecomment-3478424606
        if (::clipboardHistoryManager.isInitialized && key == Settings.PREF_CLIPBOARD_HISTORY_PINNED_FIRST) {
            // Ensure settings are reloaded first
            Settings.getInstance().onSharedPreferenceChanged(prefs, key)
            clipboardHistoryManager.sortHistoryEntries()
            clipboardAdapter.notifyDataSetChanged()
        }
    }
}

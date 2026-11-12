/*
 * Copyright (C) 2011 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */
package helium314.keyboard.latin.suggestions

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.GestureDetector
import android.view.GestureDetector.SimpleOnGestureListener
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.View.OnLongClickListener
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.core.view.doOnNextLayout
import androidx.core.view.isVisible
import helium314.keyboard.event.HapticEvent
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.keyboard.internal.KeyboardIconsSet
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.AudioAndHapticFeedbackManager
import helium314.keyboard.latin.dictionary.Dictionary
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.common.Colors
import helium314.keyboard.latin.common.Constants
import helium314.keyboard.latin.define.DebugFlags
import helium314.keyboard.latin.settings.DebugSettings
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.ToolbarKey
import helium314.keyboard.latin.utils.ToolbarMode
import helium314.keyboard.latin.utils.addPinnedKey
import helium314.keyboard.latin.utils.createToolbarKey
import helium314.keyboard.latin.utils.dpToPx
import helium314.keyboard.latin.utils.getEnabledToolbarKeys
import helium314.keyboard.latin.utils.getPinnedToolbarKeys
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.removeFirst
import helium314.keyboard.latin.utils.removePinnedKey
import helium314.keyboard.latin.utils.setToolbarButtonsActivatedStateOnPrefChange
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.min
import androidx.core.view.isGone
import helium314.keyboard.latin.utils.onClickToolbarKey
import helium314.keyboard.latin.utils.onLongClickToolbarKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@SuppressLint("InflateParams")
class SuggestionStripView(context: Context, attrs: AttributeSet?, defStyle: Int) :
    RelativeLayout(context, attrs, defStyle), View.OnClickListener, OnLongClickListener, OnSharedPreferenceChangeListener {

    /** Construct a [SuggestionStripView] for showing suggestions to be picked by the user. */
    constructor(context: Context, attrs: AttributeSet?) : this(context, attrs, R.attr.suggestionStripViewStyle)

    interface Listener {
        fun pickSuggestionManually(word: SuggestedWordInfo?)
        fun onCodeInput(primaryCode: Int, x: Int, y: Int, isKeyRepeat: Boolean)
        fun removeSuggestion(word: String?)
        fun removeExternalSuggestions()
        fun onSwipeDownOnToolbar()
    }

    private val moreSuggestionsContainer: View
    private val wordViews = ArrayList<TextView>()
    private val debugInfoViews = ArrayList<TextView>()
    private val dividerViews = ArrayList<View>()

    init {
        val inflater = LayoutInflater.from(context)
        inflater.inflate(R.layout.suggestions_strip, this)
        moreSuggestionsContainer = inflater.inflate(R.layout.more_suggestions, null)

        val colors = Settings.getValues().mColors
        colors.setBackground(this, ColorType.STRIP_BACKGROUND)
        repeat(SuggestedWords.MAX_SUGGESTIONS) {
            val word = TextView(context, null, R.attr.suggestionWordStyle)
            word.contentDescription = resources.getString(R.string.spoken_empty_suggestion)
            word.setOnClickListener(this)
            word.setOnLongClickListener(this)
            word.background = murmurSuggestionPill(colors)
            wordViews.add(word)
            val divider = inflater.inflate(R.layout.suggestion_divider, null)
            divider.visibility = INVISIBLE // Murmur: the pills separate words; the divider only keeps its small gap
            dividerViews.add(divider)
            val info = TextView(context, null, R.attr.suggestionWordStyle)
            info.setTextColor(colors.get(ColorType.KEY_TEXT))
            info.setTextSize(TypedValue.COMPLEX_UNIT_DIP, DEBUG_INFO_TEXT_SIZE_IN_DIP)
            debugInfoViews.add(info)
        }

        DEBUG_SUGGESTIONS = context.prefs().getBoolean(DebugSettings.PREF_SHOW_SUGGESTION_INFOS, Defaults.PREF_SHOW_SUGGESTION_INFOS)
    }

    // toolbar views, drawables and setup
    private val toolbar: ViewGroup = findViewById(R.id.toolbar)
    private val toolbarContainer: View = findViewById(R.id.toolbar_container)
    private val pinnedKeys: ViewGroup = findViewById(R.id.pinned_keys)
    /** Murmur: the dictation pill at the right edge of the strip. Replaces the voice toolbar key. */
    private val murmurMic: ImageButton = findViewById(R.id.murmur_mic)
    private val suggestionsStrip: ViewGroup = findViewById(R.id.suggestions_strip)
    private val toolbarExpandKey = findViewById<ImageButton>(R.id.suggestions_strip_toolbar_key)
    private val toolbarArrowIcon = KeyboardIconsSet.instance.getNewDrawable(KeyboardIconsSet.NAME_TOOLBAR_KEY, context)
    private val defaultToolbarBackground: Drawable = toolbarExpandKey.background
    private val enabledToolKeyBackground = GradientDrawable()
    private var direction = 1 // 1 if LTR, -1 if RTL

    private val toolbarKeyLayoutParams = LinearLayout.LayoutParams(
        resources.getDimensionPixelSize(R.dimen.config_suggestions_strip_edge_key_width),
        LinearLayout.LayoutParams.MATCH_PARENT
    )

    init {
        val colors = Settings.getValues().mColors

        // expand key
        // weird way of setting size (default is config_suggestions_strip_edge_key_width)
        // but better not change it or people will complain
        val toolbarHeight = min(toolbarExpandKey.layoutParams.height, resources.getDimension(R.dimen.config_suggestions_strip_height).toInt())
        toolbarExpandKey.layoutParams.height = toolbarHeight
        toolbarExpandKey.layoutParams.width = toolbarHeight // we want it square
        colors.setBackground(toolbarExpandKey, ColorType.STRIP_BACKGROUND) // necessary because background is re-used for defaultToolbarBackground
        colors.setColor(toolbarExpandKey, ColorType.TOOL_BAR_EXPAND_KEY)
        colors.setColor(toolbarExpandKey.background, ColorType.TOOL_BAR_EXPAND_KEY_BACKGROUND)

        // background indicator for pinned keys
        val color = colors.get(ColorType.TOOL_BAR_KEY_ENABLED_BACKGROUND) or -0x1000000 // ignore alpha (in Java this is more readable 0xFF000000)
        enabledToolKeyBackground.colors = intArrayOf(color, Color.TRANSPARENT)
        enabledToolKeyBackground.gradientType = GradientDrawable.RADIAL_GRADIENT
        enabledToolKeyBackground.gradientRadius = resources.getDimensionPixelSize(R.dimen.config_suggestions_strip_height) / 2.1f

        val mToolbarMode = if (isGone) ToolbarMode.HIDDEN else Settings.getValues().mToolbarMode
        if (mToolbarMode == ToolbarMode.TOOLBAR_KEYS) {
            setToolbarVisibility(true)
        }

        // toolbar keys setup (no need to hide them any more when locked, because then suggestion strip is gone anyway
        setupMurmurMic(colors)
        // Murmur: a fixed tools row from the mockups; HeliBoard's toolbar and pinned-key settings no longer apply
        for (key in MURMUR_TOOLS) {
            if (key == ToolbarKey.SETTINGS) { addPasswordManagerTool(colors); addHeightTool(colors) } // settings stays last
            val button = createToolbarKey(context, key)
            button.layoutParams = toolbarKeyLayoutParams
            setupKey(button, colors)
            toolbar.addView(button)
        }
        for (pinnedKey in emptyList<ToolbarKey>()) {
            val button = createToolbarKey(context, pinnedKey)
            button.layoutParams = toolbarKeyLayoutParams
            setupKey(button, colors)
            pinnedKeys.addView(button)
            val pinnedKeyInToolbar = toolbar.findViewWithTag<View>(pinnedKey)
            if (pinnedKeyInToolbar != null && Settings.getValues().mQuickPinToolbarKeys)
                pinnedKeyInToolbar.background = enabledToolKeyBackground
        }
        toolbarContainer.doOnNextLayout {
            // set min with of the toolbar so the weight of the toolbar keys actually does something
            // todo: results in requestLayout() improperly called by android.widget.LinearLayout during layout: running second layout pass
            toolbar.minimumWidth = toolbarContainer.width
        }

        updateKeys()
    }

    private lateinit var listener: Listener
    private var suggestedWords = SuggestedWords.emptyInstance
    private var startIndexOfMoreSuggestions = 0
    private var isExternalSuggestionVisible = false // Required to disable the more suggestions if other suggestions are visible
    private val layoutHelper = SuggestionStripLayoutHelper(context, attrs, defStyle, wordViews, dividerViews, debugInfoViews)
    private val moreSuggestionsView = moreSuggestionsContainer.findViewById<MoreSuggestionsView>(R.id.more_suggestions_view).apply {
        val slidingListener = object : SimpleOnGestureListener() {
            override fun onScroll(down: MotionEvent?, me: MotionEvent, deltaX: Float, deltaY: Float): Boolean {
                if (down == null) return false
                val dy = me.y - down.y
                val dx = me.x - down.x

                if (Settings.getValues().mToolbarSwipeDownToHide && dy > 50.dpToPx(resources) && abs(dy) > abs(dx)) {
                    listener.onSwipeDownOnToolbar()
                    return true
                }

                return if (!isExternalSuggestionVisible && toolbarContainer.visibility != VISIBLE && deltaY > 0 && dy < (-10).dpToPx(resources)) showMoreSuggestions()
                else false
            }
        }
        gestureDetector = GestureDetector(context, slidingListener)
    }

    // public stuff

    val isShowingMoreSuggestionPanel get() = moreSuggestionsView.isShowingInParent

    /** A connection back to the input method. */
    fun setListener(newListener: Listener, inputView: View) {
        listener = newListener
        moreSuggestionsView.listener = newListener
        moreSuggestionsView.mainKeyboardView = inputView.findViewById(R.id.keyboard_view)
    }

    fun setRtl(isRtlLanguage: Boolean) {
        val newLayoutDirection: Int
        if (!Settings.getValues().mVarToolbarDirection)
            newLayoutDirection = LAYOUT_DIRECTION_LOCALE
        else {
            newLayoutDirection = if (isRtlLanguage) LAYOUT_DIRECTION_RTL else LAYOUT_DIRECTION_LTR
            direction = if (isRtlLanguage) -1 else 1
            toolbarExpandKey.scaleX = (if (toolbarContainer.visibility != VISIBLE) 1f else -1f) * direction
        }
        layoutDirection = newLayoutDirection
        suggestionsStrip.layoutDirection = newLayoutDirection
    }

    /**
     * Murmur: password-manager suggestions stay in the strip until you type, pick one, close them or
     * leave the field. Without this, the tools row (shown for empty fields) and the neutral strip
     * replaced them as soon as they arrived.
     */
    private var pinnedExternal: View? = null
    val hasExternalSuggestions get() = pinnedExternal != null
    fun clearExternalSuggestions() { pinnedExternal = null }

    @JvmOverloads
    fun setToolbarVisibility(toolbarVisible: Boolean, fromUser: Boolean = false) {
        if (toolbarVisible && !fromUser && pinnedExternal != null) return // Murmur: keep the logins showing
        if (murmurOwnsStrip()) return // Murmur: the listening or key height bar owns the strip
        pinnedKeys.isVisible = !toolbarVisible
        suggestionsStrip.isVisible = !toolbarVisible
        toolbarContainer.isVisible = toolbarVisible

        if (DEBUG_SUGGESTIONS) {
            for (view in debugInfoViews) {
                view.visibility = suggestionsStrip.visibility
            }
        }

        toolbarExpandKey.scaleX = (if (toolbarVisible) -1f else 1f) * direction
    }

    fun setSuggestions(suggestions: SuggestedWords, isRtlLanguage: Boolean) {
        pinnedExternal?.let { external ->
            if (suggestions.isEmpty() || suggestions is PunctuationSuggestions) { // neutral strip: keep the logins (Murmur)
                (external.parent as? ViewGroup)?.removeView(external)
                setExternalSuggestionView(external, true)
                return
            }
            pinnedExternal = null // you started typing: words take over
        }
        clear()
        setRtl(isRtlLanguage)
        suggestedWords = reserveEmojiSlot(suggestions)
        startIndexOfMoreSuggestions = layoutHelper.layoutAndReturnStartIndexOfMoreSuggestions(
            context, suggestedWords, suggestionsStrip, this
        )
        // Murmur: an empty slot shows no pill
        wordViews.forEach { it.background?.alpha = if (it.text.isNullOrEmpty()) 0 else 255 }
        isExternalSuggestionVisible = false
        updateKeys()
    }

    fun setExternalSuggestionView(view: View?, addCloseButton: Boolean) {
        clear()
        isExternalSuggestionVisible = true
        pinnedExternal = view

        if (addCloseButton) {
            val wrapper = LinearLayout(context)
            suggestionsStrip.doOnNextLayout {
                wrapper.layoutParams = LinearLayout.LayoutParams(suggestionsStrip.width - 30.dpToPx(resources), LayoutParams.MATCH_PARENT)
            }
            wrapper.addView(view)
            suggestionsStrip.addView(wrapper)

            val closeButton = createToolbarKey(context, ToolbarKey.CLOSE_HISTORY)
            closeButton.layoutParams = toolbarKeyLayoutParams
            setupKey(closeButton, Settings.getValues().mColors)
            closeButton.setOnClickListener {
                listener.removeExternalSuggestions()
            }
            suggestionsStrip.addView(closeButton)
        } else {
            suggestionsStrip.addView(view)
        }

        setToolbarVisibility(false) // Murmur: always, or the tools row hides the logins
    }

    fun setMoreSuggestionsHeight(remainingHeight: Int) {
        layoutHelper.setMoreSuggestionsHeight(remainingHeight)
    }

    fun dismissMoreSuggestionsPanel() {
        moreSuggestionsView.dismissPopupKeysPanel()
    }

    // overrides: necessarily public, but not used from outside

    override fun onSharedPreferenceChanged(prefs: SharedPreferences, key: String?) {
        setToolbarButtonsActivatedStateOnPrefChange(pinnedKeys, key)
        setToolbarButtonsActivatedStateOnPrefChange(toolbar, key)
        if (key == Settings.PREF_ALWAYS_INCOGNITO_MODE)
            GlobalScope.launch { delay(10); withContext(Dispatchers.Main) { updateKeys() } }
    }

    override fun onVisibilityChanged(view: View, visibility: Int) {
        super.onVisibilityChanged(view, visibility)
        // workaround for a bug with inline suggestions views that just keep showing up otherwise, https://github.com/HeliBorg/HeliBoard/pull/386
        // Murmur: but not while the tools row (or a Murmur bar) is showing; both visible split the strip
        // and squeeze the tools into a small scrolling viewport (seen after closing the clipboard)
        if (view === this)
            suggestionsStrip.visibility = if (visibility == VISIBLE && (toolbarContainer.isVisible || murmurOwnsStrip())) GONE else visibility
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        dismissMoreSuggestionsPanel()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        // Called by the framework when the size is known. Show the important notice if applicable.
        // This may be overridden by showing suggestions later, if applicable.
    }

    override fun dispatchPopulateAccessibilityEvent(event: AccessibilityEvent): Boolean {
        // Don't populate accessibility event with suggested words and voice key.
        return true
    }

    override fun onInterceptTouchEvent(motionEvent: MotionEvent): Boolean {
        // Detecting sliding up finger to show MoreSuggestionsView.
        return moreSuggestionsView.shouldInterceptTouchEvent(motionEvent)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(motionEvent: MotionEvent): Boolean {
        moreSuggestionsView.touchEvent(motionEvent)
        return true
    }

    override fun onClick(view: View) {
        val tag = view.tag
        if (tag is ToolbarKey) {
            onClickToolbarKey(view) { listener.onCodeInput(it, Constants.SUGGESTION_STRIP_COORDINATE, Constants.SUGGESTION_STRIP_COORDINATE, false) }
            return
        }
        AudioAndHapticFeedbackManager.getInstance().performHapticAndAudioFeedback(KeyCode.NOT_SPECIFIED, this, HapticEvent.KEY_PRESS)
        if (view === toolbarExpandKey) {
            setToolbarVisibility(toolbarContainer.visibility != VISIBLE, fromUser = true)
        }

        // tag for word views is set in SuggestionStripLayoutHelper (setupWordViewsTextAndColor, layoutPunctuationSuggestions)
        if (tag is Int) {
            if (tag >= suggestedWords.size) {
                return
            }
            val wordInfo = suggestedWords.getInfo(tag)
            listener.pickSuggestionManually(wordInfo)
        }
    }

    override fun onLongClick(view: View): Boolean {
        if (view.tag is ToolbarKey) {
            onLongClickToolbarKey(view)
            return true
        }
        AudioAndHapticFeedbackManager.getInstance().performHapticFeedback(this, HapticEvent.KEY_LONG_PRESS)
        return if (view is TextView && wordViews.contains(view)) {
            onLongClickSuggestion(view)
        } else {
            showMoreSuggestions()
        }
    }

    // actually private stuff

    private fun onLongClickToolbarKey(view: View) {
        val tag = view.tag as? ToolbarKey ?: return
        if (!Settings.getValues().mQuickPinToolbarKeys || view.parent === pinnedKeys) {
            onLongClickToolbarKey(view) { code, isRepeat -> listener.onCodeInput(code, Constants.SUGGESTION_STRIP_COORDINATE, Constants.SUGGESTION_STRIP_COORDINATE, isRepeat) }
        } else if (view.parent === toolbar) {
            AudioAndHapticFeedbackManager.getInstance().performHapticFeedback(this, HapticEvent.KEY_LONG_PRESS)
            val pinnedKeyView = pinnedKeys.findViewWithTag<View>(tag)
            if (pinnedKeyView == null) {
                addKeyToPinnedKeys(tag)
                toolbar.findViewWithTag<View>(tag).background = enabledToolKeyBackground
                addPinnedKey(context.prefs(), tag)
            } else {
                removePinnedKey(context.prefs(), tag)
                toolbar.findViewWithTag<View>(tag).background = defaultToolbarBackground.constantState?.newDrawable(resources)
                pinnedKeys.removeView(pinnedKeyView)
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility") // no need for View#performClick, we only return false mostly anyway
    private fun onLongClickSuggestion(wordView: TextView): Boolean {
        var showIcon = true
        if (wordView.tag is Int) {
            val index = wordView.tag as Int
            val type = suggestedWords.getInfo(index).sourceDict
            if (type == Dictionary.DICTIONARY_USER_TYPED || type == Dictionary.DICTIONARY_HARDCODED)
                showIcon = false
        }
        if (showIcon) {
            val icon = KeyboardIconsSet.instance.getNewDrawable(KeyboardIconsSet.NAME_BIN, context)!!
            Settings.getValues().mColors.setColor(icon, ColorType.REMOVE_SUGGESTION_ICON)
            val w = icon.intrinsicWidth
            val h = icon.intrinsicHeight
            wordView.setCompoundDrawablesWithIntrinsicBounds(icon, null, null, null)
            wordView.ellipsize = TextUtils.TruncateAt.END
            val downOk = AtomicBoolean(false)
            wordView.setOnTouchListener { _, motionEvent ->
                if (motionEvent.action == MotionEvent.ACTION_UP && downOk.get()) {
                    val x = motionEvent.x
                    val y = motionEvent.y
                    if (0 < x && x < w && 0 < y && y < h) {
                        removeSuggestion(wordView)
                        wordView.cancelLongPress()
                        wordView.isPressed = false
                        return@setOnTouchListener true
                    }
                } else if (motionEvent.action == MotionEvent.ACTION_DOWN) {
                    val x = motionEvent.x
                    val y = motionEvent.y
                    if (0 < x && x < w && 0 < y && y < h) {
                        downOk.set(true)
                    }
                }
                false
            }
        }
        if (DebugFlags.DEBUG_ENABLED && (isShowingMoreSuggestionPanel || !showMoreSuggestions())) {
            showSourceDict(wordView)
            return true
        }
        return showMoreSuggestions()
    }

    private fun showMoreSuggestions(): Boolean {
        if (suggestedWords.size <= startIndexOfMoreSuggestions) {
            return false
        }
        if (!moreSuggestionsView.show(
                suggestedWords, startIndexOfMoreSuggestions, moreSuggestionsContainer, layoutHelper, this
        ))
            return false
        for (i in 0..<startIndexOfMoreSuggestions) {
            wordViews[i].isPressed = false
        }
        return true
    }

    private fun showSourceDict(wordView: TextView) {
        val word = wordView.text.toString()
        val index = wordView.tag as? Int ?: return
        val suggestedWords = suggestedWords
        if (index >= suggestedWords.size) return
        val info = suggestedWords.getInfo(index)
        if (info.word != word) return

        if (isShowingMoreSuggestionPanel) {
            moreSuggestionsView.dismissPopupKeysPanel()
        }
        var text = info.sourceDict.mDictType + ":" + info.sourceDict.mLocale
        if (index == 1 && suggestedWords.typedWordInfo != null && suggestedWords.typedWordInfo.sourceDict.mDictType != Dictionary.TYPE_USER_TYPED) {
            val dict = suggestedWords.typedWordInfo.sourceDict
            text += "\n${suggestedWords.typedWordInfo.word}: ${dict.mDictType}:${dict.mLocale}"
        }
        KeyboardSwitcher.getInstance().showToast(text, true)
    }

    private fun removeSuggestion(wordView: TextView) {
        val word = wordView.text.toString()
        listener.removeSuggestion(word)
        moreSuggestionsView.dismissPopupKeysPanel()
        // show suggestions, but without the removed word
        val newSuggestedWords = SuggestedWords(
            suggestedWords.filter { it.word != word },
            suggestedWords.rawSuggestions?.filter { it.word != word },
            suggestedWords.typedWordInfo, suggestedWords.typedWordValid, suggestedWords.willAutoCorrect,
            suggestedWords.isObsoleteSuggestions, suggestedWords.inputStyle, suggestedWords.sequenceNumber
        )
        setSuggestions(newSuggestedWords, direction != 1)
        suggestionsStrip.isVisible = true

        // Show the toolbar if no suggestions are left and the "Auto show toolbar" setting is enabled
        if (this.suggestedWords.isEmpty() && Settings.getValues().mAutoShowToolbar) {
            setToolbarVisibility(true)
        }
    }

    private fun clear() {
        suggestionsStrip.removeAllViews()
        if (DEBUG_SUGGESTIONS) removeAllDebugInfoViews()
        if (!toolbarContainer.isVisible && !murmurOwnsStrip())
            suggestionsStrip.isVisible = true
        dismissMoreSuggestionsPanel()
        for (word in wordViews) {
            word.setOnTouchListener(null)
        }
    }

    private fun removeAllDebugInfoViews() {
        for (debugInfoView in debugInfoViews) {
            val parent = debugInfoView.parent
            if (parent is ViewGroup) {
                parent.removeView(debugInfoView)
            }
        }
    }

    fun updateVoiceKey() {
        val show = Settings.getValues().mShowsVoiceInputKey
        toolbar.findViewWithTag<View>(ToolbarKey.VOICE)?.isVisible = show
        pinnedKeys.findViewWithTag<View>(ToolbarKey.VOICE)?.isVisible = show
        murmurMic.isVisible = show && murmurResizeBar == null
    }

    private fun updateKeys() {
        updateVoiceKey()
        val settingsValues = Settings.getValues()

        val toolbarIsExpandable = settingsValues.mToolbarMode == ToolbarMode.EXPANDABLE
        // Murmur: always the plain chevron. HeliBoard swapped in a spy icon in incognito fields (passwords),
        // which read as a stray glyph; nothing is learned there either way.
        toolbarExpandKey.setImageDrawable(toolbarArrowIcon)
        toolbarExpandKey.isVisible = toolbarIsExpandable

        toolbarExpandKey.setOnClickListener(if (!toolbarIsExpandable) null else this)
        pinnedKeys.visibility = suggestionsStrip.visibility
        isExternalSuggestionVisible = false
    }

    private fun addKeyToPinnedKeys(pinnedKey: ToolbarKey) {
        val original = toolbar.findViewWithTag<ImageButton>(pinnedKey) ?: return
        // copy the original key to a new ImageButton
        val copy = ImageButton(context, null, R.attr.suggestionWordStyle)
        copy.tag = pinnedKey
        copy.scaleType = original.scaleType
        copy.scaleX = original.scaleX
        copy.scaleY = original.scaleY
        copy.contentDescription = original.contentDescription
        copy.setImageDrawable(original.drawable)
        copy.layoutParams = original.layoutParams
        copy.isActivated = original.isActivated
        setupKey(copy, Settings.getValues().mColors)
        pinnedKeys.addView(copy)
    }

    /** Murmur: accent-filled pill, as in design/mockups.html. Tap dictates; long-press opens dictation settings. */
    private fun setupMurmurMic(colors: Colors) {
        murmurMic.tag = ToolbarKey.VOICE
        murmurMic.setOnClickListener(this)
        murmurMic.setOnLongClickListener {
            context.startActivity(android.content.Intent(context, dev.murmur.speech.ModelActivity::class.java).putExtra(dev.murmur.speech.ModelActivity.EXTRA_SCREEN, "DICTATION")
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        }
        murmurMic.background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = 20 * resources.displayMetrics.density
            setColor(colors.get(ColorType.ACTION_KEY_BACKGROUND))
        }
        val accent = colors.get(ColorType.ACTION_KEY_BACKGROUND)
        murmurMic.setColorFilter(if (androidx.core.graphics.ColorUtils.calculateLuminance(accent) > 0.3) 0xFF0D1336.toInt() else android.graphics.Color.WHITE)
        // Plain expand icon, no filled circle (design/mockups.html)
        toolbarExpandKey.background = null
    }

    // Murmur: listening state (design/mockups.html "listen"): keyboard button, status line, orange mic pill
    private var murmurListenBar: LinearLayout? = null
    private var murmurListenLabel: TextView? = null
    private var murmurSaved: List<Pair<View, Int>>? = null

    /** Shows [status] in the strip while dictating, or restores the strip when null. [onKeys] cancels back to the keys. */
    fun setMurmurListening(status: String?, onKeys: () -> Unit = {}) {
        val wrapper = murmurMic.parent as? LinearLayout ?: return
        val colors = Settings.getValues().mColors
        if (status == null) {
            murmurSaved?.forEach { (v, vis) -> v.visibility = vis }
            murmurSaved = null
            murmurListenBar?.let { wrapper.removeView(it) }
            murmurListenBar = null
            setupMurmurMic(colors)
            return
        }
        if (murmurSaved == null) {
            val hide = listOf<View>(toolbarExpandKey, toolbarContainer, suggestionsStrip, pinnedKeys)
            murmurSaved = hide.map { it to it.visibility }
            hide.forEach { it.visibility = GONE }
            val density = resources.displayMetrics.density
            val sub = colors.get(ColorType.KEY_HINT_TEXT)
            val label = TextView(context).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTextColor(sub)
                gravity = android.view.Gravity.CENTER
                isSingleLine = true
                // the mic pill (62 dp) is wider than the keyboard button (48 dp): nudge so the label centers in the strip
                setPadding((14 * density).toInt(), 0, 0, 0)
            }
            val keys = ImageButton(context).apply {
                setImageResource(R.drawable.ic_murmur_keyboard)
                setColorFilter(colors.get(ColorType.KEY_TEXT))
                background = null
                contentDescription = context.getString(R.string.murmur_dictation_back_to_keys)
            }
            val bar = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                addView(keys, LinearLayout.LayoutParams((48 * density).toInt(), LinearLayout.LayoutParams.MATCH_PARENT))
                addView(label, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
            }
            wrapper.addView(bar, 0, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
            murmurListenBar = bar
            murmurListenLabel = label
            murmurMic.background = GradientDrawable().apply {
                cornerRadius = 20 * density
                setColor(dev.murmur.design.MurmurTokens.RECORDING)
            }
            murmurMic.setColorFilter(Color.WHITE)
        }
        murmurListenBar?.getChildAt(0)?.setOnClickListener { onKeys() }
        murmurListenLabel?.text = status
    }

    /**
     * Murmur: SwiftKey-style emoji slot. If the best emoji suggestion didn't make the three visible
     * slots, it takes the right-hand one.
     */
    private fun reserveEmojiSlot(words: SuggestedWords): SuggestedWords {
        if (words.javaClass != SuggestedWords::class.java || words.size < 3) return words
        val list = words.suggestedWordInfoList
        val emoji = list.indices.firstOrNull { list[it].sourceDict.mDictType == helium314.keyboard.latin.dictionary.Dictionary.TYPE_EMOJI }
            ?: return words
        if (layoutHelper.isShownInStrip(emoji, words)) return words
        val right = layoutHelper.indexOfRightSlot(words)
        if (right < 0 || right == emoji) return words
        val reordered = list.toMutableList().apply { this[right] = list[emoji]; this[emoji] = list[right] }
        return SuggestedWords(reordered, words.rawSuggestions, words.typedWordInfo, words.typedWordValid,
            words.willAutoCorrect, words.isObsoleteSuggestions, words.inputStyle, words.sequenceNumber)
    }

    /** Murmur: each suggestion sits on its own rounded key-colored pill, inset from the strip (iOS-style). */
    private fun murmurSuggestionPill(colors: Colors): Drawable {
        val d = resources.displayMetrics.density
        val pill = GradientDrawable().apply { cornerRadius = 10 * d; setColor(colors.get(ColorType.KEY_BACKGROUND)) }
        val pressed = GradientDrawable().apply { cornerRadius = 10 * d; setColor(colors.get(ColorType.FUNCTIONAL_KEY_BACKGROUND)) }
        val states = android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), pressed)
            addState(intArrayOf(), pill)
        }
        return android.graphics.drawable.InsetDrawable(states, (2 * d).toInt(), (5 * d).toInt(), (2 * d).toInt(), (5 * d).toInt())
    }

    /** Murmur: opens 1Password, for sites where autofill doesn't offer the right login. Only if it's installed. */
    private fun addPasswordManagerTool(colors: Colors) {
        val launch = context.packageManager.getLaunchIntentForPackage(PASSWORD_MANAGER) ?: return
        val button = ImageButton(context, null, R.attr.suggestionWordStyle)
        button.setImageResource(R.drawable.ic_murmur_key)
        button.scaleType = android.widget.ImageView.ScaleType.CENTER
        button.contentDescription = context.getString(R.string.murmur_open_1password)
        button.layoutParams = LinearLayout.LayoutParams(toolbarKeyLayoutParams).apply { weight = 1f }
        colors.setColor(button, ColorType.TOOL_BAR_KEY)
        colors.setBackground(button, ColorType.STRIP_BACKGROUND)
        button.setOnClickListener {
            AudioAndHapticFeedbackManager.getInstance().performHapticAndAudioFeedback(KeyCode.NOT_SPECIFIED, this, HapticEvent.KEY_PRESS)
            context.startActivity(launch.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        toolbar.addView(button)
    }

    /** Murmur: opens the key height bar in the strip, so height can be tuned while looking at the keys. */
    private fun addHeightTool(colors: Colors) {
        val button = ImageButton(context, null, R.attr.suggestionWordStyle)
        button.setImageResource(R.drawable.ic_murmur_height)
        button.scaleType = android.widget.ImageView.ScaleType.CENTER
        button.contentDescription = context.getString(R.string.murmur_key_height)
        button.layoutParams = LinearLayout.LayoutParams(toolbarKeyLayoutParams).apply { weight = 1f }
        colors.setColor(button, ColorType.TOOL_BAR_KEY)
        colors.setBackground(button, ColorType.STRIP_BACKGROUND)
        button.setOnClickListener {
            AudioAndHapticFeedbackManager.getInstance().performHapticAndAudioFeedback(KeyCode.NOT_SPECIFIED, this, HapticEvent.KEY_PRESS)
            setMurmurResizing(true)
        }
        toolbar.addView(button)
    }

    // Murmur: key height bar: [-]  Key height · Medium  [+]  (Done). Each step applies right away.
    private var murmurResizeBar: LinearLayout? = null
    private var murmurResizeSaved: List<Pair<View, Int>>? = null

    fun isMurmurResizing() = murmurResizeBar != null
    private fun murmurOwnsStrip() = murmurResizeBar != null || murmurSaved != null

    fun setMurmurResizing(on: Boolean) {
        val wrapper = murmurMic.parent as? LinearLayout ?: return
        if (!on) {
            murmurResizeSaved?.forEach { (v, vis) -> v.visibility = vis }
            murmurResizeSaved = null
            murmurResizeBar?.let { wrapper.removeView(it) }
            murmurResizeBar = null
            return
        }
        if (murmurResizeBar != null || murmurSaved != null) return // already open, or dictating
        val colors = Settings.getValues().mColors
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val prefs = context.prefs()
        var index = dev.murmur.settings.KeyHeight.nearest(Settings.getValues().mKeyboardHeightScale)
        val ink = colors.get(ColorType.KEY_TEXT)
        val sub = colors.get(ColorType.KEY_HINT_TEXT)
        val fn = colors.get(ColorType.FUNCTIONAL_KEY_BACKGROUND)
        val accent = colors.get(ColorType.ACTION_KEY_BACKGROUND)
        val onAccent = if (androidx.core.graphics.ColorUtils.calculateLuminance(accent) > 0.3) 0xFF0D1336.toInt() else Color.WHITE

        fun stepper(icon: Int, label: Int) = ImageButton(context).apply {
            setImageResource(icon)
            setColorFilter(ink)
            scaleType = android.widget.ImageView.ScaleType.CENTER
            contentDescription = context.getString(label)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(fn) }
        }
        val minus = stepper(R.drawable.ic_murmur_minus, R.string.murmur_key_height_smaller)
        val plus = stepper(R.drawable.ic_murmur_plus, R.string.murmur_key_height_taller)
        val label = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = android.view.Gravity.CENTER
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val done = TextView(context).apply {
            text = context.getString(R.string.murmur_done)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(onAccent)
            gravity = android.view.Gravity.CENTER
            setPadding(dp(16), 0, dp(16), 0)
            background = GradientDrawable().apply { cornerRadius = 20 * density; setColor(accent) }
            setOnClickListener {
                AudioAndHapticFeedbackManager.getInstance().performHapticAndAudioFeedback(KeyCode.NOT_SPECIFIED, this, HapticEvent.KEY_PRESS)
                setMurmurResizing(false)
            }
        }
        fun render() {
            val name = dev.murmur.settings.KeyHeight.STEPS[index].second
            val title = context.getString(R.string.murmur_key_height)
            label.text = android.text.SpannableStringBuilder().apply {
                append(title, android.text.style.ForegroundColorSpan(sub), 0)
                append("  ")
                append(name, android.text.style.ForegroundColorSpan(ink), 0)
                setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), length - name.length, length, 0)
            }
            minus.isEnabled = index > 0; minus.alpha = if (index > 0) 1f else 0.35f
            val last = dev.murmur.settings.KeyHeight.STEPS.size - 1
            plus.isEnabled = index < last; plus.alpha = if (index < last) 1f else 0.35f
        }
        fun step(delta: Int) {
            val next = (index + delta).coerceIn(0, dev.murmur.settings.KeyHeight.STEPS.size - 1)
            if (next == index) return
            index = next
            AudioAndHapticFeedbackManager.getInstance().performHapticAndAudioFeedback(KeyCode.NOT_SPECIFIED, this, HapticEvent.KEY_PRESS)
            dev.murmur.settings.KeyHeight.write(prefs, index) // the settings listener reloads values
            render()
            post { KeyboardSwitcher.getInstance().reloadKeyboard() }
        }
        minus.setOnClickListener { step(-1) }
        plus.setOnClickListener { step(1) }
        render()

        val hide = listOf<View>(toolbarExpandKey, toolbarContainer, suggestionsStrip, pinnedKeys, murmurMic)
        murmurResizeSaved = hide.map { it to it.visibility }
        hide.forEach { it.visibility = GONE }
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(6), 0, dp(6), 0)
            addView(minus, LinearLayout.LayoutParams(dp(40), dp(40)))
            addView(label, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
            addView(plus, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(10) })
            addView(done, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(40)))
        }
        wrapper.addView(bar, 0, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        murmurResizeBar = bar
    }

    private fun setupKey(view: ImageButton, colors: Colors) {
        view.setOnClickListener(this)
        view.setOnLongClickListener(this)
        (view.layoutParams as LinearLayout.LayoutParams).weight = 1f
        colors.setColor(view, ColorType.TOOL_BAR_KEY)
        colors.setBackground(view, ColorType.STRIP_BACKGROUND)
    }

    companion object {
        /** Murmur: the tools row, in mockup order. */
        private val MURMUR_TOOLS = listOf(ToolbarKey.CLIPBOARD, ToolbarKey.SPLIT, ToolbarKey.SETTINGS) // emoji has its own key by the space bar
        private const val PASSWORD_MANAGER = "com.onepassword.android"

        @JvmField
        var DEBUG_SUGGESTIONS = false
        private const val DEBUG_INFO_TEXT_SIZE_IN_DIP = 6.5f
        private val TAG = SuggestionStripView::class.java.simpleName
    }
}

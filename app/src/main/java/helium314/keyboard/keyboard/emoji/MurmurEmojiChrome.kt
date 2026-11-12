// Murmur: the emoji panel's strip (ABC + category chips) and top row (search bar + backspace), per the mockup.
package helium314.keyboard.keyboard.emoji

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.widget.TextViewCompat
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.settings.Settings

internal object MurmurEmojiChrome {
    private val palette get() = Settings.getValues().mColors
    private fun View.dp(v: Float) = (v * resources.displayMetrics.density).toInt()
    private val ink get() = palette.get(ColorType.KEY_TEXT)
    private val onInk get() = if (ColorUtils.calculateLuminance(ink) > 0.5) 0xFF121419.toInt() else 0xFFFFFFFF.toInt()
    private val sub get() = palette.get(ColorType.EMOJI_CATEGORY)

    fun labelRes(category: EmojiCategory.Category) = when (category) {
        EmojiCategory.Category.RECENTS -> R.string.murmur_emoji_recents
        EmojiCategory.Category.SMILEYS -> R.string.murmur_emoji_smileys
        EmojiCategory.Category.PEOPLE -> R.string.murmur_emoji_people
        EmojiCategory.Category.NATURE -> R.string.murmur_emoji_nature
        EmojiCategory.Category.FOOD -> R.string.murmur_emoji_food
        EmojiCategory.Category.TRAVEL_PLACES -> R.string.murmur_emoji_travel
        EmojiCategory.Category.ACTIVITIES -> R.string.murmur_emoji_activities
        EmojiCategory.Category.OBJECTS -> R.string.murmur_emoji_objects
        EmojiCategory.Category.SYMBOLS -> R.string.murmur_emoji_symbols
        EmojiCategory.Category.FLAGS -> R.string.murmur_emoji_flags
        EmojiCategory.Category.EMOTICONS -> R.string.murmur_emoji_emoticons
    }

    private fun chip(parent: LinearLayout, text: String?) = TextView(parent.context).apply {
        this.text = text
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setPadding(dp(12f), 0, dp(12f), 0)
        minWidth = dp(44f)
        isSingleLine = true
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(32f)).apply {
            gravity = Gravity.CENTER_VERTICAL
            marginStart = dp(4f)
        }
    }

    /** Fills the strip: ABC on the left, then a scrolling row of category chips. Returns the chip row. */
    fun buildStrip(
        strip: LinearLayout, categories: List<EmojiCategory.Category>,
        onCategory: View.OnClickListener, onRecentsLongPress: View.OnLongClickListener, onAbc: () -> Unit,
    ): LinearLayout {
        strip.removeAllViews()
        strip.gravity = Gravity.CENTER_VERTICAL
        strip.addView(chip(strip, "ABC").apply {
            setTextColor(ink)
            background = GradientDrawable().apply {
                cornerRadius = dp(16f).toFloat(); setColor(palette.get(ColorType.FUNCTIONAL_KEY_BACKGROUND))
            }
            (layoutParams as LinearLayout.LayoutParams).marginStart = dp(6f)
            setOnClickListener { onAbc() }
        })
        val row = LinearLayout(strip.context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        categories.forEach { category ->
            val recents = category == EmojiCategory.Category.RECENTS
            row.addView(chip(row, if (recents) null else strip.context.getString(labelRes(category))).apply {
                tag = category
                contentDescription = strip.context.getString(labelRes(category))
                if (recents) {
                    setCompoundDrawablesRelativeWithIntrinsicBounds(
                        ContextCompat.getDrawable(context, R.drawable.ic_murmur_clock), null, null, null)
                    setOnLongClickListener(onRecentsLongPress)
                }
                setOnClickListener(onCategory)
                styleTab(this, false)
            })
        }
        strip.addView(HorizontalScrollView(strip.context).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                .apply { marginEnd = dp(6f) }
        })
        return row
    }

    /** Selected chip is filled with the ink color; the rest are plain text, like the mockup's tabs. */
    fun styleTab(view: View?, selected: Boolean) {
        val tv = view as? TextView ?: return
        val fg = if (selected) onInk else sub
        tv.setTextColor(fg)
        TextViewCompat.setCompoundDrawableTintList(tv, ColorStateList.valueOf(fg))
        tv.typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        tv.background = if (!selected) null else GradientDrawable().apply {
            cornerRadius = tv.dp(16f).toFloat(); setColor(ink)
        }
        if (selected) (tv.parent?.parent as? HorizontalScrollView)?.let { sv ->
            sv.post { sv.smoothScrollTo((tv.left - tv.dp(40f)).coerceAtLeast(0), 0) }
        }
    }

    /** Search pill that opens emoji search, and a backspace that repeats while held. */
    @SuppressLint("ClickableViewAccessibility")
    fun buildTop(top: LinearLayout, onSearch: () -> Unit, onDelete: () -> Unit) {
        top.removeAllViews()
        top.setPadding(top.dp(8f), top.dp(6f), top.dp(8f), 0)
        val keyBg = palette.get(ColorType.KEY_BACKGROUND)
        top.addView(TextView(top.context).apply {
            text = context.getString(R.string.murmur_emoji_search)
            textSize = 14f
            setTextColor(sub)
            gravity = Gravity.CENTER_VERTICAL
            compoundDrawablePadding = dp(8f)
            setPadding(dp(14f), 0, dp(14f), 0)
            setCompoundDrawablesRelativeWithIntrinsicBounds(
                ContextCompat.getDrawable(context, R.drawable.ic_murmur_search), null, null, null)
            TextViewCompat.setCompoundDrawableTintList(this, ColorStateList.valueOf(sub))
            background = GradientDrawable().apply { cornerRadius = dp(20f).toFloat(); setColor(keyBg) }
            layoutParams = LinearLayout.LayoutParams(0, dp(40f), 1f)
            setOnClickListener { onSearch() }
        })
        top.addView(ImageView(top.context).apply {
            setImageResource(R.drawable.sym_keyboard_delete_rounded)
            imageTintList = ColorStateList.valueOf(ink)
            scaleType = ImageView.ScaleType.CENTER
            contentDescription = context.getString(R.string.spoken_description_delete)
            background = GradientDrawable().apply {
                cornerRadius = dp(20f).toFloat(); setColor(palette.get(ColorType.FUNCTIONAL_KEY_BACKGROUND))
            }
            layoutParams = LinearLayout.LayoutParams(dp(56f), dp(40f)).apply { marginStart = dp(8f) }
            val handler = Handler(Looper.getMainLooper())
            val repeat = object : Runnable {
                override fun run() { onDelete(); handler.postDelayed(this, 50) }
            }
            setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { v.isPressed = true; onDelete(); handler.postDelayed(repeat, 400) }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { v.isPressed = false; handler.removeCallbacks(repeat) }
                }
                true
            }
        })
    }
}

package dev.murmur.snippets

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import dev.murmur.autofill.AutofillSheet
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.suggestions.SuggestionStripView
import java.lang.ref.WeakReference

/**
 * Murmur: snippets in the keyboard (design/styles-snippets.html, 02).
 * - A field that asks for an email, phone, address or link shows matching snippets as the first chips in the strip.
 * - The tools row's snippets button lists them all in a sheet.
 * Tapping one types it at the cursor. Password fields never get any.
 */
object SnippetChips {
    private var ime: WeakReference<LatinIME>? = null
    /** What the current field asks for (null: nothing, or a password field). */
    @Volatile var field: Snippets.Kind? = null
        private set

    @JvmStatic
    fun onStartInput(ime: LatinIME, info: EditorInfo?, restarting: Boolean, strip: SuggestionStripView?) {
        this.ime = WeakReference(ime)
        field = Snippets.kindFor(info)
        if (restarting || strip == null || strip.hasExternalSuggestions) return
        val row = chipRow(ime) ?: return
        strip.setExternalSuggestionView(row, true)
    }

    /** The chips for the current field, in a row; null when there are none. */
    @JvmStatic
    fun chipRow(context: Context): LinearLayout? {
        val kind = field ?: return null
        val list = Snippets.forKind(context, kind)
        if (list.isEmpty()) return null
        val d = context.resources.displayMetrics.density
        return LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding((6 * d).toInt(), 0, 0, 0)
            list.forEach { s ->
                addView(chip(context, s), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, (36 * d).toInt())
                    .apply { marginEnd = (6 * d).toInt(); gravity = Gravity.CENTER_VERTICAL })
            }
        }
    }

    private fun chip(context: Context, s: Snippets.Snippet): View {
        val colors = Settings.getValues().mColors
        val d = context.resources.displayMetrics.density
        val accent = colors.get(ColorType.ACTION_KEY_BACKGROUND)
        val ink = colors.get(ColorType.KEY_TEXT)
        val fill = ColorUtils.blendARGB(colors.get(ColorType.KEY_BACKGROUND), accent, 0.16f)
        val label = if (ColorUtils.calculateLuminance(fill) > 0.5) ColorUtils.blendARGB(accent, 0xFF000000.toInt(), 0.25f)
            else ColorUtils.blendARGB(accent, ink, 0.45f)
        return TextView(context).apply {
            text = s.text.lineSequence().first()
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(label)
            maxWidth = (240 * d).toInt()
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
            setPadding((12 * d).toInt(), 0, (14 * d).toInt(), 0)
            compoundDrawablePadding = (6 * d).toInt()
            setCompoundDrawablesRelativeWithIntrinsicBounds(icon(s.kind), 0, 0, 0)
            androidx.core.widget.TextViewCompat.setCompoundDrawableTintList(this, android.content.res.ColorStateList.valueOf(label))
            background = GradientDrawable().apply { cornerRadius = 18 * d; setColor(fill); setStroke((1 * d).toInt(), ColorUtils.setAlphaComponent(accent, 90)) }
            contentDescription = "Insert ${s.label}"
            setOnClickListener { insert(s.text) }
        }
    }

    private fun icon(kind: Snippets.Kind) = when (kind) {
        Snippets.Kind.EMAIL -> R.drawable.ic_murmur_mail
        Snippets.Kind.PHONE -> R.drawable.ic_murmur_phone
        Snippets.Kind.ADDRESS -> R.drawable.ic_murmur_pin
        Snippets.Kind.LINK -> R.drawable.ic_murmur_link
        Snippets.Kind.NONE -> R.drawable.ic_murmur_snippet
    }

    /** Types [text] at the cursor, with a space before it if it would touch the previous word. */
    fun insert(text: String) {
        val ime = ime?.get() ?: return
        val before = ime.currentInputConnection?.getTextBeforeCursor(1, 0)
        val prefix = if (!before.isNullOrEmpty() && !before.last().isWhitespace() && field == null) " " else ""
        ime.onTextInput(prefix + text)
    }

    /** The tools row's snippets button: every snippet in the sheet, or a pointer to settings when there are none. */
    @JvmStatic
    fun showSheet(context: Context) {
        val all = Snippets.all(context)
        if (field == null && isPasswordField()) return
        AutofillSheet.open(context, "Snippets", if (all.isEmpty()) "Add some in Murmur's settings" else "Tap one to type it") { list, width ->
            val d = context.resources.displayMetrics.density
            val dark = (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
            val ink = if (dark) dev.murmur.design.MurmurTokens.Dark.TEXT else dev.murmur.design.MurmurTokens.Light.TEXT
            val sub = if (dark) dev.murmur.design.MurmurTokens.Dark.SECONDARY_TEXT else dev.murmur.design.MurmurTokens.Light.SECONDARY_TEXT
            val rowBg = ColorUtils.setAlphaComponent(ink, 14)
            val rows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            if (all.isEmpty()) {
                rows.addView(TextView(context).apply {
                    text = "Open Murmur → Snippets to save your email, phone or address."
                    setTextColor(sub); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    setPadding(0, 0, 0, (12 * d).toInt())
                    setOnClickListener {
                        context.startActivity(android.content.Intent(context, dev.murmur.speech.ModelActivity::class.java)
                            .putExtra(dev.murmur.speech.ModelActivity.EXTRA_SCREEN, "SNIPPETS").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                        AutofillSheet.dismiss()
                    }
                })
            }
            // The field's own kind first
            all.sortedBy { if (it.kind == field) 0 else 1 }.forEach { s ->
                rows.addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding((14 * d).toInt(), (10 * d).toInt(), (14 * d).toInt(), (10 * d).toInt())
                    background = GradientDrawable().apply { cornerRadius = 16 * d; setColor(rowBg) }
                    addView(TextView(context).apply {
                        text = s.label; setTextColor(sub); setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                        setCompoundDrawablesRelativeWithIntrinsicBounds(icon(s.kind), 0, 0, 0)
                        compoundDrawablePadding = (6 * d).toInt()
                        androidx.core.widget.TextViewCompat.setCompoundDrawableTintList(this, android.content.res.ColorStateList.valueOf(sub))
                    })
                    addView(TextView(context).apply {
                        text = s.text; setTextColor(ink); setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                        maxLines = 3; ellipsize = TextUtils.TruncateAt.END
                    })
                    contentDescription = "Insert ${s.label}"
                    setOnClickListener { AutofillSheet.dismiss(); insert(s.text) }
                }, LinearLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = (8 * d).toInt() })
            }
            // Scrolls when there are many; at most about half the screen
            val max = (context.resources.displayMetrics.heightPixels * 0.5f).toInt()
            val scroll = object : ScrollView(context) {
                override fun onMeasure(w: Int, h: Int) = super.onMeasure(w, MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST))
            }
            scroll.isVerticalScrollBarEnabled = false
            scroll.addView(rows)
            list.addView(scroll)
        }
    }

    private fun isPasswordField(): Boolean {
        val info = ime?.get()?.currentInputEditorInfo ?: return false
        val cls = info.inputType and android.text.InputType.TYPE_MASK_CLASS
        val v = info.inputType and android.text.InputType.TYPE_MASK_VARIATION
        return (cls == android.text.InputType.TYPE_CLASS_TEXT && (v == android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD ||
            v == android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD || v == android.text.InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)) ||
            (cls == android.text.InputType.TYPE_CLASS_NUMBER && v == android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD)
    }
}

package com.regaim.app

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton

/** Small helpers to build the settings-style screens in code, in the Moments look. */
class Ui(private val a: Activity) {
    /** Called whenever the user changes a switch, chip or text field built by this helper (e.g. to show "save"). */
    var onChange: (() -> Unit)? = null

    private val d = a.resources.displayMetrics.density
    fun dp(v: Int) = (v * d).toInt()
    fun color(id: Int) = ContextCompat.getColor(a, id)

    private fun wrapLp(top: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(top) }

    fun text(s: CharSequence, size: Float = 15f, colorRes: Int = R.color.ink, bold: Boolean = false, top: Int = 0) =
        TextView(a).apply {
            text = s
            textSize = size
            setTextColor(color(colorRes))
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setLineSpacing(dp(2).toFloat(), 1f)
            layoutParams = wrapLp(top)
        }

    /** Section title above a card. */
    fun section(s: String) = text(s, 22f, R.color.ink, top = 28).apply { typeface = Fonts.display; androidx.core.view.ViewCompat.setAccessibilityHeading(this, true) }

    /** Small spaced label above a big heading ("ארכיון", "שאלו"). */
    fun eyebrow(s: String, top: Int = 0) = text(s, 14f, R.color.ink_soft, top = top).apply { letterSpacing = 0.12f }

    /** A big display heading. */
    fun heading(s: String, size: Float = 30f, top: Int = 0) = text(s, size, R.color.ink, top = top).apply { typeface = Fonts.display; androidx.core.view.ViewCompat.setAccessibilityHeading(this, true) }

    fun hint(s: String, top: Int = 4) = text(s, 13f, R.color.ink_soft, top = top)

    /** A card: warm beige, rounded 22dp, a whisper of shadow (design system). */
    fun card(top: Int = 8) = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        DS.cardBackground(this)
        val p = DS.padCard(a)
        setPadding(p, p - dp(2), p, p - dp(2))
        layoutParams = wrapLp(top).apply { leftMargin = dp(1); rightMargin = dp(1); bottomMargin = dp(2) } // room for the soft shadow
    }

    fun divider() = View(a).apply {
        setBackgroundColor(DS.line(a))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
            topMargin = dp(12); bottomMargin = dp(4)
        }
    }

    fun switch(label: String, checked: Boolean, top: Int = 8, onChange: (Boolean) -> Unit) = SwitchCompat(a).apply {
        text = label
        textSize = 15f
        setTextColor(color(R.color.ink))
        isChecked = checked
        thumbTintList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(DS.primary(a), DS.bg(a)),
        )
        trackTintList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(DS.button(a), DS.cardAlt(a)),
        )
        layoutParams = wrapLp(top)
        minHeight = dp(48)
        setOnCheckedChangeListener { _, on ->
            onChange(on)
            this@Ui.onChange?.invoke()
        }
    }

    /**
     * Design-system button: mocha with white text (primary) or cream with dark text (secondary), rounded 18dp,
     * with pressed and disabled states.
     */
    fun button(label: String, primary: Boolean = false, top: Int = 10, onClick: () -> Unit) = MaterialButton(a).apply {
        text = label
        textSize = 15f
        setAllCaps(false)
        typeface = DS.emphasis
        cornerRadius = DS.radiusButton(a).toInt()
        insetTop = 0; insetBottom = 0
        minHeight = DS.buttonHeight(a)
        elevation = 0f
        stateListAnimator = null
        val pressed = intArrayOf(android.R.attr.state_pressed)
        val disabled = intArrayOf(-android.R.attr.state_enabled)
        backgroundTintList = ColorStateList(
            arrayOf(disabled, pressed, intArrayOf()),
            if (primary) intArrayOf(DS.disabledBg(a), DS.buttonPressed(a), DS.button(a))
            else intArrayOf(DS.disabledBg(a), DS.buttonSecondaryPressed(a), DS.buttonSecondary(a)),
        )
        rippleColor = ColorStateList.valueOf(DS.ripple(a, onDark = primary))
        strokeWidth = 0
        setTextColor(ColorStateList(
            arrayOf(disabled, intArrayOf()),
            intArrayOf(DS.disabledInk(a), if (primary) DS.onButton(a) else DS.ink(a)),
        ))
        layoutParams = wrapLp(top)
        setOnClickListener { onClick() }
    }

    /** A text link-like row: label on the right, value / arrow on the left. */
    fun row(label: String, value: String = "", top: Int = 4, onClick: (() -> Unit)? = null) = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(10), 0, dp(10))
        layoutParams = wrapLp(top)
        addView(TextView(a).apply {
            text = label
            textSize = 15f
            setTextColor(color(R.color.ink))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        addView(TextView(a).apply {
            text = if (onClick != null) "$value ›".trim() else value
            textSize = 14f
            setTextColor(if (onClick != null) DS.primary(a) else DS.inkSoft(a))
        })
        if (onClick != null) {
            setBackgroundResource(android.R.drawable.list_selector_background)
            setOnClickListener { onClick() }
            // Read as one button: "Language, Hebrew".
            minimumHeight = dp(48)
            contentDescription = if (value.isBlank()) label else "$label, $value"
            A11y.button(this)
        }
    }

    fun field(hint: String, value: String, multiline: Boolean = false, top: Int = 8, password: Boolean = false) = EditText(a).apply {
        this.hint = hint
        setText(value)
        textSize = 15f
        setTextColor(color(R.color.ink))
        setBackgroundResource(R.drawable.chat_input_bg)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        gravity = Gravity.TOP or Gravity.START
        inputType = when {
            password -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            multiline -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        if (multiline) minLines = 2
        layoutParams = wrapLp(top)
        addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a2: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { onChange?.invoke() }
        })
    }

    /**
     * A row of pill chips; tapping one selects it. Read as one choice out of several ("2 of 5, selected"), and the
     * chips are repainted in place, so a screen reader stays on the chip that was tapped.
     */
    fun chips(options: List<String>, selected: Int, top: Int = 8, onPick: (Int) -> Unit): View {
        val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
        var sel = selected
        val views = ArrayList<TextView>()
        fun paint() {
            views.forEachIndexed { i, t ->
                val on = i == sel
                t.typeface = if (on) DS.emphasis else DS.body
                // (The pressed blue on the light-blue pill: 5.98:1; the brand blue was 4.40:1.)
                t.setTextColor(if (on) DS.buttonPressed(a) else DS.inkSoft(a))
                // A soft gray pill; the chosen one light blue with blue text (no heavy fill).
                t.background = DS.rounded(if (on) DS.selected(a) else DS.buttonSecondary(a), DS.radiusChip(a))
                t.isSelected = on
            }
        }
        options.forEachIndexed { i, o ->
            val t = TextView(a).apply {
                text = o
                textSize = 14f
                gravity = Gravity.CENTER
                minWidth = dp(56)
                setPadding(dp(16), dp(9), dp(16), dp(9))
                minHeight = dp(48)
                A11y.choice(this, i, options.size) { sel == i }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(8) }
                setOnClickListener {
                    if (sel == i) return@setOnClickListener
                    sel = i
                    paint()
                    onPick(i)
                    onChange?.invoke()
                }
            }
            views.add(t); row.addView(t)
        }
        paint()
        A11y.choiceGroup(row, options.size)
        return HorizontalScrollView(a).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = wrapLp(top)
            addView(row)
        }
    }

    /** A round thumbnail of a moment. */
    fun bubble(path: String, sizeDp: Int = 48) = ImageView(a).apply {
        val bmp = if (path.isNotEmpty()) BitmapFactory.decodeFile(path) else null
        if (bmp != null) setImageBitmap(bmp) else setImageResource(R.drawable.bubble_placeholder)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO // a thumbnail next to its text
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)).apply { marginEnd = dp(6) }
    }

    fun horizontal(top: Int = 8) = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = wrapLp(top)
    }

    fun scrollRow(inner: LinearLayout, top: Int = 8) = HorizontalScrollView(a).apply {
        isHorizontalScrollBarEnabled = false
        layoutParams = wrapLp(top)
        addView(inner.apply { layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT) })
    }

    /** Small inline action like "תקן" / "מחק". */
    fun action(label: String, colorRes: Int = R.color.ds_primary, onClick: () -> Unit) = TextView(a).apply {
        text = label
        textSize = 14f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(color(colorRes))
        setPadding(dp(10), dp(6), dp(10), dp(6))
        // A finger-sized target (48dp), read as a button.
        minHeight = dp(48)
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
        setBackgroundResource(android.R.drawable.list_selector_background)
        setOnClickListener { onClick() }
        A11y.button(this)
    }

    fun padded(v: View): LinearLayout = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(8), dp(20), 0)
        addView(v)
    }
}

package com.regaim.app

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat

/**
 * Accessibility for every screen, in one place (the screens are built in code, where the usual checks don't reach):
 * after each layout, a picture with no description is skipped by the screen reader (decoration), a picture you tap
 * gets a name, a tappable symbol ("✕", "‹") gets its word, and something tappable with no words at all is called a
 * button. Descriptions a screen gave itself are never changed.
 */
object A11y {
    fun install(app: Application) = app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
        override fun onActivityStarted(a: Activity) {
            val root = a.window?.decorView ?: return
            if (root.getTag(R.id.tagA11yApplied) == true) return
            root.setTag(R.id.tagA11yApplied, true)
            val fix = Runnable { try { fix(root) } catch (_: Throwable) { } }
            root.viewTreeObserver.addOnGlobalLayoutListener { root.removeCallbacks(fix); root.postDelayed(fix, 250) }
            try { keepPlace(root) } catch (_: Throwable) { }
            // Dialogs and bottom sheets are windows of their own: when one opens (this window loses focus), it gets
            // the same treatment.
            root.viewTreeObserver.addOnWindowFocusChangeListener { has -> if (!has) root.postDelayed({ try { coverWindows() } catch (_: Throwable) { } }, 120) }
        }
        override fun onActivityCreated(a: Activity, b: Bundle?) {}
        override fun onActivityResumed(a: Activity) {}
        override fun onActivityPaused(a: Activity) {}
        override fun onActivityStopped(a: Activity) {}
        override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
        override fun onActivityDestroyed(a: Activity) {}
    })

    /** The word for a symbol used as a button's whole text (null: not one of them). */
    fun symbolName(text: String): String? = when (text.trim()) {
        "✕", "×", "✖", "X" -> L.t("סגירה", "Close")
        "‹", "›", "←", "→" -> null // direction words depend on the screen: left as the screen says
        "⌫" -> L.t("מחיקת האחרון", "Delete the last one")
        "⋮", "…", "⋯" -> L.t("עוד אפשרויות", "More options")
        "▶", "▶️" -> L.t("ניגון", "Play")
        "⏸", "⏸️" -> L.t("השהיה", "Pause")
        "＋", "+" -> L.t("הוספה", "Add")
        "⚙", "⚙️" -> L.t("הגדרות", "Settings")
        else -> null
    }

    /** Every window of the app now on screen (dialogs, sheets) gets [fix] after each layout. Android 10 and later. */
    private fun coverWindows() {
        if (android.os.Build.VERSION.SDK_INT < 29) return
        for (w in android.view.inspector.WindowInspector.getGlobalWindowViews()) {
            if (w.getTag(R.id.tagA11yApplied) == true) continue
            w.setTag(R.id.tagA11yApplied, true)
            val fix = Runnable { try { fix(w) } catch (_: Throwable) { } }
            w.viewTreeObserver.addOnGlobalLayoutListener { w.removeCallbacks(fix); w.postDelayed(fix, 150) }
            w.post(fix)
        }
    }

    private val emoji = Regex("[\\x{1F000}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{2B00}-\\x{2BFF}\\x{2190}-\\x{21FF}\\x{25A0}-\\x{25FF}\\x{2300}-\\x{23FF}\\x{FE0F}\\x{200D}\\x{20E3}\\x{1F3FB}-\\x{1F3FF}]")
    private val letters = Regex("[\\p{L}\\p{N}]")

    /** [text] without the emoji and symbols around its words ("💞 לחבר את בן או בת הזוג" -> the words), or null. */
    fun spoken(text: CharSequence?): String? {
        val t = text?.toString() ?: return null
        if (!emoji.containsMatchIn(t) || !letters.containsMatchIn(t)) return null
        return t.replace(emoji, " ").replace(Regex("\\s{2,}"), " ").trim().takeIf { it.isNotEmpty() }
    }

    /**
     * For a text or a tappable view the screen gave no accessibility treatment of its own: said as a button when it's
     * tappable, and its emoji left out when there are words ("💾 גיבוי" is read "גיבוי"). Decided each time it's read,
     * so a text that changes is always said as it is now.
     */
    private fun auto(v: View) {
        if (v.getTag(R.id.tagA11yAuto) == true || ViewCompat.hasAccessibilityDelegate(v)) return
        if (v is android.widget.EditText || v is android.widget.CompoundButton) return
        if (v !is TextView && !v.isClickable) return
        v.setTag(R.id.tagA11yAuto, true)
        ViewCompat.setAccessibilityDelegate(v, object : AccessibilityDelegateCompat() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                if (host.isClickable && host !is android.widget.Button && info.className?.toString()?.endsWith("Button") != true) info.className = android.widget.Button::class.java.name
                if (host is TextView && host.contentDescription.isNullOrBlank()) spoken(host.text)?.let { info.contentDescription = it }
            }
        })
    }

    /** Something tappable that's lower than 48dp and sizes itself: made 48dp tall (and wide, when it's a symbol). */
    private fun target(v: View) {
        if (!v.isClickable || v is android.widget.EditText) return
        val min = (48 * v.resources.displayMetrics.density).toInt()
        val lp = v.layoutParams ?: return
        if (lp.height == ViewGroup.LayoutParams.WRAP_CONTENT && v.minimumHeight < min && v.height in 1 until min) v.minimumHeight = min
        if (lp.width == ViewGroup.LayoutParams.WRAP_CONTENT && v.minimumWidth < min && v.width in 1 until min) v.minimumWidth = min
    }

    private fun blank(v: View) = v.contentDescription.isNullOrBlank()

    private fun hasWords(v: View): Boolean = when {
        !blank(v) -> true
        v is TextView -> !v.text.isNullOrBlank()
        v is ViewGroup -> (0 until v.childCount).any { hasWords(v.getChildAt(it)) }
        else -> false
    }

    fun fix(v: View) {
        if (v.visibility != View.VISIBLE) return
        when {
            v is ImageView && blank(v) && v.importantForAccessibility == View.IMPORTANT_FOR_ACCESSIBILITY_AUTO ->
                if (v.isClickable) v.contentDescription = L.t("תמונה, לחצו לפתיחה", "Picture, tap to open")
                else v.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            v is TextView && v.isClickable && blank(v) -> symbolName(v.text?.toString().orEmpty())?.let { v.contentDescription = it }
            v.isClickable && v !is TextView && !hasWords(v) && v.importantForAccessibility == View.IMPORTANT_FOR_ACCESSIBILITY_AUTO ->
                v.contentDescription = L.t("כפתור", "Button")
        }
        try { auto(v); target(v) } catch (_: Throwable) { }
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) fix(v.getChildAt(i))
            linkLabels(v)
        }
    }

    /** A field right after its label (a plain text above it): the label is tied to the field, so both are said together. */
    private fun linkLabels(g: ViewGroup) {
        for (i in 1 until g.childCount) {
            val field = g.getChildAt(i) as? android.widget.EditText ?: continue
            val label = g.getChildAt(i - 1) as? TextView ?: continue
            if (label is android.widget.EditText || label.isClickable || label.text.isNullOrBlank() || label.labelFor != View.NO_ID) continue
            // Only a short label: not a screen's heading, not an explanation sentence above the field.
            if (label.getTag(R.id.tagA11yLabel) != true) {
                val t = label.text.toString().trim()
                if (ViewCompat.isAccessibilityHeading(label) || t.length > 40 || t.endsWith(".") || t.endsWith("…") || t.endsWith("!") || t.contains('\n')) continue
            }
            if (field.id == View.NO_ID) field.id = View.generateViewId()
            label.labelFor = field.id
        }
    }

    /** One choice out of [count] (radio-like): "Hebrew, 1 of 2, selected". [on] says whether it's the chosen one now. */
    fun choice(v: View, index: Int, count: Int, on: () -> Boolean) = ViewCompat.setAccessibilityDelegate(v, object : AccessibilityDelegateCompat() {
        override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
            super.onInitializeAccessibilityNodeInfo(host, info)
            info.className = android.widget.RadioButton::class.java.name
            info.isCheckable = true
            info.isChecked = on()
            info.setCollectionItemInfo(AccessibilityNodeInfoCompat.CollectionItemInfoCompat.obtain(0, 1, index, 1, false, on()))
        }
    })

    /** The row holding [choice]s: said as a group of [count]. */
    fun choiceGroup(v: View, count: Int) = ViewCompat.setAccessibilityDelegate(v, object : AccessibilityDelegateCompat() {
        override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
            super.onInitializeAccessibilityNodeInfo(host, info)
            info.setCollectionInfo(AccessibilityNodeInfoCompat.CollectionInfoCompat.obtain(1, count, false,
                AccessibilityNodeInfoCompat.CollectionInfoCompat.SELECTION_MODE_SINGLE))
        }
    })

    /**
     * After a screen is built again (a choice re-renders it), the screen reader's place is kept. The view it was on is
     * found again: the one with the same words (when several have them, the same one of them by order); if its words
     * changed (a box was ticked), the view at the same place on the screen. When nothing matches safely, nothing moves.
     */
    private class Place(val key: String, val ordinal: Int, val sameKey: Int, val index: Int, val total: Int)

    private fun keepPlace(root: View) {
        var last: java.lang.ref.WeakReference<View>? = null
        var place: Place? = null
        val content = root.findViewById<ViewGroup>(android.R.id.content) ?: return
        ViewCompat.setAccessibilityDelegate(content, object : AccessibilityDelegateCompat() {
            override fun onRequestSendAccessibilityEvent(host: ViewGroup, child: View, event: android.view.accessibility.AccessibilityEvent): Boolean {
                if (event.eventType == android.view.accessibility.AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED) try {
                    val all = points(content)
                    val words = (event.contentDescription ?: event.text.joinToString(" ")).toString().trim()
                    val src = all.firstOrNull { (words.isEmpty() || keyOf(it) == words) && focused(it) } ?: all.firstOrNull { focused(it) }
                    if (src != null) {
                        val key = keyOf(src)
                        val same = all.filter { keyOf(it) == key }
                        last = java.lang.ref.WeakReference(src)
                        place = Place(key, same.indexOf(src), same.size, all.indexOf(src), all.size)
                    }
                } catch (_: Throwable) { }
                return super.onRequestSendAccessibilityEvent(host, child, event)
            }
        })
        root.viewTreeObserver.addOnGlobalLayoutListener {
            val v = last?.get() ?: return@addOnGlobalLayoutListener
            val p = place ?: return@addOnGlobalLayoutListener
            if (v.isAttachedToWindow) return@addOnGlobalLayoutListener
            last = null; place = null
            val all = points(content)
            val same = if (p.key.isEmpty()) emptyList() else all.filter { keyOf(it) == p.key }
            val again = when {
                same.size == 1 && p.sameKey == 1 -> same[0]
                same.size == p.sameKey && p.ordinal in same.indices -> same[p.ordinal]
                all.size == p.total && p.index in all.indices -> all[p.index]
                else -> null
            } ?: return@addOnGlobalLayoutListener
            again.post { again.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null) }
        }
    }

    /** Has the screen reader's focus now. */
    private fun focused(v: View): Boolean {
        val n = v.createAccessibilityNodeInfo()
        return try { n.isAccessibilityFocused } finally { @Suppress("DEPRECATION") n.recycle() }
    }

    /** The places a screen reader stops at, in screen order. */
    private fun points(g: View): List<View> {
        val out = ArrayList<View>()
        fun walk(v: View) {
            if (v.visibility != View.VISIBLE || v.importantForAccessibility == View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS) return
            val important = v.importantForAccessibility != View.IMPORTANT_FOR_ACCESSIBILITY_NO
            if (important && (v.isClickable || v.isFocusable || (v is TextView && !v.text.isNullOrBlank()) || !v.contentDescription.isNullOrBlank())) out.add(v)
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(g)
        return out
    }

    /** A screen's own stable name for a view (R.id.tagA11yKey), else its words. */
    private fun keyOf(v: View): String = (v.getTag(R.id.tagA11yKey) as? String)
        ?: (v.contentDescription ?: (v as? TextView)?.text ?: "").toString().trim()

    /** A tickable item (a moment in selection, a face to correct): said with "ticked" / "not ticked". */
    fun check(v: View, on: () -> Boolean) = ViewCompat.setAccessibilityDelegate(v, object : AccessibilityDelegateCompat() {
        override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
            super.onInitializeAccessibilityNodeInfo(host, info)
            info.className = android.widget.CheckBox::class.java.name
            info.isCheckable = true
            info.isChecked = on()
        }
    })

    /** A status line: whatever it says next is read out by itself (politely, or at once for an error). */
    fun live(v: View, urgent: Boolean = false) {
        v.accessibilityLiveRegion = if (urgent) View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE else View.ACCESSIBILITY_LIVE_REGION_POLITE
    }

    /** A section title: screen readers jump between these. */
    fun heading(v: View) = ViewCompat.setAccessibilityHeading(v, true)

    /** A long-press with a name in the screen reader's actions ("Choose as cover" instead of "long press"). */
    fun longPress(v: View, name: String) =
        ViewCompat.replaceAccessibilityAction(v, AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_LONG_CLICK, name, null)

    /** Expandable: said as "expanded" / "collapsed". */
    fun expanded(v: View, open: Boolean) = ViewCompat.setStateDescription(v, if (open) L.t("פתוח", "Expanded") else L.t("סגור", "Collapsed"))

    /** [text] read in English (a word the child learns to say, "Google Photos"), inside Hebrew. */
    fun english(text: CharSequence): CharSequence = android.text.SpannableString(text).apply {
        setSpan(android.text.style.LocaleSpan(java.util.Locale.ENGLISH), 0, length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    /**
     * How long to wait before something moves on by itself (a child's turn to answer, controls that hide): longer when
     * the person asked the phone for more time (accessibility timeout), and never shorter than [ms]. Pitzki's parent
     * setting "more time to answer" multiplies it too.
     */
    fun timeout(ctx: android.content.Context, ms: Long): Long {
        val extra = Settings.prefs(ctx).getInt("answer_time_x", 1).coerceIn(1, 3)
        val am = ctx.getSystemService(android.view.accessibility.AccessibilityManager::class.java)
        val asked = if (android.os.Build.VERSION.SDK_INT >= 29 && am != null)
            am.getRecommendedTimeoutMillis(ms.toInt(), android.view.accessibility.AccessibilityManager.FLAG_CONTENT_CONTROLS or
                android.view.accessibility.AccessibilityManager.FLAG_CONTENT_TEXT).toLong() else ms
        return maxOf(ms, asked) * extra
    }

    /** The phone is set to remove animations: things that move or change by themselves stay still. */
    fun stillMotion(): Boolean = !android.animation.ValueAnimator.areAnimatorsEnabled()

    /** A screen reader (TalkBack) is on. */
    fun screenReader(ctx: android.content.Context): Boolean =
        ctx.getSystemService(android.view.accessibility.AccessibilityManager::class.java)?.isTouchExplorationEnabled == true

    /** A video with the system's play / pause / seek controls and a name. */
    fun videoControls(video: android.widget.VideoView, anchor: View, name: String) {
        video.setMediaController(android.widget.MediaController(video.context).also { it.setAnchorView(anchor) })
        video.contentDescription = name
    }

    /** Asks before something is deleted or cancelled for good. */
    fun confirm(a: android.app.Activity, question: String, yes: String, then: () -> Unit) {
        androidx.appcompat.app.AlertDialog.Builder(a).setMessage(question)
            .setPositiveButton(yes) { _, _ -> then() }
            .setNegativeButton(L.t("ביטול", "Cancel"), null).show()
    }

    /** Read as a button (a tappable text that isn't a Button). */
    fun button(v: View) = ViewCompat.setAccessibilityDelegate(v, object : AccessibilityDelegateCompat() {
        override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
            super.onInitializeAccessibilityNodeInfo(host, info)
            info.className = android.widget.Button::class.java.name
        }
    })
}

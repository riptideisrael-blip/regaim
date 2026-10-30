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
     * After a screen is built again (a choice re-renders it), the screen reader's place is kept: the view it was on
     * is found again by its words, instead of jumping to the top of the screen.
     */
    private fun keepPlace(root: View) {
        var last: java.lang.ref.WeakReference<View>? = null
        var key = ""
        val content = root.findViewById<ViewGroup>(android.R.id.content) ?: return
        ViewCompat.setAccessibilityDelegate(content, object : AccessibilityDelegateCompat() {
            override fun onRequestSendAccessibilityEvent(host: ViewGroup, child: View, event: android.view.accessibility.AccessibilityEvent): Boolean {
                if (event.eventType == android.view.accessibility.AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED) {
                    val src = findSource(child, event) ?: child
                    last = java.lang.ref.WeakReference(src); key = keyOf(src)
                }
                return super.onRequestSendAccessibilityEvent(host, child, event)
            }
        })
        root.viewTreeObserver.addOnGlobalLayoutListener {
            val v = last?.get() ?: return@addOnGlobalLayoutListener
            if (v.isAttachedToWindow || key.isEmpty()) return@addOnGlobalLayoutListener
            last = null
            val again = find(content, key) ?: return@addOnGlobalLayoutListener
            again.post { again.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null) }
        }
    }

    /** The view an accessibility event is about (the event's source may be a child of [child]). */
    private fun findSource(child: View, event: android.view.accessibility.AccessibilityEvent): View? {
        val words = (event.contentDescription ?: event.text.joinToString(" ")).toString()
        if (words.isBlank()) return null
        return find(child, words) ?: child
    }

    private fun keyOf(v: View): String = (v.contentDescription ?: (v as? TextView)?.text ?: "").toString().trim()

    private fun find(v: View, key: String): View? {
        if (v.visibility != View.VISIBLE) return null
        if (keyOf(v) == key && (v.isFocusable || v.isClickable || v is TextView)) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i), key)?.let { return it }
        return null
    }

    /** Read as a button (a tappable text that isn't a Button). */
    fun button(v: View) = ViewCompat.setAccessibilityDelegate(v, object : AccessibilityDelegateCompat() {
        override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
            super.onInitializeAccessibilityNodeInfo(host, info)
            info.className = android.widget.Button::class.java.name
        }
    })
}

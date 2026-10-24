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
        if (v is ViewGroup) for (i in 0 until v.childCount) fix(v.getChildAt(i))
    }

    /** Read as a button (a tappable text that isn't a Button). */
    fun button(v: View) = ViewCompat.setAccessibilityDelegate(v, object : AccessibilityDelegateCompat() {
        override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
            super.onInitializeAccessibilityNodeInfo(host, info)
            info.className = android.widget.Button::class.java.name
        }
    })
}

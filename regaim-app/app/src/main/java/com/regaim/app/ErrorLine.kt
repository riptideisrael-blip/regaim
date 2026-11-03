package com.regaim.app

import android.app.Activity
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.EditText
import android.widget.TextView

/**
 * Errors that stay on the screen and are read out (a toast disappears after a few seconds and isn't tied to anything):
 * a line in the error color with "⚠️", a live region, and for a field its own error, focus and announcement.
 */
object ErrorLine {
    /** A hidden error line, to place where the error belongs; [show] fills it. */
    fun make(a: Activity, top: Int = 6): TextView = Ui(a).text("", 14f, R.color.ds_error, top = top).apply {
        visibility = View.GONE
        A11y.live(this)
    }

    /** Shows [msg] in [line] (empty hides it). */
    fun show(line: TextView, msg: String) {
        if (msg.isBlank()) { line.visibility = View.GONE; line.text = ""; return }
        line.text = "⚠️ $msg"
        line.contentDescription = msg
        line.visibility = View.VISIBLE
    }

    /** The error on the field itself: shown under it, the field focused (also for a screen reader) and the error said. */
    fun field(f: EditText, msg: String) {
        f.error = msg
        f.requestFocus()
        f.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null)
        f.announceForAccessibility(msg)
    }
}

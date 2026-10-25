package com.regaim.app

import android.app.Activity
import android.text.InputType
import android.widget.EditText
import androidx.appcompat.app.AlertDialog

/**
 * "For parents": a short sum a 3–5-year-old can't do yet, before the parts of the kids' screens that are the
 * parent's (settings, paying, sharing out of the app). Once passed, it isn't asked again for 10 minutes. (Google
 * Play's families policy asks for this in an app for children.)
 */
object ParentGate {
    @Volatile private var passedAt = 0L

    /** A sum with a two-digit answer, and its answer. Pure. */
    fun question(seed: Long): Pair<String, Int> {
        val r = java.util.Random(seed)
        val a = 6 + r.nextInt(7); val b = 5 + r.nextInt(8)
        return "$a + $b" to (a + b)
    }

    fun ask(a: Activity, then: () -> Unit) {
        if (System.currentTimeMillis() - passedAt < 10 * 60_000L) { then(); return }
        val (q, answer) = question(System.nanoTime())
        val field = EditText(a).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "?"
            contentDescription = L.t("התשובה לתרגיל", "The answer")
            textSize = 22f
            gravity = android.view.Gravity.CENTER
        }
        val box = android.widget.FrameLayout(a).apply {
            val pad = (20 * a.resources.displayMetrics.density).toInt()
            setPadding(pad, 0, pad, 0)
            addView(field)
        }
        AlertDialog.Builder(a)
            .setTitle(L.t("להורים בלבד", "For parents"))
            .setMessage(L.t("כמה זה $q?", "What is $q?"))
            .setView(box)
            .setPositiveButton(L.t("המשך", "Continue")) { _, _ ->
                if (field.text.toString().trim().toIntOrNull() == answer) { passedAt = System.currentTimeMillis(); then() }
                else android.widget.Toast.makeText(a, L.t("לא נכון. נסו שוב.", "Not right. Try again."), android.widget.Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(L.t("ביטול", "Cancel"), null)
            .show()
    }
}

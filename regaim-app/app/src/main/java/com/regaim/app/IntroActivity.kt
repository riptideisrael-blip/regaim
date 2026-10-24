package com.regaim.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * The first time the app opens: three short screens on what Moments is for and how private it is, before signing in.
 * Shown once; "דלג" skips it.
 */
class IntroActivity : AppCompatActivity() {
    companion object {
        private fun prefs(ctx: Context) = ctx.getSharedPreferences("regaim", Context.MODE_PRIVATE)
        fun seen(ctx: Context) = prefs(ctx).getBoolean("intro_seen", false)
        fun open(ctx: Context) = ctx.startActivity(Intent(ctx, IntroActivity::class.java))
    }

    private class Page(val icon: String, val title: String, val body: String)

    private val pages by lazy {
        listOf(
            Page("✨", L.t("הרגעים הכי יפים קורים כשאף אחד לא מצלם", "The best moments happen when nobody is filming"),
                L.t("צחוק מתגלגל, חיבוק, ריצה אליכם בפתח הבית. Moments מזהה את הרגעים האלה ושומר אותם בשבילכם, בלי לעצור ולהוציא טלפון.",
                    "A belly laugh, a hug, a run into your arms at the door. Moments recognizes these moments and keeps them for you, without stopping to take out a phone.")),
            Page("🔒", L.t("הכל קורה בטלפון שלכם", "It all happens on your phone"),
                L.t("הזיהוי של צחוק ושל פנים רץ בטלפון עצמו. כל רגע מחכה לאישור שלכם, ושום דבר לא משותף בלי שבחרתם. לפני ההקלטה הראשונה נסביר בדיוק מה נעשה עם המצלמה והמיקרופון.",
                    "Recognizing laughter and faces runs on the phone itself. Every moment waits for your approval, and nothing is shared unless you choose. Before the first recording we'll explain exactly what happens with the camera and microphone.")),
            Page("📖", L.t("ומהזיכרונות נוצר קסם", "And memories become magic"),
                L.t("סרטון שבועי עם מוזיקה, ספר אישי מצויר שבו הילדים שלכם הם הגיבורים, ומעגל משפחה פרטי שבו סבא וסבתא רואים הכל.",
                    "A weekly video with music, a personal illustrated book with your children as the heroes, and a private family circle where grandma and grandpa see it all.")),
        )
    }
    private var at = 0
    private val ui by lazy { Ui(this) }
    private lateinit var root: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(ui.color(R.color.bg))
            setPadding(ui.dp(28), ui.dp(40), ui.dp(28), ui.dp(28))
        }
        setContentView(root)
        render()
    }

    private fun render() {
        root.removeAllViews()
        val p = pages[at]
        root.addView(TextView(this).apply {
            text = L.t("דלג", "Skip"); textSize = 15f; setTextColor(ui.color(R.color.ink_soft))
            setPadding(ui.dp(8), ui.dp(8), ui.dp(8), ui.dp(8))
            setOnClickListener { finishIntro() }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.END }
        })
        root.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        root.addView(TextView(this).apply { text = p.icon; textSize = 64f; gravity = Gravity.CENTER })
        root.addView(ui.text(p.title, 24f, bold = true, top = 24).apply { gravity = Gravity.CENTER })
        root.addView(ui.text(p.body, 16f, R.color.ink_soft, top = 14).apply { gravity = Gravity.CENTER; setLineSpacing(ui.dp(3).toFloat(), 1f) })
        root.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        // Dots: which screen of three.
        root.addView(TextView(this).apply {
            text = pages.indices.joinToString("  ") { if (it == at) "●" else "○" }
            textSize = 14f; gravity = Gravity.CENTER; setTextColor(ui.color(R.color.accent))
        })
        val last = at == pages.size - 1
        root.addView(ui.button(if (last) L.t("מתחילים", "Let's start") else L.t("הבא", "Next"), primary = true, top = 18) {
            if (last) finishIntro() else { at++; render() }
        }.apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ui.dp(18) } })
    }

    private fun finishIntro() {
        prefs(this).edit().putBoolean("intro_seen", true).apply()
        if (!Account.signedIn(this) && !Account.skipped(this)) WelcomeActivity.open(this)
        finish()
    }

    @Deprecated("Back goes to the previous screen of the intro")
    override fun onBackPressed() {
        if (at > 0) { at--; render() } else { @Suppress("DEPRECATION") super.onBackPressed() }
    }
}

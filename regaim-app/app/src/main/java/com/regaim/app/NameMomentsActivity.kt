package com.regaim.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * "Who's in these?" one moment after another: the moments nobody was recognized in (so they have no words, see
 * [NamedMoments]). The parent ticks who's there and goes on; the face recognition learns from it on the phone, and
 * the moment is described again with the names. "Nobody from the family" keeps a moment out of this list.
 */
class NameMomentsActivity : AppCompatActivity() {
    companion object {
        private const val SKIPPED = "name_moments_skipped"
        fun open(ctx: Context) = ctx.startActivity(Intent(ctx, NameMomentsActivity::class.java))

        /** The moments waiting for a name (faces already looked for, not set aside), newest first. */
        fun waiting(ctx: Context): List<Memory> {
            val skipped = skipped(ctx)
            return MemoryStore.kept(ctx).filter { !NamedMoments.named(it) && it.facesScanned && it.id !in skipped }.sortedByDescending { it.time }
        }

        private fun skipped(ctx: Context): Set<String> =
            Settings.prefs(ctx).getString(SKIPPED, "").orEmpty().split(',').filter { it.isNotBlank() }.toSet()

        private fun skip(ctx: Context, id: String) =
            Settings.prefs(ctx).edit().putString(SKIPPED, (skipped(ctx).toList().takeLast(2000) + id).joinToString(",")).apply()
    }

    private lateinit var ui: Ui
    private lateinit var content: LinearLayout
    private var list: List<Memory> = emptyList()
    private var at = 0
    private var named = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_screen)
        findViewById<View>(R.id.btnClose).setOnClickListener { finish() }
        findViewById<TextView>(R.id.screenTitle).text = L.t("מי ברגעים האלה?", "Who's in these moments?")
        content = findViewById(R.id.screenContent)
        ui = Ui(this)
        list = waiting(this)
        render()
    }

    private fun render() {
        content.removeAllViews()
        val m = list.getOrNull(at)
        if (m == null) {
            content.addView(ui.text(if (named > 0) L.t("סימנתם $named רגעים ✓", "You named $named moments ✓") else L.t("אין רגעים שמחכים לשם", "No moments are waiting for a name"), 20f, bold = true, top = 24))
            content.addView(ui.hint(if (named > 0) L.t("הם יקבלו תיאור עם השמות ברקע, וזיהוי הפנים למד מהם.", "They're described with the names in the background, and face recognition learned from them.")
                else L.t("כל הרגעים כבר מזוהים.", "Every moment already has someone recognized."), top = 6))
            content.addView(ui.button(L.t("סיום", "Done"), primary = true, top = 18) { finish() })
            return
        }
        content.addView(ui.hint(L.t("${at + 1} מתוך ${list.size} · ${Fmt.day(m.time)}", "${at + 1} of ${list.size} · ${Fmt.day(m.time)}"), top = 4))
        val image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            background = DS.rounded(DS.cardAlt(this@NameMomentsActivity), ui.dp(16).toFloat())
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(300)).apply { topMargin = ui.dp(8) }
            contentDescription = Fmt.title(m)
            setOnClickListener { StoryViewerActivity.open(this@NameMomentsActivity, m.id) }
        }
        Covers.load(image, m)
        content.addView(image)
        content.addView(ui.text(L.t("מי ברגע הזה?", "Who's in this moment?"), 16f, bold = true, top = 14))
        val people = Profile.members(this).map { it.name }.filter { it.isNotBlank() }.distinct()
        val checks = people.map { n -> CheckBox(this).apply { text = n; textSize = 16f; content.addView(this) } }
        if (people.isEmpty()) content.addView(ui.hint(L.t("קודם מוסיפים את בני המשפחה בפרופיל.", "First add the family in the profile."), top = 4))
        content.addView(ui.button(L.t("שמירה והבא", "Save and next"), primary = true, top = 14) {
            val names = checks.filter { it.isChecked }.map { it.text.toString() }
            if (names.isEmpty()) { android.widget.Toast.makeText(this, L.t("סמנו לפחות אחד, או \"דילוג\"", "Tick at least one, or \"Skip\""), android.widget.Toast.LENGTH_SHORT).show(); return@button }
            save(m, names)
            named++; at++; render()
        })
        val row = ui.horizontal(top = 6)
        row.addView(ui.action(L.t("דילוג", "Skip")) { at++; render() })
        row.addView(ui.action(L.t("אין כאן אף אחד מהמשפחה", "Nobody from the family"), R.color.ink_soft) { skip(this, m.id); at++; render() })
        content.addView(row)
    }

    /** The names onto the moment (as if marked on its own screen); the faces learn from it, then it's described. */
    private fun save(m: Memory, names: List<String>) {
        val fresh = MemoryStore.get(this, m.id) ?: m
        val faces = fresh.people.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (!MemoryStore.saveFromUi(this) { MemoryStore.update(this, fresh.copy(extraPeople = names.filter { it !in faces }.joinToString(", "))) }) return
        val app = applicationContext
        Thread {
            try { PeopleStore.learnFromMoment(app, m.id, emptyList(), names.filter { it !in faces }) } catch (_: Exception) { }
            try { MemoryStore.analyzePending(app) } catch (_: Exception) { }
        }.start()
    }
}

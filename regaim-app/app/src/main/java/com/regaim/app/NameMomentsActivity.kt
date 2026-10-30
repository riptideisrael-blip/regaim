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
    private var list: MutableList<Memory> = mutableListOf()
    /** Every kept moment (for the names recognized a few minutes before or after). */
    private var all: List<Memory> = emptyList()
    private var at = 0
    private var named = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_screen)
        findViewById<View>(R.id.btnClose).setOnClickListener { finish() }
        findViewById<TextView>(R.id.screenTitle).text = L.t("מי ברגעים האלה?", "Who's in these moments?")
        content = findViewById(R.id.screenContent)
        ui = Ui(this)
        all = MemoryStore.kept(this)
        list = waiting(this).toMutableList()
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
        val counter = ui.hint(L.t("${at + 1} מתוך ${list.size} · ${Fmt.day(m.time)}", "${at + 1} of ${list.size} · ${Fmt.day(m.time)}"), top = 4)
        content.addView(counter)
        // After "next", a screen reader starts again at the new moment (not at the top of the screen).
        if (at > 0) counter.post { counter.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null) }
        val image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            background = DS.rounded(DS.cardAlt(this@NameMomentsActivity), ui.dp(16).toFloat())
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(300)).apply { topMargin = ui.dp(8) }
            contentDescription = Fmt.title(m) + L.t(", לחצו לצפייה", ", tap to watch")
            setOnClickListener { StoryViewerActivity.open(this@NameMomentsActivity, m.id) }
        }
        Covers.load(image, m)
        content.addView(image)
        content.addView(ui.text(L.t("מי ברגע הזה?", "Who's in this moment?"), 16f, bold = true, top = 14).apply { androidx.core.view.ViewCompat.setAccessibilityHeading(this, true) })
        val people = Profile.members(this).map { it.name }.filter { it.isNotBlank() }.distinct()
        // Who was recognized a few minutes before or after (a back or legs here, a face there): ticked as a guess.
        val guess = NamedMoments.suggest(m, all).filter { it in people }
        if (guess.isNotEmpty()) content.addView(ui.hint(L.t(
            "סימנו לפי מי שזוהה ברגעים מאותן דקות (${guess.joinToString(", ")}). בדקו ותקנו אם צריך.",
            "Ticked from who was recognized in moments from the same minutes (${guess.joinToString(", ")}). Check and fix if needed."), top = 2))
        val checks = people.map { n -> CheckBox(this).apply { text = n; textSize = 16f; minHeight = ui.dp(48); isChecked = n in guess; content.addView(this) } }
        if (people.isEmpty()) {
            content.addView(ui.hint(L.t("קודם מוסיפים את בני המשפחה בפרופיל.", "First add the family in the profile."), top = 4))
            content.addView(ui.button(L.t("להוספת בני המשפחה", "Add the family"), primary = true, top = 10) { startActivity(Intent(this, ProfileActivity::class.java)) })
        }
        // Enabled once someone is ticked (no message that disappears).
        val save = ui.button(L.t("שמירה והבא", "Save and next"), primary = true, top = 14) {
            val names = checks.filter { it.isChecked }.map { it.text.toString() }
            if (names.isEmpty()) return@button
            save(m, names, learn = true)
            named++; list.removeAt(at); render()
        }
        save.isEnabled = checks.any { it.isChecked }
        // The same few minutes, waiting too: one answer for all of them (faces learn only from the one seen here).
        val sequence = NamedMoments.sameSequence(m, list)
        val saveAll = if (sequence.isEmpty()) null else ui.button(
            L.t("שמירה לכל ${sequence.size + 1} הרגעים מאותן דקות", "Save for all ${sequence.size + 1} moments from these minutes"), top = 8) {
            val names = checks.filter { it.isChecked }.map { it.text.toString() }
            if (names.isEmpty()) return@button
            // The others first (no learning, no description yet), then this one: its description run takes them all.
            sequence.forEach { save(it, names, learn = false, describe = false) }
            save(m, names, learn = true)
            named += sequence.size + 1
            val gone = (sequence.map { it.id } + m.id).toSet()
            list.removeAll { it.id in gone }
            at = at.coerceAtMost(list.size)
            render()
        }?.apply { isEnabled = checks.any { it.isChecked } }
        checks.forEach { c -> c.setOnCheckedChangeListener { _, _ ->
            val any = checks.any { it.isChecked }
            save.isEnabled = any; saveAll?.isEnabled = any
        } }
        content.addView(save)
        saveAll?.let { content.addView(it) }
        val row = ui.horizontal(top = 6)
        row.addView(ui.action(L.t("דילוג", "Skip")) { at++; render() })
        row.addView(ui.action(L.t("אין כאן אף אחד מהמשפחה", "Nobody from the family"), R.color.ink_soft) { skip(this, m.id); list.removeAt(at); render() })
        content.addView(row)
    }

    /** The names onto the moment (as if marked on its own screen); the faces learn from it, then it's described. */
    private fun save(m: Memory, names: List<String>, learn: Boolean, describe: Boolean = true) {
        val fresh = MemoryStore.get(this, m.id) ?: m
        val faces = fresh.people.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (!MemoryStore.saveFromUi(this) { MemoryStore.update(this, fresh.copy(extraPeople = names.filter { it !in faces }.joinToString(", "))) }) return
        if (!learn && !describe) return
        val app = applicationContext
        Thread {
            // Faces learn only with the parent's yes to face recognition.
            if (learn && PeopleStore.faceConsent(app)) try { PeopleStore.learnFromMoment(app, m.id, emptyList(), names.filter { it !in faces }) } catch (_: Exception) { }
            if (describe) try { MemoryStore.analyzePending(app) } catch (_: Exception) { }
        }.start()
    }
}

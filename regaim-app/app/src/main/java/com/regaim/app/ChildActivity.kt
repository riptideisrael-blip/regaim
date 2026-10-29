package com.regaim.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * "הדף של אורי": one child's page. Everything the app already knows about the child, in one calm place: the picture,
 * age and the coming birthday, the latest moments, milestones, and the ways to the timeline, the personal book and
 * Pitzki. Nothing new is stored here (phase 1).
 */
class ChildActivity : AppCompatActivity() {
    companion object {
        private const val EXTRA_ID = "member_id"
        fun open(ctx: Context, memberId: String) =
            ctx.startActivity(Intent(ctx, ChildActivity::class.java).putExtra(EXTRA_ID, memberId))

        /** The child's page for a name, when that name is a child in the family profile; false otherwise. */
        fun openByName(ctx: Context, name: String): Boolean {
            val kid = Profile.members(ctx).firstOrNull { it.isChild && it.name.trim() == name.trim() } ?: return false
            open(ctx, kid.id)
            return true
        }

        private const val STRIP = 12
        private const val MILESTONES = 4
    }

    private lateinit var ui: Ui
    private lateinit var mv: MemoryViews
    private lateinit var content: LinearLayout
    private var memberId = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_screen)
        findViewById<View>(R.id.btnClose).setOnClickListener { finish() }
        content = findViewById(R.id.screenContent)
        ui = Ui(this)
        mv = MemoryViews(this)
        memberId = intent.getStringExtra(EXTRA_ID).orEmpty()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        content.removeAllViews()
        val kids = Profile.members(this).filter { it.isChild }
        val kid = kids.firstOrNull { it.id == memberId } ?: kids.firstOrNull()
        val title = findViewById<TextView>(R.id.screenTitle)
        if (kid == null) {
            title.text = L.t("הילדים", "The kids")
            content.addView(ui.hint(L.t("עוד לא הוספתם ילדים בפרופיל המשפחה.", "You haven't added kids to the family profile yet."), top = 16))
            content.addView(ui.action(L.t("הוספת ילד/ה ›", "Add a child ›")) { startActivity(Intent(this, ProfileActivity::class.java)) })
            return
        }
        memberId = kid.id
        title.text = L.t("הדף של ${kid.name}", "${kid.name}'s page")

        // More than one child: switch between their pages here.
        if (kids.size > 1) {
            val names = kids.map { it.name }
            content.addView(ui.chips(names, kids.indexOf(kid), top = 10) { i -> memberId = kids[i].id; render() })
        }

        val all = try { MemoryStore.kept(this) } catch (_: Exception) { emptyList() }
        val mine = ChildPage.mine(all, kid.name) { Fmt.names(it) }

        // The child: picture, name, age and the coming birthday, and how many moments.
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, ui.dp(16), 0, ui.dp(4))
        }
        head.addView(mv.avatar(kid.name, 96))
        head.addView(ui.text(kid.name, 24f, bold = true, top = 10).apply {
            gravity = Gravity.CENTER
            androidx.core.view.ViewCompat.setAccessibilityHeading(this, true)
        })
        val sub = ChildPage.subtitle(Profile.ageText(kid.birth), ChildPage.birthdayInDays(kid.birth))
        if (sub.isNotEmpty()) head.addView(ui.text(sub, 15f, R.color.ink_soft, top = 2).apply { gravity = Gravity.CENTER })
        head.addView(ui.hint(ChildPage.counts(mine.size, mine.count { it.favorite }), top = 4).apply { gravity = Gravity.CENTER })
        content.addView(head)

        // What a parent does from here most: Pitzki for this child (already chosen), the book, the timeline.
        val acts = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = ui.dp(12) } }
        listOf(
            L.t("פיצקי", "Pitzki") to { PitzkiActivity.openFor(this, kid.id) },
            L.t("ספר", "Book") to { BookActivity.open(this) },
            L.t("ציר זמן", "Timeline") to { TimelineActivity.open(this, kid.name) },
        ).forEachIndexed { n, (label, go) ->
            acts.addView(ui.button(label, primary = n == 0, top = 0) { go() }.apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { if (n < 2) marginEnd = ui.dp(8) }
            })
        }
        content.addView(acts)

        // The latest moments.
        content.addView(ui.section(L.t("רגעים אחרונים", "Latest moments")))
        if (mine.isEmpty()) {
            val card = ui.card(top = 8)
            card.addView(ui.hint(L.t("כדי למצוא את ${kid.name} ברגעים, תנו את השם הזה לפנים במסך \"מי זה?\".", "To find ${kid.name} in your moments, give their face this name on the \"Who's this?\" screen."), top = 0))
            card.addView(ui.action(L.t("מי זה? ›", "Who's this? ›")) { PeopleActivity.open(this) })
            content.addView(card)
        } else {
            val strip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (m in mine.take(STRIP)) strip.addView(mv.mini(m, 104, 140) { MomentActivity.open(this, m.id) })
            content.addView(ui.scrollRow(strip, top = 8))
            content.addView(ui.action(L.t("כל הרגעים של ${kid.name} (${mine.size}) ›", "All of ${kid.name}'s moments (${mine.size}) ›")) {
                startActivity(Intent(this, MemoriesActivity::class.java).putExtra(MemoriesActivity.EXTRA_PERSON, kid.name)
                    .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            })
        }

        // Milestones: the newest few.
        val milestones = mine.filter { it.milestone.isNotBlank() }
        content.addView(ui.section(L.t("אבני דרך", "Milestones")))
        val ms = ui.card(top = 8)
        if (milestones.isEmpty()) {
            ms.addView(ui.hint(L.t("כשרגע יסומן כאבן דרך (\"צעד ראשון\", \"מילה ראשונה\"), הוא יופיע כאן.", "When a moment is marked as a milestone (\"first steps\", \"first word\"), it shows up here."), top = 0))
        } else {
            for ((i, m) in milestones.take(MILESTONES).withIndex()) {
                ms.addView(ui.row(m.milestone, Fmt.day(m.time), top = if (i == 0) 0 else 2) { MomentActivity.open(this, m.id) })
            }
            if (milestones.size > MILESTONES) ms.addView(ui.action(L.t("כל אבני הדרך (${milestones.size}) ›", "All milestones (${milestones.size}) ›")) {
                startActivity(Intent(this, MilestonesActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            })
        }
        content.addView(ms)

        // The child's part of the family file (receipts, documents, vouchers).
        val file = if (FamilyFile.consented(this)) FamilyFile.all(this).filter { it.child == kid.id } else emptyList()
        content.addView(ui.section(L.t("התיק של ${kid.name}", "${kid.name}'s file")))
        val fc = ui.card(top = 8)
        val total = FamilyItems.receiptTotal(file, java.time.LocalDate.now().year, kid.id)
        val line = when {
            file.isEmpty() -> L.t("קבלות, מסמכים ושוברים של ${kid.name}. שתפו לכאן מכל אפליקציה.", "${kid.name}'s receipts, documents and vouchers. Share them here from any app.")
            total > 0 -> L.t("${file.size} פריטים · קבלות השנה: ${FamilyItems.money(total)}", "${file.size} items · receipts this year: ${FamilyItems.money(total)}")
            else -> L.t("${file.size} פריטים", "${file.size} items")
        }
        fc.addView(ui.row(line, "", top = 0) { FamilyFileActivity.open(this, kid.id) })
        content.addView(fc)

        // The child's savings: how much there is today (estimated from the last report and the deposits since).
        val funds = if (FamilyFile.consented(this)) FamilyFile.savings(this).filter { it.child == kid.id } else emptyList()
        content.addView(ui.section(L.t("החיסכון של ${kid.name}", "${kid.name}'s savings")))
        val sc = ui.card(top = 8)
        val today = java.time.LocalDate.now().toString()
        val saved = Savings.childTotal(funds, kid.id, today) { Savings.estimate(it, today, GemelNet.cached(this, it.gemelId), CryptoPrices.cached(this)) }
        val monthly = funds.sumOf { it.perMonth }
        sc.addView(ui.row(
            if (funds.isEmpty()) L.t("מוסיפים קופה או מעלים דוח רבעוני", "Add a fund or upload a quarterly report")
            else L.t("${FamilyItems.money(saved)} היום (הערכה)", "${FamilyItems.money(saved)} today (estimate)"),
            if (monthly > 0) L.t("+${FamilyItems.money(monthly)} בחודש", "+${FamilyItems.money(monthly)} a month") else "", top = 0,
        ) { SavingsActivity.open(this, kid.id) })
        content.addView(sc)

        // More about the child.
        content.addView(ui.section(L.t("עוד", "More")))
        val more = ui.card(top = 8)
        PeopleStore.people(this).firstOrNull { it.name == kid.name }?.let { person ->
            more.addView(ui.row(L.t("תיקון הפנים שזוהו", "Fix recognized faces"), "", top = 0) { PersonFacesActivity.open(this, person.id) })
        }
        more.addView(ui.row(L.t("עריכת הפרטים", "Edit details"), "") { startActivity(Intent(this, ProfileActivity::class.java)) })
        content.addView(more)
        content.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(1, ui.dp(24)) })
    }
}

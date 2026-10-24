package com.regaim.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.Executors

/**
 * "מה Moments למד": every insight with the moments it was learned from, and your corrections:
 * fix it, delete it (and it won't come back), add your own rule, or exclude a moment from learning.
 */
class LearnedActivity : AppCompatActivity() {

    companion object {
        fun open(ctx: Context) = ctx.startActivity(Intent(ctx, LearnedActivity::class.java))
    }

    private lateinit var ui: Ui
    private lateinit var content: LinearLayout
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var learning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_screen)
        ui = Ui(this)
        content = findViewById(R.id.screenContent)
        findViewById<TextView>(R.id.screenTitle).text = L.t("מה Moments למד", "What Moments learned")
        findViewById<View>(R.id.btnClose).setOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun render() {
        val y = findViewById<View>(R.id.screenScroll).scrollY
        content.removeAllViews()
        val all = MemoryStore.kept(this)
        val byId = all.associateBy { it.id }
        val items = Learning.items(this)
        basisCard(all, items.isEmpty())
        insightList(L.t("✓ מה חשוב לך לשמור", "✓ What matters to you"), items.filter { it.like }, byId, true)
        insightList(L.t("✗ מה פחות מעניין אותך", "✗ What you care less about"), items.filter { !it.like }, byId, false)
        otherKnowledge(all)
        content.post { findViewById<View>(R.id.screenScroll).scrollTo(0, y) }
    }

    // ---------- what it learned from ----------

    private fun basisCard(all: List<Memory>, empty: Boolean) {
        val card = ui.card(top = 8)
        val chosen = all.count { it.chosen && !it.noLearn }
        val fav = all.count { it.favorite && !it.noLearn }
        val ms = all.count { it.milestone.isNotEmpty() && !it.noLearn }
        val deleted = Learning.rejected(this).size
        card.addView(ui.text(L.t("על מה הלמידה מבוססת", "What it learned from"), 15f, bold = true))
        card.addView(ui.text(L.t("♥ $chosen רגעים ששמרת בעצמך\n★ $fav מועדפים · $ms אבני דרך\n🗑 $deleted רגעים שנשמרו לבד ומחקת", "♥ $chosen moments you saved yourself\n★ $fav favorites · $ms milestones\n🗑 $deleted auto-saved moments you deleted"), 14f, top = 6))
        val tuned = CaptureVotes.summary(this)
        if (tuned.isNotEmpty()) {
            card.addView(ui.text(L.t("מה השתנה בצילום האוטומטי", "What changed in automatic capture"), 14f, bold = true, top = 12))
            card.addView(ui.hint(tuned.joinToString("\n"), top = 4))
        } else card.addView(ui.hint(L.t("אחרי ${CaptureVotes.Rules.MIN_VOTES} אגודלים על אותו סוג (צחוק, חיוך, צעקה...) בתור האישור, Moments יתחיל לכוון לפיהם את הצילום האוטומטי.", "After ${CaptureVotes.Rules.MIN_VOTES} thumbs on the same kind (laugh, smile, shout…) in the approval queue, Moments starts tuning automatic capture by them."), top = 8))
        val updated = Learning.updatedAt(this)
        if (updated > 0) card.addView(ui.hint(L.t("עודכן לאחרונה: ${ClaudeClient.describeTime(updated)}", "Last updated: ${ClaudeClient.describeTime(updated)}"), top = 8))
        card.addView(ui.hint(L.t("Moments משתמש בזה כשה-AI מתאר כל רגע, כותב \"למה זה רגע שלך\" ונותן לו ציון. כל דבר שתתקנו כאן נשמר כמו שכתבתם, וה-AI לא ישנה אותו.", "Moments uses this when the AI describes each moment, writes \"why this is your moment\" and scores it. Anything you fix here stays exactly as you wrote it, and the AI won't change it."), top = 6))
        if (empty) card.addView(ui.hint(L.t("עוד אין מספיק דוגמאות. שמרו כמה רגעים בעצמכם (♥ או ״שמור רגע״), סמנו מועדפים או מחקו רגעים לא מעניינים, ואז לחצו ״למד עכשיו״.", "Not enough examples yet. Save a few moments yourself (♥ or \"save moment\"), mark favorites or delete ones you don't care about, then tap \"Learn now\"."), top = 6))
        card.addView(ui.button(if (learning) L.t("לומד...", "Learning…") else L.t("למד עכשיו מהרגעים שלי", "Learn now from my moments"), primary = true) {
            if (learning) return@button
            if (!ClaudeClient.hasKey(this)) {
                // The family's way in is the consent screen (keys are only for the admin): after "I agree", tap again.
                if (AiGate.consented(this) && !Account.signedIn(this)) { WelcomeActivity.open(this); return@button }
                AiGate.askConsent(this) { render() }
                return@button
            }
            learning = true
            render()
            worker.execute {
                val ok = Learning.refresh(applicationContext, force = true)
                learning = false
                runOnUiThread {
                    if (isFinishing) return@runOnUiThread
                    if (!ok) toast(L.t("לא הצלחתי ללמוד. צריך לפחות רגע אחד ששמרת, אהבת או מחקת אחרי שתואר.", "Couldn't learn yet. You need at least one described moment that you saved, loved or deleted."))
                    render()
                }
            }
        })
        content.addView(card)
    }

    // ---------- the insights ----------

    private fun insightList(title: String, list: List<Insight>, byId: Map<String, Memory>, like: Boolean) {
        content.addView(ui.section(title))
        if (list.isEmpty()) content.addView(ui.hint(if (like) L.t("עוד לא נלמד כלום כאן.", "Nothing learned here yet.") else L.t("עוד לא ידוע מה לא מעניין אותך.", "Nothing here yet.")))
        for (i in list) content.addView(insightCard(i, byId))
        content.addView(ui.button(if (like) L.t("+ הוסף משהו שחשוב לי", "+ Add something that matters to me") else L.t("+ הוסף משהו שלא מעניין אותי", "+ Add something I don't care about")) { edit(null, like) })
    }

    private fun insightCard(i: Insight, byId: Map<String, Memory>): View {
        val card = ui.card()
        card.addView(ui.text(i.text, 16f, bold = true))
        val moments = i.evidence.mapNotNull { byId[it] }
        val source = when {
            i.byUser && moments.isEmpty() -> L.t("✎ כתבת את זה בעצמך", "✎ You wrote this yourself")
            i.byUser -> L.t("✎ תיקנת בעצמך · במקור נלמד מ-${moments.size} רגעים", "✎ You edited this · originally learned from ${moments.size} moments")
            moments.isEmpty() && i.deleted.isEmpty() -> L.t("נלמד בגרסה קודמת, בלי פירוט רגעים. לחץ ״למד עכשיו״ לעדכון.", "Learned in an earlier version, without moment details. Tap \"Learn now\" to update.")
            else -> L.t("נלמד מ-", "Learned from ") + listOfNotNull(
                if (moments.isNotEmpty()) L.t("${moments.size} רגעים ששמרת", "${moments.size} moments you saved") else null,
                if (i.deleted.isNotEmpty()) L.t("${i.deleted.size} שמחקת", "${i.deleted.size} you deleted") else null,
            ).joinToString(L.t(" ו-", " and "))
        }
        card.addView(ui.hint(source))
        if (moments.isNotEmpty()) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (m in moments) {
                val b = ui.bubble(m.thumb, 52)
                b.setOnClickListener { MomentActivity.open(this, m.id) }
                b.setOnLongClickListener { excludeMoment(m); true }
                row.addView(b)
            }
            card.addView(ui.scrollRow(row))
            card.addView(ui.hint(L.t("לחץ על רגע כדי לראות אותו · לחיצה ארוכה: לא ללמוד ממנו", "Tap a moment to view it · Long-press to stop learning from it"), top = 2))
        }
        for (d in i.deleted) {
            val r = ui.horizontal(top = 6)
            r.addView(ui.text(L.t("🗑 מחקת: $d", "🗑 You deleted: $d"), 13f, R.color.ink_soft).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                maxLines = 2
            })
            r.addView(ui.action(L.t("לא בגלל זה", "Not why"), R.color.ink_soft) {
                Learning.forgetRejected(this, d)
                render()
            })
            card.addView(r)
        }
        val actions = ui.horizontal(top = 6)
        actions.addView(ui.action(L.t("✎ תקן", "✎ Edit")) { edit(i, i.like) })
        actions.addView(ui.action(L.t("✕ זה לא נכון", "✕ That's wrong"), R.color.danger) {
            Learning.remove(this, i.id)
            toast(if (i.byUser) L.t("נמחק", "Deleted") else L.t("נמחק, ו-Moments לא ילמד את זה שוב", "Deleted. Moments won't learn this again"))
            render()
        })
        card.addView(actions)
        return card
    }

    private fun edit(item: Insight?, like: Boolean) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(20), ui.dp(8), ui.dp(20), 0)
        }
        val field = ui.field(if (like) L.t("מה הופך רגע למשמעותי בשבילך?", "What makes a moment meaningful to you?") else L.t("איזה סוג רגעים פחות חשוב לך לשמור?", "What kind of moments matter less to you?"), item?.text ?: "", multiline = true)
        box.addView(field)
        val yes = RadioButton(this).apply { id = View.generateViewId(); text = L.t("✓ חשוב לי לשמור רגעים כאלה", "✓ I want to keep moments like this") }
        val no = RadioButton(this).apply { id = View.generateViewId(); text = L.t("✗ פחות מעניין אותי", "✗ Less interesting to me") }
        val group = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            addView(yes)
            addView(no)
            check(if (like) yes.id else no.id)
            setPadding(0, ui.dp(8), 0, 0)
        }
        box.addView(group)
        AlertDialog.Builder(this)
            .setTitle(if (item == null) L.t("כלל משלך", "Your own rule") else L.t("תיקון", "Edit"))
            .setView(box)
            .setPositiveButton(L.t("שמור", "Save")) { _, _ ->
                val text = field.text.toString()
                val isLike = group.checkedRadioButtonId == yes.id
                if (item == null) Learning.add(this, text, isLike) else Learning.edit(this, item.id, text, isLike)
                render()
            }
            .setNegativeButton(L.t("ביטול", "Cancel"), null)
            .show()
    }

    private fun excludeMoment(m: Memory) {
        AlertDialog.Builder(this)
            .setTitle(L.t("לא ללמוד מהרגע הזה?", "Stop learning from this moment?"))
            .setMessage(L.t("\"${m.displayTitle}\" יישאר בזיכרונות, אבל Moments לא ילמד ממנו מה חשוב לך. בלמידה הבאה התובנות יתעדכנו.", "\"${m.displayTitle}\" stays in your memories, but Moments won't learn from it. Insights will update next time it learns."))
            .setPositiveButton(L.t("אל תלמד ממנו", "Don't learn from it")) { _, _ ->
                MemoryStore.get(this, m.id)?.let { MemoryStore.update(this, it.copy(noLearn = true)) }
                render()
            }
            .setNegativeButton(L.t("ביטול", "Cancel"), null)
            .show()
    }

    // ---------- other things Moments knows ----------

    private fun otherKnowledge(all: List<Memory>) {
        content.addView(ui.section(L.t("עוד דברים ש-Moments יודע", "What else Moments knows")))
        val card = ui.card()

        val family = Profile.describe(this)
        card.addView(ui.row(L.t("המשפחה", "Family"), if (family.isEmpty()) L.t("הוסף", "Add") else L.t("ערוך", "Edit"), top = 0) { SettingsActivity.open(this) })
        card.addView(ui.hint(family.ifEmpty { L.t("עוד לא מילאת פרופיל. עם שמות וגילאים ה-AI מזהה אבני דרך מתאימות.", "No profile yet. With names and ages, the AI can spot the right milestones.") }, top = 0))

        card.addView(ui.divider())
        val people = PeopleStore.people(this)
        card.addView(ui.row(L.t("אנשים שמזוהים בפנים", "People recognized by face"), if (people.isEmpty()) L.t("הוסף", "Add") else "${people.size}") { PeopleActivity.open(this) })
        card.addView(ui.hint(
            if (people.isEmpty()) L.t("תן שמות לפנים, ו-Moments יכתוב מי היה בכל רגע.", "Name faces, and Moments will note who was in each moment.")
            else people.joinToString(" · ") { L.t("${it.name}: ${PeopleStore.countFor(this, it.name)} רגעים", "${it.name}: ${PeopleStore.countFor(this, it.name)} moments") } +
                L.t("\nזיהוי שגוי? במסך אנשים אפשר לשנות שם או לשכוח אדם.", "\nWrong match? Rename or forget someone in People."),
            top = 0,
        ))

        card.addView(ui.divider())
        card.addView(ui.text(L.t("מה תופס את הרגעים שלך", "What captures your moments"), 15f, top = 4))
        val kinds = linkedMapOf<String, List<Memory>>()
        kinds[L.t("♥ שמרת ידנית", "♥ Saved by you")] = all.filter { it.label == "manual" }
        kinds[L.t("🗣 מילים", "🗣 Words")] = all.filter { it.label.startsWith("״") }
        kinds[L.t("😄 חיוך / ריצה אליך", "😄 Smile / running to you")] = all.filter { it.label in Labels.VISUAL }
        kinds[L.t("🔊 צחוק וקולות", "🔊 Laughter and sounds")] = all.filter { it.label != "manual" && !it.label.startsWith("״") && it.label !in Labels.VISUAL }
        val lines = kinds.filter { it.value.isNotEmpty() }.map { (k, v) ->
            val fav = v.count { it.favorite }
            "$k: ${v.size}" + if (fav > 0) L.t(" (♥ $fav אהבת)", " (♥ $fav loved)") else ""
        }
        card.addView(ui.hint(lines.joinToString("\n").ifEmpty { L.t("עוד אין רגעים.", "No moments yet.") }, top = 2))
        val words = all.filter { it.label.startsWith("״") }.groupingBy { it.label }.eachCount().entries.sortedByDescending { it.value }.take(4)
        if (words.isNotEmpty()) card.addView(ui.hint(L.t("המילים שהכי תופסות: ", "Top words: ") + words.joinToString(", ") { "${it.key} ${it.value}" }, top = 4))
        card.addView(ui.row(L.t("לשנות מה שומר רגע", "Change what saves a moment"), L.t("הגדרות", "Settings")) { SettingsActivity.open(this) })

        val cats = all.filter { it.category.isNotEmpty() }.groupingBy { it.category }.eachCount().entries.sortedByDescending { it.value }.take(5)
        if (cats.isNotEmpty()) {
            card.addView(ui.divider())
            card.addView(ui.text(L.t("סוגי הרגעים הכי נפוצים", "Most common kinds of moments"), 15f, top = 4))
            card.addView(ui.hint(cats.joinToString(" · ") { "${L.category(it.key)} ${it.value}" }, top = 2))
            card.addView(ui.hint(L.t("קטגוריה שגויה? פתח את הרגע ולחץ ״תקן״.", "Wrong category? Open the moment and tap \"Edit\"."), top = 2))
        }

        val excluded = all.filter { it.noLearn }
        if (excluded.isNotEmpty()) {
            card.addView(ui.divider())
            card.addView(ui.row(L.t("רגעים שביקשת לא ללמוד מהם", "Moments excluded from learning"), "${excluded.size}"))
            card.addView(ui.action(L.t("החזר את כולם ללמידה", "Include them all again")) {
                for (m in excluded) MemoryStore.update(this, m.copy(noLearn = false))
                render()
            })
        }

        val dismissed = Learning.dismissed(this)
        if (dismissed.isNotEmpty()) {
            card.addView(ui.divider())
            card.addView(ui.row(L.t("תובנות שמחקת ולא יחזרו", "Deleted insights that won't return"), "${dismissed.size}"))
            card.addView(ui.hint(dismissed.takeLast(5).joinToString("\n") { "✕ $it" }, top = 0))
            card.addView(ui.action(L.t("אפס, ותן ל-Moments ללמוד אותן שוב", "Reset and let Moments learn them again")) {
                Learning.clearDismissed(this)
                render()
            })
        }
        content.addView(card)
    }
}

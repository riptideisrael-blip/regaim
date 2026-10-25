package com.regaim.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The detection lab: a test set of real family videos, each labelled with the truth, run through the same
 * moment finder (nothing is saved to memories). Shows recall, precision and false-alarm rates instead of a feeling.
 */
class DetectionLabActivity : AppCompatActivity() {
    companion object {
        fun open(ctx: Context) = ctx.startActivity(Intent(ctx, DetectionLabActivity::class.java))

        /** The 14 situations worth covering; the last one is the "nothing special" control. */
        val CASES = listOf(
            "ילד צוחק בלי דיבור" to "Child laughing, no talking",
            "ילד צוחק בזמן משחק" to "Child laughing while playing",
            "אמא מדגדגת ילד" to "Mom tickling a child",
            "אבא משחק עם ילד" to "Dad playing with a child",
            "חיבוק" to "Hug",
            "חיוך בלבד" to "Smile only",
            "צחוק שנשמע אבל קשה לראות" to "Laugh heard but hard to see",
            "חיוך שרואים אבל כמעט לא שומעים" to "Smile seen but barely heard",
            "מוזיקה ברקע" to "Music in the background",
            "רעש רקע" to "Background noise",
            "סרטון ללא קול" to "Video with no sound",
            "כמה אנשים בפריים" to "Several people in frame",
            "ילד שרץ וצוחק" to "Child running and laughing",
            "סרטון בלי רגע מיוחד" to "Video with no real moment",
        )
    }

    private lateinit var ui: Ui
    private lateinit var content: LinearLayout
    private val worker = Executors.newSingleThreadExecutor()
    private val cancelled = AtomicBoolean(false)
    private var running: String? = null
    private var bar: ProgressBar? = null
    private var pendingCase = 0
    private var pendingHas = true
    private var pendingAt = -1L

    private val picker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) run(uri)
    }

    private fun file() = File(filesDir, "detection_lab.json")
    private fun load(): JSONArray = try { JSONArray(file().readText()) } catch (_: Exception) { JSONArray() }
    private fun store(a: JSONArray) = try { file().writeText(a.toString()) } catch (_: Exception) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_screen)
        findViewById<TextView>(R.id.screenTitle).text = L.t("מעבדת זיהוי", "Detection lab")
        findViewById<View>(R.id.btnClose).setOnClickListener { finish() }
        content = findViewById(R.id.screenContent)
        ui = Ui(this)
        render()
    }

    override fun onDestroy() {
        cancelled.set(true)
        worker.shutdown()
        super.onDestroy()
    }

    private fun cases(): List<Pair<JSONObject, LabScore.Case>> {
        val a = load()
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val found = o.optJSONArray("found") ?: JSONArray()
            val list = (0 until found.length()).mapNotNull { j ->
                val f = found.optJSONObject(j) ?: return@mapNotNull null
                Candidate(
                    f.optLong("start"), f.optLong("end"), f.optLong("peak"), 0, 0, f.optDouble("confidence").toFloat(), f.optDouble("rank").toFloat(),
                    Tier.valueOf(f.optString("tier", "maybe").uppercase()), null, null, 0f, 0f,
                    listOf((MomentType.values().firstOrNull { it.key == f.optString("type") } ?: MomentType.JOY) to 1f), emptyList(),
                )
            }
            o to LabScore.Case(o.optBoolean("has"), o.optLong("at", -1L), list)
        }
    }

    private fun render() {
        content.removeAllViews()
        bar = null
        content.addView(ui.text(L.t(
            "כאן בודקים את הזיהוי על סרטונים אמיתיים שלכם. לכל סרטון מסמנים מה באמת יש בו, ו-Moments מודד כמה הוא מצא וכמה פספס. שום דבר לא נשמר בזיכרונות.",
            "Test detection on your own real videos. For each one you mark what's really in it, and Moments measures what it found and what it missed. Nothing is saved to your memories.",
        ), 15f, top = 14))

        val all = cases()
        if (all.isNotEmpty()) {
            val card = ui.card(top = 14)
            card.addView(ui.text(L.t("תוצאות (${all.size} סרטונים)", "Results (${all.size} videos)"), 16f, bold = true))
            fun line(title: String, m: LabScore.Metrics) {
                card.addView(ui.text(title, 14f, bold = true, top = 10))
                card.addView(ui.hint(L.t(
                    "Recall ${pct(m.recall)} · Precision ${pct(m.precision)} · False positives ${pct(m.falsePositiveRate)} · False negatives ${pct(m.falseNegativeRate)}",
                    "Recall ${pct(m.recall)} · Precision ${pct(m.precision)} · False positive rate ${pct(m.falsePositiveRate)} · False negative rate ${pct(m.falseNegativeRate)}",
                ), top = 2))
                card.addView(ui.hint(L.t("נמצאו ${m.tp} · פוספסו ${m.fn} · התראות שווא ${m.fp} · נכון שאין ${m.tn}", "Found ${m.tp} · missed ${m.fn} · false alarms ${m.fp} · correctly empty ${m.tn}"), top = 2))
            }
            line(L.t("רק \"חזק\" ו\"טוב\"", "\"Strong\" and \"good\" only"), LabScore.metrics(all.map { it.second }, Tier.GOOD))
            line(L.t("כולל \"אולי\"", "Including \"maybe\""), LabScore.metrics(all.map { it.second }, Tier.MAYBE))
            // Thresholds, compared on the saved results (no re-run): what a stricter cutoff would gain and lose.
            card.addView(ui.text(L.t("השוואת ספים (לפי רמת הביטחון)", "Thresholds compared (by confidence)"), 14f, bold = true, top = 12))
            val cs = all.map { it.second }
            for (k in 0..8) {
                val cut = 0.30f + 0.05f * k
                val m = LabScore.metricsAt(cs, cut)
                card.addView(ui.hint("${"%.2f".format(cut)} · Recall ${pct(m.recall)} · Precision ${pct(m.precision)} · " + L.t("שווא ${pct(m.falsePositiveRate)}", "false alarms ${pct(m.falsePositiveRate)}"), top = 2))
            }
            card.addView(ui.hint(L.t("רגעים מתחת לסף הנמוך לא נשמרו, לכן רק ספים מחמירים אפשר לבדוק כך.", "Moments below the lowest cutoff weren't kept, so only stricter cutoffs can be checked this way."), top = 4))
            // Each version of the moment finder on its own (results of different versions aren't mixed).
            val versions = all.groupBy { it.first.optInt("version", 0) }
            if (versions.size > 1) {
                card.addView(ui.text(L.t("לפי גרסה", "By version"), 14f, bold = true, top = 12))
                for ((v, list) in versions.toSortedMap()) {
                    val m = LabScore.metrics(list.map { it.second }, Tier.GOOD)
                    card.addView(ui.hint(L.t("גרסה $v (${list.size} סרטונים)", "Version $v (${list.size} videos)") + " · Recall ${pct(m.recall)} · Precision ${pct(m.precision)}", top = 2))
                }
            }
            if (running == null) card.addView(ui.action(L.t("הריצו שוב את כל הסרטונים בגרסה הנוכחית (${MomentFusion.VERSION})", "Run all videos again with the current version (${MomentFusion.VERSION})")) { rerunAll() }.apply { setPadding(0, ui.dp(10), 0, 0) })
            val covered = all.map { it.first.optInt("case") }.toSet()
            val missing = CASES.indices.filter { it !in covered }
            if (missing.isNotEmpty()) card.addView(ui.hint(L.t("עוד חסרים: ", "Still missing: ") + missing.joinToString(", ") { L.t(CASES[it].first, CASES[it].second) }, top = 10))
            content.addView(card)
        }

        faceBench()
        bookStats()

        val busy = running
        if (busy != null) {
            content.addView(ui.text(busy, 15f, bold = true, top = 18))
            bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(8)).apply { topMargin = ui.dp(10) }
            }
            content.addView(bar)
        } else {
            content.addView(ui.button(L.t("הוסיפו סרטון לבדיקה", "Add a test video"), primary = true, top = 16) { askCase() })
        }

        for ((o, c) in all.reversed()) {
            val card = ui.card(top = 10)
            val i = o.optInt("case")
            val title = CASES.getOrNull(i)?.let { L.t(it.first, it.second) } ?: ""
            val ok = if (c.hasMoment) LabScore.hit(c, Tier.GOOD) else !LabScore.alarm(c, Tier.GOOD)
            val okMaybe = if (c.hasMoment) LabScore.hit(c, Tier.MAYBE) else !LabScore.alarm(c, Tier.MAYBE)
            card.addView(ui.text((if (ok) "✓ " else if (okMaybe) "~ " else "✗ ") + title, 15f, bold = true))
            card.addView(ui.hint(o.optString("name") + (if (c.atMs >= 0) L.t(" · הרגע בשנייה ${c.atMs / 1000}", " · moment at ${c.atMs / 1000}s") else "") + " · ${o.optLong("took") / 1000}s", top = 2))
            val found = o.optJSONArray("found") ?: JSONArray()
            if (found.length() == 0) card.addView(ui.hint(L.t("לא נמצא כלום", "Nothing found"), top = 4))
            for (j in 0 until found.length()) {
                val f = found.getJSONObject(j)
                card.addView(ui.hint(
                    "${f.optString("tier")} · ${f.optString("type")} · ${f.optLong("start") / 1000}–${f.optLong("end") / 1000}s · " +
                        "conf ${f.opt("confidence")} · A ${f.opt("audio")} · V ${f.opt("visual")} · I ${f.opt("interaction")} · T ${f.opt("temporal")}\n" +
                        (f.optJSONArray("evidence")?.let { e -> (0 until e.length()).joinToString(", ") { e.optString(it) } } ?: ""),
                    top = 4,
                ))
            }
            card.addView(ui.hint(L.t("פריימים ${o.optInt("frames")} · עם פנים ${o.optInt("faces")} · קול ${if (o.optBoolean("audio")) "כן" else "לא"}", "Frames ${o.optInt("frames")} · with faces ${o.optInt("faces")} · sound ${if (o.optBoolean("audio")) "yes" else "no"}"), top = 4))
            o.optJSONObject("ai")?.let { r ->
                val v = r.optJSONArray("violations") ?: JSONArray()
                val txt = if (r.has("error")) L.t("AI: שגיאה · ${r.optString("error")}", "AI: error · ${r.optString("error")}")
                else if (v.length() == 0) L.t("AI (גרסה ${r.optInt("prompt")}): נקי", "AI (version ${r.optInt("prompt")}): clean")
                else L.t("AI (גרסה ${r.optInt("prompt")}): ", "AI (version ${r.optInt("prompt")}): ") + (0 until v.length()).joinToString("\n") { v.optString(it) }
                card.addView(ui.hint(txt, top = 4))
            }
            card.addView(ui.action(L.t("הסירו מהבדיקה", "Remove from the test")) {
                val a = load()
                val keep = JSONArray()
                for (k in 0 until a.length()) if (a.optJSONObject(k)?.optString("id") != o.optString("id")) keep.put(a.get(k))
                store(keep); render()
            })
            content.addView(card)
        }

        if (all.isNotEmpty() && busy == null) {
            // The AI honesty test: the same fixed clips, every time the description prompt changes.
            val ai = ui.card(top = 16)
            ai.addView(ui.text(L.t("בדיקת כנות של ה-AI", "AI honesty test"), 16f, bold = true))
            val tested = all.filter { it.first.optJSONObject("ai")?.optInt("prompt") == ClaudeClient.PROMPT_VERSION }
            val clean = tested.count { (it.first.optJSONObject("ai")?.optJSONArray("violations")?.length() ?: 0) == 0 }
            ai.addView(ui.hint(
                if (tested.isEmpty()) L.t("מריצים את תיאור ה-AI על כל סרטוני הבדיקה ובודקים את רשימת \"אסור לומר\": דיבור שלא נשמע, אדם מהפרופיל שלא זוהה, \"פעם ראשונה\", ומה קרה בסוף הסרטון.", "Runs the AI description on every test video and checks the \"never say\" list: speech that wasn't heard, a profile person who wasn't recognized, \"first time\", and what happened at the end of the video.")
                else L.t("גרסת פרומפט ${ClaudeClient.PROMPT_VERSION}: $clean מתוך ${tested.size} תיאורים נקיים", "Prompt version ${ClaudeClient.PROMPT_VERSION}: $clean of ${tested.size} descriptions clean"),
                top = 4,
            ))
            if (ClaudeClient.hasKey(this)) ai.addView(ui.button(L.t("הריצו את בדיקת ה-AI", "Run the AI test"), top = 10) {
                AlertDialog.Builder(this).setMessage(L.t("הבדיקה משתמשת ב-${all.size} בקשות מהמכסה החודשית. להמשיך?", "The test uses ${all.size} requests from the monthly allowance. Continue?"))
                    .setPositiveButton(L.t("כן", "Yes")) { _, _ -> runAiTest() }
                    .setNegativeButton(L.t("ביטול", "Cancel"), null).show()
            })
            content.addView(ai)
            content.addView(ui.button(L.t("שתפו את הדוח", "Share the report"), top = 16) { share() })
            content.addView(ui.action(L.t("נקו את כל הבדיקה", "Clear the whole test")) {
                AlertDialog.Builder(this).setMessage(L.t("למחוק את כל סרטוני הבדיקה והתוצאות?", "Delete all test videos and results?"))
                    .setPositiveButton(L.t("מחיקה", "Delete")) { _, _ -> file().delete(); render() }
                    .setNegativeButton(L.t("ביטול", "Cancel"), null).show()
            }.apply { setPadding(0, ui.dp(14), 0, 0) })
        }
    }

    /**
     * Face recognition, measured on the faces the parent named: each is hidden from what the app knows, and the app
     * is asked who it is (on the phone only).
     */
    private fun faceBench() {
        val card = ui.card(top = 14)
        card.addView(ui.text(L.t("זיהוי פנים", "Face recognition"), 16f, bold = true))
        faceDiagnosis(card)
        alignLines?.forEach { card.addView(ui.hint(it, top = 2)) }
        if (running == null) {
            card.addView(ui.button(L.t("🔬 השוואה: עם יישור פנים מול בלי", "🔬 Compare: faces aligned vs. not"), primary = true, top = 12) { compareAlignment() })
            card.addView(ui.hint(L.t("רק מדידה על הפנים שאישרתם. לא משנה כלום. בערך דקה.", "Measures on the faces you confirmed only. Changes nothing. About a minute."), top = 4))
        }
        val r = try { PeopleStore.bench(this) } catch (_: Exception) { null }
        if (r == null) {
            card.addView(ui.hint(L.t("צריך לפחות 5 פנים שנתתם להן שם בעצמכם (במסך \"מי זה?\" או בתיקון). אחר כך תופיע כאן המדידה.", "Needs at least 5 faces you named yourselves (on \"Who's this?\" or when fixing). Then the measurement shows here."), top = 4))
            content.addView(card); return
        }
        val names = PeopleStore.people(this).associate { it.id to it.name }
        card.addView(ui.hint(L.t("נבדקו ${r.total} פנים שאישרתם", "${r.total} faces you confirmed were tested") +
            " · Recall ${pct(r.recall)} · Precision ${pct(r.precision)} · " + L.t("זיהוי שגוי ${pct(r.falseMatchRate)} · לא בטוח ${r.unsure}", "wrong match ${pct(r.falseMatchRate)} · unsure ${r.unsure}"), top = 4))
        for (p in r.people.values.sortedByDescending { it.faces }) {
            if (p.faces == 0) continue
            card.addView(ui.hint("${names[p.id] ?: "?"}: " + L.t("${p.faces} פנים · זוהו נכון ${pct(p.recall)} · דיוק ${pct(p.precision)} · לא בטוח ${p.unsure}", "${p.faces} faces · found ${pct(p.recall)} · precision ${pct(p.precision)} · unsure ${p.unsure}"), top = 2))
        }
        for ((pair, n) in r.confusion.entries.sortedByDescending { it.value }.take(5)) {
            card.addView(ui.hint(L.t("${names[pair.first]} זוהה כ${names[pair.second]}: $n פעמים", "${names[pair.first]} taken for ${names[pair.second]}: $n times"), top = 2))
        }
        content.addView(card)
    }

    /** Why faces stay without a name: counted on the phone, by the same rule faces are named by. */
    private fun faceDiagnosis(card: LinearLayout) {
        val d = try { PeopleStore.diagnose(this) } catch (_: Exception) { null } ?: return
        if (!d.modelReady) card.addView(ui.hint(L.t("⚠️ מודל זיהוי הפנים עוד לא ירד לטלפון, ולכן אין זיהוי בכלל. הוא יורד כשיש אינטרנט.", "⚠️ The face model isn't on the phone yet, so nothing is recognized. It downloads when online."), top = 4))
        val n = PeopleStore.calibratedOn(this)
        card.addView(ui.hint(
            if (!PeopleStore.aligned(this)) L.t("יישור פנים: עוד לא הופעל (יופעל לבד בסריקה הבאה)", "Face alignment: not on yet (turns on by itself at the next scan)")
            else L.t("יישור פנים: פעיל · סף ${"%.2f".format(PeopleStore.currentCut())}", "Face alignment: on · threshold ${"%.2f".format(PeopleStore.currentCut())}") +
                if (n > 0) L.t(" (נמדד על $n פנים שאישרתם)", " (measured on $n faces you confirmed)") else L.t(" (ברירת מחדל זהירה, עד שיהיו 12 פנים מאושרות)", " (careful default until 12 confirmed faces)"),
            top = 4,
        ))
        card.addView(ui.hint(L.t(
            "פנים עם שם: ${d.named} · בלי שם: ${d.unnamed} · \"לא אדם\"/הוסתרו: ${d.ignored} · רגעים שעוד לא נסרקו: ${d.notScanned}",
            "Named faces: ${d.named} · without a name: ${d.unnamed} · hidden: ${d.ignored} · moments not scanned yet: ${d.notScanned}",
        ), top = 4))
        if (d.unnamed == 0) return
        val label = mapOf(
            FacePrints.Unnamed.SMALL to L.t("קטנות מדי (פחות מ-72 פיקסלים) — לא מקבלות שם אוטומטית", "Too small (under 72 px) — never named automatically"),
            FacePrints.Unnamed.FAR_NEAR to L.t("כמעט דומות מספיק למישהו", "Almost close enough to someone"),
            FacePrints.Unnamed.FAR to L.t("לא דומות לאף אחד שתויג", "Not like anyone named"),
            FacePrints.Unnamed.TIE to L.t("דומות לשני אנשים (\"תיקו\", למשל אחים)", "Like two people (a \"tie\", e.g. siblings)"),
            FacePrints.Unnamed.NOT_THEM to L.t("סומנו \"זה לא X\"", "Marked \"not X\""),
            FacePrints.Unnamed.WOULD_MATCH to L.t("היו מקבלות שם עכשיו (נסרקו לפני שהאפליקציה למדה מספיק)", "Would be named now (scanned before the app learned enough)"),
            FacePrints.Unnamed.NO_EYES to L.t("בלי עיניים גלויות (ראש מלמעלה, מהצד) — לא מקבלות שם", "No visible eyes (head from above, side) — never named"),
            FacePrints.Unnamed.NO_PEOPLE to L.t("עוד אין אנשים מתויגים", "Nobody named yet"),
        )
        card.addView(ui.text(L.t("למה ${d.unnamed} פנים בלי שם:", "Why ${d.unnamed} faces have no name:"), 14f, bold = true, top = 8))
        for ((k, n) in d.why.entries.sortedByDescending { it.value }) {
            card.addView(ui.hint("${label[k] ?: k.name}: $n (${pct(n.toFloat() / d.unnamed)})", top = 2))
        }
    }

    /**
     * How well the books' characters held up, from what parents did: pages approved, pages drawn again, characters
     * drawn again, books cancelled. By month, so a change in the drawing settings shows as a change here.
     */
    private fun bookStats() {
        val books = try { BookStore.all(this) } catch (_: Exception) { emptyList() }
        if (books.isEmpty()) return
        val card = ui.card(top = 14)
        card.addView(ui.text(L.t("עקביות הדמויות בספרים", "Character consistency in books"), 16f, bold = true))
        val fmt = java.text.SimpleDateFormat("MM/yyyy", java.util.Locale.US)
        for ((month, list) in books.groupBy { fmt.format(java.util.Date(it.created)) }.toSortedMap(compareByDescending { java.text.SimpleDateFormat("MM/yyyy", java.util.Locale.US).parse(it)?.time ?: 0L })) {
            val pages = list.flatMap { it.allPages }.filter { it.status == "done" }
            val approved = pages.count { it.ok }
            val redrawn = pages.count { it.prev.isNotEmpty() }
            val castRedraws = list.sumOf { b -> (b.characterTries - 1).coerceAtLeast(0) + b.cast.sumOf { (it.tries - 1).coerceAtLeast(0) } }
            val cancelled = list.count { it.state == "failed" }
            card.addView(ui.hint("$month · " + L.t(
                "${list.size} ספרים · ${pages.size} עמודים · אושרו ${approved} · צוירו מחדש ${redrawn} (${pct(if (pages.isEmpty()) 0f else redrawn.toFloat() / pages.size)}) · ציורי דמות נוספים $castRedraws · בוטלו $cancelled",
                "${list.size} books · ${pages.size} pages · approved $approved · redrawn $redrawn (${pct(if (pages.isEmpty()) 0f else redrawn.toFloat() / pages.size)}) · extra character drawings $castRedraws · cancelled $cancelled",
            ), top = 4))
        }
        content.addView(card)
    }

    private var alignLines: List<String>? = null

    /** Measures whether aligning faces (eyes level) would recognize the family better, on the faces they named. */
    private fun compareAlignment() {
        if (running != null) return
        running = L.t("משווים זיהוי פנים...", "Comparing face recognition…")
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        render()
        worker.execute {
            val r = try { PeopleStore.compareAlignment(applicationContext) { i, n -> runOnUiThread { running = L.t("משווים פנים ${i + 1} מתוך $n...", "Comparing face ${i + 1} of $n…"); bar?.progress = 100 * i / maxOf(1, n) } } } catch (_: Exception) { null }
            runOnUiThread {
                running = null
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                alignLines = if (r == null) listOf(L.t("צריך לפחות 5 פנים שנתתם להן שם בעצמכם, ומודל פנים בטלפון.", "Needs at least 5 faces you named yourselves, and the face model on the phone."))
                else listOf(
                    L.t("השוואה על ${r.faces} פנים שאישרתם (נמצאו עיניים ב-${r.eyesFound}):", "Compared on ${r.faces} faces you confirmed (eyes found in ${r.eyesFound}):"),
                    L.t("בלי יישור: זוהו נכון ${pct(r.before.recall)} · שגוי ${pct(r.before.falseMatchRate)} · לא בטוח ${r.before.unsure}", "Without aligning: found ${pct(r.before.recall)} · wrong ${pct(r.before.falseMatchRate)} · unsure ${r.before.unsure}"),
                    L.t("עם יישור: זוהו נכון ${pct(r.after.recall)} · שגוי ${pct(r.after.falseMatchRate)} · לא בטוח ${r.after.unsure}", "With aligning: found ${pct(r.after.recall)} · wrong ${pct(r.after.falseMatchRate)} · unsure ${r.after.unsure}"),
                )
                render()
            }
        }
    }

    /** The same test videos through the current moment finder, to compare versions on the same material. */
    private fun rerunAll() {
        val list = load()
        val total = list.length()
        if (total == 0 || running != null) return
        cancelled.set(false)
        running = L.t("בודקים מחדש...", "Testing again…")
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        render()
        worker.execute {
            Settings.load(applicationContext)
            var unreachable = 0
            for (i in 0 until total) {
                if (cancelled.get()) break
                val o = list.optJSONObject(i) ?: continue
                runOnUiThread { running = L.t("בודקים מחדש ${i + 1} מתוך $total...", "Testing again ${i + 1} of $total…"); render() }
                val r = try { MomentScanner.scan(applicationContext, Uri.parse(o.optString("uri")), cancelled) { p -> runOnUiThread { bar?.progress = p } } } catch (_: Exception) { null }
                if (r == null || !r.error.isNullOrEmpty()) { unreachable++; continue }
                o.put("found", JSONArray().also { a -> r.candidates.forEach { a.put(Detections.toJson(it, "lab")) } })
                    .put("version", MomentFusion.VERSION).put("took", r.tookMs).put("frames", r.frames).put("faces", r.framesWithFaces)
                store(list)
            }
            runOnUiThread {
                running = null
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                if (unreachable > 0) Toast.makeText(this, L.t("$unreachable סרטונים לא נגישים יותר (נמחקו או שאין הרשאה)", "$unreachable videos can't be opened any more (deleted or no permission)"), Toast.LENGTH_LONG).show()
                render()
            }
        }
    }

    private fun runAiTest() {
        if (running != null) return
        running = L.t("בודקים את ה-AI...", "Testing the AI…")
        render()
        worker.execute {
            val list = load()
            val profile = Profile.names(applicationContext)
            for (i in 0 until list.length()) {
                val o = list.optJSONObject(i) ?: continue
                runOnUiThread { bar?.progress = 100 * i / list.length() }
                val id = "lab_" + o.optString("id")
                val top = o.optJSONArray("found")?.optJSONObject(0)
                val m = Memory(
                    id = id, uri = o.optString("uri"), name = o.optString("name"), time = System.currentTimeMillis(),
                    label = top?.optString("type")?.let { t -> MomentType.values().firstOrNull { it.key == t }?.label } ?: "manual",
                    lat = null, lon = null, place = "", thumb = "", durationMs = o.optLong("duration"),
                    highlightMs = top?.optLong("peak") ?: -1L,
                )
                val result = JSONObject().put("prompt", ClaudeClient.PROMPT_VERSION)
                try {
                    ClaudeClient.lastRaw = ""
                    ClaudeClient.analyze(applicationContext, m)
                    val raw = try { JSONObject(ClaudeClient.lastRaw.substring(ClaudeClient.lastRaw.indexOf('{'), ClaudeClient.lastRaw.lastIndexOf('}') + 1)) } catch (_: Exception) { JSONObject() }
                    val said = listOf("title", "description", "reason").joinToString(" ") { raw.optString(it) }
                    // Nobody was recognized in a lab clip, so any profile name the AI uses is a guess.
                    val v = HonestyRules.check(said, HonestyRules.Context(emptySet(), profile))
                    result.put("text", said.take(400)).put("violations", JSONArray().also { a -> v.forEach { a.put(it.kind.name.lowercase() + ": " + it.sentence.take(120)) } })
                } catch (e: Exception) {
                    result.put("error", e.message ?: "error").put("violations", JSONArray())
                }
                Facts.remove(applicationContext, id)
                o.put("ai", result)
            }
            store(list)
            runOnUiThread {
                running = null
                if (isFinishing || isDestroyed) return@runOnUiThread
                render()
            }
        }
    }

    private fun askCase() {
        AlertDialog.Builder(this)
            .setTitle(L.t("מה יש בסרטון באמת?", "What's really in the video?"))
            .setItems(CASES.map { L.t(it.first, it.second) }.toTypedArray()) { _, which ->
                pendingCase = which
                pendingHas = which != CASES.size - 1 && which != 8 && which != 9
                if (which == 8 || which == 9) {
                    // Music / noise: the video may or may not also have a real moment in it.
                    AlertDialog.Builder(this).setMessage(L.t("יש בסרטון גם רגע אמיתי (צחוק, חיוך, חיבוק)?", "Is there also a real moment in it (laugh, smile, hug)?"))
                        .setPositiveButton(L.t("כן", "Yes")) { _, _ -> pendingHas = true; askTime() }
                        .setNegativeButton(L.t("לא", "No")) { _, _ -> pendingHas = false; askTime() }.show()
                } else askTime()
            }.show()
    }

    private fun askTime() {
        if (!pendingHas) { pendingAt = -1L; pick(); return }
        val field = EditText(this).apply {
            hint = L.t("למשל 12 (לא חובה)", "e.g. 12 (optional)")
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        val box = LinearLayout(this).apply { setPadding(ui.dp(20), ui.dp(8), ui.dp(20), 0); addView(field, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) }
        AlertDialog.Builder(this)
            .setTitle(L.t("בערך באיזו שנייה הרגע?", "Roughly which second is the moment?"))
            .setView(box)
            .setPositiveButton(L.t("בחירת סרטון", "Choose the video")) { _, _ -> pendingAt = field.text.toString().toLongOrNull()?.times(1000L) ?: -1L; pick() }
            .show()
    }

    private fun pick() {
        try { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) } catch (_: Exception) { }
    }

    private fun run(uri: Uri) {
        if (running != null) return
        try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) { }
        val case = pendingCase
        val has = pendingHas
        val at = pendingAt
        running = L.t("בודקים...", "Testing…")
        cancelled.set(false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        render()
        worker.execute {
            Settings.load(applicationContext)
            val name = try {
                contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            } catch (_: Exception) { null } ?: uri.lastPathSegment.orEmpty()
            val r = MomentScanner.scan(applicationContext, uri, cancelled) { p -> runOnUiThread { bar?.progress = p } }
            val o = JSONObject()
                .put("id", System.currentTimeMillis().toString()).put("case", case).put("has", has).put("at", at)
                .put("name", name).put("uri", uri.toString()).put("duration", r.durationMs).put("took", r.tookMs)
                .put("frames", r.frames).put("faces", r.framesWithFaces).put("audio", r.hasAudio)
                .put("error", r.error ?: "").put("version", MomentFusion.VERSION)
                .put("found", JSONArray().also { a -> r.candidates.forEach { a.put(Detections.toJson(it, "lab")) } })
            val list = load(); list.put(o); store(list)
            runOnUiThread {
                running = null
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (r.error != null) Toast.makeText(this, r.error, Toast.LENGTH_LONG).show()
                render()
            }
        }
    }

    private fun share() {
        val all = cases()
        val good = LabScore.metrics(all.map { it.second }, Tier.GOOD)
        val any = LabScore.metrics(all.map { it.second }, Tier.MAYBE)
        val report = JSONObject()
            .put("app", "Moments detection lab").put("fusion_version", MomentFusion.VERSION)
            .put("good_or_better", JSONObject().put("tp", good.tp).put("fn", good.fn).put("fp", good.fp).put("tn", good.tn))
            .put("including_maybe", JSONObject().put("tp", any.tp).put("fn", any.fn).put("fp", any.fp).put("tn", any.tn))
            .put("cases", JSONArray().also { a -> all.forEach { (o, _) -> a.put(JSONObject(o.toString()).apply { remove("uri") }) } })
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, report.toString(1))
        startActivity(Intent.createChooser(send, L.t("שיתוף הדוח", "Share the report")))
    }

    private fun pct(f: Float) = "${(f * 100).toInt()}%"
}

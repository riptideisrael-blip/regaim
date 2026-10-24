package com.regaim.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * Owner's admin screen: how many people use Moments. The numbers come from the server
 * (function admin_stats in server/admin-stats.sql), which answers only the admin account.
 */
class AdminActivity : AppCompatActivity() {
    companion object {
        fun open(ctx: Context) = ctx.startActivity(Intent(ctx, AdminActivity::class.java))
    }

    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var ui: Ui
    private lateinit var content: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_screen)
        ui = Ui(this)
        content = findViewById(R.id.screenContent)
        findViewById<TextView>(R.id.screenTitle).text = L.t("ניהול", "Admin")
        findViewById<View>(R.id.btnClose).setOnClickListener { finish() }
        load()
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun load() {
        content.removeAllViews()
        content.addView(ui.hint(L.t("טוענים את המספרים...", "Loading stats…"), top = 8))
        worker.execute {
            val result = try { Result.success(Circle.adminStats(applicationContext)) } catch (e: Exception) { Result.failure<JSONObject>(e) }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                content.removeAllViews()
                result.onSuccess { show(it) }.onFailure { fail(it) }
                loadServices()
                loadStorage()
                loadCosts()
                compareCard()
                grantCard()
            }
        }
    }

    /**
     * Film credits for a friend's account: they paid (e.g. per film), and get credits here by their email. Each credit
     * is one film for a week: the whole film and about 4 scenes made again.
     */
    private fun grantCard() {
        val card = ui.card(top = 10)
        card.addView(ui.text(L.t("להוסיף קרדיטים לחבר", "Add credits to a friend"), 17f, bold = true))
        card.addView(ui.hint(L.t("החבר צריך להתחבר לאפליקציה פעם אחת עם האימייל שלו. קרדיט אחד = סרט אחד (כל הסצנות + בערך 4 שינויים, למשך שבוע).",
            "The friend signs in to the app once with their email first. One credit = one film (all scenes + about 4 changes, for a week)."), top = 4))
        val email = ui.field(L.t("האימייל של החבר", "Friend's email"), "")
        email.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        card.addView(email)
        val count = ui.field(L.t("כמה קרדיטים (למשל 1)", "How many credits (e.g. 1)"), "1")
        count.inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
        card.addView(count)
        val result = ui.hint("", top = 6)
        card.addView(ui.button(L.t("להוסיף", "Add"), primary = true, top = 8) {
            val to = email.text.toString().trim()
            val n = count.text.toString().trim().toIntOrNull() ?: 0
            if (!to.contains("@") || n == 0) { result.text = L.t("צריך אימייל ומספר", "An email and a number are needed"); return@button }
            result.text = L.t("מוסיפים…", "Adding…")
            worker.execute {
                val r = CartoonFilm.grant(applicationContext, to, n)
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    result.text = r.fold({ L.t("✓ נוסף. יש לו עכשיו $it קרדיטים.", "✓ Added. They now have $it credits.") }, { it.message ?: L.t("לא הצליח", "Didn't work") })
                }
            }
        })
        card.addView(result)
        content.addView(card)
    }

    /**
     * Pilot: which paid service ran out (fal, ElevenLabs, Claude, Gemini). The server writes a line when a service
     * says "no balance / quota / key" (server/service-alerts.sql), and marks it fixed when it works again.
     */
    /** How full the server is (the free plan: 1 GB of files): when to move to the paid plan. */
    private fun loadStorage() {
        worker.execute {
            val res = try { Result.success(Circle.adminStorage(applicationContext)) } catch (e: Exception) { Result.failure<JSONObject>(e) }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                val card = ui.card(top = 6)
                card.addView(ui.text(L.t("💾 מקום בשרת", "💾 Server storage"), 17f, bold = true))
                res.onSuccess { o ->
                    val total = o.optLong("total")
                    val pct = StorageMeter.percent(total, StorageMeter.FREE_PLAN)
                    card.addView(ui.text(L.t("${StorageMeter.mb(total)} מתוך 1 GB (${pct}%)", "${StorageMeter.mb(total)} of 1 GB (${pct}%)"), 15f,
                        if (pct >= 80) R.color.ds_error else R.color.ink, bold = pct >= 80, top = 4))
                    if (pct >= 80) card.addView(ui.hint(L.t("כמעט מלא: כדאי לעבור ל-Supabase Pro (100 GB, 25$ לחודש).", "Almost full: time for Supabase Pro (100 GB, $25 a month)."), top = 2))
                    val b = o.optJSONArray("buckets")
                    if (b != null) for (i in 0 until b.length()) b.getJSONObject(i).let { x ->
                        card.addView(ui.hint("· " + StorageMeter.folderName(x.optString("id")) + ": " + StorageMeter.mb(x.optLong("bytes")) + L.t(" · ${x.optInt("files")} קבצים", " · ${x.optInt("files")} files"), top = 2))
                    }
                }.onFailure {
                    card.addView(ui.hint(L.t("עוד לא הורץ server/voice-and-storage.sql ב-Supabase.", "server/voice-and-storage.sql hasn't been run in Supabase yet."), top = 4))
                }
                content.addView(card, 0)
            }
        }
    }

    private fun loadServices() {
        worker.execute {
            val res = try {
                val token = Account.token(applicationContext)
                if (token == null) null else CartoonFilm.callEdge(Circle.url(applicationContext) + "/functions/v1/cartoon-film", token, mapOf("action" to "service_alerts"))
            } catch (_: Exception) { null }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                val card = ui.card(top = 6)
                card.addView(ui.text(L.t("שירותים בתשלום", "Paid services"), 17f, bold = true))
                val code = res?.optInt("_code", 200) ?: 0
                val alerts = res?.optJSONArray("alerts")
                when {
                    res == null -> card.addView(ui.hint(L.t("לא הצלחנו לבדוק (אין חיבור או לא מחוברים).", "Couldn't check (offline or signed out)."), top = 4))
                    code == 404 -> card.addView(ui.hint(L.t("עוד לא הורץ server/service-alerts.sql ב-Supabase.", "server/service-alerts.sql hasn't been run in Supabase yet."), top = 4))
                    code !in 200..299 || alerts == null -> card.addView(ui.hint(L.t("השרת לא ענה (צריך להעלות את הגרסה החדשה של cartoon-film).", "The server didn't answer (upload the new cartoon-film version)."), top = 4))
                    else -> {
                        val open = (0 until alerts.length()).map { alerts.getJSONObject(it) }.filter { it.isNull("ok_at") || it.optString("ok_at").isEmpty() }
                        if (open.isEmpty()) card.addView(ui.text(L.t("✓ כל השירותים עובדים", "✓ All services are working"), 15f, R.color.ds_success, top = 4))
                        for (o in open) {
                            card.addView(ui.text("🔴 " + serviceName(o.optString("service")) + " · " + L.t("נגמרה היתרה / חסום", "out of credit / blocked"), 15f, R.color.ds_error, bold = true, top = 8))
                            card.addView(ui.hint(o.optString("at").take(16).replace('T', ' ') + " · (${o.optInt("status")}) " + o.optString("message").take(160), top = 2))
                        }
                        val fixed = (0 until alerts.length()).map { alerts.getJSONObject(it) }.filter { !(it.isNull("ok_at") || it.optString("ok_at").isEmpty()) }
                        for (o in fixed.take(4)) card.addView(ui.hint("✓ " + serviceName(o.optString("service")) + " " + L.t("חזר לעבוד", "works again") + " · " + o.optString("ok_at").take(16).replace('T', ' '), top = 6))
                    }
                }
                content.addView(card, 0)
            }
        }
    }

    /**
     * Saving money on describing moments: the 10 latest described moments, described again by the cheaper model
     * (Haiku), next to what was saved. Nothing is saved; it uses 10 calls of this month's AI allowance.
     */
    private fun compareCard() {
        val card = ui.card(top = 10)
        card.addView(ui.text(L.t("השוואת תיאורים (חיסכון בעלויות)", "Compare descriptions (saving costs)"), 17f, bold = true))
        card.addView(ui.hint(L.t(
            "10 הרגעים האחרונים מתוארים שוב במודל הזול (Haiku), ליד התיאור השמור. שום דבר לא נשמר. משתמש ב-10 קריאות מהמכסה החודשית.",
            "The 10 latest moments are described again by the cheaper model (Haiku), next to the saved description. Nothing is saved. Uses 10 calls of the monthly allowance."), top = 4))
        val results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        lateinit var go: View
        go = ui.button(L.t("להשוות", "Compare"), primary = true, top = 10) {
            go.isEnabled = false
            results.removeAllViews()
            if (!ClaudeClient.hasKey(applicationContext)) {
                results.addView(ui.hint(L.t("הבנת רגעים לא מופעלת בטלפון הזה (הגדרות).", "Moment understanding is off on this phone (Settings)."), top = 8))
                go.isEnabled = true
                return@button
            }
            val picked = MemoryStore.all(applicationContext).filter { it.analyzed && it.description.isNotBlank() }
                .sortedByDescending { it.time }.take(10)
            if (picked.isEmpty()) {
                results.addView(ui.hint(L.t("אין עדיין רגעים מתוארים.", "No described moments yet."), top = 8))
                go.isEnabled = true
                return@button
            }
            worker.execute {
                for ((i, m) in picked.withIndex()) {
                    val r = try { Result.success(ClaudeClient.describeWithHaiku(applicationContext, m)) } catch (e: Exception) { Result.failure<Memory>(e) }
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        results.addView(ui.text("${i + 1}. " + L.t("שמור: ", "Saved: ") + m.title, 14f, bold = true, top = 12))
                        results.addView(ui.hint(m.description.take(240), top = 2))
                        r.onSuccess { h ->
                            results.addView(ui.text("Haiku: " + h.title, 14f, R.color.ds_primary, bold = true, top = 6))
                            results.addView(ui.hint(h.description.take(240), top = 2))
                        }.onFailure { e -> results.addView(ui.hint("Haiku: " + (e.message ?: e.javaClass.simpleName).take(160), top = 6)) }
                    }
                }
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    results.addView(ui.hint(L.t(
                        "אם Haiku מספיק טוב: ב-Supabase ← Edge Functions ← Secrets, מוסיפים AI_DESCRIBE_MODEL עם הערך claude-haiku-4-5-20251001. מוחקים אותו כדי לחזור.",
                        "If Haiku is good enough: in Supabase → Edge Functions → Secrets, add AI_DESCRIBE_MODEL = claude-haiku-4-5-20251001. Delete it to go back."), top = 14))
                    go.isEnabled = true
                }
            }
        }
        card.addView(go)
        card.addView(results)
        content.addView(card)
    }

    /** What drawings and animations at fal cost (estimated by the server from its price list). */
    private fun loadCosts() {
        worker.execute {
            val res = try {
                val token = Account.token(applicationContext)
                if (token == null) null else CartoonFilm.callEdge(Circle.url(applicationContext) + "/functions/v1/cartoon-film", token, mapOf("action" to "cost_summary"))
            } catch (_: Exception) { null }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                val card = ui.card(top = 10)
                card.addView(ui.text(L.t("עלויות fal (הערכה)", "fal costs (estimate)"), 17f, bold = true))
                val code = res?.optInt("_code", 200) ?: 0
                fun usd(v: Double) = "$" + String.format(java.util.Locale.US, "%.2f", v)
                when {
                    res == null -> card.addView(ui.hint(L.t("לא הצלחנו לבדוק (אין חיבור או לא מחוברים).", "Couldn't check (offline or signed out)."), top = 4))
                    code == 404 -> card.addView(ui.hint(L.t("עוד לא הורץ server/ai-costs.sql ב-Supabase.", "server/ai-costs.sql hasn't been run in Supabase yet."), top = 4))
                    code !in 200..299 -> card.addView(ui.hint(L.t("השרת לא ענה (צריך להעלות את הגרסה החדשה של cartoon-film).", "The server didn't answer (upload the new cartoon-film version)."), top = 4))
                    else -> {
                        card.addView(ui.row(L.t("היום", "Today"), usd(res.optDouble("day"))))
                        card.addView(ui.row(L.t("7 ימים", "7 days"), usd(res.optDouble("week"))))
                        card.addView(ui.row(L.t("30 יום", "30 days"), usd(res.optDouble("month"))))
                        val kinds = res.optJSONObject("kinds")
                        if (kinds != null && kinds.length() > 0) {
                            card.addView(ui.text(L.t("לפי סוג (30 יום)", "By kind (30 days)"), 14f, bold = true, top = 10))
                            for (k in kinds.keys()) {
                                val o = kinds.getJSONObject(k)
                                card.addView(ui.row(kindName(k) + " ×${o.optInt("n")}", usd(o.optDouble("usd"))))
                            }
                        }
                        val films = res.optJSONArray("films")
                        if (films != null && films.length() > 0) {
                            card.addView(ui.text(L.t("לפי סרט / ספר אחרון", "Latest films / books"), 14f, bold = true, top = 10))
                            for (i in 0 until films.length()) {
                                val o = films.getJSONObject(i)
                                card.addView(ui.row(o.optString("ref").take(28) + " ×${o.optInt("n")}", usd(o.optDouble("usd"))))
                            }
                        }
                        card.addView(ui.hint(L.t("רק fal (ציורים והנפשות), מחושב לפי מחירון: Kling נבדק, מחירי הציורים משוערים. Claude, Gemini ו-ElevenLabs לא כלולים. רק בקשות מהיום והלאה נספרות.",
                            "fal only (drawings and animations), from a price list: Kling checked, drawing prices estimated. Claude, Gemini and ElevenLabs aren't included. Only requests from today on are counted."), top = 8))
                    }
                }
                content.addView(card, 1.coerceAtMost(content.childCount))
            }
        }
    }

    private fun kindName(k: String) = when (k) {
        "pitzki_scene" -> L.t("פיצקי: ציור סצנה", "Pitzki: scene drawing")
        "pitzki_anim" -> L.t("פיצקי: הנפשה", "Pitzki: animation")
        "pitzki_talk" -> L.t("פיצקי: שפתיים", "Pitzki: lip-sync")
        "book_page", "book_character", "book_cast" -> L.t("ספר: ציור", "Book: drawing")
        "book_anim" -> L.t("ספר: הנפשה", "Book: animation")
        "anim" -> L.t("סרט: הנפשה", "Film: animation")
        else -> k
    }

    private fun serviceName(s: String) = when (s) {
        "fal" -> L.t("fal (ציורים והנפשה: סרטים, ספרים, פיצקי)", "fal (drawings and animation: films, books, Pitzki)")
        "elevenlabs" -> L.t("ElevenLabs (הקול של פיצקי)", "ElevenLabs (Pitzki's voice)")
        "anthropic" -> L.t("Claude (הבנת רגעים וסיפורים)", "Claude (understanding moments, stories)")
        "gemini" -> L.t("Gemini (ציורי קומיקס וסצנות)", "Gemini (comic and scene drawings)")
        else -> s
    }

    private fun fail(e: Throwable) {
        val m = e.message.orEmpty()
        content.addView(ui.text(L.t("לא הצלחנו להביא את המספרים", "Couldn't load stats"), 17f, bold = true, top = 8))
        content.addView(ui.hint(
            when {
                "not allowed" in m || "42501" in m -> L.t("החשבון הזה אינו מנהל. התחברו עם חשבון הבעלים.", "This account isn't an admin. Sign in with the owner account.")
                "admin_stats" in m || "404" in m || "PGRST202" in m -> L.t("עוד לא הוספתם את פונקציית הניהול בשרת. הריצו את הקובץ server/admin-stats.sql ב-SQL Editor של Supabase.", "The admin function isn't on the server yet. Run server/admin-stats.sql in the Supabase SQL Editor.")
                else -> m.ifEmpty { L.t("בדקו חיבור לאינטרנט והתחברות לחשבון.", "Check your internet connection and that you're signed in.") }
            }, top = 6))
        content.addView(ui.button(L.t("נסו שוב", "Try again"), primary = true, top = 14) { load() })
    }

    private fun n(o: JSONObject, key: String) = o.optLong(key, 0L).toString()

    private fun stat(card: LinearLayout, label: String, value: String) {
        card.addView(ui.row(label, value))
    }

    private fun show(s: JSONObject) {
        // Which version is installed (the GitHub build number), so a problem is matched to the right build.
        val version = try { packageManager.getPackageInfo(packageName, 0).versionName.orEmpty() } catch (_: Exception) { "" }
        if (version.isNotEmpty()) content.addView(ui.text(L.t("גרסה מותקנת: $version", "Installed version: $version"), 14f, R.color.ds_primary, bold = true, top = 0))
        content.addView(ui.hint(L.t("מתוך השרת, מתעדכן בכל כניסה למסך", "From the server, refreshed each time you open this screen"), top = 0))

        val users = ui.card(top = 10)
        users.addView(ui.text(L.t("משתמשים", "Users"), 17f, bold = true))
        stat(users, L.t("נרשמו בסך הכל", "Total sign-ups"), n(s, "users"))
        stat(users, L.t("נרשמו ב-7 הימים האחרונים", "Signed up in the last 7 days"), n(s, "users_7d"))
        stat(users, L.t("נרשמו ב-30 הימים האחרונים", "Signed up in the last 30 days"), n(s, "users_30d"))
        stat(users, L.t("פעילים ב-7 הימים האחרונים", "Active in the last 7 days"), n(s, "active_7d"))
        stat(users, L.t("נרשמו עם Google", "Signed up with Google"), n(s, "google"))
        stat(users, L.t("אורחים (נכנסו מקישור הזמנה)", "Guests (joined via invite link)"), n(s, "guests"))
        content.addView(users)

        val fam = ui.card(top = 10)
        fam.addView(ui.text(L.t("משפחות ותוכן", "Families and content"), 17f, bold = true))
        stat(fam, L.t("מעגלי משפחה", "Family circles"), n(s, "circles"))
        stat(fam, L.t("חברים במעגלים", "Circle members"), n(s, "members"))
        stat(fam, L.t("רגעים ששותפו", "Moments shared"), n(s, "posts"))
        stat(fam, L.t("רגעים ששותפו ב-7 ימים", "Moments shared in 7 days"), n(s, "posts_7d"))
        stat(fam, L.t("תגובות", "Comments"), n(s, "comments"))
        stat(fam, L.t("תגובות באימוג'י", "Emoji reactions"), n(s, "reactions"))
        stat(fam, L.t("הודעות משפחה", "Family messages"), n(s, "messages"))
        stat(fam, L.t("רגעים בקהילה", "Community moments"), n(s, "community_posts"))
        stat(fam, L.t("רגעים שדווח עליהם", "Reported moments"), n(s, "community_reported"))
        stat(fam, L.t("אחסון בשימוש (MB)", "Storage used (MB)"), s.optDouble("storage_mb", 0.0).toString())
        content.addView(fam)

        val signups = s.optJSONArray("signups")
        if (signups != null && signups.length() > 0) {
            val card = ui.card(top = 10)
            card.addView(ui.text(L.t("הרשמות ב-14 הימים האחרונים", "Sign-ups in the last 14 days"), 17f, bold = true))
            for (i in 0 until signups.length()) {
                val o = signups.getJSONObject(i)
                stat(card, o.optString("day"), o.optLong("n").toString())
            }
            content.addView(card)
        }

        val recent = s.optJSONArray("recent")
        if (recent != null && recent.length() > 0) {
            val card = ui.card(top = 10)
            card.addView(ui.text(L.t("נרשמו לאחרונה", "Recent sign-ups"), 17f, bold = true))
            for (i in 0 until recent.length()) {
                val o = recent.getJSONObject(i)
                val who = o.optString("name").ifEmpty { o.optString("email") }
                stat(card, who, o.optString("created").take(10))
            }
            content.addView(card)
        }
        content.addView(ui.hint(L.t("מי שמשתמש באפליקציה בלי חשבון לא נספר כאן, כי הזיכרונות שלו נשארים רק בטלפון.", "People using the app without an account aren't counted here, since their memories stay on their phone."), top = 12))
    }
}

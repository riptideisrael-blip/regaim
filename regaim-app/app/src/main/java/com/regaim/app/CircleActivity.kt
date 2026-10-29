package com.regaim.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

/**
 * "מעגל המשפחה" (prototype): one private feed for the whole family.
 * Parents share moments from the app; grandparents and relatives join from a web link, react and comment.
 */
class CircleActivity : AppCompatActivity() {

    companion object {
        fun open(ctx: Context, postId: String? = null) = ctx.startActivity(Intent(ctx, CircleActivity::class.java).putExtra("post_id", postId))
    }

    private lateinit var ui: Ui
    private lateinit var mv: MemoryViews
    private lateinit var content: LinearLayout
    private val worker = Executors.newSingleThreadExecutor()
    private val images = Executors.newFixedThreadPool(2)
    private var feed: Circle.Feed? = null
    private var loading = false
    private var error = ""
    private var busyText = ""
    private var loadedUser = ""
    private var loadedCircle = ""

    private val pick = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) askTitleAndShare(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_screen)
        ui = Ui(this)
        mv = MemoryViews(this)
        content = findViewById(R.id.screenContent)
        // Pull down for the family's newest moments and messages.
        PullRefresh.attach(this, findViewById(R.id.screenScroll), work = { }) { load() }
        findViewById<TextView>(R.id.screenTitle).text = L.t("מעגל המשפחה", "Family circle")
        findViewById<View>(R.id.btnClose).setOnClickListener { finish() }
        takeInvite(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        takeInvite(intent)
    }

    /**
     * Opened from the invite page ("join in the app") or a pasted invite link: the code is kept until the person is
     * signed in, then they join right away. Only the family's own server (from the link) is used.
     */
    private fun takeInvite(i: Intent?) {
        val data = i?.data ?: return
        if (data.scheme != "moments" || data.host != "join") return
        val code = data.getQueryParameter("c").orEmpty().trim()
        if (!LinkSafety.inviteCodeValid(code)) { toast(L.t("קישור ההזמנה לא תקין. בקשו קישור חדש.", "The invite link isn't valid. Ask for a new one.")); return }
        Circle.serverFromInvite(this, data.getQueryParameter("s").orEmpty())
        getSharedPreferences("regaim", MODE_PRIVATE).edit().putString("pending_invite", code).apply()
        setIntent(Intent(this, CircleActivity::class.java))
    }

    /** A kept invite: join as soon as the person is signed in (asks to sign in first). */
    private fun joinPending() {
        val prefs = getSharedPreferences("regaim", MODE_PRIVATE)
        val code = prefs.getString("pending_invite", null) ?: return
        if (!Account.signedIn(this)) {
            toast(L.t("כדי להצטרף למעגל המשפחה, קודם מתחברים לחשבון. אחר כך ההצטרפות תמשיך לבד.", "To join the family circle, sign in first. Joining continues by itself afterwards."))
            WelcomeActivity.open(this)
            return
        }
        prefs.edit().remove("pending_invite").apply()
        // Already in a circle (for example one made by itself at sign-up): ask before moving to the invited one.
        if (Circle.joined(this)) confirmSwitch(code) else joinWith(code)
    }

    private fun joinWith(code: String) {
        run(L.t("מצטרפים למעגל המשפחה...", "Joining the family circle...")) {
            Circle.join(applicationContext, code)
            L.t("הצטרפתם ל${Circle.name(applicationContext)} ❤️", "You joined ${Circle.name(applicationContext)} ❤️")
        }
    }

    private fun confirmSwitch(code: String) {
        AlertDialog.Builder(this)
            .setTitle(L.t("להצטרף למעגל מההזמנה?", "Join the circle from the invite?"))
            .setMessage(L.t(
                "כרגע אתם במעגל \"${Circle.name(this)}\". אחרי ההצטרפות תראו את מעגל המשפחה שהזמין אתכם.",
                "You're in \"${Circle.name(this)}\" now. After joining you'll see the family circle that invited you.",
            ))
            .setPositiveButton(L.t("להצטרף", "Join")) { _, _ -> joinWith(code) }
            .setNegativeButton(L.t("ביטול", "Cancel"), null)
            .show()
    }

    /** Joining another family's circle from inside a circle: paste the invite link (or code). */
    private fun joinOther() {
        val field = ui.field(L.t("הדביקו כאן את קישור ההזמנה", "Paste the invite link here"), "")
        val box = android.widget.FrameLayout(this).apply { setPadding(ui.dp(20), ui.dp(8), ui.dp(20), 0); addView(field) }
        AlertDialog.Builder(this)
            .setTitle(L.t("הצטרפות למעגל של משפחה אחרת", "Join another family's circle"))
            .setView(box)
            .setPositiveButton(L.t("להצטרף", "Join")) { _, _ ->
                val input = field.text.toString().trim()
                val code = if (input.startsWith("http")) Uri.parse(input).also { u -> Circle.serverFromInvite(applicationContext, u.getQueryParameter("s").orEmpty()) }.getQueryParameter("c").orEmpty() else input
                joinWith(code)
            }
            .setNegativeButton(L.t("ביטול", "Cancel"), null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        joinPending()
        if (loadedUser != Account.id(this) || loadedCircle != Circle.circleId(this) || !Account.signedIn(this)) { feed = null; error = "" }
        render()
        load()
    }

    override fun onDestroy() {
        worker.shutdownNow()
        images.shutdownNow()
        super.onDestroy()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun load() {
        if (isFinishing || isDestroyed || worker.isShutdown) return
        if (!Circle.joined(this) || loading) return
        val requestedUser = Account.id(this)
        val requestedCircle = Circle.circleId(this)
        loading = true
        render()
        worker.execute {
            // Last time's feed at once (not for one post opened from a notification); the new one replaces it.
            if (feed == null && intent.getStringExtra("post_id") == null) Circle.cachedFeed(applicationContext)?.let { cached ->
                runOnUiThread { if (!isFinishing && feed == null && requestedUser == Account.id(this) && requestedCircle == Circle.circleId(this)) { feed = cached; loadedUser = requestedUser; loadedCircle = requestedCircle; render() } }
            }
            val r = try {
                var loaded = Circle.feed(applicationContext)
                intent.getStringExtra("post_id")?.let { id ->
                    val selected = loaded.posts.find { it.id == id } ?: Circle.postById(applicationContext, id)
                    loaded = Circle.Feed(listOfNotNull(selected), if (selected != null) Circle.reactionsForPost(applicationContext, id) else emptyList(), if (selected != null) Circle.commentsForPost(applicationContext, id) else emptyList())
                }
                Result.success(loaded)
            } catch (e: Exception) { Result.failure(e) }
            runOnUiThread {
                loading = false
                if (isFinishing || requestedUser != Account.id(this) || requestedCircle != Circle.circleId(this)) return@runOnUiThread
                r.onSuccess { feed = it; error = ""; loadedUser = requestedUser; loadedCircle = requestedCircle }.onFailure { error = it.message ?: L.t("אין חיבור כרגע", "You're offline right now") }
                render()
            }
        }
    }

    private fun render() {
        content.removeAllViews()
        content.addView(ui.hint(L.t("רגעים ששיתפתם ושיחות עם החברים המאושרים במעגל.", "Moments you've shared and chats with approved circle members."), top = 0))
        when {
            !Circle.configured(this) -> serverSetup()
            !Circle.joined(this) -> createOrJoin()
            else -> circleHome()
        }
    }

    // ---------- step 1: the family's server ----------

    private fun serverSetup() {
        val card = ui.card(top = 16)
        card.addView(ui.text(L.t("חיבור לשרת של המשפחה", "Connect your family server"), 18f, bold = true))
        card.addView(ui.hint(L.t("מ-Supabase: Project Settings ← API. מעתיקים את ה-Project URL ואת המפתח הציבורי (anon / publishable).", "In Supabase: Project Settings → API. Copy the Project URL and the public key (anon / publishable)."), top = 4))
        val url = ui.field("https://xxxx.supabase.co", Circle.url(this))
        val key = ui.field(L.t("המפתח הציבורי", "Public key"), Circle.key(this))
        card.addView(url)
        card.addView(key)
        card.addView(ui.text(L.t("כתובת דף המשפחה (לסבא וסבתא)", "Family page link (for grandparents)"), 14f, R.color.ink_soft, top = 12))
        val web = ui.field(Circle.DEFAULT_WEB, Circle.web(this), top = 4)
        card.addView(web)
        card.addView(ui.hint(L.t("אם העלית את index.html ל-GitHub Pages של הריפו, השאר כמו שזה.", "If you uploaded index.html to the repo's GitHub Pages, leave this as is."), top = 4))
        card.addView(ui.button(L.t("שמור והמשך", "Save and continue"), primary = true, top = 14) {
            Circle.setServer(this, url.text.toString(), key.text.toString(), web.text.toString())
            if (key.text.toString().trim().startsWith("sb_secret")) {
                Circle.setServer(this, url.text.toString(), "", web.text.toString())
                toast(L.t("זה המפתח הסודי. צריך את המפתח הציבורי שמתחיל ב-sb_publishable", "That's the secret key. Use the public key that starts with sb_publishable"))
            } else if (!Circle.configured(this)) toast(L.t("הכתובת צריכה להתחיל ב-https:// ולהגיע עם מפתח מלא", "The URL must start with https:// and include a full key"))
            render()
        })
        content.addView(card)
    }

    // ---------- step 2: create or join ----------

    private fun createOrJoin() {
        if (!Account.signedIn(this)) {
            content.addView(ui.text(L.t("המשפחה שלכם, יחד בזיכרונות", "Your family, together in memories"), 22f, bold = true, top = 22))
            content.addView(ui.hint(L.t("התחברו כדי לפתוח מעגל או לקבל הזמנה. הזיכרונות בטלפון זמינים גם בלי חשבון.", "Sign in to start a circle or accept an invite. Memories on your phone work without an account."), top = 8))
            content.addView(ui.button(L.t("התחברות למשפחה", "Sign in to family"), primary = true, top = 16) { WelcomeActivity.open(this) })
            return
        }
        val card = ui.card(top = 16)
        card.addView(ui.text(L.t("פתחו מעגל משפחה", "Start a family circle"), 18f, bold = true))
        val name = ui.field(L.t("שם המעגל", "Circle name"), Profile.homeTitle(this))
        card.addView(name)
        card.addView(ui.button(L.t("צור מעגל", "Create circle"), primary = true, top = 12) {
            run(L.t("פותחים את המעגל...", "Creating your circle...")) {
                Circle.create(applicationContext, name.text.toString().ifBlank { Profile.homeTitle(this) })
                L.t("המעגל שלכם נפתח ❤️", "Your circle is ready ❤️")
            }
        })
        card.addView(ui.divider())
        card.addView(ui.text(L.t("או הצטרפו למעגל קיים", "Or join an existing circle"), 16f, bold = true, top = 6))
        val code = ui.field(L.t("קישור הזמנה או קוד הזמנה מאובטח", "Invite link or secure invite code"), "")
        card.addView(code)
        card.addView(ui.button(L.t("הצטרף", "Join"), top = 10) {
            run(L.t("מצטרפים...", "Joining...")) {
                val input = code.text.toString().trim()
                val token = if (input.startsWith("https://")) Uri.parse(input).also { u -> Circle.serverFromInvite(applicationContext, u.getQueryParameter("s").orEmpty()) }.getQueryParameter("c") ?: "" else input
                Circle.join(applicationContext, token)
                L.t("הצטרפתם ל${Circle.name(applicationContext)}", "You joined ${Circle.name(applicationContext)}")
            }
        })
        // Only for the admin: for a family it only erased the server and left them on a technical screen.
        if (Account.isAdmin(this)) card.addView(ui.action(L.t("שינוי פרטי השרת", "Change server details"), R.color.ink_soft) {
            Circle.setServer(this, "", "", Circle.web(this))
            render()
        })
        content.addView(card)
        if (error.isNotEmpty()) content.addView(ui.hint(error, top = 10))
    }

    private fun run(busy: String, work: () -> String) {
        if (worker.isShutdown || busyText.isNotEmpty()) return
        busyText = busy
        toast(busy)
        render()
        worker.execute {
            val r = try { Result.success(work()) } catch (e: Exception) { Result.failure(e) }
            runOnUiThread {
                busyText = ""
                if (isFinishing) return@runOnUiThread
                r.onSuccess { toast(it) }.onFailure { error = it.message ?: L.t("משהו השתבש", "Something went wrong"); toast(error) }
                render()
                load()
            }
        }
    }

    // ---------- the circle ----------

    /**
     * The shared gallery of the two parents: choose the other parent; when both chose each other, every moment one
     * of them keeps goes automatically to the other one only (not to the rest of the circle).
     */
    private fun partnerCard() {
        val card = ui.card(top = 10)
        card.addView(ui.text(L.t("🔒 גלריה משותפת לשני ההורים", "🔒 A shared gallery for both parents"), 17f, bold = true))
        val line = ui.hint(L.t("בודקים…", "Checking…"), top = 4)
        card.addView(line)
        val actions = ui.horizontal(top = 4)
        card.addView(actions)
        content.addView(card)
        Thread {
            val app = applicationContext
            val status = Circle.partnerStatus(app)
            val members = try { Circle.members(app) } catch (_: Exception) { emptyList() }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                actions.removeAllViews()
                if (status == null) {
                    // (A missing server function and no internet look the same here: parents get the plain words.)
                    line.text = if (Account.isAdmin(app)) L.t("צריך להריץ קודם את partner-gallery.sql ב-Supabase.", "Run partner-gallery.sql in Supabase first.")
                        else L.t("אין חיבור כרגע. נסו שוב בעוד רגע.", "No connection right now. Try again in a moment.")
                    return@runOnUiThread
                }
                // What goes besides the moments, said before choosing (not only once it's on).
                val alsoShared = L.t("עובר גם: הילדים מהפרופיל (שם, תאריך לידה, תמונה והדמות של הסרט), סרטים שיצרתם (במלואם) והסרטים של פיצקי עם הילדים. ילד שאין אצל השני נוסף אצלו לבד.",
                    "Also shared: the children from the profile (name, birthday, picture and film character), films you made (whole) and the children's Pitzki films. A child the other phone doesn't have is added there.")
                val others = members.filter { it.userId != Account.id(app) }
                val chosen = others.firstOrNull { it.userId == status.first }
                line.text = when {
                    chosen == null -> L.t("כל רגע שאחד מכם שומר יעבור אוטומטית לשני, רק לשניכם (לא לשאר המשפחה). קטעים קצרים של 10 שניות.",
                        "Every moment one of you keeps goes automatically to the other, only to the two of you (not the rest of the family). Short 10-second clips.")
                    status.second -> L.t("✓ פעיל עם ${chosen.name}: רגעים חדשים עוברים ביניכם אוטומטית.", "✓ On with ${chosen.name}: new moments go between you automatically.")
                    else -> L.t("בחרת ב${chosen.name}. מחכים ש${chosen.name} יבחר/תבחר בך באפליקציה שלו/שלה.", "You chose ${chosen.name}. Waiting for ${chosen.name} to choose you in their app.")
                }
                if (chosen == null) card.addView(ui.hint(alsoShared, top = 4))
                if (chosen != null && status.second) PartnerShare.turnOn(app, chosen.userId)
                actions.addView(ui.action(if (chosen == null) L.t("לבחור את בן/בת הזוג", "Choose your partner") else L.t("להחליף", "Change")) {
                    if (others.isEmpty()) { toast(L.t("קודם הזמינו את בן/בת הזוג למעגל.", "First invite your partner to the circle.")); return@action }
                    AlertDialog.Builder(this).setTitle(L.t("עם מי לשתף את הגלריה?", "Share the gallery with?"))
                        .setItems(others.map { it.name.ifBlank { L.t("בן משפחה", "Family member") } }.toTypedArray()) { _, i ->
                            val who = others[i]
                            val whoName = who.name.ifBlank { L.t("בן המשפחה", "this family member") }
                            // Everything that goes between the two parents, chosen in one step (said before anything is shared).
                            connectDialog(whoName, alsoShared) { choice -> Thread {
                                val r = try { Circle.setPartner(app, who.userId) } catch (e: Exception) { null }
                                runOnUiThread {
                                    if (isDestroyed) return@runOnUiThread
                                    if (r == null) { toast(L.t("לא הצליח. נסו שוב.", "Didn't work. Try again.")); return@runOnUiThread }
                                    PartnerShare.turnOn(app, who.userId)
                                    applyConnect(choice)
                                    if (!r) toast(L.t("נשאר רק ש$whoName יבחר/תבחר בך באפליקציה שלו/שלה.", "Now $whoName only needs to choose you in their app."))
                                    render()
                                }
                            }.start() }
                        }.show()
                })
                if (chosen != null && status.second) {
                    // What's on between you, at a glance; anything missing is turned on in one tap.
                    val missing = connectMissing(app)
                    card.addView(ui.text(connectLines(app, chosen.name).joinToString("\n"), 13f, R.color.ink, top = 6))
                    if (missing) actions.addView(ui.action(L.t("✨ להשלים את החיבור", "✨ Complete the connection")) {
                        connectDialog(chosen.name, alsoShared) { choice -> applyConnect(choice); render() }
                    })
                    card.addView(ui.hint(alsoShared, top = 4))
                }
                if (chosen != null && status.second) {
                    // A notification when the other parent shares a moment or adds to the family file.
                    card.addView(ui.switch(L.t("התראה כשמשתפים איתי רגע או פריט בתיק", "Notify me when a moment or a file item is shared with me"), PartnerNotify.on(app), top = 8) { on ->
                        PartnerNotify.setOn(app, on)
                        if (on && android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 43)
                    })
                    // What the last sharing did, and a way to run it now (it runs by itself when the app opens).
                    card.addView(ui.hint(L.t("שיתוף אחרון: ", "Last sharing: ") + PartnerShare.lastResult(app).ifBlank { L.t("עוד לא רץ בטלפון הזה", "hasn't run on this phone yet") }, top = 2))
                    actions.addView(ui.action(L.t("🔄 לשתף עכשיו", "🔄 Share now")) {
                        if (PartnerShare.busy) { toast(L.t("השיתוף כבר רץ ברקע. עוד רגע לחצו \"רענון\".", "Sharing is already running. Tap \"refresh\" in a moment.")); render(); return@action }
                        toast(L.t("משתפים…", "Sharing…"))
                        Thread { PartnerShare.sync(app); runOnUiThread { if (!isDestroyed) { toast(PartnerShare.lastResult(app).ifBlank { L.t("השיתוף כבר רץ ברקע", "Sharing is already running") }); render() } } }.start()
                    })
                }
                if (chosen != null) actions.addView(ui.action(L.t("לכבות", "Turn off"), R.color.ink_soft) {
                    // Off means off: what this phone shared with them leaves the server (said before it happens).
                    AlertDialog.Builder(this)
                        .setTitle(L.t("לכבות את הגלריה המשותפת?", "Turn the shared gallery off?"))
                        .setMessage(L.t("כל מה שהטלפון הזה שיתף עם ${chosen.name} (רגעים, תמונות הילדים, הסרטים של פיצקי) יימחק מהשרת. מה ש${chosen.name} שיתף/ה איתך נשאר אצלך. תיק המשפחה נכבה בנפרד, בתחתית התיק.",
                            "Everything this phone shared with ${chosen.name} (moments, the children's pictures, Pitzki's films) is deleted from the server. What ${chosen.name} shared with you stays. The family file is turned off separately, at its bottom."))
                        .setPositiveButton(L.t("לכבות ולמחוק", "Turn off and delete")) { _, _ ->
                            toast(L.t("מוחקים מהשרת…", "Deleting from the server…"))
                            Thread {
                                val n = try { Circle.removePartnerShares(app, chosen.userId) } catch (_: Exception) { -1 }
                                try { Circle.setPartner(app, null) } catch (_: Exception) { }
                                PartnerShare.turnOff(app)
                                runOnUiThread {
                                    if (isDestroyed) return@runOnUiThread
                                    toast(if (n >= 0) L.t("כבוי. נמחקו מהשרת $n קבצים.", "Off. $n files deleted from the server.") else L.t("כבוי, אבל המחיקה מהשרת לא הושלמה. נסו שוב מאוחר יותר.", "Off, but deleting from the server didn't finish. Try again later."))
                                    render()
                                }
                            }.start()
                        }
                        .setNegativeButton(L.t("ביטול", "Cancel"), null)
                        .show()
                })
            }
        }.start()
    }

    /** What the parent chose in [connectDialog]: the older moments too, the family file, notifications. */
    private class Connect(val old: Boolean, val file: Boolean, val notify: Boolean)

    /**
     * Connecting with the other parent in one step: new moments always go (that's the connection), and three choices,
     * all on by default, each said plainly (the family file's own words a tap away, since it holds codes and cards).
     */
    private fun connectDialog(whoName: String, alsoShared: String, go: (Connect) -> Unit) {
        val app = applicationContext
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(22), ui.dp(8), ui.dp(22), 0) }
        box.addView(ui.text(L.t("✓ כל רגע חדש שאחד מכם שומר עובר לשני (קטעים של 10 שניות), רק לשניכם.", "✓ Every new moment one of you keeps goes to the other (10-second clips), only to the two of you."), 14f))
        box.addView(ui.hint(alsoShared, top = 4))
        fun check(label: String, sub: String, on: Boolean) = android.widget.CheckBox(this).apply {
            text = label; textSize = 15f; isChecked = on; setPadding(0, ui.dp(6), 0, 0)
            box.addView(this)
            box.addView(ui.hint(sub, top = 0))
        }
        val old = check(L.t("גם כל הרגעים הישנים", "All the older moments too"), L.t("עוברים ברקע, כמה בכל פעם. אחרת רק מה שיישמר מעכשיו.", "In the background, a few at a time. Otherwise only what's kept from now on."), false)
        val file = check(L.t("לשתף את תיק המשפחה", "Share the family file"), L.t("קבלות, מסמכים, שוברים וחיסכון של הילדים. נשמרים בשרת המשפחה, רק לשניכם (הרשאות, לא הצפנה מקצה לקצה).", "Receipts, documents, vouchers and the children's savings. Kept on the family's server, only for the two of you (permissions, not end-to-end encryption)."), true)
        box.addView(ui.action(L.t("מה בדיוק עובר בתיק ›", "What exactly the file shares ›")) {
            AlertDialog.Builder(this).setMessage(FamilyFileSync.shareConsentText(whoName)).setPositiveButton(L.t("הבנתי", "Got it"), null).show()
        })
        val notify = check(L.t("התראה כש$whoName משתף/ת משהו", "Notify me when $whoName shares something"), L.t("רגע חדש או פריט חדש בתיק, תוך כרבע שעה.", "A new moment or file item, within about 15 minutes."), true)
        AlertDialog.Builder(this)
            .setTitle(L.t("חיבור עם $whoName", "Connect with $whoName"))
            .setView(android.widget.ScrollView(this).apply { addView(box) })
            .setPositiveButton(L.t("לחבר", "Connect")) { _, _ -> go(Connect(old.isChecked, file.isChecked, notify.isChecked)) }
            .setNegativeButton(L.t("ביטול", "Cancel"), null)
            .show()
    }

    /** Turns on what was chosen (the moments themselves are already on), and starts sharing right away. */
    private fun applyConnect(c: Connect) {
        val app = applicationContext
        if (c.old && !PartnerShare.sharingOld(app)) PartnerShare.shareOld(app)
        if (c.file) {
            // Agreeing to share the file is agreeing to keep one (the words were in the same dialog).
            if (!FamilyFile.consented(app)) FamilyFile.setConsented(app)
            if (!FamilyFileSync.on(app)) FamilyFileSync.turnOn(app)
            FamilyFileSync.kick(app, force = true)
        }
        PartnerNotify.setOn(app, c.notify)
        if (c.notify && android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 43)
        Thread { try { PartnerShare.sync(app) } catch (_: Exception) { } }.start()
        toast(L.t("מחובר ✓ הרגעים עוברים ברקע", "Connected ✓ Moments are going in the background"))
    }

    /** Something of the connection is still off on this phone. */
    private fun connectMissing(app: android.content.Context) = !PartnerShare.sharingOld(app) || !FamilyFileSync.on(app) || !PartnerNotify.on(app)

    /** The connection at a glance: ✓ on, ○ off. */
    private fun connectLines(app: android.content.Context, who: String) = listOf(
        L.t("✓ רגעים חדשים עוברים ביניכם", "✓ New moments go between you"),
        (if (PartnerShare.sharingOld(app)) "✓ " else "○ ") + L.t("הרגעים הישנים", "The older moments"),
        (if (FamilyFileSync.on(app)) "✓ " else "○ ") + L.t("תיק המשפחה משותף", "The family file is shared"),
        (if (PartnerNotify.on(app)) "✓ " else "○ ") + L.t("התראה כש$who משתף/ת", "Notified when $who shares"),
    )

    private fun circleHome() {
        val head = ui.card(top = 14)
        head.addView(ui.text(Circle.name(this), 20f, bold = true))
        head.addView(ui.hint(L.t("הזיכרונות משותפים לכל החברים המאושרים במעגל", "Memories are shared with all approved circle members"), top = 2))
        head.addView(ui.button(L.t("שיחה במשפחה", "Family chat"), top = 8) { FamilyChatActivity.open(this) })
        head.addView(ui.button(L.t("הזמינו את המשפחה", "Invite your family"), primary = true, top = 12) { invite() })
        head.addView(ui.button(L.t("שתפו רגע ששמרתם", "Share a saved moment"), top = 8) { pickMoment() })
        val row = ui.horizontal(top = 4)
        row.addView(ui.action(L.t("תמונה או סרטון", "Photo or video")) { AlertDialog.Builder(this).setItems(arrayOf(L.t("תמונה", "Photo"), L.t("סרטון", "Video"))) { _, i -> pick.launch(if (i == 0) "image/*" else "video/*") }.show() })
        row.addView(ui.action(L.t("השם שלי", "My name")) { editMe() })
        row.addView(ui.action(L.t("רענון", "Refresh")) { load(); toast(L.t("מרענן...", "Refreshing...")) })
        head.addView(row)
        head.addView(ui.action(L.t("קיבלתם הזמנה למעגל אחר? הצטרפו כאן", "Got an invite to another circle? Join here")) { joinOther() })
        head.addView(ui.action(L.t("החליפו קישור הזמנה", "Reset invite link"), R.color.ink_soft) {
            AlertDialog.Builder(this).setTitle(L.t("ליצור קישור חדש?", "Create a new link?"))
                .setMessage(L.t("הקישור הישן יפסיק לצרף אנשים. החברים שכבר הצטרפו נשארים במעגל. הפעולה זמינה למנהל המשפחה.", "The old link will stop working. People who already joined stay in the circle. Only the family admin can do this."))
                .setPositiveButton(L.t("צור קישור", "Create link")) { _, _ -> run(L.t("יוצרים הזמנה חדשה...", "Creating a new invite...")) { Circle.renewInvite(applicationContext); L.t("קישור ההזמנה עודכן", "Invite link updated") } }
                .setNegativeButton(L.t("ביטול", "Cancel"), null).show()
        })
        content.addView(head)
        partnerCard()
        if (busyText.isNotEmpty()) content.addView(ui.hint(busyText, top = 10).also { mv.pulse(it) })
        if (error.isNotEmpty()) content.addView(ui.hint(error, top = 10))

        val f = feed
        if (f == null) {
            content.addView(ui.hint(if (loading) L.t("רגע, טוענים את הרגעים של המשפחה...", "Loading your family's moments...") else "", top = 18))
            return
        }
        if (f.posts.isEmpty()) {
            val e = ui.card(top = 18)
            e.gravity = Gravity.CENTER_HORIZONTAL
            e.addView(android.widget.ImageView(this).apply { setImageResource(R.drawable.ic_heart_outline); setColorFilter(androidx.core.content.ContextCompat.getColor(this@CircleActivity, R.color.ink)); layoutParams = android.widget.LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)).apply { gravity = android.view.Gravity.CENTER_HORIZONTAL } })
            e.addView(ui.text(L.t("עדיין אין כאן רגעים", "No moments here yet"), 19f, bold = true, top = 6).apply { gravity = Gravity.CENTER })
            e.addView(ui.text(L.t("פתחו רגע ולחצו \"שתף למעגל המשפחה\", או שתפו תמונה מהגלריה.", "Open a moment and tap \"Share to family circle\", or share a photo from your gallery."), 15f, R.color.ink_soft, top = 6).apply { gravity = Gravity.CENTER })
            content.addView(e)
            return
        }
        f.posts.forEachIndexed { i, p ->
            val v = postCard(p, f)
            content.addView(v)
            if (i < 6) mv.reveal(v, i * 40L)
        }
        content.addView(ui.action(L.t("יציאה מהמעגל", "Leave circle"), R.color.ink_soft) {
            AlertDialog.Builder(this).setTitle(L.t("לצאת מהמעגל?", "Leave the circle?"))
                .setMessage(L.t("לא תראו יותר את הרגעים והשיחות של המעגל. כדי לחזור תצטרכו הזמנה חדשה.", "You won't see the circle's moments and chats anymore. To come back you'll need a new invite."))
                .setPositiveButton(L.t("יציאה", "Leave")) { _, _ ->
                    Thread {
                        val err = try { Circle.leaveForGood(applicationContext); null } catch (e: Exception) {
                            if (e is java.io.IOException) L.t("אין חיבור לאינטרנט, ולכן עוד לא יצאתם מהמעגל. נסו שוב כשיש חיבור.", "No internet connection, so you haven't left the circle yet. Try again when you're online.")
                            else L.t("השרת לא אישר את היציאה, ולכן עוד לא יצאתם מהמעגל. נסו שוב. ", "The server didn't confirm, so you haven't left the circle yet. Try again. ") + (e.message ?: "")
                        }
                        runOnUiThread {
                            if (isDestroyed) return@runOnUiThread
                            if (err == null) {
                                feed = null; render()
                                android.widget.Toast.makeText(this, L.t("יצאתם מהמעגל", "You left the circle"), android.widget.Toast.LENGTH_SHORT).show()
                            } else AlertDialog.Builder(this).setTitle(L.t("היציאה לא הושלמה", "Leaving didn't finish")).setMessage(err)
                                .setPositiveButton(L.t("הבנתי", "OK"), null).show()
                        }
                    }.start()
                }
                .setNegativeButton(L.t("ביטול", "Cancel"), null).show()
        })
    }

    private fun postCard(p: Circle.Post, f: Circle.Feed): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.card_bg)
            clipToOutline = true
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = mv.dp(16) }
        }
        val media = FrameLayout(this).apply {
            setBackgroundColor(DS.buttonSecondary(context))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, mv.dp(320))
        }
        val img = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        media.addView(img)
        img.colorFilter = Circle.styleFilter(p.style)
        if (p.style == "party") media.foreground = android.graphics.drawable.GradientDrawable().apply {
            setStroke(mv.dp(5), DS.button(this@CircleActivity))
        }
        val cover = p.cover.ifEmpty { if (p.kind == "photo") p.video else "" }
        img.tag = cover
        if (cover.isNotEmpty()) images.execute {
            val bmp = Circle.image(applicationContext, cover)
            runOnUiThread {
                if (bmp != null && img.tag == cover) {
                    img.alpha = 0f
                    img.setImageBitmap(bmp)
                    img.animate().alpha(1f).setDuration(220).start()
                }
            }
        }
        if (p.kind != "photo") media.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_play)
            setColorFilter(ContextCompat.getColor(this@CircleActivity, R.color.white))
            setBackgroundResource(R.drawable.play_circle)
            val pad = mv.dp(18)
            setPadding(pad, pad, pad, pad)
            layoutParams = FrameLayout.LayoutParams(mv.dp(64), mv.dp(64), Gravity.CENTER)
        })
        media.setOnClickListener { openMedia(p) }
        box.addView(media)

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(mv.dp(16), mv.dp(12), mv.dp(16), mv.dp(14))
        }
        val titleRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        titleRow.addView(TextView(this).apply {
            text = (if (p.style == "party") "🎉 " else "") + p.title
            textSize = 18f
            typeface = DS.heading
            setTextColor(ContextCompat.getColor(this@CircleActivity, R.color.ink))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        if (p.authorId.isNotEmpty() && p.authorId == Account.id(this)) titleRow.addView(TextView(this).apply {
            text = "⋯"
            textSize = 22f
            setTextColor(ContextCompat.getColor(this@CircleActivity, R.color.ink_soft))
            setPadding(mv.dp(12), 0, mv.dp(4), mv.dp(4))
            setOnClickListener { v -> postMenu(v, p) }
        })
        body.addView(titleRow)
        if (p.caption.isNotEmpty()) body.addView(TextView(this).apply {
            text = p.caption
            textSize = 16f
            setTextColor(ContextCompat.getColor(this@CircleActivity, R.color.ink))
            setPadding(0, mv.dp(6), 0, 0)
        })
        val whenT = p.takenAt.takeIf { it > 0 } ?: p.createdAt
        body.addView(ui.hint(listOf(if (p.onlyFor.isNotEmpty()) "🔒 " + L.t("רק בינינו", "Just us two") else "", p.author, if (whenT > 0) (if (p.takenAt > 0) "" else L.t("שותף: ", "Shared: ")) + Fmt.dayTime(whenT) else "", p.people, p.place).filter { it.isNotEmpty() }.joinToString(" · "), top = 2))
        if (p.story.isNotEmpty()) body.addView(TextView(this).apply {
            text = p.story
            textSize = 16f
            typeface = DS.body
            setLineSpacing(mv.dp(3).toFloat(), 1f)
            setTextColor(ContextCompat.getColor(this@CircleActivity, R.color.ink))
            setPadding(0, mv.dp(8), 0, 0)
        })

        val reacts = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (e in Circle.EMOJIS) {
            val all = f.reactions.filter { it.postId == p.id && it.emoji == e }
            val mine = all.any { it.authorId.isNotEmpty() && it.authorId == Account.id(this) }
            reacts.addView(mv.tag(e + if (all.isNotEmpty()) " ${all.size}" else "").apply {
                if (mine) background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = DS.radiusMedia(this@CircleActivity); setColor(DS.buttonSecondary(this@CircleActivity))
                }
                setOnClickListener {
                    scaleX = 0.8f; scaleY = 0.8f
                    animate().scaleX(1f).scaleY(1f).setDuration(200).start()
                    worker.execute {
                        val failure = try { Circle.react(applicationContext, p.id, e, mine); null } catch (e: Exception) { e.message ?: L.t("התגובה לא נשמרה", "Reaction wasn't saved") }
                        runOnUiThread { if (!isDestroyed) { if (failure != null) toast(failure); load() } }
                    }
                }
                setOnLongClickListener { if (all.isNotEmpty()) toast(all.joinToString(", ") { r -> r.member }); true }
            })
        }
        val cs = f.comments.filter { it.postId == p.id }
        reacts.addView(mv.tag(if (cs.isEmpty()) L.t("תגובה", "Comment") else L.t("${cs.size} תגובות", "${cs.size} comments")).apply { setOnClickListener { addComment(p) } })
        body.addView(ui.scrollRow(reacts, top = 12))
        for (c in cs.takeLast(5)) body.addView(ui.text("${c.member}: ${c.body}", 15f, top = 6))
        if (cs.size > 5) body.addView(ui.action(L.t("כל ${cs.size} התגובות", "All ${cs.size} comments")) { showComments(p, cs) })
        body.addView(ui.action(L.t("שיחה על הזיכרון", "Chat about this memory")) { FamilyChatActivity.open(this, p.id, p.title) })
        box.addView(body)
        if (p.authorId.isNotEmpty() && p.authorId == Account.id(this)) box.setOnLongClickListener { postMenu(it, p); true }
        return box
    }

    /** Your own shared moment: edit (cut again, words, style) or delete. */
    private fun postMenu(anchor: View, p: Circle.Post) {
        val menu = android.widget.PopupMenu(this, anchor)
        menu.menu.add(0, 1, 0, L.t("עריכה: קטע, מילים ועיצוב", "Edit: clip, text and style"))
        menu.menu.add(0, 2, 1, L.t("מחיקה מהמעגל", "Remove from circle"))
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> ShareActivity.edit(this, p)
                2 -> AlertDialog.Builder(this).setTitle(L.t("למחוק את הרגע מהמעגל?", "Remove this moment from the circle?"))
                    .setMessage(L.t("הוא יימחק מהמעגל לכולם. הרגע עצמו נשאר אצלך בטלפון.", "It will be removed for everyone. The moment stays on your phone."))
                    .setPositiveButton(L.t("מחק", "Remove")) { _, _ ->
                        worker.execute {
                            val failure = try { Circle.deletePost(applicationContext, p); null } catch (e: Exception) { e.message ?: L.t("הזיכרון לא נמחק", "Couldn't remove the memory") }
                            runOnUiThread { if (!isFinishing) { if (failure != null) toast(failure); load() } }
                        }
                    }.setNegativeButton(L.t("ביטול", "Cancel"), null).show()
            }
            true
        }
        menu.show()
    }

    /** Pick one of your saved moments to share (newest first; shared ones are marked). */
    private fun pickMoment() {
        val all = MemoryStore.kept(this).take(40)
        if (all.isEmpty()) {
            toast(L.t("עוד אין רגעים שמורים", "No saved moments yet"))
            return
        }
        val grid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(mv.dp(16), mv.dp(8), mv.dp(16), mv.dp(8))
        }
        val dialog = AlertDialog.Builder(this).setTitle(L.t("איזה רגע לשתף?", "Which moment to share?"))
            .setView(android.widget.ScrollView(this).apply { addView(grid) })
            .setNegativeButton(L.t("ביטול", "Cancel"), null).create()
        val cards = all.map { m ->
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                val frame = FrameLayout(this@CircleActivity).apply {
                    setBackgroundResource(R.drawable.rounded_media)
                    clipToOutline = true
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, mv.dp(120))
                }
                val iv = ImageView(this@CircleActivity).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                }
                frame.addView(iv)
                Covers.load(iv, m)
                if (Circle.wasShared(this@CircleActivity, m)) {
                    frame.addView(TextView(this@CircleActivity).apply {
                    text = L.t("שותף", "Shared")
                    textSize = 11f
                    setTextColor(DS.onPhoto(context))
                    setBackgroundResource(R.drawable.pill_dark)
                    setPadding(mv.dp(8), mv.dp(3), mv.dp(8), mv.dp(3))
                    layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
                        setMargins(mv.dp(6), mv.dp(6), mv.dp(6), mv.dp(6))
                    }
                    })
                }
                addView(frame)
                addView(TextView(this@CircleActivity).apply {
                    text = Fmt.title(m)
                    textSize = 13f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setTextColor(ContextCompat.getColor(this@CircleActivity, R.color.ink))
                    setPadding(mv.dp(2), mv.dp(4), mv.dp(2), 0)
                })
                addView(TextView(this@CircleActivity).apply {
                    text = Fmt.dayTime(m.time)
                    textSize = 11f
                    setTextColor(ContextCompat.getColor(this@CircleActivity, R.color.ink_soft))
                    setPadding(mv.dp(2), 0, mv.dp(2), 0)
                })
                setOnClickListener {
                    dialog.dismiss()
                    ShareActivity.share(this@CircleActivity, m.id)
                }
            } as View
        }
        grid.addView(mv.grid(cards, 10))
        dialog.show()
    }

    private fun openMedia(p: Circle.Post) {
        if (p.pitzki) { PitzkiActivity.open(this); return }
        try {
            val type = if (p.kind == "photo") "image/*" else "video/*"
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(p.video), type))
        } catch (_: Exception) {
            toast(L.t("אין באפליקציה נגן מתאים", "No compatible player found"))
        }
    }

    private fun addComment(p: Circle.Post) {
        val field = ui.field(L.t("כתבו משהו...", "Write something..."), "")
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(mv.dp(20), mv.dp(8), mv.dp(20), 0)
            addView(field)
        }
        AlertDialog.Builder(this).setTitle(p.title.ifEmpty { L.t("תגובה", "Comment") }).setView(box)
            .setPositiveButton(L.t("שלח", "Send")) { _, _ ->
                val text = field.text.toString().trim()
                if (text.isEmpty()) return@setPositiveButton
                worker.execute {
                    val failure = try { Circle.comment(applicationContext, p.id, text); null } catch (e: Exception) { e.message ?: L.t("התגובה לא נשלחה", "Comment wasn't sent") }
                    runOnUiThread { if (!isDestroyed) { if (failure != null) toast(failure); load() } }
                }
            }
            .setNegativeButton(L.t("ביטול", "Cancel"), null).show()
    }

    private fun showComments(post: Circle.Post, comments: List<Circle.Comment>) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(20), ui.dp(12), ui.dp(20), ui.dp(12)) }
        comments.forEach { box.addView(ui.text("${it.member}: ${it.body}", 15f, top = 8)) }
        AlertDialog.Builder(this).setTitle(post.title.ifBlank { L.t("תגובות המשפחה", "Family comments") })
            .setView(android.widget.ScrollView(this).apply { addView(box) }).setPositiveButton(L.t("סגור", "Close"), null).show()
    }

    private fun editMe() {
        val field = ui.field(L.t("איך תרצו להופיע למשפחה?", "How should your family see your name?"), Circle.me(this))
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(mv.dp(20), mv.dp(8), mv.dp(20), 0)
            addView(field)
        }
        AlertDialog.Builder(this).setTitle(L.t("איך המשפחה תראה אותך?", "How your family sees you")).setView(box)
            .setPositiveButton(L.t("שמור", "Save")) { _, _ -> Circle.setMe(this, field.text.toString()); render() }
            .setNegativeButton(L.t("ביטול", "Cancel"), null).show()
    }

    private fun invite() {
        if (worker.isShutdown) {
            return
        }
        worker.execute {
            val result = try { Circle.secureReady(applicationContext); Result.success(Circle.inviteLink(applicationContext)) } catch (e: Exception) { Result.failure(e) }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                result.onSuccess { link ->
                    val text = L.t("הצטרפו ל${Circle.name(this)} ❤️\nכאן נשתף יחד זיכרונות משפחתיים:\n$link", "Join ${Circle.name(this)} on Moments ❤️\nLet's share our family memories here:\n$link")
                    try { startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), L.t("להזמין את המשפחה", "Invite your family"))) }
                    catch (_: Exception) { toast(L.t("לא נמצאה אפליקציה לשליחת ההזמנה", "No app found to send the invite")) }
                }.onFailure { toast(it.message ?: L.t("לא הצלחנו להכין הזמנה", "Couldn't create the invite")) }
            }
        }
    }

    private fun askTitleAndShare(uri: Uri) {
        val field = ui.field(L.t("מה קורה כאן? (לא חובה)", "What's happening here? (optional)"), "")
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(mv.dp(20), mv.dp(8), mv.dp(20), 0)
            addView(field)
        }
        AlertDialog.Builder(this).setTitle(L.t("לשתף עם המשפחה", "Share with family")).setView(box)
            .setPositiveButton(L.t("שתף", "Share")) { _, _ ->
                val title = field.text.toString()
                run(L.t("רגע, אנחנו שומרים את הזיכרון הזה...", "Saving this memory...")) {
                    Circle.shareFromGallery(applicationContext, uri, title) { msg -> runOnUiThread { busyText = msg; if (!isFinishing) render() } }
                    L.t("שותף עם המשפחה ❤️", "Shared with your family ❤️")
                }
            }
            .setNegativeButton(L.t("ביטול", "Cancel"), null).show()
    }
}

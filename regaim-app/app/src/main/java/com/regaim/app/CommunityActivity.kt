package com.regaim.app

import android.content.Context
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.format.DateUtils
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import java.util.concurrent.Executors

/**
 * "הקהילה": the public feed. Only moments that people deliberately published here.
 * Like (also with a double tap), comment, report, block. Needs the server file community-v4.sql.
 */
class CommunityActivity : AppCompatActivity() {

    companion object {
        /** Set after publishing, so the feed reloads when it comes back on screen. */
        @Volatile var dirty = false
        fun open(ctx: Context) = ctx.startActivity(Intent(ctx, CommunityActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
    }

    private lateinit var ui: Ui
    private lateinit var mv: MemoryViews
    private lateinit var content: LinearLayout
    private val worker = Executors.newSingleThreadExecutor()
    private val images = Executors.newFixedThreadPool(3)
    private val posts = mutableListOf<Community.Post>()
    private var more = false
    private var loading = false
    private var loaded = false
    private var error = ""
    private var playing: FillVideoView? = null
    private var termsOpen = false
    /** Each author's first post on screen, for the people row. */
    private val postViews = HashMap<String, View>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_screen)
        ui = Ui(this)
        mv = MemoryViews(this)
        content = findViewById(R.id.screenContent)
        // Pull down for the newest posts.
        PullRefresh.attach(this, findViewById(R.id.screenScroll), work = { }) { reload() }
        // Posts reach the screen edges (negative side margins): don't cut them at the page padding.
        content.clipToPadding = false
        content.clipChildren = false
        findViewById<TextView>(R.id.screenTitle).text = L.t("הקהילה", "Community")
        findViewById<View>(R.id.btnClose).visibility = View.GONE
        findViewById<View>(R.id.bottomNav).visibility = View.VISIBLE
        Nav.setup(this, Nav.Tab.COMMUNITY)
        Nav.addProfileButton(this, findViewById<View>(R.id.screenTitle).parent as android.view.ViewGroup)
        // Endless feed: the next page loads by itself near the bottom.
        val scroll = findViewById<ScrollView>(R.id.screenScroll)
        scroll.setOnScrollChangeListener { _, _, y, _, _ ->
            val child = scroll.getChildAt(0) ?: return@setOnScrollChangeListener
            if (more && !loading && error.isEmpty() && posts.isNotEmpty() && y + scroll.height >= child.height - ui.dp(900)) fetch(posts.last().createdAt)
        }
    }

    override fun onResume() {
        super.onResume()
        if (Community.available(this) && !Community.termsAccepted(this)) {
            render()
            if (!termsOpen) showTerms()
            return
        }
        if (dirty || (!loaded && !loading)) { dirty = false; reload() } else render()
    }

    override fun onPause() {
        super.onPause()
        stopVideo()
    }

    override fun onDestroy() {
        worker.shutdownNow()
        images.shutdownNow()
        super.onDestroy()
    }

    // ---------- data ----------

    private fun reload() {
        if (!Community.available(this)) { render(); return }
        posts.clear(); more = false; error = ""
        fetch(0L)
    }

    private fun fetch(before: Long) {
        if (loading || worker.isShutdown) return
        loading = true
        render()
        worker.execute {
            val r = try { Result.success(Community.feed(applicationContext, before)) } catch (e: Exception) { Result.failure<Community.Page>(e) }
            runOnUiThread {
                loading = false
                loaded = true
                if (isFinishing || isDestroyed) return@runOnUiThread
                r.onSuccess { posts.addAll(it.posts); more = it.more; error = "" }
                    .onFailure { error = it.message ?: L.t("לא הצלחנו לפתוח את הקהילה", "Couldn't open the community") }
                render()
            }
        }
    }

    private fun showTerms() {
        termsOpen = true
        val pad = ui.dp(22)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, ui.dp(8), pad, 0) }
        box.addView(ui.text(
                L.t(
                    "• מפרסמים רק רגעים שיש לכם זכות לפרסם.\n" +
                    "• בלי תוכן פוגעני, ובלי פרטים מזהים של ילדים (כתובת, בית ספר, שם מלא).\n" +
                    "• רק השם הפרטי שלכם מוצג, ומיקום לא מתפרסם.\n" +
                    "• אפשר למחוק את מה שפרסמתם בכל רגע.\n" +
                    "• אפשר לדווח על רגע או לחסום משתמש. רגע שדווח עליו שלוש פעמים מוסתר עד שנבדוק אותו.",
                    "• Only share moments you have the right to share.\n" +
                    "• No offensive content, and no identifying details about children (address, school, full name).\n" +
                    "• Only your first name is shown, and location is never shared.\n" +
                    "• You can delete anything you've shared at any time.\n" +
                    "• You can report a moment or block someone. A moment reported three times is hidden until we review it.",
                ) + L.t("\n• הקהילה מיועדת להורים ולמבוגרים בני 18 ומעלה בלבד.", "\n• The community is for parents and adults aged 18 and over only."), 15f))
        // Age gate: the community is full of children's pictures, so it's for adults only.
        val adult = android.widget.CheckBox(this).apply {
            text = L.t("אני בן/בת 18 ומעלה", "I'm 18 or older")
            textSize = 15f
            setPadding(0, ui.dp(6), 0, 0)
        }
        box.addView(adult)
        val dialog = AlertDialog.Builder(this)
            .setTitle(L.t("כללי הקהילה", "Community guidelines"))
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton(L.t("הבנתי, ממשיכים", "Got it, continue")) { _, _ -> termsOpen = false; Community.acceptTerms(this); reload() }
            .setNegativeButton(L.t("לא עכשיו", "Not now")) { _, _ -> termsOpen = false; startActivity(Intent(this, FamilyActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)); finish() }
            .setCancelable(false)
            .show()
        val ok = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        ok.isEnabled = false
        adult.setOnCheckedChangeListener { _, on -> ok.isEnabled = on }
    }

    // ---------- page ----------

    /** Page side padding: posts reach the screen edges, like Instagram and Facebook. */
    private val bleed = 20

    private fun render() {
        stopVideo()
        content.removeAllViews()
        postViews.clear()
        if (!Community.available(this)) {
            val card = ui.card(top = 16)
            card.addView(ui.text(L.t("הקהילה של Moments", "The Moments community"), 20f, bold = true))
            card.addView(ui.hint(L.t("רגעים שמשתמשים בחרו לפרסם לכולם. כדי לראות ולפרסם צריך חשבון.", "Moments people chose to share with everyone. You'll need an account to view and share."), top = 6))
            card.addView(ui.button(L.t("התחברות או הרשמה", "Sign in or sign up"), primary = true, top = 14) { WelcomeActivity.open(this) })
            content.addView(card)
            return
        }
        content.addView(composer())
        if (Account.isAdmin(this)) content.addView(ui.action(L.t("פוסט רשמי של Moments", "Official Moments post")) { officialPost() }.apply { setPadding(0, ui.dp(10), 0, 0) })
        content.addView(challengeCard())
        if (posts.isNotEmpty()) content.addView(peopleRow())
        content.addView(hairline(top = 12))
        if (error.isNotEmpty()) {
            val card = ui.card(top = 14)
            card.addView(ui.text(error, 15f))
            card.addView(ui.button(L.t("נסו שוב", "Try again"), primary = true, top = 12) { reload() })
            content.addView(card)
        }
        for ((i, p) in posts.toList().withIndex()) {
            val v = postCard(p)
            postViews[p.authorId] = postViews[p.authorId] ?: v
            content.addView(v)
            if (i < 3) mv.reveal(v, i * 50L)
        }
        if (loading) repeat(if (posts.isEmpty()) 2 else 1) { content.addView(skeleton()) }
        if (!loading && error.isEmpty() && posts.isEmpty()) {
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(ui.dp(12), ui.dp(40), ui.dp(12), ui.dp(20))
            }
            box.addView(ImageView(this).apply {
                setImageResource(R.drawable.ic_tab_community)
                setColorFilter(ui.color(R.color.ink))
                layoutParams = LinearLayout.LayoutParams(ui.dp(56), ui.dp(56))
            })
            box.addView(ui.text(L.t("הקהילה רק מתחילה", "The community is just getting started"), 20f, bold = true, top = 12).apply { gravity = Gravity.CENTER })
            box.addView(ui.hint(L.t("היו הראשונים לפרסם רגע טוב. רק מה שתבחרו מתפרסם.", "Be the first to share a great moment. Only what you choose gets shared."), top = 6).apply { gravity = Gravity.CENTER })
            box.addView(ui.button(L.t("פרסמו רגע", "Share a moment"), primary = true, top = 16) { pickMemory() })
            content.addView(box)
        }
        if (more && !loading && error.isEmpty() && posts.isNotEmpty()) content.addView(ui.action(L.t("עוד רגעים", "More moments")) { fetch(posts.last().createdAt) }.apply { gravity = Gravity.CENTER; setPadding(0, ui.dp(16), 0, ui.dp(8)) })
        if (!loading && !more && posts.isNotEmpty()) content.addView(ui.hint(L.t("ראיתם הכל ✓", "You're all caught up ✓"), top = 20).apply { gravity = Gravity.CENTER })
    }

    /** Facebook-style "what's on your mind": your photo, a soft pill, and a gallery button. */
    private fun composer(): View {
        val row = ui.horizontal(top = 10)
        row.gravity = Gravity.CENTER_VERTICAL
        row.addView(mv.avatar(Community.displayName(this), 40))
        row.addView(TextView(this).apply {
            text = L.t("איזה רגע תרצו לשתף?", "Which moment would you like to share?")
            textSize = 15f
            setTextColor(ui.color(R.color.ink_soft))
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.dp(16), ui.dp(11), ui.dp(16), ui.dp(11))
            background = GradientDrawable().apply { cornerRadius = DS.radiusButton(this@CommunityActivity); setColor(ui.color(R.color.fill)) }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = ui.dp(10); marginEnd = ui.dp(6) }
            setOnClickListener { pickMemory() }
        })
        row.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_gallery)
            setColorFilter(ui.color(R.color.ink))
            contentDescription = L.t("בחרו רגע", "Choose a moment")
            setPadding(ui.dp(8), ui.dp(8), ui.dp(8), ui.dp(8))
            layoutParams = LinearLayout.LayoutParams(ui.dp(40), ui.dp(40))
            setBackgroundResource(android.R.drawable.list_selector_background)
            setOnClickListener { pickMemory() }
        })
        return row
    }

    /** This week's challenge (changes every week). "Join" opens the moment picker with the hashtag ready. */
    private fun challengeCard(): View {
        val challenges = listOf(
            Triple("הצחוק הכי גדול של השבוע", "The biggest laugh of the week", "#צחוק_השבוע" to "#LaughOfTheWeek"),
            Triple("ארוחה משפחתית", "Family dinner", "#ארוחה_משפחתית" to "#FamilyDinner"),
            Triple("הצעד הראשון (או השני, או המאה)", "First steps (or the hundredth)", "#צעדים" to "#FirstSteps"),
            Triple("רגע עם סבא וסבתא", "A moment with grandma and grandpa", "#סבא_וסבתא" to "#Grandparents"),
            Triple("בלגן של שבת בבוקר", "Weekend morning chaos", "#בוקר_שבת" to "#WeekendMorning"),
            Triple("אחים ואחיות", "Siblings", "#אחים" to "#Siblings"),
            Triple("ריקוד בסלון", "Living room dance", "#ריקוד" to "#DanceParty"),
            Triple("רגע בטבע", "A moment outdoors", "#בחוץ" to "#Outdoors"),
        )
        val week = (System.currentTimeMillis() / (7L * 24 * 3600 * 1000)).toInt()
        val c = challenges[Math.floorMod(week, challenges.size)]
        val tag = L.t(c.third.first, c.third.second)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(18), ui.dp(16), ui.dp(18), ui.dp(16))
            background = GradientDrawable().apply { cornerRadius = DS.radiusCard(this@CommunityActivity); setColor(DS.button(this@CommunityActivity)) }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ui.dp(14) }
        }
        card.addView(ui.text(L.t("האתגר של השבוע", "This week's challenge"), 12f, bold = true).apply { setTextColor(DS.alpha(DS.onButton(this@CommunityActivity), 0.85f)) })
        card.addView(ui.text(L.t(c.first, c.second), 19f, bold = true, top = 4).apply { setTextColor(DS.onButton(this@CommunityActivity)) })
        card.addView(ui.text(L.t("שתפו רגע עם $tag ונציג את היפים ביותר כאן בקהילה.", "Share a moment with $tag and we'll feature the best ones here."), 13f, top = 4).apply { setTextColor(DS.alpha(DS.onButton(this@CommunityActivity), 0.9f)) })
        card.addView(TextView(this).apply {
            text = L.t("השתתפו", "Join in")
            textSize = 14f
            typeface = DS.emphasis
            setTextColor(DS.primary(this@CommunityActivity))
            gravity = Gravity.CENTER
            setPadding(ui.dp(20), ui.dp(9), ui.dp(20), ui.dp(9))
            background = GradientDrawable().apply { cornerRadius = DS.radiusButton(this@CommunityActivity); setColor(DS.bg(this@CommunityActivity)) }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ui.dp(12) }
            setOnClickListener { pickMemory("$tag ") }
        })
        return card
    }

    /** Admins only: a text card posted as the official Moments account (tips, challenge winners, news). */
    private fun officialPost() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            setPadding(ui.dp(20), ui.dp(8), ui.dp(20), 0)
        }
        val head = android.widget.EditText(this).apply { hint = L.t("כותרת (תופיע על הכרטיס)", "Headline (shown on the card)"); maxLines = 2 }
        val body = android.widget.EditText(this).apply { hint = L.t("טקסט (לא חובה)", "Text (optional)"); minLines = 2; maxLines = 6 }
        box.addView(head)
        box.addView(body)
        AlertDialog.Builder(this)
            .setTitle(L.t("פוסט רשמי של Moments", "Official Moments post"))
            .setView(box)
            .setPositiveButton(L.t("פרסום", "Post")) { _, _ ->
                val h = head.text.toString().trim()
                val b = body.text.toString().trim()
                if (h.isEmpty()) return@setPositiveButton
                val app = applicationContext
                Toast.makeText(this, L.t("מפרסם…", "Posting…"), Toast.LENGTH_SHORT).show()
                worker.execute {
                    val r = try { Community.publishCard(app, Community.tipCard(app, h, b), h, b); null } catch (e: Exception) { e.message ?: "error" }
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        if (r != null) Toast.makeText(this, L.t("הפרסום נכשל: $r", "Couldn't post: $r"), Toast.LENGTH_LONG).show()
                        else { dirty = true; reload() }
                    }
                }
            }
            .setNegativeButton(L.t("ביטול", "Cancel"), null)
            .show()
    }

    /** The people posting lately, like a stories row. Tapping one jumps to their latest moment. */
    private fun peopleRow(): View {
        val authors = posts.distinctBy { it.authorId }.take(12)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (p in authors) row.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(ui.dp(4), 0, ui.dp(10), 0)
            addView(mv.avatar(p.author, 60, byName = false))
            addView(TextView(this@CommunityActivity).apply {
                text = p.author
                textSize = 12f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                maxWidth = ui.dp(68)
                gravity = Gravity.CENTER
                setTextColor(ui.color(R.color.ink))
                setPadding(0, ui.dp(4), 0, 0)
            })
            setOnClickListener {
                val target = postViews[p.authorId] ?: return@setOnClickListener
                val scroll = findViewById<ScrollView>(R.id.screenScroll)
                scroll.smoothScrollTo(0, target.top)
            }
        })
        return ui.scrollRow(row, top = 16)
    }

    private fun hairline(top: Int = 0) = View(this).apply {
        setBackgroundColor(ui.color(R.color.line))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(1)).apply {
            topMargin = ui.dp(top); marginStart = -ui.dp(bleed); marginEnd = -ui.dp(bleed)
        }
    }

    /** A gray placeholder post while the feed loads. */
    private fun skeleton(): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = ui.dp(16); marginStart = -ui.dp(bleed); marginEnd = -ui.dp(bleed)
            }
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.dp(14), 0, ui.dp(14), ui.dp(10))
        }
        head.addView(View(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(ui.color(R.color.fill)) }
            layoutParams = LinearLayout.LayoutParams(ui.dp(34), ui.dp(34))
        })
        head.addView(View(this).apply {
            background = GradientDrawable().apply { cornerRadius = ui.dp(6).toFloat(); setColor(ui.color(R.color.fill)) }
            layoutParams = LinearLayout.LayoutParams(ui.dp(120), ui.dp(12)).apply { marginStart = ui.dp(10) }
        })
        box.addView(head)
        box.addView(View(this).apply {
            setBackgroundColor(ui.color(R.color.fill))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (resources.displayMetrics.widthPixels * 1.25f).toInt())
        })
        mv.pulse(box)
        return box
    }

    private fun pickMemory(startCaption: String = "") {
        val all = MemoryStore.kept(this).take(24)
        if (all.isEmpty()) {
            Toast.makeText(this, L.t("עוד אין לכם רגעים לפרסם. צלמו או הוסיפו רגע ונחזור לפה.", "You don't have any moments to share yet. Capture or add one, then come back."), Toast.LENGTH_LONG).show()
            return
        }
        val dialog = BottomSheetDialog(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            setPadding(ui.dp(16), ui.dp(16), ui.dp(16), ui.dp(24))
        }
        box.addView(ui.text(L.t("בחרו רגע לפרסום", "Choose a moment to share"), 18f, bold = true))
        val views = all.map { m ->
            mv.gridCard(m) { dialog.dismiss(); PublishSheet.show(this, m, Community.lastAudience(this).let { if (it == "family") "community" else it }, startCaption) { dirty = true; reload() } }
        }
        box.addView(mv.grid(views, 10))
        dialog.setContentView(ScrollView(this).apply { addView(box) })
        dialog.show()
    }

    // ---------- a post ----------

    private class State(var liked: Boolean, var likes: Int, var comments: Int)

    /** One post, Instagram-style: header, the picture edge to edge, icon actions, likes, caption, comments link. */
    private fun postCard(p: Community.Post): View {
        val me = Account.id(this)
        val st = State(p.liked, p.likes, p.comments)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = ui.dp(16); marginStart = -ui.dp(bleed); marginEnd = -ui.dp(bleed)
            }
        }
        val side = ui.dp(14)

        // header
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(side, 0, ui.dp(4), ui.dp(10))
        }
        head.addView(mv.avatar(p.author, 34, byName = false))
        val names = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(10), 0, ui.dp(8), 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        if (p.official) {
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            line.addView(ui.text("Moments", 14f, bold = true))
            line.addView(TextView(this).apply {
                text = L.t("צוות Moments", "Moments team")
                textSize = 11f
                typeface = DS.emphasis
                setTextColor(DS.onButton(this@CommunityActivity))
                setPadding(ui.dp(8), ui.dp(2), ui.dp(8), ui.dp(2))
                background = GradientDrawable().apply { cornerRadius = DS.radiusChip(this@CommunityActivity); setColor(DS.button(this@CommunityActivity)) }
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = ui.dp(6) }
            })
            names.addView(line)
        } else names.addView(ui.text(p.author, 14f, bold = true))
        val sub = DateUtils.getRelativeTimeSpanString(p.createdAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString() +
            (if (p.hidden) L.t(" · מוסתר", " · Hidden") else "") + (if (p.reports > 0) L.t(" · דיווחים: ${p.reports}", " · Reports: ${p.reports}") else "")
        names.addView(ui.text(sub, 12f, R.color.ink_soft))
        head.addView(names)
        head.addView(TextView(this).apply {
            text = "⋯"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(ui.color(R.color.ink))
            setPadding(ui.dp(12), 0, ui.dp(12), 0)
            contentDescription = L.t("עוד אפשרויות", "More options")
            setOnClickListener { menu(it, p, me) }
        })
        box.addView(head)

        // media, edge to edge (4:5), double tap = like
        val width = resources.displayMetrics.widthPixels
        val frame = FrameLayout(this).apply {
            setBackgroundColor(if (p.isVideo) ui.color(R.color.ds_video_bg) else ui.color(R.color.fill))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (width * 1.25f).toInt())
        }
        val pic = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            tag = p.image
        }
        frame.addView(pic)
        loadImage(pic, p.image)
        val badge = TextView(this).apply {
            text = "▶"
            textSize = 24f
            setTextColor(DS.onPhoto(this@CommunityActivity))
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(ui.color(R.color.ds_photo_pill)) }
            layoutParams = FrameLayout.LayoutParams(ui.dp(60), ui.dp(60), Gravity.CENTER)
            visibility = if (p.isVideo) View.VISIBLE else View.GONE
        }
        frame.addView(badge)
        // A book: a label on the cover; a tap opens it to read page by page.
        if (p.isBook) frame.addView(TextView(this).apply {
            text = L.t("📖 ספר · ${p.pages.size} עמודים · לחצו לקריאה", "📖 Book · ${p.pages.size} pages · tap to read")
            textSize = 13f
            typeface = DS.emphasis
            setTextColor(DS.onPhoto(this@CommunityActivity))
            setPadding(ui.dp(12), ui.dp(6), ui.dp(12), ui.dp(6))
            background = GradientDrawable().apply { cornerRadius = DS.radiusChip(this@CommunityActivity); setColor(DS.scrim(this@CommunityActivity)) }
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply {
                topMargin = ui.dp(12); marginStart = ui.dp(12)
            }
        })
        val pop = ImageView(this).apply {
            setImageResource(R.drawable.ic_heart)
            setColorFilter(DS.onPhoto(this@CommunityActivity))
            alpha = 0f
            elevation = ui.dp(4).toFloat()
            layoutParams = FrameLayout.LayoutParams(ui.dp(96), ui.dp(96), Gravity.CENTER)
        }
        frame.addView(pop)
        box.addView(frame)

        // actions
        val heart = ImageView(this)
        val likesLine = TextView(this)
        val commentsLine = TextView(this)
        fun paintLike() {
            heart.setImageResource(if (st.liked) R.drawable.ic_heart else R.drawable.ic_heart_outline)
            heart.setColorFilter(ui.color(if (st.liked) R.color.heart else R.color.ink))
            likesLine.text = when (st.likes) {
                0 -> ""
                1 -> L.t("לייק אחד", "1 like")
                else -> L.t("${st.likes} לייקים", "${st.likes} likes")
            }
            likesLine.visibility = if (st.likes > 0) View.VISIBLE else View.GONE
            commentsLine.text = when (st.comments) {
                0 -> L.t("הוסיפו תגובה...", "Add a comment…")
                1 -> L.t("צפו בתגובה", "View 1 comment")
                else -> L.t("צפו בכל ${st.comments} התגובות", "View all ${st.comments} comments")
            }
        }
        fun toggleLike(on: Boolean? = null) {
            val want = on ?: !st.liked
            if (want == st.liked) return
            st.liked = want
            st.likes += if (want) 1 else -1
            paintLike()
            heart.animate().scaleX(1.25f).scaleY(1.25f).setDuration(110).withEndAction { heart.animate().scaleX(1f).scaleY(1f).setDuration(110).start() }.start()
            worker.execute {
                try { Community.setLike(applicationContext, p.id, want) } catch (e: Exception) {
                    runOnUiThread {
                        st.liked = !want; st.likes += if (want) -1 else 1; paintLike()
                        Toast.makeText(this, e.message ?: L.t("לא הצלחנו", "Something went wrong"), Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        val comments = { openComments(p, st) { c -> st.comments = c; paintLike() } }
        val gesture = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onDoubleTap(e: MotionEvent): Boolean {
                toggleLike(true)
                pop.scaleX = 0.4f; pop.scaleY = 0.4f; pop.alpha = 1f
                pop.animate().scaleX(1.1f).scaleY(1.1f).setDuration(260).withEndAction { pop.animate().alpha(0f).setDuration(220).start() }.start()
                return true
            }
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (p.isVideo) playVideo(frame, pic, badge, p)
                else if (p.isBook) CommunityBookActivity.open(this@CommunityActivity, p)
                return true
            }
        })
        frame.setOnTouchListener { _, e -> gesture.onTouchEvent(e) }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.dp(6), ui.dp(4), ui.dp(6), 0)
        }
        fun icon(v: ImageView, desc: String, go: () -> Unit) {
            v.contentDescription = desc
            v.setPadding(ui.dp(8), ui.dp(8), ui.dp(8), ui.dp(8))
            v.layoutParams = LinearLayout.LayoutParams(ui.dp(42), ui.dp(42))
            v.setBackgroundResource(android.R.drawable.list_selector_background)
            v.setOnClickListener { go() }
            bar.addView(v)
        }
        icon(heart, L.t("לייק", "Like")) { toggleLike() }
        icon(ImageView(this).apply { setImageResource(R.drawable.ic_comment); setColorFilter(ui.color(R.color.ink)) }, L.t("תגובות", "Comments")) { comments() }
        box.addView(bar)

        val words = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(side, 0, side, 0)
        }
        likesLine.apply { textSize = 14f; typeface = DS.emphasis; setTextColor(ui.color(R.color.ink)) }
        words.addView(likesLine)
        // "Name  title. caption" — the author's name in bold, like Instagram.
        val caption = listOf(p.title.trim(), p.caption.trim()).filter { it.isNotEmpty() }.joinToString(L.t(". ", ". "))
        if (caption.isNotEmpty()) words.addView(TextView(this).apply {
            val s = android.text.SpannableStringBuilder()
            s.append(p.author, android.text.style.StyleSpan(android.graphics.Typeface.BOLD), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            s.append("  ").append(caption)
            text = s
            textSize = 14f
            maxLines = 3
            ellipsize = android.text.TextUtils.TruncateAt.END
            setLineSpacing(ui.dp(2).toFloat(), 1f)
            setTextColor(ui.color(R.color.ink))
            setPadding(0, ui.dp(3), 0, 0)
            setOnClickListener { maxLines = if (maxLines == 3) 40 else 3 }
        })
        // A book: the parent's own rating and review, and whether it's a real book made in the app (checked by the server).
        if (p.isBook && (p.rating > 0 || p.review.isNotBlank() || p.verified)) {
            val r = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10))
                background = GradientDrawable().apply { cornerRadius = DS.radiusChip(this@CommunityActivity); setColor(ui.color(R.color.fill)) }
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ui.dp(6) }
            }
            if (p.rating > 0) r.addView(ui.text("★".repeat(p.rating) + "☆".repeat(5 - p.rating) + L.t("  חוות הדעת של ההורה", "  The parent's review"), 14f, bold = true).apply { setTextColor(DS.star(this@CommunityActivity)) })
            if (p.review.isNotBlank()) r.addView(ui.text("“${p.review.trim()}”", 14f).apply { setPadding(0, ui.dp(4), 0, 0) })
            if (p.verified) r.addView(ui.text(L.t("✓ ספר אמיתי שנוצר באפליקציה על ידי ${p.author}", "✓ A real book made in the app by ${p.author}"), 12f, R.color.ink_soft).apply { setPadding(0, ui.dp(6), 0, 0) })
            words.addView(r)
        }
        commentsLine.apply {
            textSize = 14f
            setTextColor(ui.color(R.color.ink_soft))
            setPadding(0, ui.dp(5), 0, ui.dp(2))
            setOnClickListener { comments() }
        }
        words.addView(commentsLine)
        box.addView(words)
        paintLike()
        return box
    }

    private fun loadImage(iv: ImageView, url: String) {
        if (url.isEmpty()) return
        iv.setImageDrawable(ColorDrawable(DS.buttonSecondary(this@CommunityActivity)))
        if (images.isShutdown) return
        images.execute {
            val bmp = Community.image(applicationContext, url)
            runOnUiThread {
                if (bmp != null && iv.tag == url && !isDestroyed) {
                    iv.alpha = 0f
                    iv.setImageBitmap(bmp)
                    iv.animate().alpha(1f).setDuration(220).start()
                }
            }
        }
    }

    // ---------- video ----------

    private fun stopVideo() {
        playing?.let { v ->
            v.release()
            (v.parent as? ViewGroup)?.let { parent ->
                parent.removeView(v)
                for (i in 0 until parent.childCount) {
                    val c = parent.getChildAt(i)
                    if (c is TextView && c.text == "▶") c.visibility = View.VISIBLE
                    if (c is ImageView) c.visibility = View.VISIBLE
                }
            }
        }
        playing = null
    }

    private fun playVideo(frame: FrameLayout, pic: ImageView, badge: View, p: Community.Post) {
        val current = playing
        if (current != null && current.parent === frame) {
            if (current.isPlaying) current.pause() else current.start()
            return
        }
        stopVideo()
        val v = FillVideoView(this).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER)
        }
        frame.addView(v, 1)
        badge.visibility = View.GONE
        v.onReady = { runOnUiThread { pic.visibility = View.GONE } }
        v.onFailed = {
            runOnUiThread {
                Toast.makeText(this, L.t("לא הצלחנו לנגן את הסרטון", "Couldn't play this video"), Toast.LENGTH_SHORT).show()
                stopVideo()
            }
        }
        v.play(p.mediaUrl)
        playing = v
    }

    // ---------- menu, report, block ----------

    private fun menu(anchor: View, p: Community.Post, me: String) {
        val m = PopupMenu(this, anchor)
        val own = p.authorId == me
        if (own) m.menu.add(0, 1, 0, L.t("מחיקת הרגע", "Delete moment"))
        else {
            m.menu.add(0, 2, 0, L.t("דיווח על הרגע", "Report moment"))
            m.menu.add(0, 3, 1, L.t("חסימת ${p.author}", "Block ${p.author}"))
        }
        if (Account.isAdmin(this)) {
            m.menu.add(0, 4, 2, if (p.hidden) L.t("ביטול הסתרה (מנהל)", "Unhide (admin)") else L.t("הסתרה (מנהל)", "Hide (admin)"))
            if (!own) m.menu.add(0, 5, 3, L.t("מחיקה (מנהל)", "Delete (admin)"))
        }
        m.setOnMenuItemClickListener {
            when (it.itemId) {
                1, 5 -> confirmDelete(p)
                2 -> reportDialog(p)
                3 -> confirmBlock(p)
                4 -> background(L.t("מעדכנים...", "Updating…")) { Community.setHidden(applicationContext, p.id, !p.hidden) }
            }
            true
        }
        m.show()
    }

    private fun background(msg: String, done: String = "", work: () -> Unit) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        worker.execute {
            val err = try { work(); null } catch (e: Exception) { e.message ?: L.t("משהו השתבש", "Something went wrong") }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (err != null) Toast.makeText(this, err, Toast.LENGTH_LONG).show()
                else { if (done.isNotEmpty()) Toast.makeText(this, done, Toast.LENGTH_SHORT).show(); reload() }
            }
        }
    }

    private fun confirmDelete(p: Community.Post) {
        AlertDialog.Builder(this).setTitle(L.t("למחוק את הרגע מהקהילה?", "Delete this moment from the community?"))
            .setMessage(L.t("הוא יימחק לכולם. הרגע המקורי נשאר אצלכם בטלפון.", "It will be removed for everyone. The original stays on your phone."))
            .setPositiveButton(L.t("מחיקה", "Delete")) { _, _ -> background(L.t("מוחקים...", "Deleting…"), L.t("נמחק", "Deleted")) { Community.delete(applicationContext, p) } }
            .setNegativeButton(L.t("ביטול", "Cancel"), null).show()
    }

    private fun reportDialog(p: Community.Post) {
        AlertDialog.Builder(this).setTitle(L.t("מה הבעיה ברגע הזה?", "What's wrong with this moment?"))
            .setItems(Community.REPORT_REASON_LABELS.toTypedArray()) { _, i ->
                worker.execute {
                    val err = try { Community.report(applicationContext, p.id, Community.REPORT_REASONS[i]); null } catch (e: Exception) { e.message }
                    runOnUiThread { Toast.makeText(this, err ?: L.t("תודה, הדיווח התקבל", "Thanks, we got your report"), Toast.LENGTH_LONG).show() }
                }
            }.setNegativeButton(L.t("ביטול", "Cancel"), null).show()
    }

    private fun confirmBlock(p: Community.Post) {
        AlertDialog.Builder(this).setTitle(L.t("לחסום את ${p.author}?", "Block ${p.author}?"))
            .setMessage(L.t("לא תראו יותר רגעים מהמשתמש הזה.", "You won't see moments from this person anymore."))
            .setPositiveButton(L.t("חסימה", "Block")) { _, _ -> background(L.t("חוסמים...", "Blocking…"), L.t("נחסם", "Blocked")) { Community.block(applicationContext, p.authorId) } }
            .setNegativeButton(L.t("ביטול", "Cancel"), null).show()
    }

    // ---------- comments ----------

    private fun openComments(p: Community.Post, st: State, onCount: (Int) -> Unit) {
        val dialog = BottomSheetDialog(this)
        val me = Account.id(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            setPadding(ui.dp(16), ui.dp(16), ui.dp(16), ui.dp(16))
        }
        root.addView(ui.text(L.t("תגובות", "Comments"), 18f, bold = true))
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(300)).apply { topMargin = ui.dp(8) }
            addView(list)
        }
        root.addView(scroll)
        val input = EditText(this).apply {
            hint = L.t("כתבו תגובה...", "Add a comment…")
            textSize = 15f
            maxLines = 3
            setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(10))
            background = GradientDrawable().apply { cornerRadius = DS.radiusCard(this@CommunityActivity); setColor(DS.buttonSecondary(this@CommunityActivity)) }
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val send = TextView(this).apply {
            text = L.t("שליחה", "Send")
            textSize = 15f
            setTextColor(ContextCompat.getColor(this@CommunityActivity, R.color.accent))
            typeface = DS.emphasis
            setPadding(ui.dp(14), ui.dp(10), ui.dp(4), ui.dp(10))
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(input); row.addView(send)
        root.addView(row)

        var shown = emptyList<Community.Comment>()
        fun draw() {
            list.removeAllViews()
            if (shown.isEmpty()) list.addView(ui.hint(L.t("עוד אין תגובות. היו הראשונים.", "No comments yet. Be the first."), top = 12))
            for (c in shown) {
                val line = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, ui.dp(10), 0, ui.dp(2))
                }
                line.addView(mv.avatar(c.author, 32, byName = false))
                val texts = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPaddingRelative(ui.dp(12), 0, 0, 0)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
                texts.addView(ui.text(c.author, 13f, bold = true))
                texts.addView(ui.text(c.body, 15f, top = 1))
                line.addView(texts)
                if (c.authorId == me || p.authorId == me || Account.isAdmin(this)) {
                    line.setOnLongClickListener {
                        AlertDialog.Builder(this).setTitle(L.t("למחוק את התגובה?", "Delete this comment?"))
                            .setPositiveButton(L.t("מחיקה", "Delete")) { _, _ ->
                                worker.execute {
                                    try { Community.deleteComment(applicationContext, c.id) } catch (_: Exception) {}
                                    runOnUiThread { shown = shown.filter { it.id != c.id }; onCount(shown.size); draw() }
                                }
                            }.setNegativeButton(L.t("ביטול", "Cancel"), null).show()
                        true
                    }
                }
                list.addView(line)
            }
        }
        fun load() {
            worker.execute {
                val r = try { Community.comments(applicationContext, p.id) } catch (_: Exception) { null }
                runOnUiThread {
                    if (r != null) { shown = r; onCount(r.size) }
                    if (!isDestroyed) draw()
                }
            }
        }
        send.setOnClickListener {
            val body = input.text.toString().trim()
            if (body.isEmpty()) return@setOnClickListener
            input.setText("")
            worker.execute {
                val err = try { Community.addComment(applicationContext, p.id, body); null } catch (e: Exception) { e.message }
                runOnUiThread { if (err != null) Toast.makeText(this, err, Toast.LENGTH_LONG).show() else load() }
            }
        }
        draw()
        load()
        dialog.setContentView(root)
        dialog.show()
    }
}

package com.regaim.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import java.util.Calendar
import java.util.concurrent.Executors

/**
 * "בית": the family's home of memories. Opens on "what happened with us?", not on the camera.
 * On this day · today's story · our week (+ weekly video) · filters · latest moments.
 */
class HomeActivity : AppCompatActivity() {

    private lateinit var content: LinearLayout
    private lateinit var fab: ExtendedFloatingActionButton
    private lateinit var mv: MemoryViews
    private lateinit var ui: Ui
    private val worker = Executors.newSingleThreadExecutor()
    private val familyWorker = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private var filter = "הכל"
    private var recentBox: LinearLayout? = null
    private var shownCount = -1
    private var hasRenderedFeed = false
    private var makingReel = false
    private var resumed = false
    private var familyFeed: Circle.Feed? = null
    private var familyFeedUser = ""
    private var familyFeedCircle = ""
    private var familyLoading = false
    private var familyError = ""
    private lateinit var sharedViews: SharedMemoryViews

    private val tick = object : Runnable {
        override fun run() {
            updateFab()
            // New moments while capturing: refresh quietly.
            // Only a count (kept + waiting), one read: a new capture refreshes the page.
            if (MemoryStore.all(this@HomeActivity).size != shownCount) render()
            handler.postDelayed(this, 4000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)
        Settings.load(this)
        content = findViewById(R.id.homeContent)
        // Posts reach the screen edges (negative side margins): don't cut them at the page padding.
        content.clipToPadding = false
        content.clipChildren = false
        fab = findViewById(R.id.fabCapture)
        mv = MemoryViews(this)
        ui = Ui(this)
        sharedViews = SharedMemoryViews(this)
        // Quick capture: one tap opens the camera, ready to record. Holding it opens all the ways to add.
        fab.text = L.t("צלמו", "Record")
        fab.setIconResource(R.drawable.ic_videocam)
        fab.contentDescription = L.t("צילום רגע מהיר", "Quick record")
        fab.setOnClickListener { CaptureNowActivity.open(this, video = true) }
        fab.setOnLongClickListener { newMemory(); true }
        fab.layoutParams = (fab.layoutParams as android.widget.FrameLayout.LayoutParams).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            marginStart = ui.dp(20); marginEnd = ui.dp(20); leftMargin = ui.dp(20); rightMargin = ui.dp(20)
        }
        Nav.setup(this, Nav.Tab.HOME)
        // First launch: the welcome / sign-in screen (it can be skipped).
        // The very first time: three short screens on what Moments is (then sign in). Existing users skip it.
        if (savedInstanceState == null && !IntroActivity.seen(this) && !Account.signedIn(this) && !Account.skipped(this)) IntroActivity.open(this)
        else if (savedInstanceState == null && !Account.signedIn(this) && !Account.skipped(this)) WelcomeActivity.open(this)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        // An invite opened before signing in: continue joining the family circle now.
        if (Account.signedIn(this) && getSharedPreferences("regaim", MODE_PRIVATE).contains("pending_invite"))
            startActivity(Intent(this, CircleActivity::class.java))
        // Queue items nobody answered for 36 hours go to the trash (silence isn't a 👎).
        // The shared gallery of the two parents: new moments go to the other parent (in the background).
        Thread { try { PartnerShare.sync(applicationContext) } catch (_: Exception) { } }.start()
        // A newer version on our server: asks whether to install it.
        UpdatePrompt.maybeAsk(this)
        worker.execute {
            try { MemoryStore.expirePending(applicationContext) } catch (_: Exception) { }
            try { GlobalVotes.refresh(applicationContext) } catch (_: Exception) { } // at most once a day
        }
        render()
        loadFamily()
        handler.post(tick)
        if (ClaudeClient.hasKey(this) || PeopleStore.peopleEnabled(this)) {
            worker.execute {
                val described = try { MemoryStore.analyzePending(applicationContext) } catch (_: Exception) { 0 }
                try { MediaDates.repairOnce(applicationContext) } catch (_: Exception) { }
                val wrote = try { MemoryStore.refreshStories(applicationContext, 2) } catch (_: Exception) { false }
                if (described > 0 || wrote) runOnUiThread { if (!isFinishing && resumed) render() }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        resumed = false
        handler.removeCallbacks(tick)
        if (::mv.isInitialized) mv.stopPreviews(content)
    }

    override fun onDestroy() {
        worker.shutdownNow()
        familyWorker.shutdownNow()
        if (::sharedViews.isInitialized) sharedViews.close()
        super.onDestroy()
    }

    private fun openCapture() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
    }

    /** "+": a proper create sheet (icons, one line each), like the big apps — not a plain list dialog. */
    private fun newMemory() {
        val ui = Ui(this)
        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(16), ui.dp(10), ui.dp(16), ui.dp(24))
        }
        root.addView(View(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = ui.dp(3).toFloat(); setColor(DS.handle(this@HomeActivity)) }
            layoutParams = LinearLayout.LayoutParams(ui.dp(40), ui.dp(5)).apply { gravity = android.view.Gravity.CENTER_HORIZONTAL; bottomMargin = ui.dp(14) }
        })
        root.addView(ui.text(L.t("נוסיף רגע לסיפור שלנו", "Add a moment to our story"), 19f, bold = true).apply { setPadding(ui.dp(8), 0, ui.dp(8), ui.dp(8)) })
        fun item(icon: Int, tint: Int, title: String, sub: String, go: () -> Unit) {
            root.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(ui.dp(8), ui.dp(10), ui.dp(8), ui.dp(10))
                background = DS.pressable(this@HomeActivity, DS.rounded(DS.bg(this@HomeActivity), DS.radiusChip(this@HomeActivity)))
                addView(ImageView(this@HomeActivity).apply {
                    setImageResource(icon)
                    setColorFilter(tint)
                    setPadding(ui.dp(11), ui.dp(11), ui.dp(11), ui.dp(11))
                    background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(DS.cardAlt(this@HomeActivity)) }
                    layoutParams = LinearLayout.LayoutParams(ui.dp(46), ui.dp(46))
                })
                addView(LinearLayout(this@HomeActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(ui.dp(14), 0, 0, 0)
                    addView(ui.text(title, 16f, bold = true))
                    addView(ui.hint(sub, top = 1))
                })
                setOnClickListener { sheet.dismiss(); go() }
            })
        }
        item(R.drawable.ic_auto, DS.primary(this), L.t("תפיסת רגעים אוטומטית", "Auto-capture"), L.t("המצלמה מקשיבה ושומרת רק את הרגעים הטובים", "The camera listens and keeps only the good moments")) { openCapture() }
        item(R.drawable.ic_camera, DS.primary(this), L.t("צילום תמונה", "Take a photo"), L.t("מצלמה מהירה בתוך האפליקציה", "Quick in-app camera")) { CaptureNowActivity.open(this, video = false) }
        item(R.drawable.ic_videocam, DS.primary(this), L.t("צילום סרטון", "Record a video"), L.t("לחיצה ארוכה או הקשה · עד דקה", "Hold or tap · up to a minute")) { CaptureNowActivity.open(this, video = true) }
        item(R.drawable.ic_gallery, DS.primary(this), L.t("מהגלריה", "From your gallery"), L.t("בחירה של כמה תמונות וסרטונים בבת אחת", "Pick several photos and videos at once")) { MediaImportActivity.open(this, pickNow = true) }
        item(R.drawable.ic_videocam, DS.primary(this), L.t("ממצלמה חיצונית", "From an external camera"), L.t("GoPro, משקפיים ועוד: נמצא את הצחוקים בסרטונים", "GoPro, glasses and more: we find the laughs")) { CameraImportActivity.open(this) }
        item(R.drawable.ic_photos, DS.primary(this), L.t("מ-Google Photos", "From Google Photos"), L.t("תמונות ואלבומים מהחשבון שלכם", "Photos and albums from your account")) { GooglePhotosActivity.open(this) }
        sheet.setContentView(root)
        sheet.show()
    }

    private fun loadFamily() {
        if (!Circle.joined(this) || !Account.signedIn(this)) { familyFeed = null; familyError = ""; return }
        if (familyLoading || familyWorker.isShutdown) return
        val requestedCircle = Circle.circleId(this)
        val requestedUser = Account.id(this)
        familyLoading = true
        render()
        familyWorker.execute {
            // What was there last time, at once (like a social feed); the new one replaces it when it arrives.
            if (familyFeed == null) Circle.cachedFeed(applicationContext)?.let { cached ->
                runOnUiThread {
                    if (isFinishing || isDestroyed || familyFeed != null) return@runOnUiThread
                    if (requestedCircle != Circle.circleId(this) || requestedUser != Account.id(this)) return@runOnUiThread
                    familyFeed = cached; familyFeedUser = requestedUser; familyFeedCircle = requestedCircle
                    if (resumed) render()
                }
            }
            val result = try { Result.success(Circle.feed(applicationContext)) } catch (e: Exception) { Result.failure(e) }
            runOnUiThread {
                familyLoading = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (requestedCircle != Circle.circleId(this) || requestedUser != Account.id(this) || !Account.signedIn(this)) {
                    familyFeed = null
                    if (resumed) render()
                    return@runOnUiThread
                }
                result.onSuccess { familyFeed = it; familyFeedUser = requestedUser; familyFeedCircle = requestedCircle; familyError = "" }
                    .onFailure { familyError = L.t("לא הצלחנו להביא את הרגעים של המשפחה. הזיכרונות בטלפון עדיין כאן.", "We couldn't load your family's moments. Your memories on this phone are still here.") }
                if (resumed) render()
            }
        }
    }

    private fun open(m: Memory) = MomentActivity.open(this, m.id)

    private fun updateFab() {
        fab.visibility = View.VISIBLE
        captureBtn?.text = if (Status.running) {
            if (Status.pending) L.t("שומרים רגע…", "Saving a moment…") else L.t("צילום פעיל", "Capturing")
        } else {
            L.t("+  תפסו רגע", "+  Capture")
        }
    }

    // ---------- page ----------

    private fun render() {
        val scroll = findViewById<android.widget.ScrollView>(R.id.homeScroll)
        val previousY = scroll.scrollY
        val all = MemoryStore.kept(this)
        if (!Circle.joined(this) || !Account.signedIn(this) || familyFeedUser != Account.id(this) || familyFeedCircle != Circle.circleId(this)) {
            familyFeed = null
        }
        allCache = all
        val waiting = MemoryStore.pending(this)
        shownCount = all.size + waiting.size
        hasRenderedFeed = false
        content.removeAllViews()
        header()
        updateFab()
        pitzkiCard()
        filmInProgress()
        peopleStrip(all)
        queueCard(waiting)
        val memoryError = Status.error?.takeIf { MemoryStore.isIndexError(it) }
        if (memoryError != null) {
            val notice = ui.card(top = 18)
            notice.addView(ui.text(L.t("לא הצלחנו לפתוח את הזיכרונות כרגע", "We couldn't open your memories right now"), 18f, bold = true))
            notice.addView(ui.hint(MemoryStore.INDEX_ERROR, top = 8))
            notice.addView(ui.button(L.t("נסו שוב", "Try again"), top = 12) { render() })
            content.addView(notice)
        }
        val hasShared = familyFeed?.posts?.isNotEmpty() == true
        if (all.isEmpty() && !hasShared) {
            if (memoryError == null) emptyState()
        } else {
            // Highlights first (on this day, today, the week, what you can make), then the full feed.
            circlesSection(all)
            heroSection(all)
            storySection(all)
            weekSection(all)
            digestCard()
            if (all.isNotEmpty()) studioCard()
            if (all.isEmpty()) filter = "הכל" else filtersRow(all)
            recentSection(all)
        }
        sharedSection()
        scroll.post { scroll.scrollTo(0, previousY) }
    }

    private var captureBtn: TextView? = null

    /** One card, no new tab: "N moments waiting" → the approval queue. */
    private fun queueCard(waiting: List<Memory>) {
        if (waiting.isEmpty()) return
        val card = ui.card(top = 16)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val strip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (m in waiting.take(3)) strip.addView(ui.bubble(m.thumb, 44).apply {
            (layoutParams as? LinearLayout.LayoutParams)?.marginEnd = ui.dp(6)
        })
        row.addView(strip)
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(10), 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        texts.addView(ui.text(if (waiting.size == 1) L.t("רגע אחד מחכה", "1 moment waiting") else L.t("${waiting.size} רגעים מחכים", "${waiting.size} moments waiting"), 16f, bold = true))
        texts.addView(ui.hint(L.t("נשמרו לבד. 👍 לשמור, 👎 לא — וכך Moments לומד.", "Saved automatically. 👍 keep, 👎 not — that's how Moments learns."), top = 2))
        row.addView(texts)
        card.addView(row)
        card.setOnClickListener { ReviewActivity.open(this) }
        content.addView(card)
    }

    /** Thursday to Saturday: the week's digest for the grandparents, one tap away. */
    private fun digestCard() {
        val day = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)
        if (day != Calendar.THURSDAY && day != Calendar.FRIDAY && day != Calendar.SATURDAY) return
        val week = Digest.moments(this)
        if (week.size < 2) return
        val card = ui.card(top = 16)
        card.addView(ui.row(L.t("סיכום השבוע לסבא וסבתא", "This week's digest for the grandparents"), L.t("${week.size} רגעים", "${week.size} moments"), top = 0) { DigestActivity.open(this) })
        card.addView(ui.hint(L.t("סרטון או קולאז׳ של השבוע, מוכן לשליחה בוואטסאפ.", "A video or collage of the week, ready for WhatsApp."), top = 0))
        content.addView(card)
    }

    private fun header() {
        // Row 1: the wordmark and the profile picture. Row 2: the family's title and the capture button.
        // (Two rows, so the wordmark never runs into the button on a narrow phone or with a long family name.)
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        // The wordmark: "Moments" in an elegant serif (Minimal Luxury), the same in every language.
        top.addView(ui.text("Moments", 30f, R.color.ds_ink).apply {
            typeface = DS.logo
            letterSpacing = 0.01f
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        Nav.addProfileButton(this, top)
        content.addView(top)

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, mv.dp(6), 0, 0)
        }
        val titles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        titles.addView(ui.heading(Profile.homeTitle(this), 24f, top = 0).apply {
            if (Profile.familyName(this@HomeActivity).isEmpty() && Profile.parentName(this@HomeActivity).isEmpty()) {
                setOnClickListener { startActivity(Intent(this@HomeActivity, ProfileActivity::class.java)) }
            }
        })
        val people = Profile.members(this).count { !it.isPet }
        titles.addView(ui.text(
            if (people > 0) L.t("הבית שלנו · $people נפשות", "Our home · $people people") else L.t("הזיכרונות שלנו, תמיד איתנו", "Our memories, always with us"),
            16f, R.color.ink_soft, top = 4,
        ))
        row.addView(titles)
        val btn = TextView(this).apply {
            textSize = 15f
            gravity = Gravity.CENTER
            maxLines = 1
            setTextColor(DS.onButton(this@HomeActivity))
            typeface = DS.emphasis
            background = DS.pressable(this@HomeActivity, DS.rounded(DS.button(this@HomeActivity), DS.radiusButton(this@HomeActivity)), onDark = true)
            minHeight = mv.dp(44)
            setPadding(mv.dp(16), mv.dp(10), mv.dp(16), mv.dp(10))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = mv.dp(10) }
            setOnClickListener { newMemory() }
        }
        captureBtn = btn
        row.addView(btn)
        content.addView(row)
    }

    /** The family as round profile pictures (a ring means something new this week). Tap one to see their moments. */
    private fun peopleStrip(all: List<Memory>) {
        val weekAgo = System.currentTimeMillis() - 7 * DateUtils.DAY_IN_MILLIS
        val members = Profile.members(this)
        val named = (members.map { it.name } + PeopleStore.people(this).map { it.name } + all.flatMap { Fmt.names(it) })
            .map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (named.isEmpty()) return
        fun count(n: String) = all.count { Fmt.names(it).contains(n) }
        fun fresh(n: String) = all.any { it.time >= weekAgo && Fmt.names(it).contains(n) }
        val ordered = named.sortedByDescending { count(it) }.take(14)
        val strip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (n in ordered) {
            strip.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(mv.dp(78), ViewGroup.LayoutParams.WRAP_CONTENT)
                addView(mv.avatar(n, 66, ring = fresh(n)))
                addView(TextView(this@HomeActivity).apply {
                    text = n
                    textSize = 13f
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    gravity = Gravity.CENTER
                    setTextColor(ContextCompat.getColor(this@HomeActivity, R.color.ink))
                    setPadding(0, mv.dp(6), 0, 0)
                })
                if (count(n) > 0) addView(TextView(this@HomeActivity).apply {
                    text = if (count(n) == 1) L.t("רגע אחד", "1 moment") else L.t("${count(n)} רגעים", "${count(n)} moments")
                    textSize = 11f
                    gravity = Gravity.CENTER
                    setTextColor(ContextCompat.getColor(this@HomeActivity, R.color.ink_soft))
                })
                setOnClickListener {
                    if (count(n) > 0) startActivity(Intent(this@HomeActivity, MemoriesActivity::class.java)
                        .putExtra(MemoriesActivity.EXTRA_PERSON, n).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                    else Toast.makeText(this@HomeActivity, L.t("עדיין אין רגעים עם $n", "No moments with $n yet"), Toast.LENGTH_SHORT).show()
                }
            })
        }
        strip.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(mv.dp(78), ViewGroup.LayoutParams.WRAP_CONTENT)
            addView(TextView(this@HomeActivity).apply {
                text = "+"
                textSize = 26f
                gravity = Gravity.CENTER
                setTextColor(ContextCompat.getColor(this@HomeActivity, R.color.ink_soft))
                setBackgroundResource(R.drawable.dashed_circle)
                layoutParams = LinearLayout.LayoutParams(mv.dp(66), mv.dp(66))
            })
            addView(TextView(this@HomeActivity).apply {
                text = L.t("הוסיפו", "Add")
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(ContextCompat.getColor(this@HomeActivity, R.color.ink_soft))
                setPadding(0, mv.dp(6), 0, 0)
            })
            setOnClickListener { startActivity(Intent(this@HomeActivity, ProfileActivity::class.java)) }
        })
        content.addView(ui.scrollRow(strip, top = 22))
    }

    /**
     * The latest moments in circles, like a WhatsApp video message: the one in view plays its heart silently;
     * a tap opens the moment, with sound.
     */
    private fun circlesSection(all: List<Memory>) {
        val recent = all.sortedByDescending { it.time }.take(12)
        if (recent.isEmpty()) return
        content.addView(mv.sectionTitle(L.t("רגעים אחרונים", "Latest moments"), top = 22))
        val strip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, mv.dp(4), 0, mv.dp(4)) }
        for (m in recent) strip.addView(mv.roundClip(m, 112) { open(m) })
        content.addView(ui.scrollRow(strip, top = 10))
    }

    private fun familyEntry() {
        content.addView(ui.row(L.t("המעגל המשפחתי", "Family circle"), if (Circle.joined(this)) Circle.name(this) else L.t("הזמינו את המשפחה", "Invite your family"), top = 16) {
            startActivity(Intent(this, FamilyActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        })
        content.addView(ui.hint(L.t("הזיכרונות בטלפון שלכם. אתם בוחרים מה לשתף.", "Your memories live on your phone. You choose what to share."), top = 0))
    }

    private fun sharedSection() {
        if (!Circle.joined(this)) return
        val showingInFeed = hasRenderedFeed && filter == "הכל"
        if (!showingInFeed) content.addView(mv.sectionTitle(L.t("מהמשפחה, בשבילנו", "From the family, for us"), top = 24))
        when {
            familyError.isNotEmpty() -> {
                content.addView(ui.hint(familyError, top = 8))
                content.addView(ui.action(L.t("נסו שוב", "Try again")) { loadFamily(); render() })
            }
            familyFeed == null -> content.addView(ui.hint(if (familyLoading) L.t("רגע, מביאים את הזיכרונות של המשפחה…", "Loading your family's memories…") else L.t("פתחו את המעגל המשפחתי כדי לראות מה חדש.", "Open your family circle to see what's new."), top = 8))
            else -> {
                val feed = familyFeed ?: return
                if (feed.posts.isEmpty()) {
                    content.addView(ui.hint(L.t("עדיין לא שיתפתם זיכרונות במעגל. הרגע הראשון שלכם יכול לפתוח שיחה.", "No memories shared in your circle yet. Your first one could start a conversation."), top = 8))
                } else if (!showingInFeed) feed.posts.take(4).forEach { post -> content.addView(sharedViews.card(post, feed) { loadFamily() }) }
            }
        }
        content.addView(ui.action(L.t("לכל הזיכרונות במעגל ›", "All circle memories ›")) { CircleActivity.open(this) })
    }

    private fun emptyState() {
        val card = ui.card(top = 28)
        card.setPadding(mv.dp(24), mv.dp(32), mv.dp(24), mv.dp(28))
        card.gravity = Gravity.CENTER_HORIZONTAL
        card.addView(android.widget.ImageView(this).apply { setImageResource(R.drawable.ic_heart_outline); setColorFilter(androidx.core.content.ContextCompat.getColor(this@HomeActivity, R.color.ink)); layoutParams = android.widget.LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)).apply { gravity = android.view.Gravity.CENTER_HORIZONTAL } })
        card.addView(ui.text(L.t("עדיין אין לנו זיכרונות", "No memories yet"), 22f, bold = true, top = 8).apply { gravity = Gravity.CENTER })
        card.addView(ui.text(L.t("ברגע שיקרה משהו מיוחד, נתחיל לבנות את הסיפור המשפחתי שלכם.", "As soon as something special happens, we'll start building your family's story."), 16f, R.color.ink_soft, top = 8).apply { gravity = Gravity.CENTER })
        // The plain camera first (what "capture" means to a new parent); automatic capture is offered next to it.
        card.addView(ui.button(L.t("צלמו את הרגע הראשון", "Capture your first moment"), primary = true, top = 22) { CaptureNowActivity.open(this, video = false) })
        card.addView(ui.action(L.t("או: לתת לטלפון לתפוס לבד רגעי צחוק ›", "Or: let the phone catch laughing moments on its own ›")) { openCapture() }.apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = ui.dp(4) }
        })
        card.addView(ui.button(L.t("הוסיפו תמונה או סרטון", "Add a photo or video"), top = 8) { MediaImportActivity.open(this) })
        card.addView(ui.button(L.t("הפכו סרטון מהגלריה לסרט מצויר", "Turn a gallery video into an animated film"), top = 8) { StudioActivity.open(this) })
        card.addView(ui.hint(L.t("אפשר לצלם, לשמור רגע ביד או לבחור זיכרון שכבר נמצא בטלפון.", "Take a photo, save a moment by hand, or pick a memory that's already on your phone."), top = 12).apply { gravity = Gravity.CENTER })
        if (Profile.isEmpty(this)) {
            card.addView(ui.action(L.t("בינתיים, ספרו לנו מי במשפחה ›", "Meanwhile, tell us who's in your family ›")) { startActivity(Intent(this, ProfileActivity::class.java)) }.apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = mv.dp(8)
                    gravity = Gravity.CENTER_HORIZONTAL
                }
            })
        }
        content.addView(card)
        mv.reveal(card)
    }

    // ---------- studio ----------

    /** A 3D film being made right now: the way back to it, with how far it got. */
    /**
     * Pitzki, up front: moving (his forest loop, without sound) with two quick ways in, a walk or a talk. The
     * highlight of the app for the kids.
     */
    private var pitzkiShown = false

    private fun pitzkiCard() {
        val w = resources.displayMetrics.widthPixels - ui.dp(32)
        // Taller with a large phone font, so the title and the two buttons aren't cut.
        val h = (ui.dp(190) * resources.configuration.fontScale.coerceIn(1f, 1.6f)).toInt()
        val box = android.widget.FrameLayout(this).apply {
            background = DS.rounded(android.graphics.Color.parseColor("#2E4A26"), ui.dp(24).toFloat())
            clipToOutline = true
            elevation = ui.dp(4).toFloat()
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h).apply { topMargin = ui.dp(14) }
            contentDescription = L.t("פיצקי", "Pitzki")
            setOnClickListener { PitzkiActivity.open(this@HomeActivity) }
        }
        // His picture at once; his moving loop on it when it's on the phone (the talk's place, or his song).
        val still = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        box.addView(still, android.widget.FrameLayout.LayoutParams(-1, -1))
        // Pitzki himself, always moving (the parent's choice): his place as the picture under the moving loop.
        val pic = PitzkiStore.sharedScene(this, PitzkiStore.CHAT).takeIf { it.exists() } ?: PitzkiStore.sharedScene(this, "song").takeIf { it.exists() }
        // On its own thread: behind the other background work (the daily votes over the network) it could wait long,
        // leaving an empty green card. A picture that can't be read falls back to Pitzki's own.
        Thread {
            val b = try { pic?.let { android.graphics.BitmapFactory.decodeFile(it.absolutePath, android.graphics.BitmapFactory.Options().apply { inSampleSize = 2 }) } } catch (_: Throwable) { null }
                ?: try { PitzkiStore.art(applicationContext, "pitzki") } catch (_: Throwable) { null }
            runOnUiThread { if (!isFinishing && b != null) still.setImageBitmap(b) }
        }.start()
        // Him looking at you first (the listening loop), else talking, else his song.
        val loop = PitzkiStore.sharedClip(this, PitzkiStore.CHAT_LISTEN).takeIf { it.exists() && it.length() > 0 }
            ?: PitzkiStore.sharedClip(this, PitzkiStore.CHAT).takeIf { it.exists() && it.length() > 0 }
            ?: PitzkiStore.talkTest(this).takeIf { it.exists() && it.length() > 0 }
        if (loop != null) {
            // Wide video, a little cut at the top and bottom to fill the card.
            // (A TextureView: a VideoView left a black block while the page scrolls.)
            val video = FillVideoView(this).apply { alpha = 0f; muted = true }
            box.addView(video, android.widget.FrameLayout.LayoutParams(w, maxOf(h, w * 9 / 16), Gravity.CENTER))
            video.onReady = { video.animate().alpha(1f).setDuration(400).start() }
            video.onFailed = { video.visibility = View.GONE }
            video.play(loop.absolutePath)
        } else {
            // No loop yet: he sways gently.
            still.animate().scaleX(1.06f).scaleY(1.06f).setDuration(4000).start()
        }
        // The words and the two ways in, over a soft shadow at the bottom.
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(16), ui.dp(28), ui.dp(16), ui.dp(12))
            background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(android.graphics.Color.argb(200, 0, 0, 0), android.graphics.Color.TRANSPARENT))
        }
        bottom.addView(TextView(this).apply {
            text = L.t("🧝 פיצקי מחכה לכם!", "🧝 Pitzki is waiting for you!")
            textSize = 20f; typeface = DS.heading; setTextColor(android.graphics.Color.WHITE)
        })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, ui.dp(8), 0, 0) }
        fun chip(label: String, primary: Boolean, go: () -> Unit) = TextView(this).apply {
            text = label; textSize = 15f; typeface = DS.heading
            setTextColor(if (primary) android.graphics.Color.WHITE else android.graphics.Color.parseColor("#2E2A24"))
            gravity = Gravity.CENTER; minHeight = ui.dp(48)
            setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(8))
            background = DS.rounded(if (primary) DS.button(this@HomeActivity) else android.graphics.Color.argb(235, 255, 252, 245), ui.dp(20).toFloat())
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = ui.dp(8) }
            setOnClickListener { go() }
        }
        row.addView(chip(L.t("🌳 יוצאים לטיול", "🌳 Go for a walk"), true) { PitzkiActivity.open(this) })
        row.addView(chip(L.t("💬 לדבר עם פיצקי", "💬 Talk with Pitzki"), false) {
            val kid = PitzkiStore.children(this).firstOrNull()
            if (kid != null) PitzkiChatActivity.open(this, kid.memberId) else PitzkiActivity.open(this)
        })
        bottom.addView(row)
        box.addView(bottom, android.widget.FrameLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        // A small hello: he pops in (once, not on every refresh of the screen).
        if (!pitzkiShown) {
            pitzkiShown = true
            box.scaleX = 0.96f; box.scaleY = 0.96f; box.alpha = 0f
            box.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(450).setInterpolator(android.view.animation.OvershootInterpolator()).start()
        }
        content.addView(box)
    }

    private fun filmInProgress() {
        val s = CartoonFilm.current?.takeIf { it.isActive || it.state == CartoonFilm.Session.State.DONE } ?: return
        val done = s.state == CartoonFilm.Session.State.DONE
        val card = ui.card(top = 16)
        card.addView(ui.text(if (done) L.t("הסרט המצויר מוכן", "Your animated film is ready") else L.t("סרט מצויר בהכנה · ${s.estimate()}%", "Animated film in progress · ${s.estimate()}%"), 16f, bold = true))
        card.addView(ui.hint(if (s.state == CartoonFilm.Session.State.LOOK) L.t("מחכה לאישור שלכם: איך הדמויות נראות", "Waiting for you: approve how the characters look") else L.t("לחצו כדי לראות", "Tap to see it"), top = 2))
        card.setOnClickListener { CartoonFilmActivity.open(this, s.memory.id) }
        content.addView(card)
    }

    /** The way in to everything you can make: a 3D animated film first, then comics and videos. */
    private fun studioCard() {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.card_white)
            elevation = 0f
            setPadding(mv.dp(20), mv.dp(18), mv.dp(20), mv.dp(18))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = mv.dp(18) }
            setOnClickListener { StudioActivity.open(this@HomeActivity) }
        }
        card.addView(TextView(this).apply {
            text = L.t("סטודיו", "Studio")
            textSize = 13f
            typeface = DS.emphasis
            setTextColor(ContextCompat.getColor(this@HomeActivity, R.color.accent_dark))
        })
        card.addView(ui.text(L.t("הפכו רגע לסרט מצויר בתלת-ממד", "Turn a moment into a 3D animated film"), 20f, bold = true, top = 4))
        card.addView(ui.hint(L.t("דמויות אנימציה בסגנון סרטי פיקסאר, עם מוזיקה. וגם: סרטונים מהשבוע.", "Pixar-style animated characters, with music. Also: videos of your week."), top = 4))
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = mv.dp(14) }
        }
        fun pill(label: String, primary: Boolean, weight: Float, go: () -> Unit) = TextView(this).apply {
            text = label
            textSize = 14f
            gravity = Gravity.CENTER
            typeface = DS.emphasis
            setTextColor(ContextCompat.getColor(this@HomeActivity, if (primary) R.color.white else R.color.ink))
            background = DS.buttonBackground(this@HomeActivity, primary)
            setPadding(mv.dp(10), mv.dp(11), mv.dp(10), mv.dp(11))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight).apply { marginEnd = mv.dp(8) }
            setOnClickListener { go() }
        }
        row.addView(pill(L.t("צרו סרט מצויר", "Make a film"), true, 1.3f) { StudioActivity.makeFilm(this) })
        row.addView(pill(L.t("עוד יצירות ›", "More ›"), false, 1f) { StudioActivity.open(this) })
        card.addView(row)
        content.addView(card)
    }

    // ---------- on this day ----------

    /** "היום לפני שנה" when there is one; until there's enough history, the closest meaningful fallback. */
    private fun pickHero(all: List<Memory>): Pair<String, Memory>? {
        val now = Calendar.getInstance()
        fun cal(t: Long) = Calendar.getInstance().apply { timeInMillis = t }
        val sameDay = all.filter {
            val c = cal(it.time)
            it.timeSource != "imported" && c.get(Calendar.MONTH) == now.get(Calendar.MONTH) && c.get(Calendar.DAY_OF_MONTH) == now.get(Calendar.DAY_OF_MONTH) &&
                c.get(Calendar.YEAR) < now.get(Calendar.YEAR)
        }
        ReelMaker.best(sameDay).firstOrNull()?.let { m ->
            val years = now.get(Calendar.YEAR) - cal(m.time).get(Calendar.YEAR)
            return (if (years == 1) L.t("היום לפני שנה", "One year ago today") else L.t("היום לפני $years שנים", "$years years ago today")) to m
        }
        val monthAgo = all.filter {
            val c = cal(it.time)
            it.timeSource != "imported" && c.get(Calendar.DAY_OF_MONTH) == now.get(Calendar.DAY_OF_MONTH) && !DateUtils.isToday(it.time) &&
                System.currentTimeMillis() - it.time in 25 * DateUtils.DAY_IN_MILLIS..35 * DateUtils.DAY_IN_MILLIS
        }
        ReelMaker.best(monthAgo).firstOrNull()?.let { return L.t("היום לפני חודש", "One month ago today") to it }
        val loved = all.filter { (it.favorite || it.milestone.isNotEmpty() || it.chosen) && System.currentTimeMillis() - it.time > 2 * DateUtils.DAY_IN_MILLIS }
        if (loved.isNotEmpty()) return L.t("רגעים שאהבתם · לחצו לצפייה ברצף", "Moments you loved · tap to play") to loved[now.get(Calendar.DAY_OF_YEAR) % loved.size]
        return null
    }

    private fun heroSection(all: List<Memory>) {
        val (label, m) = pickHero(all) ?: return
        val loved = all.filter { it.favorite || it.milestone.isNotEmpty() || it.chosen }
        // "Moments you loved": a tap plays them all, one after another, like a story.
        val playAll = loved.size > 1 && label.startsWith(L.t("רגעים שאהבתם", "Moments you loved"))
        val v = mv.hero(label, m) {
            if (playAll) StoryActivity.of(this, L.t("רגעים שאהבתם", "Moments you loved"), (listOf(m) + ReelMaker.best(loved).filter { it.id != m.id }).take(15).map { it.id })
            else open(m)
        }
        content.addView(v)
        mv.reveal(v)
    }

    // ---------- today's story ----------

    private fun storySection(all: List<Memory>) {
        val today = all.filter { DateUtils.isToday(it.time) }
        if (today.isEmpty()) return
        val story = MemoryStore.story(this, today.first().dayKey)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.card_white)
            setPadding(mv.dp(18), mv.dp(16), mv.dp(18), mv.dp(18))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = mv.dp(16) }
        }
        card.addView(TextView(this).apply {
            text = (if (today.any { it.timeSource == "imported" }) L.t("היום בזיכרונות שלנו", "Today in our memories") else L.t("היום אצלנו", "Today with us")) + L.t(" · ${today.size} רגעים", " · ${today.size} moments")
            textSize = 13f
            typeface = DS.emphasis
            setTextColor(ContextCompat.getColor(this@HomeActivity, R.color.accent_dark))
        })
        val body = TextView(this).apply {
            textSize = 16f
            setLineSpacing(mv.dp(4).toFloat(), 1f)
            setTextColor(ContextCompat.getColor(this@HomeActivity, R.color.ink))
            setPadding(0, mv.dp(6), 0, 0)
        }
        when {
            story.isNotEmpty() -> body.text = story
            !ClaudeClient.hasKey(this) -> body.text = L.t("כל הרגעים מהיום נמצאים כאן. אפשר לחבר הבנת רגעים בהגדרות הנוספות בפרופיל.", "All of today's moments are here. You can turn on moment understanding in the extra settings in your profile.")
            today.size < 2 -> body.text = L.t("נשמר רגע אחד היום. מעוד רגע, נכתוב כאן את הסיפור של היום.", "One moment saved today. With one more, we'll write today's story here.")
            else -> {
                body.text = L.t("רגע, אנחנו כותבים את הסיפור של היום...", "Writing today's story...")
                mv.pulse(body)
            }
        }
        card.addView(body)
        card.addView(TextView(this).apply {
            text = L.t("צפו בסיפור של היום", "Watch today's story")
            textSize = 14f
            typeface = DS.emphasis
            setTextColor(ContextCompat.getColor(this@HomeActivity, R.color.accent_dark))
            setPadding(0, mv.dp(12), 0, 0)
        })
        card.setOnClickListener { StoryActivity.day(this, today.first().dayKey) }
        content.addView(card)
    }

    // ---------- our week ----------

    private fun weekSection(all: List<Memory>) {
        val week = ReelMaker.weekMoments(this)
        if (week.isEmpty()) return
        val imported = week.any { it.timeSource == "imported" }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.card_accent_bg)
            setPadding(mv.dp(24), mv.dp(22), mv.dp(24), mv.dp(22))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = mv.dp(22) }
        }
        card.addView(TextView(this).apply {
            text = if (imported) L.t("השבוע בזיכרונות שלנו", "This week in our memories") else L.t("השבוע שלנו", "Our week")
            textSize = 14f
            setTextColor(DS.onPhoto(this@HomeActivity, 0.75f))
        })
        card.addView(TextView(this).apply {
            text = if (week.size == 1) L.t("רגע אחד נשמר", "1 moment saved") else L.t("${week.size} רגעים נשמרו", "${week.size} moments saved")
            textSize = 30f
            typeface = Fonts.display
            setTextColor(ContextCompat.getColor(this@HomeActivity, R.color.white))
            setPadding(0, mv.dp(8), 0, 0)
        })
        card.addView(TextView(this).apply {
            text = L.t("אפשר לראות את הסיפור של השבוע, או לשלוח אותו כסרטון.", "Watch the story of the week, or send it as a video.")
            textSize = 15f
            setTextColor(DS.onPhoto(this@HomeActivity, 0.85f))
            setPadding(0, mv.dp(6), 0, mv.dp(16))
        })
        val thumbs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        ReelMaker.best(week).take(5).forEachIndexed { i, m ->
            val size = mv.dp(52)
            thumbs.addView(android.widget.ImageView(this).apply {
                scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(DS.primary(this@HomeActivity)); setStroke(mv.dp(2), DS.cardAlt(this@HomeActivity))
                }
                clipToOutline = true
                Covers.load(this, m)
                setOnClickListener { open(m) }
                layoutParams = LinearLayout.LayoutParams(size, size).apply { if (i > 0) marginStart = -mv.dp(12) }
            })
        }
        card.addView(thumbs)
        fun pill(label: String, go: () -> Unit) = TextView(this).apply {
            text = label
            textSize = 15f
            gravity = Gravity.CENTER
            typeface = DS.emphasis
            setTextColor(ContextCompat.getColor(this@HomeActivity, R.color.ink))
            background = DS.buttonBackground(this@HomeActivity, primary = false)
            setPadding(mv.dp(16), mv.dp(13), mv.dp(16), mv.dp(13))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = mv.dp(12) }
            setOnClickListener { go() }
        }
        card.addView(pill(L.t("הסיפור של השבוע", "This week's story")) { StoryActivity.week(this) }.apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = mv.dp(18)
        })
        card.addView(pill(L.t("סרטון עם מוזיקה: שבוע או חודש", "Video with music: week or month")) { PeriodVideoActivity.open(this) })
        val last = ReelMaker.lastReel(this)
        if (last != null) {
            card.addView(TextView(this).apply {
                text = L.t("הסרטון ששמרתם מהשבוע ›", "Your saved video from this week ›")
                textSize = 14f
                setTextColor(DS.onPhoto(this@HomeActivity, 0.85f))
                setPadding(0, mv.dp(14), 0, 0)
                setOnClickListener { playUri(last.first) }
            })
        }
        content.addView(card)
    }

    private fun makeReel(week: List<Memory>) {
        if (makingReel) return
        makingReel = true
        render()
        worker.execute {
            val result = try {
                Result.success(ReelMaker.make(applicationContext, week))
            } catch (e: Throwable) {
                Result.failure(e)
            }
            runOnUiThread {
                makingReel = false
                if (isFinishing) return@runOnUiThread
                render()
                result.onSuccess { r ->
                    Toast.makeText(this, L.t("הסרטון של השבוע מוכן: ${r.count} רגעים ❤️", "Your weekly video is ready: ${r.count} moments ❤️"), Toast.LENGTH_SHORT).show()
                    playUri(r.uri)
                }.onFailure { e ->
                    Toast.makeText(this, e.message ?: L.t("לא הצלחתי ליצור את הסרטון", "Couldn't create the video"), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun playUri(uri: String) {
        try {
            val u = if (uri.startsWith("content://")) Uri.parse(uri) else Uri.parse("file://$uri")
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(u, "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (_: Exception) {
            Toast.makeText(this, L.t("הסרטון נשמר בגלריה, בתיקייה Movies/Moments", "The video was saved to your gallery, in Movies/Moments"), Toast.LENGTH_LONG).show()
        }
    }

    // ---------- filters + latest ----------

    private fun filterOptions(all: List<Memory>): List<String> {
        val people = all.flatMap { Fmt.names(it) }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(4).map { it.key }
        val opts = mutableListOf("הכל")
        opts.addAll(people)
        if (all.any { it.category == "משפחה" || Fmt.names(it).size >= 2 }) opts.add("👨‍👩‍👦 משפחה")
        if (all.any { it.place.isNotEmpty() }) opts.add("📍 מקומות")
        if (all.any { it.favorite }) opts.add("⭐ מועדפים")
        if (all.any { it.category == "צחוק" }) opts.add("😂 צחוקים")
        if (all.any { it.milestone.isNotEmpty() }) opts.add("★ אבני דרך")
        return opts
    }

    private fun applyFilter(all: List<Memory>, f: String): List<Memory> = when (f) {
        "הכל" -> all
        "👨‍👩‍👦 משפחה" -> all.filter { it.category == "משפחה" || Fmt.names(it).size >= 2 }
        "📍 מקומות" -> all.filter { it.place.isNotEmpty() }
        "⭐ מועדפים" -> all.filter { it.favorite }
        "😂 צחוקים" -> all.filter { it.category == "צחוק" }
        "★ אבני דרך" -> all.filter { it.milestone.isNotEmpty() }
        else -> all.filter { Fmt.names(it).contains(f) }
    }

    /** Display text for a filter key (keys stay Hebrew for the logic above; people's names are shown as-is). */
    private fun filterLabel(f: String): String = when (f) {
        "הכל" -> L.t("הכל", "All")
        "👨‍👩‍👦 משפחה" -> L.t("משפחה", "Family")
        "📍 מקומות" -> L.t("מקומות", "Places")
        "⭐ מועדפים" -> L.t("אהבנו", "Loved")
        "😂 צחוקים" -> L.t("צחוקים", "Laughs")
        "★ אבני דרך" -> L.t("אבני דרך", "Milestones")
        else -> f
    }

    private var showCount = 8
    private var allCache: List<Memory> = emptyList()

    private fun filtersRow(all: List<Memory>) {
        val opts = filterOptions(all)
        if (filter !in opts) filter = "הכל"
        if (opts.size > 1) {
            content.addView(ui.chips(opts.map { filterLabel(it) }, opts.indexOf(filter), top = 16) { i ->
                filter = opts[i]
                showCount = 8
                fillRecent(allCache, animate = true)
            })
        }
    }

    private fun recentSection(all: List<Memory>) {
        hasRenderedFeed = true
        allCache = all
        content.addView(mv.sectionTitle(L.t("מהחיים שלנו", "From our life"), top = 26))
        recentBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
        content.addView(recentBox)
        fillRecent(all, animate = false)
    }

    private fun fillRecent(all: List<Memory>, animate: Boolean) {
        val box = recentBox ?: return
        box.removeAllViews()
        val filtered = applyFilter(all, filter)
        val feed = familyFeed
        val localIds = all.map { it.id }.toSet()
        val shared = if (filter == "הכל") feed?.posts.orEmpty().filter { it.memoryId.isBlank() || it.memoryId !in localIds } else emptyList()
        val items = (filtered.map { Triple(it.time, it, null as Circle.Post?) } +
            shared.map { Triple(it.createdAt.takeIf { t -> t > 0 } ?: it.takenAt, null as Memory?, it) })
            .sortedByDescending { it.first }
        if (items.isEmpty()) box.addView(ui.hint(L.t("עוד אין כאן רגעים.", "No moments here yet."), top = 14))
        items.take(showCount).forEach { (_, memory, post) ->
            if (memory != null) box.addView(mv.card(memory, bleedDp = 20) { open(memory) })
            else if (post != null && feed != null) box.addView(sharedViews.card(post, feed) { loadFamily() })
        }
        if (animate) mv.reveal(box)
        if (items.size > showCount) {
            box.addView(ui.action(L.t("עוד זיכרונות ↓", "More memories ↓")) {
                showCount += 8
                fillRecent(allCache, animate = false)
            }.apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = DS.radiusCard(this@HomeActivity); setColor(DS.bg(this@HomeActivity)); setStroke(mv.dp(1), DS.line(this@HomeActivity))
                }
                setPadding(mv.dp(20), mv.dp(10), mv.dp(20), mv.dp(10))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = mv.dp(18)
                    gravity = Gravity.CENTER_HORIZONTAL
                }
            })
        }
    }
}

package com.regaim.app

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.Executors

/**
 * "The magic forest with Pitzki": a colors lesson for ages 3–5 where the child walks with Pitzki.
 * The child comes from what the family already has: the character approved in a book, or their photos (drawn as a
 * character first, which the parent approves). Then the forest scenes are drawn with the child in them and brought to
 * life (kept on the phone, made again only when the child's look changes).
 */
class PitzkiActivity : AppCompatActivity() {
    companion object {
        fun open(ctx: Context) = ctx.startActivity(Intent(ctx, PitzkiActivity::class.java))
    }

    private lateinit var ui: Ui
    private lateinit var voice: PitzkiVoice
    private lateinit var ears: PitzkiEars
    private val main = Handler(Looper.getMainLooper())
    private val bg = Executors.newSingleThreadExecutor()
    /**
     * Downloads (the shared scenes from the server) on their own thread: on [bg] they held up the pictures for minutes
     * (a black screen in the lesson, empty thumbnails) while every missing file was tried.
     */
    private val net = Executors.newSingleThreadExecutor()
    private var destroyed = false

    private var children: List<PitzkiStore.Child> = emptyList()
    private var chosen: PitzkiStore.Child? = null
    private val kid: PitzkiKid get() = chosen?.kid ?: PitzkiKid("", false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        ui = Ui(this)
        voice = PitzkiVoice(this)
        ears = PitzkiEars(this)
        PitzkiStore.upgrade(this)
        PitzkiScript.worldId = (Settings.prefs(this).getString("pitzki_world", "forest") ?: "forest")
            .let { if (it == NumbersWorld.ID) PlayWorld.ID else it } // the numbers are part of the games now
        children = PitzkiStore.children(this)
        chosen = children.firstOrNull()
        // An animation cut off when the app was closed: picked up again (what was already sent isn't paid again).
        children.firstOrNull { PitzkiStore.interrupted(this, it) }?.let { PitzkiStore.resume(this, it); chosen = it }
        setup()
        // The other parent's children (and their Pitzki films) may be on their way: fetched now, shown when they arrive.
        if (PartnerShare.partner(this).isNotEmpty()) Thread {
            try { PartnerShare.sync(applicationContext) } catch (_: Exception) {}
            main.post {
                if (destroyed || inLesson) return@post
                val now = PitzkiStore.children(this)
                if (now.map { it.memberId } != children.map { it.memberId }) { children = now; if (chosen == null) chosen = now.firstOrNull(); renderSetup() }
            }
        }.start()
    }

    private var resumedOnce = false

    override fun onResume() {
        super.onResume()
        PitzkiService.screenOpen = true
        PitzkiService.clearNotice(this)
        // Back from outside the app while a film was being made: the screen catches up.
        if (resumedOnce && !inLesson) {
            // Back from the family profile: a child added there shows up at once.
            val now = PitzkiStore.children(this)
            if (now.map { it.memberId } != children.map { it.memberId }) { children = now; if (chosen == null) chosen = now.firstOrNull() }
            renderSetup(); watchDrawing()
        }
        if (!inLesson) try { heroVideo?.start() } catch (_: Exception) { }
        if (inLesson && recordingWalk) selfie?.start()
        resumedOnce = true
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // The camera allowed after the recorded walk already started: the round picture appears.
        if (requestCode == 77 && recordingWalk && checkSelfPermission(android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED)
            selfie?.let { it.visibility = View.VISIBLE; it.start() }
    }

    override fun onPause() {
        super.onPause()
        PitzkiService.screenOpen = false
        stopWaitSong()
        if (inLesson && !paused) pause()
        selfie?.stop()
    }

    override fun onDestroy() {
        try { if (recordingWalk) { recordingWalk = false; WalkRecordService.stop() } } catch (_: Exception) {}
        selfie?.stop()
        destroyed = true
        stopWaitSong()
        ambient(null)
        token++
        main.removeCallbacksAndMessages(null)
        voice.shutdown()
        ears.stop()
        stopSong()
        stopClip()
        clipIo.shutdown()
        bg.shutdownNow()
        net.shutdownNow()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (inLesson) { stopLesson(); setup() }
        else if (showSettings) { showSettings = false; renderSetup(); scrollTop() }
        else super.onBackPressed()
    }

    // =====================================================================
    // The start screen (for the parent)
    // =====================================================================

    private var setupBox: LinearLayout? = null

    private fun setup() {
        inLesson = false
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val scroll = ScrollView(this).apply { setBackgroundColor(DS.bg(this@PitzkiActivity)); isFillViewport = true }
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(20), ui.dp(12), ui.dp(20), ui.dp(32))
        }
        // The title and Pitzki moving at the top stay put (the rest below is drawn again as things change).
        outer.addView(topBar())
        outer.addView(hero())
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        outer.addView(box)
        scroll.addView(outer)
        setupBox = box
        setContentView(scroll)
        renderSetup()
        watchDrawing()
        // Pitzki's shared close-ups: fetched quietly in the background (the lesson works without them).
        net.execute {
            val before = PitzkiStore.sharedReady(applicationContext)
            try { PitzkiStore.fetchShared(applicationContext) } catch (_: Throwable) {}
            if (PitzkiStore.sharedReady(applicationContext) != before) main.post { if (!destroyed && !inLesson && setupBox === box) renderSetup() }
        }
    }

    private var setupTitle: TextView? = null

    private fun topBar(): View {
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(TextView(this).apply {
            text = "✕"; textSize = 20f; setTextColor(DS.ink(this@PitzkiActivity))
            setPadding(ui.dp(4), ui.dp(8), ui.dp(16), ui.dp(8))
            contentDescription = L.t("סגירה", "Close")
            setOnClickListener { finish() }
        })
        setupTitle = ui.text("", 18f).apply { typeface = DS.emphasis }
        top.addView(setupTitle)
        return top
    }

    /**
     * Pitzki himself, moving (the same loop as on the home screen: him looking at you, else talking, else his song,
     * without sound); his face picture until it's on the phone.
     */
    private fun hero(): View {
        val hero = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(230)).apply { topMargin = ui.dp(8) }
            background = DS.rounded(DS.card(this@PitzkiActivity), DS.radiusMedia(this@PitzkiActivity))
            clipToOutline = true
        }
        hero.addView(ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            PitzkiStore.art(this@PitzkiActivity, "face")?.let { setImageBitmap(it) }
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        })
        val loop = PitzkiStore.sharedClip(this, PitzkiStore.CHAT_LISTEN).takeIf { it.exists() && it.length() > 0 }
            ?: PitzkiStore.sharedClip(this, PitzkiStore.CHAT).takeIf { it.exists() && it.length() > 0 }
            ?: PitzkiStore.talkTest(this).takeIf { it.exists() && it.length() > 0 }
        if (loop != null) {
            // A TextureView (not a VideoView): it scrolls and keeps the card's round corners; a VideoView left a black
            // block at the top of the screen while scrolling.
            val video = FillVideoView(this).apply { alpha = 0f; muted = true }
            hero.addView(video, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            video.onReady = { video.animate().alpha(1f).setDuration(400).start() }
            video.onFailed = { video.visibility = View.GONE }
            video.play(loop.absolutePath)
            heroVideo = video
        }
        return hero
    }

    /** Started again when the screen comes back (a video view stops when the screen is left). */
    private var heroVideo: FillVideoView? = null

    /** The settings page (the child's film, photos, microphone, admin), instead of the simple start page. */
    private var showSettings = false

    /** Something about the child's film needs the parent now (shown on the start page too): a drawing on, an approval. */
    private fun needsParent(c: PitzkiStore.Child): Boolean =
        PitzkiStore.drawing?.let { !it.finished && (it.member == c.memberId || it.member.endsWith(c.memberId)) } == true ||
            PitzkiStore.candidate(this, c.memberId).exists() || PitzkiStore.heroCandidate(this, c.memberId).exists() ||
            PitzkiStore.awaitingIntro(this, c) ||
            (PitzkiScript.CHILD_KEYS.any { PitzkiStore.clipFile(this, c.memberId, it).exists() } && !PitzkiStore.reviewed(this, c.memberId))

    private fun renderSetup() {
        val box = setupBox ?: return
        box.removeAllViews()
        if (showSettings) { renderSettings(box); return }
        bigPhoto(box)
        setupTitle?.text = if (PitzkiScript.isNumbers) L.t("סופרים עם פיצקי", "Counting with Pitzki") else if (PitzkiScript.isPlay) L.t("משחקים עם פיצקי", "Games with Pitzki") else if (PitzkiScript.isNight) L.t("לילה טוב עם פיצקי", "Good night with Pitzki") else if (PitzkiScript.isFarm) L.t("החווה של פיצקי", "Pitzki's farm") else if (PitzkiScript.isTrain) L.t("תחנת הרכבת של פיצקי", "Pitzki's train station") else L.t("היער של פיצקי", "Pitzki's forest")
        box.addView(ui.heading(L.t("שלום, אני פיצקי", "Hi, I'm Pitzki"), 28f, top = 14))
        box.addView(ui.text(L.t("טיולים קצרים עם פיצקי, והילד/ה שלכם בתוך הסיפור. לגילאי 3–5, בלי פרסומות ובלי צ׳אט.",
            "Short trips with Pitzki, with your child in the story. Ages 3–5, no ads, no chat."), 15f, R.color.ink_soft, top = 6))
        box.addView(worldPicker())
        // The level in this world (when it has levels).
        PitzkiLevels.max(PitzkiScript.worldId).takeIf { it > 1 }?.let { max ->
            val lv = chosen?.let { PitzkiStore.level(this, it.memberId, PitzkiScript.worldId) } ?: 1
            box.addView(ui.text(L.t("⭐ רמה $lv מתוך $max · טיול עם ציון 100 עולה רמה", "⭐ Level $lv of $max · a walk with 100 moves up"), 14f, R.color.ink_soft, top = 8))
        }

        // Who goes on the walk.
        if (children.isNotEmpty()) {
            box.addView(ui.section(L.t("מי יוצא לטיול?", "Who's coming?")))
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, ui.dp(10), 0, 0) }
            for (c in children) row.addView(childChip(c))
            reorderable(row)
            box.addView(ui.scrollRow(row, top = 0))
        } else {
            // A new family: the first thing to do, said plainly, with the button that does it.
            val first = ui.card(top = 14)
            first.addView(ui.text(L.t("👶 קודם כל: מי הילד שיוצא לטיול?", "👶 First: who's the child going on the walk?"), 17f).apply { typeface = DS.heading })
            first.addView(ui.hint(L.t("מוסיפים את הילד בפרופיל המשפחה (שם, בן או בת, תאריך לידה). אז פיצקי פונה אליו בשם, ואפשר גם לצייר אותו בתוך הטיולים.",
                "Add the child in the family profile (name, boy or girl, birthday). Then Pitzki calls them by name, and they can be drawn into the walks."), top = 4))
            first.addView(ui.button(L.t("➕ להוסיף ילד", "➕ Add a child"), primary = true, top = 10) {
                ParentGate.ask(this) { startActivity(Intent(this, ProfileActivity::class.java)) }
            })
            first.addView(ui.hint(L.t("אפשר גם לצאת לטיול כבר עכשיו, בלי שם.", "You can also go on a walk right now, without a name."), top = 6))
            box.addView(first)
        }

        // The child's film: here only when it needs the parent; everything else is in the settings.
        chosen?.let { c ->
            PitzkiVoice.sayName = c.kid.name to PitzkiStore.sayName(this, c.memberId)
            if (needsParent(c)) box.addView(childCard(c))
            else if (PitzkiScript.CHILD_KEYS.any { PitzkiStore.clipFile(this, c.memberId, it).exists() })
                box.addView(ui.hint(L.t("✓ ${c.kid.name} ${if (c.kid.girl) "מצוירת" else "מצויר"} בתוך הטיול", "✓ ${c.kid.name} is in the walk"), top = 14))
        }
        // Pitzki's voice for this child, made ahead in the background (quietly: the phone's voice until it's ready).
        PitzkiStore.prepareVoice(this, kid)

        // Go.
        box.addView(ui.button(L.t("יוצאים לטיול ›", "Let's go ›"), primary = true, top = 22) {
            // The child's own scenes not gone over yet: the parent is asked once (they can go anyway).
            val c = chosen
            val unseen = c != null && PitzkiScript.CHILD_KEYS.any { PitzkiStore.clipFile(this, c.memberId, it).exists() } && !PitzkiStore.reviewed(this, c.memberId)
            if (!unseen) startLesson() else AlertDialog.Builder(this)
                .setTitle(L.t("עוד לא עברתם על הסצנות", "You haven't gone over the scenes"))
                .setMessage(L.t("כדאי לצפות קודם בסצנות של ${c!!.kid.name}, כדי לתקן מה שלא נראה טוב לפני שהילד רואה. לצאת בכל זאת?",
                    "It's worth watching ${c!!.kid.name}'s scenes first, to fix what looks wrong before the child sees it. Go anyway?"))
                .setPositiveButton(L.t("לצאת בכל זאת", "Go anyway")) { _, _ -> PitzkiStore.setReviewed(this, c.memberId); startLesson() }
                .setNegativeButton(L.t("לעבור על הסצנות", "Go over the scenes"), null)
                .show()
        })
        // Pitzki's teaching film (made once for every family): when it's on the phone.
        if (PitzkiStore.episodeReady(this)) box.addView(ui.button(L.t("🍎 סרט: פיצקי מלמד אחד ועוד אחד", "🍎 Film: Pitzki teaches one and one"), primary = false, top = 10) { EpisodeActivity.open(this) })
        // A walk recorded to share (the grandparents): the screen, Pitzki and the child's answers.
        box.addView(ui.button(L.t("🎥 טיול מוקלט לשיתוף", "🎥 A recorded walk to share"), primary = false, top = 10) { ParentGate.ask(this) { askToRecord() } })
        // A pilot: the child talks with Pitzki (questions, pictures to tap, his voice). The parent starts it.
        box.addView(ui.button(L.t("💬 לדבר עם פיצקי (ניסיון)", "💬 Talk with Pitzki (pilot)"), primary = false, top = 10) {
            AlertDialog.Builder(this)
                .setTitle(L.t("שיחה עם פיצקי", "Talking with Pitzki"))
                .setMessage(L.t("פיצקי ישאל כמה שאלות קצרות (צבעים, חיות, בטיחות, נימוס…), יקשיב ויענה, ואחר כך תור הילד לספר לו דברים ולשאול אותו. אפשר לענות בקול או בלחיצה על תמונה. כ-5 דקות, עם הורה ליד. " +
                    "מה שהילד אומר עובר רק דרך זיהוי הדיבור של הטלפון, ולא נשמר. פיצקי עונה מתוך תשובות שנכתבו מראש, בלי AI, ולא חוזר על מילים לא יפות. בסוף תראו מה דיברו.",
                    "Pitzki asks a few short questions (colors, animals, safety, manners…), listens and answers; then it's the child's turn to tell him things and ask him. Answer out loud or tap a picture. About 5 minutes, with a parent nearby. " +
                    "What the child says goes only through the phone's speech recognition and isn't saved. Pitzki answers from replies written ahead, with no AI, and never repeats rude words. You'll see what was said at the end."))
                .setPositiveButton(L.t("להתחיל", "Start")) { _, _ -> PitzkiChatActivity.open(this, chosen?.memberId.orEmpty()) }
                .setNegativeButton(L.t("לא עכשיו", "Not now"), null)
                .show()
        })
        val stars = chosen?.let { PitzkiStore.stars(this, it.memberId) } ?: 0
        val done = chosen?.let { PitzkiStore.lessonsDone(this, it.memberId) } ?: 0
        if (done > 0) box.addView(ui.hint(L.t("⭐ $stars כוכבים · $done טיולים", "⭐ $stars stars · $done walks"), top = 8).apply { gravity = Gravity.CENTER })
        box.addView(ui.button(chosen?.let { L.t("⚙️ הגדרות והסרט של ${it.kid.name}", "⚙️ Settings and ${it.kid.name}'s film") } ?: L.t("⚙️ הגדרות", "⚙️ Settings"),
            primary = false, top = 14) { ParentGate.ask(this) { showSettings = true; renderSetup(); scrollTop() } })
        // Said once the phone's voices are known: no Hebrew voice means subtitles only.
        main.postDelayed({
            if (!destroyed && !inLesson && !voice.heOk && setupBox === box) box.addView(ui.hint(
                L.t("בטלפון הזה אין קול בעברית, אז פיצקי ידבר בכתוביות. אפשר להוסיף קול עברי בהגדרות הטלפון ‹ נגישות ‹ פלט טקסט לדיבור.",
                    "This phone has no Hebrew voice, so Pitzki will speak in subtitles. You can add a Hebrew voice in the phone's text-to-speech settings."), top = 12))
        }, 1500)
    }

    /**
     * The steps to the child's films, in order, with what's done (✓) and one button for the next step: clear photos,
     * the film character (approved), then the films of each world (with about what each costs). Everything stays
     * where it was below; this only shows the way.
     */
    private fun setupSteps(c: PitzkiStore.Child): View {
        val card = ui.card(top = 12)
        val name = c.kid.name
        card.addView(ui.text(L.t("🧭 ההכנה של $name", "🧭 Getting $name ready"), 16f).apply { typeface = DS.emphasis })
        val photos = PitzkiStore.facePhotos(this, c.memberId).isNotEmpty()
        val hero = c.look?.hero == true
        val waitingHero = PitzkiStore.heroCandidate(this, c.memberId).exists()
        fun line(done: Boolean, text: String) = card.addView(ui.text((if (done) "✓ " else "○ ") + text, 15f, top = 6))
        line(photos, L.t("1. תמונות ברורות של הפנים", "1. Clear photos of the face"))
        line(hero, L.t("2. הדמות של $name לסרט (מאשרים שהיא דומה)", "2. $name's film character (approved as alike)"))
        val worlds = PitzkiScript.WORLDS.map { w ->
            val keys = PitzkiScript.childKeysOf(w)
            val made = keys.count { PitzkiStore.clipFile(this, c.memberId, it).exists() }
            Triple(w, keys.size, made)
        }
        val names = mapOf("forest" to L.t("היער", "The forest"), TrainWorld.ID to L.t("הרכבת", "The train"), FarmWorld.ID to L.t("החווה", "The farm"),
            NightWorld.ID to L.t("לילה טוב", "Good night"), PlayWorld.ID to L.t("משחקים", "Games"))
        line(worlds.all { it.third >= it.second }, L.t("3. הסרטים בכל עולם:", "3. The films of each world:"))
        for ((w, all, made) in worlds) card.addView(ui.hint("     " + (if (made >= all) "✓ " else "○ ") + names[w] + " · " +
            (if (made >= all) L.t("מוכן", "ready") else if (!Account.isAdmin(this)) L.t("$made מתוך $all סצנות", "$made of $all scenes") else L.t("$made מתוך $all סצנות · כ-${"%.1f".format(java.util.Locale.US, (all - made) * 0.5)}$", "$made of $all scenes · about $${"%.1f".format(java.util.Locale.US, (all - made) * 0.5)}")), top = 2))
        // The next step, one button.
        val next = worlds.firstOrNull { it.third < it.second }
        when {
            !photos -> card.addView(ui.button(L.t("📸 הצעד הבא: לצלם את $name", "📸 Next: photograph $name"), primary = true, top = 10) { faceCamera.launch(FaceCaptureActivity.intent(this, c.memberId, name)) })
            waitingHero -> card.addView(ui.hint(L.t("👇 הצעד הבא: לאשר את הדמות של $name (למטה)", "👇 Next: approve $name's character (below)"), top = 10))
            !hero -> card.addView(ui.button(price(L.t("🎨 הצעד הבא: ליצור את הדמות (כ-25 סנט)", "🎨 Next: create the character (~25¢)")), primary = true, top = 10) {
                if (PitzkiStore.makeHero(this, c)) { renderSetup(); watchDrawing() }
            })
            next != null && next.first != PitzkiScript.worldId -> card.addView(ui.button(L.t("➡️ הצעד הבא: הסרט ב${names[next.first]}", "➡️ Next: the film in ${names[next.first]}"), primary = true, top = 10) {
                PitzkiScript.worldId = next.first
                Settings.prefs(this).edit().putString("pitzki_world", next.first).apply()
                renderSetup(); scrollTop()
            })
            next != null -> card.addView(ui.button(L.t("🎬 הצעד הבא: ליצור את הסרט ב${names[next.first]}", "🎬 Next: make the film in ${names[next.first]}"), primary = true, top = 10) {
                confirmDraw(c, again = false, animate = true)
            })
            else -> card.addView(ui.hint(L.t("🎉 הכול מוכן. אפשר לצאת לטיול!", "🎉 All ready. Off on a walk!"), top = 10))
        }
        if (Account.isAdmin(this)) card.addView(ui.hint(price(L.t("העלויות משוערות (ציור והנפשה של 5 שניות לסצנה).", "Costs are estimates (a drawing and a 5-second animation per scene).")), top = 6))
        return card
    }

    /** "What the child knows": each subject with what's known, still learned, not met yet; the levels; the walks. */
    private fun knowsDialog(c: PitzkiStore.Child) {
        val m = PitzkiStore.memory(this, c.memberId)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(20), ui.dp(8), ui.dp(20), ui.dp(8)) }
        box.addView(ui.hint(L.t("לפי התשובה הראשונה לכל שאלה בטיולים: ✓ יודע · 🌱 עוד לומד · · עוד לא נשאל", "By the first answer to each question in the walks: ✓ knows · 🌱 still learning · · not asked yet")))
        for (sec in PitzkiKnows.sections(m)) {
            val asked = sec.items.count { it.state != PitzkiKnows.State.NOT_YET }
            box.addView(ui.text(sec.title + if (asked > 0) "  ${sec.known}/${sec.items.size}" else "", 16f, top = 12).apply { typeface = DS.emphasis })
            box.addView(ui.text(sec.items.joinToString("   ") { it.label + " " + when (it.state) { PitzkiKnows.State.KNOWN -> "✓"; PitzkiKnows.State.LEARNING -> "🌱"; else -> "·" } }, 15f, top = 4))
        }
        // The levels of the worlds that have them.
        val levels = listOf(FarmWorld.ID to L.t("החווה", "The farm"), PlayWorld.ID to L.t("משחקים", "Games"))
            .map { (id, name) -> "$name: " + L.t("רמה ${PitzkiStore.level(this, c.memberId, id)} מתוך ${PitzkiLevels.max(id)}", "level ${PitzkiStore.level(this, c.memberId, id)} of ${PitzkiLevels.max(id)}") }
        box.addView(ui.text(L.t("⭐ רמות", "⭐ Levels"), 16f, top = 12).apply { typeface = DS.emphasis })
        box.addView(ui.text(levels.joinToString("\n"), 15f, top = 4))
        box.addView(ui.hint(L.t("${PitzkiStore.lessonsDone(this, c.memberId)} טיולים · ${PitzkiStore.stars(this, c.memberId)} כוכבים", "${PitzkiStore.lessonsDone(this, c.memberId)} walks · ${PitzkiStore.stars(this, c.memberId)} stars"), top = 10))
        AlertDialog.Builder(this)
            .setTitle(L.t("מה ${c.kid.name} ${if (c.kid.girl) "יודעת" else "יודע"}", "What ${c.kid.name} knows"))
            .setView(android.widget.ScrollView(this).apply { addView(box) })
            .setPositiveButton(L.t("סגירה", "Close"), null)
            .show()
    }

    /** The children's row takes a dragged child: dropped on another child, it takes that one's place. Saved. */
    private fun reorderable(row: LinearLayout) {
        row.setOnDragListener { _, e ->
            when (e.action) {
                android.view.DragEvent.ACTION_DRAG_STARTED -> e.localState is String
                android.view.DragEvent.ACTION_DROP -> {
                    val id = e.localState as? String ?: return@setOnDragListener false
                    // The child under the finger (or the nearest one).
                    val target = (0 until row.childCount).minByOrNull { i -> val v = row.getChildAt(i); kotlin.math.abs(v.left + v.width / 2f - e.x) } ?: return@setOnDragListener false
                    val ids = children.map { it.memberId }.toMutableList()
                    val from = ids.indexOf(id)
                    if (from >= 0 && from != target) {
                        ids.removeAt(from); ids.add(target.coerceIn(0, ids.size), id)
                        PitzkiStore.setKidsOrder(this, ids)
                        children = PitzkiStore.children(this)
                    }
                    true
                }
                android.view.DragEvent.ACTION_DRAG_ENDED -> { main.post { if (!destroyed) renderSetup() }; true }
                else -> true
            }
        }
    }

    private fun childChip(c: PitzkiStore.Child): View {
        val on = chosen?.memberId == c.memberId
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(ui.dp(6), 0, ui.dp(6), 0)
            alpha = if (on) 1f else 0.6f
            setOnClickListener { chosen = c; renderSetup() }
            tag = c.memberId
            // A long press picks the child up, to drag to another place in the row (see [reorderable]).
            if (children.size > 1) setOnLongClickListener { v ->
                v.startDragAndDrop(android.content.ClipData.newPlainText("kid", c.memberId), View.DragShadowBuilder(v), c.memberId, 0)
                v.alpha = 0.3f
                true
            }
        }
        col.addView(lookCircle(c, 64, ring = on))
        col.addView(ui.text(c.kid.name, 14f, R.color.ink, top = 4).apply {
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            if (on) typeface = DS.emphasis
        })
        return col
    }

    /** The child's look in a circle (book character, or photo), or their initial. */
    private fun lookCircle(c: PitzkiStore.Child, sizeDp: Int, ring: Boolean): View {
        val size = ui.dp(sizeDp)
        val frame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(size, size)
            background = DS.oval(if (ring) DS.button(this@PitzkiActivity) else DS.line(this@PitzkiActivity))
        }
        val inset = ui.dp(if (ring) 3 else 1)
        val bmp = c.look?.let { Avatars.photo(it.file.absolutePath, size * 2) }
        if (bmp != null) frame.addView(ImageView(this).apply {
            setImageBitmap(bmp)
            scaleType = ImageView.ScaleType.CENTER_CROP
            outlineProvider = RoundVideo.OVAL
            clipToOutline = true
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply { setMargins(inset, inset, inset, inset) }
        }) else {
            val (bgc, fg) = DS.avatar(this, c.kid.name)
            frame.addView(TextView(this).apply {
                text = Avatars.initial(c.kid.name)
                gravity = Gravity.CENTER
                textSize = sizeDp / 2.6f
                typeface = DS.heading
                setTextColor(fg)
                background = DS.oval(bgc)
                layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply { setMargins(inset, inset, inset, inset) }
            })
        }
        return frame
    }

    /** What Pitzki knows about the child: their look, and whether the forest scenes are drawn with them. */
    /**
     * How many films this account can make, and how many changes are left in the film being made now (each film
     * credit: the whole film, and about 4 scenes made again, for a week).
     */
    private fun filmCredits(card: LinearLayout) {
        val line = ui.hint(L.t("🎟️ בודקים קרדיטים…", "🎟️ Checking credits…"), top = 4)
        card.addView(line)
        net.execute {
            val st = CartoonFilm.filmStatus(applicationContext)
            main.post {
                if (destroyed) return@post
                line.text = when {
                    st == null -> L.t("🎟️ לא הצלחנו לבדוק קרדיטים כרגע", "🎟️ Couldn't check credits right now")
                    Account.isAdmin(this) -> L.t("🎟️ מנהל: ללא הגבלת ניסיונות", "🎟️ Admin: no limit on tries")
                    else -> L.t("🎟️ סרטים שאפשר עוד ליצור: ${st.first}", "🎟️ Films you can still make: ${st.first}") +
                        (st.second?.let { left ->
                            val until = st.third.take(10).split('-').let { if (it.size == 3) "${it[2]}/${it[1]}" else "" }
                            L.t(" · בסרט הפתוח נשארו בערך ${left / 2} שינויים" + (if (until.isNotEmpty()) " (עד $until)" else ""),
                                " · about ${left / 2} changes left in the open film" + (if (until.isNotEmpty()) " (until $until)" else ""))
                        } ?: "")
                }
            }
        }
    }

    /** Whose face photos the picker is choosing. */
    // ---------- a recorded walk, to share with the grandparents ----------

    /** This walk is being recorded (the watermark shows, and an end card closes it). */
    private var recordingWalk = false
    private val recordAsk = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { r ->
        val data = r.data
        if (r.resultCode != RESULT_OK || data == null) {
            android.widget.Toast.makeText(this, L.t("ההקלטה לא אושרה. אפשר לצאת לטיול רגיל.", "Recording wasn't allowed. You can go on a normal walk."), android.widget.Toast.LENGTH_LONG).show()
            return@registerForActivityResult
        }
        WalkRecordService.onFailed = { why ->
            recordingWalk = false
            if (::watermark.isInitialized) watermark.visibility = View.GONE
            selfie?.let { it.stop(); it.visibility = View.GONE }
            android.widget.Toast.makeText(this, L.t("ההקלטה לא הצליחה להתחיל. הטיול ממשיך בלי הקלטה.", "The recording couldn't start. The walk goes on without it.") + if (why.isBlank()) "" else " ($why)", android.widget.Toast.LENGTH_LONG).show()
        }
        recordingWalk = true
        startLesson()
        // Once the screen turned sideways: the recording starts (the first second is the song's start).
        main.postDelayed({ if (!destroyed && inLesson && recordingWalk) WalkRecordService.start(this, r.resultCode, data) }, 900)
    }

    private fun askToRecord() {
        AlertDialog.Builder(this)
            .setTitle(L.t("🎥 טיול מוקלט", "🎥 A recorded walk"))
            .setMessage(L.t("הטיול יוקלט מההתחלה ועד הסוף: המסך, הקול של פיצקי והקול של ${kid.name.ifBlank { "הילד" }} כשעונים. בסוף הסרטון יישמר בגלריה ותוכלו לשתף אותו (למשל עם סבא וסבתא). הוא נשמר רק בטלפון הזה עד שתשתפו. הטלפון יבקש אישור להקליט את המסך.",
                "The walk is recorded from start to end: the screen, Pitzki's voice and the child's answers. At the end it's saved in the gallery and you can share it (e.g. with the grandparents). It stays on this phone until you share it. The phone will ask to allow screen recording."))
            .setPositiveButton(L.t("להקליט", "Record")) { _, _ ->
                // The microphone (Pitzki hears the child; the app's sound in the video) and the camera (the round picture).
                val need = arrayOf(android.Manifest.permission.RECORD_AUDIO, android.Manifest.permission.CAMERA)
                    .filter { checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
                if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), 77)
                try { recordAsk.launch(getSystemService(android.media.projection.MediaProjectionManager::class.java).createScreenCaptureIntent()) }
                catch (_: Exception) { android.widget.Toast.makeText(this, L.t("הטלפון לא מאפשר הקלטת מסך", "This phone doesn't allow screen recording"), android.widget.Toast.LENGTH_LONG).show() }
            }
            .setNegativeButton(L.t("ביטול", "Cancel"), null)
            .show()
    }

    /** The recording stopped: saved to the gallery, and the parent can share it. */
    private fun endRecording(share: Boolean) {
        if (!recordingWalk) return
        recordingWalk = false
        if (::watermark.isInitialized) watermark.visibility = View.GONE
        selfie?.let { it.stop(); it.visibility = View.GONE }
        val f = WalkRecordService.stop()
        if (f == null) {
            // Never quietly: the parent waited for a video.
            AlertDialog.Builder(this)
                .setTitle(L.t("הסרטון לא נשמר 😕", "The video wasn't saved 😕"))
                .setMessage(L.t("משהו השתבש בהקלטה, ולכן אין סרטון מהטיול הזה.", "Something went wrong with the recording, so there's no video of this walk.") +
                    WalkRecordService.problem.let { if (it.isBlank()) "" else "\n\n" + L.t("הסיבה: ", "Why: ") + it })
                .setPositiveButton(L.t("הבנתי", "OK"), null)
                .show()
            return
        }
        bg.execute {
            val ok = try { ReelMaker.publish(applicationContext, f, "Pitzki_${kid.name.filter { it.isLetterOrDigit() }}_${System.currentTimeMillis()}.mp4"); true } catch (_: Exception) { false }
            main.post {
                if (destroyed) return@post
                if (!share) { android.widget.Toast.makeText(this, if (ok) L.t("הסרטון נשמר בגלריה, באלבום Moments ✓", "The video is saved in the gallery, in the Moments album ✓") else L.t("לא הצלחנו לשמור את הסרטון בגלריה", "Couldn't save the video to the gallery"), android.widget.Toast.LENGTH_LONG).show(); return@post }
                AlertDialog.Builder(this)
                    .setTitle(L.t("הסרטון מוכן 🎬", "The video is ready 🎬"))
                    .setMessage(if (ok) L.t("נשמר בגלריה, בתיקייה Moments. לשתף עכשיו?", "Saved in the gallery, in the Moments folder. Share it now?") else L.t("לשתף עכשיו?", "Share it now?"))
                    .setPositiveButton(L.t("לשתף", "Share")) { _, _ -> ParentGate.ask(this) { shareVideo(f) } }
                    .setNegativeButton(L.t("אחר כך", "Later"), null)
                    .show()
            }
        }
    }

    private fun shareVideo(f: java.io.File) {
        try {
            val dir = java.io.File(cacheDir, "share").apply { mkdirs() }
            val copy = java.io.File(dir, f.name)
            if (!copy.exists()) f.copyTo(copy, overwrite = true)
            val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.files", copy)
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM, uri)
                .putExtra(Intent.EXTRA_TEXT, L.t("${kid.name} בטיול עם פיצקי 💚 (Moments)", "${kid.name} on a walk with Pitzki 💚 (Moments)"))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), L.t("לשתף את הטיול", "Share the walk")))
        } catch (_: Exception) {
            android.widget.Toast.makeText(this, L.t("לא הצלחנו לשתף", "Couldn't share"), android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    /** The end card that closes a recorded walk (the watermark at the end, like the short-video apps). */
    private fun endCard(then: () -> Unit) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setBackgroundColor(android.graphics.Color.parseColor("#2E2620"))
            alpha = 0f
        }
        card.addView(TextView(this).apply { text = "💚"; textSize = 54f; gravity = Gravity.CENTER })
        card.addView(TextView(this).apply {
            text = if (kid.named) L.t("${kid.name} ופיצקי", "${kid.name} and Pitzki") else L.t("טיול עם פיצקי", "A walk with Pitzki")
            textSize = 30f; typeface = DS.heading; gravity = Gravity.CENTER; setTextColor(android.graphics.Color.WHITE)
        })
        card.addView(TextView(this).apply {
            text = "Moments"; textSize = 22f; typeface = DS.emphasis; gravity = Gravity.CENTER
            setTextColor(android.graphics.Color.parseColor("#F3D9B1")); setPadding(0, ui.dp(10), 0, 0)
        })
        card.addView(TextView(this).apply {
            text = L.t("נוצר באפליקציית Moments", "Made with the Moments app"); textSize = 14f; gravity = Gravity.CENTER
            setTextColor(android.graphics.Color.argb(200, 255, 255, 255)); setPadding(0, ui.dp(4), 0, 0)
        })
        stage.addView(card, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        card.animate().alpha(1f).setDuration(500).start()
        main.postDelayed({
            if (destroyed) return@postDelayed
            then()
            stage.removeView(card)
        }, 3000)
    }

    /** A photo with Pitzki the family made elsewhere (chosen from the gallery). */
    private var ownPhotoFor: String? = null
    private val ownPhotoPicker = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()) { uri ->
        val member = ownPhotoFor
        ownPhotoFor = null
        if (member == null || uri == null) return@registerForActivityResult
        bg.execute {
            val ok = PitzkiStore.addOwnPhoto(this, member, uri)
            main.post {
                if (destroyed) return@post
                android.widget.Toast.makeText(this, if (ok) L.t("התמונה נשמרה ✓", "Photo saved ✓") else L.t("לא הצלחנו לפתוח את התמונה", "Couldn't open the photo"), android.widget.Toast.LENGTH_SHORT).show()
                if (!inLesson) renderSetup()
            }
        }
    }

    /**
     * The newest photo of the chosen child with Pitzki, big, right under Pitzki moving: the first thing seen. Tap: full
     * screen, share, delete.
     */
    private fun bigPhoto(box: LinearLayout) {
        val c = chosen ?: return
        val f = PitzkiStore.photos(this, c.memberId).firstOrNull() ?: return
        val frame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(230)).apply { topMargin = ui.dp(10) }
            background = DS.rounded(DS.card(this@PitzkiActivity), DS.radiusMedia(this@PitzkiActivity))
            clipToOutline = true
            contentDescription = L.t("${c.kid.name} ופיצקי", "${c.kid.name} and Pitzki")
            setOnClickListener {
                showMedia(f, null, L.t("${c.kid.name} ופיצקי", "${c.kid.name} and Pitzki"),
                    L.t("לשתף", "Share") to { shareImage(f) },
                    L.t("למחוק", "Delete") to { f.delete(); renderSetup() })
            }
        }
        val img = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        frame.addView(img, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // The name, over a soft shadow at the bottom.
        frame.addView(View(this).apply {
            background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(0x88000000.toInt(), 0x00000000))
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(70), Gravity.BOTTOM))
        frame.addView(TextView(this).apply {
            text = L.t("${c.kid.name} ופיצקי 💚", "${c.kid.name} and Pitzki 💚")
            textSize = 17f; typeface = DS.emphasis
            setTextColor(DS.onPhoto(this@PitzkiActivity))
            setShadowLayer(8f, 0f, 2f, DS.scrim(this@PitzkiActivity))
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = ui.dp(10) })
        box.addView(frame)
        bg.execute {
            val b = try { BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = 2 }) } catch (_: Throwable) { null }
            main.post { if (!destroyed && b != null) img.setImageBitmap(b) }
        }
    }

    /** Back from the guided photo: the child's new face photos are shown. */
    private val faceCamera = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) {
        children = PitzkiStore.children(this)
        chosen = children.firstOrNull { c -> c.memberId == chosen?.memberId } ?: chosen
        if (!inLesson) renderSetup()
    }

    private var facesFor: String? = null
    private val facePicker = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia(3)) { uris ->
        val member = facesFor
        facesFor = null
        if (member == null || uris.isNullOrEmpty()) return@registerForActivityResult
        bg.execute {
            val n = PitzkiStore.saveFacePhotos(this, member, uris)
            main.post {
                if (destroyed) return@post
                children = PitzkiStore.children(this)
                chosen = children.firstOrNull { it.memberId == member } ?: chosen
                android.widget.Toast.makeText(this, if (n > 0) L.t("נשמרו $n תמונות ✓", "$n photos saved ✓") else L.t("לא הצלחנו לפתוח את התמונות", "Couldn't open the photos"), android.widget.Toast.LENGTH_SHORT).show()
                if (!inLesson) renderSetup()
            }
        }
    }

    /**
     * Up to 3 clear photos of the child's face, chosen by the parent: the child is drawn from them (the best likeness).
     * Then the film can be drawn again from them; the parent approves the first scene before the rest is made.
     */
    private fun facePhotos(card: LinearLayout, c: PitzkiStore.Child) {
        val name = c.kid.name
        val faces = PitzkiStore.facePhotos(this, c.memberId)
        stepTitle(card, "1", L.t("תמונות של $name", "Photos of $name"), faces.isNotEmpty(), top = 6)
        if (faces.isEmpty()) {
            card.addView(ui.hint(L.t("3 תמונות של הפנים: מקדימה, באור טוב, בלי כובע. מהן מציירים את $name.",
                "3 photos of the face: from the front, good light, no hat. $name is drawn from them."), top = 2))
        } else {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, ui.dp(6), 0, 0) }
            for (f in faces) {
                val img = ImageView(this).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    background = DS.rounded(DS.buttonSecondary(this@PitzkiActivity), DS.radiusChip(this@PitzkiActivity))
                    clipToOutline = true
                    layoutParams = LinearLayout.LayoutParams(ui.dp(64), ui.dp(64)).apply { marginEnd = ui.dp(6) }
                }
                row.addView(img)
                bg.execute {
                    val b = try { BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = 4 }) } catch (_: Throwable) { null }
                    main.post { if (!destroyed && b != null) img.setImageBitmap(b) }
                }
            }
            card.addView(row)
        }
        fun pickGallery() {
            facesFor = c.memberId
            try {
                facePicker.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly))
            } catch (_: Exception) { facesFor = null }
        }
        if (faces.isEmpty()) {
            // Best: photographed now, with guidance (the selfie camera takes three pictures by itself).
            card.addView(ui.button(L.t("📸 לצלם את $name עכשיו", "📸 Photograph $name now"), primary = true, top = 8) { faceCamera.launch(FaceCaptureActivity.intent(this, c.memberId, name)) })
            card.addView(ui.hint(L.t("המצלמה מדריכה ומצלמת לבד כשהפנים במקום.", "The camera guides you and takes the pictures by itself."), top = 2))
            val other = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            other.addView(ui.action(L.t("🖼️ או לבחור תמונות מהגלריה", "🖼️ Or choose photos from the gallery")) { pickGallery() })
            // Or (nothing chosen yet) the profile pictures the app already has.
            val fromProfile = c.photos
            if (fromProfile.isNotEmpty()) other.addView(ui.action(L.t("🙂 או להשתמש בתמונות מהפרופיל (${fromProfile.size})", "🙂 Or use the profile pictures (${fromProfile.size})")) {
                bg.execute {
                    val n = PitzkiStore.saveFacePhotos(this, c.memberId, fromProfile.map { android.net.Uri.fromFile(it) })
                    main.post {
                        if (destroyed) return@post
                        children = PitzkiStore.children(this)
                        chosen = children.firstOrNull { it.memberId == c.memberId } ?: chosen
                        android.widget.Toast.makeText(this, if (n > 0) L.t("נשמרו $n תמונות ✓", "$n photos saved ✓") else L.t("לא הצלחנו לפתוח את התמונות", "Couldn't open the photos"), android.widget.Toast.LENGTH_SHORT).show()
                        if (!inLesson) renderSetup()
                    }
                }
            })
            card.addView(other)
        } else {
            val row = ui.horizontal(top = 2)
            row.addView(ui.action(L.t("📸 לצלם שוב", "📸 Photograph again")) { faceCamera.launch(FaceCaptureActivity.intent(this, c.memberId, name)) })
            row.addView(ui.action(L.t("🖼️ מהגלריה", "🖼️ From the gallery")) { pickGallery() })
            card.addView(row)
        }
    }

    /** Things a parent rarely needs (drawing the film again from the photos, fixing only the opening): folded away. */
    private var advancedOpen = false
    private fun advanced(card: LinearLayout, c: PitzkiStore.Child) {
        val name = c.kid.name
        val faces = PitzkiStore.facePhotos(this, c.memberId)
        val redraw = faces.isNotEmpty() && PitzkiStore.partial(this, c) > 0
        val hand = PitzkiStore.handFile(this, c.memberId).exists()
        if (!redraw && !hand) return
        card.addView(ui.action((if (advancedOpen) "▾ " else "▸ ") + L.t("אפשרויות מתקדמות", "More options"), R.color.ink_soft) { advancedOpen = !advancedOpen; renderSetup() }.apply { textSize = 13f })
        if (!advancedOpen) return
        // Scenes drawn before these photos: drawn again from them (the parent approves the first one before the rest).
        if (redraw) card.addView(ui.action(L.t("לצייר את הסרט מחדש מהתמונות", "Draw the film again from the photos")) {
            AlertDialog.Builder(this)
                .setTitle(L.t("לצייר מחדש מהתמונות?", "Draw again from the photos?"))
                .setMessage(L.t("קודם תצויר הסצנה הראשונה ותאשרו ש$name ${if (c.kid.girl) "דומה לה" else "דומה לו"}. רק אחרי האישור יצוירו ויונפשו שאר הסצנות. זה משתמש בקרדיט סרט אחד.",
                    "First the opening scene is drawn and you approve that it looks like $name. Only then the other scenes are drawn and animated. It uses one film credit."))
                .setPositiveButton(L.t("לצייר מחדש", "Draw again")) { _, _ ->
                    PitzkiStore.newFilm(this, c.memberId)
                    if (PitzkiStore.redrawScenes(this, c, PitzkiScript.CHILD_KEYS)) { renderSetup(); watchDrawing() }
                }
                .setNegativeButton(L.t("ביטול", "Cancel"), null)
                .show()
        })
        // Only the opening's first seconds made again (a giant hand came in): the rest of the film stays.
        if (hand) card.addView(ui.action(L.t("✋ לתקן רק את פתיחת היד", "✋ Fix only the hand opening")) {
            AlertDialog.Builder(this)
                .setTitle(L.t("לתקן רק את הפתיחה?", "Fix only the opening?"))
                .setMessage(price(L.t("רק השניות הראשונות, שבהן פיצקי מושיט יד, ייעשו מחדש: פיצקי לבד מזמין את $name, בלי יד שנכנסת. שאר הסרט נשאר כמו שהוא. (כ-35 סנט)",
                    "Only the first seconds, where Pitzki reaches out, are made again: Pitzki alone invites $name, with no hand coming in. The rest of the film stays. (about 35 cents)")))
                .setPositiveButton(L.t("לתקן", "Fix")) { _, _ ->
                    if (PitzkiStore.remakeHand(this, c)) { renderSetup(); watchDrawing() }
                    else android.widget.Toast.makeText(this, L.t("משהו אחר עוד בהכנה. נסו שוב בעוד כמה דקות.", "Something else is still being made. Try again in a few minutes."), android.widget.Toast.LENGTH_LONG).show()
                }
                .setNegativeButton(L.t("ביטול", "Cancel"), null)
                .show()
        })
    }

    /** A step's title in the child's card: "1 · Photos of Noam ✓". */
    private fun stepTitle(card: LinearLayout, n: String, title: String, done: Boolean, top: Int = 16) {
        if (top >= 16) card.addView(ui.divider())
        card.addView(ui.text("$n · $title" + if (done) "  ✓" else "", 17f, top = if (top >= 16) 6 else top).apply { typeface = DS.heading })
    }

    /**
     * The child as the film's character (the soft look of an animated movie), drawn once from the real photos and
     * approved by the parent: then every scene is drawn from it, so the child looks the same in all of them.
     */
    private fun heroSection(card: LinearLayout, c: PitzkiStore.Child) {
        val name = c.kid.name
        fun g(boy: String, girl: String) = if (c.kid.girl) girl else boy
        stepTitle(card, "2", L.t("$name כדמות מצוירת", "$name as a drawn character"), PitzkiStore.heroFile(this, c.memberId).exists())
        val d = PitzkiStore.drawing?.takeIf { it.member == PitzkiStore.heroDrawing(c.memberId) }
        if (d != null && !d.finished) {
            card.addView(ui.hint(L.t("מציירים את $name כדמות…", "Drawing $name as a character…"), top = 4))
            card.addView(etaView(d))
            return
        }
        if (d != null && d.failed.isNotEmpty()) card.addView(ui.text(d.failed, 14f, R.color.ds_error, top = 4))
        val cand = PitzkiStore.heroCandidate(this, c.memberId)
        val hero = PitzkiStore.heroFile(this, c.memberId)
        fun picture(f: java.io.File, h: Int) = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
            maxHeight = ui.dp(h)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = ui.dp(6) }
            val img = this
            bg.execute {
                val b = try { BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = 2 }) } catch (_: Throwable) { null }
                main.post { if (!destroyed && b != null) img.setImageBitmap(b) }
            }
        }
        when {
            cand.exists() -> {
                card.addView(ui.hint(L.t("${g("זה", "זאת")} $name? אם כן, כל הסצנות יצוירו מהדמות הזו.", "Is this $name? If so, every scene is drawn from this character."), top = 4))
                card.addView(picture(cand, 320))
                val row = ui.horizontal(top = 4)
                row.addView(ui.action(L.t("✓ ${g("זה", "זאת")} $name", "✓ That's $name")) {
                    if (!PitzkiStore.approveHero(this, c.memberId)) { android.widget.Toast.makeText(this, L.t("לא הצלחנו לשמור", "Couldn't save"), android.widget.Toast.LENGTH_SHORT).show(); return@action }
                    val fresh = PitzkiStore.children(this).firstOrNull { it.memberId == c.memberId }
                    fresh?.let { PitzkiStore.confirmLook(this, it) }
                    AlertDialog.Builder(this)
                        .setTitle(L.t("לצייר את הסרט מהדמות?", "Draw the film from the character?"))
                        .setMessage(L.t("קודם תצויר הסצנה הראשונה ותאשרו אותה. רק אחרי האישור יצוירו ויונפשו שאר הסצנות. זה משתמש בקרדיט סרט אחד.",
                            "First the opening scene is drawn for you to approve. Only then the other scenes are drawn and animated. It uses one film credit."))
                        .setPositiveButton(L.t("לצייר", "Draw")) { _, _ ->
                            PitzkiStore.newFilm(this, c.memberId)
                            if (fresh != null && PitzkiStore.redrawScenes(this, fresh, PitzkiScript.CHILD_KEYS)) { renderSetup(); watchDrawing() } else renderSetup()
                        }
                        .setNegativeButton(L.t("אחר כך", "Later")) { _, _ -> renderSetup() }
                        .show()
                })
                row.addView(ui.action(L.t("לצייר שוב", "Draw again")) { if (PitzkiStore.makeHero(this, c)) { renderSetup(); watchDrawing() } })
                row.addView(ui.action(L.t("ביטול", "Cancel"), R.color.ink_soft) { cand.delete(); renderSetup() })
                card.addView(row)
            }
            hero.exists() -> {
                card.addView(ui.hint(L.t("הסרט מצויר מהדמות הזו.", "The film is drawn from this character."), top = 4))
                card.addView(picture(hero, 200))
                // The character from every side: turn it around with a finger.
                val sheet = PitzkiStore.heroSheet(this, c.memberId)
                if (sheet.exists()) card.addView(ui.action(L.t("🔄 לסובב את $name באצבע", "🔄 Turn $name around with a finger")) { turnAround(sheet, name) })
                else card.addView(ui.action(price(L.t("🔄 לראות את $name מכל הצדדים (כ-25 סנט)", "🔄 See $name from every side (about 25 cents)"))) {
                    if (PitzkiStore.makeHeroSheet(this, c)) { renderSetup(); watchDrawing() }
                })
                val row = ui.horizontal(top = 4)
                row.addView(ui.action(L.t("דמות חדשה", "A new character")) { if (PitzkiStore.makeHero(this, c)) { renderSetup(); watchDrawing() } })
                row.addView(ui.action(L.t("לחזור לתמונות האמיתיות", "Back to the real photos"), R.color.ink_soft) {
                    AlertDialog.Builder(this)
                        .setMessage(L.t("$name ${g("יצויר", "תצויר")} שוב מהתמונות האמיתיות (הסצנות יצוירו מחדש כשתבקשו).", "$name will be drawn from the real photos again (the scenes are drawn again when you ask)."))
                        .setPositiveButton(L.t("כן", "Yes")) { _, _ -> PitzkiStore.removeHero(this, c.memberId); renderSetup() }
                        .setNegativeButton(L.t("ביטול", "Cancel"), null)
                        .show()
                })
                card.addView(row)
            }
            else -> {
                card.addView(ui.hint(L.t("מהתמונות מציירים את $name פעם אחת, בסגנון של סרט אנימציה. אתם מאשרים שזה דומה, ומאז $name ${g("נראה", "נראית")} כך בכל הסרטים.",
                    "$name is drawn once from the photos, like an animated film. You approve it's alike, and from then on $name looks like this in every film."), top = 2))
                card.addView(ui.button(price(L.t("🎨 לצייר את $name (כ-25 סנט)", "🎨 Draw $name (about 25 cents)")), primary = PitzkiStore.facePhotos(this, c.memberId).isNotEmpty(), top = 8) { if (PitzkiStore.makeHero(this, c)) { renderSetup(); watchDrawing() } })
            }
        }
    }

    /**
     * The character from four sides (one picture, four views in a row): a finger dragged sideways turns it, like a
     * figure on a turntable; the dots show which side.
     */
    private fun turnAround(sheet: java.io.File, name: String) {
        val full = try { BitmapFactory.decodeFile(sheet.absolutePath) } catch (_: Throwable) { null } ?: return
        val views = (0 until 4).map { i -> android.graphics.Bitmap.createBitmap(full, full.width * i / 4, 0, full.width / 4, full.height) }
        val dlg = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val root = FrameLayout(this).apply { setBackgroundColor(android.graphics.Color.parseColor("#F6EFE6")) }
        val img = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER; setImageBitmap(views[0]) }
        root.addView(img, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply { topMargin = ui.dp(70); bottomMargin = ui.dp(110) })
        root.addView(TextView(this).apply {
            text = L.t("$name מכל הצדדים · גררו באצבע ימינה ושמאלה", "$name from every side · drag left and right"); textSize = 16f; gravity = Gravity.CENTER
            setTextColor(DS.ink(this@PitzkiActivity)); setPadding(ui.dp(16), ui.dp(24), ui.dp(16), 0)
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
        val dots = TextView(this).apply { textSize = 22f; gravity = Gravity.CENTER; setTextColor(DS.ink(this@PitzkiActivity)) }
        root.addView(dots, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply { bottomMargin = ui.dp(70) })
        var at = 0
        fun show(i: Int) { at = Math.floorMod(i, 4); img.setImageBitmap(views[at]); dots.text = (0 until 4).joinToString("  ") { if (it == at) "●" else "○" } }
        show(0)
        var startX = 0f; var startAt = 0
        val step = ui.dp(60).toFloat()
        root.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> { startX = e.x; startAt = at }
                android.view.MotionEvent.ACTION_MOVE -> show(startAt + ((e.x - startX) / step).toInt())
            }
            true
        }
        root.addView(ui.button(L.t("סגירה", "Close"), primary = false, top = 0) { dlg.dismiss() }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = ui.dp(16) })
        dlg.setContentView(root)
        dlg.show()
    }

    /**
     * A photo of the child with Pitzki: choose a place (forest, train, farm, sea, stars); about 25 cents each. The
     * photos are kept here and in the family's moments; tap one to see it large or share it.
     */
    private fun photoSection(card: LinearLayout, c: PitzkiStore.Child) {
        stepTitle(card, "⭐", L.t("בונוס: תמונה משותפת עם פיצקי", "Bonus: a photo together with Pitzki"), false)
        val d = PitzkiStore.drawing?.takeIf { it.member == "photo-${c.memberId}" }
        if (d != null && !d.finished) {
            card.addView(ui.hint(L.t("מצלמים את ${c.kid.name} עם פיצקי…", "Taking ${c.kid.name}'s photo with Pitzki…"), top = 4))
            card.addView(etaView(d))
            return
        }
        if (d != null && d.failed.isNotEmpty()) card.addView(ui.text(d.failed, 14f, R.color.ds_error, top = 4))
        val photos = PitzkiStore.photos(this, c.memberId)
        if (photos.isNotEmpty()) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, ui.dp(6), 0, 0) }
            for (f in photos.take(12)) {
                val img = ImageView(this).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    background = DS.rounded(DS.buttonSecondary(this@PitzkiActivity), DS.radiusChip(this@PitzkiActivity))
                    clipToOutline = true
                    layoutParams = LinearLayout.LayoutParams(ui.dp(120), ui.dp(68)).apply { marginEnd = ui.dp(6) }
                    setOnClickListener {
                        showMedia(f, null, L.t("${c.kid.name} ופיצקי", "${c.kid.name} and Pitzki"),
                            L.t("לשתף", "Share") to { shareImage(f) },
                            L.t("למחוק", "Delete") to { f.delete(); renderSetup() })
                    }
                }
                row.addView(img)
                bg.execute {
                    val b = try { BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = 4 }) } catch (_: Throwable) { null }
                    main.post { if (!destroyed && b != null) img.setImageBitmap(b) }
                }
            }
            card.addView(ui.scrollRow(row, top = 0))
        }
        card.addView(ui.hint(price(L.t("בוחרים מקום, ופיצקי ו${c.kid.name} מצטלמים שם יחד (כ-25 סנט לתמונה):", "Choose a place, and Pitzki and ${c.kid.name} are pictured there together (about 25 cents a photo):")), top = 6))
        val places = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, ui.dp(4), 0, 0) }
        for (pl in PitzkiStore.PHOTO_PLACES) places.addView(TextView(this).apply {
            text = "${pl[2]} ${pl[1]}"
            textSize = 14f
            setTextColor(DS.ink(this@PitzkiActivity))
            setPadding(ui.dp(14), ui.dp(8), ui.dp(14), ui.dp(8))
            background = DS.pressable(this@PitzkiActivity, DS.rounded(DS.card(this@PitzkiActivity), ui.dp(18).toFloat()))
            layoutParams = LinearLayout.LayoutParams(wrap, wrap).apply { marginEnd = ui.dp(6) }
            setOnClickListener { if (PitzkiStore.makePhoto(this@PitzkiActivity, c, pl[0])) { renderSetup(); watchDrawing() } }
        })
        card.addView(ui.scrollRow(places, top = 0))
        card.addView(ui.action(L.t("🖼️ להוסיף תמונה שיצרתם (מהגלריה)", "🖼️ Add a photo you made (from the gallery)"), R.color.ink_soft) {
            ownPhotoFor = c.memberId
            try {
                ownPhotoPicker.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly))
            } catch (_: Exception) { ownPhotoFor = null }
        }.apply { textSize = 13f })
    }

    /** Shares a picture to any app (WhatsApp, Drive…). */
    private fun shareImage(f: java.io.File) {
        try {
            val dir = java.io.File(cacheDir, "share").apply { mkdirs() }
            val copy = java.io.File(dir, f.name)
            f.copyTo(copy, overwrite = true)
            val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.files", copy)
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("image/jpeg").putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), L.t("לשתף את התמונה", "Share the photo")))
        } catch (_: Exception) {
            android.widget.Toast.makeText(this, L.t("לא הצלחנו לשתף", "Couldn't share"), android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    /** Admin: the farm, made once for every family through fal (pictures, Pitzki talking, animals coming out, sounds). */
    private fun farmSection(card: LinearLayout) {
        card.addView(ui.text(L.t("🐄 עולם החווה", "🐄 The farm world"), 15f, top = 16).apply { typeface = DS.emphasis })
        val d = PitzkiStore.drawing?.takeIf { it.member == "farm" }
        if (d != null && !d.finished) {
            card.addView(ui.hint(L.t("יוצרים את החווה… ${d.note}", "Making the farm… ${d.note}"), top = 4))
            card.addView(etaView(d))
            return
        }
        if (d != null && d.failed.isNotEmpty()) card.addView(ui.text(d.failed, 14f, R.color.ds_error, top = 4))
        val missing = PitzkiStore.farmMissing(this)
        card.addView(ui.hint(if (missing == 0) L.t("✓ הכל מוכן: 7 חיות, הקולות שלהן, ופיצקי מדבר.", "✓ All ready: 7 animals, their sounds, and Pitzki talking.")
            else L.t("חסרים $missing חלקים (ציורים, סרטונים של פיצקי, חיות שמגיחות, קולות).", "$missing parts missing (pictures, Pitzki's videos, animals coming out, sounds)."), top = 4))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, ui.dp(6), 0, 0) }
        val keys = listOf(FarmWorld.INTRO) + FarmWorld.ANIMALS.flatMap { listOf(it.key, it.open) } + "farm_review"
        for (key in keys) {
            val pic = PitzkiStore.sharedScene(this, key)
            if (!pic.exists()) continue
            val a = FarmWorld.animal(key)
            val label = when { key == FarmWorld.INTRO -> L.t("הכניסה", "The gate"); key == "farm_review" -> L.t("משחק הקולות", "The sound game")
                a != null && key == a.open -> a.emoji + " " + a.he; a != null -> "❓ " + a.he; else -> key }
            val movie = PitzkiStore.sharedTalk(this, key).takeIf { it.exists() } ?: PitzkiStore.sharedClip(this, key).takeIf { it.exists() }
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(wrap, wrap).apply { marginEnd = ui.dp(6) }
                setOnClickListener {
                    showMedia(pic, movie, label, L.t("ליצור מחדש", "Make again") to {
                        AlertDialog.Builder(this@PitzkiActivity).setMessage(L.t("החלק הזה ייווצר מחדש דרך fal ויחליף את הקודם (פחות מדולר).", "This part is made again through fal, replacing the old one (under a dollar)."))
                            .setPositiveButton(L.t("ליצור מחדש", "Make again")) { _, _ -> if (PitzkiStore.buildFarm(this@PitzkiActivity, setOf(key))) { renderSetup(); watchDrawing() } }
                            .setNegativeButton(L.t("ביטול", "Cancel"), null).show()
                    })
                }
            }
            val img = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = DS.rounded(DS.buttonSecondary(this@PitzkiActivity), DS.radiusChip(this@PitzkiActivity))
                clipToOutline = true
                layoutParams = LinearLayout.LayoutParams(ui.dp(96), ui.dp(54))
            }
            cell.addView(img)
            cell.addView(ui.hint(label + if (movie != null) " ▶" else "", top = 2))
            bg.execute {
                val b = try { BitmapFactory.decodeFile(pic.absolutePath, BitmapFactory.Options().apply { inSampleSize = 8 }) } catch (_: Throwable) { null }
                main.post { if (!destroyed && b != null) img.setImageBitmap(b) }
            }
            row.addView(cell)
        }
        if (row.childCount > 0) card.addView(ui.scrollRow(row, top = 0))
        // Only what's missing is made (and paid): the price of those parts, not of the whole farm.
        val parts = PitzkiStore.farmParts(this)
        val cost = "\$" + String.format(java.util.Locale.US, "%.1f", PitzkiStore.farmCost(parts))
        // Only the animals' sounds (a few cents): the real sound instead of Pitzki saying "moo".
        if (parts[3] > 0) card.addView(ui.action(L.t("🔊 ליצור רק את ${parts[3]} קולות החיות (מקרדיטים של ElevenLabs, כמה סנטים)", "🔊 Make only the ${parts[3]} animal sounds (ElevenLabs credits, a few cents)")) {
            if (PitzkiStore.buildFarmSounds(this)) { renderSetup(); watchDrawing() }
        })
        if (missing > 0) card.addView(ui.button(L.t("להשלים את החווה דרך fal (כ-$cost)", "Finish the farm through fal (about $cost)"), primary = true, top = 8) {
            AlertDialog.Builder(this)
                .setMessage(L.t("נוצר רק מה שחסר, פעם אחת לכל המשפחות: ${parts[0]} ציורים, ${parts[1]} סרטונים של פיצקי מדבר, ${parts[2]} חיות שמגיחות, ${parts[3]} קולות של חיות. בערך $cost. מה שכבר נוצר לא נוצר שוב.",
                    "Only what's missing is made, once for every family: ${parts[0]} pictures, ${parts[1]} videos of Pitzki talking, ${parts[2]} animals coming out, ${parts[3]} animal sounds. About $cost. What's made isn't made again."))
                .setPositiveButton(L.t("ליצור", "Make it")) { _, _ -> if (PitzkiStore.buildFarm(this)) { renderSetup(); watchDrawing() } }
                .setNegativeButton(L.t("לא עכשיו", "Not now"), null)
                .show()
        })
    }

    /** The settings page: the child's film and look, the name, the microphone, and the admin's tools. */
    private fun renderSettings(box: LinearLayout) {
        setupTitle?.text = L.t("הגדרות", "Settings")
        box.addView(ui.action(L.t("‹ חזרה לפיצקי", "‹ Back to Pitzki")) { showSettings = false; renderSetup(); scrollTop() })
        if (children.size > 1) {
            box.addView(ui.section(L.t("של מי?", "Whose?")))
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, ui.dp(10), 0, 0) }
            for (c in children) row.addView(childChip(c))
            reorderable(row)
            box.addView(ui.hint(L.t("אפשר ללחוץ לחיצה ארוכה על ילד ולגרור אותו למקום אחר בסדר.", "Long-press a child and drag to change the order."), top = 2))
            box.addView(ui.scrollRow(row, top = 0))
        }
        // A new parent's way through it: the steps, what's done, the next one.
        chosen?.let { box.addView(setupSteps(it)) }
        // The child's film: the look, the photos, the scenes.
        chosen?.let { box.addView(childCard(it)) }
        // What the child knows (what Pitzki remembers), for the parents.
        chosen?.let { c -> box.addView(ui.button(L.t("📊 מה ${c.kid.name} ${if (c.kid.girl) "יודעת" else "יודע"}", "📊 What ${c.kid.name} knows"), top = 12) { knowsDialog(c) }) }
        // Scenes of this world that were drawn again (or removed): the earlier ones can come back, free.
        chosen?.let { c ->
            val earlier = PitzkiStore.earlierScenes(this, c.memberId)
            if (earlier > 0 && PitzkiStore.drawing?.finished != false) box.addView(ui.action(L.t("↩ להחזיר את $earlier הסצנות הקודמות של ${c.kid.name} בעולם הזה", "↩ Bring back ${c.kid.name}'s $earlier earlier scenes in this world")) {
                AlertDialog.Builder(this)
                    .setMessage(L.t("הסצנות הקודמות חוזרות, והנוכחיות נשמרות בצד (אפשר להחליף שוב). בלי עלות.", "The earlier scenes come back, and the current ones are kept aside (you can switch again). Free."))
                    .setPositiveButton(L.t("להחזיר", "Bring back")) { _, _ ->
                        if (PitzkiStore.restoreScenes(this, c)) { renderSetup(); android.widget.Toast.makeText(this, L.t("הסצנות הקודמות חזרו ✓", "The earlier scenes are back ✓"), android.widget.Toast.LENGTH_SHORT).show() }
                    }
                    .setNegativeButton(L.t("ביטול", "Cancel"), null)
                    .show()
            })
        }
        // Admin: what this child's film cost so far at fal (an estimate from the server's price list).
        chosen?.takeIf { adminTools() }?.let { c ->
            val ref = PitzkiStore.lastRef(this, c.memberId)
            if (ref.isNotEmpty()) {
                val line = ui.hint("", top = 6)
                box.addView(line)
                net.execute {
                    val r = try { Account.token(applicationContext)?.let { t -> CartoonFilm.callEdge(Circle.url(applicationContext) + "/functions/v1/cartoon-film", t, mapOf("action" to "cost_ref", "ref" to ref)) } } catch (_: Exception) { null }
                    main.post {
                        if (destroyed || r == null || r.optInt("_code", 200) !in 200..299) return@post
                        line.text = L.t("מנהל · עלות משוערת של הסרט ב-fal: $%.2f (%d בקשות)", "Admin · estimated fal cost of this film: $%.2f (%d requests)")
                            .format(java.util.Locale.US, r.optDouble("usd", 0.0), r.optInt("n"))
                    }
                }
            }
        }
        // How Pitzki says the child's name (אוֹרִי or אוּרִי: the same letters): the parent can set it.
        chosen?.let { c ->
            PitzkiVoice.sayName = c.kid.name to PitzkiStore.sayName(this, c.memberId)
            if (c.kid.named) box.addView(ui.hint(
                if (c.kid.girl) L.t("פיצקי פונה אל ${c.kid.name} כאל בת (\"תראי\", \"מוכנה?\"). אפשר לשנות בפרופיל המשפחה.", "Pitzki talks to ${c.kid.name} as a girl. You can change it in the family profile.")
                else L.t("פיצקי פונה אל ${c.kid.name} כאל בן (\"תראה\", \"מוכן?\"). אפשר לשנות בפרופיל המשפחה.", "Pitzki talks to ${c.kid.name} as a boy. You can change it in the family profile."), top = 6))
            if (c.kid.named) box.addView(ui.action(L.t("🔊 איך פיצקי אומר את השם \"${c.kid.name}\"?", "🔊 How does Pitzki say \"${c.kid.name}\"?")) { nameDialog(c) })
        }
        // Pitzki's voice for this child, made ahead in the background (quietly: the phone's voice until it's ready).
        PitzkiStore.prepareVoice(this, kid)
        if (adminTools()) PitzkiStore.voicePrep?.let { v ->
            box.addView(ui.hint(L.t("מנהל · הקול של פיצקי: ${v.ready} מתוך ${v.total} משפטים מוכנים", "Admin · Pitzki's voice: ${v.ready} of ${v.total} lines ready") +
                (if (v.problem.isNotEmpty()) " · " + v.problem else ""), top = 10))
        }
        if (adminTools()) box.addView(sharedCard())

        // The parent decides whether Pitzki listens to the child's answers.
        box.addView(ui.switch(L.t("פיצקי מקשיב לתשובות של הילד (מיקרופון)", "Pitzki listens to the child's answers (microphone)"), PitzkiStore.listenOn(this), top = 18) { on ->
            PitzkiStore.setListen(this, on)
        })
        box.addView(ui.hint(L.t("הילד עונה בקול, ופיצקי אומר \"נכון מאוד!\" או את התשובה הנכונה. אפשר לכבות בכל רגע, גם מתוך הטיול. הזיהוי נעשה בשירות הדיבור של הטלפון (לפעמים של Google), ולא נשמר אצלנו.",
            "The child answers out loud, and Pitzki says \"Right!\" or the right answer. You can turn it off any time, even during the walk. Recognition uses the phone's speech service (sometimes Google's) and nothing is kept by us."), top = 2))

        // The admin's tools (shared scenes, costs, voice, microphone codes): hidden unless asked for.
        if (Account.isAdmin(this)) box.addView(ui.action(if (adminTools()) L.t("🔧 להסתיר את כלי המנהל", "🔧 Hide admin tools") else L.t("🔧 כלי מנהל", "🔧 Admin tools"), R.color.ink_soft) {
            Settings.prefs(this).edit().putBoolean("pitzki_admin_tools", !adminTools()).apply()
            renderSetup()
        }.apply { gravity = Gravity.CENTER; textSize = 13f })
        box.addView(ui.action(L.t("‹ חזרה לפיצקי", "‹ Back to Pitzki")) { showSettings = false; renderSetup(); scrollTop() })
    }

    private fun scrollTop() { ((setupBox?.parent as? View)?.parent as? android.widget.ScrollView)?.scrollTo(0, 0) }

    private fun childCard(c: PitzkiStore.Child): View {
        val card = ui.card(top = 14)
        val name = c.kid.name
        fun g(boy: String, girl: String) = if (c.kid.girl) girl else boy
        val look = c.look
        card.addView(ui.text(L.t("🎬 $name בסרטים של פיצקי", "🎬 $name in Pitzki's films"), 20f).apply { typeface = DS.heading })
        card.addView(ui.hint(when {
            look == null -> L.t("עוד אין תמונה של $name", "No picture of $name yet")
            look.hero -> L.t("בסרטים: הדמות המצוירת של $name", "In the films: $name's drawn character")
            look.drawn -> L.t("בסרטים: הדמות של $name מהספר", "In the films: $name's character from the book")
            else -> L.t("בסרטים: התמונה מהפרופיל (כדאי לצייר דמות, למטה)", "In the films: the profile picture (better: draw a character, below)")
        }, top = 2))
        if (look == null) {
            card.addView(ui.hint(L.t("הוסיפו תמונה של $name בפרופיל המשפחה, או צרו ספר ילדים: הדמות שתאשרו שם תצטרף לפיצקי.",
                "Add a picture of $name in the family profile, or make a children's book: the character you approve there joins Pitzki."), top = 4))
            return card
        }
        val d = PitzkiStore.drawing?.takeIf { it.member == c.memberId }
        val clips = PitzkiStore.clips(this, c)
        val candidate = PitzkiStore.candidate(this, c.memberId)
        val steps = (d == null || d.finished) && !candidate.exists()
        if (steps) { facePhotos(card, c); heroSection(card, c) }
        // The rest (bonus photo, credits, rarely-needed options) at the end of the card.
        val tail = { if (steps) { photoSection(card, c); advanced(card, c); filmCredits(card) } }
        if (PitzkiScript.CHILD_KEYS.isNotEmpty()) stepTitle(card, "3", L.t("הסרט של $name", "$name's film"), PitzkiStore.scenesReady(this, c) && clips == PitzkiScript.SCENES)
        // A world played on Pitzki's own scenes only (the numbers): nothing of the child's to draw, nothing to pay.
        if (PitzkiScript.CHILD_KEYS.isEmpty() && (d == null || d.finished)) {
            card.addView(ui.hint(L.t("בעולם הזה משחקים על הסצנות של פיצקי בלבד, אז אין סצנות של $name לייצר ואין עלות.",
                "This world is played on Pitzki's own scenes only: nothing of $name's to make, no cost."), top = 10))
            tail()
            return card
        }
        when {
            d != null && !d.finished && d.phase == "character" -> {
                card.addView(ui.hint(L.t("מציירים את $name כדמות מהתמונות…", "Drawing $name as a character from the photos…"), top = 4))
                card.addView(etaView(d))
                card.addView(ui.hint(L.t("אפשר לצאת מהמסך, זה ממשיך.", "You can leave this screen; it keeps going."), top = 2))
            }
            candidate.exists() -> {
                // The parent's OK first: the film is only as good as the likeness.
                card.addView(ui.text(L.t("זה $name?", "Is this $name?"), 18f, top = 6).apply { typeface = DS.heading })
                card.addView(ImageView(this).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    adjustViewBounds = true
                    background = DS.rounded(DS.bg(this@PitzkiActivity), DS.radiusMedia(this@PitzkiActivity))
                    clipToOutline = true
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(300)).apply { topMargin = ui.dp(8) }
                    bg.execute {
                        val b = try { BitmapFactory.decodeFile(candidate.absolutePath) } catch (_: Throwable) { null }
                        main.post { if (!destroyed && b != null) setImageBitmap(b) }
                    }
                })
                card.addView(ui.button(L.t("כן, זה $name! לאשר", "Yes, that's $name! Approve"), primary = true, top = 10) {
                    if (PitzkiStore.approveCharacter(this, c)) {
                        children = PitzkiStore.children(this)
                        chosen = children.firstOrNull { it.memberId == c.memberId }
                        chosen?.let { PitzkiStore.confirmLook(this, it) } // the parent just approved it
                    }
                    renderSetup()
                })
                val row = ui.horizontal(top = 4)
                row.addView(ui.action(L.t("לצייר שוב", "Draw again")) { if (PitzkiStore.makeCharacter(this, c)) { renderSetup(); watchDrawing() } })
                row.addView(ui.action(L.t("ביטול", "Cancel"), R.color.ink_soft) { candidate.delete(); renderSetup() })
                card.addView(row)
            }
            d != null && !d.finished && d.phase != "character" -> waiting(card, c, d)
            d != null && !d.finished -> {
                card.addView(ui.text(
                    if (d.phase == "anim") L.t("שלב 2 מתוך 2: מנפישים", "Step 2 of 2: animating")
                    else L.t("שלב 1 מתוך 2: מציירים את $name ביער", "Step 1 of 2: drawing $name in the forest"), 15f, top = 6).apply { typeface = DS.emphasis })
                card.addView(android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                    isIndeterminate = d.phase == "anim" && d.animated == 0
                    max = d.total.coerceAtLeast(1)
                    progress = if (d.phase == "anim") d.animated else d.done
                    progressTintList = android.content.res.ColorStateList.valueOf(DS.button(this@PitzkiActivity))
                    indeterminateTintList = android.content.res.ColorStateList.valueOf(DS.button(this@PitzkiActivity))
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(8)).apply { topMargin = ui.dp(8) }
                })
                card.addView(ui.hint(
                    if (d.phase == "anim") L.t("כל הסצנות קמות לחיים ביחד, וזה לוקח בערך 3–8 דקות. מוכנות: ${d.animated} מתוך ${d.total}.", "All the scenes come to life together; it takes about 3–8 minutes. Ready: ${d.animated} of ${d.total}.")
                    else L.t("${d.done} מתוך ${d.total} ציורים. אחר כך הם יקומו לחיים.", "${d.done} of ${d.total} drawings. Then they come to life."), top = 6))
                if (d.note.isNotEmpty()) card.addView(ui.text(d.note, 13f, R.color.ds_error, top = 4))
                card.addView(ui.hint(L.t("אפשר לצאת מהמסך, זה ממשיך.", "You can leave this screen; it keeps going."), top = 2))
            }
            !PitzkiStore.lookConfirmed(this, c) -> {
                // The parent approves how the child looks before anything is drawn with it.
                if (d != null && d.failed.isNotEmpty()) card.addView(ui.text(d.failed, 14f, R.color.ds_error, top = 4))
                card.addView(ui.text(L.t("זה $name?", "Is this $name?"), 18f, top = 6).apply { typeface = DS.heading })
                card.addView(ImageView(this).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    background = DS.rounded(DS.bg(this@PitzkiActivity), DS.radiusMedia(this@PitzkiActivity))
                    clipToOutline = true
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(260)).apply { topMargin = ui.dp(8) }
                    bg.execute {
                        val b = try { BitmapFactory.decodeFile(look.file.absolutePath) } catch (_: Throwable) { null }
                        main.post { if (!destroyed && b != null) setImageBitmap(b) }
                    }
                })
                card.addView(ui.hint(
                    if (look.drawn) L.t("זו הדמות של $name מהספר. להשתמש בה בסרט של פיצקי, או ליצור דמות חדשה מהתמונות?", "This is $name's character from the book. Use it in Pitzki's film, or make a new one from the photos?")
                    else L.t("זו התמונה של $name. לדמיון הכי טוב כדאי ליצור ממנה דמות מצוירת ולאשר אותה.", "This is $name's photo. For the best likeness, make a drawn character from it and approve it."), top = 6))
                card.addView(ui.button(
                    if (look.drawn) L.t("כן, להשתמש בדמות הזאת", "Yes, use this character") else L.t("להמשיך עם התמונה", "Continue with the photo"),
                    primary = look.drawn, top = 10) { PitzkiStore.confirmLook(this, c); renderSetup() })
                if (c.photos.isNotEmpty()) card.addView(ui.button(L.t("ליצור דמות חדשה מהתמונות של $name", "Make a new character from $name's photos"),
                    primary = !look.drawn, top = 6) { confirmCharacter(c) })
            }
            PitzkiStore.awaitingIntro(this, c) -> introApproval(card, c)
            PitzkiStore.scenesReady(this, c) -> {
                if (d != null && d.failed.isNotEmpty()) card.addView(ui.text(d.failed, 14f, R.color.ds_error, top = 4))
                card.addView(ui.hint(
                    if (clips == PitzkiScript.SCENES) L.t("✓ הסרט של $name מוכן: ${PitzkiScript.SCENES} סצנות שבהן פיצקי ו$name זזים ומטיילים.", "✓ $name's film is ready: ${PitzkiScript.SCENES} scenes where Pitzki and $name move and explore.")
                    else if (clips > 0) L.t("✓ $name ${g("מצויר", "מצוירת")} ביער. $clips מתוך ${PitzkiScript.SCENES} סצנות כבר זזות.", "✓ $name is drawn in. $clips of ${PitzkiScript.SCENES} scenes already move.")
                    else L.t("✓ $name ${g("מצויר", "מצוירת")} בתוך היער, ב-${PitzkiScript.SCENES} סצנות (עדיין בלי תנועה).", "✓ $name is drawn into the forest, in ${PitzkiScript.SCENES} scenes (not moving yet)."), top = 4))
                // Before the first walk the parent goes over the scenes (and again after one is made anew).
                val own = PitzkiScript.CHILD_KEYS.any { PitzkiStore.clipFile(this, c.memberId, it).exists() }
                if (own && !PitzkiStore.reviewed(this, c.memberId)) {
                    card.addView(ui.text(L.t("👀 לפני הטיול: עברו על הסצנות של $name", "👀 Before the walk: go over $name's scenes"), 15f, top = 10).apply { typeface = DS.emphasis })
                    card.addView(ui.hint(L.t("לחצו על כל סצנה כדי לצפות בה. משהו לא נראה טוב? אפשר לגזור קטע (חינם), להנפיש מחדש רק אותה, או לצייר אותה מחדש.",
                        "Tap each scene to watch it. Something looks wrong? Cut a part out (free), animate just it again, or draw it again."), top = 2))
                }
                card.addView(scenesStrip(c))
                val flagged = PitzkiScript.CHILD_KEYS.count { k -> PitzkiStore.clipFile(this, c.memberId, k).takeIf { it.exists() }?.let { SceneScan.open(this, it).isNotEmpty() } == true }
                if (flagged > 0) card.addView(ui.hint(L.t("⚠️ האפליקציה מצאה ב-$flagged סצנות רגע שבו אולי מופיעה דמות נוספת. לחצו על הסצנה המסומנת: הזמן כתוב בכותרת, ואפשר לגזור אותו בחינם.",
                    "⚠️ The app found a moment in $flagged scenes where someone extra may appear. Tap the marked scene: the time is in its title, and it can be cut for free."), top = 4))
                if (own && !PitzkiStore.reviewed(this, c.memberId))
                    card.addView(ui.button(L.t("✓ עברתי על הסצנות, הכל טוב", "✓ I went over the scenes, all good"), primary = true, top = 8) {
                        PitzkiStore.setReviewed(this, c.memberId); renderSetup()
                    })
                syncOld(card, c)
                if (PitzkiStore.needsAnimation(this, c)) card.addView(ui.button(
                    L.t("להנפיש: פיצקי ו$name ילכו ויטיילו", "Animate: Pitzki and $name walk and explore"), primary = true, top = 10) { confirmDraw(c, again = false, animate = true) })
                if (c.photos.isNotEmpty()) card.addView(ui.action(L.t("$name לא מספיק דומה? ליצור דמות מהתמונות", "Not like $name enough? Make a character from the photos")) { confirmCharacter(c) })
                val row = ui.horizontal(top = 6)
                row.addView(ui.action(L.t("ליצור מחדש", "Make again")) { confirmDraw(c, again = true, animate = clips > 0) })
                // Short animations (from when the walks were 5 seconds): continued from their last frame, not made again.
                val short = PitzkiStore.shortClips(this, c)
                if (short.isNotEmpty()) card.addView(ui.button(L.t("להאריך את הסצנות הקצרות (${short.size})", "Make the short scenes longer (${short.size})"), primary = false, top = 8) {
                    AlertDialog.Builder(this)
                        .setMessage(price(L.t("${short.size} סצנות ימשיכו בדיוק מהרגע שבו הן נגמרות, עוד 5 שניות לכל אחת, בלי לזרוק את מה שכבר יש. עלות משוערת: כ-${"%.2f".format(java.util.Locale.US, short.size * 0.35)}$.",
                            "${short.size} scenes continue exactly from where they end, 5 more seconds each, keeping what's there. About $${"%.2f".format(java.util.Locale.US, short.size * 0.35)}.")))
                        .setPositiveButton(L.t("להאריך", "Make longer")) { _, _ -> if (PitzkiStore.extendClips(this, c)) { renderSetup(); watchDrawing() } }
                        .setNegativeButton(L.t("ביטול", "Cancel"), null)
                        .show()
                })
                // The same drawings, moving anew (e.g. after the motion was improved): only the animations are paid.
                if (clips > 0) row.addView(ui.action(L.t("להנפיש מחדש", "Animate again")) {
                    AlertDialog.Builder(this)
                        .setMessage(L.t("הציורים נשארים, ורק התנועה של $name ופיצקי נוצרת מחדש (קרדיט סרט אחד).", "The drawings stay; only $name and Pitzki's motion is made again (one film credit)."))
                        .setPositiveButton(L.t("להנפיש מחדש", "Animate again")) { _, _ ->
                            for (k in PitzkiScript.CHILD_KEYS) { PitzkiStore.clipFile(this, c.memberId, k).delete(); PitzkiStore.moreFile(this, c.memberId, k).delete() }
                            confirmDraw(c, again = false, animate = true)
                        }
                        .setNegativeButton(L.t("ביטול", "Cancel"), null)
                        .show()
                })
                row.addView(ui.action(L.t("למחוק", "Delete"), R.color.ds_error) {
                    PitzkiStore.deleteScenes(this, c.memberId); renderSetup()
                })
                card.addView(row)
            }
            else -> {
                if (d != null && d.failed.isNotEmpty()) card.addView(ui.text(d.failed, 14f, R.color.ds_error, top = 4))
                card.addView(ui.hint(
                    if (PitzkiStore.partial(this, c) > 0) L.t("נוספו לסרט צבעים חדשים! ${PitzkiStore.partial(this, c)} סצנות כבר מוכנות, ונוצרות רק החסרות.", "New colors were added to the film! ${PitzkiStore.partial(this, c)} scenes are ready; only the missing ones are made.")
                    else if (PitzkiStore.scenesOutdated(this, c)) L.t("נשמרה דמות חדשה של $name. אפשר ליצור את הסרט מחדש איתה.", "A new look of $name was saved. You can make the film again with it.")
                    else L.t("בינתיים $name ${g("מופיע", "מופיעה")} בעיגול ליד פיצקי. בסרט עצמו $name ${g("נמצא", "נמצאת")} בתוך היער, ${g("הולך", "הולכת")} ו${g("מטייל", "מטיילת")} עם פיצקי בכל סצנות היער. נוצר פעם אחת ונשמר בטלפון.",
                        "For now $name shows in a circle next to Pitzki. In the film itself $name is inside the forest, walking and exploring with Pitzki in every forest scene. Made once, kept on the phone."), top = 4))
                if (PitzkiStore.partial(this, c) > 0) { card.addView(scenesStrip(c)); syncOld(card, c) }
                if (!look.drawn && c.photos.isNotEmpty()) {
                    // Most like the child: first a character from the photos (the parent approves), then the film.
                    card.addView(ui.hint(L.t("כדי ש$name ${g("ייראה", "תיראה")} בדיוק כמו בחיים: קודם מציירים דמות מהתמונות ${g("שלו", "שלה")}, אתם מאשרים, ורק אז הסרט.",
                        "So $name looks just like in real life: first a character is drawn from the photos, you approve it, then the film."), top = 8))
                    card.addView(ui.button(L.t("שלב 1: ליצור את הדמות של $name", "Step 1: make $name's character"), primary = true, top = 10) { confirmCharacter(c) })
                    card.addView(ui.action(L.t("לדלג ולהכין את הסרט עכשיו", "Skip and make the film now")) { confirmDraw(c, again = false, animate = true) })
                } else {
                    card.addView(ui.button(L.t("ליצור את הסרט של $name", "Make $name's film"), primary = true, top = 10) { confirmDraw(c, again = false, animate = true) })
                    card.addView(ui.action(L.t("רק ציורים, בלי תנועה", "Drawings only, no motion")) { confirmDraw(c, again = false, animate = false) })
                }
            }
        }
        tail()
        return card
    }

    /**
     * The first scene is drawn: the parent sees how the child looks in the film and says "yes" before the other
     * drawings and the animations are made (and paid for). Not like the child: draw it again, or make a character.
     */
    private fun introApproval(card: LinearLayout, c: PitzkiStore.Child) {
        val name = c.kid.name
        val d = PitzkiStore.drawing?.takeIf { it.member == c.memberId }
        if (d != null && d.failed.isNotEmpty()) card.addView(ui.text(d.failed, 14f, R.color.ds_error, top = 4))
        card.addView(ui.text(L.t("ככה $name ${if (c.kid.girl) "תיראה" else "ייראה"} בסרט. ${if (c.kid.girl) "דומה" else "דומה"}?", "This is how $name looks in the film. Like $name?"), 18f, top = 6).apply { typeface = DS.heading })
        val f = PitzkiStore.sceneFile(this, c.memberId, PitzkiScript.INTRO_KEY)
        card.addView(ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = DS.rounded(DS.buttonSecondary(this@PitzkiActivity), DS.radiusMedia(this@PitzkiActivity))
            clipToOutline = true
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(200)).apply { topMargin = ui.dp(8) }
            setOnClickListener { showMedia(f, null, L.t("סצנת הפתיחה", "The opening scene")) }
            bg.execute {
                val b = try { BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = 2 }) } catch (_: Throwable) { null }
                main.post { if (!destroyed && b != null) setImageBitmap(b) }
            }
        })
        card.addView(ui.hint(L.t("רק אחרי האישור נוצרות שאר הסצנות וההנפשה. ככה לא משלמים על סרט שלא דומה.",
            "Only after you approve are the other scenes and the animation made, so you don't pay for a film that doesn't look right."), top = 6))
        card.addView(ui.button(L.t("כן, זה $name! להמשיך", "Yes, that's $name! Continue"), primary = true, top = 10) {
            if (PitzkiStore.approveIntro(this, c)) { renderSetup(); watchDrawing() }
        })
        val row = ui.horizontal(top = 4)
        row.addView(ui.action(L.t("לצייר שוב", "Draw again")) { if (PitzkiStore.redrawIntro(this, c)) { renderSetup(); watchDrawing() } })
        if (c.photos.isNotEmpty()) row.addView(ui.action(L.t("ליצור דמות חדשה מהתמונות", "New character from the photos")) { confirmCharacter(c) })
        card.addView(row)
    }

    // ---------- while the film is being made: something worth the wait ----------

    private var waitSong: android.media.MediaPlayer? = null
    private var heroShown: Pair<String, android.graphics.Bitmap>? = null

    private fun stopWaitSong() {
        val p = waitSong ?: return
        waitSong = null
        try { p.release() } catch (_: Exception) {}
    }

    /** Pitzki's song while waiting (the same one that opens the film). */
    private fun toggleWaitSong(button: TextView) {
        if (waitSong != null) { stopWaitSong(); button.text = L.t("🎵 לשמוע את השיר של פיצקי", "🎵 Hear Pitzki's song"); return }
        try {
            val fd = assets.openFd("pitzki/song.m4a")
            val p = android.media.MediaPlayer()
            p.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
            fd.close()
            p.setOnCompletionListener { stopWaitSong(); if (button.isAttachedToWindow) button.text = L.t("🎵 לשמוע שוב", "🎵 Hear it again") }
            p.prepare()
            p.start()
            waitSong = p
            button.text = L.t("⏸ לעצור את השיר", "⏸ Stop the song")
        } catch (_: Exception) { }
    }

    /**
     * The film is being made (a few minutes): instead of a bar, a "making of" — the newest drawing large, the
     * scenes filling in one by one, a few playful words about what's happening, and something to do together
     * (Pitzki's song, the colors to say). The parent can leave the app: a notification says when it's ready.
     */
    private fun waiting(card: LinearLayout, c: PitzkiStore.Child, d: PitzkiStore.Drawing) {
        val name = c.kid.name
        val anim = d.phase == "anim"
        card.addView(ui.text(L.t("✨ פיצקי מכין את הסרט של $name", "✨ Pitzki is making $name's film"), 18f, top = 6).apply { typeface = DS.heading })

        // The newest drawing, large (Pitzki himself until the first one is ready).
        val keys = PitzkiScript.CHILD_KEYS
        val newest = keys.map { PitzkiStore.sceneFile(this, c.memberId, it) }.filter { it.exists() }.maxByOrNull { it.lastModified() }
        val hero = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = DS.rounded(DS.buttonSecondary(this@PitzkiActivity), DS.radiusMedia(this@PitzkiActivity))
            clipToOutline = true
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(190)).apply { topMargin = ui.dp(10) }
        }
        card.addView(hero)
        newest?.let { nf -> hero.setOnClickListener { showMedia(nf, null, L.t("הציור האחרון", "The latest drawing")) } }
        val heroKey = newest?.let { it.absolutePath + it.lastModified() } ?: "pitzki"
        val cached = heroShown?.takeIf { it.first == heroKey }?.second
        if (cached != null) hero.setImageBitmap(cached) else bg.execute {
            val b = try {
                if (newest != null) BitmapFactory.decodeFile(newest.absolutePath, BitmapFactory.Options().apply { inSampleSize = 2 })
                else PitzkiStore.art(applicationContext, "pitzki")
            } catch (_: Throwable) { null }
            main.post {
                if (destroyed || b == null) return@post
                heroShown = heroKey to b
                hero.alpha = 0f
                hero.setImageBitmap(b)
                hero.animate().alpha(1f).setDuration(700).start()
            }
        }

        // The scenes filling in: drawn ones show, the next one waits with a sparkle; ▶ once it moves.
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, ui.dp(8), 0, 0) }
        for (k in keys) {
            val f = PitzkiStore.sceneFile(this, c.memberId, k)
            val moving = PitzkiStore.clipFile(this, c.memberId, k).exists()
            val cell = FrameLayout(this).apply {
                background = DS.rounded(DS.buttonSecondary(this@PitzkiActivity), DS.radiusChip(this@PitzkiActivity))
                clipToOutline = true
                layoutParams = LinearLayout.LayoutParams(0, ui.dp(56), 1f).apply { marginEnd = ui.dp(6) }
            }
            if (f.exists()) {
                val img = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; layoutParams = FrameLayout.LayoutParams(match, match) }
                cell.addView(img)
                cell.setOnClickListener { showMedia(f, PitzkiStore.clipFile(this, c.memberId, k).takeIf { it.exists() }, sceneName(k)) }
                bg.execute {
                    val b = try { BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = 8 }) } catch (_: Throwable) { null }
                    main.post { if (!destroyed && b != null) img.setImageBitmap(b) }
                }
                if (moving) cell.addView(TextView(this).apply {
                    text = "▶"; textSize = 14f; setTextColor(DS.onPhoto(this@PitzkiActivity))
                    setShadowLayer(6f, 0f, 1f, DS.scrim(this@PitzkiActivity))
                    layoutParams = FrameLayout.LayoutParams(wrap, wrap, Gravity.CENTER)
                })
            } else cell.addView(TextView(this).apply {
                text = "✨"; textSize = 18f; gravity = Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(match, match)
                alpha = 0.6f
            })
            row.addView(cell)
        }
        card.addView(row)

        // A few playful words about what's happening right now (changing every few seconds).
        val lines = if (anim) listOf(
            L.t("פיצקי ו$name לומדים ללכת ביחד…", "Pitzki and $name learn to walk together…"),
            L.t("מוסיפים חיוכים, נפנופים וכפיים…", "Adding smiles, waves and high-fives…"),
            L.t("החיפושית מתאמנת על ההליכה שלה…", "The beetle practices its walk…"),
            L.t("הציפורים מתכוננות לשיר…", "The birds get ready to sing…"),
            L.t("עוד רגע הכול זז!", "In a moment everything moves!"),
        ) else listOf(
            L.t("פיצקי מחפש את $name ביער…", "Pitzki looks for $name in the forest…"),
            L.t("מציירים את השביל הכי יפה…", "Drawing the prettiest path…"),
            L.t("פיצקי מסדר את הכובע שלו…", "Pitzki fixes his hat…"),
            L.t("$name ופיצקי נפגשים בפעם הראשונה…", "$name and Pitzki meet for the first time…"),
            L.t("מוסיפים פרחים, עלים ואור שמש…", "Adding flowers, leaves and sunshine…"),
        )
        val caption = ui.text(lines[((System.currentTimeMillis() / 4000) % lines.size).toInt()], 15f, R.color.ds_primary, top = 10).apply { typeface = DS.emphasis }
        card.addView(caption)
        main.postDelayed(object : Runnable {
            override fun run() {
                if (destroyed || !caption.isAttachedToWindow) return
                caption.animate().alpha(0f).setDuration(250).withEndAction {
                    caption.text = lines[((System.currentTimeMillis() / 4000) % lines.size).toInt()]
                    caption.animate().alpha(1f).setDuration(250).start()
                }.start()
                main.postDelayed(this, 4000)
            }
        }, 4000)

        // How far, and about how long (updated every second).
        card.addView(etaView(d))
        card.addView(ui.hint(if (anim) L.t("קמים לחיים: ${d.animated} מתוך ${d.total} סצנות", "Coming to life: ${d.animated} of ${d.total} scenes")
            else L.t("ציורים: ${d.done} מתוך ${d.total}", "Drawings: ${d.done} of ${d.total}"), top = 2))
        if (d.note.isNotEmpty()) card.addView(ui.text(d.note, 13f, R.color.ds_error, top = 4))

        // Something to do together meanwhile.
        card.addView(ui.text(L.t("בינתיים, ביחד:", "Meanwhile, together:"), 14f, top = 12).apply { typeface = DS.emphasis })
        lateinit var songBtn: TextView
        songBtn = ui.action(if (waitSong != null) L.t("⏸ לעצור את השיר", "⏸ Stop the song") else L.t("🎵 לשמוע את השיר של פיצקי", "🎵 Hear Pitzki's song")) { toggleWaitSong(songBtn) }
        card.addView(songBtn)
        card.addView(ui.hint(L.t("🎨 להתאמן על הצבעים: לוחצים, ופיצקי אומר את הצבע בעברית ובאנגלית", "🎨 Practice the colors: tap, and Pitzki says the color in Hebrew and English"), top = 4))
        val colors = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, ui.dp(6), 0, 0) }
        for (col in PitzkiScript.ORDER) colors.addView(TextView(this).apply {
            text = col.emoji; textSize = 26f; gravity = Gravity.CENTER
            background = DS.pressable(this@PitzkiActivity, DS.rounded(DS.bg(this@PitzkiActivity), ui.dp(16).toFloat()))
            setPadding(ui.dp(8), ui.dp(6), ui.dp(8), ui.dp(6))
            layoutParams = LinearLayout.LayoutParams(wrap, wrap).apply { marginEnd = ui.dp(6) }
            contentDescription = "${col.he}, ${col.en}"
            emojiColor(this, col)
            setOnClickListener {
                pulse(this)
                stopWaitSong()
                voice.say("${col.he}!") { voice.word(col.en) {} }
            }
        })
        card.addView(ui.scrollRow(colors, top = 0))
        card.addView(ui.hint(L.t("🔔 אפשר לצאת מהאפליקציה: נשלח התראה כשהסרט מוכן.", "🔔 You can leave the app: we'll notify you when the film is ready."), top = 12))
    }

    /** Admin: one close-up drawn and animated again, replacing it for every family. */
    private fun redrawShared(key: String, label: String) {
        AlertDialog.Builder(this)
            .setTitle(L.t("לצייר מחדש: $label?", "Draw again: $label?"))
            .setMessage(L.t("התקריב יצויר מחדש, יונפש מחדש (10 שניות) ויחליף את הקודם בשרת. עלות משוערת: פחות מדולר.",
                "The close-up is drawn again, animated again (10 seconds) and replaces the old one on the server. About under a dollar."))
            .setPositiveButton(L.t("לצייר מחדש", "Draw again")) { _, _ -> if (PitzkiStore.buildShared(this, setOf(key))) { renderSetup(); watchDrawing() } }
            .setNegativeButton(L.t("ביטול", "Cancel"), null)
            .show()
    }

    /**
     * Where Pitzki goes today: each world teaches something else. The forest: colors (Hebrew and English). The
     * train station: opposites, patience and accuracy. Not while a film is being made (it's for one world).
     */
    private fun worldPicker(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(0, ui.dp(12), 0, 0)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            isBaselineAligned = false
        }
        fun card(id: String, emoji: String, title: String, sub: String) = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val on = PitzkiScript.worldId == id
            background = DS.pressable(this@PitzkiActivity, DS.rounded(if (on) ui.color(R.color.ds_selected_bg) else DS.card(this@PitzkiActivity), ui.dp(18).toFloat()))
            // All the worlds side by side, each a third of the width (nothing cut at the edge).
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(ui.dp(6), ui.dp(10), ui.dp(6), ui.dp(10))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { marginStart = ui.dp(4); marginEnd = ui.dp(4) }
            addView(TextView(this@PitzkiActivity).apply { text = emoji; textSize = 26f; gravity = Gravity.CENTER })
            addView(ui.text(title, 14f, if (on) R.color.ds_primary else R.color.ink, bold = true).apply { gravity = Gravity.CENTER })
            addView(ui.hint(sub, top = 2).apply { gravity = Gravity.CENTER; textSize = 12f })
            setOnClickListener {
                if (PitzkiScript.worldId == id) return@setOnClickListener
                if (PitzkiStore.drawing?.finished == false) {
                    android.widget.Toast.makeText(this@PitzkiActivity, L.t("רגע, סרט עוד בהכנה.", "A film is still being made."), android.widget.Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                PitzkiScript.worldId = id
                Settings.prefs(this@PitzkiActivity).edit().putString("pitzki_world", id).apply()
                net.execute { try { PitzkiStore.fetchShared(applicationContext) } catch (_: Exception) {} }
                renderSetup()
            }
        }
        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(0, ui.dp(8), 0, 0)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            isBaselineAligned = false
        }
        row.addView(card("forest", "🌲", L.t("היער", "The forest"), L.t("צבעים בעברית ובאנגלית", "Colors in Hebrew and English")))
        row.addView(card(TrainWorld.ID, "🚂", L.t("תחנת הרכבת", "The train station"), L.t("הפכים וסבלנות", "Opposites and patience")))
        row2.addView(card(FarmWorld.ID, "🐄", L.t("החווה", "The farm"), L.t("מי משמיע את הקול?", "Who makes that sound?")))
        row2.addView(card(NightWorld.ID, "🌙", L.t("לילה טוב", "Good night"), L.t("ירח, כוכבים ושינה", "Moon, stars, sleep")))
        val row3 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(0, ui.dp(8), 0, 0)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            isBaselineAligned = false
        }
        row3.addView(card(PlayWorld.ID, "🧩", L.t("משחקים", "Games"), L.t("סופרים, חשבון ומחזירים חיות", "Counting, sums and animals home")))
        // The arrows game: its own screen (not a walk), open straight away.
        row3.addView(card("arrows", "🧭", L.t("הולכים עם פיצקי", "Walk with Pitzki"), L.t("חצים, קפיצות ותכנון", "Arrows, jumps, planning")).apply {
            setOnClickListener { ArrowGameActivity.open(this@PitzkiActivity, kid.name, kid.girl) }
        })
        // Two by two: each card wide enough for its words.
        return LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(row); addView(row2); addView(row3) }
    }

    private fun adminTools() = Account.isAdmin(this) && Settings.prefs(this).getBoolean("pitzki_admin_tools", false)

    /** A line with a price in it: as is for the admin, without the price for families. */
    private fun price(s: String) = PriceText.forViewer(s, Account.isAdmin(this))

    /** The parent writes the name the way it's said (with niqqud), hears it, and saves it. */
    private fun nameDialog(c: PitzkiStore.Child) {
        val name = c.kid.name
        val field = ui.field(name, PitzkiStore.sayName(this, c.memberId).ifBlank { name }, top = 6)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(20), ui.dp(8), ui.dp(20), 0)
            addView(ui.hint(L.t("כתבו את השם כמו שאומרים אותו, עם ניקוד. למשל אוֹרִי (ולא אוּרִי). אפשר ללחוץ \"להשמיע\" ולנסות עד שזה נשמע נכון.",
                "Write the name the way it's said, with niqqud (e.g. אוֹרִי, not אוּרִי). Tap \"Play\" and try until it sounds right."), top = 0))
            addView(field)
        }
        val d = AlertDialog.Builder(this)
            .setTitle(L.t("איך אומרים את השם?", "How is the name said?"))
            .setView(box)
            .setPositiveButton(L.t("שמירה", "Save")) { _, _ ->
                val said = field.text.toString().trim()
                PitzkiStore.setSayName(this, c.memberId, if (said == name) "" else said)
                renderSetup()
            }
            .setNeutralButton(L.t("להשמיע", "Play"), null)
            .setNegativeButton(L.t("ביטול", "Cancel"), null)
            .create()
        d.setOnShowListener {
            d.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val said = field.text.toString().trim().ifBlank { name }
                val before = PitzkiVoice.sayName
                PitzkiVoice.sayName = name to said
                voice.say(L.t("שלום $name!", "Hello $name!")) { PitzkiVoice.sayName = before }
            }
        }
        d.show()
    }

    /**
     * Admin only: Pitzki's close-ups, made once for every family. Each child's film then needs only the scenes the
     * child is in, which keeps it cheap.
     */
    private fun sharedCard(): View {
        val card = ui.card(top = 14)
        card.addView(ui.text(L.t("מנהל: הסצנות המשותפות של פיצקי", "Admin: Pitzki's shared scenes"), 16f).apply { typeface = DS.emphasis })
        // The real photos sent for drawing must not stay at fal: how many deletions fal confirmed on this phone.
        PitzkiStore.forgetStats(this).let { (ok, all) ->
            if (all > 0) card.addView(ui.hint((if (ok == all) "🧹 ✓ " else "🧹 ⚠️ ") + L.t("מחיקת התמונות ב-fal אחרי הורדה: $ok מתוך $all אושרו",
                "Photos deleted at fal after download: $ok of $all confirmed") + if (ok < all) L.t(" (בדקו שלמפתח של fal יש הרשאת מחיקה)", " (check the fal key may delete)") else "", top = 4))
        }
        // Pitzki's talking scenes have their own part below.
        val keys = (PitzkiStore.SHARED_KEYS - PitzkiTalks.KEYS).filter { !it.startsWith("farm_") }
        val drawn = keys.count { PitzkiStore.sharedScene(this, it).exists() }
        val moving = keys.count { PitzkiStore.sharedClip(this, it).exists() }
        val all = keys.size
        val d = PitzkiStore.drawing?.takeIf { it.member == "shared" }
        if (d != null && !d.finished) {
            card.addView(ui.hint(if (d.phase == "anim") L.t("מנפישים ומעלים לשרת… ${d.animated} מתוך ${d.total}", "Animating and uploading… ${d.animated} of ${d.total}")
                else L.t("מציירים ומעלים לשרת… ${d.done} מתוך ${d.total}", "Drawing and uploading… ${d.done} of ${d.total}"), top = 4))
            card.addView(etaView(d))
            if (d.note.isNotEmpty()) card.addView(ui.text(d.note, 13f, R.color.ds_error, top = 4))
            return card
        }
        if (d != null && d.failed.isNotEmpty()) card.addView(ui.text(d.failed, 14f, R.color.ds_error, top = 4))
        card.addView(ui.hint(L.t("תקריבים של פיצקי לבד עם כל צבע (בלי הילד), אותם לכל המשפחות: $drawn מתוך $all מצוירים, $moving זזים. כל ילד משלם רק על הסצנות שהוא בהן.",
            "Close-ups of Pitzki alone with each color (no child), the same for every family: $drawn of $all drawn, $moving moving. Each child pays only for the scenes they're in."), top = 4))
        // Each close-up: tap one to draw and animate it again (a bad drawing, e.g. two Pitzkis). It replaces the old one.
        if (drawn > 0) {
            card.addView(ui.hint(L.t("לחיצה על תקריב: לצפות בו, ואפשר גם לצייר אותו מחדש.", "Tap a close-up to watch it; you can also draw it again."), top = 8))
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, ui.dp(6), 0, 0) }
            for (key in keys) {
                val f = PitzkiStore.sharedScene(this, key)
                if (!f.exists()) continue
                val label = PColor.values().firstOrNull { it.name.lowercase() == key }?.he ?: if (key == "song") L.t("השיר", "The song") else sceneName(key)
                val cell = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(wrap, wrap).apply { marginEnd = ui.dp(6) }
                    setOnClickListener {
                        showMedia(f, PitzkiStore.sharedClip(this@PitzkiActivity, key).takeIf { it.exists() }, label,
                            L.t("לצייר מחדש", "Draw again") to { redrawShared(key, label) })
                    }
                }
                val img = ImageView(this).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    background = DS.rounded(DS.buttonSecondary(this@PitzkiActivity), DS.radiusChip(this@PitzkiActivity))
                    clipToOutline = true
                    layoutParams = LinearLayout.LayoutParams(ui.dp(96), ui.dp(54))
                }
                cell.addView(img)
                cell.addView(ui.hint(label + if (PitzkiStore.sharedClip(this, key).exists()) " ▶" else "", top = 2))
                bg.execute {
                    val b = try { BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = 8 }) } catch (_: Throwable) { null }
                    main.post { if (!destroyed && b != null) img.setImageBitmap(b) }
                }
                row.addView(cell)
            }
            card.addView(ui.scrollRow(row, top = 0))
        }
        talkSection(card)
        farmSection(card)
        // The lip-sync test: Pitzki sings his song with matching lips.
        val talk = PitzkiStore.drawing?.takeIf { it.member == "talk" }
        if (talk != null && !talk.finished) {
            card.addView(ui.hint(L.t("מכינים את הניסיון: פיצקי שר עם שפתיים מסונכרנות…", "Making the test: Pitzki sings with matching lips…"), top = 8))
            card.addView(etaView(talk))
        }
        else {
            if (talk != null && talk.failed.isNotEmpty()) card.addView(ui.text(talk.failed, 14f, R.color.ds_error, top = 6))
            if (PitzkiStore.talkTest(this).exists()) card.addView(ui.button(L.t("▶ לצפות בניסיון: פיצקי שר", "▶ Watch the test: Pitzki sings"), primary = true, top = 10) { playTalkTest() })
            card.addView(ui.action(L.t("ניסיון: פיצקי שר עם שפתיים מסונכרנות (קרדיט סרט אחד)", "Test: Pitzki sings with matching lips (one film credit)")) {
                if (PitzkiStore.makeTalkTest(this)) { renderSetup(); watchDrawing() }
            })
        }
        if (moving < all) card.addView(ui.button(L.t("ליצור ולהעלות את החסרות", "Make and upload the missing ones"), primary = true, top = 10) {
            AlertDialog.Builder(this)
                .setMessage(L.t("נוצרים ${all - drawn} ציורים ו-${all - moving} הנפשות (קרדיט סרט אחד), והם עולים לשרת לכל המשפחות. צריך שקובץ pitzki-shared-scenes.sql כבר הורץ ב-Supabase.",
                    "${all - drawn} drawings and ${all - moving} animations are made (one film credit) and uploaded for every family. pitzki-shared-scenes.sql must already be run in Supabase."))
                .setPositiveButton(L.t("ליצור", "Make them")) { _, _ -> if (PitzkiStore.buildShared(this)) { renderSetup(); watchDrawing() } }
                .setNegativeButton(L.t("לא עכשיו", "Not now"), null)
                .show()
        })
        // The arrows game's pictures (Pitzki's poses, the things, the clearing): drawn once for every family.
        val arrowsMade = ArrowArt.KEYS.count { PitzkiStore.sharedScene(this, it).exists() }
        card.addView(ui.text(L.t("🧭 משחק החצים: $arrowsMade מתוך ${ArrowArt.KEYS.size} ציורים", "🧭 Arrows game: $arrowsMade of ${ArrowArt.KEYS.size} pictures"), 15f, top = 12).apply { typeface = DS.emphasis })
        if (arrowsMade < ArrowArt.KEYS.size) card.addView(ui.button(L.t("לצייר את ציורי משחק החצים (כ-1.5$)", "Draw the arrows game's pictures (~$1.5)"), top = 6) {
            if (PitzkiStore.buildArrowArt(this)) { renderSetup(); watchDrawing() }
        }) else card.addView(ui.action(L.t("לצייר מחדש את כל ציורי משחק החצים", "Draw all the arrows game's pictures again")) {
            AlertDialog.Builder(this).setMessage(L.t("כל 16 הציורים יצוירו מחדש (כ-1.5$).", "All 16 pictures are drawn again (~$1.5)."))
                .setPositiveButton(L.t("לצייר מחדש", "Draw again")) { _, _ -> if (PitzkiStore.buildArrowArt(this, ArrowArt.KEYS.toSet())) { renderSetup(); watchDrawing() } }
                .setNegativeButton(L.t("ביטול", "Cancel"), null).show()
        })
        // Pitzki's teaching film "one and one": key pictures one after the other, then the shots between them.
        val epDrawn = MathEpisode.FRAME_KEYS.count { PitzkiStore.sharedScene(this, it).exists() }
        val epMoving = MathEpisode.SHOT_KEYS.count { PitzkiStore.sharedClip(this, it).exists() }
        card.addView(ui.text(L.t("🍎 סרט לימוד: אחד ועוד אחד", "🍎 Teaching film: one and one"), 15f, top = 12).apply { typeface = DS.emphasis })
        card.addView(ui.hint(L.t("$epDrawn מתוך ${MathEpisode.FRAME_KEYS.size} ציורים · $epMoving מתוך ${MathEpisode.SHOT_KEYS.size} קטעים", "$epDrawn of ${MathEpisode.FRAME_KEYS.size} pictures · $epMoving of ${MathEpisode.SHOT_KEYS.size} shots"), top = 2))
        PitzkiStore.episodeProblems(this)?.let { probs ->
            val bad = probs.filterValues { it.isNotEmpty() }
            card.addView(ui.hint(if (bad.isEmpty()) L.t("✓ בכל הקטעים מספר התפוחים נכון", "✓ The apples are right in every shot")
                else "⚠️ " + bad.entries.joinToString(" · ") { (k, v) -> L.t("קטע ${k.takeLast(2).toInt()}", "shot ${k.takeLast(2).toInt()}") + " " + v.joinToString(",") { SceneFlags.time(it.first) } }, top = 2))
        }
        if (PitzkiStore.episodeReady(this)) {
            card.addView(ui.button(L.t("▶ לצפות בסרט", "▶ Watch the film"), primary = true, top = 6) { EpisodeActivity.open(this) })
            card.addView(ui.action(L.t("לעשות מחדש קטע אחד…", "Make one shot again…")) {
                val names = MathEpisode.SHOTS.map { "${it.key.takeLast(2).toInt()}. ${it.line}" }.toTypedArray()
                AlertDialog.Builder(this).setTitle(L.t("איזה קטע?", "Which shot?"))
                    .setItems(names) { _, i -> if (PitzkiStore.buildEpisode(this, setOf(MathEpisode.SHOTS[i].key))) { renderSetup(); watchDrawing() } }
                    .setNegativeButton(L.t("ביטול", "Cancel"), null).show()
            })
            card.addView(ui.action(L.t("לצייר מחדש מציור מסוים והלאה…", "Draw again from one picture on…")) {
                val names = MathEpisode.FRAMES.mapIndexed { i, f -> "${i + 1}. ${f.change.take(60)}…" }.toTypedArray()
                AlertDialog.Builder(this).setTitle(L.t("מאיזה ציור? (כל מה שאחריו יצויר מחדש)", "From which picture? (everything after it is drawn again)"))
                    .setItems(names) { _, i -> if (PitzkiStore.buildEpisode(this, setOf(MathEpisode.FRAMES[i].key))) { renderSetup(); watchDrawing() } }
                    .setNegativeButton(L.t("ביטול", "Cancel"), null).show()
            })
        } else card.addView(ui.button(L.t("ליצור את הסרט (כ-7$)", "Make the film (~$7)"), primary = epDrawn == 0, top = 6) {
            AlertDialog.Builder(this)
                .setMessage(L.t("מצוירים ${MathEpisode.FRAME_KEYS.size} ציורים, כל אחד מהקודם, ואז ${MathEpisode.SHOT_KEYS.size} קטעים של 5 שניות. מה שכבר נוצר נשמר. הסרט עולה לשרת לכל המשפחות.",
                    "${MathEpisode.FRAME_KEYS.size} pictures are drawn, each from the one before, then ${MathEpisode.SHOT_KEYS.size} 5-second shots. What's made is kept. The film goes to the server for every family."))
                .setPositiveButton(L.t("ליצור", "Make it")) { _, _ -> if (PitzkiStore.buildEpisode(this)) { renderSetup(); watchDrawing() } }
                .setNegativeButton(L.t("ביטול", "Cancel"), null).show()
        })
        // A test of Pitzki teaching math in a real scene (the apples are part of the film, not pasted on it).
        val mathClip = PitzkiStore.sharedClip(this, MathLesson.SHOT)
        card.addView(ui.text(L.t("🎬 ניסיון: פיצקי מלמד 1+1", "🎬 Test: Pitzki teaches 1+1"), 15f, top = 12).apply { typeface = DS.emphasis })
        if (mathClip.exists()) {
            val bad = MathLesson.problems(this)
            // Not counted yet (or counted the old way): counted now, in the background.
            if (bad == null && !mathCounting) { mathCounting = true; Thread { try { MathLesson.check(applicationContext) } catch (_: Exception) {}; main.post { mathCounting = false; if (!destroyed && !inLesson) renderSetup() } }.start() }
            card.addView(ui.text(when {
                bad == null -> L.t("התפוחים עוד לא נספרו", "The apples weren't counted yet")
                bad.isEmpty() -> L.t("✓ בכל הקטע נספרו בדיוק 2 תפוחים", "✓ Exactly 2 apples all through the shot")
                else -> L.t("⚠️ מספר התפוחים השתנה ב: ", "⚠️ The apples' count changed at: ") + bad.joinToString(", ") { SceneFlags.time(it.first) }
            }, 14f, top = 4))
            card.addView(ui.button(L.t("▶ לצפות בניסיון", "▶ Watch the test"), primary = true, top = 6) { playFile(mathClip, "pitzki_math_test.mp4") })
            card.addView(ui.action(L.t("לעשות מחדש (כ-1.5$)", "Make it again (~$1.5)")) {
                if (PitzkiStore.buildMathLesson(this, again = true)) { renderSetup(); watchDrawing() }
            })
        } else card.addView(ui.button(L.t("ליצור קטע ניסיון של 5 שניות (כ-1.5$)", "Make a 5-second test shot (~$1.5)"), top = 6) {
            if (PitzkiStore.buildMathLesson(this)) { renderSetup(); watchDrawing() }
        })
        return card
    }

    @Volatile private var mathCounting = false

    private fun playFile(src: java.io.File, name: String) {
        try {
            val dir = java.io.File(cacheDir, "share").apply { mkdirs() }
            val f = java.io.File(dir, name)
            src.copyTo(f, overwrite = true)
            val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.files", f)
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (_: Exception) {
            android.widget.Toast.makeText(this, L.t("לא הצלחנו לפתוח את הסרטון", "Couldn't open the video"), android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    /** Which talking scene the chosen video goes to (and whether it's the continuation). */
    private var importFor: Pair<String, Boolean>? = null
    private val videoPicker = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        val to = importFor
        importFor = null
        if (uri == null || to == null) return@registerForActivityResult
        android.widget.Toast.makeText(this, L.t("מייבאים ומעלים לשרת…", "Importing and uploading…"), android.widget.Toast.LENGTH_SHORT).show()
        bg.execute {
            val problem = PitzkiStore.importTalk(this, to.first, uri, to.second)
            main.post {
                if (destroyed) return@post
                android.widget.Toast.makeText(this, problem ?: L.t("הסרטון נשמר ועלה לשרת ✓", "The video is saved and uploaded ✓"), android.widget.Toast.LENGTH_LONG).show()
                if (!inLesson) renderSetup()
            }
        }
    }

    private fun importTalk(key: String, second: Boolean) {
        importFor = key to second
        try { videoPicker.launch(arrayOf("video/*")) } catch (_: Exception) { importFor = null }
    }

    /**
     * Admin: Pitzki's talking scenes (the train world): a video from Hedra imported for each, or made here through
     * fal (picture + his voice + lips); and his lines as mp3 files, to make the videos in Hedra with the same voice.
     */
    private fun talkSection(card: LinearLayout) {
        card.addView(ui.text(L.t("פיצקי מדבר (עולם הרכבת)", "Pitzki talks (the train world)"), 15f, top = 16).apply { typeface = DS.emphasis })
        val d = PitzkiStore.drawing?.takeIf { it.member == "talks" }
        if (d != null && !d.finished) {
            card.addView(ui.hint(L.t("יוצרים דרך fal ומעלים לשרת… ${d.animated} מתוך ${d.total}", "Making through fal and uploading… ${d.animated} of ${d.total}"), top = 4))
            if (d.note.isNotEmpty()) card.addView(ui.hint(d.note, top = 2))
            card.addView(etaView(d))
            return
        }
        if (d != null && d.failed.isNotEmpty()) card.addView(ui.text(d.failed, 14f, R.color.ds_error, top = 4))
        val ready = TrainWorld.TALKS.count { PitzkiStore.sharedTalk(this, it.key).exists() }
        card.addView(ui.hint(L.t("$ready מתוך ${TrainWorld.TALKS.size} מוכנות. לחיצה על סצנה: לצפות, לייבא סרטון מ-Hedra, או ליצור מחדש דרך fal.",
            "$ready of ${TrainWorld.TALKS.size} ready. Tap a scene: watch, import a video from Hedra, or make it again through fal."), top = 4))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, ui.dp(6), 0, 0) }
        for (t in TrainWorld.TALKS) {
            val label = sceneName(t.key)
            val pic = PitzkiStore.sharedScene(this, t.key)
            val talk = PitzkiStore.sharedTalk(this, t.key)
            val more = PitzkiStore.sharedTalk2(this, t.key)
            val actions = ArrayList<Pair<String, () -> Unit>>()
            actions.add(L.t("לייבא סרטון מ-Hedra", "Import a video from Hedra") to { importTalk(t.key, false) })
            if (t.more.isNotBlank() && talk.exists()) actions.add(L.t("לייבא את ההמשך", "Import the continuation") to { importTalk(t.key, true) })
            actions.add(L.t("ליצור מחדש דרך fal (~\$1)", "Make again through fal (~\$1)") to { remakeTalk(t.key, label) })
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(wrap, wrap).apply { marginEnd = ui.dp(6) }
                setOnClickListener {
                    if (pic.exists()) showMedia(pic, talk.takeIf { it.exists() }, label, *actions.toTypedArray())
                    else AlertDialog.Builder(this@PitzkiActivity).setTitle(label)
                        .setItems(actions.map { it.first }.toTypedArray()) { _, i -> actions[i].second() }.show()
                }
            }
            val img = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = DS.rounded(DS.buttonSecondary(this@PitzkiActivity), DS.radiusChip(this@PitzkiActivity))
                clipToOutline = true
                layoutParams = LinearLayout.LayoutParams(ui.dp(96), ui.dp(54))
            }
            cell.addView(img)
            val mark = when {
                !talk.exists() -> " ·"
                t.more.isNotBlank() && !more.exists() -> " ▶½"
                else -> " ▶"
            }
            cell.addView(ui.hint(label + mark, top = 2))
            if (pic.exists()) bg.execute {
                val b = try { BitmapFactory.decodeFile(pic.absolutePath, BitmapFactory.Options().apply { inSampleSize = 8 }) } catch (_: Throwable) { null }
                main.post { if (!destroyed && b != null) img.setImageBitmap(b) }
            }
            row.addView(cell)
        }
        card.addView(ui.scrollRow(row, top = 0))
        val missing = PitzkiStore.talksMissing(this)
        if (missing.isNotEmpty()) card.addView(ui.button(L.t("ליצור דרך fal את מה שחסר (${missing.size})", "Make the missing ones through fal (${missing.size})"), primary = true, top = 10) {
            AlertDialog.Builder(this)
                .setMessage(L.t("נוצרים ${missing.size} סרטונים: ציור (אם אין), הקול של פיצקי, ושפתיים מסונכרנות. בערך \$1 לסרטון (קרדיט סרט אחד), והם עולים לשרת לכל המשפחות. סצנה שתייבא מ-Hedra לפני כן לא תיווצר.",
                    "${missing.size} videos are made: a drawing (if missing), Pitzki's voice and matching lips. About \$1 each (one film credit), uploaded for every family. A scene you import from Hedra first isn't made."))
                .setPositiveButton(L.t("ליצור", "Make them")) { _, _ -> if (PitzkiStore.buildTalks(this)) { renderSetup(); watchDrawing() } }
                .setNegativeButton(L.t("לא עכשיו", "Not now"), null)
                .show()
        })
        card.addView(ui.action(L.t("לשלוח את הקול של פיצקי (mp3) ל-Hedra", "Send Pitzki's voice (mp3) for Hedra")) { shareTalkVoices() })
        // The chat's place: Pitzki on his stump in the forest with his animal friends (a picture and a moving loop).
        val chat = PitzkiStore.drawing?.takeIf { it.member == "chat" }
        card.addView(ui.text(L.t("השיחה עם פיצקי: המקום שלו", "Talking with Pitzki: his place"), 15f, top = 16).apply { typeface = DS.emphasis })
        if (chat != null && !chat.finished) { card.addView(ui.hint(L.t("מציירים ומנפישים…", "Drawing and animating…"), top = 4)); card.addView(etaView(chat)); return }
        if (chat != null && chat.failed.isNotEmpty()) card.addView(ui.text(chat.failed, 14f, R.color.ds_error, top = 4))
        val place = PitzkiStore.sharedScene(this, PitzkiStore.CHAT)
        val moving = PitzkiStore.sharedClip(this, PitzkiStore.CHAT)
        val listening = PitzkiStore.sharedClip(this, PitzkiStore.CHAT_LISTEN)
        card.addView(ui.hint(when {
            moving.exists() -> L.t("✓ מוכן: פיצקי על גזע ביער עם הציפורים, הינשוף והארנב.", "✓ Ready: Pitzki on a stump in the forest with the birds, the owl and the bunny.")
            else -> L.t("עד שייווצר, השיחה משתמשת בסצנת השיר של פיצקי ביער.", "Until it's made, the talk uses Pitzki's song scene in the forest.")
        }, top = 4))
        if (place.exists()) card.addView(ui.action(L.t("לצפות", "Watch")) { showMedia(place, moving.takeIf { it.exists() }, L.t("המקום של פיצקי", "Pitzki's place"),
            L.t("להנפיש מחדש: מסתכל לילד בעיניים (~\$0.7)", "Animate again: looking at the child (~\$0.7)") to { if (PitzkiStore.buildChatScene(this, redo = true, motionOnly = true)) { renderSetup(); watchDrawing() } },
            L.t("לצייר ולהנפיש מחדש (~\$1)", "Draw and animate again (~\$1)") to { if (PitzkiStore.buildChatScene(this, redo = true)) { renderSetup(); watchDrawing() } }) })
        if (place.exists() && listening.exists()) card.addView(ui.action(L.t("לצפות: פיצקי מקשיב ומסתכל על הילד", "Watch: Pitzki listening, looking at the child")) {
            showMedia(place, listening, L.t("פיצקי מקשיב", "Pitzki listening")) })
        if (!moving.exists() || !listening.exists()) card.addView(ui.button(
            if (moving.exists()) L.t("להוסיף: פיצקי מקשיב ומסתכל על הילד (~\$0.7)", "Add: Pitzki listening, looking at the child (~\$0.7)")
            else L.t("ליצור את המקום של פיצקי לשיחה (~\$1.5)", "Make Pitzki's chat place (~\$1.5)"), primary = false, top = 8) {
            if (PitzkiStore.buildChatScene(this)) { renderSetup(); watchDrawing() }
        })
    }

    private fun remakeTalk(key: String, label: String) {
        AlertDialog.Builder(this)
            .setTitle(L.t("ליצור מחדש: $label?", "Make again: $label?"))
            .setMessage(L.t("הציור, הסרטון וההמשך שלו נמחקים ונוצרים מחדש דרך fal, ומחליפים את הקודמים בשרת. בערך \$1.",
                "The drawing, the video and its continuation are deleted and made again through fal, replacing the old ones on the server. About \$1."))
            .setPositiveButton(L.t("ליצור מחדש", "Make again")) { _, _ -> if (PitzkiStore.buildTalks(this, setOf(key))) { renderSetup(); watchDrawing() } }
            .setNegativeButton(L.t("ביטול", "Cancel"), null)
            .show()
    }

    /** Pitzki's lines for the talking scenes, in his voice (the steady settings), shared to any app (WhatsApp, Drive…). */
    private fun shareTalkVoices() {
        android.widget.Toast.makeText(this, L.t("מכינים את הקבצים…", "Preparing the files…"), android.widget.Toast.LENGTH_SHORT).show()
        bg.execute {
            val files = try { PitzkiStore.talkVoices(this) } catch (e: Exception) {
                main.post { if (!destroyed) android.widget.Toast.makeText(this, e.message ?: L.t("לא הצלחנו להכין את הקול", "Couldn't prepare the voice"), android.widget.Toast.LENGTH_LONG).show() }
                return@execute
            }
            main.post {
                if (destroyed) return@post
                try {
                    val uris = ArrayList(files.map { androidx.core.content.FileProvider.getUriForFile(this, "$packageName.files", it) })
                    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND_MULTIPLE).setType("audio/mpeg")
                        .putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                        L.t("לשלוח את הקול של פיצקי", "Send Pitzki's voice")))
                } catch (_: Exception) {
                    android.widget.Toast.makeText(this, L.t("לא הצלחנו לשתף", "Couldn't share"), android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /** Opens the lip-sync test in the phone's video player (with its sound). */
    private fun playTalkTest() {
        try {
            val dir = java.io.File(cacheDir, "share").apply { mkdirs() }
            val f = java.io.File(dir, "pitzki_song_test.mp4")
            PitzkiStore.talkTest(this).copyTo(f, overwrite = true)
            val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.files", f)
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (_: Exception) {
            android.widget.Toast.makeText(this, L.t("לא הצלחנו לפתוח את הסרטון", "Couldn't open the video"), android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    /** The child's scenes (newest first: a scene made again is the first one seen); tap one to watch it. */
    private fun scenesStrip(c: PitzkiStore.Child): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, ui.dp(8), 0, 0) }
        fun stamp(k: String) = maxOf(PitzkiStore.sceneFile(this, c.memberId, k).lastModified(), PitzkiStore.clipFile(this, c.memberId, k).lastModified())
        for (n in PitzkiScript.CHILD_KEYS.filter { PitzkiStore.sceneFile(this, c.memberId, it).exists() }.sortedByDescending { stamp(it) }) {
            val f = PitzkiStore.sceneFile(this, c.memberId, n)
            val clip = PitzkiStore.clipFile(this, c.memberId, n).takeIf { it.exists() }
            // Looked at by itself (extra faces): a ⚠️ with the times, until they're cut.
            val flags = clip?.let { SceneScan.open(this, it) }.orEmpty()
            if (clip != null && SceneScan.result(this, clip) == null) SceneScan.scan(this, clip) { if (!destroyed && !inLesson && showSettings) renderSetup() }
            val title = if (flags.isEmpty()) sceneName(n) else sceneName(n) + L.t(" · ⚠️ ייתכן שמופיעה דמות נוספת ב-", " · ⚠️ maybe someone extra at ") +
                flags.joinToString(", ") { SceneScan.time(it.first) + "–" + SceneScan.time(it.last) }
            val cell = FrameLayout(this).apply {
                background = DS.rounded(DS.buttonSecondary(this@PitzkiActivity), DS.radiusChip(this@PitzkiActivity))
                clipToOutline = true
                layoutParams = LinearLayout.LayoutParams(ui.dp(112), ui.dp(64)).apply { marginEnd = ui.dp(6) }
                contentDescription = title
                setOnClickListener {
                    val more = PitzkiStore.moreFile(this@PitzkiActivity, c.memberId, n).takeIf { it.exists() && it.length() > 0 }
                    if (clip != null && more != null) showMedia(f, clip, title,
                        L.t("✂️ לגזור קטע מהסרטון (חינם)", "✂️ Cut a part out (free)") to { cutDialog(clip) },
                        L.t("▶ לצפות בהמשך הסצנה (וגם לגזור בו)", "▶ Watch the rest of the scene (and cut there)") to {
                            showMedia(f, more, sceneName(n) + L.t(" · ההמשך", " · the rest"),
                                L.t("✂️ לגזור קטע מההמשך (חינם)", "✂️ Cut a part of the rest (free)") to { cutDialog(more) })
                        },
                        price(L.t("להנפיש מחדש רק את הסצנה (כ-70 סנט)", "Animate just this scene again (~70¢)")) to { reanimateOne(c, n) },
                        L.t("לצייר מחדש את הסצנה", "Draw this scene again") to { redrawOne(c, n) },
                        L.t("למחוק את הסצנה", "Delete this scene") to { deleteOne(c, n) })
                    else if (clip != null) showMedia(f, clip, title,
                        L.t("✂️ לגזור קטע מהסרטון (חינם)", "✂️ Cut a part out (free)") to { cutDialog(clip) },
                        price(L.t("להנפיש מחדש רק את הסצנה (כ-70 סנט)", "Animate just this scene again (~70¢)")) to { reanimateOne(c, n) },
                        L.t("לצייר מחדש את הסצנה", "Draw this scene again") to { redrawOne(c, n) },
                        L.t("למחוק את הסצנה", "Delete this scene") to { deleteOne(c, n) })
                    else showMedia(f, clip, sceneName(n),
                        L.t("לצייר מחדש את הסצנה", "Draw this scene again") to { redrawOne(c, n) },
                        L.t("למחוק את הסצנה", "Delete this scene") to { deleteOne(c, n) })
                }
            }
            val img = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; layoutParams = FrameLayout.LayoutParams(match, match) }
            cell.addView(img)
            if (clip != null) cell.addView(TextView(this).apply {
                text = "▶"; textSize = 16f; setTextColor(DS.onPhoto(this@PitzkiActivity))
                setShadowLayer(6f, 0f, 1f, DS.scrim(this@PitzkiActivity))
                layoutParams = FrameLayout.LayoutParams(wrap, wrap, Gravity.CENTER)
            })
            if (flags.isNotEmpty()) cell.addView(TextView(this).apply {
                text = "⚠️"; textSize = 16f
                setShadowLayer(6f, 0f, 1f, DS.scrim(this@PitzkiActivity))
                layoutParams = FrameLayout.LayoutParams(wrap, wrap, Gravity.TOP or Gravity.END).apply { topMargin = ui.dp(2); marginEnd = ui.dp(4) }
            })
            bg.execute {
                val b = try { BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = 4 }) } catch (_: Throwable) { null }
                main.post { if (!destroyed && b != null) img.setImageBitmap(b) }
            }
            row.addView(cell)
        }
        return ui.scrollRow(row, top = 0)
    }

    /**
     * Some scenes were drawn the older way (less like the child, and they don't match the new ones): one tap draws
     * just those again, so the whole film is one look.
     */
    private fun syncOld(card: LinearLayout, c: PitzkiStore.Child) {
        val old = PitzkiStore.oldScenes(this, c)
        if (old.isEmpty()) return
        val all = old.size == PitzkiScript.CHILD_KEYS.size
        card.addView(ui.text(
            if (all) L.t("הסצנות צוירו בשיטה הישנה, פחות דומה ל${c.kid.name}.", "These scenes were drawn the older way, less like ${c.kid.name}.")
            else L.t("${old.size} מתוך ${PitzkiScript.CHILD_KEYS.size} סצנות צוירו בשיטה הישנה, ולכן ${c.kid.name} נראה בהן אחרת.", "${old.size} of ${PitzkiScript.CHILD_KEYS.size} scenes were drawn the older way, so ${c.kid.name} looks different in them."),
            14f, R.color.ds_error, top = 10))
        card.addView(ui.button(L.t("לצייר מחדש רק אותן (${old.size})", "Draw just those again (${old.size})"), primary = true, top = 6) {
            AlertDialog.Builder(this)
                .setMessage(price(L.t("${old.size} הסצנות הישנות יצוירו מחדש במודל של הספר ויונפשו מחדש, כדי שכל הסרט ייראה אותו דבר. השאר נשארות. עלות משוערת: כ-${"%.1f".format(java.util.Locale.US, old.size * 0.8)}$.",
                    "The ${old.size} old scenes are drawn again with the book's model and animated again, so the whole film looks the same. The rest stay. About $${"%.1f".format(java.util.Locale.US, old.size * 0.8)}.")))
                .setPositiveButton(L.t("לצייר מחדש", "Draw again")) { _, _ -> if (PitzkiStore.redrawScenes(this, c, old)) { renderSetup(); watchDrawing() } }
                .setNegativeButton(L.t("ביטול", "Cancel"), null)
                .show()
        })
    }

    /** One of the child's scenes drawn and animated again (the others stay). */
    private fun reanimateOne(c: PitzkiStore.Child, key: String) {
        AlertDialog.Builder(this)
            .setTitle(L.t("להנפיש מחדש: ${sceneName(key)}?", "Animate again: ${sceneName(key)}?"))
            .setMessage(price(L.t("הציור נשאר, ורק התנועה של הסצנה הזו נעשית מחדש. שאר הסצנות לא משתנות. בערך 70 סנט.",
                "The drawing stays; only this scene's motion is made again. The other scenes don't change. About 70 cents.")))
            .setPositiveButton(L.t("להנפיש מחדש", "Animate again")) { _, _ ->
                if (PitzkiStore.reanimateOne(this, c, key)) { renderSetup(); watchDrawing() }
                else android.widget.Toast.makeText(this, L.t("משהו אחר עוד רץ. רגע.", "Something else is still running."), android.widget.Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(L.t("ביטול", "Cancel"), null)
            .show()
    }

    private fun redrawOne(c: PitzkiStore.Child, key: String) {
        AlertDialog.Builder(this)
            .setTitle(L.t("לצייר מחדש: ${sceneName(key)}?", "Draw again: ${sceneName(key)}?"))
            .setMessage(L.t("הסצנה תצויר מחדש במודל של הספר ותונפש מחדש. שאר הסצנות נשארות. עלות משוערת: פחות מדולר.",
                "The scene is drawn again with the book's model and animated again. The other scenes stay. About under a dollar."))
            .setPositiveButton(L.t("לצייר מחדש", "Draw again")) { _, _ ->
                if (PitzkiStore.redrawScenes(this, c, listOf(key))) { renderSetup(); watchDrawing() }
                else android.widget.Toast.makeText(this, L.t("משהו אחר עוד רץ. רגע.", "Something else is still running."), android.widget.Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(L.t("ביטול", "Cancel"), null)
            .show()
    }

    private fun deleteOne(c: PitzkiStore.Child, key: String) {
        AlertDialog.Builder(this)
            .setMessage(L.t("למחוק את הסצנה \"${sceneName(key)}\"? בטיול יופיעו במקומה היער והתמונה של ${c.kid.name}, עד שתיצרו אותה שוב.",
                "Delete \"${sceneName(key)}\"? The walk shows the forest and ${c.kid.name}'s picture there until you make it again."))
            .setPositiveButton(L.t("למחוק", "Delete")) { _, _ -> PitzkiStore.deleteScene(this, c, key); renderSetup() }
            .setNegativeButton(L.t("ביטול", "Cancel"), null)
            .show()
    }

    private fun sceneName(key: String) = when (key) {
        "intro" -> L.t("הפתיחה", "The opening")
        "walk" -> L.t("הליכה ביער", "Walking in the forest")
        "walk2" -> L.t("ממשיכים פנימה", "Deeper in")
        "walk3" -> L.t("קופצים על האבנים", "Hopping on the stones")
        "walk4" -> L.t("על הגשר", "On the bridge")
        "end" -> L.t("הסיום", "The end")
        "train_intro" -> L.t("מגיעים לתחנה", "Arriving at the station")
        "train_ticket" -> L.t("הכרטיס", "The ticket")
        "train_board" -> L.t("עולים לרכבת", "Getting on")
        "train_window" -> L.t("ליד החלון", "By the window")
        "train_end" -> L.t("הגענו", "We're here")
        "train_big" -> L.t("גדול וקטן", "Big and small")
        "train_line" -> L.t("הקו הצהוב", "The yellow line")
        "train_light" -> L.t("רמזור", "The light")
        "train_fast" -> L.t("מהר ולאט", "Fast and slow")
        FarmWorld.KID_INTRO -> L.t("מגיעים לחווה", "Arriving at the farm")
        "farm_dog_kid" -> L.t("ליטוף לכלב", "Petting the dog")
        "farm_sheep_kid" -> L.t("חציר לכבשה", "Hay for the sheep")
        "farm_end_kid" -> L.t("נתראה בהרפתקה", "See you next time")
        "night_intro" -> L.t("טיול ערב", "An evening walk")
        "play_intro" -> L.t("החיות ברחו", "The animals ran off")
        "num_intro" -> L.t("סופרים יחד", "Counting together")
        "num_end" -> L.t("כל הכבוד", "Well done")
        "num_count" -> L.t("סופרים בקול", "Counting out loud")
        "num_tap" -> L.t("סופרים תפוחים", "Counting apples")
        "num_sum" -> L.t("זה ועוד זה", "This and that")
        "num_more" -> L.t("עוד חשבון", "More sums")
        "play_yard" -> L.t("מחזירים הביתה", "Taking them home")
        "play_end" -> L.t("כולם בבית", "Everyone's home")
        "night_lantern" -> L.t("הפנס הקטן", "The little lantern")
        "night_end" -> L.t("לילה טוב", "Good night")
        "night_hello" -> L.t("השמש הולכת לישון", "The sun goes to sleep")
        "night_moon" -> L.t("הירח", "The moon")
        "night_stars" -> L.t("כוכבים", "Stars")
        "night_owl" -> L.t("הינשוף", "The owl")
        "night_fireflies" -> L.t("גחליליות", "Fireflies")
        "night_dark" -> L.t("החושך", "The dark")
        "night_sleep" -> L.t("כולם ישנים", "Everyone sleeps")
        "train_hello" -> L.t("בוקר טוב בתחנה", "Good morning at the station")
        "train_owl" -> L.t("בקופה עם הינשוף", "At the owl's ticket window")
        "train_feel" -> L.t("מתרגשים ושמחים", "Excited and happy")
        else -> PColor.values().firstOrNull { it.name.lowercase() == key }?.he ?: key
    }

    /**
     * A picture large (the whole screen), or its animation playing in a loop when it has one. Tap to close.
     * [action]: an extra button (the admin's "draw again").
     */
    /**
     * Cut a bad moment out of a scene's animation: from second X to second Y it's skipped (the video itself stays as
     * it is, so it can be undone). Free.
     */
    private fun cutDialog(clip: java.io.File) = trimEditor(clip)

    /**
     * Cutting a bad moment out, like in a video editor: the video above, its frames in a strip, and two handles on
     * the strip that mark the part to take out. Moving a handle shows that exact frame. Watch the part, watch the
     * whole without it, then cut. Free; the video itself stays (so it can be undone): the part is skipped when played.
     */
    private fun trimEditor(clip: java.io.File) {
        val durMs = try {
            val r = android.media.MediaMetadataRetriever()
            try { r.setDataSource(clip.absolutePath); r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L } finally { r.release() }
        } catch (_: Exception) { 0L }
        if (durMs < 1000) { android.widget.Toast.makeText(this, L.t("לא הצלחנו לפתוח את הסרטון", "Couldn't open the video"), android.widget.Toast.LENGTH_SHORT).show(); return }
        val dlg = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ui.color(R.color.ds_video_bg))
            setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(16))
        }
        fun white(t: String, size: Float) = TextView(this).apply {
            text = t; textSize = size; gravity = Gravity.CENTER; setTextColor(DS.onPhoto(this@PitzkiActivity))
        }
        root.addView(white(L.t("✂️ גזירת קטע", "✂️ Cut a part"), 18f).apply { typeface = DS.emphasis })
        // The video, paused on the frame a handle points at.
        val video = android.widget.VideoView(this)
        val videoBox = FrameLayout(this).apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = ui.dp(8) } }
        videoBox.addView(video, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        root.addView(videoBox)
        var ready = false
        var playingTo = -1L
        video.setVideoPath(clip.absolutePath)
        video.setOnPreparedListener { mp -> try { mp.setVolume(0f, 0f) } catch (_: Exception) {}; ready = true; video.start(); video.pause(); video.seekTo((durMs * 0.3).toInt()) }
        // The frames, with the handles over them.
        val strip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_LTR }
        val frames = FrameLayout(this).apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(56)).apply { topMargin = ui.dp(10) } }
        frames.addView(strip, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply { leftMargin = ui.dp(14); rightMargin = ui.dp(14) })
        val n = 8
        val cells = (0 until n).map { ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0.85f } }
        cells.forEach { strip.addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)) }
        bg.execute {
            val r = android.media.MediaMetadataRetriever()
            try {
                r.setDataSource(clip.absolutePath)
                for (k in 0 until n) {
                    val b = try { r.getFrameAtTime(durMs * 1000 * (2 * k + 1) / (2 * n), android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC) } catch (_: Exception) { null }
                    val small = b?.let { android.graphics.Bitmap.createScaledBitmap(it, 160, 90, true) }
                    main.post { if (!destroyed && small != null) cells[k].setImageBitmap(small) }
                }
            } catch (_: Exception) { } finally { try { r.release() } catch (_: Exception) {} }
        }
        val slider = com.google.android.material.slider.RangeSlider(this).apply {
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            valueFrom = 0f; valueTo = durMs.toFloat()
            values = listOf((durMs * 0.3f), (durMs * 0.5f))
            setMinSeparationValue(200f)
            setLabelFormatter { v -> String.format(java.util.Locale.US, "%.1f", v / 1000f) }
        }
        frames.addView(slider, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL))
        root.addView(frames)
        val label = white("", 15f).apply { setPadding(0, ui.dp(6), 0, 0) }
        root.addView(label)
        val done = white("", 13f).apply { alpha = 0.8f }
        root.addView(done)
        fun sec(v: Float) = String.format(java.util.Locale.US, "%.1f", v / 1000f)
        fun show() {
            val v = slider.values
            label.text = L.t("החלק שיוסר: מ-${sec(v[0])} עד ${sec(v[1])} שניות (${sec(v[1] - v[0])} שנ׳)", "The part to take out: ${sec(v[0])}–${sec(v[1])} s (${sec(v[1] - v[0])} s)")
            val cuts = PitzkiStore.cuts(this, clip)
            done.text = if (cuts.isEmpty()) "" else L.t("כבר נגזר: ", "Already cut: ") + cuts.joinToString(", ") { "${sec(it.first.toFloat())}–${sec(it.second.toFloat())}" }
        }
        show()
        // Moving a handle: that frame is shown, paused.
        slider.addOnChangeListener { sl, value, fromUser ->
            show()
            if (fromUser && ready) { playingTo = -1; try { video.pause(); video.seekTo(value.toInt()) } catch (_: Exception) {} }
        }
        // Playing: the part only, or the whole without the cut parts.
        var skipCuts = false
        val tick = object : Runnable {
            override fun run() {
                if (!dlg.isShowing) return
                if (playingTo >= 0 && video.isPlaying) {
                    val at = video.currentPosition.toLong()
                    if (skipCuts) {
                        val gaps = PitzkiStore.cuts(this@PitzkiActivity, clip) + (slider.values[0].toLong() to slider.values[1].toLong())
                        gaps.firstOrNull { at >= it.first && at < it.second }?.let { video.seekTo(it.second.toInt()) }
                    }
                    if (at >= playingTo) { video.pause(); playingTo = -1 }
                }
                main.postDelayed(this, 50)
            }
        }
        main.post(tick)
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(0, ui.dp(8), 0, 0) }
        fun small(t: String, primary: Boolean, f: () -> Unit) = ui.button(t, primary = primary, top = 0) { f() }.apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = ui.dp(4); marginEnd = ui.dp(4) }
        }
        buttons.addView(small(L.t("▶ את החלק", "▶ The part"), false) {
            if (!ready) return@small
            skipCuts = false; video.seekTo(slider.values[0].toInt()); playingTo = slider.values[1].toLong(); video.start()
        })
        buttons.addView(small(L.t("▶ בלי החלק", "▶ Without it"), false) {
            if (!ready) return@small
            skipCuts = true; video.seekTo(0); playingTo = durMs; video.start()
        })
        root.addView(buttons)
        val buttons2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(0, ui.dp(6), 0, 0) }
        buttons2.addView(small(L.t("✂️ להוריד את החלק", "✂️ Take it out"), true) {
            val v = slider.values
            PitzkiStore.addCut(this, clip, v[0].toLong(), v[1].toLong())
            show()
            android.widget.Toast.makeText(this, L.t("נגזר ✓ אפשר ללחוץ \"בלי החלק\" לראות", "Cut ✓ Tap \"Without it\" to see"), android.widget.Toast.LENGTH_SHORT).show()
        })
        buttons2.addView(small(L.t("לבטל גזירות", "Undo cuts"), false) { PitzkiStore.clearCuts(this, clip); show() })
        buttons2.addView(small(L.t("סיום", "Done"), false) { dlg.dismiss() })
        root.addView(buttons2)
        root.setOnApplyWindowInsetsListener { _, ins ->
            @Suppress("DEPRECATION")
            root.setPadding(ui.dp(16), ui.dp(12) + ins.systemWindowInsetTop, ui.dp(16), ui.dp(16) + ins.systemWindowInsetBottom)
            ins
        }
        dlg.setContentView(root)
        dlg.setOnDismissListener { try { video.stopPlayback() } catch (_: Exception) {} }
        dlg.show()
    }

    private fun showMedia(image: java.io.File, clip: java.io.File?, title: String, vararg actions: Pair<String, () -> Unit>) {
        val dlg = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val root = FrameLayout(this).apply { setBackgroundColor(ui.color(R.color.ds_video_bg)) }
        if (clip != null) {
            val v = android.widget.VideoView(this).apply { layoutParams = FrameLayout.LayoutParams(match, wrap, Gravity.CENTER) }
            root.addView(v)
            v.setVideoPath(clip.absolutePath)
            v.setOnPreparedListener { mp -> mp.isLooping = true; try { mp.setVolume(0f, 0f) } catch (_: Exception) {}; v.start() }
            v.setOnErrorListener { _, _, _ -> true }
            // The second it's at (to tell which part to cut), and the parts already cut are skipped here too.
            val clock = TextView(this).apply {
                textSize = 20f; typeface = DS.emphasis; setTextColor(DS.onPhoto(this@PitzkiActivity))
                setShadowLayer(8f, 0f, 1f, DS.scrim(this@PitzkiActivity))
                setPadding(ui.dp(20), ui.dp(16), ui.dp(20), ui.dp(16))
                layoutParams = FrameLayout.LayoutParams(wrap, wrap, Gravity.TOP or Gravity.START)
            }
            root.addView(clock)
            val cuts = PitzkiStore.cuts(this, clip)
            main.post(object : Runnable {
                override fun run() {
                    if (!v.isAttachedToWindow && clock.text.isNotEmpty()) return
                    val at = try { v.currentPosition.toLong() } catch (_: Exception) { 0L }
                    cuts.firstOrNull { at >= it.first && at < it.second }?.let { v.seekTo(it.second.toInt()) }
                    clock.text = String.format(java.util.Locale.US, "%.1f", at / 1000f) + L.t(" שנ׳", " s")
                    main.postDelayed(this, 100)
                }
            })
        } else {
            val img = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER; layoutParams = FrameLayout.LayoutParams(match, match) }
            root.addView(img)
            bg.execute {
                val b = try { BitmapFactory.decodeFile(image.absolutePath) } catch (_: Throwable) { null }
                main.post { if (!destroyed && b != null) img.setImageBitmap(b) }
            }
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(28))
            layoutParams = FrameLayout.LayoutParams(match, wrap, Gravity.BOTTOM)
        }
        bar.addView(TextView(this).apply {
            text = title + if (clip != null) L.t(" · מונפש", " · animated") else ""
            textSize = 16f; typeface = DS.emphasis; gravity = Gravity.CENTER
            setTextColor(DS.onPhoto(this@PitzkiActivity)); setShadowLayer(8f, 0f, 1f, DS.scrim(this@PitzkiActivity))
        })
        for ((i, a) in actions.withIndex()) bar.addView(ui.button(a.first, primary = i == 0, top = if (i == 0) 10 else 6) { dlg.dismiss(); a.second() })
        root.addView(bar)
        // Above the phone's own buttons at the bottom (they hid the last button), and below the status bar on top.
        root.setOnApplyWindowInsetsListener { _, ins ->
            @Suppress("DEPRECATION")
            bar.setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(28) + ins.systemWindowInsetBottom)
            ins
        }
        root.addView(TextView(this).apply {
            text = "✕"; textSize = 24f; setTextColor(DS.onPhoto(this@PitzkiActivity))
            setShadowLayer(8f, 0f, 1f, DS.scrim(this@PitzkiActivity))
            setPadding(ui.dp(20), ui.dp(16), ui.dp(20), ui.dp(16))
            contentDescription = L.t("סגירה", "Close")
            layoutParams = FrameLayout.LayoutParams(wrap, wrap, Gravity.TOP or Gravity.END)
            setOnClickListener { dlg.dismiss() }
        })
        root.setOnClickListener { dlg.dismiss() }
        dlg.setContentView(root)
        dlg.show()
    }

    // ---------- how far a job is, in percent, and about how long is left (updated every second) ----------

    private val etas = ArrayList<Triple<PitzkiStore.Drawing, android.widget.ProgressBar, TextView>>()

    private fun etaView(d: PitzkiStore.Drawing): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, ui.dp(8), 0, 0) }
        val bar = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = android.content.res.ColorStateList.valueOf(DS.button(this@PitzkiActivity))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(8))
        }
        val text = ui.text("", 14f, R.color.ds_primary, bold = true, top = 4)
        box.addView(bar)
        box.addView(text)
        etas.add(Triple(d, bar, text))
        fillEta(d, bar, text)
        return box
    }

    private fun fillEta(d: PitzkiStore.Drawing, bar: android.widget.ProgressBar, text: TextView) {
        val (p, left) = d.eta()
        bar.progress = p
        val min = (left + 30) / 60
        text.text = "$p% · " + when {
            left < 60 -> L.t("עוד פחות מדקה", "less than a minute left")
            min == 1 -> L.t("נשארה בערך דקה", "about a minute left")
            else -> L.t("נשארו בערך $min דקות", "about $min minutes left")
        }
    }

    private fun tickEtas() {
        etas.removeAll { (d, bar, _) -> d.finished || !bar.isAttachedToWindow }
        for ((d, bar, text) in etas) fillEta(d, bar, text)
    }

    /** The character from the child's photos: says what is sent and what it costs, then draws it. */
    private fun confirmCharacter(c: PitzkiStore.Child) {
        AiGate.askConsent(this) {
            AlertDialog.Builder(this)
                .setTitle(L.t("ליצור את הדמות של ${c.kid.name}?", "Make ${c.kid.name}'s character?"))
                .setMessage(L.t("נשלחות לשרת של Moments, ומשם לשירות fal ולמודל הציור של OpenAI (GPT Image), ${c.photos.size} תמונות של ${c.kid.name} (מהפרופיל ומהפנים שהאפליקציה למדה). נוצרת דמות מצוירת שאתם מאשרים, והיא תשמש גם בספרים. זה משתמש בקרדיט סרט אחד, ואותו קרדיט מכסה גם את הסרט של פיצקי אם תכינו אותו בשעות הקרובות. השרת לא שומר את התמונות.",
                    "${c.photos.size} photos of ${c.kid.name} (profile and the face the app learned) go to Moments' server and from there to the drawing model. A drawn character is made for you to approve; books will use it too. It uses one film credit, which also covers Pitzki's film if you make it in the next few hours. The server doesn't keep the photos."))
                .setPositiveButton(L.t("ליצור", "Make it")) { _, _ ->
                    if (!PitzkiStore.makeCharacter(this, c)) android.widget.Toast.makeText(this, L.t("משהו אחר עוד רץ. רגע.", "Something else is still running."), android.widget.Toast.LENGTH_SHORT).show()
                    renderSetup()
                    watchDrawing()
                }
                .setNegativeButton(L.t("לא עכשיו", "Not now"), null)
                .show()
        }
    }

    /** Says exactly what is sent, then draws. */
    private fun confirmDraw(c: PitzkiStore.Child, again: Boolean, animate: Boolean = false) {
        val look = c.look ?: return
        // The scenes are drawn through Moments' server (fal): only a signed-in account is needed.
        if (!PitzkiStore.scenesReady(this, c) && !Account.signedIn(this)) {
            AlertDialog.Builder(this).setMessage(L.t("כדי לצייר צריך להתחבר לחשבון.", "Please sign in to draw.")).setPositiveButton(L.t("הבנתי", "OK"), null).show()
            return
        }
        AiGate.askConsent(this) {
            val what = if (look.drawn) L.t("הדמות המצוירת של ${c.kid.name} מהספר", "${c.kid.name}'s drawn character from the book")
                else L.t("התמונה של ${c.kid.name} מהפרופיל", "${c.kid.name}'s profile picture")
            AlertDialog.Builder(this)
                .setTitle(if (animate) L.t("ליצור את הסרט של ${c.kid.name}?", "Make ${c.kid.name}'s film?") else L.t("לצייר את ${c.kid.name} ביער?", "Draw ${c.kid.name} in the forest?"))
                .setMessage(L.t("נשלחים לשרת של Moments, ומשם לשירות fal ולמודל הציור של OpenAI (GPT Image, אותו מודל שמצייר את הספרים): $what, תמונה אמיתית של ${c.kid.name} לדמיון, ותמונה של פיצקי. קודם מצוירת סצנה אחת, ואתם מאשרים שהיא דומה. רק אז נוצרות שאר ${PitzkiScript.CHILD_KEYS.size} הסצנות שבהן ${c.kid.name} מופיע (הציורים משתמשים בקרדיט הסרט)",
                    "Sent to Moments' server, and from there to fal and OpenAI's drawing model (GPT Image, the same one that draws the books): $what, a real photo of ${c.kid.name} for the likeness, and a picture of Pitzki. One scene is drawn first and you approve the likeness; only then are the other ${PitzkiScript.CHILD_KEYS.size} scenes with ${c.kid.name} made (the drawings use the film credit)") +
                    (if (animate) L.t(", ואז כל ציור קם לחיים לכמה שניות: פיצקי ו${c.kid.name} הולכים, מצביעים ומנופפים. ההנפשה משתמשת בקרדיט סרט אחד ולוקחת כמה דקות.", ", then each drawing comes to life for a few seconds: Pitzki and ${c.kid.name} walk, point and wave. The animation uses one film credit and takes a few minutes.") else ".") +
                    L.t(" הסרט נשמר רק בטלפון. השרת שלנו לא שומר את התמונות, ו-fal מוחקים אותן כשהן מגיעות לטלפון (לכל היותר אחרי 16 שעות).",
                        " The film is kept only on this phone. Our server doesn't keep the pictures, and fal deletes them once they reach the phone (at most after 16 hours)."))
                .setPositiveButton(if (animate) L.t("ליצור", "Make it") else L.t("לצייר", "Draw")) { _, _ ->
                    if (again) PitzkiStore.deleteScenes(this, c.memberId)
                    if (!PitzkiStore.drawScenes(this, c, animate)) android.widget.Toast.makeText(this, L.t("ציור אחר עוד רץ. רגע.", "Another drawing is still running."), android.widget.Toast.LENGTH_SHORT).show()
                    renderSetup()
                    watchDrawing()
                }
                .setNegativeButton(L.t("לא עכשיו", "Not now"), null)
                .show()
        }
    }

    /** Refreshes the card while scenes are being drawn. */
    private var watching: PitzkiStore.Drawing? = null

    private fun watchDrawing() {
        val d = PitzkiStore.drawing ?: return
        if (d.finished || watching === d) return
        watching = d
        if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 42)
        var last = "${d.done}/${d.animated}/${d.note}"
        main.postDelayed(object : Runnable {
            override fun run() {
                if (destroyed || inLesson) { if (watching === d) watching = null; return }
                val now = "${d.done}/${d.animated}/${d.note}"
                if (now != last || d.finished) { last = now; renderSetup() } else tickEtas()
                if (!d.finished) main.postDelayed(this, 700) else if (watching === d) watching = null
            }
        }, 700)
    }

    // =====================================================================
    // The lesson (for the child)
    // =====================================================================

    private var inLesson = false
    private var scenes: List<PitzkiScene> = emptyList()
    private var sceneIdx = 0
    private var beatIdx = 0
    private var token = 0
    private var paused = false
    private var asking: PColor? = null
    private var askSeq = 0
    private var misses = 0
    private var praiseN = 0
    private var starsNow = 0
    private var useDrawn = false

    private lateinit var stage: FrameLayout
    private lateinit var watermark: TextView
    /** The round camera picture of a recorded walk (null when not recording). */
    private var selfie: SelfieBubble? = null
    private lateinit var sceneImg: ImageView
    private lateinit var objectView: TextView
    private lateinit var childBubble: View
    private lateinit var wordBubble: TextView
    /** The counting numbers: in the bottom-left corner, off the characters' faces. */
    private lateinit var countBubble: TextView
    private lateinit var burst: TextView
    private lateinit var subtitle: TextView
    private lateinit var signPatch: ImageView
    @Volatile private var signBmp: android.graphics.Bitmap? = null
    private lateinit var progress: LinearLayout
    private lateinit var starsLabel: TextView
    private lateinit var strip: LinearLayout
    private lateinit var pausedLayer: LinearLayout
    private lateinit var listening: TextView
    private lateinit var micBtn: TextView
    private val items = HashMap<PColor, View>()
    /** Changes with every scene, so a picture that loads late never lands on the next scene. */
    private var sceneSeq = 0
    private lateinit var clipView: android.view.TextureView
    private var clipPlayer: android.media.MediaPlayer? = null
    private var clipSurface: android.view.Surface? = null
    private var clipEnded = false
    private val clipIo = Executors.newSingleThreadExecutor() // opening a clip never on the main thread

    /**
     * Some phones draw the beetle emoji green: for "black" the picture is shown black (and nothing else is changed),
     * so the child taps a black beetle when Pitzki asks for the black one.
     */
    private fun emojiColor(v: TextView, c: PColor?) {
        if (c == PColor.BLACK) {
            val m = android.graphics.ColorMatrix().apply { setSaturation(0f) }
            m.postConcat(android.graphics.ColorMatrix(floatArrayOf(0.35f, 0f, 0f, 0f, 0f, 0f, 0.35f, 0f, 0f, 0f, 0f, 0f, 0.35f, 0f, 0f, 0f, 0f, 0f, 1f, 0f)))
            v.setLayerType(View.LAYER_TYPE_HARDWARE, android.graphics.Paint().apply { colorFilter = android.graphics.ColorMatrixColorFilter(m) })
        } else v.setLayerType(View.LAYER_TYPE_NONE, null)
    }

    private fun colorOf(c: PColor) = ui.color(when (c) {
        PColor.GREEN -> R.color.pitzki_green
        PColor.BROWN -> R.color.pitzki_brown
        PColor.RED -> R.color.pitzki_red
        PColor.YELLOW -> R.color.pitzki_yellow
        PColor.GRAY -> R.color.pitzki_gray
        PColor.WHITE -> R.color.pitzki_white
        PColor.BLACK -> R.color.pitzki_black
        PColor.PURPLE -> R.color.pitzki_purple
        PColor.PINK -> R.color.pitzki_pink
        PColor.BLUE -> R.color.pitzki_blue
    })

    private fun startLesson() {
        inLesson = true
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Like a film: sideways, the whole screen, no bars.
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        immersive(true)
        stopSong()
        answered.clear()
        asked = 0; rightFirst = 0
        chosen?.let { PitzkiScript.memory = PitzkiStore.memory(this, it.memberId); PitzkiScript.walksDone = PitzkiStore.lessonsDone(this, it.memberId) }
            ?: run { PitzkiScript.memory = emptyMap(); PitzkiScript.walksDone = 0 }
        PitzkiScript.level = chosen?.let { PitzkiStore.level(this, it.memberId, PitzkiScript.worldId) } ?: 1
        scenes = PitzkiScript.lesson(kid)
        sceneIdx = 0; beatIdx = 0; starsNow = 0; praiseN = 0; asking = null; paused = false
        // The child's own drawings wherever they exist (also when the lesson grew and some are still missing).
        useDrawn = chosen?.let { PitzkiStore.partial(this, it) > 0 } == true
        if (PitzkiStore.listenOn(this) && !micGranted()) requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 41)
        buildLesson()
        showScene()
        runBeat()
        showMicTip()
    }

    private fun stopLesson() {
        endRecording(share = false)
        if (::stage.isInitialized) lingering?.let { stage.removeView(it) }
        lingering = null
        token++
        main.removeCallbacksAndMessages(null)
        voice.stop()
        ears.stop()
        stopSong()
        stopClip()
        ambient(null)
        if (::stage.isInitialized) clearGame()
        inLesson = false
        immersive(false)
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }

    private fun immersive(on: Boolean) {
        val c = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
        if (on) {
            c.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            c.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        } else c.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
    }

    private fun lp(w: Int, h: Int, gravity: Int) = FrameLayout.LayoutParams(w, h, gravity)
    private val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
    private val match = ViewGroup.LayoutParams.MATCH_PARENT

    /**
     * The whole screen is the forest (sideways, like a film). Over it: the words Pitzki says (subtitles), the color
     * word in a bubble, the child, and the five things to tap — only when it's
     * the child's turn. One tap anywhere pauses.
     */
    private fun buildLesson() {
        val black = DS.alpha(DS.ink(this), 1f)
        stage = FrameLayout(this).apply {
            setBackgroundColor(black)
            layoutDirection = View.LAYOUT_DIRECTION_LTR // positions are fixed to the art: Pitzki stands on the right
            // A tap pauses (not while it's the child's turn: little fingers tap everywhere).
            // During the light game a tap is the child's answer (not a pause).
            setOnClickListener { if (paused) resume() else if (lightOn != 0) lightTap() else if (asking == null && game == null) pause() }
        }
        sceneImg = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; layoutParams = lp(match, match, Gravity.CENTER) }
        stage.addView(sceneImg)
        // The scene come to life (when it was animated): plays over the still, then fades back to it.
        clipView = android.view.TextureView(this).apply { alpha = 0f; layoutParams = lp(match, match, Gravity.CENTER) }
        stage.addView(clipView)
        // The farm's hello: its sign is covered with the app's clean sign ("חוות החברים"), placed where the video has it.
        signPatch = ImageView(this).apply { visibility = View.GONE; scaleType = ImageView.ScaleType.FIT_XY; layoutParams = lp(1, 1, Gravity.TOP or Gravity.START) }
        stage.addView(signPatch)
        // Soft dark at the bottom so the subtitles read well over any picture.
        stage.addView(View(this).apply {
            setBackgroundResource(R.drawable.cover_scrim)
            layoutParams = lp(match, ui.dp(170), Gravity.BOTTOM)
        })
        stage.addView(View(this).apply {
            setBackgroundResource(R.drawable.scrim_top)
            layoutParams = lp(match, ui.dp(70), Gravity.TOP)
        })

        objectView = TextView(this).apply {
            textSize = 70f
            gravity = Gravity.CENTER
            background = DS.oval(DS.alpha(DS.bg(this@PitzkiActivity), 0.9f))
            elevation = ui.dp(6).toFloat()
            visibility = View.GONE
            layoutParams = lp(ui.dp(150), ui.dp(150), Gravity.CENTER).apply { rightMargin = ui.dp(60) }
        }
        stage.addView(objectView)
        childBubble = chosen?.let { lookCircle(it, 150, ring = true) } ?: View(this)
        childBubble.layoutParams = lp(ui.dp(150), ui.dp(150), Gravity.LEFT or Gravity.CENTER_VERTICAL).apply { leftMargin = ui.dp(36); bottomMargin = ui.dp(20) }
        childBubble.elevation = ui.dp(8).toFloat()
        stage.addView(childBubble)
        wordBubble = TextView(this).apply {
            textSize = 44f
            typeface = DS.heading
            gravity = Gravity.CENTER
            setPadding(ui.dp(28), ui.dp(6), ui.dp(28), ui.dp(10))
            background = DS.rounded(DS.alpha(DS.bg(this@PitzkiActivity), 0.96f), ui.dp(34).toFloat())
            elevation = ui.dp(8).toFloat()
            visibility = View.GONE
            // Top middle, under the dots: never on Pitzki's face (he stands on the right in his close-ups).
            layoutParams = lp(wrap, wrap, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = ui.dp(40) }
        }
        stage.addView(wordBubble)
        countBubble = TextView(this).apply {
            textSize = 40f
            typeface = DS.heading
            gravity = Gravity.CENTER
            setTextColor(DS.primary(this@PitzkiActivity))
            setPadding(ui.dp(22), ui.dp(4), ui.dp(22), ui.dp(8))
            background = DS.rounded(DS.alpha(DS.bg(this@PitzkiActivity), 0.92f), ui.dp(30).toFloat())
            elevation = ui.dp(8).toFloat()
            visibility = View.GONE
            layoutParams = lp(wrap, wrap, Gravity.BOTTOM or Gravity.LEFT).apply { leftMargin = ui.dp(20); bottomMargin = ui.dp(96) }
        }
        stage.addView(countBubble)
        burst = TextView(this).apply {
            text = "⭐"; textSize = 110f; gravity = Gravity.CENTER; visibility = View.GONE
            layoutParams = lp(wrap, wrap, Gravity.CENTER)
        }
        stage.addView(burst)

        // Subtitles: what Pitzki says, large and white, at the bottom.
        // In the farm the subtitles have niqqud (display only; what's said is the same).
        subtitle = object : TextView(this) {
            override fun setText(text: CharSequence?, type: BufferType?) {
                val t = if (text != null && PitzkiScript.isFarm) FarmNiqqud.show(text.toString(), kid.girl) else text
                super.setText(t, type)
            }
        }.apply {
            textSize = 21f // (a little smaller: the picture first, but big enough to start reading)
            typeface = DS.emphasis
            gravity = Gravity.CENTER
            setTextColor(DS.onPhoto(this@PitzkiActivity))
            setShadowLayer(10f, 0f, 2f, DS.scrim(this@PitzkiActivity))
            textDirection = View.TEXT_DIRECTION_RTL
            maxLines = 2
            layoutParams = lp(match, wrap, Gravity.BOTTOM).apply { leftMargin = ui.dp(90); rightMargin = ui.dp(90); bottomMargin = ui.dp(18) }
        }
        stage.addView(subtitle)

        // Like a book or stories: two small arrows at the bottom corners, and a swipe, move to the next / previous
        // scene. Hebrew goes right to left: the next scene is on the left.
        fun arrow(label: String, side: Int, desc: String, go: () -> Unit) = TextView(this).apply {
            text = label; textSize = 26f; gravity = Gravity.CENTER
            setTextColor(DS.onPhoto(this@PitzkiActivity))
            background = DS.oval(ui.color(R.color.ds_photo_control_bg))
            contentDescription = desc
            layoutParams = lp(ui.dp(52), ui.dp(52), Gravity.BOTTOM or side).apply { leftMargin = ui.dp(20); rightMargin = ui.dp(20); bottomMargin = ui.dp(16) }
            setOnClickListener { go() }
        }
        stage.addView(arrow("‹", Gravity.LEFT, L.t("לסצנה הבאה", "Next scene")) { goScene(+1) })
        stage.addView(arrow("›", Gravity.RIGHT, L.t("לסצנה הקודמת", "Previous scene")) { goScene(-1) })
        val swipe = android.view.GestureDetector(this, object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: android.view.MotionEvent?, e2: android.view.MotionEvent, vx: Float, vy: Float): Boolean {
                val dx = e2.x - (e1?.x ?: e2.x)
                val dy = e2.y - (e1?.y ?: e2.y)
                if (kotlin.math.abs(dx) < ui.dp(60) || kotlin.math.abs(dx) < kotlin.math.abs(dy)) return false
                swiped = true
                goScene(if (dx > 0) +1 else -1) // finger to the right turns the page forward (right to left)
                return true
            }
        })
        stage.setOnTouchListener { v, e ->
            if (e.actionMasked == android.view.MotionEvent.ACTION_DOWN) swiped = false
            swipe.onTouchEvent(e)
            // A swipe isn't also a tap (no pause after it).
            if (swiped && e.actionMasked == android.view.MotionEvent.ACTION_UP) { v.isPressed = false; true } else false
        }

        // The five things to tap: slide up only when it's the child's turn.
        strip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            layoutParams = lp(wrap, wrap, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = ui.dp(76) }
        }
        items.clear()
        for (c in PitzkiScript.ORDER) {
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = DS.pressable(this@PitzkiActivity, DS.rounded(DS.alpha(DS.bg(this@PitzkiActivity), 0.95f), ui.dp(22).toFloat()))
                elevation = ui.dp(6).toFloat()
                setPadding(ui.dp(4), ui.dp(6), ui.dp(4), ui.dp(6))
                layoutParams = LinearLayout.LayoutParams(ui.dp(68), wrap).apply { marginStart = ui.dp(3); marginEnd = ui.dp(3) }
                contentDescription = "${c.he}, ${c.en}"
                setOnClickListener { tap(c) }
            }
            item.addView(TextView(this).apply { text = c.emoji; textSize = 30f; gravity = Gravity.CENTER; emojiColor(this, c) })
            item.addView(TextView(this).apply {
                text = c.en; textSize = 13f; gravity = Gravity.CENTER; typeface = DS.heading; maxLines = 1
                setTextColor(colorOf(c))
            })
            items[c] = item
            strip.addView(item)
        }
        stage.addView(strip)

        // Top: close (right), where we are (dots), stars and sound (left).
        stage.addView(TextView(this).apply {
            text = "✕"; textSize = 22f; setTextColor(DS.onPhoto(this@PitzkiActivity))
            setShadowLayer(8f, 0f, 1f, DS.scrim(this@PitzkiActivity))
            setPadding(ui.dp(18), ui.dp(12), ui.dp(18), ui.dp(12))
            contentDescription = L.t("יציאה", "Exit")
            layoutParams = lp(wrap, wrap, Gravity.TOP or Gravity.RIGHT)
            setOnClickListener { stopLesson(); setup() }
        })
        progress = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            layoutParams = lp(wrap, wrap, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = ui.dp(20) }
        }
        repeat(scenes.size) {
            progress.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(ui.dp(16), ui.dp(4)).apply { marginStart = ui.dp(2); marginEnd = ui.dp(2) }
            })
        }
        stage.addView(progress)
        val left = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = lp(wrap, wrap, Gravity.TOP or Gravity.LEFT).apply { topMargin = ui.dp(8); leftMargin = ui.dp(12) }
        }
        starsLabel = TextView(this).apply {
            text = "⭐ 0"; textSize = 18f; typeface = DS.heading
            setTextColor(DS.onPhoto(this@PitzkiActivity))
            setShadowLayer(8f, 0f, 1f, DS.scrim(this@PitzkiActivity))
            setPadding(ui.dp(6), ui.dp(6), ui.dp(10), ui.dp(6))
        }
        left.addView(TextView(this).apply {
            text = if (voice.muted) "🔇" else "🔊"; textSize = 18f
            setPadding(ui.dp(6), ui.dp(6), ui.dp(10), ui.dp(6))
            contentDescription = L.t("קול", "Sound")
            setOnClickListener {
                voice.muted = !voice.muted; text = if (voice.muted) "🔇" else "🔊"
                try { ambientPlayer?.setVolume(if (voice.muted) 0f else 0.35f, if (voice.muted) 0f else 0.35f) } catch (_: Exception) {}
                try { songPlayer?.setVolume(if (voice.muted) 0f else 1f, if (voice.muted) 0f else 1f) } catch (_: Exception) {}
            }
        })
        left.addView(starsLabel)
        micBtn = TextView(this).apply {
            textSize = 13f
            typeface = DS.emphasis
            setTextColor(DS.onPhoto(this@PitzkiActivity))
            background = DS.rounded(DS.scrim(this@PitzkiActivity), ui.dp(14).toFloat())
            setPadding(ui.dp(10), ui.dp(4), ui.dp(10), ui.dp(4))
            contentDescription = L.t("מיקרופון: פיצקי מקשיב לתשובות", "Microphone: Pitzki listens to answers")
            layoutParams = LinearLayout.LayoutParams(wrap, wrap).apply { marginStart = ui.dp(8) }
            setOnClickListener {
                PitzkiStore.setListen(this@PitzkiActivity, !PitzkiStore.listenOn(this@PitzkiActivity))
                showMic()
                if (PitzkiStore.listenOn(this@PitzkiActivity) && !micGranted()) requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 41)
                if (!PitzkiStore.listenOn(this@PitzkiActivity)) { ears.stop(); listening.visibility = View.GONE }
            }
        }
        left.addView(micBtn)
        showMic()
        stage.addView(left)
        // While Pitzki listens: a small "listening" sign in the middle, so the child knows to talk.
        listening = TextView(this).apply {
            text = L.t("🎤 פיצקי מקשיב…", "🎤 Pitzki is listening…")
            textSize = 22f
            typeface = DS.emphasis
            setTextColor(DS.onPhoto(this@PitzkiActivity))
            background = DS.rounded(DS.scrim(this@PitzkiActivity), ui.dp(22).toFloat())
            setPadding(ui.dp(18), ui.dp(8), ui.dp(18), ui.dp(8))
            visibility = View.GONE
            layoutParams = lp(wrap, wrap, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = ui.dp(84) }
        }
        stage.addView(listening)
        // The watermark of a recorded walk (it's in the video as it's recorded).
        watermark = TextView(this).apply {
            text = "✨ Moments · פיצקי"
            textSize = 12f; typeface = DS.emphasis
            setTextColor(android.graphics.Color.argb(220, 255, 255, 255))
            setShadowLayer(6f, 0f, 1f, android.graphics.Color.argb(160, 0, 0, 0))
            setPadding(ui.dp(10), ui.dp(4), ui.dp(10), ui.dp(4))
            background = DS.rounded(android.graphics.Color.argb(70, 0, 0, 0), ui.dp(12).toFloat())
            visibility = if (recordingWalk) View.VISIBLE else View.GONE
            layoutParams = lp(wrap, wrap, Gravity.BOTTOM or Gravity.START).apply { leftMargin = ui.dp(24); bottomMargin = ui.dp(70) }
        }
        stage.addView(watermark)
        // The child's face in a little circle while the walk is recorded (tap: smaller / bigger).
        selfie?.stop()
        selfie = if (!recordingWalk) null else SelfieBubble(this).apply {
            layoutParams = lp(ui.dp(96), ui.dp(96), Gravity.TOP or Gravity.START).apply { leftMargin = ui.dp(22); topMargin = ui.dp(66) }
            pivotX = 0f; pivotY = 0f
            setOnClickListener { val small = scaleX < 1f; animate().scaleX(if (small) 1f else 0.55f).scaleY(if (small) 1f else 0.55f).setDuration(200).start() }
            contentDescription = L.t("המצלמה", "The camera")
        }
        selfie?.let { stage.addView(it); it.start() }

        // Paused: a calm layer with play, scene again and next scene.
        pausedLayer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundColor(DS.scrimSoft(this@PitzkiActivity))
            visibility = View.GONE
            layoutParams = lp(match, match, Gravity.CENTER)
            setOnClickListener { resume() }
        }
        fun round(label: String, sizeDp: Int, primary: Boolean, desc: String, go: () -> Unit) = TextView(this).apply {
            text = label; textSize = if (primary) 30f else 22f; gravity = Gravity.CENTER
            setTextColor(if (primary) DS.onButton(this@PitzkiActivity) else DS.primary(this@PitzkiActivity))
            background = DS.pressable(this@PitzkiActivity, DS.oval(if (primary) DS.button(this@PitzkiActivity) else DS.bg(this@PitzkiActivity)), onDark = primary)
            contentDescription = desc
            layoutParams = LinearLayout.LayoutParams(ui.dp(sizeDp), ui.dp(sizeDp)).apply { marginStart = ui.dp(18); marginEnd = ui.dp(18) }
            setOnClickListener { go() }
        }
        pausedLayer.addView(round("↻", 60, false, L.t("הסצנה מההתחלה", "Scene again")) { jump(sceneIdx) })
        pausedLayer.addView(round("▶", 88, true, L.t("המשך", "Play")) { resume() })
        pausedLayer.addView(round("⏭", 60, false, L.t("לסצנה הבאה", "Next scene")) { if (sceneIdx + 1 < scenes.size) jump(sceneIdx + 1) else resume() })
        stage.addView(pausedLayer)
        setContentView(stage)
    }

    private fun showProgress() {
        for (i in 0 until progress.childCount) progress.getChildAt(i).background =
            DS.rounded(if (i <= sceneIdx) DS.onPhoto(this) else DS.onPhoto(this, 0.35f), ui.dp(2).toFloat())
    }

    private fun showScene() {
        val s = scenes[sceneIdx]
        clearGame()
        lingering?.let { stage.removeView(it) }; lingering = null
        showProgress()
        wordBubble.visibility = View.GONE
        countBubble.visibility = View.GONE
        burst.visibility = View.GONE
        strip.visibility = View.GONE
        val member = chosen?.memberId
        // The child's own drawing (the child is in it), or Pitzki's shared close-up; otherwise Pitzki's forest, the thing,
        // and the child in a circle.
        val owner = if (useDrawn) member else null
        val clip = PitzkiStore.clipFor(this, owner, s.key)
        // The opening, when it's animated from Pitzki alone: that first moment is shown (the child isn't there yet).
        val drawn = ((if (s.key == PitzkiScript.INTRO_KEY && owner != null && clip != null && PitzkiStore.handFile(this, owner).exists()) PitzkiStore.startFile(this, owner).takeIf { it.exists() } else null)
            ?: PitzkiStore.sceneFor(this, owner, s.key))
            // A hidden picture that gives the animal away: the farm gate instead.
            ?.takeUnless { PitzkiScript.isFarm && FarmWorld.gateInstead(s.key) }
        val seq = ++sceneSeq
        // The farm before its pictures are made: the pictures that come with the app.
        val bundled = if (drawn == null && PitzkiScript.isPlay) "farm_gate" // (until the games scenes are drawn: the empty gate, no animals there)
            else if (drawn == null && PitzkiScript.isFarm) (if (FarmWorld.gateInstead(s.key)) "farm_gate" else FarmWorld.bundled(s.key)) else null
        bg.execute {
            val b = try {
                if (drawn != null && drawn.exists()) BitmapFactory.decodeFile(drawn.absolutePath)
                else bundled?.let { PitzkiStore.art(applicationContext, "farm/$it") } ?: PitzkiStore.art(applicationContext, "forest")
            } catch (_: Throwable) { null }
            main.post {
                if (destroyed || seq != sceneSeq || !inLesson || b == null) return@post
                // From the picture that was on screen to the new one (never through a black screen).
                sceneImg.animate().cancel()
                sceneImg.alpha = 1f
                val old = sceneImg.drawable
                val fresh = android.graphics.drawable.BitmapDrawable(resources, b)
                if (old == null) sceneImg.setImageDrawable(fresh) else {
                    val t = android.graphics.drawable.TransitionDrawable(arrayOf(old, fresh)).apply { isCrossFadeEnabled = true }
                    sceneImg.setImageDrawable(t)
                    t.startTransition(450)
                }
            }
        }
        stopClip()
        // The song scene: Pitzki sings it himself, lips matched to the song (when that clip was made), with its sound.
        val talk = if (s.key == "song") PitzkiStore.talkTest(this).takeIf { it.exists() && it.length() > 0 } else null
        songFromClip = talk != null
        // Pitzki's talking scenes: his video with its own sound (and its continuation right after it).
        val spoken = if (s.key in PitzkiTalks.KEYS) PitzkiStore.sharedTalk(this, s.key).takeIf { it.exists() && it.length() > 0 } else null
        talking = spoken != null
        clipRendering = false
        waitedForClip = 0
        // A clip that was continued (5 more seconds from its last frame) plays on right after it.
        // The opening: first the hand coming in (Pitzki alone), then the two of them together.
        val hand = if (s.key == PitzkiScript.INTRO_KEY && owner != null && clip != null) PitzkiStore.handFile(this, owner).takeIf { it.exists() && it.length() > 0 } else null
        nextClip = if (spoken != null) PitzkiStore.sharedTalk2(this, s.key).takeIf { it.exists() && it.length() > 0 }
            else if (hand != null) clip
            else if (owner != null && clip != null && talk == null) PitzkiStore.moreFile(this, owner, s.key).takeIf { it.exists() && it.length() > 0 } else null
        // Pitzki's video starts when his line comes (in the farm the animal's sound is heard first).
        pendingTalk = if (spoken != null && s.beats.firstOrNull() !is Beat.Talk) spoken else null
        if (spoken != null) { if (pendingTalk == null) playClip(spoken, seq, withSound = true) }
        else if (hand != null) playClip(hand, seq)
        else (talk ?: clip)?.let { playClip(it, seq) }
        // The bird scene: soft little chirps, so the forest sounds alive.
        ambient(if (s.color == PColor.BLUE) "pitzki/birds.m4a" else null)
        val c = s.color
        objectView.visibility = if (drawn == null && c != null) View.VISIBLE else View.GONE
        if (c != null) { objectView.text = c.emoji; emojiColor(objectView, c) }
        childBubble.visibility = if (drawn == null && bundled == null) View.VISIBLE else View.GONE
        kenBurns()
        if (objectView.visibility == View.VISIBLE) {
            objectView.scaleX = 0.4f; objectView.scaleY = 0.4f
            objectView.animate().scaleX(1f).scaleY(1f).setDuration(700).setInterpolator(android.view.animation.OvershootInterpolator()).start()
        }
    }

    /** Plays a scene's animation once, silently (Pitzki talks over it), then its last frame stays on screen. */
    /**
     * True in the song scene when Pitzki's lip-synced singing exists: that video is the picture, and the song's
     * recording (the same sound it was made from) plays with it, started together, so the lips match.
     */
    private var songFromClip = false
    /** The scene is Pitzki's talking video (played with its sound; the Talk beat waits for its end). */
    private var talking = false
    /** His video for this scene, waiting for its line (started by the Talk beat). */
    private var pendingTalk: java.io.File? = null
    /** The continuation of the current clip, played when it ends (seamless: it starts from its last frame). */
    private var nextClip: java.io.File? = null
    /** The current clip's first frame is on screen (to start the song with it). */
    private var clipRendering = false

    private fun playClip(f: java.io.File, seq: Int, withSound: Boolean = false) {
        val st = clipView.surfaceTexture
        if (st == null) {
            clipView.surfaceTextureListener = object : android.view.TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(t: android.graphics.SurfaceTexture, w: Int, h: Int) { if (seq == sceneSeq && inLesson) playClip(f, seq, withSound) }
                override fun onSurfaceTextureSizeChanged(t: android.graphics.SurfaceTexture, w: Int, h: Int) {}
                override fun onSurfaceTextureDestroyed(t: android.graphics.SurfaceTexture) = true
                override fun onSurfaceTextureUpdated(t: android.graphics.SurfaceTexture) {}
            }
            return
        }
        val mp = android.media.MediaPlayer()
        val surface = android.view.Surface(st)
        clipPlayer = mp
        clipSurface = surface
        clipEnded = false
        mp.setOnInfoListener { _, what, _ ->
            if (what == android.media.MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START && clipPlayer === mp) {
                clipRendering = true
                // A talking video whose picture is spoiled (the farm's hello: a sign with made-up letters): only its
                // voice is heard, over the clean picture.
                if (!(PitzkiScript.isFarm && FarmWorld.voiceOnly(scenes.getOrNull(sceneIdx)?.key.orEmpty())))
                    clipView.animate().alpha(1f).setDuration(400).start()
                sceneImg.animate().cancel()
            }
            false
        }
        mp.setOnCompletionListener {
            if (clipPlayer !== mp) return@setOnCompletionListener
            // The last frame stays on screen (where the characters ended up), no jump back to the still drawing,
            // and the camera keeps drifting in slowly while Pitzki still talks, so it never looks stuck.
            val next = nextClip
            if (next != null && seq == sceneSeq && inLesson) {
                nextClip = null
                clipPlayer = null
                val oldSurface = clipSurface
                clipSurface = null
                clipIo.execute { try { mp.release() } catch (_: Exception) {}; try { oldSurface?.release() } catch (_: Exception) {} }
                playClip(next, seq, withSound)
                return@setOnCompletionListener
            }
            clipEnded = true
            clipView.animate().scaleX(1.1f).scaleY(1.1f).setDuration(14_000)
                .setInterpolator(android.view.animation.LinearInterpolator()).start()
        }
        mp.setOnErrorListener { _, _, _ -> if (clipPlayer === mp) { clipEnded = true; clipView.alpha = 0f }; true }
        clipIo.execute {
            val ok = try {
                mp.setDataSource(f.absolutePath)
                mp.setSurface(surface)
                val v = if (withSound && !voice.muted) 1f else 0f
                mp.setVolume(v, v)
                mp.prepare()
                true
            } catch (_: Throwable) { false }
            main.post {
                if (clipPlayer !== mp || destroyed) return@post
                if (!ok) { stopClip(); return@post }
                try {
                    fillClip(mp.videoWidth, mp.videoHeight)
                    clipView.animate().cancel(); clipView.scaleX = 1f; clipView.scaleY = 1f
                    // The child's scenes play a little slower (85%): calmer, and longer while Pitzki talks.
                    if (scenes.getOrNull(sceneIdx)?.key in PitzkiScript.CHILD_KEYS && !songFromClip)
                        try { mp.playbackParams = mp.playbackParams.setSpeed(0.85f) } catch (_: Exception) {}
                    // A clip whose start is spoiled (the farm's animals: a child that isn't there): only its clean end.
                    val from = FarmWorld.clipFrom(f.nameWithoutExtension)
                    if (from > 0f && mp.duration > 0) try {
                        val at = (mp.duration * from).toLong()
                        if (android.os.Build.VERSION.SDK_INT >= 26) mp.seekTo(at, android.media.MediaPlayer.SEEK_CLOSEST) else mp.seekTo(at.toInt())
                    } catch (_: Exception) {}
                    // (Changing the speed starts playback on some phones: paused stays paused.)
                    if (!paused) mp.start() else try { if (mp.isPlaying) mp.pause() } catch (_: Exception) {}
                    // A talking video played only up to a point (the owl: Pitzki's own "thank you" is the child's to say).
                    val endAt = TrainWorld.TALK_END_MS.entries.firstOrNull { f.name == "${it.key}_talk.mp4" }?.value
                    // Moments the parent cut out (a pull, a face that changed): jumped over.
                    val cuts = PitzkiStore.cuts(this, f)
                    if (endAt != null) nextClip = null // its continuation started from the cut part
                    if (endAt != null || cuts.isNotEmpty()) {
                        main.post(object : Runnable {
                            override fun run() {
                                if (clipPlayer !== mp || destroyed || clipEnded) return
                                val at = try { mp.currentPosition.toLong() } catch (_: Exception) { return }
                                cuts.firstOrNull { at >= it.first && at < it.second }?.let { cut ->
                                    try { if (android.os.Build.VERSION.SDK_INT >= 26) mp.seekTo(cut.second, android.media.MediaPlayer.SEEK_CLOSEST) else mp.seekTo(cut.second.toInt()) } catch (_: Exception) {}
                                }
                                if (endAt == null || at < endAt) { main.postDelayed(this, 60); return }
                                try { mp.pause() } catch (_: Exception) {}
                                clipEnded = true
                                clipView.animate().scaleX(1.1f).scaleY(1.1f).setDuration(14_000)
                                    .setInterpolator(android.view.animation.LinearInterpolator()).start()
                            }
                        })
                    }
                } catch (_: Exception) { stopClip() }
            }
        }
    }

    /**
     * A success in a game: the scene's video plays once more (the child and Pitzki point, clap and smile again), so
     * they react right then instead of standing still. Only a silent video (the child's scenes), never a talking one.
     */
    private fun cheerClip() {
        val mp = clipPlayer ?: return
        if (!clipEnded || paused || destroyed || !inLesson) return
        if (scenes.getOrNull(sceneIdx)?.key !in PitzkiScript.CHILD_KEYS) return
        try {
            clipView.animate().cancel(); clipView.scaleX = 1f; clipView.scaleY = 1f
            clipEnded = false
            mp.seekTo(0)
            mp.start()
        } catch (_: Exception) { clipEnded = true }
    }

    /** The clip fills the screen (cropped, not stretched). */
    private fun fillClip(vw: Int, vh: Int) {
        if (vw <= 0 || vh <= 0 || clipView.width == 0 || clipView.height == 0) return
        val sx = clipView.width.toFloat() / vw
        val sy = clipView.height.toFloat() / vh
        val k = maxOf(sx, sy)
        clipView.setTransform(android.graphics.Matrix().apply {
            setScale(k / sx, k / sy, clipView.width / 2f, clipView.height / 2f)
            // Only the farm's videos (made through fal) wrote captions at the very bottom: a little closer there, from the
            // top-left (Pitzki stands on the left). The train's videos have none: shown whole, as made.
            // (1.2: at 1.12 the cow's question still showed a line of them.)
            if (talking && PitzkiScript.isFarm) postScale(1.26f, 1.26f, 0f, 0f)
        }.also { placeSign(it) })
    }

    /** The clean sign over the video's sign (the camera stands still there): the same place and zoom as the video. */
    private fun placeSign(m: android.graphics.Matrix) {
        if (!::signPatch.isInitialized) return
        val on = talking && PitzkiScript.isFarm && scenes.getOrNull(sceneIdx)?.key == FarmWorld.INTRO
        if (!on) { signPatch.visibility = View.GONE; return }
        val w = clipView.width.toFloat(); val h = clipView.height.toFloat()
        val r = FarmWorld.SIGN_RECT
        val box = android.graphics.RectF(r[0] * w, r[1] * h, r[2] * w, r[3] * h)
        m.mapRect(box)
        signPatch.layoutParams = (signPatch.layoutParams as FrameLayout.LayoutParams).apply { width = box.width().toInt(); height = box.height().toInt() }
        signPatch.translationX = box.left; signPatch.translationY = box.top
        val b = signBmp
        if (b != null) { signPatch.setImageBitmap(b); signPatch.visibility = View.VISIBLE }
        else bg.execute {
            val cut = try { assets.open("pitzki/farm/farm_sign_patch.png").use { BitmapFactory.decodeStream(it) } } catch (_: Throwable) { null }
            signBmp = cut
            main.post { if (!destroyed && cut != null && scenes.getOrNull(sceneIdx)?.key == FarmWorld.INTRO && talking) { signPatch.setImageBitmap(cut); signPatch.visibility = View.VISIBLE } }
        }
    }

    private fun stopClip() {
        val p = clipPlayer
        val sf = clipSurface
        clipPlayer = null
        clipSurface = null
        clipEnded = true
        if (::clipView.isInitialized) { clipView.animate().cancel(); clipView.alpha = 0f }
        if (::signPatch.isInitialized) signPatch.visibility = View.GONE
        if (p != null || sf != null) try {
            clipIo.execute {
                try { p?.release() } catch (_: Exception) {}
                try { sf?.release() } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }

    // ---------- Pitzki's song ----------

    private var songPlayer: android.media.MediaPlayer? = null
    private var songEnded = false

    private var waitedForClip = 0

    /** (Not used: the song's recording is the sound, see [sing].) */
    @Suppress("unused")
    private fun singWithClip(b: Beat.Song, my: Int, done: () -> Unit) {
        val began = System.currentTimeMillis()
        main.post(object : Runnable {
            override fun run() {
                if (my != token || paused || destroyed) return
                val mp = clipPlayer
                val pos = try { mp?.currentPosition?.toLong() ?: 0L } catch (_: Exception) { 0L }
                // Not playing after a few seconds (or failed at once): the recorded song instead.
                if ((mp == null || (clipEnded && pos < 1000)) && System.currentTimeMillis() - began > 4000) {
                    songFromClip = false
                    sing(b, my, done)
                    return
                }
                b.lines.lastOrNull { it.first <= pos }?.let { if (subtitle.text != it.second) subtitle.text = it.second }
                if (clipEnded && pos >= 1000) { done(); return }
                main.postDelayed(this, 120)
            }
        })
    }

    // ---------- sounds of the forest in some scenes (quiet, under Pitzki's voice) ----------

    private var ambientPlayer: android.media.MediaPlayer? = null

    private fun ambient(asset: String?) {
        ambientPlayer?.let { try { it.release() } catch (_: Exception) {} }
        ambientPlayer = null
        if (asset == null) return
        try {
            val p = android.media.MediaPlayer()
            assets.openFd(asset).use { fd -> p.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length) }
            p.isLooping = false // the recording is 8 seconds: a few chirps when the bird appears, then quiet
            val v = if (voice.muted) 0f else 0.35f
            p.setVolume(v, v)
            p.prepare()
            if (!paused) p.start()
            ambientPlayer = p
            // A gentle fade a little before it ends, so it never stops with a click.
            val mine = p
            main.postDelayed(object : Runnable {
                var vol = v
                override fun run() {
                    if (ambientPlayer !== mine) return
                    vol -= 0.05f
                    if (vol <= 0f) { ambient(null); return }
                    try { mine.setVolume(vol, vol) } catch (_: Exception) { return }
                    main.postDelayed(this, 120)
                }
            }, 5_500)
        } catch (_: Exception) { }
    }

    private fun hasSong() = try { assets.openFd("pitzki/song.m4a").close(); true } catch (_: Exception) { false }

    /** Plays the sung song from [b]'s part (one recording across the song's scenes), lines shown as they're sung. */
    private fun sing(b: Beat.Song, my: Int, done: () -> Unit) {
        // With the lip-synced video: wait (up to 3 seconds) for its first frame, then start the song with it.
        if (songFromClip && !clipRendering && waitedForClip < 30) {
            waitedForClip++
            main.postDelayed({ if (my == token && !paused && !destroyed) sing(b, my, done) }, 100)
            return
        }
        if (songFromClip && clipRendering && waitedForClip >= 0) {
            waitedForClip = -1
            try { clipPlayer?.seekTo(0) } catch (_: Exception) {}
        }
        if (!hasSong()) { sayAll(b.lines.map { it.second }, my, done); return }
        val mp = songPlayer ?: try {
            android.media.MediaPlayer().also { p ->
                assets.openFd("pitzki/song.m4a").use { fd -> p.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length) }
                p.setOnCompletionListener { songEnded = true }
                p.prepare() // a small file in the app: quick
                songPlayer = p
                songEnded = false
            }
        } catch (_: Exception) { null }
        if (mp == null) { sayAll(b.lines.map { it.second }, my, done); return }
        val end = if (b.toMs > 0) b.toMs else mp.duration.toLong()
        try {
            val v = if (voice.muted) 0f else 1f
            mp.setVolume(v, v)
            val pos = if (songEnded) end else mp.currentPosition.toLong()
            if (pos < b.fromMs - 500 || pos >= end) { songEnded = false; mp.seekTo(b.fromMs.toInt()) }
            if (!mp.isPlaying) mp.start()
        } catch (_: Exception) { sayAll(b.lines.map { it.second }, my, done); return }
        main.post(object : Runnable {
            override fun run() {
                if (my != token || paused || destroyed) return
                val pos = if (songEnded) end else try { mp.currentPosition.toLong() } catch (_: Exception) { end }
                b.lines.lastOrNull { it.first <= pos }?.let { if (subtitle.text != it.second) subtitle.text = it.second }
                if (pos >= end - 100) {
                    if (b.toMs == 0L) stopSong() // the song is over
                    done()
                    return
                }
                main.postDelayed(this, 120)
            }
        })
    }

    /** The song's lines said one after another (no recording, or it couldn't play). */
    private fun sayAll(lines: List<String>, my: Int, done: () -> Unit) {
        if (lines.isEmpty()) { done(); return }
        subtitle.text = lines.first()
        voice.say(lines.first()) { if (my == token && !paused) sayAll(lines.drop(1), my, done) }
    }

    private fun stopSong() {
        val p = songPlayer ?: return
        songPlayer = null
        try { p.release() } catch (_: Exception) {}
    }

    private fun micGranted() = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED

    /** Pitzki can listen right now: the parent allows it, the phone allows the microphone, and can recognize speech. */
    private fun canListen() = PitzkiStore.listenOn(this) && micGranted() && ears.available()

    private fun showMic() {
        if (!::micBtn.isInitialized) return
        micBtn.text = if (PitzkiStore.listenOn(this)) L.t("🎤 מיקרופון פועל", "🎤 Mic on") else L.t("🎤 מיקרופון כבוי", "🎤 Mic off")
        micBtn.alpha = if (PitzkiStore.listenOn(this)) 1f else 0.6f
    }

    /** The first time: a little cloud by the microphone button says what it is and how to turn it off. */
    private fun showMicTip() {
        if (PitzkiStore.micTipShown(this)) return
        PitzkiStore.setMicTipShown(this)
        val tip = TextView(this).apply {
            text = L.t("🎤 פיצקי מקשיב לתשובות של הילד ועונה לו.\nלא מתאים? לחצו על הכפתור הזה כדי לכבות.", "🎤 Pitzki listens to the child's answers and replies.\nNot right for your child? Tap this button to turn it off.")
            textSize = 14f
            setTextColor(DS.ink(this@PitzkiActivity))
            background = DS.rounded(DS.bg(this@PitzkiActivity), ui.dp(18).toFloat())
            elevation = ui.dp(10).toFloat()
            setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(10))
            layoutParams = lp(wrap, wrap, Gravity.TOP or Gravity.LEFT).apply { topMargin = ui.dp(56); leftMargin = ui.dp(12) }
            setOnClickListener { visibility = View.GONE }
        }
        stage.addView(tip)
        tip.alpha = 0f
        tip.animate().alpha(1f).setStartDelay(1200).setDuration(400).start()
        main.postDelayed({ tip.animate().alpha(0f).setDuration(500).withEndAction { tip.visibility = View.GONE }.start() }, 10_000)
    }

    /** A slow, gentle push-in: the forest feels alive, never jumpy (ages 3–5). */
    private fun kenBurns() {
        sceneImg.animate().cancel()
        sceneImg.scaleX = 1f; sceneImg.scaleY = 1f; sceneImg.translationX = 0f
        sceneImg.animate().scaleX(1.12f).scaleY(1.12f).translationX(-ui.dp(18).toFloat())
            .setDuration(28_000).setInterpolator(android.view.animation.LinearInterpolator()).start()
    }

    private var swiped = false

    /** The next (+1) or previous (-1) scene, from where we are (the first and last stay put). */
    private fun goScene(step: Int) {
        if (!inLesson || scenes.isEmpty()) return
        val to = (sceneIdx + step).coerceIn(0, scenes.size - 1)
        if (to != sceneIdx) jump(to)
    }

    private fun jump(index: Int) {
        stopSfx()
        token++
        main.removeCallbacksAndMessages(null)
        voice.stop()
        ears.stop()
        stopSong()
        listening.visibility = View.GONE
        asking = null
        sceneIdx = index; beatIdx = 0
        paused = false
        pausedLayer.visibility = View.GONE
        showScene()
        runBeat()
    }

    /** The animal's sound now playing (the farm). */
    private var sfx: android.media.MediaPlayer? = null

    private fun stopSfx() {
        val p = sfx
        sfx = null
        try { p?.release() } catch (_: Exception) {}
    }

    private fun pause() {
        stopSfx()
        paused = true
        token++
        main.removeCallbacksAndMessages(null)
        voice.stop()
        if (::pausedLayer.isInitialized) pausedLayer.visibility = View.VISIBLE
        try { songPlayer?.takeIf { it.isPlaying }?.pause() } catch (_: Exception) {}
        ears.stop()
        if (::listening.isInitialized) listening.visibility = View.GONE
        sceneImg.animate().cancel()
        try { clipPlayer?.takeIf { it.isPlaying }?.pause() } catch (_: Exception) {}
        try { ambientPlayer?.takeIf { it.isPlaying }?.pause() } catch (_: Exception) {}
    }

    private fun resume() {
        paused = false
        pausedLayer.visibility = View.GONE
        kenBurns()
        if (!clipEnded) try { clipPlayer?.start() } catch (_: Exception) {}
        try { ambientPlayer?.start() } catch (_: Exception) {}
        runBeat() // the current line again, from its start
    }

    private fun next() {
        beatIdx++
        if (beatIdx >= scenes[sceneIdx].beats.size) {
            // The scene's animation is still playing (e.g. they're just stepping into the train): it's seen to its end
            // before the next scene (up to 15 seconds more).
            // The child's scenes go on once Pitzki is done (a moment later): no walking on in silence. Only stepping
            // into the train is seen to its end.
            val key = scenes[sceneIdx].key
            val tail = if (key in PitzkiScript.CHILD_KEYS && key !in PitzkiScript.SEEN_TO_END) 900L else 15_000L
            if (clipPlayer != null && !clipEnded && !paused) {
                beatIdx-- // still on the scene's last line while waiting (a pause and resume says it again, never past the end)
                val my = ++token
                val began = System.currentTimeMillis()
                main.post(object : Runnable {
                    override fun run() {
                        if (my != token || paused || destroyed || !inLesson) return
                        if (clipPlayer == null || clipEnded || System.currentTimeMillis() - began > tail) { clipEnded = true; next(); return }
                        main.postDelayed(this, 200)
                    }
                })
                return
            }
            if (sceneIdx + 1 >= scenes.size) { finishLesson(); return }
            sceneIdx++
            beatIdx = 0
            showScene()
        }
        runBeat()
    }

    private fun runBeat() {
        if (!inLesson || paused || destroyed) return
        val my = ++token
        fun then() { if (my == token && !paused) next() }
        when (val b = scenes[sceneIdx].beats[beatIdx]) {
            is Beat.Say -> {
                subtitle.text = b.text
                voice.say(b.text, if (b.personal) "" else b.id) { then() }
            }
            is Beat.Talk -> {
                subtitle.text = b.text
                if (!talking) { voice.say(b.text, "") { then() }; return }
                pendingTalk?.let { pendingTalk = null; clipRendering = false; playClip(it, sceneSeq, withSound = true) }
                // His video says it: wait for its end (and its continuation). Not playing after a few seconds: his voice instead.
                val began = System.currentTimeMillis()
                main.post(object : Runnable {
                    override fun run() {
                        if (my != token || paused || destroyed) return
                        if (clipRendering && clipEnded && nextClip == null) { then(); return }
                        if (!clipRendering && (clipEnded || clipPlayer == null) && System.currentTimeMillis() - began > 4000) {
                            talking = false
                            stopClip()
                            voice.say(b.text, "") { then() }
                            return
                        }
                        main.postDelayed(this, 150)
                    }
                })
            }
            is Beat.Sound -> {
                // The animal's sound (its recording), or Pitzki says it himself when it isn't made yet.
                subtitle.text = "🔊 " + b.text
                val f = PitzkiStore.sfxFile(this, b.animal).takeIf { it.exists() && it.length() > 0 }
                if (f == null || voice.muted) { voice.say(b.text, "") { then() }; return }
                stopSfx()
                try {
                    val mp = android.media.MediaPlayer()
                    mp.setDataSource(f.absolutePath)
                    mp.setOnCompletionListener { if (sfx === mp) stopSfx(); main.postDelayed({ then() }, 300) }
                    mp.setOnErrorListener { _, _, _ -> stopSfx(); then(); true }
                    mp.prepare()
                    mp.start()
                    sfx = mp
                    // A safety net: on to the next even if the phone never says it ended.
                    main.postDelayed({ if (sfx === mp) { stopSfx(); then() } }, maxOf(mp.duration.toLong(), 1000L) + 2500L)
                } catch (_: Exception) { stopSfx(); voice.say(b.text, "") { then() } }
            }
            is Beat.Word -> {
                showWord(b.color)
                bounce(b.color)
                voice.word(b.color.en) { then() }
            }
            is Beat.Wait -> main.postDelayed({ then() }, b.ms)
            is Beat.Listen -> {
                if (!canListen()) { main.postDelayed({ then() }, PitzkiScript.ANSWER_MS); return }
                listeningAt(top = false)
                listening.text = L.t("🎤 פיצקי מקשיב… איזה צבע?", "🎤 Pitzki is listening… what color?")
                listening.visibility = View.VISIBLE
                pulse(listening)
                // 8 seconds to answer (at least a few, even when the phone's recognizer gives up early: it listens again).
                ears.listen(8000) { heard, said ->
                    if (my != token || paused || destroyed) { listening.visibility = View.GONE; return@listen }
                    // What the phone heard, for a moment (so a parent sees that it really listens).
                    val first = heard.firstOrNull { it.isNotBlank() }
                    if (first != null) {
                        listening.text = L.t("👂 שמעתי: ", "👂 I heard: ") + first.take(30)
                        main.postDelayed({ if (my == token) listening.visibility = View.GONE }, 1800)
                    } else if (adminTools() && ears.lastError != 0) {
                        // The admin sees why nothing was heard (the phone recognizer's error code).
                        listening.text = L.t("🎤 לא נשמע (קוד ${ears.lastError})", "🎤 Not heard (code ${ears.lastError})")
                        main.postDelayed({ if (my == token) listening.visibility = View.GONE }, 2500)
                    } else listening.visibility = View.GONE
                    val c = PitzkiScript.heard(heard)
                    when {
                        c == b.color -> {
                            starsNow++
                            starsLabel.text = "⭐ $starsNow"
                            celebrate()
                            val p = PitzkiScript.rightSaid(kid)
                            subtitle.text = p
                            voice.say(p) { then() }
                        }
                        c != null -> {
                            // In short pieces, each in Pitzki's own voice (not the phone's).
                            sayAll(PitzkiScript.wrongSaidParts(kid, c, b.color), my) { then() }
                        }
                        said -> {
                            val t = PitzkiScript.notHeard(kid)
                            subtitle.text = t
                            voice.say(t) { then() }
                        }
                        // Nothing heard at all: Pitzki says it with them, so the turn never just passes silently.
                        else -> {
                            val t = PitzkiScript.notHeard(kid)
                            subtitle.text = t
                            voice.say(t) { then() }
                        }
                    }
                }
            }
            is Beat.Ask -> {
                asking = b.color
                misses = 0
                showStrip(true)
                val seq = ++askSeq
                items[b.color]?.let { pulse(it) }
                // No answer for a while: Pitzki shows it, kindly.
                main.postDelayed({ if (my == token && asking == b.color && askSeq == seq) help(b.color) }, 12_000)
            }
            is Beat.Song -> sing(b, my) { then() }
            is Beat.Celebrate -> {
                celebrate()
                val p = PitzkiScript.praise(kid, praiseN++)
                subtitle.text = p
                voice.say(p) { then() }
            }
            is Beat.Pick -> pickGame(b, my) { then() }
            is Beat.Drag -> dragGame(b, my) { then() }
            is Beat.Hear -> hearGame(b, my) { then() }
            is Beat.Open -> openGame(b, my) { then() }
            is Beat.Count -> countGame(b.n, 1, my) { then() }
            is Beat.TapCount -> tapCountGame(b, my) { then() }
            is Beat.Sum -> sumGame(b, my) { then() }
            is Beat.ShowSum -> showSum(b, my) { then() }
            is Beat.Breathe -> breatheGame(b.times, my) { then() }
            is Beat.Light -> lightGame(my) { then() }
        }
    }

    private fun tap(c: PColor) {
        if (!inLesson) return
        bounce(c)
        val wanted = asking
        if (wanted == null || paused) { showWord(c); return } // just exploring: the word, no score
        val my = ++token // the waiting for an answer ends here
        // Pitzki remembers the first answer to each color (for "what the child knows").
        if (answered.add(PitzkiKnows.colorId(wanted))) chosen?.let { PitzkiStore.remember(this, it.memberId, PitzkiKnows.colorId(wanted), c == wanted) }
        if (c == wanted) {
            asking = null
            starsNow++
            starsLabel.text = "⭐ $starsNow"
            celebrate()
            val p = PitzkiScript.praise(kid, praiseN++)
            subtitle.text = p
            voice.say(p) { if (my == token && !paused) next() }
        } else {
            misses++
            if (misses >= 2) { help(wanted); return }
            val seq = ++askSeq
            sayAll(PitzkiScript.tryAgainParts(kid, c, wanted), my) {}
            main.postDelayed({ if (my == token && asking == wanted && askSeq == seq) help(wanted) }, 12_000)
        }
    }

    /** Shows the right answer, no score lost, and goes on. */
    private fun help(c: PColor) {
        asking = null
        val my = ++token
        items[c]?.let { pulse(it) }
        showWord(c)
        val t = PitzkiScript.showAnswer(c)
        subtitle.text = t
        voice.say(t) { if (my == token && !paused) next() }
    }

    /** The five things slide up when it's the child's turn, and step aside after. */
    private fun showStrip(on: Boolean) {
        if (on == (strip.visibility == View.VISIBLE)) return
        strip.animate().cancel()
        if (on) {
            strip.visibility = View.VISIBLE
            strip.translationY = ui.dp(80).toFloat(); strip.alpha = 0f
            strip.animate().translationY(0f).alpha(1f).setDuration(350).start()
        } else {
            strip.animate().translationY(ui.dp(80).toFloat()).alpha(0f).setDuration(300).withEndAction { strip.visibility = View.GONE }.start()
        }
    }

    // =====================================================================
    // The games of the other worlds: choose the big one, say a word, count, breathe, wait for green
    // =====================================================================

    /** The pictures to choose from while a Pick is on (null: no choosing now). */
    private var game: View? = null
    /** The light game: 0 off, 1 red (wait), 2 green (tap now). */
    private var lightOn = 0
    private var lightView: TextView? = null
    private var lightDone: (() -> Unit)? = null

    /** What stays from the last game through the next question (the basket of apples), gone after it. */
    private var lingering: View? = null

    private fun clearGame() {
        game?.let { stage.removeView(it) }
        game = null
        sumView?.let { stage.removeView(it) }
        sumView = null
        sumNow = null
        lightView?.let { stage.removeView(it) }
        lightView = null
        lightOn = 0
        lightDone = null
    }

    private fun say(text: String, done: () -> Unit) { subtitle.text = text; voice.say(text, "", done) }

    /** "Where's the big one?": big pictures to tap; the right one gets a star, a wrong one a kind "try again". */
    private fun pickGame(b: Beat.Pick, my: Int, done: () -> Unit) {
        clearGame()
        var misses = 0
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER or Gravity.BOTTOM
            layoutParams = lp(wrap, wrap, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = ui.dp(70) }
        }
        val cells = ArrayList<View>()
        for ((i, ch) in b.choices.withIndex()) {
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = DS.pressable(this@PitzkiActivity, DS.rounded(DS.alpha(DS.bg(this@PitzkiActivity), 0.95f), ui.dp(24).toFloat()))
                elevation = ui.dp(6).toFloat()
                setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(8))
                layoutParams = LinearLayout.LayoutParams(wrap, wrap).apply { marginStart = ui.dp(10); marginEnd = ui.dp(10) }
                contentDescription = ch.label
            }
            cell.addView(TextView(this).apply { text = ch.emoji; textSize = 40f * ch.scale; gravity = Gravity.CENTER })
            cell.addView(TextView(this).apply { text = if (PitzkiScript.isFarm) FarmNiqqud.show(ch.label) else ch.label; textSize = 16f; typeface = DS.heading; gravity = Gravity.CENTER; setTextColor(DS.ink(this@PitzkiActivity)) })
            cell.setOnClickListener { chooseIn(b, i, cell, cells, my, { misses++; misses }, done) }
            cells.add(cell)
            row.addView(cell)
        }
        game = row
        stage.addView(row)
        row.alpha = 0f; row.translationY = ui.dp(60).toFloat()
        row.animate().alpha(1f).translationY(0f).setDuration(350).start()
        // No answer for a while: Pitzki shows it.
        main.postDelayed({ if (my == token && game === row) { pulse(cells[b.right]); say(PitzkiScript.HERE_IT_IS) {} } }, 12_000)
        // The child can also say it ("פרה!"): Pitzki listens while the pictures are up (twice, a few seconds each).
        if (b.choices.size > 1 && canListen()) main.postDelayed({ listenForChoice(b, row, cells, my, 2, { misses++; misses }, done) }, 400)
    }

    /** A picture chosen, by a tap or by voice: right -> a star and on; wrong -> try again (the second time he shows it). */
    private fun chooseIn(b: Beat.Pick, i: Int, cell: View, cells: List<View>, my: Int, miss: () -> Int, done: () -> Unit) {
        if (my != token || paused) return
        bounce(cell)
        // Pitzki remembers the first answer to each question (one-choice taps like "יוצאים!" aren't questions).
        if (b.choices.size > 1 && answered.add(b.id)) { scored(i == b.right); chosen?.let { PitzkiStore.remember(this, it.memberId, b.id, i == b.right) } }
        if (i == b.right) {
            clearGame()
            lingering?.let { stage.removeView(it) }; lingering = null
            if (::listening.isInitialized) listening.visibility = View.GONE
            starsNow++; starsLabel.text = "⭐ $starsNow"
            celebrate()
            say(PitzkiScript.praise(kid, praiseN++)) { if (my == token && !paused) done() }
        } else {
            val m = miss()
            val sum = sumNow
            if (m >= 2) { pulse(cells[b.right]); say(PitzkiScript.HERE_IT_IS) {} }
            else if (sum != null) say(NumbersWorld.sumHelp(sum.a, sum.b)) {}
            else say(PitzkiScript.tryAgainPick(kid)) {}
        }
    }

    /** The "listening" bubble: big at the bottom, or small at the top while pictures are there (not hidden behind them). */
    private fun listeningAt(top: Boolean) {
        listening.textSize = if (top) 15f else 22f
        listening.setPadding(ui.dp(if (top) 12 else 18), ui.dp(if (top) 4 else 8), ui.dp(if (top) 12 else 18), ui.dp(if (top) 4 else 8))
        listening.layoutParams = lp(wrap, wrap, (if (top) Gravity.TOP else Gravity.BOTTOM) or Gravity.CENTER_HORIZONTAL).apply {
            if (top) topMargin = ui.dp(56) else bottomMargin = ui.dp(84)
        }
    }

    /** The questions answered in this walk (only the first answer of each is remembered). */
    private val answered = HashSet<String>()
    /** This walk's score: the questions (a picture to choose, an animal to take home) and how many were right the first time. */
    private var asked = 0
    private var rightFirst = 0
    private fun scored(right: Boolean) { asked++; if (right) rightFirst++ }

    private fun listenForChoice(b: Beat.Pick, row: View, cells: List<View>, my: Int, tries: Int, miss: () -> Int, done: () -> Unit) {
        if (my != token || paused || destroyed || game !== row || tries <= 0) return
        listeningAt(top = true)
        listening.text = L.t("🎤 אפשר גם להגיד…", "🎤 You can also say it…")
        listening.visibility = View.VISIBLE
        ears.listen(6000, b.choices.flatMap { c -> c.label.filter { it !in '\u0591'..'\u05C7' }.replace("־", " ").split(" ") } + b.choices.map { it.emoji }) { heard, _ ->
            if (my != token || paused || destroyed || game !== row) return@listen
            heard.firstOrNull { it.isNotBlank() }?.let { listening.text = L.t("👂 שמעתי: ", "👂 I heard: ") + it.take(30) }
            main.postDelayed({ if (my == token && game !== row) listening.visibility = View.GONE }, 1500)
            val digits = b.choices.map { it.emoji }.takeIf { d -> d.all { e -> e.isNotEmpty() && e.all { it.isDigit() } } } ?: emptyList()
            val i = PitzkiScript.heardChoice(heard, b.choices.map { it.label }, digits)
            if (i >= 0) chooseIn(b, i, cells[i], cells, my, miss, done)
            // Nothing clear: listen once more (the pictures are still there to tap).
            if (i != b.right) main.postDelayed({ listenForChoice(b, row, cells, my, tries - 1, miss, done) }, 2500)
        }
    }

    /**
     * Taking the animals home: each one dragged with a finger to its dark shape. Near it -> it jumps in, its sound,
     * a star. Elsewhere -> back to its place ("not its home"). [Beat.Drag.only]: only that one is asked for.
     */
    private fun dragGame(b: Beat.Drag, my: Int, done: () -> Unit) {
        clearGame()
        val layer = FrameLayout(this).apply { layoutParams = lp(match, match, Gravity.CENTER) }
        val w = stage.width.toFloat().takeIf { it > 0 } ?: resources.displayMetrics.widthPixels.toFloat()
        val h = stage.height.toFloat().takeIf { it > 0 } ?: resources.displayMetrics.heightPixels.toFloat()
        val n = b.items.size
        // The homes on the right in another order; the animals on the left.
        val order = (0 until n).toList().let { if (n > 1) it.drop(1) + it.first() else it }
        val size = ui.dp(if (n >= 4) 66 else 86)
        val big = if (n >= 4) 40f else 52f
        fun yAt(i: Int) = h * (i + 1) / (n + 1) - size / 2f - ui.dp(10)
        val homes = ArrayList<View>()
        val silhouette = android.graphics.Paint().apply {
            // A solid dark shape of the animal (clear on any scene), on a light card with a dashed edge: "put it here".
            colorFilter = android.graphics.ColorMatrixColorFilter(floatArrayOf(0f, 0f, 0f, 0f, 60f, 0f, 0f, 0f, 0f, 45f, 0f, 0f, 0f, 0f, 35f, 0f, 0f, 0f, 0.9f, 0f))
        }
        for (i in 0 until n) {
            val c = b.items[i]
            // The card (light, dashed edge) holds the shape; only the shape is darkened (the filter is on it alone).
            val home = FrameLayout(this).apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = ui.dp(26).toFloat()
                    setColor(android.graphics.Color.argb(215, 255, 250, 235))
                    setStroke(ui.dp(3), android.graphics.Color.rgb(121, 85, 58), ui.dp(9).toFloat(), ui.dp(6).toFloat())
                }
                elevation = ui.dp(3).toFloat()
                layoutParams = FrameLayout.LayoutParams(size, size)
                x = w * 0.8f - size / 2f; y = yAt(order.indexOf(i))
            }
            home.addView(TextView(this).apply {
                text = c.emoji; textSize = big * c.scale; gravity = Gravity.CENTER
                setLayerType(View.LAYER_TYPE_HARDWARE, silhouette)
                layoutParams = FrameLayout.LayoutParams(size, size)
            })
            // A soft "here" pulse until it's filled.
            android.animation.ObjectAnimator.ofFloat(home, View.SCALE_X, 1f, 1.07f).apply { duration = 800; repeatCount = android.animation.ValueAnimator.INFINITE; repeatMode = android.animation.ValueAnimator.REVERSE; start() }
            android.animation.ObjectAnimator.ofFloat(home, View.SCALE_Y, 1f, 1.07f).apply { duration = 800; repeatCount = android.animation.ValueAnimator.INFINITE; repeatMode = android.animation.ValueAnimator.REVERSE; start() }
            homes.add(home); layer.addView(home)
        }
        var left = if (b.only >= 0) 1 else n
        var misses = 0
        for (i in 0 until n) {
            val c = b.items[i]
            val home = homes[i]
            // The animals in the middle, the homes on the right: the child and Pitzki wait on the left (play_yard).
            val startX = w * 0.5f - size / 2f; val startY = yAt(i)
            val piece = TextView(this).apply {
                text = c.emoji; textSize = big * c.scale; gravity = Gravity.CENTER
                background = DS.rounded(android.graphics.Color.argb(235, 255, 252, 245), ui.dp(26).toFloat())
                elevation = ui.dp(6).toFloat()
                layoutParams = FrameLayout.LayoutParams(size, size)
                x = startX; y = startY
                contentDescription = c.label
            }
            var dx = 0f; var dy = 0f; var homeNow = false
            piece.setOnTouchListener { v, e ->
                if (my != token || paused || homeNow) return@setOnTouchListener false
                when (e.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> { dx = v.x - e.rawX; dy = v.y - e.rawY; v.animate().scaleX(1.15f).scaleY(1.15f).setDuration(120).start(); v.elevation = ui.dp(14).toFloat() }
                    android.view.MotionEvent.ACTION_MOVE -> { v.x = e.rawX + dx; v.y = e.rawY + dy }
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                        v.animate().scaleX(1f).scaleY(1f).setDuration(120).start(); v.elevation = ui.dp(6).toFloat()
                        val near = homes.indexOfFirst { hv -> kotlin.math.hypot((v.x - hv.x).toDouble(), (v.y - hv.y).toDouble()) < size * 0.75 }
                        val wanted = b.only < 0 || b.only == i
                        if (near == i && wanted) {
                            homeNow = true
                            v.animate().x(home.x).y(home.y).setDuration(180).start()
                            home.animate().alpha(0f).setDuration(200).start()
                            if (answered.add(b.id + "_" + i)) { scored(misses == 0); chosen?.let { PitzkiStore.remember(this, it.memberId, b.id + "_" + i, misses == 0) } }
                            starsNow++; starsLabel.text = "⭐ $starsNow"
                            playAnimalSound(c.label)
                            cheerClip()
                            left--
                            if (left <= 0) main.postDelayed({
                                if (my != token) return@postDelayed
                                celebrate()
                                say(PitzkiScript.praise(kid, praiseN++)) { if (my == token && !paused) { clearGame(); done() } }
                            }, 900)
                        } else {
                            misses++
                            v.animate().x(startX).y(startY).setDuration(260).start()
                            if (near >= 0 || !wanted) say(if (!wanted) L.t("לא עכשיו. ", "Not now. ") + PitzkiScript.tryAgainPick(kid) else L.t("זה לא הבית שלו. ", "That's not its home. ") + PitzkiScript.tryAgainPick(kid)) {}
                            // Twice wrong: the home it's looking for blinks.
                            if (misses >= 2) pulse(homes[if (b.only >= 0) b.only else i])
                        }
                    }
                }
                true
            }
            layer.addView(piece)
        }
        game = layer
        stage.addView(layer, stage.indexOfChild(subtitle).coerceAtLeast(0))
        layer.alpha = 0f
        layer.animate().alpha(1f).setDuration(350).start()
    }

    /** The farm animal's own sound (its recording, made once for every family), if it has one. */
    private fun playAnimalSound(label: String) {
        val a = FarmWorld.ANIMALS.firstOrNull { it.he == label } ?: FarmWorld.ANIMALS.firstOrNull { it.id == "horse" && label in listOf("גדול", "קטן") } ?: return
        val f = PitzkiStore.sfxFile(this, a.id).takeIf { it.exists() && it.length() > 0 } ?: return
        if (voice.muted) return
        stopSfx()
        try {
            val mp = android.media.MediaPlayer()
            mp.setDataSource(f.absolutePath)
            mp.setOnCompletionListener { if (sfx === mp) stopSfx() }
            mp.prepare(); mp.start()
            sfx = mp
        } catch (_: Exception) { stopSfx() }
    }

    /** An open question out loud: Pitzki answers what was said (no wrong answers, no stars lost). */
    private fun openGame(b: Beat.Open, my: Int, done: () -> Unit) {
        if (!canListen()) { main.postDelayed({ if (my == token && !paused) say(b.quiet) { done() } }, PitzkiScript.ANSWER_MS); return }
        listeningAt(top = false)
        listening.text = L.t("🎤 פיצקי מקשיב…", "🎤 Pitzki is listening…")
        listening.visibility = View.VISIBLE
        pulse(listening)
        ears.listen(8000, b.answers.flatMap { it.first }) { heard, _ ->
            if (my != token || paused || destroyed) { listening.visibility = View.GONE; return@listen }
            heard.firstOrNull { it.isNotBlank() }?.let { listening.text = L.t("👂 שמעתי: ", "👂 I heard: ") + it.take(30) }
            main.postDelayed({ if (my == token) listening.visibility = View.GONE }, 1500)
            val reply = b.answers.firstOrNull { PitzkiScript.heardAny(heard, it.first) }?.second
                ?: if (heard.any { it.isNotBlank() }) b.any else b.quiet
            if (reply != b.quiet) { starsNow++; starsLabel.text = "⭐ $starsNow"; celebrate() }
            say(reply) { done() }
        }
    }

    /** The child says a word out loud ("thank you", "slow!"): heard -> Pitzki is pleased; not -> he says it with them. */
    private fun hearGame(b: Beat.Hear, my: Int, done: () -> Unit) {
        if (!canListen()) { main.postDelayed({ if (my == token && !paused) say(b.hint) { done() } }, PitzkiScript.ANSWER_MS); return }
        listeningAt(top = false)
        listening.text = L.t("🎤 פיצקי מקשיב…", "🎤 Pitzki is listening…")
        listening.visibility = View.VISIBLE
        pulse(listening)
        // "Ready?" (the same answer whatever is said): a short wait, not 8 seconds of silence.
        ears.listen(if (b.ok == b.hint) 3500 else 8000, b.words) { heard, said ->
            if (my != token || paused || destroyed) { listening.visibility = View.GONE; return@listen }
            heard.firstOrNull { it.isNotBlank() }?.let { listening.text = L.t("👂 שמעתי: ", "👂 I heard: ") + it.take(30) }
            main.postDelayed({ if (my == token) listening.visibility = View.GONE }, 1500)
            // (An animal's sound isn't words: for "any answer", the child's voice heard at all is enough.)
            val okHeard = PitzkiScript.heardAny(heard, b.words) || (Beat.ANY in b.words && (said || heard.any { it.isNotBlank() }))
            if (Beat.ANY !in b.words && answered.add(b.id)) chosen?.let { PitzkiStore.remember(this, it.memberId, b.id, okHeard) }
            if (okHeard) {
                starsNow++; starsLabel.text = "⭐ $starsNow"
                celebrate()
                say(b.ok) { done() }
            } else say(b.hint) { done() }
        }
    }

    /** Counting together, a big number for each one (waiting patiently). */
    /** The sum on the screen ("🍎🍎 + 🍎 = ?") while its answer is chosen, and which sum it is. */
    private var sumView: View? = null
    private var sumNow: Beat.Sum? = null

    /**
     * The apples in the scene's picture, where they are on the screen now (center x, y and radius): the counting game
     * puts its circles on the real apples on the tree. Empty when the picture isn't there or has too few apples.
     */
    private fun applesOnScreen(n: Int, most: Int = n): List<Triple<Float, Float, Float>> {
        return try {
            val d = sceneImg.drawable ?: return emptyList()
            val top = if (d is android.graphics.drawable.TransitionDrawable) d.getDrawable(d.numberOfLayers - 1) else d
            val bmp = (top as? android.graphics.drawable.BitmapDrawable)?.bitmap ?: return emptyList()
            if (bmp.width <= 0 || d.intrinsicWidth <= 0) return emptyList()
            val sw = 240; val sh = (bmp.height * sw / bmp.width).coerceAtLeast(1)
            val small = android.graphics.Bitmap.createScaledBitmap(bmp, sw, sh, true)
            val px = IntArray(sw * sh)
            small.getPixels(px, 0, sw, 0, 0, sw, sh)
            if (small !== bmp) small.recycle()
            // On the tree only (apples fallen on the grass are not for picking).
            val spots = AppleSpots.find(px, sw, sh, most, lowest = 0.62f, need = n)
            if (spots.size < n) return emptyList()
            // Picture pixels -> the drawable (its own width and height: a cross-fade stretches the picture to the
            // bigger of the two) -> the view (crop) -> the view's own zoom -> the stage.
            val kx = d.intrinsicWidth.toFloat() / sw
            val ky = d.intrinsicHeight.toFloat() / sh
            val m = android.graphics.Matrix(sceneImg.imageMatrix)
            m.postConcat(sceneImg.matrix)
            m.postTranslate(sceneImg.left.toFloat(), sceneImg.top.toFloat())
            val scale = floatArrayOf(0f, 0f, 1f, 0f)
            m.mapVectors(scale)
            val unit = kotlin.math.hypot(scale[0].toDouble(), scale[1].toDouble()).toFloat()
            spots.map { sp ->
                val pt = floatArrayOf(sp.x * kx, sp.y * ky)
                m.mapPoints(pt)
                Triple(pt[0], pt[1], sp.r * kx * unit)
            }
        } catch (_: Throwable) { emptyList() }
    }

    /**
     * Picking apples into the basket: every apple on the tree in the picture can be touched; a touched one flies into
     * the basket and Pitzki counts it ("שתיים!"). When [Beat.TapCount.n] are in the basket: a star (then "how many
     * apples are in the basket?"). The tree has more apples than that: we pick up to the number, not count the tree.
     */
    private fun appleBasket(b: Beat.TapCount, real: List<Triple<Float, Float, Float>>, my: Int, layer: FrameLayout, done: () -> Unit) {
        val w = stage.width.toFloat().takeIf { it > 0 } ?: resources.displayMetrics.widthPixels.toFloat()
        val h = stage.height.toFloat().takeIf { it > 0 } ?: resources.displayMetrics.heightPixels.toFloat()
        val n = b.n
        var count = 0
        var busy = false
        var lastTouch = System.currentTimeMillis()
        val ring = { fill: Int, stroke: Int ->
            android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(fill); setStroke(ui.dp(3), stroke) }
        }
        // The basket, on the grass on the left, with the number in it.
        val basketSize = ui.dp(104)
        val basket = TextView(this).apply {
            text = "🧺"; textSize = 66f; gravity = Gravity.CENTER
            setShadowLayer(12f, 0f, 4f, android.graphics.Color.argb(140, 0, 0, 0))
            layoutParams = FrameLayout.LayoutParams(basketSize, basketSize)
            x = w * 0.08f; y = h * 0.6f - basketSize / 2f
        }
        val inBasket = TextView(this).apply {
            text = ""; textSize = 26f; typeface = DS.heading; gravity = Gravity.CENTER
            setTextColor(android.graphics.Color.WHITE)
            background = DS.rounded(android.graphics.Color.rgb(46, 125, 50), ui.dp(18).toFloat())
            layoutParams = FrameLayout.LayoutParams(ui.dp(40), ui.dp(40))
            x = basket.x + basketSize - ui.dp(26); y = basket.y - ui.dp(6)
            visibility = View.GONE
        }
        layer.addView(basket); layer.addView(inBasket)
        val targets = ArrayList<View>()
        for ((cx, cy, r) in real) {
            val size = maxOf((r * 2.5f).toInt(), ui.dp(50))
            val t = View(this).apply {
                background = ring(android.graphics.Color.argb(30, 255, 255, 255), android.graphics.Color.argb(230, 255, 255, 255))
                layoutParams = FrameLayout.LayoutParams(size, size)
                x = cx - size / 2f; y = cy - size / 2f
                contentDescription = "🍎"
            }
            android.animation.ObjectAnimator.ofFloat(t, View.ALPHA, 1f, 0.45f).apply {
                duration = 900; repeatCount = android.animation.ValueAnimator.INFINITE; repeatMode = android.animation.ValueAnimator.REVERSE
                startDelay = (targets.size * 120).toLong(); start(); t.tag = this
            }
            t.setOnClickListener {
                if (my != token || paused || busy || count >= n || t.visibility != View.VISIBLE) return@setOnClickListener
                lastTouch = System.currentTimeMillis()
                count++
                val now = count
                (t.tag as? android.animation.Animator)?.cancel()
                t.visibility = View.INVISIBLE
                // The apple flies from the tree into the basket.
                val fly = TextView(this).apply {
                    text = "🍎"; textSize = 30f; gravity = Gravity.CENTER
                    layoutParams = FrameLayout.LayoutParams(ui.dp(44), ui.dp(44))
                    x = cx - ui.dp(22); y = cy - ui.dp(22)
                    elevation = ui.dp(6).toFloat()
                }
                layer.addView(fly)
                val endX = basket.x + basketSize / 2f - ui.dp(22) + ((now - 1) % 4 - 1.5f) * ui.dp(12)
                val endY = basket.y + ui.dp(4) - ((now - 1) / 4) * ui.dp(10)
                fly.animate().x(endX).y(endY).scaleX(0.75f).scaleY(0.75f).setDuration(520)
                    .setInterpolator(android.view.animation.AccelerateDecelerateInterpolator()).withEndAction { if (my == token) bounce(basket) }.start()
                inBasket.text = now.toString(); inBasket.visibility = View.VISIBLE; bounce(inBasket)
                busy = true
                say(PitzkiScript.NUMBERS[now - 1] + "!") {
                    busy = false
                    if (my != token || paused) return@say
                    if (now == n) {
                        targets.forEach { (it.tag as? android.animation.Animator)?.cancel(); it.visibility = View.GONE }
                        starsNow++; starsLabel.text = "⭐ $starsNow"
                        celebrate()
                        // The basket (with its apples) stays on through the question "how many apples are in the basket?".
                        say(PitzkiScript.praise(kid, praiseN++)) {
                            if (my == token && !paused) { game = null; lingering?.let { stage.removeView(it) }; lingering = layer; done() }
                        }
                    }
                }
            }
            targets.add(t); layer.addView(t)
        }
        game = layer
        stage.addView(layer)
        if (::clipView.isInitialized) clipView.animate().alpha(0f).setDuration(250).start()
        layer.alpha = 0f
        layer.animate().alpha(1f).setDuration(350).start()
        fun nudge() {
            if (my != token || game !== layer) { targets.forEach { (it.tag as? android.animation.Animator)?.cancel() }; return }
            if (!paused && !busy && System.currentTimeMillis() - lastTouch > 9000) {
                targets.firstOrNull { it.visibility == View.VISIBLE }?.let { pulse(it) }
                lastTouch = System.currentTimeMillis()
            }
            main.postDelayed({ nudge() }, 3000)
        }
        main.postDelayed({ nudge() }, 3000)
    }

    /**
     * Counting things by touching each one. Apples: circles on the real apples of the tree in the picture (each touched
     * one lights up and gets its number, and Pitzki says it: "שלוש!"). Other things (balloons), or a picture without
     * enough apples: the things themselves float over the scene. Each is counted once; when all are counted, a star.
     * No touch for a while: the next one to touch pulses.
     */
    private fun tapCountGame(b: Beat.TapCount, my: Int, done: () -> Unit) {
        clearGame()
        val layer = FrameLayout(this).apply { layoutParams = lp(match, match, Gravity.CENTER) }
        val w = stage.width.toFloat().takeIf { it > 0 } ?: resources.displayMetrics.widthPixels.toFloat()
        val h = stage.height.toFloat().takeIf { it > 0 } ?: resources.displayMetrics.heightPixels.toFloat()
        val n = b.n.coerceIn(1, 20)
        val rnd = java.util.Random(b.id.hashCode().toLong())
        val pieces = ArrayList<View>()
        val counted = BooleanArray(n)
        var count = 0
        var busy = false
        var lastTouch = System.currentTimeMillis()
        // The real apples: the still picture is shown (the video's last frame may have moved), and it stays still.
        val real = if (b.emoji == "🍎") {
            sceneImg.animate().cancel()
            applesOnScreen(n, most = n + 4)
        } else emptyList()
        if (real.isNotEmpty()) { appleBasket(b, real, my, layer, done); return }
        if (real.isNotEmpty() && ::clipView.isInitialized) clipView.animate().alpha(0f).setDuration(250).start()
        val ring = { fill: Int, stroke: Int ->
            android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(fill); setStroke(ui.dp(3), stroke) }
        }
        for (k in 0 until n) {
            val piece: View
            if (real.isNotEmpty()) {
                val (cx, cy, r) = real[k]
                val size = maxOf((r * 2.5f).toInt(), ui.dp(50))
                piece = View(this).apply {
                    background = ring(android.graphics.Color.argb(30, 255, 255, 255), android.graphics.Color.argb(230, 255, 255, 255))
                    layoutParams = FrameLayout.LayoutParams(size, size)
                    x = cx - size / 2f; y = cy - size / 2f
                    contentDescription = b.emoji
                }
                // A soft breathing ring: "touch me".
                android.animation.ObjectAnimator.ofFloat(piece, View.ALPHA, 1f, 0.45f).apply {
                    duration = 900; repeatCount = android.animation.ValueAnimator.INFINITE; repeatMode = android.animation.ValueAnimator.REVERSE
                    startDelay = (k * 120).toLong(); start()
                    piece.tag = this
                }
            } else {
                // Floating over the sky of the scene, gently bobbing (no boxes, no cards).
                // Big enough for a small finger, spread over the middle of the scene (under the top bar, over the words).
                val size = ui.dp(if (n > 10) 66 else 84)
                val cols = if (n > 10) 6 else if (n > 6) 4 else n.coerceAtLeast(3)
                val rows = (n + cols - 1) / cols
                val top = h * 0.2f; val bottom = h * 0.8f
                val cellW = w * 0.8f / cols; val cellH = (bottom - top) / rows
                val r0 = k / cols; val c0 = k % cols
                piece = TextView(this).apply {
                    text = b.emoji; textSize = if (n > 10) 42f else 54f; gravity = Gravity.CENTER
                    setShadowLayer(10f, 0f, 3f, android.graphics.Color.argb(120, 0, 0, 0))
                    layoutParams = FrameLayout.LayoutParams(size, size)
                    x = w * 0.1f + cellW * c0 + (cellW - size) / 2f + (rnd.nextFloat() - 0.5f) * cellW * 0.4f
                    y = top + cellH * r0 + (cellH - size) / 2f + (rnd.nextFloat() - 0.5f) * cellH * 0.3f
                    contentDescription = b.emoji
                }
                // (Its place is its translation: the bob moves from there, never back to the top.)
                android.animation.ObjectAnimator.ofFloat(piece, View.TRANSLATION_Y, piece.translationY, piece.translationY - ui.dp(8)).apply {
                    duration = 1400L + rnd.nextInt(600); repeatCount = android.animation.ValueAnimator.INFINITE; repeatMode = android.animation.ValueAnimator.REVERSE
                    start(); piece.tag = this
                }
            }
            piece.setOnClickListener {
                if (my != token || paused || busy || counted[k]) return@setOnClickListener
                lastTouch = System.currentTimeMillis()
                counted[k] = true
                count++
                val now = count
                (piece.tag as? android.animation.Animator)?.cancel()
                piece.alpha = 1f
                if (real.isNotEmpty()) piece.background = ring(android.graphics.Color.argb(90, 255, 214, 102), android.graphics.Color.rgb(255, 202, 40))
                else piece.background = ring(android.graphics.Color.argb(150, 255, 214, 102), android.graphics.Color.rgb(255, 202, 40))
                bounce(piece)
                // Its number, small, on it.
                val lpw = piece.layoutParams.width
                layer.addView(TextView(this).apply {
                    text = now.toString(); textSize = 15f; typeface = DS.heading; gravity = Gravity.CENTER
                    setTextColor(android.graphics.Color.WHITE)
                    background = DS.rounded(android.graphics.Color.rgb(46, 125, 50), ui.dp(13).toFloat())
                    elevation = ui.dp(8).toFloat()
                    layoutParams = FrameLayout.LayoutParams(ui.dp(26), ui.dp(26))
                    x = piece.x + lpw - ui.dp(20); y = piece.y - ui.dp(4)
                })
                countBubble.text = now.toString(); countBubble.visibility = View.VISIBLE; bounce(countBubble)
                busy = true
                say(PitzkiScript.NUMBERS[now - 1] + "!") {
                    busy = false
                    if (my != token || paused) return@say
                    if (now == n) {
                        countBubble.visibility = View.GONE
                        clearGame()
                        starsNow++; starsLabel.text = "⭐ $starsNow"
                        celebrate()
                        say(PitzkiScript.praise(kid, praiseN++)) { if (my == token && !paused) done() }
                    }
                }
            }
            pieces.add(piece); layer.addView(piece)
        }
        game = layer
        stage.addView(layer)
        layer.alpha = 0f
        layer.animate().alpha(1f).setDuration(350).start()
        fun nudge() {
            if (my != token || game !== layer) { pieces.forEach { (it.tag as? android.animation.Animator)?.cancel() }; return }
            if (!paused && !busy && System.currentTimeMillis() - lastTouch > 9000) {
                (0 until n).firstOrNull { !counted[it] }?.let { pulse(pieces[it]) }
                lastTouch = System.currentTimeMillis()
            }
            main.postDelayed({ nudge() }, 3000)
        }
        main.postDelayed({ nudge() }, 3000)
    }

    /**
     * Pitzki teaches a sum with things to see: the first group appears ("here are two"), the second joins it ("and one
     * more"), then all are counted together, each one jumping as its number is said, and "= 3" appears with the answer.
     */
    private fun showSum(b: Beat.ShowSum, my: Int, done: () -> Unit) {
        clearGame()
        val total = (b.a + b.b).coerceIn(1, 20)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutDirection = View.LAYOUT_DIRECTION_LTR // a sum reads left to right
            background = DS.rounded(DS.alpha(DS.bg(this@PitzkiActivity), 0.92f), ui.dp(26).toFloat())
            elevation = ui.dp(6).toFloat()
            setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(8))
            layoutParams = lp(wrap, wrap, Gravity.CENTER).apply { bottomMargin = ui.dp(40) }
        }
        fun part(t: String, sizeSp: Float) = TextView(this).apply {
            text = t; textSize = sizeSp; gravity = Gravity.CENTER
            typeface = DS.heading; setTextColor(DS.ink(this@PitzkiActivity))
            setPadding(ui.dp(3), 0, ui.dp(3), 0)
            alpha = 0f
        }
        val big = if (total > 6) 34f else 44f
        val items = ArrayList<TextView>()
        repeat(b.a) { items.add(part(b.emoji, big)) }
        val plus = part("+", 36f)
        repeat(b.b) { items.add(part(b.emoji, big)) }
        val eq = part("=", 36f)
        val answer = part(total.toString(), 44f).apply { setTextColor(android.graphics.Color.rgb(46, 125, 50)) }
        items.take(b.a).forEach { row.addView(it) }
        row.addView(plus)
        items.drop(b.a).forEach { row.addView(it) }
        row.addView(eq); row.addView(answer)
        game = row
        stage.addView(row)
        fun ok() = my == token && !paused && !destroyed && game === row
        fun countFrom(i: Int) {
            if (!ok()) return
            if (i > total) {
                eq.animate().alpha(1f).setDuration(250).start()
                answer.alpha = 1f; bounce(answer)
                say(b.total) { main.postDelayed({ if (ok()) { clearGame(); done() } }, 700) }
                return
            }
            val v = items[i - 1]
            v.background = DS.rounded(android.graphics.Color.argb(110, 255, 214, 102), ui.dp(16).toFloat())
            bounce(v)
            say(PitzkiScript.NUMBERS[i - 1] + "!") { main.postDelayed({ countFrom(i + 1) }, 250) }
        }
        // 1) the first group, one by one.
        items.take(b.a).forEachIndexed { k, v -> main.postDelayed({ if (ok()) { v.alpha = 1f; bounce(v) } }, 200L + k * 250L) }
        main.postDelayed({
            if (!ok()) return@postDelayed
            say(b.look) {
                if (!ok()) return@say
                // 2) "+" and the second group joining.
                plus.alpha = 1f
                items.drop(b.a).forEachIndexed { k, v -> main.postDelayed({ if (ok()) { v.alpha = 1f; v.translationX = ui.dp(30).toFloat(); v.animate().translationX(0f).setDuration(300).start() } }, 150L + k * 250L) }
                main.postDelayed({
                    if (!ok()) return@postDelayed
                    // 3) all of them counted together.
                    say(b.add) { main.postDelayed({ countFrom(1) }, 300) }
                }, 300L + b.b * 250L)
            }
        }, 300L + b.a * 250L)
    }

    /**
     * A sum to see: the first group, "+", the second group (they come in one after the other), then "= ?" and three
     * numbers to choose from (tap or out loud). A wrong answer: Pitzki counts them all together with the child.
     */
    private fun sumGame(b: Beat.Sum, my: Int, done: () -> Unit) {
        clearGame()
        val total = (b.a + b.b).coerceIn(1, 20)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutDirection = View.LAYOUT_DIRECTION_LTR // a sum reads left to right
            background = DS.rounded(DS.alpha(DS.bg(this@PitzkiActivity), 0.9f), ui.dp(24).toFloat())
            elevation = ui.dp(6).toFloat()
            setPadding(ui.dp(14), ui.dp(6), ui.dp(14), ui.dp(6))
            layoutParams = lp(wrap, wrap, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = ui.dp(104) }
        }
        fun part(t: String, big: Boolean = false) = TextView(this).apply {
            text = t; textSize = if (big) 34f else 30f; gravity = Gravity.CENTER
            typeface = DS.heading; setTextColor(DS.ink(this@PitzkiActivity))
            setPadding(ui.dp(4), 0, ui.dp(4), 0)
        }
        val a = part(b.emoji.repeat(b.a)); val plus = part("+", true); val bb = part(b.emoji.repeat(b.b)); val eq = part("= ?", true)
        listOf(a, plus, bb, eq).forEach { it.alpha = 0f; row.addView(it) }
        stage.addView(row)
        sumView = row
        a.animate().alpha(1f).setDuration(300).start()
        main.postDelayed({ if (my == token) { plus.animate().alpha(1f).setDuration(250).start(); bb.alpha = 1f; bounce(bb) } }, 700)
        main.postDelayed({
            if (my != token || paused) return@postDelayed
            eq.animate().alpha(1f).setDuration(250).start()
            val (choices, right) = NumbersWorld.choices(total)
            // pickGame clears the game (not the sum): the sum stays up while choosing.
            val keep = sumView; sumView = null
            pickGame(Beat.Pick(b.id, choices, right), my) { done() }
            sumView = keep; sumNow = b
        }, 1500)
    }

    private fun countGame(n: Int, i: Int, my: Int, done: () -> Unit) {
        if (my != token || paused) return
        if (i > n) { countBubble.visibility = View.GONE; done(); return }
        countBubble.text = i.toString()
        countBubble.visibility = View.VISIBLE
        bounce(countBubble)
        say(PitzkiScript.NUMBERS[i - 1] + "!") { main.postDelayed({ countGame(n, i + 1, my, done) }, 500) }
    }

    /** Breathing together: a soft circle grows (in…) and shrinks (out…). */
    private fun breatheGame(times: Int, my: Int, done: () -> Unit) {
        clearGame()
        val ball = TextView(this).apply {
            text = "🫧"; textSize = 60f; gravity = Gravity.CENTER
            background = DS.oval(DS.alpha(DS.bg(this@PitzkiActivity), 0.7f))
            layoutParams = lp(ui.dp(170), ui.dp(170), Gravity.CENTER)
            scaleX = 0.6f; scaleY = 0.6f
        }
        stage.addView(ball)
        lightView = ball // removed by clearGame
        fun round(k: Int) {
            if (my != token || paused) return
            if (k >= times) { clearGame(); done(); return }
            ball.animate().scaleX(1.25f).scaleY(1.25f).setDuration(3000).start()
            say(PitzkiScript.BREATHE_IN) {}
            main.postDelayed({
                if (my != token || paused) return@postDelayed
                ball.animate().scaleX(0.6f).scaleY(0.6f).setDuration(3000).start()
                say(PitzkiScript.BREATHE_OUT) {}
                main.postDelayed({ round(k + 1) }, 3200)
            }, 3200)
        }
        round(0)
    }

    /** Red… (wait) …green: tap! Tapping on red: "not yet". Patience, and tapping exactly at the right moment. */
    private fun lightGame(my: Int, done: () -> Unit) {
        clearGame()
        val light = TextView(this).apply {
            text = "🔴"; textSize = 96f; gravity = Gravity.CENTER
            background = DS.rounded(DS.alpha(DS.ink(this@PitzkiActivity), 0.75f), ui.dp(36).toFloat())
            setPadding(ui.dp(24), ui.dp(10), ui.dp(24), ui.dp(10))
            layoutParams = lp(wrap, wrap, Gravity.CENTER)
        }
        stage.addView(light)
        lightView = light
        lightOn = 1
        lightDone = done
        val waitMs = 2500L + (Math.random() * 2500).toLong()
        main.postDelayed({
            if (my != token || lightView !== light) return@postDelayed
            lightOn = 2
            light.text = "🟢"
            bounce(light)
            // Not tapped: Pitzki says "now!", then goes on anyway.
            main.postDelayed({ if (my == token && lightOn == 2 && lightView === light) say(PitzkiScript.LIGHT_NOW) {} }, 6000)
            main.postDelayed({ if (my == token && lightOn == 2 && lightView === light) { val d = lightDone; clearGame(); d?.invoke() } }, 14_000)
        }, waitMs)
    }

    private fun lightTap() {
        val light = lightView ?: return
        if (lightOn == 1) { bounce(light); say(PitzkiScript.LIGHT_WAIT) {}; return }
        if (lightOn == 2) {
            val d = lightDone
            val my = token
            clearGame()
            starsNow++; starsLabel.text = "⭐ $starsNow"
            celebrate()
            say(PitzkiScript.praise(kid, praiseN++)) { if (my == token && !paused) d?.invoke() }
        }
    }

    private fun showWord(c: PColor) {
        wordBubble.text = c.en
        wordBubble.setTextColor(colorOf(c))
        wordBubble.visibility = View.VISIBLE
        wordBubble.scaleX = 0.5f; wordBubble.scaleY = 0.5f
        wordBubble.animate().scaleX(1f).scaleY(1f).setDuration(350).setInterpolator(android.view.animation.OvershootInterpolator()).start()
    }

    private fun bounce(c: PColor) {
        val v = items[c] ?: return
        v.animate().cancel()
        v.scaleX = 1f; v.scaleY = 1f
        v.animate().scaleX(1.12f).scaleY(1.12f).setDuration(140).withEndAction { v.animate().scaleX(1f).scaleY(1f).setDuration(160).start() }.start()
    }

    private fun bounce(v: View) {
        v.animate().cancel()
        v.animate().scaleX(1.18f).scaleY(1.18f).setDuration(140).withEndAction {
            v.animate().scaleX(1f).scaleY(1f).setDuration(200).start()
        }.start()
    }

    private fun pulse(v: View) {
        v.animate().cancel()
        v.animate().scaleX(1.15f).scaleY(1.15f).setDuration(450).withEndAction {
            v.animate().scaleX(1f).scaleY(1f).setDuration(450).start()
        }.start()
    }

    private fun celebrate() {
        burst.visibility = View.VISIBLE
        burst.alpha = 1f; burst.scaleX = 0.3f; burst.scaleY = 0.3f
        burst.animate().scaleX(1.4f).scaleY(1.4f).alpha(0f).setDuration(1100).withEndAction { burst.visibility = View.GONE }.start()
    }

    private fun finishLesson() {
        asking = null
        chosen?.let { PitzkiStore.addStars(this, it.memberId, starsNow); PitzkiStore.markDone(this, it.memberId) }
        celebrate()
        // The stars only when some were won (never "0 stars" to a child).
        val won = if (starsNow > 0) L.t(" $starsNow כוכבים ⭐", " $starsNow stars ⭐") else " ⭐"
        subtitle.text = if (kid.named) L.t("כל הכבוד, ${kid.name}!", "Well done, ${kid.name}!") + won
            else L.t("כל הכבוד!", "Well done!") + won
        // The score and the level: a perfect walk (100) moves up a level in this world.
        val max = PitzkiLevels.max(PitzkiScript.worldId)
        if (max > 1 && asked > 0) {
            val score = PitzkiLevels.score(asked, rightFirst)
            val now = PitzkiScript.level
            val next = PitzkiLevels.next(now, max, asked, rightFirst)
            if (next != now) chosen?.let { PitzkiStore.setLevel(this, it.memberId, PitzkiScript.worldId, next) }
            subtitle.text = subtitle.text.toString() + "\n" + when {
                next > now -> L.t("ציון 100 💯  עלית לרמה $next! 🎉", "Score 100 💯  Up to level $next! 🎉")
                score == 100 -> L.t("ציון 100 💯  הרמה הכי גבוהה 🏆", "Score 100 💯  Top level 🏆")
                else -> L.t("ציון $score · רמה $now · עם 100 עולים רמה", "Score $score · level $now · 100 moves up")
            }
            if (score == 100) main.postDelayed({ if (!destroyed && inLesson) { celebrate(); voice.say(if (next > now) PitzkiScript.LEVEL_UP else PitzkiScript.TOP_LEVEL) {} } }, 900)
        }
        // The end: once more, or back to the start screen.
        strip.visibility = View.GONE
        pausedLayer.removeAllViews()
        pausedLayer.setOnClickListener(null)
        pausedLayer.addView(TextView(this).apply {
            text = L.t("עוד פעם ↻", "Again ↻"); textSize = 20f; typeface = DS.emphasis; gravity = Gravity.CENTER
            setTextColor(DS.onButton(this@PitzkiActivity))
            background = DS.buttonBackground(this@PitzkiActivity, primary = true)
            setPadding(ui.dp(26), ui.dp(14), ui.dp(26), ui.dp(14))
            setOnClickListener { startLesson() }
        })
        pausedLayer.addView(TextView(this).apply {
            text = L.t("סיום", "Done"); textSize = 20f; typeface = DS.emphasis; gravity = Gravity.CENTER
            setTextColor(DS.ink(this@PitzkiActivity))
            background = DS.buttonBackground(this@PitzkiActivity, primary = false)
            setPadding(ui.dp(26), ui.dp(14), ui.dp(26), ui.dp(14))
            layoutParams = LinearLayout.LayoutParams(wrap, wrap).apply { marginStart = ui.dp(16) }
            setOnClickListener { stopLesson(); setup() }
        })
        main.postDelayed({
            if (destroyed || !inLesson) return@postDelayed
            fun buttons() { paused = true; pausedLayer.visibility = View.VISIBLE }
            if (recordingWalk) endCard { endRecording(share = true); buttons() } else buttons()
        }, 2500)
    }
}

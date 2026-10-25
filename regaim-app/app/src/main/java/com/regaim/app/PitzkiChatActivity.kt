package com.regaim.app

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
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
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.Executors

/**
 * "Talk with Pitzki" (a pilot): Pitzki sits on his stump in the forest with his animal friends, asks the child a few
 * questions ([PitzkiChat]), listens, and answers in his own voice. While he talks his picture moves; while the child
 * talks he listens. About 3–4 minutes, started by the parent, who can end it any time and sees what was said at the
 * end. Nothing of the talk is kept.
 */
class PitzkiChatActivity : AppCompatActivity() {
    companion object {
        private const val MEMBER = "member"
        private const val LIMIT_MS = 3 * 60_000L
        /** The child's free turn: up to 2 minutes. */
        private const val FREE_MS = 2 * 60_000L
        /** The AI writes his answers to open questions (slower: its voice is made on the spot). Off for the pilot. */
        private const val AI_ANSWERS = false

        fun open(ctx: Context, member: String) {
            ctx.startActivity(Intent(ctx, PitzkiChatActivity::class.java).putExtra(MEMBER, member))
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val bg = Executors.newSingleThreadExecutor()
    private lateinit var voice: PitzkiVoice
    private lateinit var ears: PitzkiEars
    private var kid = PitzkiKid("", false)
    private var tries = 0
    private var started = 0L
    private var token = 0
    private var destroyed = false
    private var ended = false
    /** What was said, for the parent at the end (kept only on this screen). */
    private val said = ArrayList<Pair<Boolean, String>>()

    private lateinit var still: ImageView
    /** His two moving loops: talking, and listening (looking at the child). */
    private lateinit var talkLoop: Loop
    private lateinit var listenLoop: Loop
    private lateinit var subtitle: TextView
    private lateinit var status: TextView
    private lateinit var heardLine: TextView
    /** The picture answers (up to 3), shown while Pitzki listens. */
    private lateinit var choices: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        }
        voice = PitzkiVoice(this)
        ears = PitzkiEars(this)
        val member = intent.getStringExtra(MEMBER).orEmpty()
        kid = PitzkiStore.children(this).firstOrNull { it.memberId == member }?.kid ?: PitzkiKid("", false)
        PitzkiVoice.sayName = PitzkiStore.sayName(this, member).takeIf { it.isNotBlank() }?.let { kid.name to it }
        build()
        if (AI_ANSWERS && !AiGate.ready(this)) { // (no AI used: no AI consent needed)
            subtitle.text = L.t("כדי לדבר עם פיצקי צריך להפעיל את הבנת הרגעים (AI) בהגדרות.", "To talk with Pitzki, turn on moment understanding (AI) in Settings.")
            status.visibility = View.GONE
            return
        }
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            askingMic = true
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 51)
        }
        bg.execute { try { PitzkiStore.fetchShared(applicationContext) } catch (_: Throwable) {} }
        started = System.currentTimeMillis()
        // His voice for the beginning first (a few seconds the first time; at once later), then he says hello.
        // A real moment of this week (looked for meanwhile, on its own thread; none found: the talk goes on as always).
        Thread {
            moment = try {
                val said = Settings.prefs(this).getStringSet("pitzki_moment_said_$member", emptySet()).orEmpty()
                PitzkiMoments.line(MemoryStore.kept(applicationContext).map { m ->
                    PitzkiMoments.Seen(m.id, m.time, listOf(m.title, m.tags, m.description, m.category).joinToString(" "), m.people)
                }, kid, System.currentTimeMillis(), said)
            } catch (_: Throwable) { null }
        }.apply { isDaemon = true }.start()
        prepare { started = System.currentTimeMillis(); say(PitzkiChat.opening(kid), happy = true) { realMoment(member) { ask() } } }
    }

    /** (what Pitzki says, the moment's id), when one was found. */
    @Volatile private var moment: Pair<String, String>? = null

    /** Pitzki asks about the real moment, listens a little, answers warmly, and goes on. Nothing found: straight on. */
    private fun realMoment(member: String, then: () -> Unit) {
        val m = moment ?: return then()
        moment = null
        try {
            val p = Settings.prefs(this)
            val said = p.getStringSet("pitzki_moment_said_$member", emptySet()).orEmpty()
            p.edit().putStringSet("pitzki_moment_said_$member", (said + m.second).toList().takeLast(50).toSet()).apply()
        } catch (_: Exception) { }
        say(m.first) {
            if (destroyed) return@say
            ears.listen(6000, listOf(Beat.ANY)) { heard, said ->
                if (destroyed) return@listen
                say(if (said || heard.any { it.isNotBlank() }) PitzkiChat.momentReply(kid) else PitzkiChat.momentQuiet(kid)) { then() }
            }
        }
    }

    /** The microphone permission dialog is open (it pauses the screen; that's not leaving it). */
    private var askingMic = false

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        askingMic = false
    }

    override fun onPause() {
        super.onPause()
        // Leaving the screen ends the talk (the microphone never listens in the background).
        if (!isChangingConfigurations && !askingMic) finishTalk(silent = true)
    }

    override fun onDestroy() {
        destroyed = true
        token++
        main.removeCallbacksAndMessages(null)
        voice.shutdown()
        ears.stop()
        talkLoop.release(); listenLoop.release()
        bg.shutdownNow()
        ahead.shutdownNow()
        super.onDestroy()
    }

    // ---------- the screen ----------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun build() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.parseColor("#1E2A1A")) }
        still = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        root.addView(still, FrameLayout.LayoutParams(-1, -1))
        val listenView = android.view.TextureView(this).apply { alpha = 0f }
        root.addView(listenView, FrameLayout.LayoutParams(-1, -1))
        val talkView = android.view.TextureView(this).apply { alpha = 0f }
        root.addView(talkView, FrameLayout.LayoutParams(-1, -1))
        // Talking: the talk's loop, else his singing (lips moving). Listening: him looking at the child, if it was made.
        talkLoop = Loop(talkView) {
            PitzkiStore.sharedClip(this, PitzkiStore.CHAT).takeIf { it.exists() && it.length() > 0 }
                ?: PitzkiStore.talkTest(this).takeIf { it.exists() && it.length() > 0 }
        }
        listenLoop = Loop(listenView) { PitzkiStore.sharedClip(this, PitzkiStore.CHAT_LISTEN).takeIf { it.exists() && it.length() > 0 } }
        // His place: the chat scene (on his stump with the animals), else his song scene in the forest, else his picture.
        val pic = PitzkiStore.sharedScene(this, PitzkiStore.CHAT).takeIf { it.exists() } ?: PitzkiStore.sharedScene(this, "song").takeIf { it.exists() }
        bg.execute {
            val b = try { pic?.let { BitmapFactory.decodeFile(it.absolutePath) } ?: PitzkiStore.art(applicationContext, "forest") } catch (_: Throwable) { null }
            main.post { if (!destroyed && b != null) still.setImageBitmap(b) }
        }
        // The words (big, at the bottom, like the walks), and what Pitzki is doing now.
        subtitle = TextView(this).apply {
            textSize = 24f
            typeface = DS.heading
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setShadowLayer(10f, 0f, 2f, Color.BLACK)
            setPadding(dp(80), dp(10), dp(80), dp(18))
            background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(Color.argb(170, 0, 0, 0), Color.TRANSPARENT))
        }
        root.addView(subtitle, FrameLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        heardLine = TextView(this).apply {
            textSize = 16f
            setTextColor(Color.WHITE)
            setPadding(dp(16), dp(6), dp(16), dp(6))
            background = DS.rounded(Color.argb(150, 0, 0, 0), dp(18).toFloat())
            visibility = View.GONE
        }
        choices = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; visibility = View.GONE }
        root.addView(choices, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(92) })
        root.addView(heardLine, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(60) })
        status = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.WHITE)
            setPadding(dp(14), dp(6), dp(14), dp(6))
            background = DS.rounded(Color.argb(150, 0, 0, 0), dp(18).toFloat())
            text = L.t("💬 שיחה עם פיצקי", "💬 Talking with Pitzki")
        }
        root.addView(status, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(16) })
        // For the parent: end, and skip to the next question.
        val close = TextView(this).apply {
            text = "✕"; textSize = 26f; setTextColor(Color.WHITE); setPadding(dp(16), dp(8), dp(16), dp(8))
            contentDescription = L.t("לסיים את השיחה", "End the talk")
            setOnClickListener { finishTalk(silent = false) }
        }
        root.addView(close, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.RIGHT).apply { topMargin = dp(8); rightMargin = dp(8) })
        val skip = TextView(this).apply {
            text = L.t("שאלה אחרת ›", "Another question ›"); textSize = 15f; setTextColor(Color.WHITE)
            setPadding(dp(14), dp(6), dp(14), dp(6))
            background = DS.rounded(Color.argb(120, 0, 0, 0), dp(18).toFloat())
            setOnClickListener { if (!ended) { token++; voice.stop(); ears.stop(); next() } }
        }
        root.addView(skip, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.LEFT).apply { topMargin = dp(16); leftMargin = dp(16) })
        setContentView(root)
    }

    // ---------- Pitzki moving while he talks ----------

    /** One looping video, without sound, on its own view (shown only while it plays). */
    private inner class Loop(private val view: android.view.TextureView, private val file: () -> java.io.File?) {
        private var mp: android.media.MediaPlayer? = null
        private var sf: android.view.Surface? = null
        private var wanted = false
        val exists: Boolean get() = file() != null

        fun play() {
            wanted = true
            if (destroyed) return
            val existing = mp
            if (existing != null) { try { existing.start() } catch (_: Exception) {}; view.animate().alpha(1f).setDuration(250).start(); return }
            val f = file() ?: return
            val st = view.surfaceTexture ?: run {
                view.surfaceTextureListener = object : android.view.TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(t: android.graphics.SurfaceTexture, w: Int, h: Int) { if (wanted) play() }
                    override fun onSurfaceTextureSizeChanged(t: android.graphics.SurfaceTexture, w: Int, h: Int) {}
                    override fun onSurfaceTextureDestroyed(t: android.graphics.SurfaceTexture) = true
                    override fun onSurfaceTextureUpdated(t: android.graphics.SurfaceTexture) {}
                }
                return
            }
            try {
                val p = android.media.MediaPlayer()
                val s = android.view.Surface(st)
                p.setDataSource(f.absolutePath)
                p.setSurface(s)
                p.setVolume(0f, 0f)
                p.isLooping = true
                p.setOnInfoListener { _, what, _ ->
                    if (what == android.media.MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START && wanted) view.animate().alpha(1f).setDuration(250).start()
                    false
                }
                p.prepare()
                fill(view, p.videoWidth, p.videoHeight)
                p.start()
                mp = p; sf = s
            } catch (_: Exception) { release() }
        }

        /** Stops where it is; [hide]: the other loop (or the picture) is shown instead. */
        fun pause(hide: Boolean) {
            wanted = false
            try { mp?.takeIf { it.isPlaying }?.pause() } catch (_: Exception) {}
            if (hide) view.animate().alpha(0f).setDuration(250).start()
        }

        fun release() {
            wanted = false
            try { mp?.release() } catch (_: Exception) {}
            try { sf?.release() } catch (_: Exception) {}
            mp = null; sf = null
        }
    }

    /**
     * While he talks, his talking loop; while the child talks, him listening and looking at the child (when that loop
     * was made; otherwise his talking loop stops where it is).
     */
    private fun talking(on: Boolean) {
        if (destroyed) return
        if (on) { listenLoop.pause(hide = true); talkLoop.play(); return }
        if (listenLoop.exists) { talkLoop.pause(hide = true); listenLoop.play() } else talkLoop.pause(hide = false)
    }

    private fun fill(view: android.view.TextureView, vw: Int, vh: Int) {
        if (vw <= 0 || vh <= 0 || view.width == 0 || view.height == 0) return
        val sx = view.width.toFloat() / vw
        val sy = view.height.toFloat() / vh
        val k = maxOf(sx, sy)
        view.setTransform(android.graphics.Matrix().apply {
            setScale(k / sx, k / sy, view.width / 2f, view.height / 2f)
            // The bottom strip (a video model sometimes writes captions there) stays off screen.
            postScale(1.12f, 1.12f, view.width / 2f, 0f)
        })
    }

    // ---------- the talk ----------

    /** What Pitzki is doing: shown on screen, and his picture moves only while he talks. */
    private enum class Mode { SPEAKING, LISTENING, THINKING, HAPPY }

    private fun mode(m: Mode) {
        if (destroyed) return
        status.text = when (m) {
            Mode.SPEAKING -> L.t("💬 פיצקי מדבר", "💬 Pitzki is talking")
            Mode.LISTENING -> L.t("🎤 פיצקי מקשיב…", "🎤 Pitzki is listening…")
            Mode.THINKING -> L.t("🤔 פיצקי חושב…", "🤔 Pitzki is thinking…")
            Mode.HAPPY -> L.t("✨ כל הכבוד!", "✨ Well done!")
        }
        status.animate().cancel()
        status.scaleX = 1f; status.scaleY = 1f
        if (m == Mode.LISTENING) pulse(status)
        talking(m == Mode.SPEAKING || m == Mode.HAPPY)
        if (m == Mode.HAPPY) burst()
    }

    private fun pulse(v: View) {
        v.animate().scaleX(1.08f).scaleY(1.08f).setDuration(500).withEndAction {
            v.animate().scaleX(1f).scaleY(1f).setDuration(500).withEndAction { if (status.text.startsWith("🎤") && !destroyed) pulse(v) }.start()
        }.start()
    }

    /** A few stars around Pitzki for a right answer. */
    private fun burst() {
        val root = window.decorView.findViewById<ViewGroup>(android.R.id.content)
        repeat(6) { i ->
            val star = TextView(this).apply { text = if (i % 2 == 0) "⭐" else "✨"; textSize = 34f }
            val w = resources.displayMetrics.widthPixels
            val h = resources.displayMetrics.heightPixels
            root.addView(star, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            star.x = w * (0.3f + 0.4f * random.nextFloat()); star.y = h * 0.55f
            star.animate().translationYBy(-h * (0.25f + 0.2f * random.nextFloat())).alpha(0f).setDuration(1300)
                .withEndAction { root.removeView(star) }.start()
        }
    }

    private val random = java.util.Random()
    private var current: PitzkiChat.Q? = null
    private var asked = 0
    private var right = 0
    private var aiCalls = 0
    private val usedTopics = HashSet<String>()

    /**
     * Pitzki says [text] in his own voice (made now if needed; the phone's voice if that fails). The microphone is
     * never open while he talks: it opens only after he finished.
     */
    private fun say(text: String, happy: Boolean = false, then: () -> Unit) {
        if (destroyed) return
        val my = ++token
        ears.stop()
        hideChoices()
        subtitle.text = text
        said.add(false to text)
        mode(Mode.THINKING)
        bg.execute {
            try { PitzkiStore.voiceFor(applicationContext, text) } catch (_: Exception) { /* the phone's voice */ }
            main.post {
                if (my != token || destroyed) return@post
                mode(if (happy) Mode.HAPPY else Mode.SPEAKING)
                voice.say(text) {
                    talking(false)
                    if (my == token && !destroyed) main.postDelayed({ if (my == token) then() }, 400)
                }
            }
        }
    }

    /** The talk's questions (chosen at the start, so his voice for them is made while he talks). */
    private var plan: List<Pair<PitzkiChat.Q, PitzkiChat.Q>> = emptyList()
    /** Three lines made at the same time (each takes a few seconds at the voice service, which allows a few at once). */
    private val ahead = Executors.newFixedThreadPool(3)

    /**
     * His voice for the whole talk, made in the order it's needed, three at a time. The talk starts once the
     * beginning is ready (the hello and the first questions with their answers): then he answers at once.
     */
    private fun prepare(ready: () -> Unit) {
        val recent = Settings.prefs(this).getStringSet("pitzki_chat_recent", emptySet()).orEmpty()
        plan = PitzkiChat.plan(recent, random)
        val first = (listOf(PitzkiChat.opening(kid), PitzkiChat.notHeard(kid), PitzkiChat.moveOn(kid), PitzkiChat.WELL_DONE) +
            plan.take(2).flatMap { PitzkiChat.lines(kid, it.first) }).distinct()
        val rest = (plan.drop(2).flatMap { PitzkiChat.lines(kid, it.first) } + plan.flatMap { PitzkiChat.lines(kid, it.second) } +
            listOf(PitzkiChat.closing(kid))).distinct() - first.toSet()
        val app = applicationContext
        val missing = first.filter { !PitzkiStore.voiceReady(app, it) }
        val left = java.util.concurrent.atomic.AtomicInteger(missing.size)
        if (missing.isEmpty()) ready()
        else {
            status.text = L.t("🌳 פיצקי מתכונן… 0/${missing.size}", "🌳 Pitzki is getting ready… 0/${missing.size}")
            for (l in missing) ahead.execute {
                if (!destroyed) try { PitzkiStore.voiceFor(app, l) } catch (_: Exception) { }
                val n = left.decrementAndGet()
                main.post {
                    if (destroyed) return@post
                    if (n > 0) status.text = L.t("🌳 פיצקי מתכונן… ${missing.size - n}/${missing.size}", "🌳 Pitzki is getting ready… ${missing.size - n}/${missing.size}")
                    else ready()
                }
            }
        }
        for (l in rest) ahead.execute { if (!destroyed && !PitzkiStore.voiceReady(app, l)) try { PitzkiStore.voiceFor(app, l) } catch (_: Exception) { } }
    }

    private fun ask() {
        if (ended || destroyed) return
        val recent = Settings.prefs(this).getStringSet("pitzki_chat_recent", emptySet()).orEmpty()
        val slot = plan.getOrNull(asked) ?: run { goodbye(); return }
        // Harder questions after a few right answers.
        val q = if (right >= 2) slot.second else slot.first
        current = q
        usedTopics.add(q.topic)
        asked++
        // Remembered (only which questions) so the next talks ask other ones first.
        val keep = (listOf(q.id) + recent.toList()).take(24).toSet()
        Settings.prefs(this).edit().putStringSet("pitzki_chat_recent", keep).apply()
        tries = 0
        say(PitzkiChat.form(kid, q.ask)) { listen(q) }
    }

    private fun canHear() = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED && ears.available()

    private fun listen(q: PitzkiChat.Q) {
        if (ended || destroyed) return
        val my = ++token
        showChoices(q)
        mode(Mode.LISTENING)
        if (!canHear()) {
            // No microphone: the pictures, and a few seconds; then Pitzki goes on kindly.
            main.postDelayed({ if (my == token) heard(q, emptyList()) }, if (q.choices.isEmpty()) 4000 else 9000)
            return
        }
        ears.listen(8000) { words, _ ->
            if (my != token || destroyed || ended) return@listen
            heard(q, words)
        }
    }

    /** A picture was tapped: that's the answer. */
    private fun tapped(q: PitzkiChat.Q, i: Int) {
        if (ended || destroyed || current !== q) return
        token++
        ears.stop()
        said.add(true to "👆 " + q.choices[i].label)
        answered(q, i)
    }

    private fun heard(q: PitzkiChat.Q, words: List<String>) {
        val first = words.firstOrNull { it.isNotBlank() }.orEmpty().trim()
        if (first.isNotEmpty()) {
            said.add(true to first)
            heardLine.text = L.t("👂 שמעתי: ", "👂 I heard: ") + first.take(40)
            heardLine.visibility = View.VISIBLE
            main.postDelayed({ if (!destroyed) heardLine.visibility = View.GONE }, 2500)
        }
        val picked = PitzkiChat.match(q, words)
        when {
            picked >= 0 -> answered(q, picked)
            // Something was said to "say with me" / "show me": that's taking part.
            first.isNotEmpty() && q.choices.isEmpty() && !q.open -> answered(q, -1)
            first.isNotEmpty() && q.open -> openAnswer(q, first)
            // Nothing (or not understood): never guessed. Once more (with the pictures), then something else.
            tries == 0 -> { tries++; say(PitzkiChat.notHeard(kid)) { listen(q) } }
            else -> say(PitzkiChat.moveOn(kid)) { next() }
        }
    }

    private fun answered(q: PitzkiChat.Q, picked: Int) {
        val good = q.right < 0 || picked == q.right
        if (q.right >= 0 && picked == q.right) right++
        say(PitzkiChat.replyTo(kid, q, picked), happy = good && !q.plain) { follow(q, picked) }
    }

    /** An open question ("what do you like to eat?"): a natural reaction by the AI (a few per talk), else his own. */
    private fun openAnswer(q: PitzkiChat.Q, words: String) {
        // Quick first: his prepared answer, said at once. (An AI answer needs its voice made on the spot, which is
        // several seconds of silence for a small child; it's kept for later, behind AI_ANSWERS.)
        if (!AI_ANSWERS) { say(PitzkiChat.replyTo(kid, q, -1), happy = true) { next() }; return }
        val my = ++token
        mode(Mode.THINKING)
        if (aiCalls >= 4) { say(PitzkiChat.replyTo(kid, q, -1)) { next() }; return }
        aiCalls++
        bg.execute {
            // Up to ~8 seconds; an AI that's slow or failing never leaves Pitzki "thinking".
            val f = java.util.concurrent.Executors.newSingleThreadExecutor().submit<String> {
                ClaudeClient.pitzkiReply(applicationContext, PitzkiChat.system(kid), PitzkiChat.prompt(kid, q, words))
            }
            val raw = try { f.get(8, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Exception) { f.cancel(true); "" }
            val reply = PitzkiChat.clean(raw)
            main.post {
                if (my != token || destroyed || ended) return@post
                say(reply ?: PitzkiChat.replyTo(kid, q, -1), happy = true) { next() }
            }
        }
    }

    /** One small thing more, together ("say with me: yellow!"): any answer is taking part. */
    private fun follow(q: PitzkiChat.Q, picked: Int) {
        val en = q.choices.getOrNull(picked)?.en.orEmpty()
        val good = q.right < 0 || picked == q.right
        if (q.follow.isEmpty() || !good || (q.follow.contains("{en}") && en.isEmpty())) { next(); return }
        say(PitzkiChat.form(kid, q.follow, en)) {
            if (ended || destroyed) return@say
            val my = ++token
            mode(Mode.LISTENING)
            if (!canHear()) { main.postDelayed({ if (my == token) next() }, 3000); return@say }
            ears.listen(6000) { words, _ ->
                if (my != token || destroyed || ended) return@listen
                val w = words.firstOrNull { it.isNotBlank() }.orEmpty()
                if (w.isNotEmpty()) { said.add(true to w); say(PitzkiChat.WELL_DONE, happy = true) { next() } } else next()
            }
        }
    }

    private fun showChoices(q: PitzkiChat.Q) {
        choices.removeAllViews()
        if (q.choices.isEmpty()) { choices.visibility = View.GONE; return }
        for ((i, c) in q.choices.take(3).withIndex()) {
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(6), dp(16), dp(8))
                background = DS.rounded(Color.argb(235, 255, 252, 245), dp(22).toFloat())
                elevation = dp(6).toFloat()
                contentDescription = c.label
                setOnClickListener { tapped(q, i) }
            }
            cell.addView(TextView(this).apply { text = c.emoji; textSize = 34f; gravity = Gravity.CENTER })
            cell.addView(TextView(this).apply { text = c.label; textSize = 15f; typeface = DS.heading; gravity = Gravity.CENTER; setTextColor(Color.parseColor("#3A2E25")) })
            choices.addView(cell, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8); marginEnd = dp(8) })
        }
        choices.visibility = View.VISIBLE
    }

    private fun hideChoices() { if (::choices.isInitialized) choices.visibility = View.GONE }

    private fun next() {
        if (ended || destroyed) return
        // About 3 minutes, or 6 questions: then the child's turn to talk, then goodbye (the right length at this age).
        if (asked >= 6 || System.currentTimeMillis() - started > LIMIT_MS) freeTalk() else ask()
    }

    // ---------- the child's turn: tells Pitzki things or asks him (no AI: his replies are written ahead) ----------

    private var freeStarted = 0L
    private var freeTurns = 0
    private var quietTurns = 0
    private val freeUsed = HashMap<String, Int>()

    private fun freeTalk() {
        if (ended || destroyed) return
        if (freeStarted > 0L) { goodbye(); return }
        freeStarted = System.currentTimeMillis()
        hideChoices()
        say(PitzkiFreeTalk.invite(kid), happy = true) { freeListen() }
    }

    private fun freeListen() {
        if (ended || destroyed) return
        // Up to 2 minutes or 8 turns; quiet twice in a row: goodbye.
        if (freeTurns >= 8 || System.currentTimeMillis() - freeStarted > FREE_MS || quietTurns >= 2) { goodbye(); return }
        val my = ++token
        mode(Mode.LISTENING)
        if (!canHear()) { goodbye(); return }
        ears.listen(9000) { words, _ ->
            if (my != token || destroyed || ended) return@listen
            val first = words.firstOrNull { it.isNotBlank() }.orEmpty().trim()
            if (first.isEmpty()) {
                quietTurns++
                if (quietTurns >= 2) goodbye() else say(PitzkiFreeTalk.nudge(kid)) { freeListen() }
                return@listen
            }
            quietTurns = 0
            freeTurns++
            // A rude word isn't shown or kept, only that one was said.
            val r = PitzkiFreeTalk.reply(words, kid, freeUsed)
            said.add(true to if (r.topic == "rude") L.t("(מילה לא יפה)", "(not a nice word)") else first)
            heardLine.text = L.t("👂 שמעתי: ", "👂 I heard: ") + (if (r.topic == "rude") "…" else first.take(40))
            heardLine.visibility = View.VISIBLE
            main.postDelayed({ if (!destroyed) heardLine.visibility = View.GONE }, 2500)
            freeUsed[r.topic] = (freeUsed[r.topic] ?: 0) + 1
            say(r.text, happy = r.topic != "rude") { freeListen() }
        }
    }

    private fun goodbye() {
        if (ended) return
        ended = true
        ears.stop()
        say(PitzkiChat.closing(kid), happy = true) { showSummary() }
    }

    private fun finishTalk(silent: Boolean) {
        if (destroyed) return
        token++
        ended = true
        voice.stop()
        ears.stop()
        talking(false)
        if (silent) finish() else showSummary()
    }

    /** For the parent: what Pitzki and the child said (only on this screen; nothing is kept). */
    private fun showSummary() {
        if (destroyed) return
        ears.stop()
        talking(false)
        status.text = L.t("השיחה הסתיימה", "The talk is over")
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
            background = DS.rounded(DS.bg(this@PitzkiChatActivity), dp(20).toFloat())
        }
        box.addView(TextView(this).apply {
            text = L.t("מה דיברנו", "What we talked about"); textSize = 20f; typeface = DS.heading; setTextColor(DS.ink(this@PitzkiChatActivity))
        })
        for ((child, line) in said) box.addView(TextView(this).apply {
            text = (if (child) "👦 " + kid.name.ifBlank { L.t("הילד", "Child") } + ": " else "🧝 " + L.t("פיצקי", "Pitzki") + ": ") + line
            textSize = 15f
            setTextColor(DS.ink(this@PitzkiChatActivity))
            setPadding(0, dp(6), 0, 0)
        })
        box.addView(TextView(this).apply {
            text = L.t("השיחה לא נשמרת. מה שהילד אמר עבר רק דרך זיהוי הדיבור של הטלפון; פיצקי עונה מתוך תשובות שנכתבו מראש, בלי AI.",
                "The talk isn't saved. What the child said went only through the phone's speech recognition; Pitzki answers from replies written ahead, with no AI.")
            textSize = 12f; setTextColor(DS.inkSoft(this@PitzkiChatActivity)); setPadding(0, dp(10), 0, 0)
        })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(0, dp(12), 0, 0) }
        row.addView(TextView(this).apply {
            text = L.t("עוד שיחה", "Talk again"); textSize = 16f; typeface = DS.heading; setTextColor(Color.WHITE)
            setPadding(dp(20), dp(10), dp(20), dp(10)); background = DS.rounded(DS.button(this@PitzkiChatActivity), dp(22).toFloat())
            setOnClickListener { recreate() }
        })
        row.addView(TextView(this).apply {
            text = L.t("סיום", "Done"); textSize = 16f; setTextColor(DS.ink(this@PitzkiChatActivity)); setPadding(dp(20), dp(10), dp(20), dp(10))
            setOnClickListener { finish() }
        })
        box.addView(row)
        val scroll = ScrollView(this).apply { addView(box) }
        val layer = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(140, 0, 0, 0))
            addView(scroll, FrameLayout.LayoutParams(dp(520).coerceAtMost(resources.displayMetrics.widthPixels - dp(32)), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply { topMargin = dp(16); bottomMargin = dp(16) })
        }
        (window.decorView.findViewById<ViewGroup>(android.R.id.content)).addView(layer, FrameLayout.LayoutParams(-1, -1))
    }
}

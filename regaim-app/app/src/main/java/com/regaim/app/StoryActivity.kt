package com.regaim.app

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.DateUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.Executors

/**
 * A memory story, like a photo-app "memory": full screen, moment after moment,
 * each showing its highlight seconds, with titles and gentle background music.
 * Tap left/right to move, hold to pause.
 */
class StoryActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_KIND = "kind"
        private const val EXTRA_DAY = "day"
        private const val SLIDE_MS = 5000L
        private const val INTRO_MS = 2800L

        fun week(ctx: Context) = ctx.startActivity(Intent(ctx, StoryActivity::class.java).putExtra(EXTRA_KIND, "week"))
        fun day(ctx: Context, dayKey: String) =
            ctx.startActivity(Intent(ctx, StoryActivity::class.java).putExtra(EXTRA_KIND, "day").putExtra(EXTRA_DAY, dayKey))

        /** A story of any set of moments (a topic, an album...). */
        fun of(ctx: Context, title: String, ids: List<String>) = ctx.startActivity(
            Intent(ctx, StoryActivity::class.java).putExtra(EXTRA_KIND, "ids").putExtra("title", title)
                .putExtra("ids", ids.toTypedArray())
        )
    }

    private val ui = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var moments: List<Memory>
    private lateinit var title: String
    private lateinit var subtitle: String
    private lateinit var video: VideoView
    private lateinit var poster: ImageView
    private lateinit var texts: LinearLayout
    private lateinit var bars: LinearLayout
    private val fills = mutableListOf<View>()
    private var slide = -1          // -1 = intro, 0..n-1 = moments, n = the end
    private var startedAt = 0L
    private var pausedAt = 0L
    private var paused = false
    /** Paused with the pause button (stays paused until play), apart from a finger held on the screen. */
    private var userPaused = false
    private var muted = false
    private lateinit var playBtn: TextView
    private lateinit var muteBtn: TextView
    private val a11y by lazy { getSystemService(android.view.accessibility.AccessibilityManager::class.java) }
    /** A screen reader is on: slides wait for "next" instead of moving on by themselves. */
    private fun screenReader() = a11y?.isTouchExplorationEnabled == true
    private var mood = Music.Mood.WARM
    private lateinit var moodBtn: TextView
    @Volatile private var destroyed = false
    private val images = Executors.newFixedThreadPool(2)

    private val tick = object : Runnable {
        override fun run() {
            if (!paused) {
                moments.getOrNull(slide)?.previewWindow?.let { window ->
                    if (::video.isInitialized && video.isPlaying && video.currentPosition >= window.endMs) video.pause()
                }
                val len = if (slide < 0) INTRO_MS else SLIDE_MS
                val p = ((System.currentTimeMillis() - startedAt).toFloat() / len).coerceIn(0f, 1f)
                fills.getOrNull(slide + 1)?.scaleX = p
                if (p >= 1f && slide < moments.size && !screenReader()) go(slide + 1)
            }
            ui.postDelayed(this, 50)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = Color.BLACK
        window.decorView.systemUiVisibility = 0
        val all = MemoryStore.kept(this)
        if (intent.getStringExtra(EXTRA_KIND) == "ids") {
            val ids = intent.getStringArrayExtra("ids")?.toSet() ?: emptySet()
            moments = all.filter { it.id in ids }.sortedBy { it.time }
            title = intent.getStringExtra("title") ?: ""
            subtitle = moments.firstOrNull()?.let { if (moments.size > 1) "${Fmt.day(it.time)} – ${Fmt.day(moments.last().time)}" else Fmt.day(it.time) } ?: ""
        } else if (intent.getStringExtra(EXTRA_KIND) == "day") {
            val day = intent.getStringExtra(EXTRA_DAY) ?: ""
            moments = ReelMaker.best(all.filter { it.dayKey == day }).take(10).sortedBy { it.time }
            title = moments.firstOrNull()?.let { if (DateUtils.isToday(it.time)) L.t("היום שלנו", "Our day") else Fmt.fullDate(it.time) } ?: ""
            subtitle = Profile.homeTitle(this)
        } else {
            moments = ReelMaker.best(ReelMaker.weekMoments(this)).take(10).sortedBy { it.time }
            title = L.t("השבוע שלנו", "Our week")
            subtitle = moments.firstOrNull()?.let { "${Fmt.day(it.time)} – ${Fmt.day(moments.last().time)}" } ?: ""
        }
        if (moments.isEmpty()) {
            Toast.makeText(this, L.t("עוד אין רגעים לסיפור", "No moments for a story yet"), Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        mood = if (moments.count { it.category == "צחוק" || it.category == "משחק" } * 2 >= moments.size) Music.Mood.HAPPY
        else if (moments.count { it.category == "שינה" } * 2 >= moments.size) Music.Mood.CALM else Music.Mood.WARM
        buildViews()
        go(-1)
        ui.post(tick)
        startMusic()
    }

    override fun onPause() {
        super.onPause()
        if (::video.isInitialized) video.pause()
        Music.pause(); LicensedMusic.pause()
        paused = true
        pausedAt = System.currentTimeMillis()
    }

    override fun onResume() {
        super.onResume()
        if (paused && !userPaused && ::video.isInitialized) {
            startedAt += System.currentTimeMillis() - pausedAt
            paused = false
            if (!muted) { Music.resume(); LicensedMusic.resume() }
            if (slide in moments.indices && moments[slide].isVideo && moments[slide].previewWindow?.isFallback == false) video.start()
        }
    }

    override fun onDestroy() {
        destroyed = true
        ui.removeCallbacksAndMessages(null)
        worker.shutdownNow()
        images.shutdownNow()
        Music.stop(); LicensedMusic.stop()
        super.onDestroy()
    }

    private fun startMusic() {
        val piece = LicensedMusic.forMood(mood)
        moodBtn.text = piece?.let { "♪ " + it.title } ?: mood.title
        moodBtn.contentDescription = L.t("מוזיקה: ", "Music: ") + (piece?.title ?: mood.title) + L.t(". לחצו להחלפה", ". Tap to change")
        if (muted) return
        worker.execute {
            // A real recorded piece when one fits the mood; the music composed on the phone otherwise.
            try {
                Music.stop(); LicensedMusic.stop()
                if (destroyed || isFinishing) return@execute
                if (piece == null || !LicensedMusic.play(applicationContext, piece)) Music.play(mood) { !destroyed && !isFinishing }
            } catch (_: Exception) {}
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ---------- views ----------

    private fun buildViews() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        video = VideoView(this).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
        }
        root.addView(video)
        poster = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        root.addView(poster)
        root.addView(View(this).apply {
            setBackgroundResource(R.drawable.cover_scrim)
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(320), Gravity.BOTTOM)
        })
        root.addView(View(this).apply {
            setBackgroundResource(R.drawable.scrim_top)
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(120), Gravity.TOP)
        })
        texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            setPadding(dp(24), 0, dp(24), dp(96))
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
        }
        root.addView(texts)

        // Progress bars: one per slide (intro + moments).
        bars = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            setPadding(dp(10), dp(12), dp(10), 0)
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP)
        }
        repeat(moments.size + 1) {
            val track = FrameLayout(this).apply {
                background = GradientDrawable().apply { cornerRadius = dp(2).toFloat(); setColor(0x55FFFFFF) }
                layoutParams = LinearLayout.LayoutParams(0, dp(3), 1f).apply { marginStart = dp(2); marginEnd = dp(2) }
            }
            val fill = View(this).apply {
                background = GradientDrawable().apply { cornerRadius = dp(2).toFloat(); setColor(Color.WHITE) }
                layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                scaleX = 0f
                // Fills in the reading direction: right-to-left in Hebrew, left-to-right in English.
                addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> v.pivotX = if (L.isEn) 0f else v.width.toFloat() }
            }
            track.addView(fill)
            fills.add(fill)
            bars.addView(track)
        }
        root.addView(bars)

        // Top row: close and music mood.
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(22), dp(8), 0)
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP)
        }
        top.addView(TextView(this).apply {
            text = "✕"
            textSize = 20f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            minWidth = dp(48); minHeight = dp(48)
            contentDescription = L.t("סגירה", "Close")
            A11y.button(this)
            setOnClickListener { finish() }
        })
        top.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) })
        moodBtn = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(0x40FFFFFF) }
            setPadding(dp(12), dp(6), dp(12), dp(6))
            minHeight = dp(48); gravity = Gravity.CENTER
            A11y.button(this)
            setOnClickListener {
                mood = Music.Mood.values()[(mood.ordinal + 1) % Music.Mood.values().size]
                startMusic()
            }
        }
        top.addView(moodBtn)
        root.addView(top)

        // Bottom: previous / pause / next and music on/off, for everyone (and the only way with a screen reader).
        val ctrl = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            setPadding(dp(8), 0, dp(8), dp(14))
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
        }
        fun control(label: String, said: String, onClick: () -> Unit) = TextView(this).apply {
            text = label; textSize = 18f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            minWidth = dp(56); minHeight = dp(56)
            background = GradientDrawable().apply { cornerRadius = dp(28).toFloat(); setColor(0x66000000) }
            contentDescription = said
            A11y.button(this)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8); marginEnd = dp(8) }
            setOnClickListener { onClick() }
        }
        // In Hebrew "previous" is on the right and points right (the reading direction).
        ctrl.addView(control(if (L.isEn) "‹" else "›", L.t("הקודם", "Previous")) { go((slide - 1).coerceAtLeast(-1)) })
        playBtn = control("⏸", L.t("השהיה", "Pause")) { setUserPaused(!userPaused) }
        ctrl.addView(playBtn)
        ctrl.addView(control(if (L.isEn) "›" else "‹", L.t("הבא", "Next")) { go((slide + 1).coerceAtMost(moments.size)) })
        muteBtn = control("🔊", L.t("השתקת המוזיקה", "Mute the music")) { setMuted(!muted) }
        ctrl.addView(muteBtn)
        root.addView(ctrl)

        // Tap left / right, hold to pause.
        root.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { pause(true); true }
                MotionEvent.ACTION_UP -> {
                    val held = e.eventTime - e.downTime > 350
                    pause(false)
                    if (!held) {
                        // Tap the "forward" side (left in Hebrew, right in English) for the next slide.
                        val forward = if (L.isEn) e.x > v.width * 2 / 3f else e.x < v.width / 3f
                        val back = if (L.isEn) e.x < v.width / 3f else e.x > v.width * 2 / 3f
                        if (forward) go((slide + 1).coerceAtMost(moments.size))
                        else if (back) go((slide - 1).coerceAtLeast(-1))
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> { pause(false); true }
                else -> true
            }
        }
        setContentView(root)
    }

    /** Shows a moment's cover in the poster without touching its fade (the video may be fading it out). */
    private fun cover(m: Memory) {
        poster.setImageDrawable(null)
        val want = m.id
        poster.tag = want
        // The card cover first (fast), then the photo or video frame at the screen's sharpness.
        val screen = maxOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels).coerceIn(1080, 2048)
        images.execute {
            val bmp = try { Covers.get(applicationContext, m) } catch (_: Throwable) { null }
            runOnUiThread { if (!destroyed && bmp != null && poster.tag == want) poster.setImageBitmap(bmp) }
            if (destroyed || poster.tag != want) return@execute
            val sharp = try { Covers.sharp(applicationContext, m, screen) } catch (_: Throwable) { null }
            runOnUiThread { if (!destroyed && sharp != null && sharp !== bmp && poster.tag == want) poster.setImageBitmap(sharp) }
        }
    }

    private fun setUserPaused(on: Boolean) {
        userPaused = on
        if (on) pause(true) else { paused = true; pause(false) }
        playBtn.text = if (on) "▶" else "⏸"
        playBtn.contentDescription = if (on) L.t("המשך", "Play") else L.t("השהיה", "Pause")
        playBtn.announceForAccessibility(if (on) L.t("מושהה", "Paused") else L.t("ממשיך", "Playing"))
    }

    private fun setMuted(on: Boolean) {
        muted = on
        if (on) { Music.pause(); LicensedMusic.pause() } else startMusic()
        muteBtn.text = if (on) "🔇" else "🔊"
        muteBtn.contentDescription = if (on) L.t("הפעלת המוזיקה", "Turn the music on") else L.t("השתקת המוזיקה", "Mute the music")
    }

    private fun pause(on: Boolean) {
        if (!on && userPaused) return
        if (on == paused) return
        paused = on
        if (on) {
            pausedAt = System.currentTimeMillis()
            if (video.isPlaying) video.pause()
        } else {
            startedAt += System.currentTimeMillis() - pausedAt
            if (slide in moments.indices && moments[slide].isVideo && moments[slide].previewWindow?.isFallback == false) video.start()
        }
    }

    // ---------- slides ----------

    private fun go(index: Int) {
        slide = index
        startedAt = System.currentTimeMillis()
        fills.forEachIndexed { i, f -> f.scaleX = if (i < index + 1) 1f else 0f }
        texts.removeAllViews()
        when {
            index < 0 -> intro()
            index < moments.size -> moment(moments[index])
            else -> ending()
        }
        // Said to a screen reader: where we are and what's on it.
        val said = when {
            index < 0 -> "$title. " + L.t("${moments.size} רגעים", "${moments.size} moments")
            index < moments.size -> L.t("${index + 1} מתוך ${moments.size}. ", "${index + 1} of ${moments.size}. ") + Fmt.title(moments[index])
            else -> L.t("הסוף", "The end")
        }
        texts.announceForAccessibility(said)
        texts.alpha = 0f
        texts.translationY = dp(16).toFloat()
        texts.animate().alpha(1f).translationY(0f).setDuration(450).start()
    }

    private fun bigText(s: String, size: Float, bold: Boolean = true, alpha: Int = 0xFF) = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(Color.argb(alpha, 255, 255, 255))
        if (bold) setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD))
        setShadowLayer(8f, 0f, 2f, 0x66000000)
    }

    /** Slow zoom on the picture (the "Ken Burns" look). */
    private fun kenBurns(duration: Long) {
        poster.animate().cancel()
        poster.scaleX = 1f; poster.scaleY = 1f
        poster.animate().scaleX(1.12f).scaleY(1.12f).setDuration(duration).setInterpolator(android.view.animation.LinearInterpolator()).start()
    }

    private fun intro() {
        video.stopPlayback()
        video.visibility = View.INVISIBLE
        poster.visibility = View.VISIBLE
        poster.alpha = 1f
        cover(moments.first())
        kenBurns(INTRO_MS + 1500)
        texts.addView(bigText(subtitle, 15f, bold = false, alpha = 0xDD))
        texts.addView(bigText(title, 40f))
        texts.addView(bigText(L.t("${moments.size} רגעים", "${moments.size} moments"), 15f, bold = false, alpha = 0xCC))
    }

    private fun moment(m: Memory) {
        video.stopPlayback()
        video.setOnPreparedListener(null)
        video.visibility = View.INVISIBLE
        // The cover shows at once (with a slow zoom); the video takes over from the highlight when ready.
        poster.visibility = View.VISIBLE
        poster.alpha = 1f
        cover(m)
        kenBurns(SLIDE_MS + 1000)
        val window = m.previewWindow
        if (m.isVideo && window != null && !window.isFallback) {
            video.visibility = View.VISIBLE
            val uri = if (m.uri.startsWith("content://")) Uri.parse(m.uri) else Uri.parse("file://" + m.uri)
            video.setOnPreparedListener { mp ->
                // Fill the screen like a story (no black bars).
                (video.parent as? View)?.let { VideoCover.apply(video, it, mp.videoWidth, mp.videoHeight) }
                try { mp.setVolume(0.15f, 0.15f) } catch (_: Exception) {}
                mp.setOnInfoListener { _, what, _ ->
                    if (what == android.media.MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                        poster.animate().alpha(0f).setDuration(350).start()
                    }
                    false
                }
                video.seekTo(window.startMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                if (!paused) video.start()
            }
            video.setOnErrorListener { _, _, _ -> true } // keep the picture if the clip is gone
            video.setVideoURI(uri)
        }

        texts.addView(bigText(Fmt.title(m), 28f))
        if (m.isVideo && window != null) texts.addView(bigText(window.label, 12f, bold = false, alpha = 0xCC))
        val date = if (m.timeSource == "imported") L.t("נוסף: ${Fmt.day(m.time)}", "Added ${Fmt.day(m.time)}") else Fmt.day(m.time)
        val sub = (listOf(date) + Fmt.names(m) + listOf(Fmt.place(m))).filter { it.isNotEmpty() }.joinToString(" · ")
        texts.addView(bigText(sub, 14f, bold = false, alpha = 0xDD))
        if (m.description.isNotEmpty()) texts.addView(bigText(m.description, 15f, bold = false, alpha = 0xEE).apply {
            maxLines = 3
            setPadding(0, dp(8), 0, 0)
        })
    }

    private fun ending() {
        video.stopPlayback()
        video.visibility = View.INVISIBLE
        poster.visibility = View.VISIBLE
        poster.alpha = 0.45f
        cover(moments.last())
        texts.addView(bigText("❤️", 40f))
        texts.addView(bigText(Profile.homeTitle(this), 26f))
        texts.addView(bigText(title, 16f, bold = false, alpha = 0xDD))
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(20), 0, 0)
        }
        fun pill(s: String, onClick: () -> Unit) = TextView(this).apply {
            text = s
            textSize = 15f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            background = GradientDrawable().apply { cornerRadius = dp(22).toFloat(); setColor(0x40FFFFFF) }
            setPadding(dp(18), dp(10), dp(18), dp(10))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(10) }
            setOnClickListener { onClick() }
        }
        row.addView(pill(L.t("↺ שוב", "↺ Replay")) { go(-1) })
        row.addView(pill(L.t("סגור", "Close")) { finish() })
        texts.addView(row)
    }
}

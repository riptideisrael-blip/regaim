package com.regaim.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

/**
 * One memory, opened like a story: the video, what happened, who was there, what it was,
 * where it happened, and a box to ask the memory anything.
 */
class MomentActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_ID = "id"
        fun open(ctx: Context, id: String) {
            ctx.startActivity(Intent(ctx, MomentActivity::class.java).putExtra(EXTRA_ID, id))
            if (ctx is Activity) {
                @Suppress("DEPRECATION")
                ctx.overridePendingTransition(R.anim.slide_up_in, R.anim.stay)
            }
        }
    }

    private lateinit var memory: Memory
    private lateinit var mv: MemoryViews
    private lateinit var video: VideoView
    private lateinit var poster: ImageView
    private lateinit var bigPlay: View
    private lateinit var playPause: ImageView
    private lateinit var seek: SeekBar
    private lateinit var timeText: TextView
    private lateinit var muteBtn: ImageView
    private lateinit var totalText: TextView
    private lateinit var controls: View
    private var controlsShown = true
    private var autoplayed = false
    private val hideControls = Runnable { setControls(false) }
    private val ui = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var player: MediaPlayer? = null
    private var muted = false
    private var loadedUri = ""
    private var triedAnalyze = false
    private var asking = false
    private var social: MemorySocial? = null

    private val ticker = object : Runnable {
        override fun run() {
            if (video.isPlaying) {
                seek.progress = video.currentPosition
                timeText.text = Fmt.shortDuration(video.currentPosition.toLong())
                totalText.text = Fmt.shortDuration(video.duration.toLong().coerceAtLeast(0))
            }
            ui.postDelayed(this, 250)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_moment)
        val m = MemoryStore.get(this, intent.getStringExtra(EXTRA_ID) ?: "")
        if (m == null) {
            Toast.makeText(this, L.t("הזיכרון הזה כבר אינו זמין", "This memory is no longer available"), Toast.LENGTH_LONG).show()
            finish()
            return
        }
        memory = m
        mv = MemoryViews(this)
        video = findViewById(R.id.videoView)
        poster = findViewById(R.id.videoPoster)
        bigPlay = findViewById(R.id.btnBigPlay)
        playPause = findViewById(R.id.btnPlayPause)
        seek = findViewById(R.id.videoSeek)
        timeText = findViewById(R.id.videoTime)
        totalText = findViewById(R.id.videoTotal)
        muteBtn = findViewById(R.id.btnMute)
        controls = findViewById(R.id.mediaControls)
        findViewById<View>(R.id.btnFullscreen).contentDescription = L.t("מסך מלא", "Full screen")
        setupTaps()
        findViewById<View>(R.id.btnChoosePreview).setOnClickListener { choosePreview() }

        findViewById<View>(R.id.btnClose).setOnClickListener { finish() }
        findViewById<View>(R.id.btnFixMoment).setOnClickListener { fix() }
        bigPlay.setOnClickListener { play() }
        playPause.setOnClickListener { if (video.isPlaying) pause() else play() }
        muteBtn.setOnClickListener {
            muted = !muted
            applyMute()
        }
        findViewById<View>(R.id.btnFullscreen).setOnClickListener { fullscreen() }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) {
                    video.seekTo(p)
                    timeText.text = Fmt.shortDuration(p.toLong())
                }
            }
            override fun onStartTrackingTouch(s: SeekBar?) { ui.removeCallbacks(hideControls) }
            override fun onStopTrackingTouch(s: SeekBar?) { scheduleHide() }
        })
        video.setOnPreparedListener { mp ->
            player = mp
            applyMute()
            mp.isLooping = true
            seek.max = mp.duration.coerceAtLeast(1)
            timeText.text = Fmt.shortDuration(0)
            totalText.text = Fmt.shortDuration(mp.duration.toLong())
            memory.previewWindow?.takeUnless { it.isFallback }?.let { window ->
                video.seekTo(window.startMs.toInt())
                seek.progress = window.startMs.toInt()
                timeText.text = Fmt.shortDuration(window.startMs)
            }
            // Opens playing, like a story: the moment is what you came for.
            if (!autoplayed && !isFinishing) { autoplayed = true; play() }
        }
        video.setOnCompletionListener {
            playPause.setImageResource(R.drawable.ic_play); playPause.contentDescription = L.t("ניגון", "Play")
            bigPlay.visibility = View.VISIBLE
            video.seekTo(0)
            seek.progress = 0
        }
        video.setOnErrorListener { _, _, _ ->
            bigPlay.visibility = View.GONE
            timeText.text = ""
            totalText.text = ""
            Toast.makeText(this, L.t("לא מצאתי את הסרטון של הרגע הזה בגלריה", "Couldn't find this moment's video in your gallery"), Toast.LENGTH_LONG).show()
            true
        }
        val askInput = findViewById<EditText>(R.id.askInput)
        findViewById<View>(R.id.btnMore).setOnClickListener { v -> moreMenu(v) }
        findViewById<View>(R.id.btnAsk).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = mv.dp(18).toFloat()
                setColor(ContextCompat.getColor(this@MomentActivity, R.color.accent))
            }
            setOnClickListener { ask(askInput.text.toString()) }
        }
        askInput.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_SEND) { ask(askInput.text.toString()); true } else false
        }
        bind(first = true)
        addCreateCard()
        mv.reveal(findViewById(R.id.momentBody))
        ui.post(ticker)
    }

    override fun onResume() {
        super.onResume()
        // Back from trimming or fixing: reload.
        if (::memory.isInitialized) MemoryStore.get(this, memory.id)?.let { memory = it; bind(first = false) }
    }

    override fun onPause() {
        super.onPause()
        if (::video.isInitialized && video.isPlaying) pause()
    }

    override fun onDestroy() {
        ui.removeCallbacks(ticker)
        ui.removeCallbacks(hideControls)
        worker.shutdownNow()
        social?.close()
        super.onDestroy()
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(R.anim.stay, R.anim.fade_out_down)
    }

    // ---------- video ----------

    private fun uri(): Uri = if (memory.uri.startsWith("content://") || memory.uri.startsWith("file://"))
        Uri.parse(memory.uri) else Uri.fromFile(java.io.File(memory.uri))

    private fun exportUri(): Uri {
        val source = uri()
        return if (source.scheme == "file") androidx.core.content.FileProvider.getUriForFile(
            this, "$packageName.files", java.io.File(source.path ?: throw IllegalStateException(L.t("המדיה אינה זמינה", "Media isn't available"))),
        ) else source
    }

    private fun loadVideo() {
        if (loadedUri == memory.uri) return
        loadedUri = memory.uri
        if (!memory.isVideo) {
            video.stopPlayback()
            video.visibility = View.GONE
            bigPlay.visibility = View.GONE
            findViewById<View>(R.id.mediaControls).visibility = View.GONE
            poster.visibility = View.VISIBLE
            poster.setOnClickListener(null)
            if (!memory.isPending) photoExpandHint()
            // A photo: at the screen's own sharpness, not the small card cover.
            Covers.loadSharp(poster, memory, maxOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)) { bmp -> fitBox(bmp) }
            return
        }
        findViewById<View>(R.id.videoBox).findViewWithTag<View>("expand")?.visibility = View.GONE
        video.visibility = View.VISIBLE
        findViewById<View>(R.id.mediaControls).visibility = View.VISIBLE
        video.setVideoURI(uri())
        poster.visibility = View.VISIBLE
        bigPlay.visibility = View.VISIBLE
        timeText.text = Fmt.shortDuration(0)
        totalText.text = Fmt.shortDuration(memory.durationMs)
        Covers.load(poster, memory) { bmp -> fitBox(bmp) }
    }

    /** The video frame takes the clip's shape (portrait clips get a tall frame), within the screen. */
    private fun fitBox(bmp: Bitmap) {
        val box = findViewById<FrameLayout>(R.id.videoBox)
        val w = resources.displayMetrics.widthPixels
        // A tall clip or photo fills the whole screen (only the top edge of the details peeks from below; scroll
        // down for them). A wide one keeps its own shape.
        val maxH = resources.displayMetrics.heightPixels - mv.dp(36)
        val h = (w * bmp.height.toFloat() / bmp.width).toInt().coerceIn(mv.dp(220), maxH)
        box.layoutParams = box.layoutParams.apply { height = h }
        backdrop(bmp)
    }

    /** The picture again behind the clip, soft and dark, so a tall clip has no empty bars at its sides. */
    private fun backdrop(bmp: Bitmap) {
        val iv = findViewById<ImageView>(R.id.videoBackdrop)
        try {
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                iv.setImageBitmap(bmp)
                iv.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(70f, 70f, android.graphics.Shader.TileMode.CLAMP))
            } else {
                // Older phones: a tiny copy stretched back up looks blurred.
                val small = Bitmap.createScaledBitmap(bmp, 16, (16f * bmp.height / bmp.width).toInt().coerceAtLeast(1), true)
                iv.setImageBitmap(small)
            }
            iv.setColorFilter(0xFF6E6E6E.toInt(), android.graphics.PorterDuff.Mode.MULTIPLY)
        } catch (_: Throwable) { }
    }

    // ---------- taps and controls ----------

    /** One tap: show the controls (or play/pause when they show). Two taps: favorite, with a heart. */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun setupTaps() {
        val layer = findViewById<View>(R.id.videoTapLayer)
        val gesture = android.view.GestureDetector(this, object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: android.view.MotionEvent) = true
            override fun onSingleTapConfirmed(e: android.view.MotionEvent): Boolean {
                // A photo: a tap opens it full screen, like a story.
                if (!memory.isVideo) { if (!memory.isPending) fullscreen(); return true }
                if (!controlsShown) setControls(true)
                else if (video.isPlaying) pause() else play()
                return true
            }
            override fun onDoubleTap(e: android.view.MotionEvent): Boolean {
                if (!memory.favorite) toggleFavorite()
                val heart = findViewById<View>(R.id.heartPop)
                heart.scaleX = 0.4f; heart.scaleY = 0.4f; heart.alpha = 0.95f
                heart.animate().setStartDelay(0).scaleX(1.05f).scaleY(1.05f).setDuration(240)
                    .withEndAction { heart.animate().alpha(0f).setStartDelay(250).setDuration(260).start() }.start()
                return true
            }
        })
        layer.setOnTouchListener { _, e -> gesture.onTouchEvent(e) }
        layer.contentDescription = if (memory.isVideo) L.t("נגן או עצור", "Play or pause") else L.t("פתיחה במסך מלא", "Open full screen")
    }

    private fun setControls(show: Boolean) {
        controlsShown = show
        ui.removeCallbacks(hideControls)
        controls.animate().cancel()
        if (show) {
            controls.visibility = View.VISIBLE
            controls.animate().alpha(1f).setDuration(160).start()
            scheduleHide()
        } else {
            controls.animate().alpha(0f).setDuration(260).withEndAction { if (!controlsShown) controls.visibility = View.INVISIBLE }.start()
        }
    }

    /** While playing, the controls step aside after a moment so the clip fills the frame. */
    private fun scheduleHide() {
        ui.removeCallbacks(hideControls)
        if (memory.isVideo && video.isPlaying) ui.postDelayed(hideControls, 2600)
    }

    private fun play() {
        if (!memory.isVideo) return
        if (poster.visibility == View.VISIBLE) poster.animate().alpha(0f).setDuration(180).withEndAction { poster.visibility = View.GONE; poster.alpha = 1f }.start()
        bigPlay.visibility = View.GONE
        playPause.setImageResource(R.drawable.ic_pause); playPause.contentDescription = L.t("השהיה", "Pause")
        video.start()
        scheduleHide()
    }

    private fun pause() {
        video.pause()
        playPause.setImageResource(R.drawable.ic_play); playPause.contentDescription = L.t("ניגון", "Play")
        bigPlay.visibility = View.VISIBLE
        setControls(true)
        ui.removeCallbacks(hideControls)
    }

    private fun applyMute() {
        try { player?.setVolume(if (muted) 0f else 1f, if (muted) 0f else 1f) } catch (_: Exception) {}
        muteBtn.setImageResource(if (muted) R.drawable.ic_volume_off else R.drawable.ic_volume)
        muteBtn.contentDescription = if (muted) L.t("הפעל קול", "Unmute") else L.t("השתק", "Mute")
    }

    /** On a photo: a small "full screen" mark in the corner (a tap anywhere on the photo opens it). */
    private fun photoExpandHint() {
        val box = findViewById<android.widget.FrameLayout>(R.id.videoBox)
        box.findViewWithTag<View>("expand")?.let { it.visibility = View.VISIBLE; return }
        val d = resources.displayMetrics.density
        box.addView(android.widget.ImageView(this).apply {
            tag = "expand"
            setImageResource(R.drawable.ic_fullscreen); setColorFilter(0xFFFFFFFF.toInt())
            setPadding((10 * d).toInt(), (10 * d).toInt(), (10 * d).toInt(), (10 * d).toInt())
            background = DS.oval(0x59000000)
            contentDescription = L.t("מסך מלא", "Full screen")
            setOnClickListener { fullscreen() }
            layoutParams = android.widget.FrameLayout.LayoutParams((40 * d).toInt(), (40 * d).toInt(), android.view.Gravity.BOTTOM or android.view.Gravity.START).apply {
                setMargins((14 * d).toInt(), 0, 0, (34 * d).toInt())
            }
        })
    }

    private fun fullscreen() {
        if (video.isPlaying) pause()
        // Kept memories open full screen, one after another, like stories.
        if (!memory.isPending) { StoryViewerActivity.open(this, memory.id); return }
        if (!memory.isVideo) { share(); return }
        try {
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(exportUri(), "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (_: Exception) {
            Toast.makeText(this, L.t("הקליפ נמצא בגלריה, תיקייה Moments", "Your clip is in the gallery, in the Moments folder"), Toast.LENGTH_LONG).show()
        }
    }

    // ---------- the story ----------

    /** A deliberate family choice, never labelled as an AI-selected scene. */
    private fun choosePreview() {
        if (!memory.isVideo || memory.durationMs < 1000) return
        val helper = Ui(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(helper.dp(20), helper.dp(8), helper.dp(20), 0)
        }
        box.addView(helper.hint(L.t("בחרו שנייה עד שש שניות שמספרות את הזיכרון שלכם. הבחירה משנה רק את התצוגה בבית.", "Pick 1 to 6 seconds that tell your memory's story. This only changes how it looks on Home.")))
        val duration = memory.durationMs.toFloat()
        val window = memory.previewWindow
        val start = (window?.startMs ?: 0L).toFloat().coerceIn(0f, duration - 1000f)
        val end = (window?.endMs ?: (start + 3000).toLong()).toFloat().coerceIn(start + 1000, minOf(duration, start + 6000))
        val label = helper.text("", 14f, top = 10)
        val slider = com.google.android.material.slider.RangeSlider(this).apply {
            valueFrom = 0f
            valueTo = duration
            values = listOf(start, end)
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            setMinSeparationValue(1000f)
        }
        fun showRange() {
            val values = slider.values
            label.text = "${Fmt.shortDuration(values[0].toLong())} – ${Fmt.shortDuration(values[1].toLong())} · " + L.t("${String.format(java.util.Locale.US, "%.1f", (values[1] - values[0]) / 1000)} שניות", "${String.format(java.util.Locale.US, "%.1f", (values[1] - values[0]) / 1000)} sec")
        }
        slider.addOnChangeListener { _, _, _ -> showRange() }
        showRange()
        box.addView(label)
        box.addView(slider)
        box.addView(helper.button(L.t("צפו בקטע שבחרתם", "Watch your selection")) {
            video.seekTo(slider.values[0].toInt())
            play()
            ui.postDelayed({ if (!isDestroyed && video.isPlaying) pause() }, (slider.values[1] - slider.values[0]).toLong())
        })
        val dialog = AlertDialog.Builder(this)
            .setTitle(L.t("הרגע שנרצה לזכור", "The moment to remember"))
            .setView(box)
            .setPositiveButton(L.t("שמור בחירה", "Save selection"), null)
            .setNeutralButton(L.t("חזרה לתצוגה המקורית", "Back to original")) { _, _ ->
                val fresh = MemoryStore.get(this, memory.id) ?: return@setNeutralButton
                memory = fresh.copy(highlightStartMs = -1, highlightEndMs = -1, highlightSource = "")
                if (!MemoryStore.saveFromUi(this) { MemoryStore.update(this, memory) }) return@setNeutralButton
                bind(false)
            }
            .setNegativeButton(L.t("ביטול", "Cancel"), null).create()
        dialog.setOnDismissListener { if (video.isPlaying) pause() }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val chosen = slider.values
                val chosenStart = chosen[0].toLong()
                val chosenEnd = chosen[1].toLong()
                if (chosenEnd - chosenStart !in 1000L..6000L || chosenEnd > memory.durationMs) {
                    Toast.makeText(this, L.t("בחרו קטע באורך שנייה עד שש שניות", "Choose a clip between 1 and 6 seconds"), Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                val fresh = MemoryStore.get(this, memory.id) ?: return@setOnClickListener
                memory = fresh.copy(highlightStartMs = chosenStart, highlightEndMs = chosenEnd, highlightSource = "user")
                if (!MemoryStore.saveFromUi(this) { MemoryStore.update(this, memory) }) return@setOnClickListener
                bind(false)
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun bind(first: Boolean) {
        val m = memory
        loadVideo()
        findViewById<TextView>(R.id.momentTitle).text = Fmt.title(m)
        findViewById<TextView>(R.id.momentMeta).text = listOf((if (m.timeSource == "imported") L.t("נוסף: ", "Added: ") else "") + Fmt.dayTime(m.time), if (m.isVideo) Fmt.duration(m.durationMs) else L.t("תמונה", "Photo")).joinToString(" · ")
        findViewById<TextView>(R.id.previewSource).text = if (m.isVideo) when (m.previewWindow?.source) {
            "user" -> L.t("התצוגה בבית: הקטע שבחרתם", "On Home: your selected clip")
            "trigger" -> L.t("התצוגה בבית: סביב הרגע שזוהה בצילום", "On Home: around the moment we detected")
            else -> L.t("התצוגה בבית: תמונה מהזיכרון, ללא קטע נבחר", "On Home: a still from the memory, no clip selected")
        } else L.t("תמונה מהזיכרון", "A still from the memory")
        // "What shows on Home" lives in the ⋮ menu now; the screen stays about the moment.
        findViewById<View>(R.id.btnChoosePreview).visibility = View.GONE
        bindInfo()
        bindStory(first)
        bindPeople()
        bindTags()
        bindPlace()
        bindAsk()
        bindActions()
        if (first || social == null) {
            social?.close()
            val box = findViewById<LinearLayout>(R.id.socialBox)
            box.removeAllViews()
            social = MemorySocial(this, m.id, { shareToCircle() }).also { box.addView(it.view); it.load() }
        } else social?.load()
    }

    private fun bindStory(first: Boolean) {
        val m = memory
        val desc = findViewById<TextView>(R.id.momentDesc)
        mv.stopPulse(desc)
        desc.setOnClickListener(null)
        findViewById<TextView>(R.id.storySource).apply {
            // Facts first (what was actually observed), then where the words below come from.
            val facts = Facts.line(this@MomentActivity, m)
            val source = if (m.description.isBlank()) "" else when {
                m.descriptionByUser -> L.t("הסיפור שלכם", "Your story")
                m.descriptionSource == "sampled_images" -> L.t("התיאור: מה שנראה בתמונות האלה, לא בכל הסרטון · אפשר לתקן", "The description: what's in these pictures, not the whole video · you can fix it")
                else -> L.t("תיאור שמור · אפשר לתקן פרטים", "Saved description · you can fix details")
            }
            text = listOf(facts, source).filter { it.isNotEmpty() }.joinToString("\n")
            visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
        }
        when {
            m.analyzed && m.description.isNotEmpty() -> {
                desc.text = m.description
                desc.setTextColor(ContextCompat.getColor(this, R.color.ink))
                if (!first) mv.reveal(desc)
            }
            (!m.analyzed || NamedMoments.waitingForNames(m)) && ClaudeClient.hasKey(this) && AnalysisRetry.gaveUp(this, m.id) -> {
                mv.stopPulse(desc)
                desc.text = L.t("לא הצלחנו לתאר את הרגע הזה אחרי כמה ניסיונות. לנסות שוב? ›", "We couldn't describe this moment after a few tries. Try again? ›")
                desc.setTextColor(ContextCompat.getColor(this, R.color.ink_soft))
                desc.setOnClickListener { AnalysisRetry.reset(this, m.id); triedAnalyze = false; desc.setOnClickListener(null); bind(first = false) }
            }
            (!m.analyzed || NamedMoments.waitingForNames(m)) && ClaudeClient.hasKey(this) -> {
                desc.text = L.t("אנחנו מבינים את הרגע...", "Getting to know this moment...")
                desc.setTextColor(ContextCompat.getColor(this, R.color.accent))
                mv.pulse(desc)
                if (!triedAnalyze) {
                    triedAnalyze = true
                    worker.execute {
                        try { MemoryStore.analyzePending(applicationContext) } catch (_: Exception) {}
                        runOnUiThread {
                            if (isFinishing) return@runOnUiThread
                            val fresh = MemoryStore.get(this, memory.id) ?: return@runOnUiThread
                            memory = fresh
                            if (fresh.analyzed && !NamedMoments.waitingForNames(fresh)) bind(first = false) else {
                                mv.stopPulse(desc)
                                desc.text = L.t("לא הצלחנו להבין את הרגע הפעם. ננסה שוב בפעם הבאה שתפתחו את האפליקציה.", "We couldn't make sense of this moment yet. We'll try again next time you open the app.")
                                desc.setTextColor(ContextCompat.getColor(this, R.color.ink_soft))
                            }
                        }
                    }
                }
            }
            // Nobody known in it: no guessed words; who's in it, and it's described with their names.
            m.analyzed && !NamedMoments.named(m) && !m.descriptionByUser -> {
                desc.text = L.t("לא זיהינו מי ברגע הזה, אז לא כתבנו תיאור. מי זה? ›", "We didn't recognize who's in this moment, so we didn't describe it. Who is it? ›")
                desc.setTextColor(ContextCompat.getColor(this, R.color.ink_soft))
                desc.setOnClickListener { editPeople() }
            }
            m.analyzed -> {
                desc.text = L.t("אין עדיין תיאור לזיכרון הזה. אפשר להוסיף את הסיפור שלכם.", "No description yet. Add your own story.")
                desc.setTextColor(ContextCompat.getColor(this, R.color.ink_soft))
                desc.setOnClickListener { fix() }
            }
            else -> {
                desc.text = L.t("להפעיל הבנת רגעים? נספר מה רואים ב-3 תמונות מהרגע ›", "Turn on moment understanding? We'll describe what's in 3 pictures from it ›")
                desc.setTextColor(ContextCompat.getColor(this, R.color.ink_soft))
                desc.setOnClickListener { AiGate.askConsent(this) { triedAnalyze = false; bind(first = false) } }
            }
        }
        findViewById<TextView>(R.id.momentReason).apply {
            val lines = mutableListOf<String>()
            if (m.chosen) lines.add(L.t("♥ שמרתם את הרגע הזה בעצמכם", "♥ You saved this moment yourself"))
            if (m.reason.isNotEmpty()) lines.add(L.t("למה זה רגע שלכם: ${m.reason}", "Why it matters: ${m.reason}"))
            Detections.explain(this@MomentActivity, m.id)?.let { lines.add(L.t("למה Moments מצא את הרגע: $it", "Why Moments found it: $it")) }
            if (m.noLearn) lines.add(L.t("Moments לא לומד מהרגע הזה", "Moments won't learn from this moment"))
            text = lines.joinToString("\n")
            visibility = if (lines.isEmpty()) View.GONE else View.VISIBLE
        }
        findViewById<TextView>(R.id.btnFixMoment).apply {
            visibility = View.VISIBLE
            text = L.t("כתבו או תקנו את הסיפור", "Write or edit the story")
        }
    }

    /** Three quick facts under the title: who, where, and favorite. */
    private fun bindInfo() {
        val m = memory
        val row = findViewById<LinearLayout>(R.id.infoRow)
        row.removeAllViews()
        fun chip(icon: Int, line1: String, line2: String, onClick: (() -> Unit)?, iconColor: Int = R.color.accent): View {
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setBackgroundResource(R.drawable.chat_chip_bg)
                setPadding(mv.dp(10), 0, mv.dp(10), 0)
                // Same height for every chip, one line or two.
                layoutParams = LinearLayout.LayoutParams(0, mv.dp(56), 1f).apply {
                    marginStart = mv.dp(4); marginEnd = mv.dp(4)
                }
                if (onClick != null) setOnClickListener { onClick() }
            }
            box.addView(mv.icon(icon, 22, iconColor))
            val texts = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(mv.dp(8), 0, mv.dp(2), 0)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            texts.addView(TextView(this).apply {
                text = line1
                textSize = 14f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(ContextCompat.getColor(this@MomentActivity, R.color.ink))
            })
            if (line2.isNotEmpty()) texts.addView(TextView(this).apply {
                text = line2
                textSize = 12f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(ContextCompat.getColor(this@MomentActivity, R.color.ink_soft))
            })
            box.addView(texts)
            return box
        }
        val who = Fmt.names(m).firstOrNull()
        val age = who?.let { n -> Profile.kids(this).firstOrNull { it.name == n }?.let { Profile.ageText(it.birth, m.time) } }.orEmpty()
        if (who != null) row.addView(chip(R.drawable.ic_face, who, age.ifEmpty { if (Fmt.names(m).size > 1) L.t("ועוד ${Fmt.names(m).size - 1}", "+${Fmt.names(m).size - 1} more") else "" }, { editPeople() }))
        val place = Fmt.place(m)
        if (place.isNotEmpty()) row.addView(chip(R.drawable.ic_pin_outline, place, "", null))
        // (Loving a moment is the heart in the bottom bar, or a double tap on the video: one control, not two.)
    }

    /** Choose who was in this moment: everyone the family knows, plus a new name. */
    private fun editPeople() {
        val current = Fmt.names(memory)
        // The family and the named people only (not every name ever typed on a moment), without "אני (X)" twins of X.
        val raw = (Profile.members(this).map { it.name } + PeopleStore.people(this).map { it.name } + current)
            .map { it.trim() }.filter { it.isNotBlank() }.distinct()
        val known = raw.filterNot { n -> Regex("^אני\\s*\\((.+)\\)$").find(n)?.groupValues?.get(1)?.trim()?.let { it in raw } == true }
        val ui = Ui(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(20), ui.dp(4), ui.dp(20), 0)
        }
        val checks = known.map { n ->
            android.widget.CheckBox(this).apply {
                text = n
                textSize = 16f
                isChecked = n in current
                box.addView(this)
            }
        }
        if (known.isEmpty()) box.addView(ui.hint(L.t("עוד אין שמות. כתבו שם חדש:", "No names yet. Add a new one:"), top = 4))
        val newName = ui.field(L.t("שם בן או בת המשפחה", "Family member's name"), "", top = 10)
        box.addView(newName)
        val rewrite = if (ClaudeClient.hasKey(this)) android.widget.CheckBox(this).apply {
            text = L.t("לכתוב מחדש את התיאור עם השמות", "Rewrite the description with these names")
            isChecked = true
            setPadding(0, ui.dp(8), 0, 0)
            box.addView(this)
        } else null
        AlertDialog.Builder(this)
            .setTitle(L.t("מי היה ברגע הזה?", "Who was in this moment?"))
            .setView(android.widget.ScrollView(this).apply { addView(box) })
            .setPositiveButton(L.t("שמור", "Save")) { _, _ ->
                val chosen = checks.filter { it.isChecked }.map { it.text.toString() }.toMutableList()
                newName.text.toString().split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach { if (it !in chosen) chosen.add(it) }
                setPeople(chosen, rewrite = rewrite?.isChecked == true)
            }
            .setNegativeButton(L.t("ביטול", "Cancel"), null)
            .show()
    }

    /** Saves who was in the moment: names not found by face recognition are "added", missing ones "removed". */
    private fun setPeople(names: List<String>, rewrite: Boolean) {
        val fresh = MemoryStore.get(this, memory.id) ?: memory
        val faces = fresh.people.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        memory = fresh.copy(
            extraPeople = names.filter { it !in faces }.joinToString(", "),
            hiddenPeople = faces.filter { it !in names }.joinToString(", "),
            analyzed = if (rewrite) false else fresh.analyzed,
        )
        if (!MemoryStore.saveFromUi(this) { MemoryStore.update(this, memory) }) return
        triedAnalyze = false
        bind(first = false)
        // The face recognition learns from the fix too (on the phone): a removed name isn't this face, an added one is.
        val removed = faces.filter { it !in names }
        val added = names.filter { it !in faces }
        if (removed.isNotEmpty() || added.isNotEmpty()) {
            val id = memory.id
            Thread {
                try { PeopleStore.learnFromMoment(applicationContext, id, removed, added) } catch (_: Exception) { }
                runOnUiThread { if (!isFinishing && !isDestroyed) MemoryStore.get(this, id)?.let { memory = it; bind(first = false) } }
            }.start()
        }
        Toast.makeText(this, if (names.isEmpty()) L.t("עודכן", "Updated") else L.t("עודכן: ${names.joinToString(", ")}", "Updated: ${names.joinToString(", ")}"), Toast.LENGTH_SHORT).show()
    }

    private fun toggleFavorite() {
        val fresh = MemoryStore.get(this, memory.id) ?: memory
        memory = fresh.copy(favorite = !fresh.favorite)
        if (!MemoryStore.saveFromUi(this) { MemoryStore.update(this, memory) }) return
        if (memory.favorite) Detections.feedbackFor(this, memory.id, 1)
        bindInfo()
        bindActions()
        if (memory.favorite) Toast.makeText(this, L.t("נוסף לרגעים שאהבתם", "Added to moments you love"), Toast.LENGTH_SHORT).show()
    }

    /** "Make something from this moment": visible right under the title, not hidden in the ⋮ menu. */
    private fun addCreateCard() {
        val info = findViewById<View>(R.id.infoRow)
        val parent = info.parent as? android.view.ViewGroup ?: return
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.card_accent_bg)
            setPadding(mv.dp(18), mv.dp(16), mv.dp(18), mv.dp(16))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = mv.dp(16) }
        }
        card.addView(TextView(this).apply {
            text = L.t("יוצרים מהרגע הזה", "Make something from this moment")
            textSize = 17f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(ContextCompat.getColor(this@MomentActivity, R.color.ink))
        })
        card.addView(TextView(this).apply {
            text = L.t("סרט אנימציה מצויר בתלת-ממד (בסגנון פיקסאר).", "A 3D animated film (Pixar style).")
            textSize = 13f
            setTextColor(DS.inkSoft(this@MomentActivity))
            setPadding(0, mv.dp(4), 0, mv.dp(10))
        })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun pill(label: String, weight: Float, go: () -> Unit) = TextView(this).apply {
            text = label
            textSize = 14f
            gravity = android.view.Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(ContextCompat.getColor(this@MomentActivity, R.color.ds_on_button))
            background = DS.buttonBackground(this@MomentActivity, primary = true)
            setPadding(mv.dp(10), mv.dp(11), mv.dp(10), mv.dp(11))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight).apply { marginEnd = mv.dp(8) }
            setOnClickListener { go() }
        }
        // (The comic was taken off: nobody used it. Its screen is still in the app, unreachable.)
        row.addView(pill(L.t("סרט מצויר 3D", "3D film"), 1.3f) { CartoonFilmActivity.open(this, memory.id) })
        card.addView(row)
        parent.addView(card, parent.indexOfChild(info) + 1)
    }

    private fun moreMenu(anchor: View) {
        val menu = android.widget.PopupMenu(this, anchor)
        menu.menu.add(0, 6, 0, L.t("סרט מצויר בתלת-ממד", "3D animated film"))
        menu.menu.add(0, 2, 2, if (memory.milestone.isEmpty()) L.t("סמן כאבן דרך", "Mark as milestone") else L.t("ערוך אבן דרך", "Edit milestone"))
        menu.menu.add(0, 3, 3, L.t("תקן את מה שנכתב", "Fix the description"))
        menu.menu.add(0, 4, 4, L.t("שתף את הקליפ המלא", "Share full clip"))
        menu.menu.add(0, 5, 5, L.t("פתח בנגן של הטלפון", "Open in phone's player"))
        if (memory.isVideo && memory.durationMs >= 1000) menu.menu.add(0, 7, 6, L.t("בחרו את הקטע שיופיע בבית", "Choose the clip shown on Home"))
        if (memory.isVideo && memory.durationMs >= 2000) menu.menu.add(0, 8, 7, L.t("למצוא את הרגע המדויק (8 תמונות)", "Find the exact moment (8 pictures)"))
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> ComicActivity.forMoment(this, memory.id)
                2 -> editMilestone()
                3 -> fix()
                4 -> share()
                5 -> fullscreen()
                6 -> CartoonFilmActivity.open(this, memory.id)
                7 -> choosePreview()
                8 -> if (ClaudeClient.apiKey(this).startsWith("sk-")) findExactMoment() else AiGate.askConsent(this) { findExactMoment() }
            }
            true
        }
        menu.show()
    }

    /**
     * The extended look: 8 pictures around the detector's mark, and the AI returns the moment's span with its confidence.
     * Only a confident answer becomes "the moment"; otherwise the preview stays "around the detection".
     */
    private fun findExactMoment() {
        if (!ClaudeClient.hasKey(this)) return
        Toast.makeText(this, L.t("מסתכלים על 8 תמונות סביב הרגע...", "Looking at 8 pictures around the moment…"), Toast.LENGTH_SHORT).show()
        val m = memory
        worker.execute {
            val r = try { Result.success(ClaudeClient.findMoment(applicationContext, m)) } catch (e: Exception) { Result.failure(e) }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                r.onSuccess { found ->
                    if (found == null) {
                        Toast.makeText(this, L.t("התמונות לא הראו רגע ברור. התצוגה נשארת סביב הזיהוי.", "The pictures didn't show a clear moment. The preview stays around the detection."), Toast.LENGTH_LONG).show()
                    } else {
                        Facts.segment(this, m.id, found.first, found.second, found.third)
                        val fresh = MemoryStore.get(this, m.id) ?: m
                        // A range you chose yourself stays yours.
                        if (fresh.highlightSource != "user") {
                            memory = fresh.copy(highlightStartMs = found.first, highlightEndMs = found.second, highlightSource = "ai")
                            if (!MemoryStore.saveFromUi(this) { MemoryStore.update(this, memory) }) return@runOnUiThread
                        }
                        Toast.makeText(this, L.t("נמצא: ${Fmt.duration(found.first)}–${Fmt.duration(found.second)}", "Found: ${Fmt.duration(found.first)}–${Fmt.duration(found.second)}"), Toast.LENGTH_LONG).show()
                        bind(first = false)
                    }
                }.onFailure { Toast.makeText(this, it.message ?: "", Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun bindPeople() {
        val m = memory
        val row = findViewById<LinearLayout>(R.id.peopleRow)
        val hint = findViewById<TextView>(R.id.peopleHint)
        row.removeAllViews()
        hint.setOnClickListener(null)
        val names = Fmt.names(m)
        val kids = Profile.kids(this)
        for (n in names) {
            val age = kids.firstOrNull { it.name == n }?.let { Profile.ageText(it.birth, m.time) }.orEmpty()
            val manuallyTagged = m.extraPeople.split(",").map { it.trim() }.contains(n)
            val provenance = if (manuallyTagged) L.t("תיוג שלכם", "Tagged by you") else L.t("זיהוי פנים", "Face recognition")
            row.addView(mv.person(n, listOf(age, provenance).filter { it.isNotEmpty() }.joinToString(" · ")) {
                startActivity(Intent(this, MemoriesActivity::class.java).putExtra(MemoriesActivity.EXTRA_PERSON, n)
                    .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            }.apply {
                setOnLongClickListener {
                    AlertDialog.Builder(this@MomentActivity).setTitle(L.t("להסיר את $n מהרגע הזה?", "Remove $n from this moment?"))
                        .setPositiveButton(L.t("הסר", "Remove")) { _, _ -> setPeople(Fmt.names(memory) - n, rewrite = false) }
                        .setNegativeButton(L.t("ביטול", "Cancel"), null).show()
                    true
                }
            })
        }
        hint.setTextColor(ContextCompat.getColor(this, R.color.ink_soft))
        hint.text = when {
            names.isNotEmpty() -> L.t("לחיצה ארוכה על אדם מסירה אותו מהרגע", "Long-press a person to remove them")
            !PeopleStore.peopleEnabled(this) -> L.t("זיהוי הפנים כבוי. אפשר להפעיל בפרופיל.", "Face recognition is off. Turn it on in your profile.")
            !m.facesScanned -> L.t("מחפשים מי היה כאן...", "Looking for who was here...")
            else -> L.t("לא זוהה כאן אף אחד. הוסיפו בעצמכם עם ה-+, או תנו שמות לפנים ›", "No one recognized yet. Add people with +, or name faces ›")
        }
        run {
            row.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(mv.dp(78), LinearLayout.LayoutParams.WRAP_CONTENT)
                addView(TextView(this@MomentActivity).apply {
                    text = "+"
                    textSize = 24f
                    gravity = android.view.Gravity.CENTER
                    setTextColor(ContextCompat.getColor(this@MomentActivity, R.color.accent))
                    setBackgroundResource(R.drawable.dashed_circle)
                    layoutParams = LinearLayout.LayoutParams(mv.dp(56), mv.dp(56))
                })
                addView(TextView(this@MomentActivity).apply {
                    text = L.t("אנשים נוספים", "Add people")
                    textSize = 12f
                    gravity = android.view.Gravity.CENTER
                    setTextColor(ContextCompat.getColor(this@MomentActivity, R.color.ink_soft))
                    setPadding(0, mv.dp(6), 0, 0)
                })
                setOnClickListener { editPeople() }
            })
        }
        if (names.isEmpty() && m.facesScanned && PeopleStore.peopleEnabled(this)) {
            hint.setTextColor(ContextCompat.getColor(this, R.color.accent))
            hint.setOnClickListener {
                AlertDialog.Builder(this).setTitle(L.t("לא זוהה כאן אף אחד", "No one recognized here"))
                    .setMessage(L.t("אפשר לסרוק שוב בזהירות רבה יותר (יותר פריימים, ותמונות הפרופיל של המשפחה), ואז התיאור ייכתב מחדש.", "You can scan again more carefully (more frames, plus your family's profile photos); the description will be rewritten."))
                    .setPositiveButton(L.t("סרקו שוב", "Scan again")) { _, _ -> rescanFaces() }
                    .setNeutralButton(L.t("אנשים", "People")) { _, _ -> PeopleActivity.open(this) }
                    .setNegativeButton(L.t("ביטול", "Cancel"), null).show()
            }
        }
    }

    private fun rescanFaces() {
        val id = memory.id
        Toast.makeText(this, L.t("סורקים שוב...", "Scanning again…"), Toast.LENGTH_SHORT).show()
        Thread {
            try {
                PeopleStore.rescan(applicationContext, memory)
                try { Pets.rescan(applicationContext, memory) } catch (_: Throwable) {}
                try { MemoryStore.analyzePending(applicationContext) } catch (_: Exception) {}
            } catch (_: Exception) {}
            runOnUiThread {
                MemoryStore.get(this, id)?.let { memory = it }
                if (!isFinishing) recreate()
            }
        }.start()
    }

    private fun bindTags() {
        val m = memory
        val row = findViewById<LinearLayout>(R.id.tagsRow)
        row.removeAllViews()
        val chips = mutableListOf<String>()
        if (m.category.isNotEmpty()) chips.add(L.category(m.category))
        if (m.milestone.isNotEmpty()) chips.add("★ ${m.milestone}")
        val names = Fmt.names(m).toSet()
        m.tags.split(",").map { it.trim() }
            .filter { it.length in 2..18 && it != m.category && it !in names }
            .distinct().take(7).forEach { chips.add(it) }
        // What saved it — only when it's a real trigger (an imported photo has nothing to say here).
        when {
            m.label == "manual" -> chips.add(L.t("נשמר על ידיכם", "Saved by you"))
            m.label.startsWith("״") -> chips.add(m.label)
            m.origin == "import" || m.origin == "film" -> { }
            Labels.TRIGGERS.containsKey(m.label) -> chips.add(Labels.he(m.label))
        }
        chips.forEach { row.addView(mv.tag(it)) }
        // The AI thinks this may be a milestone: the parent decides (yes = it's marked; no = never asked again).
        val guess = m.milestoneGuess
        if (m.milestone.isEmpty() && guess.isNotBlank() && guess != "-") row.addView(mv.tag("★ " + L.t("אבן דרך? $guess", "Milestone? $guess")).apply {
            alpha = 0.85f
            setOnClickListener {
                AlertDialog.Builder(this@MomentActivity)
                    .setTitle(L.t("זאת אבן דרך?", "Is this a milestone?"))
                    .setMessage(L.t("נראה שזה יכול להיות: $guess. לסמן את הרגע כאבן דרך?", "This might be: $guess. Mark the moment as a milestone?"))
                    .setPositiveButton(L.t("כן, לסמן", "Yes, mark it")) { _, _ ->
                        memory = (MemoryStore.get(this@MomentActivity, memory.id) ?: memory).copy(milestone = guess, milestoneGuess = "", category = "אבן דרך")
                        MemoryStore.update(this@MomentActivity, memory)
                        bind(first = false)
                    }
                    .setNeutralButton(L.t("לסמן בשם אחר", "Mark with another name")) { _, _ -> editMilestone() }
                    .setNegativeButton(L.t("לא", "No")) { _, _ ->
                        memory = (MemoryStore.get(this@MomentActivity, memory.id) ?: memory).copy(milestoneGuess = "-")
                        MemoryStore.update(this@MomentActivity, memory)
                        bind(first = false)
                    }
                    .show()
            }
        })
    }

    private fun bindPlace() {
        val m = memory
        val box = findViewById<View>(R.id.placeBox)
        if (m.place.isEmpty()) {
            box.visibility = View.GONE
            return
        }
        box.visibility = View.VISIBLE
        val t = findViewById<TextView>(R.id.placeText)
        val hasMap = m.lat != null && m.lon != null
        t.text = m.place + if (hasMap) L.t("\nפתח במפה ›", "\nOpen in Maps ›") else ""
        if (hasMap) findViewById<View>(R.id.placeCard).setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:${m.lat},${m.lon}?q=${m.lat},${m.lon}")))
            } catch (_: Exception) {
            }
        }
    }

    // ---------- ask the memory ----------

    private fun bindAsk() {
        val chips = findViewById<LinearLayout>(R.id.askChips)
        chips.removeAllViews()
        val input = findViewById<EditText>(R.id.askInput)
        input.isEnabled = !asking
        input.hint = L.t("שאלו על הפרטים שנשמרו בזיכרון...", "Ask about this memory...")
        findViewById<View>(R.id.btnAsk).isEnabled = !asking
        val qs = listOf(L.t("באיזה יום צילמנו את זה?", "What date was this taken?"), L.t("מי היה שם?", "Who was there?"), L.t("איפה זה היה?", "Where was this?"))
        for (q in qs) chips.addView(mv.tag(q).apply {
            isEnabled = !asking
            setOnClickListener { input.setText(q); ask(q) }
        })
    }

    private fun ask(q: String) {
        val question = q.trim()
        if (question.isEmpty() || asking) return
        MemoryFacts.answer(memory, question)?.let { known ->
            findViewById<TextView>(R.id.askAnswer).apply {
                visibility = View.VISIBLE
                mv.stopPulse(this)
                text = known
                setTextColor(ContextCompat.getColor(this@MomentActivity, R.color.ink))
            }
            findViewById<LinearLayout>(R.id.askRelated).removeAllViews()
            return
        }
        if (!ClaudeClient.hasKey(this)) {
            findViewById<TextView>(R.id.askAnswer).apply {
                visibility = View.VISIBLE
                text = L.t("אין לי מספיק מידע שמור כדי לענות על זה. אפשר לשאול על התאריך, השעה, המקום או האנשים שתויגו.", "I don't have enough saved info to answer that. Try asking about the date, time, place, or tagged people.")
            }
            return
        }
        asking = true
        bindAsk()
        val answer = findViewById<TextView>(R.id.askAnswer)
        val related = findViewById<LinearLayout>(R.id.askRelated)
        related.removeAllViews()
        answer.visibility = View.VISIBLE
        answer.text = L.t("רגע, אני מסתכלת על הרגע...", "One sec, looking at this moment...")
        answer.setTextColor(ContextCompat.getColor(this, R.color.accent))
        mv.pulse(answer)
        val m = memory
        worker.execute {
            val all = MemoryStore.kept(applicationContext)
            val result = try {
                Result.success(ClaudeClient.askMoment(applicationContext, m, question, all))
            } catch (e: Exception) {
                Result.failure(e)
            }
            runOnUiThread {
                asking = false
                if (isFinishing) return@runOnUiThread
                bindAsk()
                mv.stopPulse(answer)
                answer.setTextColor(ContextCompat.getColor(this, R.color.ink))
                result.onSuccess { a ->
                    answer.text = a.text
                    mv.reveal(answer)
                    val byId = all.associateBy { it.id }
                    for (id in a.ids) {
                        val o = byId[id] ?: continue
                        if (o.id == m.id) continue
                        related.addView(LinearLayout(this).apply {
                            orientation = LinearLayout.VERTICAL
                            addView(mv.mini(o, 96, 120) { MomentActivity.open(this@MomentActivity, o.id) })
                            addView(TextView(this@MomentActivity).apply {
                                text = Fmt.day(o.time)
                                textSize = 12f
                                setTextColor(ContextCompat.getColor(this@MomentActivity, R.color.ink_soft))
                                setPadding(0, mv.dp(4), 0, 0)
                            })
                        })
                    }
                }.onFailure {
                    answer.text = L.t("לא הצלחתי לענות עכשיו. השאלה נשארה כאן; אפשר לבדוק את החיבור ולנסות שוב.", "Couldn't answer right now. Your question is still here — check your connection and try again.")
                    answer.setTextColor(ContextCompat.getColor(this, R.color.ink_soft))
                }
            }
        }
    }

    // ---------- quiet actions ----------

    /** The bar at the bottom: favorite, share, family circle, trim, delete. */
    private fun bindActions() {
        val row = findViewById<LinearLayout>(R.id.actionsRow)
        row.removeAllViews()
        fun add(icon: Int, label: String, colorRes: Int = R.color.ink, action: () -> Unit) {
            row.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER
                setBackgroundResource(android.R.drawable.list_selector_background)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                addView(mv.icon(icon, 22, colorRes).apply { (layoutParams as LinearLayout.LayoutParams).bottomMargin = mv.dp(3) })
                addView(TextView(this@MomentActivity).apply {
                    text = label
                    textSize = 11f
                    gravity = android.view.Gravity.CENTER
                    setTextColor(ContextCompat.getColor(this@MomentActivity, colorRes))
                })
                setOnClickListener {
                    scaleX = 0.9f; scaleY = 0.9f
                    animate().scaleX(1f).scaleY(1f).setDuration(160).start()
                    action()
                }
            })
        }
        add(if (memory.favorite) R.drawable.ic_heart else R.drawable.ic_heart_outline, L.t("אהבתי", "Love"),
            if (memory.favorite) R.color.heart else R.color.ink) { toggleFavorite() }
        add(R.drawable.ic_share_outline, L.t("שתף", "Share")) { share() }
        val published = Circle.wasShared(this, memory) || Community.wasPublished(this, memory)
        add(R.drawable.ic_mail, if (published) L.t("פורסם", "Posted") else L.t("פרסם", "Post"), R.color.accent) {
            PublishSheet.show(this, memory) { bindActions() }
        }
        if (memory.isVideo) add(R.drawable.ic_cut, L.t("קצר", "Trim")) { TrimActivity.open(this, memory.id) }
        add(R.drawable.ic_trash, L.t("מחק", "Delete"), R.color.danger) { confirmDelete() }
    }

    /** Sends the heart of this moment (~10 seconds) to the family circle. */
    private fun shareToCircle() {
        if (!Circle.joined(this)) {
            CircleActivity.open(this)
            return
        }
        // Choose the part, write a few words and a style first.
        ShareActivity.share(this, memory.id)
    }

    @Suppress("unused")
    private fun shareToCircleNow() {
        val m = memory
        Toast.makeText(this, L.t("רגע, אנחנו מכינים את הרגע למשפחה...", "Getting this moment ready for your family..."), Toast.LENGTH_SHORT).show()
        worker.execute {
            val r = try {
                Circle.shareMoment(applicationContext, m) {}
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                r.onSuccess {
                    Toast.makeText(this, L.t("שותף עם ${Circle.name(this)} ❤️", "Shared with ${Circle.name(this)} ❤️"), Toast.LENGTH_SHORT).show()
                    bindActions()
                }.onFailure { e -> Toast.makeText(this, L.t("לא הצלחתי לשתף: ${e.message}", "Couldn't share: ${e.message}"), Toast.LENGTH_LONG).show() }
            }
        }
    }

    /** Share the clip itself (e.g. to the family WhatsApp group). */
    private fun share() {
        SocialShare.show(this, memory)
    }

    @Suppress("unused")
    private fun shareRaw() {
        try {
            val text = Fmt.title(memory) + if (memory.description.isNotEmpty()) "\n${memory.description}" else ""
            val send = Intent(Intent.ACTION_SEND)
                .setType(if (memory.isVideo) "video/mp4" else "image/*")
                .putExtra(Intent.EXTRA_STREAM, exportUri())
                .putExtra(Intent.EXTRA_TEXT, text)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(send, L.t("לשתף את הרגע עם...", "Share this moment with...")))
        } catch (_: Exception) {
            Toast.makeText(this, L.t("לא ניתן לשתף את הקליפ הזה", "This clip can't be shared"), Toast.LENGTH_SHORT).show()
        }
    }

    private fun editMilestone() {
        val field = EditText(this).apply {
            hint = L.t("למשל: הצעדים הראשונים", "e.g. First steps")
            setText(memory.milestone)
            val p = (20 * resources.displayMetrics.density).toInt()
            setPadding(p, p / 2, p, p / 2)
        }
        AlertDialog.Builder(this)
            .setTitle(L.t("אבן דרך", "Milestone"))
            .setMessage(L.t("איזו פעם ראשונה זאת?", "Which first is this?"))
            .setView(field)
            .setPositiveButton(L.t("שמור", "Save")) { _, _ ->
                val ms = field.text.toString().trim()
                // From the saved moment (not the copy opened earlier): a description the AI wrote meanwhile stays.
                val fresh = MemoryStore.get(this, memory.id) ?: memory
                memory = fresh.copy(
                    milestone = ms,
                    category = if (ms.isNotEmpty()) "אבן דרך" else if (fresh.category == "אבן דרך") "אחר" else fresh.category,
                )
                if (!MemoryStore.saveFromUi(this) { MemoryStore.update(this, memory) }) return@setPositiveButton
                bind(first = false)
            }
            .setNegativeButton(L.t("ביטול", "Cancel"), null)
            .show()
    }

    /** Moves the moment to the trash (30 days, undo from the trash screen) — no scary dialog. */
    private fun confirmDelete() {
        try { MemoryStore.moveToTrash(this, setOf(memory.id)) } catch (_: Exception) {
            Toast.makeText(this, L.t("המחיקה נכשלה", "Couldn't delete"), Toast.LENGTH_SHORT).show(); return
        }
        Toast.makeText(this, L.t("הועבר לסל המחזור. אפשר לשחזר עד 30 יום.", "Moved to trash. You can restore it within 30 days."), Toast.LENGTH_LONG).show()
        finish()
    }

    /** Correct the AI: title, description, category, why it matters, and whether to learn from it. */
    private fun fix() {
        val ui = Ui(this)
        val m = memory
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(20), ui.dp(8), ui.dp(20), 0)
        }
        box.addView(ui.text(L.t("כותרת", "Title"), 13f, R.color.ink_soft))
        val title = ui.field(L.t("כותרת", "Title"), m.title, top = 2)
        box.addView(title)
        box.addView(ui.text(L.t("מה קורה ברגע", "What's happening"), 13f, R.color.ink_soft, top = 10))
        val desc = ui.field(L.t("תיאור", "Description"), m.description, multiline = true, top = 2)
        box.addView(desc)
        box.addView(ui.text(L.t("למה זה רגע שלך", "Why it matters to you"), 13f, R.color.ink_soft, top = 10))
        val reason = ui.field(L.t("למשל: היא רצה אליי מהגן", "e.g. She ran to me at pickup"), m.reason, multiline = true, top = 2)
        box.addView(reason)
        box.addView(ui.text(L.t("קטגוריה", "Category"), 13f, R.color.ink_soft, top = 10))
        var category = m.category
        box.addView(ui.chips(Categories.ALL.map { L.category(it) }, Categories.ALL.indexOf(m.category), top = 4) { category = Categories.ALL[it] })
        var noLearn = m.noLearn
        box.addView(ui.switch(L.t("לא ללמוד מהרגע הזה", "Don't learn from this moment"), noLearn, top = 12) { noLearn = it })
        box.addView(ui.hint(L.t("מי מופיע מתוקן במסך \"אנשים\" (משפחה ← מי זה?).", "Fix who appears on the \"People\" screen (Family → Who's this?)."), top = 8))
        val scroll = android.widget.ScrollView(this).apply { addView(box) }
        AlertDialog.Builder(this)
            .setTitle(L.t("תיקון הרגע", "Edit moment"))
            .setView(scroll)
            .setPositiveButton(L.t("שמור", "Save")) { _, _ ->
                val fresh = MemoryStore.get(this, m.id) ?: m
                memory = fresh.copy(
                    title = title.text.toString().trim().take(40),
                    titleByUser = true,
                    description = desc.text.toString().trim(),
                    descriptionByUser = true,
                    descriptionSource = "user",
                    reason = reason.text.toString().trim(),
                    category = if (fresh.milestone.isNotEmpty()) "אבן דרך" else category,
                    noLearn = noLearn,
                    analyzed = true,
                )
                if (!MemoryStore.saveFromUi(this) { MemoryStore.update(this, memory) }) return@setPositiveButton
                bind(first = false)
                Toast.makeText(this, L.t("נשמר. Moments ילמד מהתיקון בלמידה הבאה", "Saved. Moments will learn from this next time"), Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(L.t("ביטול", "Cancel"), null)
            .show()
    }
}

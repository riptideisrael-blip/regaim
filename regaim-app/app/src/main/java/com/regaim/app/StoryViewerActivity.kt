package com.regaim.app

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity
import kotlin.math.abs

/**
 * Memories full screen, like stories: one after another, each video plays to its end and the next one starts by
 * itself (photos stay 5 seconds). Tap the left side for the next memory and the right side for the previous one
 * (Hebrew reading direction), hold to pause, swipe down to close. Only kept memories (never the approval queue).
 * Also the other parent's new moments as a story ([partner]): played from the family server, each marked as watched.
 */
class StoryViewerActivity : AppCompatActivity() {
    companion object {
        private const val EXTRA_ID = "id"
        private const val PHOTO_MS = 5_000L
        private const val EXTRA_POSTS = "posts"
        fun open(ctx: Context, memoryId: String) =
            ctx.startActivity(Intent(ctx, StoryViewerActivity::class.java).putExtra(EXTRA_ID, memoryId))
        /** The other parent's moments (ids of [Circle.cachedPartnerMoments]), in this order. */
        fun partner(ctx: Context, postIds: List<String>) = ctx.startActivity(partnerIntent(ctx, postIds))
        fun partnerIntent(ctx: Context, postIds: List<String>): Intent =
            Intent(ctx, StoryViewerActivity::class.java).putStringArrayListExtra(EXTRA_POSTS, ArrayList(postIds))
    }

    /** One thing on the screen: a memory on this phone, or a moment the other parent shared. */
    private class Item(val time: Long, val isVideo: Boolean, val title: String, val memory: Memory? = null, val post: Circle.Post? = null)

    private val loader = java.util.concurrent.Executors.newSingleThreadExecutor()
    /** Which item is on the screen: a link or picture that arrives late for another item is dropped. */
    private var showing = 0

    private lateinit var video: VideoView
    private lateinit var photo: ImageView
    private lateinit var bar: ProgressBar
    private lateinit var title: TextView
    private lateinit var meta: TextView
    private var list: List<Item> = emptyList()
    private var at = 0
    private var paused = false
    /** Paused by holding the screen (resumes on release), not by the pause button. */
    private var heldPause = false
    private var userPaused = false
    private lateinit var pauseBtn: TextView
    private val a11y by lazy { getSystemService(android.view.accessibility.AccessibilityManager::class.java) }
    /** A screen reader is on: a photo waits for "next" instead of moving on by itself. */
    private fun screenReader() = a11y?.isTouchExplorationEnabled == true
    private var photoShownAt = 0L
    private var photoElapsed = 0L

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
        val postIds = intent.getStringArrayListExtra(EXTRA_POSTS)
        if (postIds != null) {
            val byId = Circle.cachedPartnerMoments(this).associateBy { it.id }
            list = postIds.mapNotNull { byId[it] }.map { p -> Item(p.takenAt.takeIf { it > 0 } ?: p.createdAt, p.kind != "photo", p.title, post = p) }
            at = 0
        } else {
            list = MemoryStore.kept(this).sortedByDescending { it.time }.map { Item(it.time, it.isVideo, it.displayTitle, memory = it) }
            at = list.indexOfFirst { it.memory?.id == intent.getStringExtra(EXTRA_ID) }.coerceAtLeast(0)
        }
        if (list.isEmpty()) { finish(); return }

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        video = VideoView(this)
        root.addView(video, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        photo = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER; visibility = View.GONE }
        root.addView(photo, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // Top: progress of this memory, its date and a close button. Bottom: its title.
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(24))
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x99000000.toInt(), 0))
        }
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progressTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(0x55FFFFFF)
        }
        top.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        meta = TextView(this).apply { setTextColor(Color.WHITE); textSize = 14f }
        row.addView(meta, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(TextView(this).apply {
            text = "✕"; textSize = 20f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            minWidth = dp(48); minHeight = dp(48)
            contentDescription = L.t("סגירה", "Close")
            setOnClickListener { finish() }
            A11y.button(this)
        })
        top.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        root.addView(top, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
        title = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 18f; gravity = Gravity.CENTER
            setPadding(dp(20), dp(40), dp(20), dp(84))
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xAA000000.toInt(), 0))
            setShadowLayer(dp(3).toFloat(), 0f, dp(1).toFloat(), 0x99000000.toInt())
        }
        root.addView(title, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))

        // Gestures over everything: tap sides, hold to pause, swipe down to close.
        val gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                if (e.x < root.width / 2f) next() else previous()
                return true
            }
            override fun onLongPress(e: MotionEvent) { if (!paused) { heldPause = true; pause() } }
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                val dy = e2.y - (e1?.y ?: e2.y)
                if (dy > dp(80) && abs(vy) > abs(vx)) { finish(); return true }
                return false
            }
        })
        val touch = View(this)
        touch.setOnTouchListener { _, ev ->
            gestures.onTouchEvent(ev)
            if ((ev.action == MotionEvent.ACTION_UP || ev.action == MotionEvent.ACTION_CANCEL) && heldPause) { heldPause = false; resume() }
            true
        }
        root.addView(touch, 1 + root.indexOfChild(photo), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        touch.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        // Buttons for everyone (screen readers, switch access, a hand that can't tap the edges): previous, pause, next.
        fun ctrl(label: String, desc: String, act: () -> Unit) = TextView(this).apply {
            text = label; textSize = 24f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            minWidth = dp(64); minHeight = dp(56); contentDescription = desc
            setOnClickListener { act() }
            A11y.button(this)
        }
        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        controls.addView(ctrl("‹", L.t("הרגע הקודם", "Previous moment")) { previous() })
        pauseBtn = ctrl("⏸", L.t("השהיה", "Pause")) { if (paused) { userPaused = false; resume() } else { userPaused = true; pause() } }
        controls.addView(pauseBtn)
        controls.addView(ctrl("›", L.t("הרגע הבא", "Next moment")) { next() })
        root.addView(controls, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply { bottomMargin = dp(12) })
        setContentView(root)

        // A video fills the screen like a story (no black bars); a photo is shown whole.
        video.setOnPreparedListener { mp -> VideoCover.apply(video, root, mp.videoWidth, mp.videoHeight) }
        video.setOnCompletionListener { next() }
        video.setOnErrorListener { _, _, _ -> next(); true }
        show()
        tick()
    }

    private fun uriOf(m: Memory): Uri = if (m.uri.startsWith("content://") || m.uri.startsWith("file://")) Uri.parse(m.uri) else Uri.fromFile(java.io.File(m.uri))

    private fun show() {
        val cur = list.getOrNull(at) ?: run { finish(); return }
        val token = ++showing
        paused = false
        bar.progress = 0
        meta.text = (cur.post?.let { p -> p.author.trim().substringBefore(' ') + "  ·  " }.orEmpty()) + "${Fmt.day(cur.time)}  ·  ${at + 1}/${list.size}"
        title.text = cur.title
        userPaused = false; heldPause = false; pauseState()
        // Said for screen readers: which moment this is.
        window.decorView.announceForAccessibility(meta.text.toString() + ". " + cur.title)
        cur.post?.let { p -> PartnerStories.markSeen(this, p.id) }
        val m = cur.memory
        val p = cur.post
        if (cur.isVideo) {
            photo.visibility = View.GONE
            video.visibility = View.VISIBLE
            if (m != null) play(uriOf(m))
            else if (p != null) {
                try { video.stopPlayback() } catch (_: Exception) { }
                // From the family server: a fresh link first (off the main thread).
                loader.execute {
                    val url = try { p.video.ifEmpty { Circle.mediaLink(applicationContext, p.videoPath) } } catch (_: Exception) { "" }
                    runOnUiThread { if (token == showing && !isDestroyed) { if (url.isEmpty()) next() else play(Uri.parse(url)) } }
                }
            }
        } else {
            try { video.stopPlayback() } catch (_: Exception) { }
            video.visibility = View.GONE
            photo.visibility = View.VISIBLE
            if (m != null) photo.setImageBitmap(MediaImages.decode(this, m.uri, 1600))
            else if (p != null) {
                photo.setImageDrawable(null)
                loader.execute {
                    val bmp = try {
                        // The photo itself full screen (the cover may be the grid's small copy).
                        val url = if (p.videoPath.isNotEmpty()) Circle.mediaLink(applicationContext, p.videoPath) else p.cover.ifEmpty { Circle.mediaLink(applicationContext, p.coverPath) }
                        Circle.image(applicationContext, url)
                    } catch (_: Exception) { null }
                    runOnUiThread { if (token == showing && !isDestroyed && bmp != null) photo.setImageBitmap(bmp) }
                }
            }
            photoShownAt = System.currentTimeMillis()
            photoElapsed = 0L
        }
    }

    private fun play(uri: Uri) {
        try { video.setVideoURI(uri); video.start() } catch (_: Exception) { next() }
    }

    override fun onDestroy() { loader.shutdownNow(); super.onDestroy() }

    private fun next() { if (at + 1 < list.size) { at++; show() } else finish() }
    private fun previous() { if (at > 0) { at--; show() } else show() }

    /** The pause button says what it will do. */
    private fun pauseState() {
        if (!::pauseBtn.isInitialized) return
        pauseBtn.text = if (paused) "▶" else "⏸"
        pauseBtn.contentDescription = if (paused) L.t("המשך", "Resume") else L.t("השהיה", "Pause")
    }

    private fun pause() {
        paused = true
        pauseState()
        val m = list.getOrNull(at) ?: return
        if (m.isVideo) video.pause() else photoElapsed += System.currentTimeMillis() - photoShownAt
    }

    private fun resume() {
        paused = false
        pauseState()
        val m = list.getOrNull(at) ?: return
        if (m.isVideo) video.start() else photoShownAt = System.currentTimeMillis()
    }

    /** Moves the progress bar; a photo moves on after 5 seconds. */
    private fun tick() {
        if (isFinishing || isDestroyed) return
        val m = list.getOrNull(at)
        if (m != null && !paused) {
            if (m.isVideo) {
                val d = video.duration
                if (d > 0) bar.progress = (1000L * video.currentPosition / d).toInt()
            } else {
                val shown = photoElapsed + (System.currentTimeMillis() - photoShownAt)
                bar.progress = (1000L * shown / PHOTO_MS).toInt().coerceAtMost(1000)
                if (shown >= PHOTO_MS && !screenReader()) next()
            }
        }
        bar.postDelayed({ tick() }, 50)
    }

    override fun onPause() { super.onPause(); if (!paused) pause() }
    override fun onResume() { super.onResume(); if (paused && !userPaused) resume() }
}

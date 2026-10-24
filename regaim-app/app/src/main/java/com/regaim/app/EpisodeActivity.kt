package com.regaim.app

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity

/**
 * Plays Pitzki's teaching film ([MathEpisode]): shot after shot, full screen, Pitzki's voice over each one, and a
 * small chalkboard in the corner where the numbers are written by hand (always exact). A shot moves on when both its
 * picture and Pitzki's line are done (and after a pause, where the child is asked something).
 */
class EpisodeActivity : AppCompatActivity() {
    companion object {
        fun open(ctx: Context) = ctx.startActivity(Intent(ctx, EpisodeActivity::class.java))
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var voice: PitzkiVoice
    private lateinit var video: VideoView
    private lateinit var board: TextView
    private lateinit var caption: TextView
    private var at = -1
    private var token = 0
    private var destroyed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        voice = PitzkiVoice(this)
        val ui = Ui(this)
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        video = VideoView(this)
        root.addView(video, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        // The chalkboard: dark green slate in a wooden frame, in the top corner.
        board = TextView(this).apply {
            textSize = 34f; setTextColor(Color.rgb(250, 250, 240)); gravity = Gravity.CENTER
            typeface = Typeface.create("casual", Typeface.BOLD)
            setShadowLayer(3f, 0f, 0f, Color.argb(120, 255, 255, 255))
            background = GradientDrawable().apply {
                setColor(Color.rgb(46, 74, 56)); cornerRadius = ui.dp(10).toFloat(); setStroke(ui.dp(7), Color.rgb(140, 98, 60))
            }
            setPadding(ui.dp(22), ui.dp(10), ui.dp(22), ui.dp(10))
            minWidth = ui.dp(150)
            visibility = View.INVISIBLE
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            textDirection = View.TEXT_DIRECTION_LTR
        }
        root.addView(board, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
            topMargin = ui.dp(16); marginEnd = ui.dp(16)
        })
        // What Pitzki says, as subtitles (a quiet room, or a phone with no Hebrew voice).
        caption = TextView(this).apply {
            textSize = 20f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setShadowLayer(6f, 0f, 1f, Color.BLACK)
            setPadding(ui.dp(24), 0, ui.dp(24), ui.dp(18))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        root.addView(caption, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        root.addView(TextView(this).apply {
            text = "✕"; textSize = 22f; setTextColor(Color.argb(200, 255, 255, 255)); setPadding(ui.dp(18), ui.dp(12), ui.dp(18), ui.dp(12))
            contentDescription = L.t("סגירת הסרט", "Close the film")
            setOnClickListener { finish() }
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START))
        setContentView(root)
        if (!PitzkiStore.episodeReady(this)) {
            caption.text = L.t("הסרט עוד לא ירד לטלפון. נסו שוב בעוד רגע.", "The film isn't on the phone yet. Try again in a moment.")
            Thread { try { PitzkiStore.fetchShared(applicationContext) } catch (_: Exception) {} }.start()
            return
        }
        main.postDelayed({ next() }, 600)
    }

    private fun next() {
        if (destroyed) return
        at++
        val shots = MathEpisode.SHOTS
        if (at >= shots.size) { main.postDelayed({ if (!destroyed) finish() }, 1500); return }
        val s = shots[at]
        val my = ++token
        var clipDone = false
        var lineDone = false
        fun maybeNext() { if (my == token && clipDone && lineDone) main.postDelayed({ if (my == token) next() }, 250 + s.waitMs) }
        writeBoard(s.board)
        caption.text = s.line
        try {
            video.setOnPreparedListener { mp -> try { mp.setVolume(0f, 0f) } catch (_: Exception) {} }
            // The picture stays on its last frame until Pitzki finishes his line.
            video.setOnCompletionListener { clipDone = true; maybeNext() }
            video.setOnErrorListener { _, _, _ -> clipDone = true; maybeNext(); true }
            video.setVideoPath(PitzkiStore.sharedClip(this, s.key).absolutePath)
            video.start()
        } catch (_: Exception) { clipDone = true }
        voice.say(s.line) { lineDone = true; maybeNext() }
    }

    /** The board's new text, written in: the new characters appear one by one, like chalk. */
    private fun writeBoard(text: String) {
        if (text.isEmpty()) { board.visibility = View.INVISIBLE; return }
        val old = board.text?.toString().orEmpty()
        board.visibility = View.VISIBLE
        // Read out as words ("1 + 1 = 2" → "on the board: 1 plus 1 equals 2").
        board.contentDescription = L.t("על הלוח: ", "On the board: ") + text.replace("+", L.t("ועוד", "plus")).replace("=", L.t("שווה", "equals"))
        if (text == old) return
        val start = if (text.startsWith(old)) old.length else 0
        for (i in start..text.length) main.postDelayed({ if (!destroyed) board.text = text.substring(0, i) }, (i - start) * 260L)
    }

    private var stopped = false

    override fun onPause() {
        super.onPause()
        // Leaving the screen pauses the film; back, it goes on from the same shot.
        if (at >= 0) { stopped = true; token++; try { voice.stop() } catch (_: Exception) {}; try { video.pause() } catch (_: Exception) {} }
    }

    override fun onResume() {
        super.onResume()
        if (stopped && !destroyed) { stopped = false; at -= 1; next() }
    }

    override fun onDestroy() {
        destroyed = true
        token++
        main.removeCallbacksAndMessages(null)
        try { voice.shutdown() } catch (_: Exception) {}
        super.onDestroy()
    }
}

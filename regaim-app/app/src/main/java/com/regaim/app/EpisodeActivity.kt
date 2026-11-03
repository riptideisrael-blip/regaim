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
            // A solid dark box behind the words: readable over any frame.
            background = GradientDrawable().apply { setColor(Color.argb(200, 0, 0, 0)); cornerRadius = ui.dp(14).toFloat() }
            setPadding(ui.dp(16), ui.dp(6), ui.dp(16), ui.dp(8))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        root.addView(caption, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
            leftMargin = ui.dp(16); rightMargin = ui.dp(16); bottomMargin = ui.dp(16)
        })
        // Top: close, pause / play and sound (not only "close").
        val controls = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.HORIZONTAL }
        fun control(label: String, said: String, onClick: (TextView) -> Unit) = TextView(this).apply {
            text = label; textSize = 20f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            minWidth = ui.dp(48); minHeight = ui.dp(48)
            background = GradientDrawable().apply { setColor(Color.argb(140, 0, 0, 0)); cornerRadius = ui.dp(24).toFloat() }
            contentDescription = said
            A11y.button(this)
            layoutParams = android.widget.LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = ui.dp(8) }
            setOnClickListener { onClick(this) }
        }
        controls.addView(control("✕", L.t("סגירת הסרט", "Close the film")) { finish() })
        controls.addView(control("⏸", L.t("השהיה", "Pause")) { b ->
            if (userPaused) { userPaused = false; at -= 1; next() } else { userPaused = true; token++; try { voice.stop() } catch (_: Exception) {}; try { video.pause() } catch (_: Exception) {} }
            b.text = if (userPaused) "▶" else "⏸"
            b.contentDescription = if (userPaused) L.t("המשך", "Play") else L.t("השהיה", "Pause")
        })
        controls.addView(control(if (voice.muted) "🔇" else "🔊", Wording.state(L.t("קול", "Sound"), !voice.muted)) { b ->
            voice.muted = !voice.muted
            b.text = if (voice.muted) "🔇" else "🔊"
            b.contentDescription = Wording.state(L.t("קול", "Sound"), !voice.muted)
        })
        root.addView(controls, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply {
            topMargin = ui.dp(12); marginStart = ui.dp(12)
        })
        setContentView(root)
        if (!PitzkiStore.episodeReady(this)) {
            caption.text = L.t("הסרט עוד לא ירד לטלפון. נסו שוב בעוד רגע.", "The film isn't on the phone yet. Try again in a moment.")
            Thread { try { PitzkiStore.fetchShared(applicationContext) } catch (_: Exception) {} }.start()
            // A way on from the black screen: try again (it opens once the film is on the phone).
            root.addView(ui.button(L.t("לנסות שוב", "Try again"), primary = true, top = 0) {
                if (PitzkiStore.episodeReady(this)) { recreate() } else { caption.text = L.t("עדיין יורד. נסו שוב בעוד רגע.", "Still downloading. Try again in a moment.") }
            }.apply { layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER) })
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
    /** Paused with the pause button: stays paused when the screen comes back. */
    private var userPaused = false

    override fun onPause() {
        super.onPause()
        // Leaving the screen pauses the film; back, it goes on from the same shot.
        if (at >= 0) { stopped = true; token++; try { voice.stop() } catch (_: Exception) {}; try { video.pause() } catch (_: Exception) {} }
    }

    override fun onResume() {
        super.onResume()
        if (stopped && !destroyed && !userPaused) { stopped = false; at -= 1; next() }
    }

    override fun onDestroy() {
        destroyed = true
        token++
        main.removeCallbacksAndMessages(null)
        try { voice.shutdown() } catch (_: Exception) {}
        super.onDestroy()
    }
}

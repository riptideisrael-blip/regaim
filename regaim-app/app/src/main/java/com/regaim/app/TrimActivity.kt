package com.regaim.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.slider.RangeSlider
import java.util.Locale
import java.util.concurrent.Executors

/** Shorten a moment: pick the part worth keeping, and only that stays in the gallery. */
class TrimActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_ID = "id"
        fun open(ctx: Context, id: String) = ctx.startActivity(Intent(ctx, TrimActivity::class.java).putExtra(EXTRA_ID, id))
    }

    private lateinit var memory: Memory
    private lateinit var video: VideoView
    private lateinit var range: RangeSlider
    private lateinit var label: TextView
    private val ui = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var prepared = false
    private var last = listOf(9f, 21f)

    private val loop = object : Runnable {
        override fun run() {
            // Playing the chosen part stops exactly where the clip will end, so you see the real ending.
            try {
                val v = range.values
                if (prepared && v.size >= 2 && video.isPlaying && video.currentPosition >= (v[1] * 1000).toInt()) {
                    video.pause()
                    video.seekTo((v[1] * 1000).toInt())
                    label.text = L.t("כאן הקטע נגמר · ${fmt(v[1])}", "Clip ends here · ${fmt(v[1])}")
                }
            } catch (_: Exception) {
            }
            ui.postDelayed(this, 50)
        }
    }

    /** Pause and show exactly the frame at [sec]. */
    private fun showFrame(sec: Float) {
        if (video.isPlaying) video.pause()
        video.seekTo((sec * 1000).toInt())
    }

    private fun playFrom(sec: Float) {
        video.seekTo((sec * 1000).toInt())
        video.start()
    }

    /** A row of small frames under the slider, so you can see where things happen. */
    private fun loadStrip(durSec: Float) {
        val strip = findViewById<android.widget.LinearLayout>(R.id.trimStrip)
        val n = 8
        worker.execute {
            val mmr = android.media.MediaMetadataRetriever()
            val frames = try {
                if (memory.uri.startsWith("content://")) mmr.setDataSource(this, Uri.parse(memory.uri)) else mmr.setDataSource(memory.uri)
                (0 until n).map { i ->
                    val t = ((durSec * (i + 0.5f) / n) * 1_000_000).toLong()
                    mmr.getFrameAtTime(t, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { b ->
                        android.graphics.Bitmap.createScaledBitmap(b, 120, (120f * b.height / b.width).toInt().coerceAtLeast(1), true)
                    }
                }
            } catch (_: Throwable) {
                emptyList()
            } finally {
                try { mmr.release() } catch (_: Exception) {}
            }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                strip.removeAllViews()
                for (b in frames) strip.addView(android.widget.ImageView(this).apply {
                    scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                    if (b != null) setImageBitmap(b)
                    layoutParams = android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { marginEnd = 2 }
                })
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_trim)
        memory = MemoryStore.get(this, intent.getStringExtra(EXTRA_ID) ?: "") ?: run {
            Toast.makeText(this, L.t("לא מצאתי את הרגע לעריכה", "Couldn't find this moment to edit"), Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        video = findViewById(R.id.trimVideo)
        range = findViewById(R.id.trimRange)
        label = findViewById(R.id.trimLabel)
        findViewById<View>(R.id.btnClose).setOnClickListener { finish() }
        findViewById<View>(R.id.btnSaveTrim).setOnClickListener { save() }

        // Give the slider two handles right away, so nothing reads a missing handle before the video loads.
        range.valueFrom = 0f
        range.valueTo = 30f
        range.values = listOf(9f, 21f)
        updateLabel()
        video.setOnErrorListener { _, _, _ ->
            label.text = L.t("לא הצלחתי לפתוח את הקליפ לעריכה", "Couldn't open this clip for editing")
            true
        }

        val uri = if (memory.uri.startsWith("content://")) Uri.parse(memory.uri) else Uri.parse("file://" + memory.uri)
        video.setVideoURI(uri)
        video.setOnPreparedListener { mp ->
            mp.isLooping = false
            if (!prepared) {
                try {
                    val dur = (mp.duration / 1000f).coerceAtLeast(3f)
                    // Suggest the middle around the moment itself (the clip is "before + after" the trigger).
                    val mid = dur / 2f
                    val a = (mid - 6f).coerceAtLeast(0f)
                    val b = (mid + 6f).coerceAtMost(dur)
                    range.values = listOf(0f, 1f)
                    range.valueTo = dur
                    range.values = listOf(a, b)
                    last = listOf(a, b)
                    prepared = true
                    updateLabel()
                    loadStrip(dur)
                } catch (e: Exception) {
                    label.text = L.t("שגיאה בהכנת העריכה: ${e.message}", "Couldn't prepare editing: ${e.message}")
                }
            }
            val v = range.values
            if (v.isNotEmpty()) video.seekTo((v[0] * 1000).toInt())
            video.start()
        }
        range.addOnChangeListener { slider, _, fromUser ->
            updateLabel()
            val v = slider.values
            if (fromUser && v.size >= 2) {
                // Show the exact frame of the handle you are dragging: the start or the end.
                val endMoved = kotlin.math.abs(v[1] - last[1]) > kotlin.math.abs(v[0] - last[0])
                showFrame(if (endMoved) v[1] else v[0])
                label.text = if (endMoved) L.t("כאן הקטע ייגמר · ${fmt(v[1])}", "Clip will end here · ${fmt(v[1])}") else L.t("כאן הקטע יתחיל · ${fmt(v[0])}", "Clip will start here · ${fmt(v[0])}")
            }
            if (v.size >= 2) last = v.toList()
        }
        findViewById<View>(R.id.btnPlaySel).setOnClickListener {
            val v = range.values
            if (v.size >= 2) { updateLabel(); playFrom(v[0]) }
        }
        findViewById<View>(R.id.btnSeeEnd).setOnClickListener {
            val v = range.values
            if (v.size >= 2) { label.text = L.t("3 השניות האחרונות של הקטע...", "Last 3 seconds of the clip..."); playFrom(maxOf(v[0], v[1] - 3f)) }
        }
        video.setOnClickListener { if (video.isPlaying) video.pause() else video.start() }
        ui.post(loop)
    }

    override fun onDestroy() {
        ui.removeCallbacks(loop)
        worker.shutdown()
        super.onDestroy()
    }

    private fun fmt(s: Float) = String.format(Locale.US, "%d:%04.1f", (s / 60).toInt(), s % 60)

    private fun updateLabel() {
        val v = range.values
        if (v.size < 2) return
        val a = v[0]
        val b = v[1]
        label.text = L.t("שומרים ${String.format(Locale.US, "%.1f", b - a)} שניות", "Keeping ${String.format(Locale.US, "%.1f", b - a)} sec")
        findViewById<TextView>(R.id.trimStartLabel).text = L.t("התחלה ${fmt(a)}", "Start ${fmt(a)}")
        findViewById<TextView>(R.id.trimEndLabel).text = L.t("סוף ${fmt(b)}", "End ${fmt(b)}")
    }

    private fun save() {
        if (!prepared || range.values.size < 2) return
        val a = (range.values[0] * 1000).toLong()
        val b = (range.values[1] * 1000).toLong()
        if (b - a < 2000) {
            Toast.makeText(this, L.t("צריך לפחות 2 שניות", "Clip must be at least 2 seconds"), Toast.LENGTH_SHORT).show()
            return
        }
        video.pause()
        findViewById<View>(R.id.btnSaveTrim).isEnabled = false
        label.text = L.t("מקצר...", "Trimming...")
        worker.execute {
            val result = try {
                Result.success(ClipTrimmer.trim(applicationContext, memory, a, b))
            } catch (e: Exception) {
                Result.failure(e)
            }
            runOnUiThread {
                result.onSuccess { m ->
                    MemoryStore.update(this, m)
                    Toast.makeText(this, L.t("הרגע קוצר ל-${(b - a) / 1000} שניות", "Trimmed to ${(b - a) / 1000} sec"), Toast.LENGTH_SHORT).show()
                    finish()
                }.onFailure { e ->
                    findViewById<View>(R.id.btnSaveTrim).isEnabled = true
                    label.text = L.t("הקיצור נכשל: ${e.message}", "Trim failed: ${e.message}")
                }
            }
        }
    }
}

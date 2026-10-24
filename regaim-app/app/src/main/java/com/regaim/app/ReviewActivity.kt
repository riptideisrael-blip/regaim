package com.regaim.app

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.Executors

/**
 * The approval queue: automatic captures, one card at a time, a few seconds around the moment (the existing
 * preview, not the whole clip). 👍 keeps it in the archive; 👎 sends it to the trash and teaches the recorder.
 * Nothing about a queued moment leaves the phone (faces and pets are recognized locally, so a name can show).
 */
class ReviewActivity : AppCompatActivity() {
    companion object {
        fun open(ctx: Context) = ctx.startActivity(Intent(ctx, ReviewActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
    }

    private lateinit var ui: Ui
    private lateinit var content: LinearLayout
    private val worker = Executors.newSingleThreadExecutor()
    private var preview: ImageView? = null
    private var kept = 0
    private var dropped = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_screen)
        findViewById<TextView>(R.id.screenTitle).text = L.t("רגעים שמחכים", "Moments waiting")
        findViewById<View>(R.id.btnClose).setOnClickListener { finish() }
        content = findViewById(R.id.screenContent)
        ui = Ui(this)
        render()
        // Local only: recognize faces and pets in the queue so the card can show a name.
        worker.execute {
            try { if (PeopleStore.peopleEnabled(applicationContext)) PeopleStore.scanPending(applicationContext) } catch (_: Exception) { }
            try { Pets.scanPending(applicationContext) } catch (_: Throwable) { }
            runOnUiThread { if (!isFinishing && !isDestroyed) render() }
        }
    }

    override fun onDestroy() {
        preview?.let { Motion.stop(it) }
        worker.shutdown()
        super.onDestroy()
    }

    private fun render() {
        preview?.let { Motion.stop(it) }
        preview = null
        content.removeAllViews()
        try { MemoryStore.expirePending(this) } catch (_: Exception) { }
        val queue = MemoryStore.pending(this)
        if (queue.isEmpty()) { done(); return }
        val m = queue.first()

        content.addView(ui.hint(
            if (queue.size == 1) L.t("רגע אחרון", "Last one") else L.t("עוד ${queue.size} רגעים", "${queue.size} moments to go"),
            top = 12,
        ))

        // A few seconds around the moment, with the preview that already exists (not the whole clip).
        val width = resources.displayMetrics.widthPixels - ui.dp(40)
        val iv = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply { cornerRadius = ui.dp(20).toFloat(); setColor(0xFFF0E6DC.toInt()) }
            clipToOutline = true
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (width * 1.2f).toInt()).apply { topMargin = ui.dp(10) }
            if (m.thumb.isNotEmpty()) BitmapFactory.decodeFile(m.thumb)?.let { setImageBitmap(it) }
        }
        content.addView(iv)
        preview = iv
        Motion.attach(iv, m, width = 480)
        swipe(iv, m)
        // Watch it (with sound) before deciding: a tap on the picture or this line.
        if (m.isVideo) {
            iv.setOnClickListener { watch(m) }
            content.addView(TextView(this).apply {
                text = "▶  " + L.t("לצפות ברגע", "Watch the moment")
                textSize = 15f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(ui.color(R.color.ink))
                gravity = Gravity.CENTER
                setPadding(0, ui.dp(10), 0, ui.dp(4))
                setOnClickListener { watch(m) }
            })
        }

        val names = Fmt.names(m)
        val what = listOf(
            Labels.he(m.label),
            DateUtils.getRelativeTimeSpanString(m.time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString(),
        ).joinToString(" · ")
        content.addView(ui.text(if (names.isNotEmpty()) names.joinToString(", ") else what, 18f, bold = true, top = 14))
        if (names.isNotEmpty()) content.addView(ui.hint(what, top = 2))
        if (m.place.isNotEmpty()) content.addView(ui.hint(m.place, top = 2))
        // Why the recorder kept it, in plain words.
        content.addView(TextView(this).apply {
            text = why(m)
            textSize = 14f
            setTextColor(ui.color(R.color.ink))
            setPadding(ui.dp(12), ui.dp(6), ui.dp(12), ui.dp(6))
            background = GradientDrawable().apply { cornerRadius = ui.dp(14).toFloat(); setColor(0xFFF3EBE2.toInt()) }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ui.dp(8) }
        })

        // 👍 on top, 👎 below.
        content.addView(bigButton("👍  " + L.t("לשמור", "Keep it"), primary = true, top = 18) { keep(m) })
        content.addView(bigButton("👎  " + L.t("לא צריך", "Not needed"), primary = false, top = 10) { drop(m) })
        content.addView(ui.hint(L.t(
            "אפשר גם להחליק את התמונה: ימינה לשמור, שמאלה לזרוק. רגע שלא תגעו בו יעבור לפח אחרי 36 שעות, בלי שנלמד ממנו.",
            "You can also swipe the picture: right to keep, left to drop. A moment you don't touch moves to the trash after 36 hours, and nothing is learned from it.",
        ), top = 14).apply { gravity = Gravity.CENTER })
    }

    /** The clip itself, full screen with sound, from the moment it was saved for. A tap or Back closes it. Phone only. */
    private fun watch(m: Memory) {
        preview?.let { Motion.stop(it) }
        val uri = if (m.uri.startsWith("content://") || m.uri.startsWith("file://")) android.net.Uri.parse(m.uri) else android.net.Uri.fromFile(java.io.File(m.uri))
        val box = android.widget.FrameLayout(this).apply { setBackgroundColor(0xFF000000.toInt()) }
        val video = android.widget.VideoView(this)
        box.addView(video, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        val dlg = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dlg.setContentView(box)
        box.setOnClickListener { dlg.dismiss() }
        video.setOnPreparedListener { mp ->
            val at = m.highlightStartMs
            if (at > 0 && at < mp.duration - 1000) mp.seekTo(at.toInt())
            mp.start()
        }
        video.setOnCompletionListener { dlg.dismiss() }
        video.setOnErrorListener { _, _, _ ->
            android.widget.Toast.makeText(this, L.t("לא הצלחנו לנגן את הרגע", "Couldn't play the moment"), android.widget.Toast.LENGTH_SHORT).show()
            dlg.dismiss(); true
        }
        dlg.setOnDismissListener {
            try { video.stopPlayback() } catch (_: Exception) { }
            preview?.let { p -> if (!isFinishing && !isDestroyed) Motion.attach(p, m, width = 480) }
        }
        video.setVideoURI(uri)
        dlg.show()
    }

    /** "Saved because of: laughter · 14:32" (the sound or sight that made the recorder keep it). */
    private fun why(m: Memory): String {
        val time = java.text.SimpleDateFormat("HH:mm", L.locale).format(java.util.Date(m.time))
        val what = when {
            m.chosen -> L.t("שמרתם אותו בעצמכם", "you saved it yourself")
            m.label.isNotBlank() -> Labels.he(m.label)
            else -> L.t("רגע שנראה חשוב", "a moment that looked special")
        }
        val sure = score(m).takeIf { it > 0f && !m.chosen }?.let { L.t(" (בביטחון ${(it * 100).toInt()}%)", " (${(it * 100).toInt()}% sure)") }.orEmpty()
        return L.t("נשמר בגלל: $what$sure · $time", "Saved because of: $what$sure · $time")
    }

    /** Swipe the picture: right = keep, left = drop (it slides away first). */
    private fun swipe(v: View, m: Memory) {
        var downX = 0f
        var decided = false
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> { downX = e.rawX; decided = false; true }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    view.translationX = dx
                    view.rotation = dx / view.width * 8f
                    view.alpha = 1f - minOf(0.5f, kotlin.math.abs(dx) / view.width)
                    true
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    val dx = e.rawX - downX
                    if (!decided && kotlin.math.abs(dx) > view.width * 0.3f) {
                        decided = true
                        val keepIt = dx > 0
                        view.animate().translationX(if (keepIt) view.width * 1.5f else -view.width * 1.5f).alpha(0f).setDuration(180)
                            .withEndAction { if (keepIt) keep(m) else drop(m) }.start()
                    } else {
                        view.animate().translationX(0f).rotation(0f).alpha(1f).setDuration(150).start()
                        if (kotlin.math.abs(dx) < 10f) view.performClick()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun bigButton(label: String, primary: Boolean, top: Int, onClick: () -> Unit): View = TextView(this).apply {
        text = label
        textSize = 17f
        gravity = Gravity.CENTER
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setTextColor(if (primary) 0xFFFFFFFF.toInt() else ui.color(R.color.ink))
        background = GradientDrawable().apply {
            cornerRadius = ui.dp(28).toFloat()
            if (primary) setColor(0xFF87664F.toInt()) else { setColor(0xFFF0E6DC.toInt()) }
        }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(56)).apply { topMargin = ui.dp(top) }
        setOnClickListener { onClick() }
    }

    /** 👍: into the archive as approved (not a favorite, not "chosen"), a vote for its label, then the usual description. */
    private fun keep(m: Memory) {
        MemoryStore.approve(this, m.id)
        CaptureVotes.up(this, m.label, score(m))
        kept++
        // Failures are recorded per moment inside analyzePending (and retried later by kind); this only guards the screen.
        worker.execute { try { MemoryStore.analyzePending(applicationContext) } catch (e: Exception) { android.util.Log.w("Moments", "Describing after keep failed: ${e.javaClass.simpleName}") } }
        render()
    }

    /** 👎: to the trash with learning (the recorder remembers the look); a vote against its label. Nothing goes to the cloud. */
    private fun drop(m: Memory) {
        MemoryStore.moveToTrash(this, setOf(m.id), learn = true)
        CaptureVotes.down(this, m.label, score(m))
        dropped++
        render()
    }

    /** The detector's score when it saved this moment (-1 when there is none, e.g. a smile). */
    private fun score(m: Memory): Float = Facts.get(this, m.id)?.optDouble("trigger_score", -1.0)?.toFloat() ?: -1f

    private fun done() {
        content.addView(ui.heading(L.t("הכל עבר", "All done"), 24f, top = 24))
        if (kept + dropped > 0) content.addView(ui.hint(L.t("נשמרו $kept · נזרקו $dropped. תודה, Moments לומד מזה.", "Kept $kept · dropped $dropped. Thanks, Moments learns from this."), top = 6))
        else content.addView(ui.hint(L.t("אין רגעים שמחכים לאישור.", "No moments are waiting."), top = 6))
        content.addView(ui.button(L.t("לזיכרונות", "Open memories"), primary = true, top = 18) {
            startActivity(Intent(this, MemoriesActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)); finish()
        })
    }
}

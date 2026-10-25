package com.regaim.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.slider.RangeSlider
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Share a moment with the family circle, or edit one you shared:
 * choose the part of the clip, write a few words, pick a style, see a preview; or delete it.
 */
class ShareActivity : AppCompatActivity() {

    companion object {
        private const val MAX_MS = 30_000L

        fun share(ctx: Context, memoryId: String) =
            ctx.startActivity(Intent(ctx, ShareActivity::class.java).putExtra("memory", memoryId))

        fun edit(ctx: Context, p: Circle.Post) = ctx.startActivity(
            Intent(ctx, ShareActivity::class.java)
                .putExtra("post", p.id).putExtra("memory", p.memoryId).putExtra("author", p.author).putExtra("author_id", p.authorId)
                .putExtra("title", p.title).putExtra("story", p.story).putExtra("people", p.people).putExtra("place", p.place)
                .putExtra("kind", p.kind).putExtra("video", p.video).putExtra("cover", p.cover)
                .putExtra("caption", p.caption).putExtra("style", p.style)
        )
    }

    private lateinit var ui: Ui
    private lateinit var content: LinearLayout
    private val worker = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private var memory: Memory? = null
    private var post: Circle.Post? = null
    private var style = "plain"
    private var recut = true
    private var busy = false
    private var range: RangeSlider? = null
    private var video: VideoView? = null
    private lateinit var preview: ImageView
    private lateinit var previewCaption: TextView

    private val stopAtEnd = object : Runnable {
        override fun run() {
            val v = video
            val r = range
            if (v != null && r != null && r.values.size >= 2 && v.isPlaying && v.currentPosition >= r.values[1].toInt()) v.pause()
            handler.postDelayed(this, 60)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_screen)
        ui = Ui(this)
        content = findViewById(R.id.screenContent)
        findViewById<View>(R.id.btnClose).setOnClickListener { finish() }
        memory = intent.getStringExtra("memory")?.takeIf { it.isNotEmpty() }?.let { MemoryStore.get(this, it) }
        intent.getStringExtra("post")?.let { id ->
            post = Circle.Post(
                id, intent.getStringExtra("author") ?: "", intent.getStringExtra("title") ?: "", intent.getStringExtra("story") ?: "",
                intent.getStringExtra("people") ?: "", intent.getStringExtra("place") ?: "", intent.getStringExtra("kind") ?: "video",
                0L, intent.getStringExtra("video") ?: "", intent.getStringExtra("cover") ?: "", 0L,
                intent.getStringExtra("caption") ?: "", intent.getStringExtra("style") ?: "plain", intent.getStringExtra("memory") ?: "", intent.getStringExtra("author_id") ?: "",
            )
        }
        if (post != null && post?.authorId != Account.id(this)) { Toast.makeText(this, L.t("אפשר לערוך רק זיכרון ששיתפתם מהחשבון הזה", "You can only edit memories you shared from this account"), Toast.LENGTH_LONG).show(); finish(); return }
        if (memory == null && post == null) { finish(); return }
        style = post?.style ?: "plain"
        recut = post == null
        findViewById<TextView>(R.id.screenTitle).text = if (post == null) L.t("שיתוף עם המשפחה", "Share with family") else L.t("עריכת הרגע", "Edit moment")
        build()
        handler.post(stopAtEnd)
    }

    override fun onPause() {
        super.onPause()
        video?.pause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun fmt(ms: Float) = String.format(Locale.US, "%d:%04.1f", (ms / 60000).toInt(), (ms % 60000) / 1000f)

    private fun build() {
        val m = memory
        val p = post

        // ---- the part of the clip ----
        if (m != null && m.isVideo) {
            content.addView(ui.section(if (p == null) L.t("איזה קטע לשתף?", "Which part to share?") else L.t("הקטע ששותף", "Shared clip")))
            if (p != null) content.addView(ui.switch(L.t("לגזור מחדש", "Trim again"), false, top = 4) { on ->
                recut = on
                range?.isEnabled = on
            })
            val box = FrameLayout(this).apply {
                setBackgroundColor(Color.BLACK)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(300)).apply { topMargin = ui.dp(10) }
            }
            val v = VideoView(this).apply {
                layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
            }
            box.addView(v)
            content.addView(box)
            video = v
            val dur = m.durationMs.coerceAtLeast(3000).toFloat()
            val a = (m.highlight - 5000).toFloat().coerceAtMost(dur - 3000f).coerceAtLeast(0f)
            val b = (a + 10_000f).coerceAtMost(dur)
            val labels = ui.hint("", top = 4)
            val r = RangeSlider(this).apply {
                valueFrom = 0f
                valueTo = dur
                values = listOf(a, b)
                setMinSeparationValue(2000f)
                isEnabled = recut
                layoutDirection = View.LAYOUT_DIRECTION_LTR
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ui.dp(6) }
            }
            var last = listOf(a, b)
            fun showLabels() {
                val vs = r.values
                if (vs.size < 2) return
                labels.text = L.t("מ-${fmt(vs[0])} עד ${fmt(vs[1])} · ${String.format(Locale.US, "%.1f", (vs[1] - vs[0]) / 1000f)} שניות", "${fmt(vs[0])} to ${fmt(vs[1])} · ${String.format(Locale.US, "%.1f", (vs[1] - vs[0]) / 1000f)} sec")
            }
            r.addOnChangeListener { s, _, fromUser ->
                var vs = s.values
                if (vs.size < 2) return@addOnChangeListener
                // At most 30 seconds, so it stays light for grandparents' phones.
                if (vs[1] - vs[0] > MAX_MS) {
                    val endMoved = kotlin.math.abs(vs[1] - last[1]) > kotlin.math.abs(vs[0] - last[0])
                    s.values = if (endMoved) listOf(vs[1] - MAX_MS, vs[1]) else listOf(vs[0], vs[0] + MAX_MS)
                    vs = s.values
                }
                if (fromUser) {
                    val endMoved = kotlin.math.abs(vs[1] - last[1]) > kotlin.math.abs(vs[0] - last[0])
                    if (v.isPlaying) v.pause()
                    v.seekTo((if (endMoved) vs[1] else vs[0]).toInt())
                }
                last = vs.toList()
                showLabels()
            }
            content.addView(r)
            content.addView(labels)
            showLabels()
            range = r
            content.addView(ui.button(L.t("▶ נגן את הקטע", "▶ Play clip"), top = 4) {
                val vs = r.values
                if (vs.size >= 2) { v.seekTo(vs[0].toInt()); v.start() }
            })
            v.setOnPreparedListener { mp -> mp.isLooping = false; v.seekTo(a.toInt()) }
            v.setOnErrorListener { _, _, _ -> true }
            v.setVideoURI(if (m.uri.startsWith("content://")) Uri.parse(m.uri) else Uri.parse("file://" + m.uri))
        } else if (p != null) {
            content.addView(ui.hint(L.t("הרגע הזה לא נמצא בטלפון, אז אפשר לערוך רק את הטקסט והעיצוב.", "This moment isn't on this phone, so you can only edit the text and style."), top = 8))
        }

        // ---- words ----
        content.addView(ui.hint(L.t("השיתוף יופיע לכל החברים המאושרים במעגל שלכם. בחירת אנשים מסוימים עדיין אינה זמינה.", "This will be shared with all approved members of your circle. Choosing specific people isn't available yet."), top = 14))
        content.addView(ui.section(L.t("כמה מילים", "A few words")))
        val title = ui.field(L.t("כותרת", "Title"), p?.title ?: m?.let { Fmt.title(it) } ?: "")
        content.addView(title)
        val caption = ui.field(L.t("מה תרצו לספר למשפחה? (למשל: הפעם הראשונה שהוא...)", "What would you like to tell the family? (e.g. the first time he...)"), p?.caption ?: "", multiline = true)
        content.addView(caption)
        var withStory = p?.story?.isNotEmpty() ?: (m?.description?.isNotEmpty() == true)
        if (m != null && m.description.isNotEmpty()) {
            content.addView(ui.switch(L.t("לצרף את הסיפור שנשמר עם הרגע", "Include the story saved with this moment"), withStory) { withStory = it })
        }

        // ---- style + preview ----
        content.addView(ui.section(L.t("עיצוב", "Style")))
        val names = Circle.STYLES.map { it.second }
        content.addView(ui.chips(names, Circle.STYLES.indexOfFirst { it.first == style }.coerceAtLeast(0), top = 8) { i ->
            style = Circle.STYLES[i].first
            applyPreview()
        })
        val card = FrameLayout(this).apply {
            setBackgroundResource(R.drawable.rounded_media)
            clipToOutline = true
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(220)).apply { topMargin = ui.dp(12) }
        }
        preview = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        card.addView(preview)
        card.addView(View(this).apply {
            setBackgroundResource(R.drawable.cover_scrim)
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(130), Gravity.BOTTOM)
        })
        previewCaption = TextView(this).apply {
            textSize = 16f
            setTextColor(Color.WHITE)
            setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD))
            setPadding(ui.dp(16), 0, ui.dp(16), ui.dp(14))
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
        }
        card.addView(previewCaption)
        content.addView(card)
        if (m != null) Covers.load(preview, m)
        else if (p != null && p.cover.isNotEmpty()) worker.execute {
            val bmp = Circle.image(applicationContext, p.cover)
            runOnUiThread { if (bmp != null && !isFinishing) preview.setImageBitmap(bmp) }
        }
        val refresh = { previewCaption.text = listOf(title.text.toString(), caption.text.toString()).filter { it.isNotBlank() }.joinToString("\n") }
        ui.onChange = { refresh() }
        refresh()
        applyPreview()

        // ---- actions ----
        content.addView(ui.button(if (p == null) L.t("💌 שתפו עם המשפחה", "💌 Share with family") else L.t("שמור שינויים", "Save changes"), primary = true, top = 22) {
            val d = Circle.Draft(
                title.text.toString(), caption.text.toString(), withStory, style,
                range?.values?.getOrNull(0)?.toLong() ?: -1, range?.values?.getOrNull(1)?.toLong() ?: -1,
            )
            send(d)
        })
        if (p != null) {
            content.addView(ui.action(L.t("🗑 מחק מהמעגל", "🗑 Remove from circle"), R.color.danger) {
                AlertDialog.Builder(this).setTitle(L.t("למחוק את הרגע מהמעגל?", "Remove this moment from the circle?"))
                    .setMessage(L.t("הוא יימחק מהמעגל לכולם. הרגע עצמו נשאר אצלך בטלפון.", "It will be removed for everyone in the circle. The moment itself stays on your phone."))
                    .setPositiveButton(L.t("מחק", "Remove")) { _, _ ->
                        run(L.t("מוחקים...", "Removing...")) { Circle.deletePost(applicationContext, p); L.t("נמחק מהמעגל", "Removed from circle") }
                    }
                    .setNegativeButton(L.t("ביטול", "Cancel"), null).show()
            }.apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = ui.dp(14); gravity = Gravity.CENTER_HORIZONTAL
                }
            })
        }

    }

    private fun applyPreview() {
        preview.colorFilter = Circle.styleFilter(style)
        val card = preview.parent as? FrameLayout ?: return
        card.foreground = if (style == "party") android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = ui.dp(22).toFloat()
            setStroke(ui.dp(5), 0xFF87664F.toInt())
        } else null
    }

    private fun send(d: Circle.Draft) {
        val m = memory
        val p = post
        if (p == null && m != null) {
            run(L.t("רגע, אנחנו מכינים את הרגע למשפחה...", "Getting this moment ready for your family...")) {
                Circle.shareMoment(applicationContext, m, d) { msg -> runOnUiThread { toast(msg) } }
                L.t("שותף עם ${Circle.name(applicationContext)} ❤️", "Shared with ${Circle.name(applicationContext)} ❤️")
            }
        } else if (p != null) {
            run(L.t("שומרים...", "Saving...")) {
                Circle.updatePost(applicationContext, p, d, m, recut && m != null) { msg -> runOnUiThread { toast(msg) } }
                L.t("נשמר ✓", "Saved ✓")
            }
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun run(busyText: String, work: () -> String) {
        if (busy) return
        busy = true
        toast(busyText)
        video?.pause()
        worker.execute {
            val r = try { Result.success(work()) } catch (e: Exception) { Result.failure(e) }
            runOnUiThread {
                busy = false
                if (isFinishing) return@runOnUiThread
                r.onSuccess {
                    toast(it)
                    setResult(Activity.RESULT_OK)
                    finish()
                }.onFailure { e ->
                    Toast.makeText(this, L.t("לא הצלחנו: ${e.message}", "Something went wrong: ${e.message}"), Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}

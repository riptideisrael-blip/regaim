package com.regaim.app

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import java.io.File
import kotlin.math.abs
import kotlin.math.max

/**
 * A finished book, read like a real picture book: full-page illustrations, the story printed on the page, swipe to
 * turn the page (right to left, like a Hebrew book) with a page-turn effect, a cover, a dedication and "the end".
 * Tap the page for the controls (close, page number, draw this page again).
 */
class BookViewerActivity : AppCompatActivity() {
    companion object {
        private const val EXTRA_ID = "book"
        fun open(ctx: Context, id: String) = ctx.startActivity(Intent(ctx, BookViewerActivity::class.java).putExtra(EXTRA_ID, id))

        /** Plays an animated book's film (saved in the gallery). */
        fun playFilm(ctx: Context, film: String) {
            try {
                ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(android.net.Uri.parse(film), "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            } catch (_: Exception) {
                Toast.makeText(ctx, L.t("הסרט נמצא בגלריה, בתיקייה Moments", "The film is in your gallery, in the Moments folder"), Toast.LENGTH_LONG).show()
            }
        }
    }

    /** One spread of the book as the reader sees it. [page] is the index in Book.allPages (0 = cover), -1 for extra pages. */
    private class Sheet(val kind: Int, val page: Int)
    private val COVER = 0; private val DEDICATION = 1; private val STORY = 2; private val END = 3

    private lateinit var pager: ViewPager2
    private lateinit var bar: LinearLayout
    private lateinit var counter: TextView
    private lateinit var redrawBtn: TextView
    private lateinit var editBtn: TextView
    private lateinit var prevBtn: TextView
    private lateinit var okBtn: TextView
    private lateinit var voiceBtn: TextView
    private lateinit var readBtn: TextView
    private lateinit var shareBtn: TextView
    private lateinit var filmBtn: TextView
    /** Read-aloud: each page plays the parent's recording when it opens. */
    private var readAloud = false
    private var player: android.media.MediaPlayer? = null
    private var recorder: android.media.MediaRecorder? = null
    private var book: Book? = null
    private var sheets: List<Sheet> = emptyList()
    private var busy = false
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val paper = Color.parseColor("#F8F2E7")
    private val ink = Color.parseColor("#2A2420")

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        val root = FrameLayout(this).apply { setBackgroundColor(Color.parseColor("#1C1917")) }
        pager = ViewPager2(this).apply {
            layoutDirection = View.LAYOUT_DIRECTION_RTL // the next page comes from the left, like a Hebrew book
            offscreenPageLimit = 1
            setPageTransformer { page, position -> turn(page, position) }
        }
        root.addView(pager, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(28), dp(16), dp(16))
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xDD000000.toInt(), 0x00000000))
            visibility = View.GONE
        }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val tools = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val close = barText("✕") { finish() }
        counter = barText("") {}
        editBtn = barText(L.t("✎ עריכה", "✎ Edit")) { editCurrent() }
        redrawBtn = barText(L.t("ציור מחדש", "Redraw")) { askRedraw() }
        prevBtn = barText(L.t("↶ הציור הקודם", "↶ Previous")) { backToPrevious() }
        okBtn = barText("") { toggleApproved() }
        voiceBtn = barText(L.t("🎙 הקלטה", "🎙 Record")) { recordVoice() }
        readBtn = barText("") { readAloud = !readAloud; updateBar(); if (readAloud) playCurrent() else stopPlaying() }
        shareBtn = barText(L.t("שליחה", "Send")) { shareBook() }
        filmBtn = barText(L.t("🎬 סרט", "🎬 Film")) { film() }
        top.addView(close)
        top.addView(counter, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(16) })
        top.addView(filmBtn)
        top.addView(shareBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(12) })
        top.addView(okBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(12) })
        tools.addView(editBtn)
        tools.addView(redrawBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(12) })
        tools.addView(prevBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(12) })
        val voice = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        voice.addView(voiceBtn)
        voice.addView(readBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(12) })
        bar.addView(top)
        bar.addView(tools, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        bar.addView(voice, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
        root.addView(bar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
        setContentView(root)
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) { updateBar(); if (readAloud) playCurrent() else stopPlaying() }
        })
        load()
    }

    override fun onDestroy() {
        stopPlaying()
        try { recorder?.release() } catch (_: Exception) { }
        recorder = null
        worker.shutdown()
        super.onDestroy()
    }

    override fun onPause() { stopPlaying(); super.onPause() }

    private fun barText(t: String, onClick: () -> Unit) = TextView(this).apply {
        text = t
        setTextColor(Color.WHITE)
        textSize = 16f
        setPadding(dp(6), dp(6), dp(6), dp(6))
        setOnClickListener { onClick() }
    }

    private fun load(keepAt: Int = 0) {
        val b = BookStore.load(this, intent.getStringExtra(EXTRA_ID).orEmpty()) ?: run { finish(); return }
        book = b
        val list = ArrayList<Sheet>()
        list.add(Sheet(COVER, 0))
        if (b.dedication.isNotBlank()) list.add(Sheet(DEDICATION, -1))
        b.pages.indices.forEach { list.add(Sheet(STORY, it + 1)) }
        list.add(Sheet(END, -1))
        sheets = list
        pager.adapter = Adapter()
        pager.setCurrentItem(keepAt.coerceIn(0, list.size - 1), false)
        updateBar()
    }

    private fun updateBar() {
        val s = sheets.getOrNull(pager.currentItem) ?: return
        counter.text = when (s.kind) {
            COVER -> L.t("כריכה", "Cover")
            STORY -> L.t("עמוד ${s.page} מתוך ${book?.pages?.size ?: 0}", "Page ${s.page} of ${book?.pages?.size ?: 0}")
            else -> ""
        }
        redrawBtn.visibility = if (s.page >= 0) View.VISIBLE else View.GONE
        redrawBtn.text = if (busy) L.t("מציירים מחדש...", "Redrawing...") else L.t("ציור מחדש", "Redraw")
        editBtn.visibility = if (s.kind == END) View.GONE else View.VISIBLE
        val b = book
        val page = if (b != null && s.page >= 0) b.allPages.getOrNull(s.page) else null
        prevBtn.visibility = if (b != null && page != null && page.prev.isNotEmpty() && File(BookStore.dir(this, b.id), page.prev).exists()) View.VISIBLE else View.GONE
        val ok = approved(s)
        okBtn.visibility = if (s.kind == END || ok == null) View.GONE else View.VISIBLE
        okBtn.text = if (ok == true) L.t("✓ אושר", "✓ Approved") else L.t("○ לאשר עמוד", "○ Approve page")
        okBtn.setTextColor(if (ok == true) Color.parseColor("#7BE0A0") else Color.WHITE)
        val hasVoice = page?.voice?.let { it.isNotEmpty() && b != null && File(BookStore.dir(this, b.id), it).exists() } == true
        voiceBtn.visibility = if (page != null) View.VISIBLE else View.GONE
        voiceBtn.text = if (hasVoice) L.t("🎙 הקלטה מחדש", "🎙 Re-record") else L.t("🎙 להקליט את העמוד", "🎙 Record this page")
        readBtn.text = if (readAloud) L.t("⏸ הקראה פועלת", "⏸ Read-aloud on") else L.t("▶ הקראה בקול שלכם", "▶ Read in your voice")
    }

    private fun toggleBar() { bar.visibility = if (bar.visibility == View.VISIBLE) View.GONE else View.VISIBLE; updateBar() }

    /** A gentle page turn: the page leaving folds away around the spine; the next one is revealed underneath. */
    private val rtl = true
    private val dir = if (rtl) -1 else 1

    private fun turn(page: View, position: Float) {
        page.cameraDistance = page.width * 14f
        when {
            position < -1f || position > 1f -> { page.alpha = 0f }
            position <= 0f -> {
                page.alpha = 1f
                page.translationX = 0f
                page.pivotY = page.height / 2f
                page.pivotX = if (rtl) page.width.toFloat() else 0f
                page.rotationY = -75f * position * dir
                page.translationZ = 1f
            }
            else -> {
                page.alpha = 1f
                page.rotationY = 0f
                page.translationZ = 0f
                // Stay in place under the turning page instead of sliding in.
                page.translationX = -page.width * position * dir
                val scale = 0.94f + 0.06f * (1f - abs(position))
                page.scaleX = scale; page.scaleY = scale
            }
        }
        if (position <= 0f) { page.scaleX = 1f; page.scaleY = 1f }
    }

    /** Decodes a page picture at about screen size (no out-of-memory on long books). */
    private fun picture(file: File): Bitmap? {
        if (!file.exists()) return null
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, o)
        val target = max(resources.displayMetrics.widthPixels, 720)
        var sample = 1
        while (o.outWidth / (sample * 2) >= target) sample *= 2
        return BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private inner class Holder(val frame: FrameLayout) : RecyclerView.ViewHolder(frame)

    private inner class Adapter : RecyclerView.Adapter<Holder>() {
        override fun getItemCount() = sheets.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(FrameLayout(parent.context).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        })
        override fun onBindViewHolder(h: Holder, position: Int) {
            val f = h.frame
            f.removeAllViews()
            f.setOnClickListener { toggleBar() }
            val b = book ?: return
            val s = sheets[position]
            when (s.kind) {
                COVER -> coverPage(f, b)
                DEDICATION -> textPage(f, b.dedication, small = L.t("הקדשה", "Dedication"))
                STORY -> storyPage(f, b, s.page)
                else -> endPage(f, b)
            }
            // Approved for print: a small green mark in the corner.
            if (approved(s) == true) f.addView(TextView(this@BookViewerActivity).apply {
                text = L.t("✓ אושר", "✓ OK")
                textSize = 12f
                setTextColor(Color.WHITE)
                setPadding(dp(10), dp(4), dp(10), dp(4))
                background = GradientDrawable().apply { setColor(0xCC2E9E5B.toInt()); cornerRadius = dp(12).toFloat() }
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
                setMargins(0, dp(16), dp(14), 0)
            })
        }
    }

    private fun image(f: FrameLayout, p: BookPage, b: Book): Boolean {
        val bmp = if (p.image.isNotEmpty()) picture(File(BookStore.dir(this, b.id), p.image)) else null
        if (bmp == null) {
            f.setBackgroundColor(paper)
            f.addView(TextView(this).apply {
                text = L.t("העמוד הזה עוד לא צויר.\nפתחו את התפריט ולחצו \"לצייר את העמוד מחדש\".", "This page isn't drawn yet.\nOpen the menu and tap \"Redraw this page\".")
                setTextColor(ink); textSize = 16f; gravity = Gravity.CENTER
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            return false
        }
        // Portrait pages fill the screen; older landscape pages are shown whole.
        val portrait = bmp.height >= bmp.width
        f.setBackgroundColor(if (portrait) Color.BLACK else paper)
        f.addView(ImageView(this).apply {
            setImageBitmap(bmp)
            scaleType = if (portrait) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_START
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        return true
    }

    private fun coverPage(f: FrameLayout, b: Book) {
        image(f, b.cover, b)
        f.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x99000000.toInt(), 0x00000000, 0x00000000))
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(56), dp(24), 0)
        }
        box.addView(TextView(this).apply {
            text = b.title
            textSize = 34f
            typeface = Fonts.display
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setShadowLayer(dp(6).toFloat(), 0f, dp(2).toFloat(), 0xAA000000.toInt())
        })
        box.addView(TextView(this).apply {
            text = L.t("ספר של ${b.child}", "A book about ${b.child}")
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setShadowLayer(dp(4).toFloat(), 0f, dp(1).toFloat(), 0xAA000000.toInt())
            setPadding(0, dp(8), 0, 0)
        })
        f.addView(box, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
        // The publisher's mark at the foot of the cover, as on a printed book.
        f.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0x88000000.toInt(), 0x00000000))
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(90), Gravity.BOTTOM))
        f.addView(brandMark(white = true, size = 22, alpha = 0.92f), FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
        ).apply { bottomMargin = dp(22) })
    }

    /**
     * The Moments mark (logo + name), the book's watermark. Only drawn on screen: the page pictures themselves stay
     * clean (for printing later).
     */
    private fun brandMark(white: Boolean, size: Int, alpha: Float, withName: Boolean = true) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        this.alpha = alpha
        addView(ImageView(this@BookViewerActivity).apply {
            setImageResource(R.drawable.ic_logo_mark)
            if (white) setColorFilter(Color.WHITE)
        }, LinearLayout.LayoutParams(dp(size), dp(size)))
        if (withName) addView(TextView(this@BookViewerActivity).apply {
            text = "Moments"
            textSize = size * 0.62f
            typeface = Fonts.display
            setTextColor(if (white) Color.WHITE else Color.parseColor("#87664F"))
            setPadding(dp(6), 0, 0, 0)
            if (white) setShadowLayer(dp(3).toFloat(), 0f, dp(1).toFloat(), 0x99000000.toInt())
        })
    }

    private fun storyPage(f: FrameLayout, b: Book, index: Int) {
        val p = b.allPages.getOrNull(index) ?: return
        image(f, p, b)
        // The story printed on the page: a soft paper panel over the calm lower part of the picture.
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(18), dp(22), dp(22))
            background = GradientDrawable().apply {
                setColor((paper and 0x00FFFFFF) or (0xEB shl 24))
                cornerRadius = dp(22).toFloat()
            }
            elevation = dp(2).toFloat()
        }
        panel.addView(TextView(this).apply {
            text = BookJson.display(p.text)
            textSize = 20f
            setTextColor(ink)
            setLineSpacing(dp(5).toFloat(), 1f)
            gravity = Gravity.CENTER
            textDirection = if (b.lang == "he") View.TEXT_DIRECTION_RTL else View.TEXT_DIRECTION_LTR
        })
        panel.addView(TextView(this).apply {
            text = "$index"
            textSize = 12f
            setTextColor(Color.parseColor("#8A7F74"))
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        })
        f.addView(panel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
            setMargins(dp(14), 0, dp(14), dp(22))
        })
        // A small, faint watermark in the top corner of every page.
        f.addView(brandMark(white = true, size = 16, alpha = 0.6f), FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START,
        ).apply { setMargins(dp(14), dp(16), 0, 0) })
    }

    private fun textPage(f: FrameLayout, text: String, small: String) {
        f.setBackgroundColor(paper)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(36), 0, dp(36), 0) }
        box.addView(TextView(this).apply { this.text = "♡"; textSize = 30f; setTextColor(Color.parseColor("#C07A5A")); gravity = Gravity.CENTER })
        box.addView(TextView(this).apply {
            this.text = text
            textSize = 24f
            typeface = Fonts.display
            setTextColor(ink)
            gravity = Gravity.CENTER
            setLineSpacing(dp(6).toFloat(), 1f)
            setPadding(0, dp(16), 0, dp(16))
        })
        box.addView(TextView(this).apply { this.text = small; textSize = 13f; setTextColor(Color.parseColor("#8A7F74")); gravity = Gravity.CENTER })
        f.addView(box, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun endPage(f: FrameLayout, b: Book) {
        val drawn = image(f, b.cover, b)
        if (drawn) f.addView(View(this).apply { setBackgroundColor(0xB3000000.toInt()) },
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        box.addView(brandMark(white = drawn, size = 40, alpha = 1f, withName = false), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(14) })
        box.addView(TextView(this).apply {
            text = L.t("הסוף", "The End")
            textSize = 44f
            typeface = Fonts.display
            setTextColor(if (drawn) Color.WHITE else ink)
            gravity = Gravity.CENTER
        })
        box.addView(TextView(this).apply {
            text = L.t("נוצר באהבה ב-Moments", "Made with love in Moments")
            textSize = 14f
            setTextColor(if (drawn) Color.WHITE else Color.parseColor("#8A7F74"))
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, 0)
        })
        // The check before printing.
        val (ok, total) = BookReview.count(b)
        val all = ok == total
        val ready = all && b.allPages.all { it.image.isEmpty() || (it.print.isNotEmpty() && File(BookStore.dir(this, b.id), it.print).exists()) }
        box.addView(TextView(this).apply {
            text = when {
                ready -> L.t("הספר מוכן להדפסה ✓", "The book is ready for print ✓")
                all -> L.t("כל העמודים אושרו ✓", "All pages approved ✓")
                else -> L.t("לפני הדפסה: אושרו $ok מתוך $total עמודים", "Before printing: $ok of $total pages approved")
            }
            textSize = 16f
            setTextColor(if (drawn) Color.WHITE else ink)
            gravity = Gravity.CENTER
            setPadding(0, dp(34), 0, 0)
        })
        if (!ready) box.addView(TextView(this).apply {
            text = if (all) L.t("הכנה להדפסה", "Prepare for print") else L.t("לעמוד הבא לאישור", "Next page to approve")
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(26), dp(12), dp(26), dp(12))
            background = GradientDrawable().apply { setColor(Color.parseColor("#87664F")); cornerRadius = dp(24).toFloat() }
            setOnClickListener { if (all) preparePrint() else firstUnapproved()?.let { pager.currentItem = it } }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
        if (!all) box.addView(TextView(this).apply {
            text = L.t("עברו על כל עמוד: לחצו על העמוד ואז על \"לאשר עמוד\". שינוי בעמוד מבטל את האישור שלו.", "Go over every page: tap it, then \"Approve page\". Changing a page clears its approval.")
            textSize = 13f
            setTextColor(if (drawn) 0xDDFFFFFF.toInt() else Color.parseColor("#8A7F74"))
            gravity = Gravity.CENTER
            setPadding(dp(36), dp(10), dp(36), 0)
        })
        f.addView(box, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun firstUnapproved(): Int? = sheets.indices.firstOrNull { approved(sheets[it]) == false }

    /**
     * Every page is enlarged twice for print (sharp on paper). Cheap and doesn't use a credit. Sending to a print
     * house comes next; for now the book is marked ready.
     */
    private fun preparePrint() {
        val b = book ?: return
        if (busy) return
        busy = true
        val msg = TextView(this).apply { setPadding(dp(24), dp(16), dp(24), dp(8)); textSize = 15f; text = L.t("מכינים את העמודים באיכות הדפסה...", "Preparing the pages in print quality...") }
        val dlg = AlertDialog.Builder(this).setTitle(L.t("הכנה להדפסה", "Preparing for print")).setView(msg).setCancelable(false).show()
        val at = pager.currentItem
        worker.execute {
            val res = try {
                val n = BookMaker.Print.prepare(applicationContext, b.id) { i, t -> runOnUiThread { msg.text = L.t("מכינים עמוד ${i + 1} מתוך $t באיכות הדפסה...", "Preparing page ${i + 1} of $t in print quality...") } }
                L.t("$n עמודים מוכנים להדפסה, בגודל כפול וחד. השליחה לבית הדפוס תתווסף בשלב הבא.", "$n pages are ready for print, twice the size and sharp. Sending to a print house comes next.")
            } catch (e: Exception) { L.t("ההכנה להדפסה לא הושלמה. אפשר לנסות שוב, עמודים שכבר הוכנו לא יוכנו שוב.", "Print preparation didn't finish. You can try again; pages already done are kept.") + (e.message?.let { "\n$it" } ?: "") }
            runOnUiThread {
                busy = false
                try { dlg.dismiss() } catch (_: Exception) { }
                if (isFinishing || isDestroyed) return@runOnUiThread
                load(at)
                AlertDialog.Builder(this).setMessage(res).setPositiveButton(L.t("אישור", "OK"), null).show()
            }
        }
    }

    // ---------- the parent's voice ----------

    private fun voiceFile(b: Book, page: Int) = File(BookStore.dir(this, b.id), "voice_${page}_${System.currentTimeMillis()}.m4a")

    /** Records the parent reading this page (the child hears the book in their voice). */
    private fun recordVoice() {
        val s = sheets.getOrNull(pager.currentItem) ?: return
        val b = book ?: return
        if (s.page < 0) return
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 71)
            return
        }
        stopPlaying()
        val f = voiceFile(b, s.page)
        val rec = try {
            @Suppress("DEPRECATION")
            (if (android.os.Build.VERSION.SDK_INT >= 31) android.media.MediaRecorder(this) else android.media.MediaRecorder()).apply {
                setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
                setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(96_000)
                setAudioSamplingRate(44_100)
                setOutputFile(f.absolutePath)
                prepare()
                start()
            }
        } catch (_: Exception) {
            f.delete()
            Toast.makeText(this, L.t("לא הצלחנו להתחיל להקליט. אם ההקלטה האוטומטית פועלת, עצרו אותה לרגע ונסו שוב.", "Couldn't start recording. If automatic capture is running, pause it for a moment and try again."), Toast.LENGTH_LONG).show()
            return
        }
        recorder = rec
        val at = pager.currentItem
        fun stop(): Boolean {
            val ok = try { rec.stop(); true } catch (_: Exception) { false }
            try { rec.release() } catch (_: Exception) { }
            recorder = null
            return ok && f.length() > 2_000
        }
        AlertDialog.Builder(this)
            .setTitle(L.t("מקליטים... 🎙", "Recording... 🎙"))
            .setMessage(L.t("קראו את העמוד בקול, לאט ובחום. כשתסיימו, לחצו \"סיום\".", "Read the page aloud, slowly and warmly. When you're done, tap \"Done\"."))
            .setCancelable(false)
            .setPositiveButton(L.t("סיום", "Done")) { _, _ ->
                if (!stop()) { f.delete(); Toast.makeText(this, L.t("ההקלטה קצרה מדי, נסו שוב.", "The recording is too short. Try again."), Toast.LENGTH_SHORT).show(); return@setPositiveButton }
                var old = ""
                BookStore.update(this, b.id) { x ->
                    if (s.page == 0) { old = x.cover.voice; x.copy(cover = x.cover.copy(voice = f.name)) }
                    else x.copy(pages = x.pages.mapIndexed { k, p -> if (k == s.page - 1) { old = p.voice; p.copy(voice = f.name) } else p })
                }
                if (old.isNotEmpty()) File(BookStore.dir(this, b.id), old).delete()
                load(at)
                playCurrent()
            }
            .setNegativeButton(L.t("ביטול", "Cancel")) { _, _ -> stop(); f.delete() }
            .show()
    }

    /** Plays this page's recording, if there is one. */
    private fun playCurrent() {
        stopPlaying()
        val s = sheets.getOrNull(pager.currentItem) ?: return
        val b = book ?: return
        val v = b.allPages.getOrNull(s.page)?.voice?.takeIf { it.isNotEmpty() } ?: return
        val f = File(BookStore.dir(this, b.id), v).takeIf { it.exists() } ?: return
        player = try {
            android.media.MediaPlayer().apply {
                setDataSource(f.absolutePath)
                prepare()
                // In read-aloud mode, the next page turns by itself after a short pause.
                setOnCompletionListener {
                    if (readAloud) pager.postDelayed({ if (readAloud && pager.currentItem + 1 < sheets.size) pager.currentItem = pager.currentItem + 1 }, 1200)
                }
                start()
            }
        } catch (_: Exception) { null }
    }

    private fun stopPlaying() {
        try { player?.stop() } catch (_: Exception) { }
        try { player?.release() } catch (_: Exception) { }
        player = null
    }

    /** The animated book's film; for a book without one: make it (one more credit), with the parent's OK. */
    private fun film() {
        val b = book ?: return
        if (b.film.isNotEmpty()) { playFilm(this, b.film); return }
        AlertDialog.Builder(this)
            .setTitle(L.t("להפוך לספר מונפש?", "Make it an animated book?"))
            .setMessage(L.t("כל עמוד יתעורר לחיים לכ-5 שניות, עם המילים על המסך ומוזיקה, בסרט אחד שנשמר בגלריה. עלות: ${BookMaker.ANIMATION_EXTRA} (קרדיט אחד). לוקח כ-10 דקות.", "Every page comes to life for about 5 seconds, with the words on screen and music, in one film saved to the gallery. Cost: ${BookMaker.ANIMATION_EXTRA} (one credit). Takes about 10 minutes."))
            .setPositiveButton(L.t("כן, להנפיש", "Yes, animate")) { _, _ ->
                Billing.ensureCredits(this, 1, Billing.ANIMATION) {
                    BookMaker.animate(applicationContext, b)
                    startActivity(Intent(this, BookActivity::class.java))
                    finish()
                }
            }
            .setNegativeButton(L.t("לא עכשיו", "Not now"), null).show()
    }

    /** The book as a PDF (with the Moments mark), to send to grandparents on WhatsApp, by email... */
    /** Send the book (PDF), or share it with the Moments community with a rating and a review. */
    private fun shareBook() {
        val b = book ?: return
        if (busy) return
        val community = Community.available(this)
        // A printed copy through Lulu: for now only the admin account (the orders are paid from the Lulu account).
        val print = Account.isAdmin(this)
        if (!community && !print) { sendPdf(); return }
        val items = ArrayList<Pair<String, () -> Unit>>()
        items.add(L.t("שליחה כ-PDF (וואטסאפ, מייל...)", "Send as a PDF (WhatsApp, email...)") to { sendPdf() })
        if (community) items.add(L.t(if (Community.bookShared(this, b)) "שיתוף בקהילת Moments (כבר שותף)" else "שיתוף בקהילת Moments, עם חוות דעת", if (Community.bookShared(this, b)) "Share in the Moments community (already shared)" else "Share in the Moments community, with a review") to { shareToCommunity(b) })
        if (print) items.add(L.t(if (PrintBook.order(this, b.id) != null) "📦 ספר מודפס: מצב ההזמנה" else "📦 הזמנת ספר מודפס (כריכה קשה)", if (PrintBook.order(this, b.id) != null) "📦 Printed book: order status" else "📦 Order a printed book (hardcover)") to { PrintOrderActivity.open(this, b.id) })
        if (print) items.add(L.t("🖨️ שמירת קבצי ההדפסה (PDF ל-Lulu)", "🖨️ Save the print files (PDF for Lulu)") to { savePrintFiles(b) })
        AlertDialog.Builder(this)
            .setTitle(L.t("שיתוף הספר", "Share the book"))
            .setItems(items.map { it.first }.toTypedArray()) { _, which -> items[which].second() }
            .show()
    }

    /** Other parents see the book, the parent's stars and words, and that it's a real book made in the app. */
    private fun shareToCommunity(b: Book) {
        if (!Community.termsAccepted(this)) {
            Toast.makeText(this, L.t("קודם מאשרים את כללי הקהילה, ואז חוזרים לשתף", "First accept the community rules, then come back to share"), Toast.LENGTH_LONG).show()
            CommunityActivity.open(this); return
        }
        val pad = dp(20)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, dp(8), pad, 0) }
        box.addView(TextView(this).apply { text = L.t("כמה אהבתם את הספר?", "How much did you love the book?"); textSize = 15f })
        val stars = android.widget.RatingBar(this).apply { numStars = 5; stepSize = 1f; rating = 5f }
        box.addView(stars, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val review = android.widget.EditText(this).apply {
            hint = L.t("כמה מילים להורים אחרים (לא חובה)", "A few words for other parents (optional)")
            minLines = 2; maxLines = 5
            filters = arrayOf(android.text.InputFilter.LengthFilter(600))
        }
        box.addView(review)
        val withText = android.widget.CheckBox(this).apply {
            text = L.t("לכלול את מילות הסיפור (יש בהן את השמות)", "Include the story's words (they have the names)"); isChecked = true
        }
        box.addView(withText)
        box.addView(TextView(this).apply {
            text = L.t(
                "כל המשתמשים המחוברים ב-Moments יראו את הציורים (הם דומים לילדים), עם השם הפרטי שלכם. אפשר למחוק את הפוסט בכל רגע.",
                "Every signed-in Moments member will see the drawings (they look like your children), with your first name. You can delete the post at any time.",
            )
            textSize = 13f; setPadding(0, dp(8), 0, 0)
        })
        AlertDialog.Builder(this)
            .setTitle(L.t("שיתוף בקהילה", "Share with the community"))
            .setView(box)
            .setPositiveButton(L.t("לשתף", "Share")) { _, _ ->
                busy = true
                Toast.makeText(this, L.t("מעלים את הספר לקהילה...", "Uploading the book to the community..."), Toast.LENGTH_SHORT).show()
                val rating = stars.rating.toInt()
                val words = review.text.toString()
                val text = withText.isChecked
                worker.execute {
                    val err = try { Community.publishBook(applicationContext, b, rating, words, text) { }; null } catch (e: Exception) { e.message ?: "" }
                    runOnUiThread {
                        busy = false
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        if (err == null) Toast.makeText(this, L.t("הספר שותף בקהילה 💜", "The book is shared with the community 💜"), Toast.LENGTH_LONG).show()
                        else Toast.makeText(this, L.t("לא הצלחנו לשתף: ", "Couldn't share: ") + err, Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton(L.t("ביטול", "Cancel"), null)
            .show()
    }

    /** Makes Lulu's two print files on the phone (nothing ordered) and opens the share sheet to save them. */
    private fun savePrintFiles(b: Book) {
        if (busy) return
        busy = true
        val line = TextView(this).apply { text = L.t("מתחילים...", "Starting..."); textSize = 15f; setPadding(dp(24), dp(16), dp(24), 0) }
        val dlg = AlertDialog.Builder(this).setTitle(L.t("מכינים קבצי הדפסה", "Making the print files")).setView(line).setCancelable(false).show()
        worker.execute {
            val r = try { PrintBook.exportFiles(applicationContext, b.id) { t -> runOnUiThread { line.text = t } } } catch (e: Exception) { e }
            runOnUiThread {
                busy = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                dlg.dismiss()
                if (r is PrintBook.Export) {
                    if (r.noCover != null) Toast.makeText(this, L.t("נשמר רק קובץ העמודים. הכריכה צריכה את החיבור ל-Lulu: ", "Only the pages were made. The cover needs the Lulu connection: ") + r.noCover, Toast.LENGTH_LONG).show()
                    PrintBook.shareFiles(this, r.files, b.title)
                } else Toast.makeText(this, L.t("לא הצלחנו להכין את קבצי ההדפסה: ", "Couldn't make the print files: ") + ((r as? Exception)?.message ?: ""), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun sendPdf() {
        val b = book ?: return
        if (busy) return
        busy = true
        Toast.makeText(this, L.t("מכינים את הספר לשליחה...", "Preparing the book to send..."), Toast.LENGTH_SHORT).show()
        worker.execute {
            val f = try { BookPdf.make(applicationContext, b) } catch (_: Exception) { null }
            runOnUiThread {
                busy = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (f == null) Toast.makeText(this, L.t("לא הצלחנו להכין את הספר לשליחה", "Couldn't prepare the book to send"), Toast.LENGTH_LONG).show()
                else BookPdf.share(this, f, b.title)
            }
        }
    }

    /** Whether this sheet was approved for print (null: nothing to approve, e.g. a page not drawn yet). */
    private fun approved(s: Sheet): Boolean? {
        val b = book ?: return null
        return when (s.kind) {
            DEDICATION -> b.dedicationOk
            COVER, STORY -> b.allPages.getOrNull(s.page)?.takeIf { it.status == "done" && it.image.isNotEmpty() }?.ok
            else -> null
        }
    }

    /** The parent approves this page for print (or takes the approval back). Approving moves on to the next page. */
    private fun toggleApproved() {
        val s = sheets.getOrNull(pager.currentItem) ?: return
        val b = book ?: return
        val now = approved(s) ?: return
        val i = if (s.kind == DEDICATION) -1 else s.page
        BookStore.update(this, b.id) { BookReview.approve(it, i, !now) }
        val at = pager.currentItem
        load(at)
        if (!now) pager.postDelayed({ if (pager.currentItem == at && at + 1 < sheets.size) pager.currentItem = at + 1 }, 350)
    }

    /** Back to the picture before the last redraw (and again, to undo that). */
    private fun backToPrevious() {
        val s = sheets.getOrNull(pager.currentItem) ?: return
        val b = book ?: return
        if (busy || s.page < 0) return
        BookStore.update(this, b.id) { x ->
            if (s.page == 0) x.copy(cover = BookReview.swapBack(x.cover))
            else x.copy(pages = x.pages.mapIndexed { k, p -> if (k == s.page - 1) BookReview.swapBack(p) else p })
        }
        load(pager.currentItem)
    }

    /** The parent fixes the words: the title on the cover, the dedication, or a page's text. Saved at once. */
    private fun editCurrent() {
        val s = sheets.getOrNull(pager.currentItem) ?: return
        val b = book ?: return
        val (title, now) = when (s.kind) {
            COVER -> L.t("שם הספר", "Book title") to b.title
            DEDICATION -> L.t("ההקדשה", "Dedication") to b.dedication
            STORY -> L.t("הטקסט של עמוד ${s.page}", "Page ${s.page} text") to (b.pages.getOrNull(s.page - 1)?.text ?: return)
            else -> return
        }
        val field = android.widget.EditText(this).apply {
            setText(now)
            setSelection(now.length)
            minLines = if (s.kind == STORY) 4 else 1
            textDirection = if (b.lang == "he") View.TEXT_DIRECTION_RTL else View.TEXT_DIRECTION_LTR
        }
        val box = FrameLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(field) }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(box)
            .setPositiveButton(L.t("שמירה", "Save")) { _, _ ->
                val t = field.text.toString().trim()
                if (t.isEmpty() && s.kind != DEDICATION) return@setPositiveButton
                val at = pager.currentItem
                BookStore.update(this, b.id) { x ->
                    when (s.kind) {
                        COVER -> x.copy(title = t.take(80), cover = x.cover.copy(text = t.take(80), ok = false))
                        DEDICATION -> x.copy(dedication = t.take(200), dedicationOk = false)
                        else -> x.copy(pages = x.pages.mapIndexed { k, p -> if (k == s.page - 1) p.copy(text = t.take(700), ok = false) else p })
                    }
                }
                load(at)
            }
            .setNegativeButton(L.t("ביטול", "Cancel"), null)
            .show()
    }

    /** Before redrawing: what should change? (optional, in the parent's words, e.g. "Dad smiling, at the beach"). */
    private fun askRedraw() {
        if (busy) return
        val field = android.widget.EditText(this).apply {
            hint = L.t("למשל: אבא מחייך, בלי משקפיים, בחוף הים", "e.g. Dad smiling, no glasses, at the beach")
            minLines = 2
        }
        val box = FrameLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(field) }
        AlertDialog.Builder(this)
            .setTitle(L.t("מה לשנות בציור?", "What should change?"))
            .setMessage(L.t("לא חובה. אפשר להשאיר ריק ולצייר שוב כמו שהוא.", "Optional. Leave it empty to just draw it again."))
            .setView(box)
            .setPositiveButton(L.t("לצייר מחדש", "Redraw")) { _, _ -> redrawCurrent(wish = field.text.toString()) }
            .setNegativeButton(L.t("ביטול", "Cancel"), null)
            .show()
    }

    private fun redrawCurrent(newCredit: Boolean = false, wish: String = "") {
        val s = sheets.getOrNull(pager.currentItem) ?: return
        val b = book ?: return
        if (busy || s.page < 0) return
        busy = true
        updateBar()
        val at = pager.currentItem
        worker.execute {
            val err = try { BookMaker.Pages.redraw(applicationContext, b.id, s.page, newCredit, wish); null } catch (e: BookMaker.NeedCredit) { "credit" } catch (e: Exception) { e.message ?: "" }
            runOnUiThread {
                busy = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                when (err) {
                    null -> load(at)
                    "credit" -> AlertDialog.Builder(this)
                        .setMessage(L.t("עברו יותר משלוש שעות מאז שהספר התחיל, אז ציור מחדש משתמש בקרדיט נוסף (שמאפשר עוד תיקונים בשלוש השעות הקרובות). להמשיך?", "It's been over three hours since the book started, so redrawing uses another credit (good for more fixes in the next three hours). Continue?"))
                        .setPositiveButton(L.t("כן", "Yes")) { _, _ -> redrawCurrent(true, wish) }
                        .setNegativeButton(L.t("לא", "No"), null).show()
                    else -> { load(at); Toast.makeText(this, err, Toast.LENGTH_LONG).show() }
                }
            }
        }
    }
}

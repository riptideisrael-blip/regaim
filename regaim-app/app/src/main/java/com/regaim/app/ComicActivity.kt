package com.regaim.app

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import java.io.File
import java.util.concurrent.Executors

/**
 * Draws a comic story from one moment or a whole day. Not good enough? Make other versions
 * (another style, another tone, 3 different stories) or the next page, and pick the one you like.
 */
class ComicActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_MOMENT = "moment"
        private const val EXTRA_DAY = "day"

        fun forMoment(ctx: Context, id: String) =
            ctx.startActivity(Intent(ctx, ComicActivity::class.java).putExtra(EXTRA_MOMENT, id))

        fun forDay(ctx: Context, dayKey: String) =
            ctx.startActivity(Intent(ctx, ComicActivity::class.java).putExtra(EXTRA_DAY, dayKey))
    }

    private val worker = Executors.newSingleThreadExecutor()
    private val made = mutableListOf<ComicMaker.Made>()
    private val saved = mutableSetOf<String>()
    private var selected = -1
    private var style = 0
    private var tone = 0
    private var busy = false

    private lateinit var status: TextView
    private lateinit var image: ImageView
    private lateinit var actions: View
    private lateinit var versionsScroll: View
    private lateinit var versionsRow: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_comic)
        status = findViewById(R.id.comicStatus)
        image = findViewById(R.id.comicImage)
        actions = findViewById(R.id.comicActions)
        versionsScroll = findViewById(R.id.versionsScroll)
        versionsRow = findViewById(R.id.versionsRow)
        findViewById<View>(R.id.btnClose).setOnClickListener { finish() }
        findViewById<View>(R.id.btnShare).setOnClickListener { share() }
        findViewById<View>(R.id.btnSaveComic).setOnClickListener { saveSelected(true) }
        findViewById<View>(R.id.btnAgain).setOnClickListener { showOptions() }
        val p = Settings.prefs(this)
        style = p.getInt("comic_style", 0)
        tone = p.getInt("comic_tone", 0)
        if (!GeminiClient.hasKey(this) && style != ComicStyles.REAL) style = 0
        build(ComicMaker.Options(style, tone, 0), 1)
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun moments(): List<Memory> {
        val all = MemoryStore.kept(this)
        intent.getStringExtra(EXTRA_MOMENT)?.let { id -> return all.filter { it.id == id } }
        val day = intent.getStringExtra(EXTRA_DAY) ?: return emptyList()
        return all.filter { it.dayKey == day }
    }

    private fun build(opt: ComicMaker.Options, versions: Int) {
        if (busy) return
        val items = moments()
        if (items.isEmpty()) {
            status.visibility = View.VISIBLE
            status.text = L.t("לא נמצאו רגעים לקומיקס", "No moments found for a comic")
            return
        }
        busy = true
        status.visibility = View.VISIBLE
        status.text = if (GeminiClient.hasKey(this) && opt.style != ComicStyles.REAL) L.t("מצייר קומיקס באיור AI...\nזה לוקח בערך חצי דקה לפאנל", "Drawing your comic with AI...\nThis takes about 30 seconds per panel")
        else L.t("מצייר את הקומיקס...", "Drawing your comic...")
        image.alpha = 0.25f
        actions.visibility = View.GONE
        val previous = made.filter { it.page < opt.page }.distinctBy { it.page }.map { it.title }
        worker.execute {
            val result = try {
                Result.success(ComicMaker.make(applicationContext, items, opt, versions, previous) { msg ->
                    runOnUiThread { if (!isFinishing) status.text = msg }
                })
            } catch (e: Throwable) {
                Result.failure(e)
            }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                busy = false
                image.alpha = 1f
                actions.visibility = if (made.isEmpty() && result.isFailure) View.GONE else View.VISIBLE
                result.onSuccess { list ->
                    made.addAll(list)
                    status.visibility = View.GONE
                    select(made.size - list.size)
                    renderVersions()
                    if (list.size > 1) Toast.makeText(this, L.t("נוצרו ${list.size} גרסאות. בחר את זאת שאתה אוהב למטה", "${list.size} versions ready. Pick your favorite below"), Toast.LENGTH_LONG).show()
                    if (!ClaudeClient.hasKey(this)) Toast.makeText(this, L.t("עם מפתח Claude הקומיקס יקבל גם סיפור וטקסטים", "Add a Claude key to give your comic a story and captions"), Toast.LENGTH_LONG).show()
                }.onFailure { e ->
                    status.text = L.t("לא הצלחתי לצייר: ${e.message}", "Couldn't draw the comic: ${e.message}")
                }
            }
        }
    }

    private fun select(i: Int) {
        val m = made.getOrNull(i) ?: return
        selected = i
        image.setImageBitmap(BitmapFactory.decodeFile(m.file.absolutePath))
        renderVersions()
    }

    /** Small thumbnails of every version made here; tap one to pick it. */
    private fun renderVersions() {
        versionsRow.removeAllViews()
        versionsScroll.visibility = if (made.size > 1) View.VISIBLE else View.GONE
        val d = resources.displayMetrics.density
        made.forEachIndexed { i, m ->
            val opts = BitmapFactory.Options().apply { inSampleSize = 8 }
            val thumb = ImageView(this).apply {
                setImageBitmap(BitmapFactory.decodeFile(m.file.absolutePath, opts))
                scaleType = ImageView.ScaleType.CENTER_CROP
                val pad = (3 * d).toInt()
                setPadding(pad, pad, pad, pad)
                background = GradientDrawable().apply {
                    cornerRadius = 8 * d
                    setColor(if (i == selected) 0xFFFFE9A8.toInt() else 0x33FFFFFF)
                }
                layoutParams = LinearLayout.LayoutParams((54 * d).toInt(), (96 * d).toInt()).apply { marginEnd = (8 * d).toInt() }
                setOnClickListener { select(i) }
            }
            val label = TextView(this).apply {
                text = (if (m.page > 0) L.t("עמ׳ ${m.page + 1}", "Page ${m.page + 1}") else L.t("גרסה ${i + 1}", "Version ${i + 1}")) + if (m.file.absolutePath in saved) " ✓" else ""
                textSize = 11f
                setTextColor(0xFFE4ECF6.toInt())
            }
            versionsRow.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(thumb)
                addView(label)
            })
        }
    }

    private fun showOptions() {
        if (busy) return
        val ui = Ui(this)
        val hasGemini = GeminiClient.hasKey(this)
        val hasClaude = ClaudeClient.hasKey(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(20), ui.dp(8), ui.dp(20), ui.dp(8))
        }
        val dialog = AlertDialog.Builder(this).setTitle(L.t("גרסה אחרת", "Another version")).setView(ScrollView(this).apply { addView(box) })
            .setNegativeButton(L.t("סגור", "Close"), null).create()

        box.addView(ui.text(L.t("סגנון הציור", "Art style"), 14f, R.color.ink_soft))
        val styleIdx = if (hasGemini) ComicStyles.NAMES.indices.toList() else listOf(0, ComicStyles.REAL)
        box.addView(ui.chips(styleIdx.map { ComicStyles.NAMES[it] }, styleIdx.indexOf(style).coerceAtLeast(0), top = 4) { style = styleIdx[it] })
        if (!hasGemini) box.addView(ui.hint(L.t("עוד סגנונות ציור (ספר ילדים, מנגה, אנימציה) עם מפתח Gemini בהגדרות.", "Add a Gemini key in Settings for more art styles (picture book, manga, animation).")))

        if (hasClaude) {
            box.addView(ui.text(L.t("איך לספר", "Storytelling style"), 14f, R.color.ink_soft, top = 14))
            box.addView(ui.chips(ComicStyles.TONES, tone, top = 4) { tone = it })
        }

        val page = made.getOrNull(selected)?.page ?: 0
        fun go(opt: ComicMaker.Options, versions: Int) {
            Settings.prefs(this).edit().putInt("comic_style", style).putInt("comic_tone", tone).apply()
            dialog.dismiss()
            build(opt, versions)
        }
        box.addView(ui.button(L.t("צייר גרסה חדשה", "Draw a new version"), primary = true, top = 18) { go(ComicMaker.Options(style, tone, page), 1) })
        if (hasClaude) {
            box.addView(ui.button(L.t("3 סיפורים שונים לבחירה", "3 different stories to choose from")) { go(ComicMaker.Options(style, tone, page), 3) })
            box.addView(ui.hint(L.t("אותם ציורים, 3 כותרות וטקסטים אחרים. זול ומהיר.", "Same drawings, 3 different titles and captions. Quick and cheap."), top = 2))
        }
        val nextPage = (made.maxOfOrNull { it.page } ?: 0) + 1
        box.addView(ui.button(L.t("+ עמוד ${nextPage + 1}: המשך הסיפור", "+ Page ${nextPage + 1}: continue the story")) { go(ComicMaker.Options(style, tone, nextPage), 1) })
        box.addView(ui.hint(if (moments().size > 1) L.t("העמוד הבא משתמש ברגעים אחרים מהיום, כשיש.", "The next page uses other moments from today, when there are any.") else L.t("העמוד הבא לוקח רגעים אחרים מתוך הקליפ.", "The next page uses other moments from the clip."), top = 2))
        dialog.show()
    }

    private fun saveSelected(toast: Boolean): File? {
        val m = made.getOrNull(selected) ?: return null
        if (m.file.absolutePath !in saved) {
            ComicMaker.saveToGalleryPublic(this, m.file)
            saved.add(m.file.absolutePath)
            renderVersions()
            if (toast) Toast.makeText(this, L.t("נשמר בגלריה, בתיקייה Pictures/Moments", "Saved to your gallery in Pictures/Moments"), Toast.LENGTH_SHORT).show()
        } else if (toast) {
            Toast.makeText(this, L.t("כבר שמור בגלריה", "Already saved to your gallery"), Toast.LENGTH_SHORT).show()
        }
        return m.file
    }

    private fun share() {
        val f = saveSelected(false) ?: return
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.files", f)
            val send = Intent(Intent.ACTION_SEND)
                .setType("image/png")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(send, L.t("לשתף את הקומיקס עם...", "Share comic with...")))
        } catch (e: Exception) {
            Toast.makeText(this, L.t("הקומיקס נשמר בגלריה, בתיקייה Moments", "Comic saved to your gallery in the Moments folder"), Toast.LENGTH_LONG).show()
        }
    }
}

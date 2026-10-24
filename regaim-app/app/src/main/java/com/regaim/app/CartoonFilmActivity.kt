package com.regaim.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.annotation.OptIn
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.media3.common.util.UnstableApi
import java.io.File
import java.util.concurrent.Executors

/**
 * Premium: a moment as a 3D animated film, in three clear steps:
 * choose the sound → approve how the characters look → the film is made (you can leave; it's saved to the archive).
 */
@OptIn(markerClass = [UnstableApi::class])
class CartoonFilmActivity : AppCompatActivity() {
    companion object {
        private const val EXTRA = "moment"
        private const val MUSIC_KEY = "cartoon_music"
        private const val VOICES_KEY = "cartoon_voices"
        private const val CONSENT = "cartoon_consent"
        fun open(ctx: Context, id: String) = ctx.startActivity(Intent(ctx, CartoonFilmActivity::class.java).putExtra(EXTRA, id))
    }

    private lateinit var ui: Ui
    private lateinit var content: LinearLayout
    private lateinit var memory: Memory
    private var mood: Music.Mood? = Music.Mood.WARM
    private var voices = true
    private var session: CartoonFilm.Session? = null
    private var progressBar: ProgressBar? = null
    private var statusText: TextView? = null
    private var shownState: CartoonFilm.Session.State? = null
    private var ring: ProgressRing? = null
    private val ticker = android.os.Handler(android.os.Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            val s = session ?: return
            ring?.let { r -> r.percent = s.estimate(); r.invalidate() }
            if (s.state == CartoonFilm.Session.State.PREPARING || s.state == CartoonFilm.Session.State.FILMING) ticker.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_screen)
        findViewById<TextView>(R.id.screenTitle).text = L.t("סרט מצויר בתלת-ממד", "3D animated film")
        findViewById<View>(R.id.btnClose).setOnClickListener { finish() }
        content = findViewById(R.id.screenContent)
        ui = Ui(this)
        memory = MemoryStore.get(this, intent.getStringExtra(EXTRA) ?: "") ?: run { finish(); return }
        val prefs = Settings.prefs(this)
        if (prefs.contains(MUSIC_KEY)) mood = Music.choice(this, MUSIC_KEY)
        voices = prefs.getBoolean(VOICES_KEY, true)
        // Coming back to a film that's still being made for this moment.
        session = CartoonFilm.current?.takeIf { it.memory.id == memory.id }
        render()
    }

    override fun onResume() {
        super.onResume()
        session?.listener = { onSessionChanged() }
        if (::content.isInitialized) render()
    }

    override fun onPause() {
        super.onPause()
        session?.listener = null
        ticker.removeCallbacks(tick)
    }

    override fun onDestroy() {
        // Leaving does NOT stop the film: it keeps going and is saved to the archive when ready.
        Music.stop()
        super.onDestroy()
    }

    /** Small changes (status text, progress) update in place; a new state redraws the screen. */
    private fun onSessionChanged() {
        val s = session ?: return
        if (isFinishing || isDestroyed) return
        if (s.state != shownState) { render(); return }
        statusText?.text = s.status
        progressBar?.let { if (s.progress in 0..100) { it.isIndeterminate = false; it.progress = s.progress } }
    }

    private fun render() {
        content.removeAllViews()
        progressBar = null; statusText = null; ring = null
        ticker.removeCallbacks(tick)
        val s = session
        shownState = s?.state
        content.addView(ui.text(L.t("הרגע הזה, כסרט אנימציה", "This moment, as an animated film"), 19f, bold = true, top = 14))

        when (s?.state) {
            null, CartoonFilm.Session.State.CANCELLED -> renderStart()
            CartoonFilm.Session.State.PREPARING -> renderWorking(s, L.t("שלב 1 מתוך 2: איך הדמויות ייראו", "Step 1 of 2: how the characters look"), cancellable = true)
            CartoonFilm.Session.State.LOOK -> renderLook(s)
            CartoonFilm.Session.State.FILMING -> renderWorking(s, L.t("שלב 2 מתוך 2: הסרט בהכנה", "Step 2 of 2: making the film"), cancellable = true)
            CartoonFilm.Session.State.DONE -> renderResult(s)
            CartoonFilm.Session.State.FAILED -> renderFailed(s)
        }
    }

    private fun intro() = L.t(
        "אנחנו מציירים את הרגע מחדש כדמויות אנימציה בתלת-ממד, בסגנון סרטי פיקסאר: סרט של ${CartoonFilm.seconds(memory)} שניות, עם הקולות מהרגע עצמו. קודם תאשרו איך הדמויות נראות, ורק אז נכין את הסרט. אפשר להאריך אחר כך.",
        "We redraw the moment as 3D animated characters, Pixar-movie style: a ${CartoonFilm.seconds(memory)}-second film, with the moment's own sound. First you approve how the characters look, then we make the film. You can make it longer afterwards.")

    private fun renderStart() {
        content.addView(ui.hint(intro(), top = 6))
        val musicRow = ui.action("") { }
        musicRow.text = L.t("מוזיקה ברקע: ${mood?.title ?: "ללא"} ›", "Background music: ${mood?.title ?: "none"} ›")
        musicRow.setOnClickListener {
            Music.chooser(this, mood) { m -> mood = m; Music.setChoice(this, m, MUSIC_KEY); render() }
        }
        content.addView(musicRow.apply { setPadding(0, ui.dp(16), 0, ui.dp(4)) })
        if (memory.isVideo) content.addView(ui.switch(L.t("הקולות מהרגע (צחוקים, דיבור)", "The moment's own sound (laughs, voices)"), voices, top = 6) { on ->
            voices = on; Settings.prefs(this).edit().putBoolean(VOICES_KEY, on).apply()
        })
        // How many films are left (the server decides; no developer switch in the app).
        val left = ui.hint("", top = 14)
        content.addView(left)
        Executors.newSingleThreadExecutor().execute {
            val n = CartoonFilm.credits(applicationContext)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                left.text = when {
                    n == null -> ""
                    n <= 0 -> L.t("נגמרו הסרטים בחבילה שלכם החודש.", "You've used all the films in your plan this month.")
                    n == 1 -> L.t("נשאר לכם סרט אחד.", "You have 1 film left.")
                    else -> L.t("נשארו לכם $n סרטים.", "You have $n films left.")
                }
            }
        }
        content.addView(ui.button(L.t("ציירו את הדמויות לאישור", "Draw the characters for approval"), primary = true, top = 18) { confirmStart() })
        content.addView(ui.hint(L.t("ציור הדמויות לוקח כחצי דקה. תמונות מהרגע נשלחות לשירות ציור AI דרך השרת שלנו, רק לצורך הסרט הזה.", "Drawing the characters takes about 30 seconds. Frames from the moment are sent to an AI drawing service through our server, only for this film."), top = 6))
    }

    private fun renderWorking(s: CartoonFilm.Session, step: String, cancellable: Boolean) {
        content.addView(ui.text(step, 16f, R.color.accent_dark, bold = true, top = 18))
        // A circle that fills up, with the picture it's working on in the middle.
        val center = if (s.state == CartoonFilm.Session.State.FILMING) s.look ?: s.preview else s.preview
        ring = ProgressRing(this, center).apply {
            percent = s.estimate()
            val size = (resources.displayMetrics.widthPixels * 0.62f).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size).apply { topMargin = ui.dp(18); gravity = android.view.Gravity.CENTER_HORIZONTAL }
        }
        content.addView(ring)
        statusText = ui.text(s.status, 15f, top = 14).apply { gravity = android.view.Gravity.CENTER }
        content.addView(statusText)
        ticker.post(tick)
        if (s.state == CartoonFilm.Session.State.FILMING)
            content.addView(ui.hint(L.t("זה לוקח כמה דקות. אפשר לצאת מהמסך: הסרט ימשיך להיווצר ויישמר בארכיון, ליד הרגע המקורי.", "This takes a few minutes. You can leave: the film keeps going and is saved to your archive, next to the original moment."), top = 10))
        if (cancellable) content.addView(ui.action(L.t("ביטול", "Cancel"), R.color.danger) {
            AlertDialog.Builder(this).setTitle(L.t("לבטל את הסרט?", "Cancel the film?"))
                .setMessage(L.t("הקרדיט יחזור אליכם.", "Your credit will be returned."))
                .setPositiveButton(L.t("ביטול הסרט", "Cancel film")) { _, _ -> s.cancel(); session = null; render() }
                .setNegativeButton(L.t("המשך", "Keep going"), null).show()
        }.apply { setPadding(0, ui.dp(16), 0, 0) })
    }

    private fun renderLook(s: CartoonFilm.Session) {
        content.addView(ui.text(L.t("שלב 1 מתוך 2: ככה הדמויות ייראו בסרט", "Step 1 of 2: this is how the characters will look"), 16f, R.color.accent_dark, bold = true, top = 18))
        val bmp = s.look
        if (bmp != null) {
            val box = FrameLayout(this).apply {
                setBackgroundResource(R.drawable.rounded_media)
                clipToOutline = true
                val h = (resources.displayMetrics.heightPixels * 0.5f).toInt()
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h).apply { topMargin = ui.dp(10) }
            }
            box.addView(ImageView(this).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                setImageBitmap(bmp)
                layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            })
            content.addView(box)
        }
        val all = s.looks
        if (all.size > 1) {
            content.addView(ui.hint(L.t("הציורים שנעשו עד עכשיו. לחצו על זה שהכי אהבתם:", "The drawings so far. Tap the one you like most:"), top = 10))
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            all.forEachIndexed { i, b ->
                row.addView(ImageView(this).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setImageBitmap(b)
                    setBackgroundResource(R.drawable.rounded_media)
                    clipToOutline = true
                    alpha = if (i == s.chosenLook) 1f else 0.55f
                    setPadding(if (i == s.chosenLook) ui.dp(3) else 0, if (i == s.chosenLook) ui.dp(3) else 0, if (i == s.chosenLook) ui.dp(3) else 0, if (i == s.chosenLook) ui.dp(3) else 0)
                    background = android.graphics.drawable.GradientDrawable().apply {
                        cornerRadius = ui.dp(12).toFloat()
                        setColor(if (i == s.chosenLook) ui.color(R.color.accent) else 0x00000000)
                    }
                    layoutParams = LinearLayout.LayoutParams(ui.dp(84), ui.dp(112)).apply { marginEnd = ui.dp(8) }
                    setOnClickListener { s.chooseLook(i); render() }
                })
            }
            content.addView(ui.scrollRow(row, top = 6))
        }
        content.addView(ui.hint(L.t("כל הסצנות בסרט יצוירו לפי הדמויות האלה: אותן פנים, גיל, שיער ובגדים.", "Every scene will be drawn like these characters: same faces, age, hair and clothes."), top = 8))
        val who = s.refNames
        content.addView(ui.hint(
            if (who.isNotEmpty()) L.t("השתמשנו בתמונת הפרופיל של ${who.joinToString(" ו")} כדי שהדמות תהיה דומה.", "We used ${who.joinToString(" and ")}'s profile photo so the character looks like them.")
            else L.t("טיפ: כדי שהדמות תהיה דומה יותר, הוסיפו תמונת פנים ברורה לילד בפרופיל (פרופיל ← המשפחה) ותייגו אותו ברגע.", "Tip: for a closer likeness, add a clear face photo of your child in the profile (Profile → Family) and tag them in the moment."),
            top = 6))
        content.addView(ui.button(L.t("נראה מעולה, צרו את הסרט", "Looks great, make the film"), primary = true, top = 14) {
            s.makeFilm(mood, voices && memory.isVideo)
        })
        if (s.redrawsLeft > 0) content.addView(ui.button(L.t("ציירו שוב (עוד ${s.redrawsLeft})", "Draw again (${s.redrawsLeft} left)"), top = 6) { s.redraw() })
        content.addView(ui.action(L.t("ביטול (הקרדיט יחזור)", "Cancel (credit returned)"), R.color.danger) { s.cancel(); session = null; render() }
            .apply { setPadding(0, ui.dp(14), 0, 0) })
    }

    private fun renderResult(s: CartoonFilm.Session) {
        val file = s.result
        content.addView(ui.text(L.t("הסרט מוכן · ${s.filmSeconds} שניות", "Your film is ready · ${s.filmSeconds} seconds"), 18f, bold = true, top = 16))
        if (s.note.isNotEmpty()) content.addView(ui.hint(s.note, top = 4))
        if (file != null && file.exists()) {
            // The frame takes the film's own shape, so the video fills it with no empty side.
            val (vw, vh) = videoSize(file)
            val boxW = resources.displayMetrics.widthPixels - ui.dp(40)
            val boxH = (boxW.toFloat() * vh / vw.coerceAtLeast(1)).toInt().coerceAtMost((resources.displayMetrics.heightPixels * 0.65f).toInt())
            val box = FrameLayout(this).apply {
                setBackgroundColor(0xFF1E1E1E.toInt())
                background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = ui.dp(18).toFloat(); setColor(0xFF1E1E1E.toInt()) }
                clipToOutline = true
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, boxH).apply { topMargin = ui.dp(10) }
            }
            box.addView(VideoView(this).apply {
                layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT, android.view.Gravity.CENTER)
                setVideoPath(file.absolutePath)
                setOnPreparedListener { it.isLooping = true; start() }
            })
            content.addView(box)
        }
        val savedId = s.savedId
        content.addView(ui.hint(
            if (savedId != null) L.t("הסרט נשמר בגלריה (Movies/Moments) ובארכיון, ליד הרגע המקורי.", "Saved to your gallery (Movies/Moments) and to your archive, next to the original moment.")
            else L.t("לא הצלחנו לשמור את הסרט אוטומטית. אפשר לשמור בכפתור למטה.", "We couldn't save the film automatically. Use the button below."), top = 10))
        if (file != null && file.exists()) content.addView(ui.button(L.t("שתף", "Share"), primary = true, top = 14) { share(file) })
        if (savedId != null) content.addView(ui.button(L.t("פתח את הסרט ברגעים", "Open the film in Moments"), top = 6) { MomentActivity.open(this, savedId) })
        else if (file != null && file.exists()) content.addView(ui.button(L.t("שמור בגלריה", "Save to gallery"), top = 6) { save(file) })
        // Longer films are a paid extra: one more credit adds 15 seconds (two more scenes from the same moment).
        if (memory.isVideo) {
            content.addView(ui.button(L.t("הארכה ב-${CartoonFilm.EXTEND_SECONDS} שניות · קרדיט אחד", "Make it ${CartoonFilm.EXTEND_SECONDS} seconds longer · 1 credit"), top = 10) {
                AlertDialog.Builder(this)
                    .setTitle(L.t("להאריך את הסרט?", "Make the film longer?"))
                    .setMessage(L.t("נוסיף עוד ${CartoonFilm.EXTEND_SECONDS} שניות מרגעים אחרים בסרטון, עם אותן דמויות. זה משתמש בקרדיט אחד נוסף. אם זה לא יצליח, הקרדיט יוחזר.", "We'll add ${CartoonFilm.EXTEND_SECONDS} more seconds from other spots in the clip, with the same characters. This uses one more credit. If it doesn't work, the credit is returned."))
                    .setPositiveButton(L.t("הארכה", "Extend")) { _, _ -> s.extend(); render() }
                    .setNegativeButton(L.t("ביטול", "Cancel"), null)
                    .show()
            })
            s.extendError?.let { content.addView(ui.hint(it, top = 4)) }
        }
        content.addView(ui.action(L.t("סרט חדש מהרגע הזה", "A new film from this moment")) { session = null; render() }.apply { setPadding(0, ui.dp(14), 0, 0) })
    }

    /** Width and height of a video as it is shown (rotation applied). */
    private fun videoSize(f: File): Pair<Int, Int> {
        val r = android.media.MediaMetadataRetriever()
        return try {
            r.setDataSource(f.absolutePath)
            val w = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 720
            val h = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 1280
            val rot = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rot == 90 || rot == 270) h to w else w to h
        } catch (_: Exception) { 720 to 1280 } finally { try { r.release() } catch (_: Exception) {} }
    }

    private fun renderFailed(s: CartoonFilm.Session) {
        content.addView(ui.text(L.t("הסרט לא הצליח הפעם", "The film didn't work this time"), 17f, bold = true, top = 18))
        content.addView(ui.hint(s.error ?: L.t("משהו השתבש", "Something went wrong"), top = 6))
        content.addView(ui.hint(L.t("אם הקרדיט נלקח, הוא הוחזר.", "If a credit was used, it was returned."), top = 4))
        content.addView(ui.button(L.t("נסו שוב", "Try again"), primary = true, top = 16) { session = null; render() })
    }

    private fun confirmStart() {
        val prefs = Settings.prefs(this)
        if (prefs.getBoolean(CONSENT, false)) { start(); return }
        AlertDialog.Builder(this)
            .setTitle(L.t("לשלוח תמונות ל-AI?", "Send frames to AI?"))
            .setMessage(L.t("כדי לצייר את הסרט נשלח כמה תמונות מהרגע (שעלולות לכלול פנים, גם של ילדים) לשירות ציור והנפשה מבוסס AI, דרך השרת שלנו. נשלח רק מה שנדרש לסרט הזה. להמשיך?", "To draw the film we send a few frames from the moment (which may include faces, including children's) to an AI drawing and animation service, through our server. Only what this film needs is sent. Continue?"))
            .setPositiveButton(L.t("המשך", "Continue")) { _, _ -> prefs.edit().putBoolean(CONSENT, true).apply(); start() }
            .setNegativeButton(L.t("ביטול", "Cancel"), null)
            .show()
    }

    private fun start() {
        val running = CartoonFilm.current
        if (running != null && running.isActive && running.memory.id != memory.id) {
            Toast.makeText(this, L.t("כבר נוצר סרט מרגע אחר. אפשר להתחיל סרט חדש כשהוא יסתיים.", "Another film is already being made. Start a new one when it's done."), Toast.LENGTH_LONG).show()
            return
        }
        val s = CartoonFilm.start(this, memory)
        session = s
        s.listener = { onSessionChanged() }
        render()
    }

    private fun share(file: File) {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
            val send = Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM, uri)
                .putExtra(Intent.EXTRA_TEXT, L.t("נעשה עם Moments 💜", "Made with Moments 💜"))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(send, L.t("לשתף עם...", "Share with...")).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (_: Exception) {
            Toast.makeText(this, L.t("לא הצלחנו לפתוח את השיתוף", "Couldn't open sharing"), Toast.LENGTH_SHORT).show()
        }
    }

    private fun save(file: File) {
        Executors.newSingleThreadExecutor().execute {
            val ok = try { CartoonFilm.keep(applicationContext, memory, file); true } catch (_: Exception) { false }
            runOnUiThread {
                Toast.makeText(this, if (ok) L.t("נשמר בגלריה ובארכיון", "Saved to your gallery and archive") else L.t("לא הצלחנו לשמור", "Couldn't save"), Toast.LENGTH_LONG).show()
            }
        }
    }
}

/** A round progress indicator: a ring that fills clockwise around a circular picture, with the percent in the middle. */
private class ProgressRing(ctx: Context, private val picture: android.graphics.Bitmap?) : View(ctx) {
    var percent = 0
    private val d = ctx.resources.displayMetrics.density
    private val track = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE; strokeWidth = 10 * d; color = 0x22000000
    }
    private val arc = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE; strokeWidth = 10 * d; strokeCap = android.graphics.Paint.Cap.ROUND
        color = androidx.core.content.ContextCompat.getColor(ctx, R.color.accent)
    }
    private val shade = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0x66000000 }
    private val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF0E6DC.toInt() }
    private val text = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = android.graphics.Paint.Align.CENTER; textSize = 30 * d; isFakeBoldText = true
    }
    private val picPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG)
    private val box = android.graphics.RectF()

    override fun onDraw(c: android.graphics.Canvas) {
        super.onDraw(c)
        val pad = 8 * d
        box.set(pad, pad, width - pad, height - pad)
        val cx = width / 2f; val cy = height / 2f
        val r = minOf(box.width(), box.height()) / 2f - 10 * d
        if (picture != null) {
            val shader = android.graphics.BitmapShader(picture, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP)
            val scale = maxOf(2 * r / picture.width, 2 * r / picture.height)
            shader.setLocalMatrix(android.graphics.Matrix().apply {
                postScale(scale, scale)
                postTranslate(cx - picture.width * scale / 2f, cy - picture.height * scale / 2f)
            })
            picPaint.shader = shader
            c.drawCircle(cx, cy, r, picPaint)
            c.drawCircle(cx, cy, r, shade)
            text.color = 0xFFFFFFFF.toInt()
        } else {
            c.drawCircle(cx, cy, r, fill)
            text.color = androidx.core.content.ContextCompat.getColor(context, R.color.ink)
        }
        c.drawArc(box, 0f, 360f, false, track)
        c.drawArc(box, -90f, 360f * percent.coerceIn(0, 100) / 100f, false, arc)
        c.drawText("$percent%", cx, cy - (text.descent() + text.ascent()) / 2f, text)
    }
}

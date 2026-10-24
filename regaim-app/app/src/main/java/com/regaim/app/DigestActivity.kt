package com.regaim.app

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.media3.common.util.UnstableApi
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

/** The week's digest for the grandparents: what to send, and how it looks. */
object Digest {
    /** The last 7 days, without films made in the Studio. */
    fun moments(ctx: Context): List<Memory> =
        PeriodVideo.moments(ctx, PeriodVideo.Period.THIS_WEEK).filter { it.origin != "film" }

    /**
     * "אורי ונעם": all the family's kids, by first name. (It used to be only the kids recognized in the week's
     * moments, so a little one the app doesn't know by face yet was left out of the family's own message.)
     */
    fun who(ctx: Context, week: List<Memory>): String {
        val seen = Profile.kids(ctx).map { PitzkiScript.firstName(it.name) }.filter { it.isNotBlank() }.distinct()
        return when (seen.size) {
            0 -> ""
            1 -> seen[0]
            else -> seen.dropLast(1).joinToString(", ") + L.t(" ו", " and ") + seen.last()
        }
    }

    fun message(ctx: Context, week: List<Memory>): String {
        val who = who(ctx, week)
        val picks = PeriodVideo.pick(week)
        val lines = ReelMaker.best(week).take(3).map { "• " + Fmt.title(it) }
        return buildString {
            append(L.t("שבת שלום! 💛", "Happy Friday! 💛")).append('\n')
            append(
                if (who.isNotEmpty()) L.t("השבוע של $who, ב-${week.size} רגעים:", "$who's week, in ${week.size} moments:")
                else L.t("השבוע שלנו, ב-${week.size} רגעים:", "Our week, in ${week.size} moments:")
            ).append('\n')
            lines.forEach { append(it).append('\n') }
            if (picks.isNotEmpty()) append('\n').append(L.t("מתגעגעים ❤️", "Miss you ❤️"))
        }.trim()
    }

    /** A 1080×1350 collage of up to 6 moments, with a title. Background thread only. */
    fun collage(ctx: Context, week: List<Memory>): File? {
        val picks = ReelMaker.best(week).take(6).sortedBy { it.time }
        val covers = picks.mapNotNull { m -> try { Covers.get(ctx, m) } catch (_: Throwable) { null } }
        if (covers.isEmpty()) return null
        val w = 1080; val h = 1350
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.WHITE)
        val pad = 48f
        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(18, 18, 18); textAlign = Paint.Align.CENTER; textSize = 64f; typeface = Fonts.display
        }
        val sub = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(115, 115, 115); textAlign = Paint.Align.CENTER; textSize = 36f
        }
        val who = who(ctx, week)
        c.drawText(if (who.isNotEmpty()) L.t("השבוע של $who", "$who's week") else L.t("השבוע שלנו", "Our week"), w / 2f, 120f, title)
        val f = java.text.SimpleDateFormat("d.M", L.locale)
        c.drawText("${f.format(java.util.Date(week.minOf { it.time }))} – ${f.format(java.util.Date(week.maxOf { it.time }))}", w / 2f, 176f, sub)

        val top = 220f
        val bottom = h - 120f
        val cols = if (covers.size == 1) 1 else 2
        val rows = (covers.size + cols - 1) / cols
        val gap = 16f
        val cellW = (w - pad * 2 - gap * (cols - 1)) / cols
        val cellH = (bottom - top - gap * (rows - 1)) / rows
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        covers.forEachIndexed { i, b ->
            val col = i % cols; val row = i / cols
            // RTL order: the first moment on the right.
            val x = if (L.isEn) pad + col * (cellW + gap) else w - pad - (col + 1) * cellW - col * gap
            val y = top + row * (cellH + gap)
            val dst = RectF(x, y, x + cellW, y + cellH)
            val scale = maxOf(cellW / b.width, cellH / b.height)
            val sw = cellW / scale; val sh = cellH / scale
            val sx = ((b.width - sw) / 2f).toInt(); val sy = ((b.height - sh) / 2f).toInt()
            val src = Rect(sx, sy, (sx + sw).toInt(), (sy + sh).toInt())
            c.save()
            c.clipPath(Path().apply { addRoundRect(dst, 24f, 24f, Path.Direction.CW) })
            c.drawBitmap(b, src, dst, paint)
            c.restore()
        }
        val mark = SocialShare.badge(ctx, 300)
        c.drawBitmap(mark, (w - mark.width) / 2f, h - 60f - mark.height / 2f, null)
        val dir = File(ctx.cacheDir, "share").apply { mkdirs() }
        val file = File(dir, "digest_${System.currentTimeMillis()}.jpg")
        return try { FileOutputStream(file).use { out.compress(Bitmap.CompressFormat.JPEG, 90, it) }; file } catch (_: Exception) { null }
    }

    /** WhatsApp chat id from a phone number the family typed ("050-1234567" → 972501234567@s.whatsapp.net). */
    fun jid(phone: String): String? {
        var d = phone.filter { it.isDigit() }
        if (d.isEmpty()) return null
        if (d.startsWith("00")) d = d.drop(2)
        if (d.startsWith("0")) d = "972" + d.drop(1)
        return if (d.length in 10..15) "$d@s.whatsapp.net" else null
    }
}

/**
 * "סיכום לסבא וסבתא": every Friday the week's best moments are ready to send on WhatsApp, as a short video or a collage,
 * with a few warm lines. Nothing is sent without the parent pressing send.
 */
@OptIn(markerClass = [UnstableApi::class])
class DigestActivity : AppCompatActivity() {
    companion object {
        fun open(ctx: Context) = ctx.startActivity(Intent(ctx, DigestActivity::class.java))
    }

    private lateinit var ui: Ui
    private lateinit var mv: MemoryViews
    private lateinit var content: LinearLayout
    private var message: EditText? = null
    private var handle: PeriodVideo.Handle? = null
    private var progress: ProgressBar? = null
    private var progressText: TextView? = null
    private var busy = false
    private val worker = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_screen)
        findViewById<TextView>(R.id.screenTitle).text = L.t("סיכום לסבא וסבתא", "Grandparents' digest")
        findViewById<View>(R.id.btnClose).setOnClickListener { finish() }
        content = findViewById(R.id.screenContent)
        ui = Ui(this)
        mv = MemoryViews(this)
        render()
    }

    override fun onDestroy() {
        if (busy) handle?.cancel?.invoke()
        super.onDestroy()
    }

    private fun render() {
        content.removeAllViews()
        progress = null; progressText = null
        val week = Digest.moments(this)
        content.addView(ui.hint(L.t(
            "כל יום שישי בבוקר מוכן לכם סיכום של השבוע לשלוח לסבא וסבתא בוואטסאפ. אתם רואים, מתקנים ושולחים בלחיצה.",
            "Every Friday morning the week's digest is ready to send to the grandparents on WhatsApp. You check it, edit it and send.",
        ), top = 12))

        if (week.size < 2) {
            content.addView(ui.text(L.t("השבוע עוד אין מספיק רגעים", "Not enough moments this week yet"), 17f, bold = true, top = 24))
            content.addView(ui.hint(L.t("צריך לפחות שני רגעים מהשבוע האחרון.", "You need at least two moments from the last 7 days."), top = 4))
        } else {
            val picks = PeriodVideo.pick(week)
            content.addView(ui.text(L.t("${week.size} רגעים השבוע", "${week.size} moments this week"), 17f, bold = true, top = 20))
            val strip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            picks.forEach { m -> strip.addView(mv.mini(m) { MomentActivity.open(this, m.id) }) }
            content.addView(ui.scrollRow(strip, top = 10))

            content.addView(ui.text(L.t("ההודעה", "The message"), 15f, bold = true, top = 20))
            val field = ui.field(L.t("מה לכתוב לסבא וסבתא", "What to write"), Digest.message(this, week), multiline = true, top = 6)
            message = field
            content.addView(field)

            if (busy) {
                progressText = ui.text(L.t("מכינים את הסרטון... כדקה. אפשר להישאר כאן.", "Making the video… about a minute. Stay on this screen."), 15f, top = 16)
                content.addView(progressText)
                progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                    max = 100; isIndeterminate = true
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(8)).apply { topMargin = ui.dp(10) }
                }
                content.addView(progress)
            } else {
                content.addView(ui.button(L.t("שלחו סרטון של השבוע בוואטסאפ", "Send the week's video on WhatsApp"), primary = true, top = 18) { sendVideo(picks) })
                content.addView(ui.button(L.t("שלחו קולאז׳ תמונות", "Send a photo collage"), top = 8) { sendCollage(week) })
                content.addView(ui.hint(L.t("הסרטון: הרגעים הכי טובים, כ-${PeriodVideo.seconds(picks)} שניות עם מוזיקה. הקולאז׳: תמונה אחת עם עד 6 רגעים.", "Video: the best moments, about ${PeriodVideo.seconds(picks)} seconds with music. Collage: one picture with up to 6 moments."), top = 6))
            }
        }

        // Settings for the digest.
        val card = ui.card(top = 26)
        card.addView(ui.switch(L.t("להזכיר לי בכל יום שישי ב-10:00", "Remind me every Friday at 10:00"), Reminders.digestOn(this), top = 0) { on -> Reminders.setDigest(this, on) })
        card.addView(ui.text(L.t("הוואטסאפ של סבא וסבתא (לא חובה)", "Grandparents' WhatsApp number (optional)"), 14f, bold = true, top = 14))
        val phone = ui.field(L.t("למשל 050-1234567", "e.g. +972 50 123 4567"), Settings.prefs(this).getString(Reminders.KEY_DIGEST_PHONE, "") ?: "", top = 6).apply {
            inputType = android.text.InputType.TYPE_CLASS_PHONE
        }
        ui.onChange = { Settings.putString(this, Reminders.KEY_DIGEST_PHONE, phone.text.toString().trim()) }
        card.addView(phone)
        card.addView(ui.hint(L.t("עם מספר, וואטסאפ נפתח ישר בשיחה איתם. בלי מספר, תבחרו איש קשר או קבוצה (למשל \"המשפחה\").", "With a number WhatsApp opens their chat directly. Without one you pick a contact or a group."), top = 6))
        content.addView(card)
    }

    private fun text() = message?.text?.toString()?.trim().orEmpty()

    private fun sendVideo(picks: List<Memory>) {
        if (busy) return
        busy = true
        val msg = text()
        render()
        message?.setText(msg)
        // The memory editor chooses, orders and scores the week (the same as the period video).
        handle = PeriodVideo.makeAuto(applicationContext, PeriodVideo.Period.THIS_WEEK, Digest.moments(this),
            onProgress = { p -> progress?.let { it.isIndeterminate = false; it.progress = p } },
            onDone = { file, err ->
                busy = false
                if (isFinishing || isDestroyed) return@makeAuto
                val latest = text().ifEmpty { msg }
                render()
                message?.setText(latest)
                if (file == null) Toast.makeText(this, err ?: L.t("משהו השתבש", "Something went wrong"), Toast.LENGTH_LONG).show()
                else send(file, "video/mp4", latest)
            })
    }

    private fun sendCollage(week: List<Memory>) {
        if (busy) return
        val msg = text()
        Toast.makeText(this, L.t("מכינים את הקולאז׳...", "Making the collage…"), Toast.LENGTH_SHORT).show()
        worker.execute {
            val file = Digest.collage(applicationContext, week)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (file == null) Toast.makeText(this, L.t("לא הצלחנו להכין את הקולאז׳", "Couldn't make the collage"), Toast.LENGTH_LONG).show()
                else send(file, "image/jpeg", msg)
            }
        }
    }

    /** Opens WhatsApp with the file and the message (straight into the grandparents' chat if we have their number). */
    private fun send(file: File, type: String, msg: String) {
        val uri: Uri = try { FileProvider.getUriForFile(this, "$packageName.files", file) } catch (_: Exception) {
            Toast.makeText(this, L.t("לא הצלחנו לשתף", "Couldn't share"), Toast.LENGTH_SHORT).show(); return
        }
        fun intent(pkg: String?, jid: String?) = Intent(Intent.ACTION_SEND).setType(type)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_TEXT, msg)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .apply { if (pkg != null) setPackage(pkg); if (jid != null) putExtra("jid", jid) }
        val jid = Digest.jid(Settings.prefs(this).getString(Reminders.KEY_DIGEST_PHONE, "") ?: "")
        for (pkg in listOf("com.whatsapp", "com.whatsapp.w4b")) {
            try { startActivity(intent(pkg, jid)); return } catch (_: ActivityNotFoundException) { } catch (_: SecurityException) { }
        }
        try {
            startActivity(Intent.createChooser(intent(null, null), L.t("לשלוח עם...", "Send with...")).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (_: Exception) {
            Toast.makeText(this, L.t("לא מצאנו אפליקציה לשליחה", "No app to send with"), Toast.LENGTH_SHORT).show()
        }
    }
}

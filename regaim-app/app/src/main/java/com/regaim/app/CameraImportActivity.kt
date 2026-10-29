package com.regaim.app

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * Moments from an external camera (GoPro, Insta360, DJI, Ray-Ban Meta glasses...).
 * The camera's own app moves the videos to the phone; here we listen to each video's sound, find the laughs with the
 * same on-phone model as the live recorder, and cut each one into a ~30 second moment. The original video is untouched.
 */
object CameraImport {
    /** Videos up to this long are kept whole (no cutting needed). */
    const val KEEP_WHOLE_MS = 45_000L
    private const val RATE = 16_000

    /** One scanned video and the moments found in it. */
    class Found(val name: String, val uri: Uri, val durationMs: Long, val takenAt: Long, val dated: Boolean, val scan: MomentScanner.Result?, val error: String? = null)

    /** Background thread only. Finds the moments; saves nothing yet (the family chooses). [progress] 0..100. */
    fun scan(ctx: Context, uri: Uri, cancelled: AtomicBoolean, progress: (Int) -> Unit): Found {
        Settings.load(ctx)
        val name = displayName(ctx, uri)
        val info = info(ctx, uri)
        if (info.durationMs <= 0) return Found(name, uri, 0, 0, false, null, L.t("לא הצלחנו לקרוא את הסרטון", "Couldn't read this video"))
        val r = MomentScanner.scan(ctx, uri, cancelled, progress = progress)
        return Found(name, uri, info.durationMs, info.takenAt, info.dated, r, r.error?.let { L.t("לא הצלחנו לנתח את הסרטון", "Couldn't analyze this video") })
    }

    /**
     * Saves the chosen moments of one video. A short video is kept whole once (marked at its best moment);
     * a long one is cut into a clip per moment, with the lead-up and the after. Background thread only.
     */
    fun save(ctx: Context, f: Found, chosen: List<Candidate>): List<Memory> {
        if (chosen.isEmpty()) return emptyList()
        try { ctx.contentResolver.takePersistableUriPermission(f.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) { }
        val saved = ArrayList<Memory>()
        fun keepWhole(c: Candidate): Memory? = try {
            val m = MediaImporter.add(ctx, f.uri)
            MemoryStore.modify(ctx, m.id) { it.copy(label = c.primary.label, highlightMs = c.peakMs, origin = "camera", chosen = false) }
            Detections.save(ctx, m.id, c, "camera")
            MemoryStore.get(ctx, m.id)
        } catch (_: Exception) { null }
        if (f.durationMs <= KEEP_WHOLE_MS) {
            keepWhole(chosen.maxByOrNull { it.rank }!!)?.let { saved.add(it) }
            return saved
        }
        for (c in chosen.sortedBy { it.startMs }) {
            try {
                val source = Memory(
                    id = UUID.randomUUID().toString(), uri = f.uri.toString(), name = f.name, time = f.takenAt + c.peakMs, label = c.primary.label,
                    lat = null, lon = null, place = "", thumb = "", durationMs = f.durationMs,
                    highlightMs = c.peakMs, mediaType = "video", origin = "import",
                )
                val cut = ClipTrimmer.trim(ctx, source, c.clipStartMs, c.clipEndMs)
                val m = cut.copy(origin = "camera", time = f.takenAt + c.clipStartMs, timeSource = if (f.dated) "captured" else "imported")
                MemoryStore.add(ctx, m)
                Detections.save(ctx, m.id, c, "camera")
                saved.add(m)
            } catch (_: Exception) {
                // Couldn't cut this video (unusual format): keep it whole rather than lose the moment.
                if (saved.isEmpty()) keepWhole(c)?.let { saved.add(it) }
            }
        }
        return saved
    }

    /** Decodes the video's sound to 16 kHz mono floats, chunk by chunk. */
    internal fun decodeMono16k(ctx: Context, uri: Uri, cancelled: AtomicBoolean, onTime: (Long) -> Unit, onChunk: (FloatArray) -> Unit) {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(ctx, uri, null)
            val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                ?: throw IllegalStateException("no audio")
            ex.selectTrack(track)
            val format = ex.getTrackFormat(track)
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var isFloat = false
            val c = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec = c
            c.configure(format, null, null, 0)
            c.start()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var resampler = Resampler(rate)
            while (!outputDone && !cancelled.get()) {
                if (!inputDone) {
                    val inIdx = c.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val inBuf = c.getInputBuffer(inIdx)!!
                        val size = ex.readSampleData(inBuf, 0)
                        if (size < 0) {
                            c.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            c.queueInputBuffer(inIdx, 0, size, ex.sampleTime, 0)
                            onTime(ex.sampleTime / 1000)
                            ex.advance()
                        }
                    }
                }
                val outIdx = c.dequeueOutputBuffer(info, 10_000)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = c.outputFormat
                        rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        isFloat = f.containsKey(MediaFormat.KEY_PCM_ENCODING) && f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                        resampler = Resampler(rate)
                    }
                    outIdx >= 0 -> {
                        if (info.size > 0) {
                            val out = c.getOutputBuffer(outIdx)!!
                            out.position(info.offset); out.limit(info.offset + info.size)
                            val ch = channels.coerceAtLeast(1)
                            val mono: FloatArray = if (isFloat) {
                                val fb = out.order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer()
                                val frames = fb.remaining() / ch
                                FloatArray(frames) { i -> var s = 0f; for (k in 0 until ch) s += fb.get(i * ch + k); s / ch }
                            } else {
                                val sb = out.order(java.nio.ByteOrder.nativeOrder()).asShortBuffer()
                                val frames = sb.remaining() / ch
                                FloatArray(frames) { i -> var s = 0f; for (k in 0 until ch) s += sb.get(i * ch + k); s / ch / 32768f }
                            }
                            val res = resampler.push(mono)
                            if (res.isNotEmpty()) onChunk(res)
                        }
                        c.releaseOutputBuffer(outIdx, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true
                    }
                }
            }
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            ex.release()
        }
    }

    /** Any rate → 16 kHz: a gentle low-pass (moving average) then linear interpolation, streaming. */
    private class Resampler(private val from: Int) {
        private val step = from.toDouble() / RATE
        private val avg = (from / RATE).coerceIn(1, 6)
        private var pos = 0.0
        private var tail = FloatArray(0)

        fun push(input: FloatArray): FloatArray {
            if (from == RATE) return input
            val x = tail + input
            // Smooth over [avg] samples so high pitches don't fold into the speech band.
            val smooth = if (avg <= 1) x else FloatArray(x.size) { i ->
                var s = 0f; var n = 0
                for (k in maxOf(0, i - avg + 1)..i) { s += x[k]; n++ }
                s / n
            }
            val out = ArrayList<Float>((x.size / step).toInt() + 2)
            while (pos + 1 < smooth.size) {
                val i = pos.toInt()
                val f = (pos - i).toFloat()
                out.add(smooth[i] * (1 - f) + smooth[i + 1] * f)
                pos += step
            }
            val keepFrom = maxOf(0, pos.toInt() - avg)
            tail = x.copyOfRange(keepFrom, x.size)
            pos -= keepFrom
            return out.toFloatArray()
        }
    }

    // ---------- file details ----------

    private class Info(val durationMs: Long, val takenAt: Long, val dated: Boolean)

    private fun info(ctx: Context, uri: Uri): Info {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(ctx, uri)
            val dur = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val date = parseDate(mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE))
            Info(dur, date ?: (System.currentTimeMillis() - dur), date != null)
        } catch (_: Exception) {
            Info(0L, System.currentTimeMillis(), false)
        } finally {
            try { mmr.release() } catch (_: Exception) {}
        }
    }

    /** "20261005T101010.000Z" (the video's own recording time). Cameras without a clock say 1970 or 1904: ignored. */
    internal fun parseDate(s: String?): Long? {
        if (s.isNullOrBlank()) return null
        for (p in listOf("yyyyMMdd'T'HHmmss.SSS'Z'", "yyyyMMdd'T'HHmmss'Z'", "yyyyMMdd'T'HHmmss")) {
            try {
                val f = SimpleDateFormat(p, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
                val t = f.parse(s)?.time ?: continue
                return if (t > 946_684_800_000L && t < System.currentTimeMillis() + 86_400_000L) t else null
            } catch (_: Exception) { }
        }
        return null
    }

    private fun displayName(ctx: Context, uri: Uri): String = try {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
        } ?: uri.lastPathSegment.orEmpty()
    } catch (_: Exception) { uri.lastPathSegment.orEmpty() }
}

/** "ממצלמה חיצונית": pick videos, Moments finds the moments in them, the family chooses which to keep. */
class CameraImportActivity : AppCompatActivity() {
    companion object {
        fun open(ctx: Context) = ctx.startActivity(Intent(ctx, CameraImportActivity::class.java))
        private const val MAX_PICK = 20
    }

    /** One possible moment on the review list. */
    private class Item(val f: CameraImport.Found, val c: Candidate, var selected: Boolean)

    private lateinit var ui: Ui
    private lateinit var mv: MemoryViews
    private lateinit var content: LinearLayout
    private val worker = Executors.newSingleThreadExecutor()
    private val cancelled = AtomicBoolean(false)
    private var running = false
    private var saving = false
    private var status: TextView? = null
    private var bar: ProgressBar? = null
    private var found: List<CameraImport.Found>? = null
    private var items: MutableList<Item> = ArrayList()
    private var saved: List<Memory>? = null
    private val thumbCache = HashMap<String, android.graphics.Bitmap?>()

    private val picker = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_PICK)) { uris ->
        if (!uris.isNullOrEmpty()) scan(uris)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_screen)
        findViewById<TextView>(R.id.screenTitle).text = L.t("ממצלמה חיצונית", "From an external camera")
        findViewById<View>(R.id.btnClose).setOnClickListener { finish() }
        content = findViewById(R.id.screenContent)
        ui = Ui(this)
        mv = MemoryViews(this)
        render()
    }

    override fun onDestroy() {
        cancelled.set(true)
        worker.shutdown()
        super.onDestroy()
    }

    private fun render() {
        content.removeAllViews()
        status = null; bar = null
        when {
            running || saving -> working()
            saved != null -> done(saved!!)
            found != null -> review(found!!)
            else -> intro()
        }
    }

    private fun intro() {
        content.addView(ui.text(L.t(
            "מצלמת אקסטרים או משקפיים מצלמות הרבה. Moments מקשיב לסרטונים וגם מסתכל עליהם, מוצא את הרגעים ששווה לזכור, ואתם בוחרים מה לשמור.",
            "Action cams and glasses record a lot. Moments listens to the videos and looks at them, finds the moments worth remembering, and you choose what to keep.",
        ), 16f, top = 16))

        val how = ui.card(top = 16)
        how.addView(ui.text(L.t("איך זה עובד", "How it works"), 16f, bold = true))
        how.addView(ui.text(L.t("1. מעבירים את הסרטונים מהמצלמה לגלריה של הטלפון, דרך האפליקציה של המצלמה.", "1. Move the videos from the camera to your phone's gallery with the camera's own app."), 14f, top = 8))
        how.addView(ui.text(L.t("2. בוחרים כאן את הסרטונים.", "2. Pick the videos here."), 14f, top = 6))
        how.addView(ui.text(L.t("3. Moments מחפש צחוק, חיוכים, חיבוקים, משחק וחגיגות, לאורך כל הסרטון. הכל קורה בטלפון.", "3. Moments looks for laughter, smiles, hugs, play and celebrations through the whole video. It all happens on your phone."), 14f, top = 6))
        how.addView(ui.text(L.t("4. אתם בוחרים מה לשמור. כל רגע נשמר עם כמה שניות לפני ואחרי, כדי שיהיה סיפור קטן.", "4. You choose what to keep. Each moment is saved with a few seconds before and after, so it tells a little story."), 14f, top = 6))
        content.addView(how)

        val cams = ui.card(top = 12)
        cams.addView(ui.text(L.t("מעבירים לטלפון מ:", "Getting the videos to your phone:"), 16f, bold = true))
        fun cam(n: String, he: String, en: String) {
            cams.addView(ui.text(n, 14f, bold = true, top = 10))
            cams.addView(ui.hint(L.t(he, en), top = 2))
        }
        cam("GoPro", "באפליקציית GoPro Quik: מתחברים למצלמה ומורידים את הסרטונים לטלפון. אפשר להפעיל שם העברה אוטומטית.", "In the GoPro Quik app: connect to the camera and download the videos. You can turn on automatic transfer there.")
        cam("Insta360 / DJI", "באפליקציה של המצלמה (Insta360 / DJI Mimo): מורידים או מייצאים את הסרטון לגלריה.", "In the camera's app (Insta360 / DJI Mimo): download or export the video to the gallery.")
        cam("Ray-Ban Meta", "באפליקציית Meta AI: מייבאים את הסרטונים מהמשקפיים ושומרים לגלריה.", "In the Meta AI app: import the videos from the glasses and save them to the gallery.")
        cams.addView(ui.hint(L.t("כל מצלמה אחרת שהסרטונים שלה מגיעים לגלריה עובדת אותו דבר.", "Any other camera whose videos reach your gallery works the same way."), top = 10))
        content.addView(cams)

        content.addView(ui.button(L.t("בחרו סרטונים", "Choose videos"), primary = true, top = 18) {
            try { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) } catch (_: Exception) {
                android.widget.Toast.makeText(this, L.t("לא הצלחנו לפתוח את בחירת הסרטונים בטלפון הזה", "Couldn't open the video picker on this phone"), android.widget.Toast.LENGTH_LONG).show()
            }
        })
        content.addView(ui.hint(L.t(
            "עד $MAX_PICK סרטונים בכל פעם. דקה של סרטון לוקחת בערך 20–40 שניות.",
            "Up to $MAX_PICK videos at a time. A minute of video takes about 20–40 seconds.",
        ), top = 6))
    }

    private fun working() {
        status = ui.text(if (saving) L.t("שומרים את הרגעים...", "Saving the moments…") else L.t("מקשיבים ומסתכלים...", "Listening and looking…"), 16f, bold = true, top = 24)
        content.addView(status)
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            isIndeterminate = saving
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(8)).apply { topMargin = ui.dp(12) }
        }
        content.addView(bar)
        if (!saving) {
            content.addView(ui.hint(L.t("כדאי להשאיר את המסך פתוח. אם עוצרים, נראה את מה שכבר נמצא.", "Keep this screen open. If you stop, you'll see what was found so far."), top = 10))
            content.addView(ui.button(L.t("עצור", "Stop"), top = 18) { cancelled.set(true) })
        }
    }

    private fun scan(uris: List<Uri>) {
        if (running) return
        running = true
        cancelled.set(false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        render()
        worker.execute {
            val list = ArrayList<CameraImport.Found>()
            uris.forEachIndexed { i, uri ->
                if (cancelled.get()) return@forEachIndexed
                list.add(CameraImport.scan(applicationContext, uri, cancelled) { p ->
                    runOnUiThread {
                        status?.text = if (uris.size == 1) L.t("מקשיבים ומסתכלים... $p%", "Listening and looking… $p%")
                        else L.t("סרטון ${i + 1} מתוך ${uris.size} · $p%", "Video ${i + 1} of ${uris.size} · $p%")
                        bar?.progress = (i * 100 + p) / uris.size
                    }
                })
            }
            runOnUiThread {
                running = false
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                if (isFinishing || isDestroyed) return@runOnUiThread
                found = list
                // Strong and good are ticked; "maybe" waits for you. Best first.
                items = list.flatMap { f -> (f.scan?.candidates ?: emptyList()).map { Item(f, it, it.tier != Tier.MAYBE) } }
                    .sortedByDescending { it.c.rank }.toMutableList()
                render()
            }
        }
    }

    private fun review(list: List<CameraImport.Found>) {
        val many = list.size > 1
        content.addView(ui.heading(
            when (items.size) {
                0 -> L.t("לא מצאנו רגעים", "No moments found")
                1 -> L.t("מצאנו רגע אפשרי אחד", "We found 1 possible moment")
                else -> L.t("מצאנו ${items.size} רגעים אפשריים", "We found ${items.size} possible moments")
            }, 24f, top = 20,
        ))
        if (items.isNotEmpty()) {
            content.addView(ui.hint(L.t(
                "החזקים כבר מסומנים. הקישו על תמונה כדי לצפות, סמנו מה לשמור, ו־✕ על מה שאינו רגע (כך Moments לומד מה חשוב לכם).",
                "The strong ones are ticked. Tap a picture to watch, tick what to keep, and ✕ what isn't a moment (that's how Moments learns what matters to you).",
            ), top = 4))
            for (it in items.toList()) content.addView(itemRow(it, many))
            val n = items.count { it.selected }
            content.addView(ui.button(if (n == 1) L.t("שמרו רגע אחד", "Save 1 moment") else L.t("שמרו $n רגעים", "Save $n moments"), primary = true, top = 18) { saveChosen() }.apply { isEnabled = n > 0 })
        }
        val empty = list.filter { f -> items.none { it.f === f } }
        if (empty.isNotEmpty()) {
            content.addView(ui.section(if (items.isEmpty()) L.t("הסרטונים", "The videos") else L.t("סרטונים בלי רגע ברור", "Videos with no clear moment")))
            for (f in empty) {
                val card = ui.card(top = 8)
                card.addView(ui.text(f.name.ifEmpty { L.t("סרטון", "Video") }, 15f, bold = true))
                val r = f.scan
                val note = f.error ?: when {
                    r == null -> ""
                    !r.hasAudio && r.framesWithFaces == 0 -> L.t("אין בו קול, ולא ראינו בו פנים", "It has no sound and we couldn't see faces")
                    r.framesWithFaces == 0 -> L.t("לא שמענו צחוק ולא ראינו פנים ברורות", "We didn't hear laughter or see clear faces")
                    else -> L.t("לא שמענו ולא ראינו רגע ברור", "We didn't hear or see a clear moment")
                }
                card.addView(ui.hint(note + L.t(". אפשר להוסיף אותו בכל זאת.", ". You can still add it."), top = 2))
                if (f.durationMs > 0) card.addView(ui.action(L.t("הוסיפו בכל זאת ›", "Add it anyway ›")) { addAnyway(f) })
                content.addView(card)
            }
        }
        content.addView(ui.button(L.t("סרטונים אחרים", "Other videos"), top = 14) { found = null; items = ArrayList(); render() })
    }

    private fun itemRow(item: Item, many: Boolean): View {
        val c = item.c
        val row = ui.card(top = 10)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        val thumb = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply { cornerRadius = ui.dp(12).toFloat(); setColor(0xFFEEF3F9.toInt()) }
            clipToOutline = true
            layoutParams = LinearLayout.LayoutParams(ui.dp(84), ui.dp(108))
            item.f.scan?.thumbs?.get(c.peakMs)?.let { p -> thumbCache.getOrPut(p) { android.graphics.BitmapFactory.decodeFile(p) }?.let { setImageBitmap(it) } }
            contentDescription = L.t("צפייה ברגע", "Watch this moment")
            setOnClickListener { preview(item) }
        }
        row.addView(thumb)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(12), 0, ui.dp(8), 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val (fg, bg) = when (c.tier) {
            Tier.STRONG -> Color.WHITE to 0xFF1A6FCC.toInt()
            Tier.GOOD -> 0xFF155BA8.toInt() to 0xFFEEF3F9.toInt()
            Tier.MAYBE -> 0xFF555555.toInt() to 0xFFEEF3F9.toInt()
        }
        col.addView(TextView(this).apply {
            text = MomentWords.tier(c.tier)
            textSize = 11f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(fg)
            setPadding(ui.dp(8), ui.dp(2), ui.dp(8), ui.dp(2))
            background = GradientDrawable().apply { cornerRadius = ui.dp(10).toFloat(); setColor(bg) }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        })
        col.addView(ui.text(MomentWords.headline(c), 15f, bold = true, top = 4))
        val why = MomentWords.why(c)
        if (why.isNotEmpty()) col.addView(ui.hint(why, top = 2))
        col.addView(ui.hint(time(c.startMs) + "–" + time(c.endMs) + (if (many) " · " + item.f.name.take(24) else ""), top = 2))
        row.addView(col)

        val actions = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        val check = TextView(this).apply {
            textSize = 16f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(ui.dp(48), ui.dp(48))
        }
        fun paint() {
            check.text = if (item.selected) "✓" else ""
            check.contentDescription = Wording.state(L.t("לשמור את הרגע", "Keep this moment"), item.selected, L.t("מסומן", "checked"), L.t("לא מסומן", "not checked"))
            check.setTextColor(Color.WHITE)
            check.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                if (item.selected) setColor(0xFF1A6FCC.toInt()) else { setColor(Color.TRANSPARENT); setStroke(ui.dp(2), 0xFFBBBBBB.toInt()) }
            }
        }
        paint()
        check.setOnClickListener { item.selected = !item.selected; render() }
        actions.addView(check)
        actions.addView(TextView(this).apply {
            text = "✕"
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(ui.color(R.color.ink_soft))
            layoutParams = LinearLayout.LayoutParams(ui.dp(36), ui.dp(36)).apply { topMargin = ui.dp(8) }
            contentDescription = L.t("זה לא רגע", "Not a moment")
            setOnClickListener {
                Detections.feedback(applicationContext, c, -1)
                items.remove(item)
                android.widget.Toast.makeText(this@CameraImportActivity, L.t("תודה, נלמד מזה", "Thanks, we'll learn from this"), android.widget.Toast.LENGTH_SHORT).show()
                render()
            }
        })
        row.addView(actions)
        return row
    }

    /** Plays the clip that would be saved (with its lead-up and after). */
    private fun preview(item: Item) {
        val c = item.c
        val video = android.widget.VideoView(this)
        val box = android.widget.FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(video, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(420), Gravity.CENTER))
        }
        val handler = android.os.Handler(mainLooper)
        val stopAtEnd = object : Runnable {
            override fun run() {
                if (video.currentPosition >= c.clipEndMs) video.seekTo(c.clipStartMs.toInt())
                handler.postDelayed(this, 250)
            }
        }
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(MomentWords.headline(c))
            .setView(box)
            .setPositiveButton(if (item.selected) L.t("סגור", "Close") else L.t("לשמור", "Keep")) { _, _ -> if (!item.selected) { item.selected = true; render() } }
            .setNegativeButton(L.t("סגור", "Close"), null)
            .create()
        dialog.setOnDismissListener { handler.removeCallbacksAndMessages(null); try { video.stopPlayback() } catch (_: Exception) { } }
        video.setOnPreparedListener { mp -> mp.isLooping = false; video.seekTo(c.clipStartMs.toInt()); video.start(); handler.post(stopAtEnd) }
        video.setOnErrorListener { _, _, _ -> true }
        video.setVideoURI(item.f.uri)
        dialog.show()
    }

    private fun saveChosen() {
        val chosen = items.filter { it.selected }
        if (chosen.isEmpty() || saving) return
        saving = true
        render()
        worker.execute {
            val out = ArrayList<Memory>()
            for ((f, list) in chosen.groupBy { it.f }) {
                out.addAll(CameraImport.save(applicationContext, f, list.map { it.c }))
                list.forEach { Detections.feedback(applicationContext, it.c, 1) }
            }
            runOnUiThread {
                saving = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                saved = out
                render()
            }
        }
    }

    private fun done(list: List<Memory>) {
        content.addView(ui.heading(
            when (list.size) {
                0 -> L.t("לא הצלחנו לשמור", "Couldn't save")
                1 -> L.t("נשמר רגע אחד", "1 moment saved")
                else -> L.t("נשמרו ${list.size} רגעים", "${list.size} moments saved")
            }, 24f, top = 20,
        ))
        if (list.isNotEmpty()) {
            content.addView(ui.hint(L.t("הם כבר בזיכרונות. הסרטון המקורי לא השתנה.", "They're in your memories. The original videos are untouched."), top = 4))
            val strip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            list.forEach { m -> strip.addView(mv.mini(m, 96, 128) { MomentActivity.open(this, m.id) }) }
            content.addView(ui.scrollRow(strip, top = 12))
            content.addView(ui.button(L.t("לזיכרונות", "Open memories"), primary = true, top = 16) {
                startActivity(Intent(this, MemoriesActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)); finish()
            })
        }
        content.addView(ui.button(L.t("עוד סרטונים", "More videos"), top = 12) { found = null; items = ArrayList(); saved = null; render() })
    }

    private fun addAnyway(f: CameraImport.Found) {
        worker.execute {
            try { contentResolver.takePersistableUriPermission(f.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) { }
            val m = try { MediaImporter.add(applicationContext, f.uri) } catch (_: Exception) { null }
            runOnUiThread {
                if (m == null) android.widget.Toast.makeText(this, L.t("לא הצלחנו להוסיף", "Couldn't add it"), android.widget.Toast.LENGTH_SHORT).show()
                else if (m.durationMs > NewMemory.MAX_STORY_MS) TrimActivity.open(this, m.id) else MomentActivity.open(this, m.id)
            }
        }
    }

    private fun time(ms: Long): String {
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }
}

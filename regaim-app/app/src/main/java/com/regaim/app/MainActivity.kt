package com.regaim.app

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.format.DateUtils
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private val ui = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("regaim", MODE_PRIVATE) }

    private lateinit var liveBubble: LiveBubbleView
    private lateinit var statusChip: TextView
    private lateinit var statusText: TextView
    private lateinit var hearText: TextView
    private lateinit var btnToggle: Button
    private lateinit var statTime: TextView
    private lateinit var statToday: TextView
    private lateinit var statVideo: TextView
    private lateinit var bubblesRow: LinearLayout
    private lateinit var bubblesEmpty: TextView
    private lateinit var settingsBody: View
    private lateinit var settingsHeader: TextView
    private lateinit var sensitivity: SeekBar
    private lateinit var sensitivityLabel: TextView
    private lateinit var btnSaveNow: Button
    private lateinit var errorText: TextView
    private lateinit var storyText: TextView
    private lateinit var diagText: TextView
    private lateinit var btnComicToday: Button
    private lateinit var btnSaveBig: Button

    private lateinit var zoomChips: LinearLayout
    private val zoomOptions = listOf(0.6f, 1f, 2f, 3f, 5f)

    /** Lens / zoom picker under the live bubble: 0.6x is the ultra-wide lens, 3x and 5x the telephoto. */
    private fun buildZoomChips() {
        zoomChips.removeAllViews()
        val d = resources.displayMetrics.density
        for (z in zoomOptions) {
            val selected = kotlin.math.abs(z - Status.zoom) < 0.01f
            val chip = TextView(this).apply {
                text = if (z < 1f) "0.6x" else "${z.toInt()}x"
                textSize = 14f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                gravity = android.view.Gravity.CENTER
                minWidth = (52 * d).toInt()
                setPadding((10 * d).toInt(), (7 * d).toInt(), (10 * d).toInt(), (7 * d).toInt())
                setTextColor(if (selected) ContextCompat.getColor(this@MainActivity, R.color.white) else ContextCompat.getColor(this@MainActivity, R.color.ink))
                background = if (selected) android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = 20 * d
                    setColor(ContextCompat.getColor(this@MainActivity, R.color.accent))
                } else null
                setOnClickListener {
                    Status.zoom = z
                    prefs.edit().putFloat("zoom", z).apply()
                    buildZoomChips()
                    if (Status.running) sendToService(CaptureService.ACTION_ZOOM)
                }
            }
            zoomChips.addView(chip)
        }
    }
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor()

    private var shownMemoryCount = -1
    private var lastBubblesCheck = 0L

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (hasCorePermissions()) startCapture()
            else toast(L.t("צריך הרשאת מצלמה ומיקרופון כדי לעבוד", "Camera and microphone access are needed to work"))
        }

    private val tick = object : Runnable {
        override fun run() {
            refresh()
            ui.postDelayed(this, 200)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        liveBubble = findViewById(R.id.liveBubble)
        statusChip = findViewById(R.id.statusChip)
        statusText = findViewById(R.id.statusText)
        hearText = findViewById(R.id.hearText)
        btnToggle = findViewById(R.id.btnToggle)
        statTime = findViewById(R.id.statTime)
        statToday = findViewById(R.id.statToday)
        statVideo = findViewById(R.id.statVideo)
        bubblesRow = findViewById(R.id.bubblesRow)
        bubblesEmpty = findViewById(R.id.bubblesEmpty)
        settingsBody = findViewById(R.id.settingsBody)
        settingsHeader = findViewById(R.id.settingsHeader)
        sensitivity = findViewById(R.id.sensitivity)
        sensitivityLabel = findViewById(R.id.sensitivityLabel)
        btnSaveNow = findViewById(R.id.btnSaveNow)
        errorText = findViewById(R.id.errorText)

        btnToggle.setOnClickListener {
            if (Status.running) sendToService(CaptureService.ACTION_STOP) else requestAndStart()
        }
        liveBubble.setOnClickListener { if (!Status.running) requestAndStart() }
        findViewById<View>(R.id.cardBubbles).setOnClickListener {
            startActivity(Intent(this, MemoriesActivity::class.java))
        }
        storyText = findViewById(R.id.storyText)
        diagText = findViewById(R.id.diagText)
        btnComicToday = findViewById(R.id.btnComicToday)
        btnComicToday.setOnClickListener {
            MemoryStore.kept(this).firstOrNull { DateUtils.isToday(it.time) }?.let { ComicActivity.forDay(this, it.dayKey) }
        }
        // The camera is a mode of the app, not a tab: back goes home.
        findViewById<View>(R.id.btnHome).setOnClickListener {
            startActivity(Intent(this, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        }
        settingsHeader.setOnClickListener {
            val open = settingsBody.visibility != View.VISIBLE
            settingsBody.visibility = if (open) View.VISIBLE else View.GONE
            settingsHeader.text = if (open) L.t("בדיקה והגדרות מהירות ⌃", "Check & quick settings ⌃") else L.t("בדיקה והגדרות מהירות ⌄", "Check & quick settings ⌄")
        }
        btnSaveNow.setOnClickListener { sendToService(CaptureService.ACTION_SAVE_NOW) }
        btnSaveBig = findViewById(R.id.btnSaveBig)
        btnSaveBig.setOnClickListener { sendToService(CaptureService.ACTION_SAVE_NOW) }
        findViewById<View>(R.id.btnSettings).setOnClickListener { SettingsActivity.open(this) }
        findViewById<View>(R.id.btnLearned).setOnClickListener { LearnedActivity.open(this) }
        findViewById<View>(R.id.btnGear).setOnClickListener { SettingsActivity.open(this) }
        findViewById<View>(R.id.btnGallery).setOnClickListener { openGallery() }

        Settings.load(this)
        zoomChips = findViewById(R.id.zoomChips)
        buildZoomChips()
        sensitivity.max = 55
        sensitivity.progress = ((0.6f - Status.threshold) * 100).roundToInt().coerceIn(0, 55)
        updateSensitivityLabel()
        sensitivity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                Status.threshold = 0.6f - progress / 100f
                prefs.edit().putFloat("threshold3", Status.threshold).apply()
                updateSensitivityLabel()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    override fun onResume() {
        super.onResume()
        // Moments added earlier that nobody looked at yet: find and tag the faces in the background.
        FaceScan.kick(this)
        // Settings may have changed on the settings screen.
        sensitivity.progress = ((0.6f - Status.threshold) * 100).roundToInt().coerceIn(0, 55)
        buildZoomChips()
        Status.uiVisible = true
        shownMemoryCount = -1
        ui.post(tick)
        showStory()
        // Let the AI catch up: describe new moments, then write the story of the day.
        if (ClaudeClient.hasKey(this)) {
            worker.execute {
                val described = MemoryStore.analyzePending(applicationContext)
                val wrote = MemoryStore.refreshStories(applicationContext, 1)
                if (described > 0 || wrote) runOnUiThread {
                    if (!isFinishing) {
                        shownMemoryCount = -1
                        showStory()
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun showStory() {
        val today = MemoryStore.kept(this).filter { DateUtils.isToday(it.time) }
        val story = if (today.isNotEmpty()) MemoryStore.story(this, today.first().dayKey) else ""
        storyText.text = when {
            story.isNotEmpty() -> story
            today.isEmpty() -> L.t("הסיפור של היום עוד לא התחיל. הפעילו את Moments, ובערב יחכה לכם כאן סיכום חם של היום.", "Today's story hasn't started yet. Turn on Moments, and this evening a warm recap of your day will be waiting here.")
            !ClaudeClient.hasKey(this) -> L.t("כדי שנכתוב את סיפור היום, הפעילו \"הבנת רגעים\" בהגדרות.", "To write today's story, turn on \"Moment understanding\" in Settings.")
            today.size < 2 -> L.t("נתפס רגע אחד היום. מעוד רגע אחד אכתוב כאן את סיפור היום.", "One moment caught today. One more and I'll write today's story here.")
            else -> L.t("כותבת את סיפור היום...", "Writing today's story...")
        }
    }

    override fun onPause() {
        super.onPause()
        Status.uiVisible = false
        ui.removeCallbacks(tick)
    }

    // ---------- permissions & service ----------

    private fun neededPermissions(): List<String> {
        val list = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) list.add(Manifest.permission.POST_NOTIFICATIONS)
        // Approximate location only: enough to note the neighborhood or town of a moment, never the exact spot.
        list.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT < 29) list.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        return list
    }

    private fun granted(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun hasCorePermissions(): Boolean {
        val core = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT < 29) core.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        return core.all { granted(it) }
    }

    private fun requestAndStart() {
        // What happens with the camera, microphone and location, before they're asked for (once).
        Disclosure.beforeCapture(this) {
            val missing = neededPermissions().filterNot { granted(it) }
            if (missing.isEmpty()) startCapture() else permLauncher.launch(missing.toTypedArray())
        }
    }

    private fun startCapture() {
        Status.error = null
        val intent = Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_START)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun sendToService(action: String) {
        startService(Intent(this, CaptureService::class.java).setAction(action))
    }

    // ---------- UI ----------

    private fun updateSensitivityLabel() {
        val p = sensitivity.progress
        val word = when {
            p >= 48 -> L.t("גבוהה מאוד", "very high")
            p >= 38 -> L.t("גבוהה", "high")
            p >= 22 -> L.t("בינונית", "medium")
            else -> L.t("נמוכה", "low")
        }
        sensitivityLabel.text = L.t("רגישות לצחוק: $word (שומר מעל ${(Status.threshold * 100).roundToInt()}%)", "Laugh sensitivity: $word (saves above ${(Status.threshold * 100).roundToInt()}%)")
        liveBubble.threshold = Status.threshold
    }

    private fun refresh() {
        val s = Status
        val runningFor = if (s.running && s.startedAt > 0) System.currentTimeMillis() - s.startedAt else 0L

        // Live bubble
        liveBubble.recording = s.running
        liveBubble.bitmap = s.preview
        liveBubble.level = s.lastScore
        liveBubble.caught = s.pending
        liveBubble.threshold = s.threshold

        // Header chip + main button
        btnSaveBig.visibility = if (s.running) View.VISIBLE else View.GONE
        btnSaveBig.isEnabled = !s.pending
        btnSaveBig.text = if (s.pending) L.t("שומרים את ההמשך…", "Saving what comes next…") else L.t("♥ שמור את הרגע", "♥ Save this moment")
        btnToggle.backgroundTintList = android.content.res.ColorStateList.valueOf(
            ContextCompat.getColor(this, if (s.running) R.color.ink else R.color.accent)
        )
        if (s.running) {
            statusChip.text = L.t("● מקליט", "● Recording")
            statusChip.setBackgroundResource(R.drawable.chip_on)
            statusChip.setTextColor(ContextCompat.getColor(this, R.color.white))
            btnToggle.text = L.t("עצור", "Stop")
        } else {
            statusChip.text = L.t("כבוי", "Off")
            statusChip.setBackgroundResource(R.drawable.chip_off)
            statusChip.setTextColor(ContextCompat.getColor(this, R.color.ink_soft))
            btnToggle.text = L.t("התחל לתפוס רגעים", "Start catching moments")
        }

        statusText.text = when {
            !s.running -> L.t("מוכן לתפוס רגעים", "Ready to catch moments")
            s.pending -> L.t("נתפס רגע! שומר את ההמשך...", "Moment caught! Saving what comes next...")
            else -> L.t("מאזין לצחוק...", "Listening for laughter...")
        }
        hearText.text = if (s.running) {
            val now = Labels.he(s.topLabel).ifEmpty { "..." }
            val laugh = (s.lastScore * 100).roundToInt()
            if (s.lastLabel.isNotEmpty() && laugh >= 5) L.t("שומע: ${Labels.he(s.lastLabel)} · $laugh%", "Hearing: ${Labels.he(s.lastLabel)} · $laugh%") else L.t("שומע: $now", "Hearing: $now")
        } else {
            L.t("רגעים שיזוהו יישמרו עם הזמן שלפני ואחרי, לפי ההגדרות שלכם", "Detected moments are saved with a bit before and after, based on your settings")
        }

        // Stats
        statTime.text = formatDuration(runningFor)
        statVideo.text = when {
            !s.running -> "—"
            s.videoFrames > 0 && System.currentTimeMillis() - s.lastFrameMs > 3000 -> L.t("מתחבר...", "Connecting...")
            s.videoFrames > 0 -> (if (s.zoomApplied < 1f) String.format(Locale.US, "%.1fx", s.zoomApplied) else "${s.zoomApplied.toInt()}x") + " ✓"
            else -> "..."
        }

        // Bubbles strip (cheap check, every 2 seconds)
        val nowMs = System.currentTimeMillis()
        if (nowMs - lastBubblesCheck > 2000) {
            lastBubblesCheck = nowMs
            val today = MemoryStore.kept(this).filter { DateUtils.isToday(it.time) }
            statToday.text = today.size.toString()
            btnComicToday.visibility = if (today.isNotEmpty()) View.VISIBLE else View.GONE
            if (today.size != shownMemoryCount) {
                shownMemoryCount = today.size
                showBubbles(today.take(5))
            }
        }

        // Errors + camera sanity check
        val err = s.error ?: if (s.running && runningFor > 6000 && s.videoFrames == 0L) {
            L.t("המצלמה לא שולחת וידאו. נסה לעצור ולהתחיל שוב, ולוודא שאף אפליקציה אחרת לא משתמשת במצלמה.", "The camera isn't sending video. Try stopping and starting again, and make sure no other app is using the camera.")
        } else null
        errorText.text = err ?: ""
        errorText.visibility = if (err == null) View.GONE else View.VISIBLE
        btnSaveNow.isEnabled = s.running && !s.pending
        diagText.text = if (s.running) {
            val words = if (s.wordsOn) L.t("\nמילים: ${s.wordsState.ifEmpty { "..." }}", "\nWords: ${s.wordsState.ifEmpty { "..." }}") +
                (if (s.heard.isNotEmpty()) L.t("\nשמעתי: \"${s.heard}\"", "\nHeard: \"${s.heard}\"") else "") else ""
            val faces = if (s.facesOn) L.t("\nפנים: ${s.faces} · חיוך ${(s.smile * 100).roundToInt()}%", "\nFaces: ${s.faces} · smile ${(s.smile * 100).roundToInt()}%") else ""
            L.t("הצחוק הכי חזק שנשמע: ${(s.peakScore * 100).roundToInt()}%\nשומע עכשיו: ${s.top3}$words$faces", "Loudest laugh heard: ${(s.peakScore * 100).roundToInt()}%\nHearing now: ${s.top3}$words$faces")
        } else ""
    }

    private fun showBubbles(items: List<Memory>) {
        bubblesRow.removeAllViews()
        bubblesEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        val size = (56 * resources.displayMetrics.density).toInt()
        val gap = (8 * resources.displayMetrics.density).toInt()
        for (m in items) {
            val iv = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(size, size).apply { marginEnd = gap }
                val bmp = if (m.thumb.isNotEmpty()) BitmapFactory.decodeFile(m.thumb) else null
                if (bmp != null) setImageBitmap(bmp) else setImageResource(R.drawable.bubble_placeholder)
            }
            iv.setOnClickListener { MomentActivity.open(this, m.id) }
            bubblesRow.addView(iv)
        }
    }

    private fun formatDuration(ms: Long): String {
        val total = ms / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val sec = total % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
        else String.format(Locale.US, "%d:%02d", m, sec)
    }

    private fun openGallery() {
        val candidates = listOf(
            Intent(Intent.ACTION_VIEW).setDataAndType(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "video/*"),
            Intent(Intent.ACTION_VIEW, MediaStore.Video.Media.EXTERNAL_CONTENT_URI),
            Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_GALLERY),
        )
        for (intent in candidates) {
            try {
                startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: ActivityNotFoundException) {
            } catch (_: SecurityException) {
            }
        }
        toast(L.t("הקליפים נמצאים בגלריה, בתיקייה Movies/Moments", "Your clips are in the gallery, in Movies/Moments"))
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}

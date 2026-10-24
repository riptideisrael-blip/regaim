package com.regaim.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.media3.common.util.UnstableApi
import java.io.File
import java.util.concurrent.Executors

/**
 * "סרטון תקופתי": the family memory editor. It looks at the period's moments (on the phone), chooses the strong ones,
 * orders them as a story, and scores them with original music. The family sees what was chosen, picks a style,
 * watches the preview, and exports — or asks for another version.
 */
@OptIn(markerClass = [UnstableApi::class])
class PeriodVideoActivity : AppCompatActivity() {
    companion object {
        fun open(ctx: Context) = ctx.startActivity(Intent(ctx, PeriodVideoActivity::class.java))
        private const val STYLE_KEY = "reel_style"
    }

    private lateinit var ui: Ui
    private lateinit var mv: MemoryViews
    private lateinit var content: LinearLayout
    private val worker = Executors.newSingleThreadExecutor()
    private var period = PeriodVideo.Period.THIS_WEEK
    /** null = the style that fits the moments. */
    private var style: ReelStyle? = null
    private var seed = 0
    private var prepared: PeriodVideo.Prepared? = null
    private var preparing = false
    private var handle: PeriodVideo.Handle? = null
    private var progress: ProgressBar? = null
    private var result: File? = null
    private var building = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_screen)
        findViewById<TextView>(R.id.screenTitle).text = L.t("סרטון השבוע", "Your week's film")
        findViewById<View>(R.id.btnClose).setOnClickListener { finish() }
        content = findViewById(R.id.screenContent)
        ui = Ui(this)
        mv = MemoryViews(this)
        style = ReelStyle.values().firstOrNull { it.key == Settings.prefs(this).getString(STYLE_KEY, "") }
        decide()
    }

    override fun onDestroy() {
        if (building) handle?.cancel?.invoke()
        worker.shutdown()
        super.onDestroy()
    }

    /** Runs the editor (signals are cached, so a style change is quick). */
    private fun decide() {
        preparing = true
        prepared = null
        result = null
        render()
        val p = period
        val st = style
        val sd = seed
        worker.execute {
            val r = try { PeriodVideo.prepare(applicationContext, p, PeriodVideo.moments(applicationContext, p), st, sd) } catch (_: Exception) { null }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                preparing = false
                prepared = r
                render()
            }
        }
    }

    private fun render() {
        content.removeAllViews()
        progress = null
        content.addView(ui.hint(L.t(
            "לא כל מה שקרה, רק מה ששווה לזכור. Moments בוחר את הרגעים החזקים, מסדר אותם כסיפור ומלחין להם מוזיקה מקורית.",
            "Not everything that happened, just what's worth remembering. Moments picks the strong moments, orders them as a story and composes original music for them.",
        ), top = 14))
        val periods = PeriodVideo.Period.values().toList()
        content.addView(ui.chips(periods.map { it.title }, periods.indexOf(period), top = 12) { i ->
            if (building || preparing) return@chips
            period = periods[i]; seed = 0; decide()
        })

        if (preparing) {
            content.addView(ui.text(L.t("בוחרים את הרגעים...", "Choosing the moments…"), 16f, bold = true, top = 22))
            content.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                isIndeterminate = true
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(8)).apply { topMargin = ui.dp(10) }
            })
            return
        }
        val p = prepared
        if (p == null || p.plan.shots.size < 2) {
            content.addView(ui.text(L.t("אין מספיק רגעים טובים בתקופה הזאת", "Not enough good moments in this period"), 17f, bold = true, top = 24))
            content.addView(ui.hint(L.t("צריך לפחות שני רגעים עם אנשים. אפשר לבחור תקופה אחרת.", "You need at least two moments with people in them. Try another period."), top = 4))
            return
        }
        val plan = p.plan

        // The preview card: what was chosen, the music, the length.
        val card = ui.card(top = 16)
        card.addView(ui.text(L.t("❤️ נבחרו ${plan.shots.size} רגעים", "❤️ ${plan.shots.size} moments chosen") +
            L.t(" מתוך ${p.considered}", " of ${p.considered}"), 17f, bold = true))
        card.addView(ui.text("🎵 " + PeriodVideo.musicName(plan) + "   ⏱ " + L.t("${plan.totalMs / 1000} שניות", "${plan.totalMs / 1000} sec"), 15f, top = 6))
        val strip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (s in plan.shots) p.byId[s.id]?.let { m -> strip.addView(mv.mini(m) { }) }
        card.addView(ui.scrollRow(strip, top = 10))
        val left = plan.rejected.values.groupingBy { it }.eachCount()
        val why = listOfNotNull(
            left["duplicate"]?.let { L.t("$it כפולים", "$it duplicates") },
            left["screenshot"]?.let { L.t("$it צילומי מסך", "$it screenshots") },
            left["blurry"]?.let { L.t("$it מטושטשים", "$it blurry") },
            left["weak"]?.let { L.t("$it חלשים", "$it weaker ones") },
        )
        if (why.isNotEmpty()) card.addView(ui.hint(L.t("נשארו בחוץ: ", "Left out: ") + why.joinToString(" · "), top = 6))
        content.addView(card)

        // Style.
        val styles = ReelStyle.values().toList()
        val auto = style == null
        content.addView(ui.text(L.t("סגנון", "Style"), 15f, bold = true, top = 16))
        content.addView(ui.chips(styles.map { PeriodVideo.styleName(it) + if (auto && it == plan.style) L.t(" (מומלץ)", " (suggested)") else "" }, styles.indexOf(plan.style), top = 6) { i ->
            if (building || preparing) return@chips
            style = styles[i]
            Settings.prefs(this).edit().putString(STYLE_KEY, styles[i].key).apply()
            decide()
        })

        val done = result
        if (done != null && done.exists()) { showResult(done); return }
        if (building) {
            content.addView(ui.text(L.t("עורכים את הסרטון... כדקה, אפשר להישאר כאן.", "Editing your film… about a minute. Stay on this screen."), 15f, top = 14))
            progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100; isIndeterminate = true
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(8)).apply { topMargin = ui.dp(10) }
            }
            content.addView(progress)
            return
        }
        content.addView(ui.button(L.t("▶ תצוגה מקדימה", "▶ Preview"), primary = true, top = 18) { build(p) })
        content.addView(ui.action(L.t("גרסה אחרת ›", "Create another version ›")) { seed++; decide() }.apply { setPadding(0, ui.dp(12), 0, 0) })
    }

    private fun build(p: PeriodVideo.Prepared) {
        building = true
        render()
        handle = PeriodVideo.make(applicationContext, p,
            onProgress = { pr -> progress?.let { it.isIndeterminate = false; it.progress = pr } },
            onDone = { file, err ->
                building = false
                if (isFinishing || isDestroyed) return@make
                if (file == null) Toast.makeText(this, err ?: L.t("משהו השתבש", "Something went wrong"), Toast.LENGTH_LONG).show()
                result = file
                render()
            })
    }

    private fun showResult(file: File) {
        val box = FrameLayout(this).apply {
            setBackgroundResource(R.drawable.rounded_media)
            clipToOutline = true
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (resources.displayMetrics.heightPixels * 0.55f).toInt()).apply { topMargin = ui.dp(14) }
        }
        // The whole video, centered in the frame (its own shape, never cut: filling the box pushed it to one side).
        box.background = DS.rounded(android.graphics.Color.rgb(20, 18, 16), ui.dp(24).toFloat())
        val video = VideoView(this).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, android.view.Gravity.CENTER)
            setVideoPath(file.absolutePath)
            setOnPreparedListener { it.isLooping = true; start() }
        }
        box.addView(video)
        content.addView(box)
        content.addView(ui.button(L.t("שתף", "Share"), primary = true, top = 14) { share(file) })
        content.addView(ui.button(L.t("שמור בגלריה", "Save to gallery"), top = 6) { save(file) })
        content.addView(ui.action(L.t("גרסה אחרת ›", "Create another version ›")) { seed++; decide() }.apply { setPadding(0, ui.dp(14), 0, 0) })
    }

    private fun share(file: File) {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
            val send = Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM, uri)
                .putExtra(Intent.EXTRA_TEXT, period.videoTitle + L.t("\n\nנעשה עם Moments 💜", "\n\nMade with Moments 💜"))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(send, L.t("לשתף עם...", "Share with...")).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (_: Exception) {
            Toast.makeText(this, L.t("לא הצלחנו לפתוח את השיתוף", "Couldn't open sharing"), Toast.LENGTH_SHORT).show()
        }
    }

    private fun save(file: File) {
        Executors.newSingleThreadExecutor().execute {
            val ok = try { ReelMaker.publish(applicationContext, file); true } catch (_: Exception) { false }
            runOnUiThread {
                Toast.makeText(this, if (ok) L.t("נשמר בגלריה, בתיקייה Movies/Moments", "Saved to your gallery, in Movies/Moments") else L.t("לא הצלחנו לשמור", "Couldn't save"), Toast.LENGTH_LONG).show()
            }
        }
    }
}

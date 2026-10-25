package com.regaim.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.Executors

/** Android's document picker grants access only to the selected family memory. */
class MediaImportActivity : AppCompatActivity() {
    companion object {
        private const val EXTRA_PICK_NOW = "pickNow"
        private const val MAX_PICK = 30
        /** [pickNow]: open the phone's picker at once (photos and videos), without the intro screen. */
        fun open(ctx: Context, pickNow: Boolean = false) =
            ctx.startActivity(Intent(ctx, MediaImportActivity::class.java).putExtra(EXTRA_PICK_NOW, pickNow))
    }

    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var title: EditText
    private lateinit var status: TextView
    private lateinit var actions: LinearLayout
    private var importing = false
    /** The modern Android photo picker: choose many photos and videos at once (no storage permission). */
    private val picker = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_PICK)) { uris ->
        if (uris.isNullOrEmpty()) { if (intent.getBooleanExtra(EXTRA_PICK_NOW, false) && !importing) finish(); return@registerForActivityResult }
        importAll(uris)
    }

    private fun pick(type: ActivityResultContracts.PickVisualMedia.VisualMediaType) {
        if (importing) return
        try { picker.launch(PickVisualMediaRequest(type)) } catch (_: Exception) { status.text = L.t("לא הצלחנו לפתוח את הגלריה", "Couldn't open your gallery") }
    }

    private fun importAll(uris: List<android.net.Uri>) {
        importing = true
        actions.visibility = View.GONE
        val requestedTitle = if (uris.size == 1) title.text.toString() else ""
        if (uris.size > 1) {
            // Many at once: copied in the background (with a notification), so the parent may leave the screen.
            for (u in uris) try { contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) { }
            val before = ImportService.State.finishedJobs
            if (!ImportService.start(applicationContext, GalleryJob(uris))) {
                importing = false
                actions.visibility = View.VISIBLE
                status.text = L.t("הוספה קודמת עוד רצה. נסו שוב כשהיא תסתיים (ההתראה תודיע).", "A previous import is still running. Try again when it's done (the notification will tell you).")
                return
            }
            watch(before)
            return
        }
        worker.execute {
            val added = ArrayList<Memory>()
            uris.forEachIndexed { i, uri ->
                runOnUiThread { if (!isFinishing) status.text = if (uris.size == 1) L.t("רגע, מוסיפים את הזיכרון...", "Adding your memory...") else L.t("מוסיפים ${i + 1} מתוך ${uris.size}...", "Adding ${i + 1} of ${uris.size}...") }
                try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) { }
                try { added.add(MediaImporter.add(applicationContext, uri, requestedTitle)) } catch (_: Exception) { }
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                when {
                    added.isEmpty() -> {
                        importing = false
                        actions.visibility = View.VISIBLE
                        status.text = L.t("לא הצלחנו להוסיף. נסו לבחור שוב.", "Couldn't add that. Try picking again.")
                    }
                    added.size == 1 -> afterOne(added[0])
                    else -> {
                        val failed = uris.size - added.size
                        android.widget.Toast.makeText(this, L.t("נוספו ${added.size} רגעים ✓", "${added.size} moments added ✓") + if (failed > 0) L.t(" ($failed לא נוספו)", " ($failed couldn't be added)") else "", android.widget.Toast.LENGTH_LONG).show()
                        startActivity(Intent(this, MemoriesActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                        finish()
                    }
                }
            }
        }
    }

    /** Progress of the background copy while this screen is open; when it ends, on to the memories as before. */
    private fun watch(before: Int) {
        if (isFinishing || isDestroyed) return
        val st = ImportService.State
        if (st.finishedJobs == before) {
            status.text = L.t("מוסיפים ${st.done} מתוך ${st.total}... אפשר לצאת מהמסך, ההוספה ממשיכה ברקע.", "Adding ${st.done} of ${st.total}... You can leave this screen; adding goes on in the background.")
            status.postDelayed({ watch(before) }, 1000)
            return
        }
        if (st.added == 0) {
            importing = false
            actions.visibility = View.VISIBLE
            status.text = L.t("לא הצלחנו להוסיף. נסו לבחור שוב.", "Couldn't add that. Try picking again.")
            return
        }
        val failed = st.total - st.added
        android.widget.Toast.makeText(this, L.t("נוספו ${st.added} רגעים ✓", "${st.added} moments added ✓") + if (failed > 0) L.t(" ($failed לא נוספו)", " ($failed couldn't be added)") else "", android.widget.Toast.LENGTH_LONG).show()
        startActivity(Intent(this, MemoriesActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        finish()
    }

    private fun afterOne(memory: Memory) {
        if (memory.isVideo && memory.durationMs > NewMemory.MAX_STORY_MS) {
            // A long video: offer to pick the part worth keeping before opening it.
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(L.t("הסרטון ארוך", "Long video"))
                .setMessage(L.t("הסרטון באורך ${Fmt.duration(memory.durationMs)}. לקצר אותו לפני שנמשיך?", "This video is ${Fmt.duration(memory.durationMs)} long. Trim it before continuing?"))
                .setPositiveButton(L.t("קצר", "Trim")) { _, _ -> TrimActivity.open(this, memory.id); finish() }
                .setNegativeButton(L.t("שמור כמו שהוא", "Keep as is")) { _, _ -> MomentActivity.open(this, memory.id); finish() }
                .setCancelable(false)
                .show()
        } else {
            MomentActivity.open(this, memory.id)
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_screen)
        findViewById<TextView>(R.id.screenTitle).text = L.t("להוסיף זיכרון", "Add a memory")
        findViewById<View>(R.id.btnClose).setOnClickListener { finish() }
        val ui = Ui(this)
        val content = findViewById<LinearLayout>(R.id.screenContent)
        content.addView(ui.text(L.t("גם התמונות והסרטונים שכבר אצלכם יכולים להיות חלק מהסיפור המשפחתי.", "The photos and videos you already have can be part of your family story too."), 17f, top = 18))
        content.addView(ui.hint(L.t("רק מה שתבחרו יתווסף. הזיכרון יישאר פרטי עד שתחליטו לשתף אותו עם המשפחה.", "Only what you pick is added. It stays private until you choose to share it with family."), top = 10))
        title = ui.field(L.t("איך תרצו לקרוא לרגע הזה? (לא חובה)", "What would you like to call this moment? (optional)"), "", top = 22)
        content.addView(title)
        actions = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        actions.addView(ui.button(L.t("בחרו תמונות וסרטונים", "Choose photos and videos"), primary = true, top = 20) { pick(ActivityResultContracts.PickVisualMedia.ImageAndVideo) })
        actions.addView(ui.hint(L.t("אפשר לבחור עד $MAX_PICK בבת אחת.", "You can pick up to $MAX_PICK at once."), top = 6))
        content.addView(actions)
        status = ui.hint(L.t("בסרטון שתוסיפו תוכלו לבחור בעצמכם את הקטע שתרצו לזכור.", "For videos, you can choose the exact part you want to remember."), top = 18)
        content.addView(status)
        if (savedInstanceState == null && intent.getBooleanExtra(EXTRA_PICK_NOW, false)) pick(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }
}

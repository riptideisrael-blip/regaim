package com.regaim.app

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.Executors

/**
 * Every face the app named as one person, together: tap the ones that aren't them and fix them all at once
 * ("זה לא אורי"). The app learns from it, so it won't name such faces so again (on the phone only).
 */
class PersonFacesActivity : AppCompatActivity() {
    companion object {
        private const val EXTRA_ID = "person"
        fun open(ctx: Context, personId: String) = ctx.startActivity(Intent(ctx, PersonFacesActivity::class.java).putExtra(EXTRA_ID, personId))
    }

    private val ui by lazy { Ui(this) }
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var grid: LinearLayout
    private lateinit var fix: TextView
    private lateinit var title: TextView
    private val picked = LinkedHashSet<String>()
    private val thumbs = HashMap<String, Bitmap?>()
    private var personId = ""
    private var name = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        personId = intent.getStringExtra(EXTRA_ID).orEmpty()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(ui.color(R.color.bg)) }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.dp(20), ui.dp(18), ui.dp(12), ui.dp(4))
        }
        title = ui.text("", 20f, bold = true)
        head.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(ui.action(L.t("סגור", "Close")) { finish() })
        root.addView(head)
        root.addView(ui.hint(L.t(
            "סמנו את הפנים שאינן שלו/ה, ותקנו את כולן בבת אחת. האפליקציה תלמד מזה ולא תטעה כך שוב.",
            "Tap the faces that aren't them, and fix them all at once. The app learns from it and won't make that mistake again.",
        )).apply { setPadding(ui.dp(20), 0, ui.dp(20), ui.dp(8)) })
        grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(12), 0, ui.dp(12), ui.dp(12)) }
        root.addView(ScrollView(this).apply { addView(grid) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        fix = ui.button("", primary = true, top = 0) { fixPicked() }
        root.addView(LinearLayout(this).apply {
            setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(16))
            addView(fix, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        })
        setContentView(root)
        render()
    }

    override fun onDestroy() { worker.shutdownNow(); super.onDestroy() }

    private fun render() {
        val person = PeopleStore.people(this).firstOrNull { it.id == personId } ?: run { finish(); return }
        name = person.name
        val faces = PeopleStore.faces(this).filter { it.personId == personId }.sortedByDescending { it.time }
        picked.retainAll(faces.map { it.id }.toSet())
        title.text = L.t("הפנים של $name (${faces.size})", "$name's faces (${faces.size})")
        grid.removeAllViews()
        if (faces.isEmpty()) grid.addView(ui.hint(L.t("אין פנים שמשויכות ל-$name כרגע.", "No faces are named $name right now."), top = 12))
        val side = (resources.displayMetrics.widthPixels - ui.dp(24)) / 4
        for (row in faces.chunked(4)) {
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (f in row) line.addView(cell(f, side))
            grid.addView(line)
        }
        paintButton()
    }

    private fun cell(f: FaceShot, side: Int): View {
        val frame = FrameLayout(this).apply { setPadding(ui.dp(3), ui.dp(3), ui.dp(3), ui.dp(3)) }
        val img = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageBitmap(thumbs.getOrPut(f.path) { BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = 2 }) })
            background = GradientDrawable().apply { cornerRadius = ui.dp(10).toFloat(); setColor(ui.color(R.color.fill)) }
            clipToOutline = true
        }
        frame.addView(img, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val mark = TextView(this).apply {
            text = "✕"; textSize = 26f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
            background = GradientDrawable().apply { cornerRadius = ui.dp(10).toFloat(); setColor(0x99D0343C.toInt()) }
            visibility = if (f.id in picked) View.VISIBLE else View.GONE
        }
        frame.addView(mark, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        frame.setOnClickListener {
            if (!picked.add(f.id)) picked.remove(f.id)
            mark.visibility = if (f.id in picked) View.VISIBLE else View.GONE
            paintButton()
        }
        frame.layoutParams = LinearLayout.LayoutParams(side, side)
        return frame
    }

    private fun paintButton() {
        fix.isEnabled = picked.isNotEmpty()
        fix.alpha = if (picked.isEmpty()) 0.4f else 1f
        fix.text = if (picked.isEmpty()) L.t("סמנו פנים שאינן של $name", "Tap faces that aren't $name")
        else L.t("זה לא $name (${picked.size})", "That's not $name (${picked.size})")
    }

    /** Who they really are: another person, or "someone else" (they wait under "מי זה?"). */
    private fun fixPicked() {
        if (picked.isEmpty()) return
        val others = PeopleStore.people(this).filter { it.id != personId }.map { it.name }
        val choices = others + L.t("מישהו אחר (אסמן אחר כך)", "Someone else (I'll name them later)")
        AlertDialog.Builder(this)
            .setTitle(L.t("אז מי זה?", "So who is it?"))
            .setItems(choices.toTypedArray()) { _, i ->
                val to = others.getOrNull(i)
                val ids = picked.toList()
                worker.execute {
                    PeopleStore.notThem(applicationContext, ids, personId, to)
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        picked.clear()
                        android.widget.Toast.makeText(this, L.t("תוקן ✓", "Fixed ✓"), android.widget.Toast.LENGTH_SHORT).show()
                        render()
                    }
                }
            }
            .setNegativeButton(L.t("ביטול", "Cancel"), null)
            .show()
    }
}

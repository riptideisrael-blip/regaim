package com.regaim.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.snackbar.Snackbar

/** "סל המחזור": deleted moments stay here for 30 days and can be brought back (like Google Photos). */
class TrashActivity : AppCompatActivity() {
    companion object {
        fun open(ctx: Context) = ctx.startActivity(Intent(ctx, TrashActivity::class.java))
    }

    private lateinit var content: LinearLayout
    private lateinit var ui: Ui

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_screen)
        findViewById<TextView>(R.id.screenTitle).text = L.t("סל המחזור", "Trash")
        findViewById<View>(R.id.btnClose).setOnClickListener { finish() }
        content = findViewById(R.id.screenContent)
        ui = Ui(this)
        render()
    }

    private fun render() {
        content.removeAllViews()
        val items = MemoryStore.trash(this)
        content.addView(ui.hint(L.t("רגעים שמחקתם נשמרים כאן ${MemoryStore.TRASH_DAYS} יום ואז נמחקים לצמיתות. הקבצים המקוריים בגלריה של הטלפון לא נמחקים.", "Deleted moments stay here for ${MemoryStore.TRASH_DAYS} days, then they're gone for good. The original files in your phone's gallery aren't deleted."), top = 14))
        if (items.isEmpty()) {
            content.addView(android.widget.ImageView(this).apply { setImageResource(R.drawable.ic_trash); setColorFilter(androidx.core.content.ContextCompat.getColor(this@TrashActivity, R.color.ink)); layoutParams = android.widget.LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)).apply { gravity = android.view.Gravity.CENTER_HORIZONTAL; topMargin = ui.dp(40) } })
            content.addView(ui.text(L.t("סל המחזור ריק", "Trash is empty"), 18f, bold = true, top = 8).apply { gravity = Gravity.CENTER })
            return
        }
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        bar.addView(ui.text(if (items.size == 1) L.t("רגע אחד", "1 moment") else L.t("${items.size} רגעים", "${items.size} moments"), 15f, bold = true).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        bar.addView(ui.action(L.t("שחזור הכל", "Restore all")) { restore(items.map { it.memory.id }.toSet()) })
        bar.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(ui.dp(16), 1) })
        bar.addView(ui.action(L.t("ריקון", "Empty trash"), R.color.danger) { confirmForever(items.map { it.memory.id }.toSet()) })
        content.addView(bar.apply { setPadding(0, ui.dp(16), 0, ui.dp(4)) })
        items.forEach { content.addView(row(it)) }
    }

    private fun row(t: MemoryStore.Trashed): View {
        val m = t.memory
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.card_white)
            setPadding(ui.dp(10), ui.dp(10), ui.dp(12), ui.dp(10))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ui.dp(10) }
        }
        val frame = FrameLayout(this).apply {
            setBackgroundResource(R.drawable.rounded_media)
            clipToOutline = true
            layoutParams = LinearLayout.LayoutParams(ui.dp(64), ui.dp(64))
        }
        val iv = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        frame.addView(iv)
        Covers.load(iv, m)
        card.addView(frame)
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(12), 0, ui.dp(8), 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        texts.addView(ui.text(Fmt.title(m), 15f, bold = true))
        texts.addView(ui.hint(if (t.daysLeft <= 1) L.t("יימחק לצמיתות מחר", "Deleted forever tomorrow") else L.t("יימחק לצמיתות בעוד ${t.daysLeft} ימים", "Deleted forever in ${t.daysLeft} days"), top = 2))
        card.addView(texts)
        card.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_restore)
            setColorFilter(ui.color(R.color.accent))
            setPadding(ui.dp(10), ui.dp(10), ui.dp(10), ui.dp(10))
            contentDescription = L.t("שחזור", "Restore")
            layoutParams = LinearLayout.LayoutParams(ui.dp(44), ui.dp(44))
            setOnClickListener { restore(setOf(m.id)) }
        })
        card.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_close)
            setColorFilter(ui.color(R.color.ink_soft))
            setPadding(ui.dp(11), ui.dp(11), ui.dp(11), ui.dp(11))
            contentDescription = L.t("מחיקה לצמיתות", "Delete forever")
            layoutParams = LinearLayout.LayoutParams(ui.dp(44), ui.dp(44))
            setOnClickListener { confirmForever(setOf(m.id)) }
        })
        return card
    }

    private fun restore(ids: Set<String>) {
        MemoryStore.restore(this, ids)
        render()
        Snackbar.make(content, if (ids.size == 1) L.t("הרגע חזר לזיכרונות", "Moment restored") else L.t("${ids.size} רגעים חזרו לזיכרונות", "${ids.size} moments restored"), Snackbar.LENGTH_SHORT).show()
    }

    private fun confirmForever(ids: Set<String>) {
        AlertDialog.Builder(this)
            .setTitle(if (ids.size == 1) L.t("למחוק לצמיתות?", "Delete forever?") else L.t("למחוק ${ids.size} רגעים לצמיתות?", "Delete ${ids.size} moments forever?"))
            .setMessage(L.t("אי אפשר לבטל את זה. הקבצים המקוריים בגלריה של הטלפון יישארו.", "This can't be undone. The original files in your phone's gallery will stay."))
            .setPositiveButton(L.t("מחיקה לצמיתות", "Delete forever")) { _, _ -> MemoryStore.deleteForever(this, ids); render() }
            .setNegativeButton(L.t("ביטול", "Cancel"), null)
            .show()
    }
}

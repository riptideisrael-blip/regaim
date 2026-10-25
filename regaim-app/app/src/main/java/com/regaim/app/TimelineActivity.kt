package com.regaim.app

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import android.graphics.Bitmap
import android.widget.Toast
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.Calendar

/**
 * "ציר הזמן": one child's life in Moments, month by month — how old they were, their milestones and firsts,
 * and the best moments of each month. Like a yearbook that writes itself.
 */
class TimelineActivity : AppCompatActivity() {
    companion object {
        private const val EXTRA_NAME = "name"
        fun open(ctx: Context, name: String) =
            ctx.startActivity(Intent(ctx, TimelineActivity::class.java).putExtra(EXTRA_NAME, name))

        /** Shown under each month: up to this many moments, the rest behind "more". */
        private const val PER_MONTH = 6
    }

    private lateinit var ui: Ui
    private lateinit var mv: MemoryViews
    private lateinit var content: LinearLayout
    private var name = ""
    private val worker = Executors.newSingleThreadExecutor()
    private val pickPhoto = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@registerForActivityResult
        worker.execute {
            val bmp = try { MediaImages.decode(applicationContext, uri.toString(), 1024) } catch (_: Exception) { null }
            runOnUiThread { if (bmp == null) toast(L.t("לא הצלחנו לקרוא את התמונה", "Couldn't read that photo")) else savePhoto(bmp) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_screen)
        findViewById<View>(R.id.btnClose).setOnClickListener { finish() }
        content = findViewById(R.id.screenContent)
        ui = Ui(this)
        mv = MemoryViews(this)
        name = intent.getStringExtra(EXTRA_NAME).orEmpty().ifEmpty { Profile.kids(this).firstOrNull()?.name.orEmpty() }
        render()
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        if (::content.isInitialized && content.childCount > 0) render()
    }

    private fun render() {
        content.removeAllViews()
        findViewById<TextView>(R.id.screenTitle).text = if (name.isEmpty()) L.t("ציר זמן", "Timeline") else L.t("ציר הזמן של $name", "$name's timeline")
        val kids = Profile.kids(this)
        if (kids.size > 1) {
            val names = kids.map { it.name }
            content.addView(ui.chips(names, names.indexOf(name).coerceAtLeast(0), top = 10) { i -> name = names[i]; render() })
        }
        if (name.isEmpty()) {
            content.addView(ui.text(L.t("עוד לא הוספתם ילדים", "No kids added yet"), 18f, bold = true, top = 24))
            content.addView(ui.hint(L.t("הוסיפו את הילדים בפרופיל, עם תאריך לידה, וכאן ייבנה ציר הזמן שלהם.", "Add your kids in the profile, with a birth date, and their timeline builds itself here.")))
            content.addView(ui.button(L.t("לפרופיל", "Open profile"), primary = true, top = 14) { startActivity(Intent(this, ProfileActivity::class.java)) })
            return
        }
        val birth = Profile.kids(this).firstOrNull { it.name == name }?.birth.orEmpty()
        val mine = MemoryStore.kept(this).filter { Fmt.names(it).contains(name) }.sortedByDescending { it.time }

        header(birth, mine)
        if (mine.isEmpty()) {
            content.addView(ui.hint(L.t(
                "עוד אין רגעים עם $name. כדי שנמצא את $name ברגעים, תנו את השם הזה לפנים במסך \"מי זה?\".",
                "No moments with $name yet. To find $name, give their face this name on the \"Who's this?\" screen.",
            ), top = 18))
            content.addView(ui.button(L.t("מי זה?", "Who's this?"), top = 12) { PeopleActivity.open(this) })
            return
        }

        var year = -1
        val months = mine.groupBy { Fmt.monthKey(it.time) }
        months.entries.forEachIndexed { i, (_, items) ->
            val y = Calendar.getInstance().apply { timeInMillis = items.first().time }.get(Calendar.YEAR)
            if (y != year) {
                year = y
                content.addView(ui.heading(y.toString(), 26f, top = if (i == 0) 22 else 30))
            }
            content.addView(monthBlock(items, birth, last = i == months.size - 1))
        }
        content.addView(ui.hint(L.t("כאן מתחיל הסיפור של $name ב-Moments ❤️", "This is where $name's story in Moments begins ❤️"), top = 14).apply { gravity = Gravity.CENTER })
    }

    /** Photo, name, age today, and a few numbers. */
    private fun header(birth: String, mine: List<Memory>) {
        val row = ui.horizontal(top = 16)
        row.addView(mv.avatar(name, 76, ring = mine.isNotEmpty()).apply {
            contentDescription = L.t("התמונה של $name", "$name's photo")
            setOnClickListener { photoMenu(mine) }
        })
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(16), 0, ui.dp(8), 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        texts.addView(ui.heading(name, 24f))
        val age = Profile.ageText(birth)
        if (age.isNotEmpty()) texts.addView(ui.hint(age, top = 2))
        row.addView(texts)
        content.addView(row)

        if (mine.isEmpty()) return
        val stats = ui.horizontal(top = 16)
        fun stat(n: Int, label: String) = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(ui.text(n.toString(), 18f, bold = true).apply { gravity = Gravity.CENTER })
            addView(ui.hint(label, top = 0).apply { gravity = Gravity.CENTER })
        }
        stats.addView(stat(mine.size, L.t("רגעים", "moments")))
        stats.addView(stat(mine.count { it.milestone.isNotEmpty() }, L.t("אבני דרך", "milestones")))
        stats.addView(stat(mine.groupBy { Fmt.monthKey(it.time) }.size, L.t("חודשים", "months")))
        stats.addView(stat(mine.count { it.favorite }, L.t("מועדפים", "favorites")))
        content.addView(stats)
        content.addView(ui.divider())
    }

    /** One month: a dot on the rail, the month and the child's age then, milestones, and the best moments. */
    private fun monthBlock(items: List<Memory>, birth: String, last: Boolean): View {
        val block = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ui.dp(14) }
        }
        // The rail: a dot and a thin line down to the next month.
        val rail = FrameLayout(this).apply { layoutParams = LinearLayout.LayoutParams(ui.dp(18), ViewGroup.LayoutParams.MATCH_PARENT) }
        if (!last) rail.addView(View(this).apply {
            setBackgroundColor(ui.color(R.color.line))
            layoutParams = FrameLayout.LayoutParams(ui.dp(2), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER_HORIZONTAL).apply { topMargin = ui.dp(8) }
        })
        rail.addView(View(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(ui.color(R.color.ink)) }
            layoutParams = FrameLayout.LayoutParams(ui.dp(10), ui.dp(10), Gravity.CENTER_HORIZONTAL).apply { topMargin = ui.dp(6) }
        })
        block.addView(rail)

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(10), 0, 0, ui.dp(6))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val first = items.minOf { it.time }
        val monthEnd = Calendar.getInstance().apply {
            timeInMillis = first; set(Calendar.DAY_OF_MONTH, getActualMaximum(Calendar.DAY_OF_MONTH))
        }.timeInMillis
        val titleRow = ui.horizontal(top = 0)
        titleRow.addView(ui.text(Fmt.month(first), 17f, bold = true).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        // WRAP_CONTENT: a full-width count would squeeze the month name to nothing (one letter per line).
        titleRow.addView(ui.hint(if (items.size == 1) L.t("רגע אחד", "1 moment") else L.t("${items.size} רגעים", "${items.size} moments"), top = 0).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        })
        body.addView(titleRow)
        val age = Profile.ageText(birth, monthEnd)
        if (age.isNotEmpty()) body.addView(ui.hint(age, top = 0))

        // Milestones and firsts, in the order they happened.
        for (m in items.filter { it.milestone.isNotEmpty() }.sortedBy { it.time }) {
            body.addView(TextView(this).apply {
                text = "★ ${m.milestone} · ${Fmt.day(m.time)}"
                textSize = 14f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(ui.color(R.color.ink))
                background = GradientDrawable().apply { cornerRadius = ui.dp(10).toFloat(); setColor(ui.color(R.color.fill)) }
                setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ui.dp(8) }
                setOnClickListener { MomentActivity.open(this@TimelineActivity, m.id) }
            })
        }

        // The month's best moments, in a 3-column grid of squares.
        val best = ReelMaker.best(items).take(PER_MONTH).sortedBy { it.time }
        val side = ((resources.displayMetrics.widthPixels - ui.dp(40 + 18 + 10) - ui.dp(4) * 2) / 3).coerceAtLeast(ui.dp(60))
        var line: LinearLayout? = null
        best.forEachIndexed { i, m ->
            if (i % 3 == 0) line = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ui.dp(if (i == 0) 10 else 4) }
            }.also { body.addView(it) }
            line?.addView(tile(m, side, gapStart = i % 3 != 0))
        }
        val more = items.size - best.size
        if (more > 0) body.addView(ui.action(L.t("עוד $more רגעים מהחודש ›", "$more more this month ›")) {
            startActivity(Intent(this, MemoriesActivity::class.java).putExtra(MemoriesActivity.EXTRA_PERSON, name))
        }.apply { setPadding(0, ui.dp(8), 0, 0) })
        block.addView(body)
        return block
    }

    private fun tile(m: Memory, side: Int, gapStart: Boolean): View {
        val frame = FrameLayout(this).apply {
            setBackgroundResource(R.drawable.rounded_media)
            clipToOutline = true
            layoutParams = LinearLayout.LayoutParams(side, side).apply { if (gapStart) marginStart = ui.dp(4) }
            setOnClickListener { MomentActivity.open(this@TimelineActivity, m.id) }
        }
        val img = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        frame.addView(img)
        Covers.load(img, m)
        if (m.isVideo) frame.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_play)
            setColorFilter(ui.color(R.color.white))
            layoutParams = FrameLayout.LayoutParams(ui.dp(18), ui.dp(18), Gravity.TOP or Gravity.END).apply { setMargins(ui.dp(6), ui.dp(6), ui.dp(6), ui.dp(6)) }
        })
        if (m.favorite) frame.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_heart)
            setColorFilter(ui.color(R.color.white))
            layoutParams = FrameLayout.LayoutParams(ui.dp(16), ui.dp(16), Gravity.BOTTOM or Gravity.START).apply { setMargins(ui.dp(6), ui.dp(6), ui.dp(6), ui.dp(6)) }
        })
        return frame
    }

    // ---------- the child's photo ----------

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun member(): FamilyMember? = Profile.members(this).firstOrNull { it.name == name }

    /** Tap on the photo: look at it, change it (gallery or one of the moments), or remove it. */
    private fun photoMenu(mine: List<Memory>) {
        val m = member() ?: run { startActivity(Intent(this, ProfileActivity::class.java)); return }
        val has = m.photo.isNotEmpty() && File(m.photo).exists()
        val options = ArrayList<Pair<String, () -> Unit>>()
        if (has) options.add(L.t("צפייה בתמונה", "View photo") to { viewPhoto(m.photo) })
        options.add(L.t("תמונה מהגלריה", "Photo from gallery") to { try { pickPhoto.launch("image/*") } catch (_: Exception) { } })
        if (mine.isNotEmpty()) options.add(L.t("תמונה מתוך הרגעים", "A photo from the moments") to { fromMoments(mine) })
        if (has) options.add(L.t("הסרת התמונה", "Remove photo") to { setPhoto("") })
        options.add(L.t("עריכת הפרטים (שם, תאריך לידה)", "Edit details (name, birthday)") to { startActivity(Intent(this, ProfileActivity::class.java)) })
        AlertDialog.Builder(this)
            .setTitle(name)
            .setItems(options.map { it.first }.toTypedArray()) { _, i -> options[i].second() }
            .show()
    }

    /** The photo, big, on black. Tap anywhere to close. */
    private fun viewPhoto(path: String) {
        val bmp = Avatars.photo(path, 1080) ?: android.graphics.BitmapFactory.decodeFile(path) ?: return
        val dialog = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val img = ImageView(this).apply {
            setImageBitmap(android.graphics.BitmapFactory.decodeFile(path) ?: bmp)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(0xFF000000.toInt())
            contentDescription = L.t("התמונה של $name", "$name's photo")
            setOnClickListener { dialog.dismiss() }
        }
        dialog.setContentView(img)
        dialog.show()
    }

    /** Pick one of the child's moments; we take the face from it. */
    private fun fromMoments(mine: List<Memory>) {
        val strip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(ui.dp(16), ui.dp(8), ui.dp(16), 0) }
        var dialog: AlertDialog? = null
        for (m in ReelMaker.best(mine).take(20)) strip.addView(mv.mini(m, 88, 88) {
            dialog?.dismiss()
            toast(L.t("מכינים את התמונה...", "Preparing the photo…"))
            worker.execute {
                val cover = try { Covers.get(applicationContext, m) } catch (_: Throwable) { null }
                val face = cover?.let { try { CartoonFilm.faceCrop(it) } catch (_: Throwable) { it } }
                runOnUiThread { if (face == null) toast(L.t("לא הצלחנו לקחת תמונה מהרגע", "Couldn't take a photo from that moment")) else savePhoto(face) }
            }
        })
        dialog = AlertDialog.Builder(this)
            .setTitle(L.t("בחרו רגע עם הפנים של $name", "Pick a moment with $name's face"))
            .setView(android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(strip) })
            .setNegativeButton(L.t("ביטול", "Cancel"), null)
            .show()
    }

    private fun savePhoto(bmp: Bitmap) {
        worker.execute {
            val path = try {
                val side = minOf(bmp.width, bmp.height)
                val square = Bitmap.createBitmap(bmp, (bmp.width - side) / 2, (bmp.height - side) / 2, side, side)
                val out = if (side > 768) Bitmap.createScaledBitmap(square, 768, 768, true) else square
                val dir = File(filesDir, "family_photos").apply { mkdirs() }
                val f = File(dir, "${UUID.randomUUID()}.jpg")
                f.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 88, it) }
                f.absolutePath
            } catch (_: Exception) { null }
            runOnUiThread {
                if (path == null) toast(L.t("לא הצלחנו לשמור את התמונה", "Couldn't save the photo"))
                else { setPhoto(path); toast(L.t("התמונה עודכנה ✓", "Photo updated ✓")) }
            }
        }
    }

    private fun setPhoto(path: String) {
        val all = Profile.members(this)
        if (all.none { it.name == name }) return
        Profile.saveMembers(this, all.map { if (it.name == name) it.copy(photo = path) else it })
        // A new face photo also teaches face recognition who this is.
        worker.execute { try { PeopleStore.enrollFamily(applicationContext) } catch (_: Throwable) { } }
        render()
    }
}

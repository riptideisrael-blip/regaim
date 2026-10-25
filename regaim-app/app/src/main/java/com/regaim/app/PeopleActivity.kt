package com.regaim.app

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Outline
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

/**
 * "אנשים", like in Google Photos: your named people, and groups of faces waiting for a name.
 * Name a face once and Moments finds that person in all moments, on the phone only.
 */
class PeopleActivity : AppCompatActivity() {

    companion object {
        fun open(ctx: Context) = ctx.startActivity(Intent(ctx, PeopleActivity::class.java))
    }

    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var known: LinearLayout
    private lateinit var unknown: LinearLayout
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_people)
        known = findViewById(R.id.knownPeople)
        unknown = findViewById(R.id.unknownFaces)
        status = findViewById(R.id.peopleStatus)
        findViewById<View>(R.id.btnClose).setOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        render()
        scan()
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun scan() {
        // Same person under two names ("יוסף" and "אני (יוסף)"): becomes one.
        worker.execute {
            try { PeopleStore.mergeDuplicates(applicationContext) } catch (_: Exception) { }
            runOnUiThread { if (!isFinishing) render() }
        }
        val pending = MemoryStore.kept(this).count { !it.facesScanned }
        if (pending == 0 && FaceId.ready(this)) {
            status.visibility = View.GONE
            return
        }
        status.visibility = View.VISIBLE
        status.text = if (!FaceId.ready(this)) L.t("מכינים את זיהוי הפנים (פעם אחת)...", "Getting face recognition ready (one time)...") else L.t("מחפש פנים ב-$pending רגעים...", "Looking for faces in $pending moments...")
        worker.execute {
            var total = 0
            while (true) {
                val n = try { PeopleStore.scanPending(applicationContext, 4) } catch (_: Throwable) { 0 }
                total += n
                runOnUiThread { if (!isFinishing) render() }
                if (n == 0) break
            }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (!FaceId.ready(this)) status.text = L.t("לא הצלחנו להכין את זיהוי הפנים. בדקו את החיבור לאינטרנט ונסו שוב.", "Couldn't get face recognition ready. Check your internet connection and try again.")
                else status.visibility = View.GONE
            }
        }
    }

    private fun render() {
        val d = resources.displayMetrics.density
        // Named people
        known.removeAllViews()
        val people = PeopleStore.people(this)
        findViewById<View>(R.id.knownEmpty).visibility = if (people.isEmpty()) View.VISIBLE else View.GONE
        for (row in people.chunked(4)) {
            val line = rowLayout()
            for (p in row) {
                val count = PeopleStore.countFor(this, p.name)
                line.addView(faceCell(p.photo, p.name, if (count == 1) L.t("רגע אחד", "1 moment") else L.t("$count רגעים", "$count moments")) { personMenu(p) })
            }
            known.addView(line)
        }
        // Faces waiting for a name
        unknown.removeAllViews()
        val groups = PeopleStore.unknownGroups(this)
        findViewById<View>(R.id.unknownEmpty).visibility = if (groups.isEmpty()) View.VISIBLE else View.GONE
        for (row in groups.take(24).chunked(4)) {
            val line = rowLayout()
            for (g in row) {
                // "Maybe Uri?": a suggestion when most of these faces look like someone already named.
                val maybe = PeopleStore.guess(people, g)
                val title = if (maybe != null) L.t("אולי ${maybe.name}?", "Maybe ${maybe.name}?") else L.t("מי זה?", "Who's this?")
                line.addView(faceCell(g.first().path, title, if (g.size > 1) L.t("${g.size} פעמים", "${g.size} times") else "") { nameGroup(g, maybe) })
            }
            unknown.addView(line)
        }
        known.setPadding(0, (4 * d).toInt(), 0, 0)
    }

    private fun rowLayout() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    }

    private fun faceCell(path: String, title: String, sub: String, onClick: () -> Unit): View {
        val d = resources.displayMetrics.density
        val cell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding((4 * d).toInt(), (8 * d).toInt(), (4 * d).toInt(), (8 * d).toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { onClick() }
        }
        val img = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams((68 * d).toInt(), (68 * d).toInt())
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundResource(R.drawable.bubble_placeholder)
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) = outline.setOval(0, 0, view.width, view.height)
            }
            clipToOutline = true
            BitmapFactory.decodeFile(path)?.let { setImageBitmap(it) }
        }
        cell.addView(img)
        cell.addView(TextView(this).apply {
            text = title
            textSize = 14f
            setTextColor(ContextCompat.getColor(this@PeopleActivity, R.color.ink))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, (6 * d).toInt(), 0, 0)
        })
        if (sub.isNotEmpty()) cell.addView(TextView(this).apply {
            text = sub
            textSize = 12f
            setTextColor(ContextCompat.getColor(this@PeopleActivity, R.color.ink_soft))
            gravity = Gravity.CENTER
        })
        return cell
    }

    private fun nameGroup(group: List<FaceShot>, maybe: Person? = null) {
        if (maybe != null) {
            // One tap to confirm the suggestion; otherwise the usual choice.
            AlertDialog.Builder(this)
                .setTitle(L.t("זה ${maybe.name}?", "Is this ${maybe.name}?"))
                .setPositiveButton(L.t("כן, זה ${maybe.name}", "Yes, it's ${maybe.name}")) { _, _ -> assign(group, maybe.name) }
                .setNegativeButton(L.t("לא, מישהו אחר", "No, someone else")) { _, _ ->
                    worker.execute { PeopleStore.notGuess(applicationContext, group, maybe.id) }
                    nameGroup(group, null)
                }
                .show()
            return
        }
        val names = PeopleStore.people(this).map { it.name }
        val field = EditText(this).apply {
            hint = L.t("השם שבו תרצו שיופיע בזיכרונות", "The name you'd like to see in your memories")
            val p = (20 * resources.displayMetrics.density).toInt()
            setPadding(p, p / 2, p, p / 2)
        }
        // Existing people as pictures (one tap), and below them a name for someone new.
        var dlg: AlertDialog? = null
        val box = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL }
        if (names.isNotEmpty()) box.addView(MemoryViews(this).peopleGrid(names) { i -> dlg?.dismiss(); assign(group, names[i]) })
        box.addView(field)
        val b = AlertDialog.Builder(this)
            .setTitle(L.t("מי זה?", "Who's this?"))
            .setView(android.widget.ScrollView(this).apply { addView(box) })
            .setPositiveButton(L.t("שמור", "Save")) { _, _ -> assign(group, field.text.toString()) }
            .setNeutralButton(L.t("לא חשוב", "Skip")) { _, _ ->
                worker.execute { PeopleStore.ignore(applicationContext, group.map { it.id }); runOnUiThread { render() } }
            }
            .setNegativeButton(L.t("ביטול", "Cancel"), null)
        dlg = b.show()
    }

    private fun assign(group: List<FaceShot>, name: String) {
        if (name.isBlank()) return
        worker.execute {
            PeopleStore.assign(applicationContext, group.map { it.id }, name)
            runOnUiThread { if (!isFinishing) render() }
        }
    }

    private fun personMenu(p: Person) {
        AlertDialog.Builder(this)
            .setTitle(p.name)
            .setItems(arrayOf(L.t("הצג את הרגעים של ${p.name}", "Show ${p.name}'s moments"), L.t("שנה שם", "Rename"), L.t("שכח את האדם הזה", "Forget this person"),
                L.t("זה אותו אדם כמו...", "This is the same person as..."),
                L.t("בדיקת הפנים של ${p.name} (תיקון טעויות)", "Check ${p.name}'s faces (fix mistakes)"))) { _, i ->
                when (i) {
                    0 -> startActivity(Intent(this, MemoriesActivity::class.java)
                        .putExtra(MemoriesActivity.EXTRA_PERSON, p.name)
                        .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                    1 -> {
                        val field = EditText(this).apply { setText(p.name) }
                        AlertDialog.Builder(this).setTitle(L.t("שם חדש", "New name")).setView(field)
                            .setPositiveButton(L.t("שמור", "Save")) { _, _ ->
                                worker.execute { PeopleStore.rename(applicationContext, p.id, field.text.toString()); runOnUiThread { render() } }
                            }
                            .setNegativeButton(L.t("ביטול", "Cancel"), null).show()
                    }
                    2 -> worker.execute { PeopleStore.forget(applicationContext, p.id); runOnUiThread { render() } }
                    3 -> {
                        // Join two entries of one person: the faces of this one move to the chosen one (its name stays).
                        val others = PeopleStore.people(this).filter { it.id != p.id }
                        if (others.isEmpty()) return@setItems
                        AlertDialog.Builder(this).setTitle(L.t("${p.name} הוא בעצם...", "${p.name} is actually..."))
                            .setItems(others.map { it.name }.toTypedArray()) { _, k ->
                                worker.execute { PeopleStore.merge(applicationContext, p.id, others[k].id); runOnUiThread { render() } }
                            }
                            .setNegativeButton(L.t("ביטול", "Cancel"), null).show()
                    }
                    4 -> PersonFacesActivity.open(this, p.id)
                }
            }
            .show()
    }
}

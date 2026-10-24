package com.regaim.app

import android.graphics.BitmapFactory
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/** "זיכרונות": every moment as a bubble, grouped by day with the story of the day, filterable by category. */
private const val ROUND_GAP_DP = 4

class MemoriesActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PERSON = "person"
        private const val PEOPLE_PICKER = "__people__"
    }

    private sealed class Row {
        class Header(val day: String, val title: String, val story: String, val count: Int) : Row()
        class Bubble(val m: Memory) : Row()
        class Custom(val view: View) : Row()
    }

    private lateinit var chipsScroll: View
    private lateinit var mv: MemoryViews

    private lateinit var list: RecyclerView
    private lateinit var empty: TextView
    private lateinit var chips: LinearLayout
    private val adapter = RowAdapter()
    private val worker = Executors.newSingleThreadExecutor()
    private var filter = "הכל"
    private var selecting = false
    private val selected = linkedSetOf<String>()
    private lateinit var selectBar: LinearLayout
    private lateinit var selectCount: TextView
    private var shownIds: List<String> = emptyList()
    private var shownIdsByDay: Map<String, List<String>> = emptyMap()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_memories)
        list = findViewById(R.id.bubbleList)
        empty = findViewById(R.id.emptyText)
        chips = findViewById(R.id.filterChips)

        val grid = GridLayoutManager(this, 6)
        grid.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int) = if (adapter.rows[position] is Row.Bubble) 2 else 6 // 3 squares a row
        }
        list.layoutManager = grid
        list.adapter = adapter
        // The grid reaches the screen edges, like a profile grid; headers and sections keep their own padding.
        list.setPadding(0, list.paddingTop, 0, list.paddingBottom)
        Nav.setup(this, Nav.Tab.MEMORIES)
        mv = MemoryViews(this)
        // The filter chips move into the list, under the Stories / People / Albums section.
        chipsScroll = chips.parent as View
        (chipsScroll.parent as ViewGroup).removeView(chipsScroll)
        findViewById<View>(R.id.btnPeople).visibility = View.GONE
        findViewById<View>(R.id.btnMilestones).visibility = View.GONE
        findViewById<View>(R.id.btnPeople).setOnClickListener { PeopleActivity.open(this) }
        findViewById<View>(R.id.btnMilestones).setOnClickListener {
            startActivity(android.content.Intent(this, MilestonesActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        }
        intent.getStringExtra(EXTRA_PERSON)?.let { filter = "👤$it"; cameFromOutside = true }
        buildSelectBar()
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    selecting -> stopSelecting()
                    // Opened for one person from another screen (e.g. Family): back goes back there.
                    isFocused() && cameFromOutside -> { cameFromOutside = false; filter = "הכל"; reload(); isEnabled = false; onBackPressedDispatcher.onBackPressed(); isEnabled = true }
                    isFocused() -> closeAlbum()
                    else -> { isEnabled = false; onBackPressedDispatcher.onBackPressed(); isEnabled = true }
                }
            }
        })
    }

    // ---------- selecting several moments (Google Photos style) ----------
    //  • a visible "בחירה" button in the header (no hidden gesture needed), or a long press on any moment
    //  • every tile shows a check circle; a check next to each day selects the whole day
    //  • the header turns into "✕  3 נבחרו  ·  בחירת הכל", the bottom tabs turn into the actions
    //  • delete moves to the trash with "ביטול" — nothing is gone for good for 30 days

    private lateinit var header: View
    private lateinit var selectTop: LinearLayout
    private lateinit var nav: View

    private fun buildSelectBar() {
        val ui = Ui(this)
        header = findViewById<View>(R.id.btnPeople).parent as View
        nav = (list.parent as ViewGroup).let { it.getChildAt(it.childCount - 1) }
        val root = list.parent as ViewGroup

        // header extras: trash + select
        (header as LinearLayout).apply {
            addView(ImageView(this@MemoriesActivity).apply {
                setImageResource(R.drawable.ic_trash)
                setColorFilter(ui.color(R.color.ink_soft))
                setPadding(ui.dp(14), ui.dp(13), ui.dp(14), ui.dp(13))
                contentDescription = L.t("סל המחזור", "Trash")
                background = ContextCompat.getDrawable(this@MemoriesActivity, R.drawable.chat_chip_bg)
                layoutParams = LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)).apply { marginEnd = ui.dp(8) }
                setOnClickListener { TrashActivity.open(this@MemoriesActivity) }
            })
            addView(TextView(this@MemoriesActivity).apply {
                text = L.t("בחירה", "Select")
                textSize = 14f
                setTextColor(ui.color(R.color.accent_dark))
                typeface = DS.emphasis
                background = ContextCompat.getDrawable(this@MemoriesActivity, R.drawable.chat_chip_bg)
                setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(8))
                setOnClickListener { startSelecting(null) }
            })
            Nav.addProfileButton(this@MemoriesActivity, this)
        }

        // contextual top bar
        selectTop = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(ui.dp(8), ui.dp(16), ui.dp(16), ui.dp(4))
            visibility = View.GONE
        }
        selectTop.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_close)
            setColorFilter(ui.color(R.color.ink))
            setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12))
            contentDescription = L.t("סיום בחירה", "Done selecting")
            layoutParams = LinearLayout.LayoutParams(ui.dp(48), ui.dp(48))
            setOnClickListener { stopSelecting() }
        })
        selectCount = ui.text("", 22f, bold = true).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = ui.dp(4) }
        }
        selectTop.addView(selectCount)
        selectAll = ui.action(L.t("בחירת הכל", "Select all")) {
            if (selected.size == shownIds.size) selected.clear() else { selected.clear(); selected.addAll(shownIds) }
            updateSelection()
        }
        selectTop.addView(selectAll)
        root.addView(selectTop, root.indexOfChild(header) + 1)

        // bottom actions (replace the tabs while selecting)
        selectBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundResource(R.drawable.nav_bg)
            minimumHeight = ui.dp(64)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            visibility = View.GONE
        }
        fun act(icon: Int, label: String, color: Int = R.color.ink, f: () -> Unit) {
            selectBar.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                setBackgroundResource(android.R.drawable.list_selector_background)
                addView(ImageView(this@MemoriesActivity).apply {
                    setImageResource(icon); setColorFilter(ui.color(color))
                    layoutParams = LinearLayout.LayoutParams(ui.dp(24), ui.dp(24))
                })
                addView(TextView(this@MemoriesActivity).apply {
                    text = label; textSize = 12f; setTextColor(ui.color(color))
                    setPadding(0, ui.dp(3), 0, 0)
                })
                setOnClickListener { if (selected.isEmpty()) hintPick() else f() }
            })
        }
        act(R.drawable.ic_share_outline, L.t("שיתוף", "Share")) { shareSelected() }
        act(R.drawable.ic_heart_outline, L.t("מועדפים", "Favorite")) { favoriteSelected() }
        act(R.drawable.ic_edit, L.t("עריכה", "Edit")) { editSelected() }
        act(R.drawable.ic_trash, L.t("מחיקה", "Delete"), R.color.danger) { deleteSelected() }
        root.addView(selectBar, root.indexOfChild(nav))
    }

    private lateinit var selectAll: TextView

    private fun hintPick() = Toast.makeText(this, L.t("סמנו קודם רגע אחד או יותר", "Select one or more moments first"), Toast.LENGTH_SHORT).show()

    private fun startSelecting(first: String?) {
        selecting = true
        selected.clear()
        first?.let { selected.add(it) }
        list.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        updateSelection()
    }

    private fun stopSelecting() {
        selecting = false
        selected.clear()
        updateSelection()
    }

    private fun toggle(id: String) {
        if (!selected.remove(id)) selected.add(id)
        updateSelection()
    }

    private fun toggleDay(day: String) {
        val ids = MemoryStore.kept(this).filter { it.dayKey == day && it.id in shownIds }.map { it.id }
        if (ids.all { it in selected }) selected.removeAll(ids.toSet()) else selected.addAll(ids)
        updateSelection()
    }

    private fun updateSelection() {
        header.visibility = if (selecting) View.GONE else View.VISIBLE
        selectTop.visibility = if (selecting) View.VISIBLE else View.GONE
        selectBar.visibility = if (selecting) View.VISIBLE else View.GONE
        nav.visibility = if (selecting) View.GONE else View.VISIBLE
        selectCount.text = when (selected.size) { 0 -> L.t("בחרו רגעים", "Select moments"); 1 -> L.t("נבחר 1", "1 selected"); else -> L.t("נבחרו ${selected.size}", "${selected.size} selected") }
        selectAll.text = if (selected.isNotEmpty() && selected.size == shownIds.size) L.t("ניקוי", "Clear") else L.t("בחירת הכל", "Select all")
        adapter.notifyDataSetChanged()
    }

    private fun favoriteSelected() {
        val ids = selected.toSet()
        val all = MemoryStore.kept(this).filter { it.id in ids }
        val makeFav = all.any { !it.favorite }
        MemoryStore.updateMany(this, ids) { it.copy(favorite = makeFav) }
        if (makeFav) all.filter { !it.favorite }.forEach { Detections.feedbackFor(this, it.id, 1) }
        snack(if (makeFav) L.t("נוספו למועדפים ♥", "Added to favorites ♥") else L.t("הוסרו מהמועדפים", "Removed from favorites"))
        stopSelecting(); reload()
    }

    private fun shareSelected() {
        val items = MemoryStore.kept(this).filter { it.id in selected }
        if (items.size == 1) { SocialShare.show(this, items[0]); return }
        try {
            val uris = ArrayList(items.map { m ->
                if (m.uri.startsWith("content://")) android.net.Uri.parse(m.uri)
                else androidx.core.content.FileProvider.getUriForFile(this, "$packageName.files", java.io.File(m.uri))
            })
            val send = android.content.Intent(android.content.Intent.ACTION_SEND_MULTIPLE)
                .setType(if (items.all { !it.isVideo }) "image/*" else if (items.all { it.isVideo }) "video/*" else "*/*")
                .putParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM, uris)
                .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(android.content.Intent.createChooser(send, L.t("לשתף ${items.size} רגעים", "Share ${items.size} moments")))
        } catch (_: Exception) {
            snack(L.t("לא הצלחנו לשתף את הרגעים האלה", "Couldn't share these moments"))
        }
    }

    private fun editSelected() {
        val ids = selected.toSet()
        AlertDialog.Builder(this).setTitle(if (ids.size == 1) L.t("עריכת רגע", "Edit moment") else L.t("עריכת ${ids.size} רגעים", "Edit ${ids.size} moments"))
            .setItems(arrayOf(L.t("שינוי קטגוריה", "Change category"), L.t("כותרת אחת לכולם", "One title for all"), L.t("הפיכה לפרטי (רק אצלי)", "Make private (only me)"))) { _, which ->
                when (which) {
                    0 -> {
                        val cats = Categories.ALL.toTypedArray()
                        AlertDialog.Builder(this).setTitle(L.t("איזו קטגוריה?", "Which category?")).setItems(cats.map { L.category(it) }.toTypedArray()) { _, i ->
                            MemoryStore.updateMany(this, ids) { it.copy(category = cats[i]) }
                            snack(L.t("הקטגוריה עודכנה", "Category updated")); stopSelecting(); reload()
                        }.setNegativeButton(L.t("ביטול", "Cancel"), null).show()
                    }
                    1 -> {
                        val pad = Ui(this).dp(20)
                        val input = android.widget.EditText(this).apply { hint = L.t("כותרת", "Title"); setPadding(pad, pad / 2, pad, pad / 2) }
                        AlertDialog.Builder(this).setTitle(L.t("כותרת לכולם", "Title for all")).setView(input)
                            .setPositiveButton(L.t("שמירה", "Save")) { _, _ ->
                                val t = input.text.toString().trim().take(80)
                                if (t.isNotEmpty()) {
                                    MemoryStore.updateMany(this, ids) { it.copy(title = t, titleByUser = true) }
                                    snack(L.t("הכותרת עודכנה", "Title updated")); stopSelecting(); reload()
                                }
                            }.setNegativeButton(L.t("ביטול", "Cancel"), null).show()
                    }
                    else -> {
                        MemoryStore.updateMany(this, ids) { it.copy(privacy = if (Community.wasPublished(this, it)) it.privacy else "private") }
                        snack(L.t("סומנו כפרטיים", "Marked as private")); stopSelecting(); reload()
                    }
                }
            }.setNegativeButton(L.t("ביטול", "Cancel"), null).show()
    }

    /** No scary dialog: move to the trash and offer "undo", like Google Photos. */
    private fun deleteSelected() {
        val ids = selected.toSet()
        try { MemoryStore.moveToTrash(this, ids) } catch (e: Exception) { snack(e.message ?: L.t("המחיקה נכשלה", "Couldn't delete")); return }
        stopSelecting(); reload()
        snack(if (ids.size == 1) L.t("הרגע הועבר לסל המחזור", "Moment moved to trash") else L.t("${ids.size} רגעים הועברו לסל המחזור", "${ids.size} moments moved to trash"), L.t("ביטול", "Undo")) {
            MemoryStore.restore(this, ids); reload()
        }
    }

    private fun snack(text: String, action: String? = null, onAction: (() -> Unit)? = null) {
        val bar = com.google.android.material.snackbar.Snackbar.make(list, text, if (action != null) 6000 else com.google.android.material.snackbar.Snackbar.LENGTH_SHORT)
        if (action != null && onAction != null) bar.setAction(action) { onAction() }
        bar.setAnchorView(if (selecting) selectBar else nav)
        bar.show()
    }

    /** One-time tip so people discover selecting. */
    private fun maybeTip(count: Int) {
        val prefs = Settings.prefs(this)
        if (count < 3 || prefs.getBoolean("tip_select", false)) return
        prefs.edit().putBoolean("tip_select", true).apply()
        list.postDelayed({
            if (!isFinishing && !selecting) snack(L.t("טיפ: לחצו על \"בחירה\" או לחיצה ארוכה על רגע כדי לבחור כמה בבת אחת", "Tip: tap \"Select\" or long-press a moment to pick several at once"), L.t("הבנתי", "Got it")) { }
        }, 900)
    }

    override fun onResume() {
        super.onResume()
        reload()
        if (ClaudeClient.hasKey(this)) {
            worker.execute {
                val described = MemoryStore.analyzePending(applicationContext)
                val wrote = MemoryStore.refreshStories(applicationContext)
                if (described > 0 || wrote) runOnUiThread { if (!isFinishing) reload() }
            }
        }
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun reload() {
        val all = MemoryStore.kept(this)
        findViewById<TextView>(R.id.archiveCount)?.text = if (all.size == 1) L.t("זיכרון אחד בטלפון", "1 memory on this phone") else L.t("${all.size} זיכרונות בטלפון", "${all.size} memories on this phone")
        buildChips(all)
        // An album or a person is its own page: a header with the way back, then only their moments.
        val inAlbum = isFocused()
        val shown = when {
            filter.startsWith("📅") -> all.filter { Fmt.monthKey(it.time) == filter.removePrefix("📅") }
            else -> filterOf(all)
        }
        val rows = mutableListOf<Row>()
        if (inAlbum) {
            rows.add(Row.Custom(albumHeader(shown)))
        } else {
            if (all.isNotEmpty()) rows.add(Row.Custom(topSection(all)))
            rows.add(Row.Custom(chipsScroll))
        }
        buildRows(rows, shown, all)
    }

    /** True when this page was opened from another screen for one person, so "back" returns there. */
    private var cameFromOutside = false

    private fun isFocused() = filter.startsWith("📅") || filter.startsWith("👤")

    private fun openAlbum(key: String) {
        filter = "📅$key"
        reload()
        list.scrollToPosition(0)
    }

    private fun closeAlbum() {
        filter = "הכל"
        reload()
        list.scrollToPosition(0)
    }

    private fun albumHeader(shown: List<Memory>): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(mv.dp(16), mv.dp(4), mv.dp(16), mv.dp(8))
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        box.addView(TextView(this).apply {
            text = L.t("→ חזרה לכל הרגעים", "← Back to all moments")
            textSize = 15f
            typeface = DS.emphasis
            setTextColor(ContextCompat.getColor(this@MemoriesActivity, R.color.accent_dark))
            background = ContextCompat.getDrawable(this@MemoriesActivity, R.drawable.chat_chip_bg)
            setPadding(mv.dp(16), mv.dp(9), mv.dp(16), mv.dp(9))
            setOnClickListener { closeAlbum() }
        })
        if (filter.startsWith("👤")) {
            val who = filter.removePrefix("👤")
            box.addView(mv.sectionTitle(L.t("הרגעים של $who", "$who's moments"), top = 18, icon = R.drawable.ic_face))
            if (Profile.kids(this).any { it.name == who }) box.addView(TextView(this).apply {
                text = L.t("ציר הזמן של $who ›", "$who's timeline ›")
                textSize = 14f
                typeface = DS.emphasis
                setTextColor(ContextCompat.getColor(this@MemoriesActivity, R.color.accent))
                setPadding(mv.dp(2), mv.dp(6), mv.dp(2), mv.dp(2))
                setOnClickListener { TimelineActivity.open(this@MemoriesActivity, who) }
            })
        } else {
            val title = shown.firstOrNull()?.let { Fmt.month(it.time) } ?: L.t("אלבום", "Album")
            box.addView(mv.sectionTitle(L.t("אלבום: $title", "Album: $title"), top = 18, icon = R.drawable.ic_photos))
        }
        box.addView(TextView(this).apply {
            text = if (shown.size == 1) L.t("רגע אחד", "1 moment") else L.t("${shown.size} רגעים", "${shown.size} moments")
            textSize = 14f
            setTextColor(ContextCompat.getColor(this@MemoriesActivity, R.color.ink_soft))
            setPadding(mv.dp(2), mv.dp(2), mv.dp(2), 0)
        })
        return box
    }

    private fun filterOf(all: List<Memory>): List<Memory> = when (filter) {
            "הכל" -> all
            "מועדפים" -> all.filter { it.favorite }
            in personFilters(all) -> all.filter { m -> Fmt.names(m).contains(filter.removePrefix("👤")) }
            else -> all.filter { it.category == filter }
        }

    private fun buildRows(rows: MutableList<Row>, shown: List<Memory>, all: List<Memory>) {
        for ((day, items) in shown.groupBy { it.dayKey }) {
            rows.add(Row.Header(day, dayTitle(items.first().time), MemoryStore.story(this, day), items.size))
            items.forEach { rows.add(Row.Bubble(it)) }
        }
        adapter.rows = rows
        shownIds = shown.map { it.id }
        shownIdsByDay = shown.groupBy { it.dayKey }.mapValues { e -> e.value.map { it.id } }
        selected.retainAll(shownIds.toSet())
        maybeTip(all.size)
        adapter.notifyDataSetChanged()
        empty.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
        empty.text = if (all.isEmpty()) getString(R.string.bubbles_empty) else L.t("אין רגעים בקטגוריה הזאת עדיין", "No moments in this category yet")
    }

    private fun buildChips(all: List<Memory>) {
        chips.removeAllViews()
        // Short and predictable: All · Favorites · one "by person" chip · the categories that have moments.
        // ("Other" only shows when it's the active filter; every person used to get a chip of their own.)
        val present = Categories.ALL.filter { c -> (c != "אחר" || filter == c) && all.any { it.category == c } }
        val people = personFilters(all)
        val personChip = when {
            filter.startsWith("👤") -> filter
            people.isNotEmpty() -> PEOPLE_PICKER
            else -> null
        }
        val options = listOf("הכל", "מועדפים") + listOfNotNull(personChip) + present
        for (o in options) {
            val chip = layoutInflater.inflate(R.layout.item_chip, chips, false) as TextView
            chip.text = when {
                o == "הכל" -> L.t("הכל", "All")
                o == "מועדפים" -> L.category(o)
                o == PEOPLE_PICKER -> L.t("לפי אדם ▾", "By person ▾")
                o.startsWith("👤") -> o.removePrefix("👤") + "  ✕"
                else -> (Fmt.CATEGORY_EMOJI[o]?.let { "$it " } ?: "") + L.category(o)
            }
            val selected = o == filter
            val bg = GradientDrawable().apply {
                cornerRadius = 40f * resources.displayMetrics.density
                setColor(ContextCompat.getColor(this@MemoriesActivity, if (selected) R.color.ds_primary else R.color.fill))
            }
            chip.background = bg
            chip.setTextColor(if (selected) DS.onButton(this) else ContextCompat.getColor(this, R.color.ink))
            chip.setOnClickListener {
                when {
                    o == PEOPLE_PICKER -> pickPerson(people)
                    o.startsWith("👤") && o == filter -> { filter = "הכל"; reload() }
                    else -> { filter = o; reload() }
                }
            }
            chips.addView(chip)
        }
    }

    private fun pickPerson(people: List<String>) {
        val names = people.map { it.removePrefix("👤") }
        var dlg: AlertDialog? = null
        val grid = MemoryViews(this).peopleGrid(names) { i ->
            dlg?.dismiss()
            filter = people[i]
            reload()
            list.scrollToPosition(0)
        }
        dlg = AlertDialog.Builder(this)
            .setTitle(L.t("הרגעים של מי?", "Whose moments?"))
            .setView(android.widget.ScrollView(this).apply { addView(grid) })
            .setNegativeButton(L.t("ביטול", "Cancel"), null)
            .show()
    }

    // ---------- Stories · People · Albums ----------

    private fun cover(m: Memory, h: Int, w: Int = ViewGroup.LayoutParams.MATCH_PARENT, weight: Float = 0f): View {
        val frame = android.widget.FrameLayout(this).apply {
            setBackgroundResource(R.drawable.rounded_media)
            clipToOutline = true
            layoutParams = if (weight > 0f) LinearLayout.LayoutParams(0, h, weight) else LinearLayout.LayoutParams(w, h)
        }
        val iv = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        frame.addView(iv)
        Covers.load(iv, m)
        return frame
    }

    private fun tileText(box: LinearLayout, title: String, sub: String) {
        box.addView(TextView(this).apply {
            text = title
            textSize = 16f
            typeface = DS.emphasis
            setTextColor(ContextCompat.getColor(this@MemoriesActivity, R.color.ink))
            setPadding(mv.dp(2), mv.dp(8), mv.dp(2), 0)
        })
        box.addView(TextView(this).apply {
            text = sub
            textSize = 12f
            setTextColor(ContextCompat.getColor(this@MemoriesActivity, R.color.ink_soft))
            setPadding(mv.dp(2), 0, mv.dp(2), 0)
        })
    }

    private fun topSection(all: List<Memory>): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(mv.dp(16), mv.dp(4), mv.dp(16), mv.dp(8))
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val best = AlbumCovers.faceFirst(all, Facts.confident(this))
        val tiles = mutableListOf<View>()

        // Search or ask: "ask Moments" lives here now, where people look for things.
        box.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            background = ContextCompat.getDrawable(this@MemoriesActivity, R.drawable.chat_input_bg)
            setPadding(mv.dp(16), mv.dp(12), mv.dp(16), mv.dp(12))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = mv.dp(14) }
            addView(mv.icon(R.drawable.ic_search, 20, R.color.ink_soft))
            addView(TextView(this@MemoriesActivity).apply {
                text = L.t("חפשו או שאלו: \"מתי צחקנו הכי הרבה?\"", "Search or ask: \"When did we laugh the most?\"")
                textSize = 15f
                setTextColor(ContextCompat.getColor(this@MemoriesActivity, R.color.ink_soft))
                setPadding(mv.dp(10), 0, 0, 0)
            })
            setOnClickListener { startActivity(android.content.Intent(this@MemoriesActivity, ChatActivity::class.java)) }
        })

        // Stories: a collage of the best moments.
        val stories = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setOnClickListener { StoriesActivity.open(this@MemoriesActivity) } }
        val collage = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, mv.dp(176)) }
        best.getOrNull(0)?.let { collage.addView(cover(it, mv.dp(176), weight = 1.4f)) }
        val side = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, mv.dp(176), 1f).apply { marginStart = mv.dp(4) }
        }
        best.getOrNull(1)?.let { side.addView(cover(it, mv.dp(86))) }
        best.getOrNull(2)?.let { side.addView(cover(it, mv.dp(86)).apply { (layoutParams as LinearLayout.LayoutParams).topMargin = mv.dp(4) }) }
        if (side.childCount > 0) collage.addView(side)
        stories.addView(collage)
        tileText(stories, L.t("סיפורים", "Stories"), L.t("${Topics.all(this).size} נושאים", "${Topics.all(this).size} topics"))
        tiles.add(stories)

        // People: up to four faces.
        val names = (Profile.kids(this).map { it.name } + PeopleStore.people(this).map { it.name } + all.flatMap { Fmt.names(it) }).distinct()
        // People: choose a person and see their moments right here (no names yet → name the faces).
        val people = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setOnClickListener {
                val pf = personFilters(all)
                if (pf.isNotEmpty()) pickPerson(pf) else PeopleActivity.open(this@MemoriesActivity)
            }
        }
        val faces = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            setBackgroundResource(R.drawable.rounded_media)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, mv.dp(176))
        }
        if (names.isEmpty()) {
            faces.addView(mv.icon(R.drawable.ic_tab_family, 44, R.color.ink_soft))
        } else names.take(4).chunked(2).forEach { pair ->
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER }
            pair.forEach { n -> r.addView(mv.avatar(n, 70).apply { (layoutParams as LinearLayout.LayoutParams).setMargins(mv.dp(5), mv.dp(5), mv.dp(5), mv.dp(5)) }) }
            faces.addView(r)
        }
        people.addView(faces)
        tileText(people, L.t("אנשים", "People"), if (names.isEmpty()) L.t("תנו שמות לפנים", "Name the faces") else "${names.size}")
        tiles.add(people)
        box.addView(mv.grid(tiles, 12))

        // Albums: one per month, plus milestones.
        box.addView(mv.sectionTitle(L.t("אלבומים", "Albums"), top = 26, icon = R.drawable.ic_photos))
        val albums = mutableListOf<View>()
        val milestones = all.filter { it.milestone.isNotEmpty() }
        if (milestones.isNotEmpty()) albums.add(album("milestones", L.t("אבני דרך", "Milestones"), milestones) {
            startActivity(android.content.Intent(this, MilestonesActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        })
        for ((key, ms) in Topics.months(this).take(6)) {
            albums.add(album("m$key", Fmt.month(ms.first().time), ms) { openAlbum(key) })
        }
        box.addView(mv.grid(albums, 12))
        if (albums.isNotEmpty()) box.addView(TextView(this).apply {
            text = L.t("לחיצה ארוכה על אלבום: בחירת תמונה ראשית", "Long-press an album to choose its cover")
            textSize = 12f
            setTextColor(ContextCompat.getColor(this@MemoriesActivity, R.color.ink_soft))
            setPadding(mv.dp(2), mv.dp(8), mv.dp(2), 0)
        })
        box.addView(mv.sectionTitle(L.t("כל הרגעים", "All moments"), top = 26, icon = R.drawable.ic_clock))
        return box
    }

    private fun album(key: String, title: String, ms: List<Memory>, onClick: () -> Unit): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setOnClickListener { onClick() }
            setOnLongClickListener { chooseCover(key, title, ms); true }
        }
        val top = AlbumCovers.ordered(this, key, ms)
        top.getOrNull(0)?.let { box.addView(cover(it, mv.dp(110))) }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, mv.dp(56)).apply { topMargin = mv.dp(4) }
        }
        top.drop(1).take(3).forEachIndexed { i, m ->
            row.addView(cover(m, mv.dp(56), weight = 1f).apply { if (i > 0) (layoutParams as LinearLayout.LayoutParams).marginStart = mv.dp(4) })
        }
        if (row.childCount > 0) box.addView(row)
        tileText(box, title, if (ms.size == 1) L.t("רגע אחד", "1 moment") else L.t("${ms.size} רגעים", "${ms.size} moments"))
        return box
    }

    /** A grid of the album's moments: tap one to make it the album's main picture. */
    private fun chooseCover(key: String, title: String, ms: List<Memory>) {
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(mv.dp(12), mv.dp(8), mv.dp(12), mv.dp(8)) }
        var dialog: androidx.appcompat.app.AlertDialog? = null
        ms.sortedByDescending { it.time }.take(60).chunked(3).forEach { rowItems ->
            val r = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = mv.dp(6) }
            }
            rowItems.forEachIndexed { i, m ->
                r.addView(cover(m, mv.dp(104), weight = 1f).apply {
                    if (i > 0) (layoutParams as LinearLayout.LayoutParams).marginStart = mv.dp(6)
                    setOnClickListener {
                        AlbumCovers.set(this@MemoriesActivity, key, m.id)
                        dialog?.dismiss()
                        reload()
                    }
                })
            }
            repeat(3 - rowItems.size) { r.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(0, mv.dp(104), 1f).apply { marginStart = mv.dp(6) } }) }
            grid.addView(r)
        }
        dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(L.t("תמונה ראשית ל$title", "Cover for $title"))
            .setView(android.widget.ScrollView(this).apply { addView(grid) })
            .setNeutralButton(L.t("אוטומטי", "Automatic")) { _, _ -> AlbumCovers.set(this, key, null); reload() }
            .setNegativeButton(L.t("ביטול", "Cancel"), null)
            .show()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        val person = intent.getStringExtra(EXTRA_PERSON)
        if (person != null) { filter = "👤$person"; cameFromOutside = true; reload(); list.scrollToPosition(0) }
        else if (isFocused()) {
            // The Memories tab was tapped while a person's or an album's page was open: back to all memories.
            filter = "הכל"; cameFromOutside = false; reload(); list.scrollToPosition(0)
        }
    }

    private fun personFilters(all: List<Memory>): List<String> =
        all.flatMap { m -> Fmt.names(m).filter { it.isNotBlank() } }.distinct().map { "👤$it" }

    private fun dayTitle(t: Long): String = when {
        DateUtils.isToday(t) -> L.t("היום", "Today")
        DateUtils.isToday(t + DateUtils.DAY_IN_MILLIS) -> L.t("אתמול", "Yesterday")
        else -> ClaudeClient.describeTime(t).substringBeforeLast(" ")
    }

    private inner class RowAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        var rows: List<Row> = emptyList()

        inner class HeaderVH(v: View) : RecyclerView.ViewHolder(v) {
            val title: TextView = v.findViewById(R.id.dayTitle)
            val count: TextView = v.findViewById(R.id.dayCount)
            val story: TextView = v.findViewById(R.id.dayStory)
            val comic: TextView = v.findViewById(R.id.dayComic)
            val check: ImageView = v.findViewById(R.id.dayCheck)
        }

        inner class BubbleVH(v: View) : RecyclerView.ViewHolder(v) {
            val image: ImageView = v.findViewById(R.id.bubbleImage)
            val title: TextView = v.findViewById(R.id.bubbleTitle)
            val sub: TextView = v.findViewById(R.id.bubbleSub)
            val check: ImageView = v.findViewById(R.id.bubbleCheck)
            val kind: ImageView = v.findViewById(R.id.bubbleKind)
        }

        inner class CustomVH(val box: android.widget.FrameLayout) : RecyclerView.ViewHolder(box)

        override fun getItemViewType(position: Int) = when (rows[position]) {
            is Row.Header -> 0
            is Row.Bubble -> 1
            is Row.Custom -> 2
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inf = LayoutInflater.from(parent.context)
            return when (viewType) {
                0 -> HeaderVH(inf.inflate(R.layout.item_day_header, parent, false))
                2 -> CustomVH(android.widget.FrameLayout(parent.context).apply {
                    layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                })
                else -> BubbleVH(inf.inflate(R.layout.item_bubble, parent, false).apply {
                    // Round moments, a third of the width each, like a WhatsApp video message: a mocha ring and the
                    // picture inside; the clip most in view plays silently (RoundVideo).
                    val w = (if (parent.width > 0) parent.width else resources.displayMetrics.widthPixels) / 3
                    layoutParams = layoutParams.apply { height = w - mv.dp(2) }
                    val gap = mv.dp(ROUND_GAP_DP)
                    background = android.graphics.drawable.RippleDrawable(
                        android.content.res.ColorStateList.valueOf(DS.ripple(context)),
                        android.graphics.drawable.InsetDrawable(DS.oval(DS.button(context)), gap),
                        android.graphics.drawable.InsetDrawable(DS.oval(DS.button(context)), gap),
                    )
                    findViewById<ImageView>(R.id.bubbleImage).apply {
                        outlineProvider = RoundVideo.OVAL
                        clipToOutline = true
                        val ring = DS.ringWidth(context)
                        (layoutParams as ViewGroup.MarginLayoutParams).setMargins(gap + ring, gap + ring, gap + ring, gap + ring)
                    }
                })
            }
        }

        override fun getItemCount() = rows.size

        override fun onBindViewHolder(h: RecyclerView.ViewHolder, position: Int) {
            when (val r = rows[position]) {
                is Row.Custom -> {
                    h as CustomVH
                    h.box.removeAllViews()
                    (r.view.parent as? ViewGroup)?.removeView(r.view)
                    h.box.addView(r.view)
                }
                is Row.Header -> {
                    h as HeaderVH
                    h.title.text = r.title
                    h.count.text = if (r.count == 1) L.t("רגע אחד", "1 moment") else L.t("${r.count} רגעים", "${r.count} moments")
                    h.story.text = r.story
                    h.story.visibility = if (r.story.isEmpty()) View.GONE else View.VISIBLE
                    // The day's story as a two-line caption; tap to read it all.
                    h.story.maxLines = 2
                    h.story.setOnClickListener { h.story.maxLines = if (h.story.maxLines == 2) 50 else 2 }
                    h.comic.setOnClickListener { ComicActivity.forDay(this@MemoriesActivity, r.day) }
                    h.comic.visibility = if (selecting) View.GONE else View.VISIBLE
                    h.check.visibility = if (selecting) View.VISIBLE else View.GONE
                    if (selecting) {
                        val dayIds = shownIdsByDay[r.day].orEmpty()
                        h.check.setImageResource(if (dayIds.isNotEmpty() && dayIds.all { it in selected }) R.drawable.check_on else R.drawable.check_off_dark)
                    }
                    h.check.setOnClickListener { toggleDay(r.day) }
                    h.title.setOnClickListener { if (selecting) toggleDay(r.day) }
                }
                is Row.Bubble -> {
                    h as BubbleVH
                    val m = r.m
                    Motion.stop(h.image)
                    h.image.clipToOutline = true
                    h.image.scaleType = ImageView.ScaleType.CENTER_CROP
                    h.image.contentDescription = Fmt.title(m)
                    Covers.load(h.image, m)
                    Motion.attach(h.image, m, width = 240)
                    // Pictures only: the title and time are inside the moment. A clip shows "0:09 🔇" and the one
                    // most in view plays silently; sound only when it's opened.
                    h.title.visibility = View.GONE
                    h.sub.visibility = View.GONE
                    h.kind.visibility = View.GONE
                    val frame = h.itemView as android.widget.FrameLayout
                    val inset = mv.dp(ROUND_GAP_DP) + DS.ringWidth(frame.context)
                    (frame.getTag(R.id.bubbleKind) as? View)?.let { frame.removeView(it) }
                    frame.setTag(R.id.bubbleKind, RoundVideo.badge(frame, m, inset + mv.dp(8))?.also { frame.addView(it) })
                    RoundVideo.attach(frame, m, inset)

                    val on = m.id in selected
                    h.check.visibility = if (selecting) View.VISIBLE else View.GONE
                    h.check.setImageResource(if (on) R.drawable.check_on else R.drawable.check_off)
                    val target = if (on) 0.86f else 1f
                    if (h.image.scaleX != target) h.image.animate().scaleX(target).scaleY(target).setDuration(140).start()
                    h.itemView.setOnClickListener {
                        if (selecting) toggle(m.id) else MomentActivity.open(this@MemoriesActivity, m.id)
                    }
                    h.itemView.setOnLongClickListener {
                        if (selecting) toggle(m.id) else startSelecting(m.id)
                        true
                    }
                }
            }
        }
    }
}

package com.regaim.app

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * "משפחה": everyone in the family, each with their own moments.
 * Built from the profile (kids and ages) and from the faces you named — never from guesses.
 */
class FamilyActivity : AppCompatActivity() {

    private lateinit var ui: Ui
    private lateinit var mv: MemoryViews
    private lateinit var content: LinearLayout
    private lateinit var sharedViews: SharedMemoryViews
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var familyFeed: Circle.Feed? = null
    private var circleMembers = emptyList<Circle.Member>()
    private var amOwner = false
    private var familyError = ""
    private var loading = false
    private var loadedUser = ""
    private var loadedCircle = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_screen)
        ui = Ui(this)
        mv = MemoryViews(this)
        sharedViews = SharedMemoryViews(this)
        content = findViewById(R.id.screenContent)
        findViewById<TextView>(R.id.screenTitle).text = Profile.homeTitle(this)
        findViewById<View>(R.id.btnClose).visibility = View.GONE
        findViewById<View>(R.id.bottomNav).visibility = View.VISIBLE
        Nav.setup(this, Nav.Tab.FAMILY)
        Nav.addProfileButton(this, findViewById<View>(R.id.screenTitle).parent as android.view.ViewGroup)
    }

    override fun onResume() {
        super.onResume()
        if (!Circle.joined(this) || !Account.signedIn(this) || loadedUser != Account.id(this) || loadedCircle != Circle.circleId(this)) { familyFeed = null; circleMembers = emptyList(); familyError = "" }
        render()
        loadFamily()
        ChildSync.kick(this)
    }

    override fun onDestroy() { worker.shutdownNow(); sharedViews.close(); super.onDestroy() }

    private fun loadFamily() {
        if (!Circle.joined(this) || loading || worker.isShutdown) return
        val requestedUser = Account.id(this)
        val requestedCircle = Circle.circleId(this)
        loading = true
        render()
        worker.execute {
            // Last time's feed at once; the new one replaces it.
            if (familyFeed == null) Circle.cachedFeed(applicationContext)?.let { cached ->
                runOnUiThread { if (!isDestroyed && familyFeed == null && requestedUser == Account.id(this) && requestedCircle == Circle.circleId(this)) { familyFeed = cached; loadedUser = requestedUser; loadedCircle = requestedCircle; render() } }
            }
            val result = try { Result.success(Circle.feed(applicationContext) to Circle.members(applicationContext)) } catch (e: Exception) { Result.failure(e) }
            val owner = Circle.isOwner(applicationContext)
            runOnUiThread {
                loading = false
                if (isDestroyed || requestedUser != Account.id(this) || requestedCircle != Circle.circleId(this)) return@runOnUiThread
                amOwner = owner
                result.onSuccess { familyFeed = it.first; circleMembers = it.second; familyError = ""; loadedUser = requestedUser; loadedCircle = requestedCircle }
                    .onFailure { familyError = it.message ?: L.t("לא הצלחנו לפתוח את המשפחה כרגע", "We couldn't load your family right now") }
                render()
            }
        }
    }

    private fun confirmRemove(member: Circle.Member) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(L.t("להוציא את ${member.name} מהמעגל?", "Remove ${member.name} from the circle?"))
            .setMessage(L.t("${member.name} לא יראה יותר את הרגעים והשיחות. קישור ההזמנה יוחלף, כדי שהישן לא יעבוד.", "${member.name} won't see the moments and chats anymore. The invite link changes so the old one stops working."))
            .setPositiveButton(L.t("הוצאה", "Remove")) { _, _ ->
                worker.execute {
                    val err = try { Circle.removeMember(applicationContext, member.userId); null } catch (e: Exception) { e.message ?: L.t("לא הצלחנו", "Something went wrong") }
                    runOnUiThread {
                        if (isDestroyed) return@runOnUiThread
                        android.widget.Toast.makeText(this, err ?: L.t("${member.name} הוצא מהמעגל", "${member.name} was removed"), android.widget.Toast.LENGTH_LONG).show()
                        if (err == null) { circleMembers = circleMembers.filter { it.userId != member.userId }; render() }
                    }
                }
            }
            .setNegativeButton(L.t("ביטול", "Cancel"), null)
            .show()
    }

    /**
     * "Who is this in your family list?": the parent picks; nothing is linked by name. A person with the same name is
     * listed first, marked, but still has to be chosen. Kept on this phone only.
     */
    private fun linkDialog(member: Circle.Member, local: List<FamilyMember>) {
        val people = local.filter { !it.isPet && it.id != "self" }
            .sortedByDescending { Profile.sameName(it.name, member.name) }
        val now = Circle.linkedMember(this, member.userId)
        val labels = people.map { p ->
            val rel = Profile.relationLabel(p.relation).takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
            val same = if (Profile.sameName(p.name, member.name)) L.t(" (אותו שם)", " (same name)") else ""
            val mark = if (p.id == now) "✓ " else ""
            "$mark${p.name}$rel$same"
        } + L.t("לא מהרשימה / לבטל קישור", "Not on the list / unlink")
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(L.t("מי זה ${member.name} ברשימת המשפחה?", "Who is ${member.name} in your family list?"))
            .setItems(labels.toTypedArray()) { _, which ->
                Circle.link(this, member.userId, people.getOrNull(which)?.id)
                render()
            }
            .setNegativeButton(L.t("ביטול", "Cancel"), null)
            .show()
    }

    private class Member(val name: String, val birth: String, val isKid: Boolean, val relation: String)

    private fun members(): List<Member> = Profile.members(this).map { Member(it.name, it.birth, it.isChild, it.relation) }

    private fun openPerson(name: String) {
        startActivity(Intent(this, MemoriesActivity::class.java).putExtra(MemoriesActivity.EXTRA_PERSON, name)
            .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
    }

    private fun render() {
        content.removeAllViews()
        // The family first: everyone in the profile (the children too), a tap away from their page.
        familyStrip()
        // Where to go: the circle, the chat, the family file and the savings, as tiles.
        tiles()
        if (loading) content.addView(ui.hint(L.t("רגע, פותחים את הזיכרונות של המשפחה...", "Loading your family's memories..."), top = 10))
        if (familyError.isNotBlank()) {
            content.addView(ui.hint(familyError, top = 10))
            content.addView(ui.action(L.t("נסו שוב", "Try again")) { loadFamily() })
            if (!Account.signedIn(this)) content.addView(ui.action(L.t("התחברו למעגל", "Sign in to your circle")) { WelcomeActivity.open(this) })
        }
        if (circleMembers.isNotEmpty()) {
            content.addView(ui.section(L.t("מחוברים למעגל", "Connected to the circle")))
            content.addView(ui.hint(L.t("מי שיש לו את האפליקציה ומחובר למעגל המשפחה.", "Who has the app and is connected to the family circle."), top = 2))
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val local = Profile.members(this)
            var unlinked = 0
            for (member in circleMembers) row.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
                setPadding(ui.dp(10), ui.dp(5), ui.dp(10), ui.dp(5))
                // Their own picture and relation come from the family profile only when the parent linked them.
                val linked = Circle.linkedMember(this@FamilyActivity, member.userId)?.let { id -> local.firstOrNull { it.id == id } }
                if (linked == null) unlinked++
                addView(mv.avatar(member.name, 48, byName = false, photoPath = linked?.photo.orEmpty()))
                addView(ui.text(member.name, 14f, bold = true, top = 5))
                val sub = when {
                    linked == null -> Wording.memberRelation(member.relationship, Profile.relationLabel(member.relationship))
                    Profile.sameName(linked.name, member.name) -> "✓ " + Profile.relationLabel(linked.relation)
                    else -> "✓ " + linked.name
                }
                addView(ui.hint(sub, top = 2))
                if (local.any { !it.isPet } && member.userId != Account.id(this@FamilyActivity)) setOnClickListener { linkDialog(member, local) }
                // The family's owner can take someone out of the circle (long press).
                if (amOwner && member.userId != Account.id(this@FamilyActivity)) setOnLongClickListener { confirmRemove(member); true }
            })
            content.addView(ui.scrollRow(row, top = 4))
            if (unlinked > 0 && local.any { !it.isPet }) content.addView(ui.hint(L.t("נגיעה בבן משפחה מקשרת אותו לאדם ברשימת המשפחה שלכם (התמונה והקרבה). שמות זהים לא מתחברים לבד.", "Tap someone to link them to a person in your family list (photo and relation). Same names never link by themselves."), top = 2))
            if (amOwner && circleMembers.size > 1) content.addView(ui.hint(L.t("לחיצה ארוכה על בן משפחה מוציאה אותו מהמעגל.", "Long-press someone to remove them from the circle."), top = 2))
        }
        familyFeed?.let { feed ->
            content.addView(ui.section(L.t("מה חדש במשפחה", "What's new in the family")))
            if (feed.posts.isEmpty()) content.addView(ui.hint(L.t("עוד לא שיתפתם זיכרונות במעגל. הזיכרון הבא יכול להתחיל שיחה ❤️", "No memories shared in your circle yet. Your next one could start a conversation ❤️"), top = 8))
            feed.posts.take(3).forEach { content.addView(sharedViews.card(it, feed) { loadFamily() }) }
            if (feed.posts.size > 3) content.addView(ui.action(L.t("כל זיכרונות המשפחה", "All family memories")) { CircleActivity.open(this) })
        }
        content.addView(ui.section(L.t("האנשים בסיפור שלכם", "The people in your story")))
        val all = MemoryStore.kept(this)
        val people = members()
        if (people.isEmpty()) {
            val card = ui.card(top = 20)
            card.gravity = Gravity.CENTER_HORIZONTAL
            card.setPadding(mv.dp(22), mv.dp(28), mv.dp(22), mv.dp(24))
            card.addView(android.widget.ImageView(this).apply { setImageResource(R.drawable.ic_tab_family); setColorFilter(androidx.core.content.ContextCompat.getColor(this@FamilyActivity, R.color.ink)); layoutParams = android.widget.LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)).apply { gravity = android.view.Gravity.CENTER_HORIZONTAL } })
            card.addView(ui.text(L.t("בואו נכיר את המשפחה", "Let's meet your family"), 20f, bold = true, top = 8).apply { gravity = Gravity.CENTER })
            card.addView(ui.text(L.t("הוסיפו בני משפחה בפרופיל. תוכלו לחבר שמות לפנים שזוהו ולגלות את הרגעים שלכם יחד.", "Add family members in your profile. Then you can name the faces we find and discover your moments together."), 15f, R.color.ink_soft, top = 6).apply { gravity = Gravity.CENTER })
            card.addView(ui.button(L.t("הוסיפו את המשפחה", "Add your family"), primary = true, top = 18) { startActivity(Intent(this, ProfileActivity::class.java)) })
            content.addView(card)
            mv.reveal(card)
        }
        people.forEachIndexed { i, p ->
            val v = memberCard(p, all)
            content.addView(v)
            mv.reveal(v, (i * 50L).coerceAtMost(250L))
        }

        val unknown = try { PeopleStore.unknownGroups(this).size } catch (_: Exception) { 0 }
        val more = ui.card(top = 20)
        if (unknown > 0) more.addView(ui.row(L.t("מי זה? $unknown פנים מחכות לשם", "Who's this? $unknown faces need a name"), "") { PeopleActivity.open(this) })
        more.addView(ui.row(L.t("אבני דרך", "Milestones"), "${all.count { it.milestone.isNotEmpty() }}") {
            startActivity(Intent(this, MilestonesActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        })
        more.addView(ui.row(L.t("ערוך את המשפחה", "Edit family"), "") { startActivity(Intent(this, ProfileActivity::class.java)) })
        content.addView(more)
    }

    private fun memberCard(p: Member, all: List<Memory>): View {
        val mine = all.filter { Fmt.names(it).contains(p.name) }
        val card = ui.card(top = 14)
        val top = ui.horizontal(top = 0)
        top.addView(mv.avatar(p.name, 60))
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(mv.dp(14), 0, mv.dp(14), 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        texts.addView(ui.text(p.name, 19f, bold = true))
        val age = Profile.ageText(p.birth)
        val sub = listOf(
            Profile.relationLabel(p.relation),
            age,
            when (mine.size) {
                0 -> ""
                1 -> L.t("רגע אחד", "1 moment")
                else -> L.t("${mine.size} רגעים", "${mine.size} moments")
            },
            mine.firstOrNull()?.let { L.t("אחרון: ${Fmt.day(it.time)}", "Latest: ${Fmt.day(it.time)}") }.orEmpty(),
        ).filter { it.isNotEmpty() }.joinToString(" · ")
        texts.addView(ui.hint(sub.ifEmpty { L.t("עוד אין רגעים", "No moments yet") }, top = 2))
        top.addView(texts)
        card.addView(top)

        if (mine.isNotEmpty()) {
            val strip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (m in mine.take(8)) strip.addView(mv.mini(m) { MomentActivity.open(this, m.id) })
            card.addView(ui.scrollRow(strip, top = 12))
            card.setOnClickListener { if (!(p.isKid && ChildActivity.openByName(this, p.name))) openPerson(p.name) }
            if (p.isKid) card.addView(ui.action(L.t("הדף של ${p.name} ›", "${p.name}'s page ›")) { ChildActivity.openByName(this, p.name) })
            card.addView(ui.action(L.t("כל הרגעים של ${p.name} ›", "All of ${p.name}'s moments ›")) { openPerson(p.name) })
            if (p.isKid) card.addView(ui.action(L.t("ציר הזמן של ${p.name} ›", "${p.name}'s timeline ›")) { TimelineActivity.open(this, p.name) })
            PeopleStore.people(this).firstOrNull { it.name == p.name }?.let { person ->
                card.addView(ui.action(L.t("זוהה בטעות? תיקון הפנים של ${p.name} ›", "Wrongly recognized? Fix ${p.name}'s faces ›")) { PersonFacesActivity.open(this, person.id) })
            }
        } else if (p.isKid) {
            card.setOnClickListener { ChildActivity.openByName(this, p.name) }
            card.addView(ui.action(L.t("הדף של ${p.name} ›", "${p.name}'s page ›")) { ChildActivity.openByName(this, p.name) })
        }
        if (mine.isEmpty() && p.isKid && PeopleStore.people(this).none { it.name == p.name }) {
            card.addView(ui.hint(L.t("כדי למצוא את ${p.name} ברגעים, תנו את השם הזה לפנים שלו במסך \"מי זה?\".", "To find ${p.name} in your moments, give their face this name on the \"Who's this?\" screen."), top = 10))
            card.addView(ui.action(L.t("מי זה? ›", "Who's this? ›")) { PeopleActivity.open(this) })
        }
        return card
    }

    /** Everyone in the family profile: a round picture, the name, and the age (children) or relation; tap = their page. */
    private fun familyStrip() {
        val people = Profile.members(this).filter { !it.isPet }
        if (people.isEmpty()) return
        content.addView(ui.text(L.t("המשפחה שלנו", "Our family"), 13f, R.color.ink_soft, bold = true, top = 12))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        // The children first: they're who most of it is about.
        for (p in people.sortedByDescending { it.isChild }) row.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(ui.dp(8), ui.dp(4), ui.dp(8), ui.dp(4))
            minimumWidth = ui.dp(76)
            addView(mv.avatar(p.name, 64, ring = p.isChild))
            addView(ui.text(PitzkiScript.firstName(p.name), 14f, bold = true, top = 6).apply { gravity = Gravity.CENTER })
            addView(ui.hint(if (p.isChild) Profile.ageText(p.birth).ifEmpty { Profile.relationLabel(p.relation) } else Profile.relationLabel(p.relation), top = 0).apply { gravity = Gravity.CENTER; maxLines = 1 })
            background = DS.pressable(this@FamilyActivity, DS.rounded(android.graphics.Color.TRANSPARENT, ui.dp(16).toFloat()))
            contentDescription = listOf(p.name, if (p.isChild) Profile.ageText(p.birth) else Profile.relationLabel(p.relation)).filter { it.isNotBlank() }.joinToString(", ")
            A11y.button(this)
            setOnClickListener { if (!(p.isChild && ChildActivity.openByName(this@FamilyActivity, p.name))) openPerson(p.name) }
        })
        content.addView(ui.scrollRow(row, top = 6))
    }

    /** Two tiles a row: an icon on a soft circle, what it is, and a line of what's there. */
    private fun tiles() {
        val today = java.time.LocalDate.now().toString()
        val consented = FamilyFile.consented(this)
        val items = if (consented) FamilyFile.all(this).filterNot { CouponImport.noCode(it) }.size else 0
        val funds = if (consented) FamilyFile.savings(this) else emptyList()
        val saved = Savings.familyTotal(funds, today) { Savings.estimate(it, today, GemelNet.cached(this, it.gemelId), CryptoPrices.cached(this)) }
        val joined = Circle.joined(this)
        val list = listOf(
            Triple(R.drawable.ic_tab_family, L.t("מעגל המשפחה", "Family circle") to (if (joined) Circle.name(this) else L.t("פותחים ומזמינים", "Start and invite"))) { CircleActivity.open(this) },
            Triple(R.drawable.ic_comment, L.t("שיחה במשפחה", "Family chat") to (if (joined) L.t("כולם ביחד", "Everyone together") else L.t("אחרי שמצטרפים למעגל", "Once you join the circle"))) {
                if (joined) FamilyChatActivity.open(this) else CircleActivity.open(this)
            },
            Triple(R.drawable.ic_tab_file, L.t("תיק המשפחה", "Family file") to (if (items > 0) L.t("$items פריטים", "$items items") else L.t("קבלות, שוברים, אחריות", "Receipts, vouchers, warranties"))) { FamilyFileActivity.open(this) },
            Triple(R.drawable.ic_savings, L.t("חיסכון והשקעות", "Savings") to (if (saved > 0) FamilyItems.money(Math.rint(saved)) else L.t("קופות הילדים", "The children's funds"))) { SavingsActivity.open(this) },
        )
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ui.dp(14) } }
        list.chunked(2).forEach { pair ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = ui.dp(10) } }
            pair.forEachIndexed { n, (icon, texts, go) ->
                row.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(ui.dp(14), ui.dp(14), ui.dp(14), ui.dp(14)); minimumHeight = ui.dp(112)
                    background = DS.pressable(this@FamilyActivity, DS.rounded(DS.card(this@FamilyActivity), ui.dp(20).toFloat()))
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { if (n == 0) marginEnd = ui.dp(10) }
                    addView(android.widget.ImageView(this@FamilyActivity).apply {
                        setImageResource(icon); setColorFilter(DS.primary(this@FamilyActivity))
                        setPadding(ui.dp(9), ui.dp(9), ui.dp(9), ui.dp(9))
                        background = DS.oval(DS.cardAlt(this@FamilyActivity))
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                        layoutParams = LinearLayout.LayoutParams(ui.dp(42), ui.dp(42))
                    })
                    addView(ui.text(texts.first, 15f, bold = true, top = 10))
                    addView(TextView(this@FamilyActivity).apply {
                        text = texts.second; textSize = 12.5f; setTextColor(DS.inkSoft(this@FamilyActivity)); maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END; textDirection = View.TEXT_DIRECTION_FIRST_STRONG
                        setPadding(0, ui.dp(2), 0, 0)
                    })
                    contentDescription = texts.first + ", " + texts.second
                    A11y.button(this)
                    setOnClickListener { go() }
                })
            }
            grid.addView(row)
        }
        content.addView(grid)
    }
}

package com.regaim.app

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.io.File

/**
 * "ספר אישי": choose a child and a theme; the app picks the photos, writes the story, draws the child as the
 * book's hero for the parent to approve, then draws the book.
 */
class BookActivity : AppCompatActivity() {
    companion object {
        /** Off for now: choosing real moments to build the story on (see setup). */
        private const val REAL_MOMENTS = false
        fun open(ctx: Context) = ctx.startActivity(Intent(ctx, BookActivity::class.java))
    }

    private lateinit var ui: Ui
    private lateinit var content: LinearLayout
    private var child: FamilyMember? = null
    private var theme: BookTheme = BookThemes.ALL.first()
    private var details: android.widget.EditText? = null
    /** 0 = a theme from the list, 1 = the parent's own subject, 2 = the parent's own story. */
    private var themeMode = 0
    private var animatedBook = false

    // The little book on the waiting screens: kept between screen refreshes so its page turns aren't cut,
    // with the pictures in the order they were drawn (two are drawn at a time, so not always in book order).
    private var bookView: BookLoadingView? = null
    private var bookViewFor = ""
    private val arrived = ArrayList<String>()
    private val pictureCache = BitmapLru(BitmapLru.share(8, 48 * 1024 * 1024)) // book pages are big: limited

    private fun bookView(bookId: String): BookLoadingView {
        if (bookViewFor != bookId) { bookView = null; bookViewFor = bookId; arrived.clear(); pictureCache.clear() }
        val v = bookView ?: BookLoadingView(this).also { bookView = it }
        (v.parent as? ViewGroup)?.removeView(v)
        v.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ui.dp(10) }
        return v
    }

    /** The book's pictures drawn so far (small copies): in the order they arrived, or in book order. */
    private fun drawnPictures(bookId: String, bookOrder: Boolean): List<android.graphics.Bitmap> {
        val b = BookStore.load(this, bookId) ?: return emptyList()
        val dir = BookStore.dir(this, b.id)
        val ready = b.allPages.filter { it.status == "done" && it.image.isNotEmpty() && java.io.File(dir, it.image).exists() }.map { it.image }
        ready.forEach { if (it !in arrived) arrived.add(it) }
        arrived.retainAll(ready.toSet())
        return (if (bookOrder) ready else arrived.toList()).mapNotNull { name ->
            pictureCache[name] ?: try {
                android.graphics.BitmapFactory.decodeFile(java.io.File(dir, name).absolutePath, android.graphics.BitmapFactory.Options().apply { inSampleSize = 4 })
            } catch (_: Throwable) { null }?.also { pictureCache[name] = it }
        }
    }
    private var customTheme: android.widget.EditText? = null
    private var scriptField: android.widget.EditText? = null
    /** The family members chosen for the book (empty = the story decides) and the real moments to build it on. */
    private val peopleSel = LinkedHashSet<String>()
    private val momentSel = ArrayList<String>()
    private var pickingMoments = false
    /** The cast member whose moments are open on the approve screen (to draw them from another photo). */
    private var castPickFor: String? = null
    private var backfillStarted = false
    /** When the screen was last rebuilt (a tap right after it can land on a button that just moved there). */
    private var renderedAt = 0L

    private val thumbs = java.util.concurrent.Executors.newFixedThreadPool(2)
    private var showMoments = false
    private val thumbCache = BitmapLru(BitmapLru.share(16, 16 * 1024 * 1024))
    private val picker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) BookMaker.current?.usePhoto(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_screen)
        findViewById<TextView>(R.id.screenTitle).text = L.t("ספר אישי", "Personal book")
        findViewById<View>(R.id.btnClose).setOnClickListener { finish() }
        content = findViewById(R.id.screenContent)
        ui = Ui(this)
        child = Profile.members(this).firstOrNull { it.isChild }
    }

    override fun onResume() {
        super.onResume()
        BookService.screenOpen = true
        BookService.clearNotice(this)
        Refunds.flushAsync(applicationContext) // a refund that couldn't be sent earlier (no connection)
        Billing.recover(applicationContext) // a payment whose confirmation was lost (the app closed, no signal)
        BookMaker.current?.listener = { render() }
        render()
    }

    override fun onPause() {
        BookService.screenOpen = false
        BookMaker.current?.listener = null
        super.onPause()
    }

    override fun onDestroy() {
        thumbs.shutdownNow()
        super.onDestroy()
    }

    private fun render() {
        renderedAt = android.os.SystemClock.uptimeMillis()
        content.removeAllViews()
        val s = BookMaker.current
        if (s == null) { setup(); return }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        when (s.state) {
            BookMaker.State.PHOTOS, BookMaker.State.WRITING, BookMaker.State.CHARACTER -> working(s)
            BookMaker.State.NEED_PHOTO -> needPhoto(s)
            BookMaker.State.CONFIRM_PHOTOS -> confirmPhotos(s)
            BookMaker.State.CAST -> if (s.castChoices.isEmpty()) working(s) else cast(s)
            BookMaker.State.APPROVE -> approve(s)
            BookMaker.State.PAGES -> pages(s)
            BookMaker.State.ANIMATING -> animating(s)
            BookMaker.State.PAUSED -> paused(s)
            BookMaker.State.NEED_CREDIT -> needCredit(s)
            BookMaker.State.DONE -> done(s)
            BookMaker.State.FAILED -> failed(s)
        }
    }

    private fun setup() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        content.addView(ui.text(L.t(
            "ספר קצר שהילד שלכם הוא הגיבור שלו. בוחרים ילד ונושא, ו-Moments בוחר לבד את התמונות הכי טובות, כותב את הסיפור ומצייר אותו.",
            "A short book where your child is the hero. Pick a child and a theme; Moments picks the best photos, writes the story and illustrates it.",
        ), 16f, top = 14))
        val kids = Profile.members(this).filter { it.isChild }
        if (kids.isEmpty()) {
            content.addView(ui.text(L.t("עוד אין ילדים בפרופיל המשפחה", "There are no children in the family profile yet"), 16f, bold = true, top = 20))
            content.addView(ui.button(L.t("לפרופיל המשפחה", "Family profile"), primary = true, top = 12) { startActivity(Intent(this, FamilyActivity::class.java)) })
            return
        }
        content.addView(ui.text(L.t("מי הגיבור?", "Who's the hero?"), 16f, bold = true, top = 20))
        val sel = kids.indexOfFirst { it.id == child?.id }.coerceAtLeast(0)
        child = kids[sel]
        content.addView(ui.chips(kids.map { it.name }, sel, top = 8) { i ->
            if (child?.id != kids[i].id) { peopleSel.clear(); momentSel.clear() }
            child = kids[i]; render()
        })

        content.addView(ui.text(L.t("על מה הספר?", "What's it about?"), 16f, bold = true, top = 20))
        val themes = BookThemes.ALL
        val other = themes.size
        val own = themes.size + 1
        val picked = when (themeMode) { 1 -> other; 2 -> own; else -> themes.indexOf(theme).coerceAtLeast(0) }
        content.addView(ui.chips(themes.map { it.title } + L.t("נושא אחר...", "Another subject...") + L.t("יש לי סיפור משלי", "I have my own story"), picked, top = 8) { i ->
            themeMode = when (i) { other -> 1; own -> 2; else -> { theme = themes[i]; 0 } }
            render()
        })
        if (themeMode == 1) {
            val f = ui.field(L.t("על מה הספר? למשל: היום הראשון בגן, טיול לסבתא, אורי לומד לרכוב על אופניים", "What's the book about? e.g. the first day at kindergarten, a visit to grandma, learning to ride a bike"), customTheme?.text?.toString().orEmpty(), multiline = true, top = 10)
            customTheme = f
            content.addView(f)
        }
        if (themeMode == 2) {
            val f = ui.field(L.t("הדביקו או כתבו כאן את הסיפור. נחלק אותו ל-${BookMaker.PAGES} עמודים ונצייר כל עמוד, בלי לשנות את המילים שלכם (רק תיקוני כתיב).", "Paste or write the story here. We'll split it into ${BookMaker.PAGES} pages and draw each one, keeping your words (spelling fixes only)."), scriptField?.text?.toString().orEmpty(), multiline = true, top = 10)
            f.minLines = 6
            f.filters = arrayOf(android.text.InputFilter.LengthFilter(4000))
            scriptField = f
            content.addView(f)
        }

        val field = ui.field(L.t("פרטים שיעזרו לסיפור (לא חובה): שם המוצץ, צעצוע אהוב, שם האח...", "Details for the story (optional): the pacifier's name, a favorite toy, a sibling's name..."), details?.text?.toString().orEmpty(), multiline = true, top = 16)
        details = field
        content.addView(field)

        // Who's in the book: the parent decides (or leaves it to the story).
        val family = Cast.family(this, child!!.name)
        if (family.isNotEmpty()) {
            content.addView(ui.text(L.t("מי עוד בספר?", "Who else is in the book?"), 16f, bold = true, top = 20))
            content.addView(ui.hint(L.t("אם לא תסמנו אף אחד, הסיפור יבחר לבד.", "If you don't pick anyone, the story chooses."), top = 2))
            val row = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL }
            for (m in family) row.addView(android.widget.CheckBox(this).apply {
                text = if (m.relation.isNotBlank() && m.relation != m.name) "${m.name} (${m.relation})" else m.name
                textSize = 15f
                isChecked = m.id in peopleSel
                setOnCheckedChangeListener { _, on -> if (on) peopleSel.add(m.id) else peopleSel.remove(m.id) }
            })
            content.addView(row)
        }

        // Real moments for the story: hidden until there's enough material to check the stories' quality
        // (a 30-second moment is one action, not a plot). The code stays for "The year of ..." books later.
        if (REAL_MOMENTS) {
        content.addView(ui.text(L.t("רגעים אמיתיים לסיפור (עד 5)", "Real moments for the story (up to 5)"), 16f, bold = true, top = 20))
        content.addView(ui.hint(L.t(
            "בחרו רגעים מהחיים של ${child!!.name}, והסיפור ייבנה עליהם: המקומות, האנשים ומה שקרה. נשלח רק התיאור הכתוב שלהם, לא התמונות.",
            "Pick moments from ${child!!.name}'s life and the story is built on them: the places, the people and what happened. Only their written description is sent, not the photos.",
        ), top = 2))
        if (momentSel.isNotEmpty()) content.addView(ui.text(L.t("נבחרו ${momentSel.size} רגעים", "${momentSel.size} moments chosen"), 15f, R.color.accent, top = 6))
        content.addView(ui.action(if (pickingMoments) L.t("סיום בחירה", "Done choosing") else L.t("בחרו רגעים", "Choose moments")) {
            pickingMoments = !pickingMoments; render()
        }.apply { setPadding(0, ui.dp(8), 0, 0) })
        if (pickingMoments) momentPicker(child!!.name)
        }

        // Which book: pages to turn, or also a film where every page comes to life.
        content.addView(ui.text(L.t("איזה ספר?", "Which book?"), 16f, bold = true, top = 20))
        content.addView(ui.chips(listOf(L.t("ספר דיגיטלי · ${BookMaker.PRICE_DIGITAL}", "Digital book · ${BookMaker.PRICE_DIGITAL}"), L.t("ספר מונפש · ${BookMaker.PRICE_ANIMATED}", "Animated book · ${BookMaker.PRICE_ANIMATED}")), if (animatedBook) 1 else 0, top = 8) { i ->
            animatedBook = i == 1; render()
        })
        content.addView(ui.hint(if (animatedBook) L.t(
            "ספר לדפדוף, ובנוסף סרט שבו כל עמוד מתעורר לחיים (כ-5 שניות לעמוד), עם המילים על המסך ומוזיקה. נשמר בגלריה ואפשר לשלוח. ההנפשה לוקחת עוד כ-10 דקות.",
            "A book to turn, plus a film where every page comes to life (about 5 seconds a page), with the words on screen and music. Saved to the gallery, ready to send. Animating takes about 10 more minutes.",
        ) else L.t("ספר לדפדוף בטלפון, לשליחה כ-PDF ולהדפסה.", "A book to turn on the phone, send as a PDF and print."), top = 4))
        content.addView(ui.button(L.t("צרו את הספר", "Create the book"), primary = true, top = 18) { consent() })
        content.addView(ui.hint(L.t("ספר של ${BookMaker.PAGES} עמודים, כריכה והקדשה. עולה קרדיט אחד. לפני שמציירים את הספר, תבחרו מאילו תמונות לצייר כל דמות ותאשרו את הדמויות.", "${BookMaker.PAGES} pages, a cover and a dedication. Uses one credit. Before the book is drawn, you choose the photos to draw each character from and approve the characters."), top = 8))

        // A book that stopped in the middle (no connection, or the phone closed the app): continue it from where it was.
        BookMaker.unfinished(this)?.let { u ->
            val card = ui.card(top = 18)
            card.addView(ui.text(L.t("ספר שלא הסתיים: ${u.title}", "An unfinished book: ${u.title}"), 16f, bold = true, top = 0))
            card.addView(ui.hint(L.t("ממשיכים מהשלב שבו הוא נעצר. בתוך שלוש שעות מההתחלה זה לא עולה קרדיט נוסף.", "It continues from where it stopped. Within three hours of the start it costs no extra credit."), top = 4))
            card.addView(ui.button(L.t("המשיכו", "Continue"), primary = true, top = 10) { BookMaker.resume(this, u).listener = { render() }; render() })
            card.addView(ui.action(L.t("ביטול הספר", "Cancel the book")) {
                Thread { BookMaker.abandon(applicationContext, u); runOnUiThread { if (!isFinishing) render() } }.start()
            }.apply { setPadding(0, ui.dp(12), 0, 0) })
            content.addView(card)
        }

        // The family's characters from earlier books (used again by default in the next book).
        if (!backfillStarted) {
            backfillStarted = true
            Thread { CharacterStore.backfill(applicationContext); runOnUiThread { if (!isFinishing && BookMaker.current == null) render() } }.start()
        }
        val saved = CharacterStore.all(this)
        // Everyone in the family, each once: their character (the version the next book uses), or "no character yet".
        val everyone = Cast.family(this, "")
        if (everyone.isNotEmpty()) {
            content.addView(ui.section(L.t("הדמויות של המשפחה", "The family's characters")))
            content.addView(ui.hint(L.t("הדמות של כל אחד נשמרת מהספר שבו אישרתם אותה, והספר הבא ישתמש בה (בלי לצייר שוב). לחיצה על דמות: גרסאות, החלפה או מחיקה.", "Each person's character is kept from the book where you approved it, and the next book uses it (no new drawing). Tap a character for its versions, to switch or delete."), top = 2))
            val scroll = android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (m in everyone) {
                val versions = saved.filter { it.member == m.id }
                val main = versions.firstOrNull()
                val cell = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = android.view.Gravity.CENTER_HORIZONTAL }
                if (main != null) cell.addView(ImageView(this).apply {
                    setImageBitmap(BitmapFactory.decodeFile(CharacterStore.file(this@BookActivity, main).absolutePath, BitmapFactory.Options().apply { inSampleSize = 4 }))
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setBackgroundResource(R.drawable.rounded_media)
                    clipToOutline = true
                }, LinearLayout.LayoutParams(ui.dp(76), ui.dp(100)))
                else cell.addView(TextView(this).apply {
                    text = L.t("עוד אין\nדמות", "No\ncharacter yet")
                    textSize = 11f
                    gravity = android.view.Gravity.CENTER
                    setBackgroundResource(R.drawable.rounded_media)
                    alpha = 0.6f
                }, LinearLayout.LayoutParams(ui.dp(76), ui.dp(100)))
                cell.addView(TextView(this).apply {
                    text = m.name + if (versions.size > 1) " · ${versions.size}" else ""
                    textSize = 12f; gravity = android.view.Gravity.CENTER
                })
                if (main != null) cell.setOnClickListener { characterMenu(m.name, versions) }
                row.addView(cell, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = ui.dp(10) })
            }
            scroll.addView(row)
            content.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ui.dp(8) })
        }

        val books = BookStore.all(this).filter { it.done }
        if (books.isNotEmpty()) {
            content.addView(ui.section(L.t("הספרים שלכם", "Your books")))
            for (b in books) content.addView(ui.row(b.title, b.child) { BookViewerActivity.open(this, b.id) })
        }
    }

    /** Exactly what leaves the phone, and where it goes (checked against the providers' published terms). */
    private fun consent() {
        val c = child ?: return
        if (themeMode == 1 && customTheme?.text.isNullOrBlank()) {
            android.widget.Toast.makeText(this, L.t("כתבו על מה הספר", "Write what the book is about"), android.widget.Toast.LENGTH_SHORT).show(); return
        }
        if (themeMode == 2 && (scriptField?.text?.toString()?.trim()?.length ?: 0) < 40) {
            android.widget.Toast.makeText(this, L.t("הדביקו את הסיפור (לפחות כמה משפטים)", "Paste the story (at least a few sentences)"), android.widget.Toast.LENGTH_SHORT).show(); return
        }
        if (!Account.signedIn(this)) { WelcomeActivity.open(this); return }
        AlertDialog.Builder(this)
            .setTitle(L.t("לפני שמתחילים: מה נשלח", "Before we start: what is sent"))
            .setMessage(L.t(
                "• לכתיבת הסיפור, ל-Claude של Anthropic: השם ${c.name}, הגיל, הנושא, הפרטים שכתבתם, שמות בני המשפחה והקשר שלהם לילד (מפרופיל המשפחה), והתיאור הכתוב של הרגעים שבחרתם (תאריך, מקום, מי היה ומה קרה). בלי תמונות. לפי Anthropic, מידע שנשלח דרך ה-API נמחק תוך 30 יום (למעט חריגים, כמו בדיקת שימוש לרעה).\n\n" +
                    "• לציור, ל-fal.ai, שמריצה את מודל הציור GPT Image של OpenAI: עד 3 תמונות פנים של ${c.name} שאישרתם, ותמונת פנים של כל בן משפחה שתבחרו לצייר לפי תמונה (אפשר לבחור \"בלי תמונה\"). אחר כך הדמויות המצוירות נשלחות לציור כל עמוד.\n" +
                    "אצל fal התוצאות נשמרות כקישורים ציבוריים (כל מי שיש לו את הקישור) עד שנמחקות. אנחנו מורידים כל ציור מיד, קובעים לו תפוגה של שעה, ומבקשים מ-fal למחוק את הבקשה ואת הקבצים. fal לא מפרסמת איך OpenAI מעבדת את המידע.\n\n" +
                    "• השרת של Moments מעביר את התמונות ולא שומר אותן. הספר נשמר רק בטלפון.\n\n" +
                    (if (animatedBook) "• ספר מונפש: הציורים הגמורים של הספר (בלי תמונות אמיתיות) נשלחים ל-fal להנפשה במודל Kling, באותם תנאי מחיקה.\n\n" else "") +
                    "• עלות: " + (if (Billing.on(this)) Billing.price(this, if (animatedBook) Billing.ANIMATED else Billing.DIGITAL) + ", בתשלום דרך Google Play (אם אין לכם ספר שכבר שולם)" else if (animatedBook) "שני קרדיטים (ספר + הנפשה)" else "קרדיט אחד") + ". אם לא תאשרו את הדמויות, הספר יוחזר לחשבון שלכם לספר הבא.",
                "• To write the story, to Anthropic's Claude: the name ${c.name}, the age, the theme, your details, your family members' names and how they relate to the child (from the family profile), and the written description of the moments you chose (date, place, who was there, what happened). No photos. Anthropic says data sent through its API is deleted within 30 days (except e.g. abuse checks).\n\n" +
                    "• To draw, to fal.ai, which runs OpenAI's GPT Image drawing model: up to 3 face photos of ${c.name} that you approved, and a face photo of each family member you choose to draw from a photo (you can choose \"without a photo\"). Then the drawn characters are sent for drawing each page.\n" +
                    "At fal, results are public links (anyone with the link) until deleted. We download each drawing at once, set it to expire in an hour, and ask fal to delete the request and its files. fal doesn't publish how OpenAI processes the data.\n\n" +
                    "• Moments' server passes the photos on and doesn't keep them. The book is saved only on your phone.\n\n" +
                    (if (animatedBook) "• Animated book: the book's finished drawings (no real photos) are sent to fal to be animated with the Kling model, with the same deletion terms.\n\n" else "") +
                    "• Cost: " + (if (Billing.on(this)) Billing.price(this, if (animatedBook) Billing.ANIMATED else Billing.DIGITAL) + ", paid through Google Play (unless you have a book already paid for)" else if (animatedBook) "two credits (book + animation)" else "one credit") + ". If you don't approve the characters, the book goes back to your account for the next one.",
            ))
            .setPositiveButton(L.t("מסכימים, צרו את הספר", "I agree, create the book")) { _, _ ->
                // When payments are on: the book is paid for first (Google Play), unless the account has credits.
                Billing.ensureCredits(this, if (animatedBook) 2 else 1, if (animatedBook) Billing.ANIMATED else Billing.DIGITAL) { startBook(c) }
            }
            .setNegativeButton(L.t("לא עכשיו", "Not now"), null)
            .show()
    }

    private fun startBook(c: FamilyMember) {
        run {
                val chosenTheme = when (themeMode) {
                    1 -> BookThemes.custom(customTheme?.text?.toString().orEmpty())
                    2 -> BookThemes.SCRIPT
                    else -> theme
                }
                BookMaker.start(this, c, chosenTheme, details?.text?.toString().orEmpty(),
                    people = peopleSel.takeIf { it.isNotEmpty() }?.toSet(), moments = momentSel.toList(),
                    script = if (themeMode == 2) scriptField?.text?.toString().orEmpty() else "", animated = animatedBook).listener = { render() }
                pickingMoments = false
                render()
        }
    }

    private fun working(s: BookMaker.Session) {
        content.addView(ui.text(s.message, 17f, bold = true, top = 24))
        if (s.state != BookMaker.State.PHOTOS) content.addView(BookLoadingView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ui.dp(10) }
        })
        content.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = s.state != BookMaker.State.PHOTOS
            max = 100; progress = s.progress
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(8)).apply { topMargin = ui.dp(12) }
        })
        if (s.waiting) content.addView(ui.text(L.t("אין חיבור לאינטרנט כרגע. ממשיכים לבד כשהחיבור יחזור.", "No internet connection right now. We'll carry on by ourselves when it's back."), 15f, R.color.accent, top = 12))
        content.addView(ui.hint(L.t("זה לוקח דקה-שתיים. אפשר לצאת מהאפליקציה: הספר ממשיך ברקע ונשלח התראה.", "This takes a minute or two. You can leave the app: the book carries on and we'll notify you."), top = 10))
        content.addView(ui.action(L.t("ביטול", "Cancel")) { s.cancel(); render() }.apply { setPadding(0, ui.dp(18), 0, 0) })
    }

    /** Face recognition can mix up siblings: the parent removes any photo that isn't this child. Nothing is sent yet. */
    private fun confirmPhotos(s: BookMaker.Session) {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val name = s.childName
        content.addView(ui.text(L.t("אלה תמונות של $name?", "Are these photos of $name?"), 20f, bold = true, top = 16))
        content.addView(ui.hint(L.t(
            "Moments בחר אותן לבד מהרגעים לפי זיהוי הפנים. אם אחת מהן אינה של $name (למשל אח או אחות), לחצו עליה: היא תוחלף בתמונה אחרת, ונזכור לא להציע אותה שוב. עוד לא נשלח כלום ולא ירד קרדיט.",
            "Moments picked these from your moments by face recognition. If one isn't $name (a brother or sister, say), tap it: it's replaced by another, and we won't offer it again. Nothing has been sent and no credit used yet.",
        ), top = 4))
        val list = s.candidates.toList().take(3)
        list.chunked(3).forEachIndexed { r, chunk ->
            val row = ui.horizontal(top = if (r == 0) 12 else 8)
            chunk.forEachIndexed { k, c ->
                val used = r * 3 + k < 3
                row.addView(android.widget.FrameLayout(this).apply {
                    layoutParams = LinearLayout.LayoutParams(0, ui.dp(104), 1f).apply { if (k > 0) marginStart = ui.dp(8) }
                    addView(ImageView(this@BookActivity).apply {
                        setImageBitmap(c.crop)
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        setBackgroundResource(R.drawable.rounded_media)
                        clipToOutline = true
                        alpha = if (used) 1f else 0.55f
                    }, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                    addView(TextView(this@BookActivity).apply {
                        text = "✕"
                        textSize = 16f
                        setTextColor(android.graphics.Color.WHITE)
                        setBackgroundColor(0x88000000.toInt())
                        setPadding(ui.dp(8), ui.dp(2), ui.dp(8), ui.dp(4))
                    }, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, android.view.Gravity.TOP or android.view.Gravity.END))
                    setOnClickListener { s.removeCandidate(c); render() }
                })
            }
            content.addView(row)
        }
        if (list.isEmpty()) content.addView(ui.hint(L.t("לא נשארו תמונות. בחרו מהרגעים של $name למטה.", "No photos left. Pick from $name's moments below."), top = 12))
        if (s.message.isNotBlank()) content.addView(ui.text(s.message, 15f, R.color.accent, top = 8))
        content.addView(ui.button(L.t("כן, אלה תמונות של $name", "Yes, these are $name"), primary = true, top = 16) { if (android.os.SystemClock.uptimeMillis() - renderedAt >= 900) { s.confirmPhotos(); render() } }.apply { isEnabled = list.isNotEmpty() })
        // Or the parent picks better ones from the child's moments.
        content.addView(ui.action(if (showMoments) L.t("הסתר את הרגעים", "Hide the moments") else L.t("להחליף בתמונה מהרגעים של $name", "Swap in a photo from $name's moments")) {
            showMoments = !showMoments; render()
        }.apply { setPadding(0, ui.dp(16), 0, 0) })
        if (showMoments || list.isEmpty()) momentsGrid(s)
        content.addView(ui.action(L.t("ביטול", "Cancel")) { s.cancel(); render() }.apply { setPadding(0, ui.dp(16), 0, 0) })
    }

    private fun needPhoto(s: BookMaker.Session) {
        // Photos come only from the family's moments, never from the phone's gallery: here the parent picks them.
        content.addView(ui.text(L.t("בחרו תמונות של ${s.childName}", "Pick photos of ${s.childName}"), 18f, bold = true, top = 22))
        content.addView(ui.hint(L.t(
            "לא הצלחנו לבחור לבד תמונות ברורות מספיק. בחרו מהרגעים של ${s.childName} רגע שבו רואים את הפנים מקרוב (אפשר כמה).",
            "We couldn't choose clear enough photos by ourselves. Pick from ${s.childName}'s moments one where the face is seen up close (you can pick several).",
        ), top = 6))
        momentsGrid(s)
        content.addView(ui.action(L.t("ביטול", "Cancel")) { s.cancel(); render() }.apply { setPadding(0, ui.dp(16), 0, 0) })
    }

    /** The child's moments (kept, not waiting in the queue) to build the story on: tap to choose, up to 5. */
    private fun momentPicker(name: String) {
        val moments = BookPhotos.taggedMoments(this, name, 45).filter { !it.isPending }
        if (moments.isEmpty()) {
            content.addView(ui.hint(L.t("עוד אין רגעים מתויגים של $name.", "No moments are tagged with $name yet."), top = 8))
            return
        }
        moments.chunked(3).forEachIndexed { r, chunk ->
            val row = ui.horizontal(top = if (r == 0) 10 else 8)
            chunk.forEachIndexed { k, m ->
                val cell = android.widget.FrameLayout(this).apply {
                    layoutParams = LinearLayout.LayoutParams(0, ui.dp(104), 1f).apply { if (k > 0) marginStart = ui.dp(8) }
                }
                val img = ImageView(this).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setBackgroundResource(R.drawable.rounded_media)
                    clipToOutline = true
                    alpha = if (m.id in momentSel) 1f else 0.85f
                }
                cell.addView(img, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                // The moment's title, so the parent knows what the story will tell.
                cell.addView(TextView(this).apply {
                    text = m.displayTitle
                    textSize = 11f
                    maxLines = 2
                    setTextColor(android.graphics.Color.WHITE)
                    setBackgroundColor(0x88000000.toInt())
                    setPadding(ui.dp(6), ui.dp(2), ui.dp(6), ui.dp(3))
                }, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, android.view.Gravity.BOTTOM))
                if (m.id in momentSel) cell.addView(TextView(this).apply {
                    text = "✓ ${momentSel.indexOf(m.id) + 1}"
                    textSize = 13f
                    setTextColor(android.graphics.Color.WHITE)
                    setBackgroundColor(0xDD1A6FCC.toInt())
                    setPadding(ui.dp(8), ui.dp(2), ui.dp(8), ui.dp(3))
                }, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, android.view.Gravity.TOP or android.view.Gravity.END))
                cell.setOnClickListener {
                    if (m.id in momentSel) momentSel.remove(m.id)
                    else if (momentSel.size < 5) momentSel.add(m.id)
                    else android.widget.Toast.makeText(this, L.t("אפשר לבחור עד 5 רגעים", "You can pick up to 5 moments"), android.widget.Toast.LENGTH_SHORT).show()
                    render()
                }
                row.addView(cell)
                val cached = thumbCache[m.id]
                if (cached != null) img.setImageBitmap(cached) else thumbs.execute {
                    val b = (if (m.thumb.isNotBlank() && java.io.File(m.thumb).exists()) BitmapFactory.decodeFile(m.thumb) else null)
                        ?: if (!m.isVideo) MediaImages.decode(this, m.uri, 300) else null
                    if (b != null) runOnUiThread { thumbCache[m.id] = b; img.setImageBitmap(b) }
                }
            }
            repeat(3 - chunk.size) { row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f).apply { marginStart = ui.dp(8) }) }
            content.addView(row)
        }
    }

    /** The child's tagged moments as a grid: tap one to use the child's face from it. */
    private fun momentsGrid(s: BookMaker.Session, name: String = s.childName, onPick: (String) -> Unit = { s.useMoment(it) }) {
        val moments = BookPhotos.taggedMoments(this, name)
        if (moments.isEmpty() && name != s.childName) {
            content.addView(ui.hint(L.t("עוד אין רגעים מתויגים של $name. תייגו במסך האנשים.", "No moments are tagged with $name yet. Tag them on the People screen."), top = 8))
            return
        }
        if (moments.isEmpty()) {
            content.addView(ui.hint(L.t("עוד אין רגעים מתויגים של ${s.childName}. תייגו אותו/ה במסך האנשים, או הוסיפו תמונת פרופיל ברורה.", "No moments are tagged with ${s.childName} yet. Tag them on the People screen, or add a clear profile photo."), top = 10))
            content.addView(ui.button(L.t("לפרופיל המשפחה", "Family profile"), primary = true, top = 12) {
                s.cancel() // nothing was sent or paid yet
                startActivity(Intent(this, FamilyActivity::class.java))
            })
            return
        }
        moments.chunked(3).forEachIndexed { r, chunk ->
            val row = ui.horizontal(top = if (r == 0) 12 else 8)
            chunk.forEachIndexed { k, m ->
                val img = ImageView(this).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setBackgroundResource(R.drawable.rounded_media)
                    clipToOutline = true
                    layoutParams = LinearLayout.LayoutParams(0, ui.dp(104), 1f).apply { if (k > 0) marginStart = ui.dp(8) }
                    setOnClickListener { onPick(m.id); render() }
                }
                row.addView(img)
                // Pictures load in the background (the list can be long), once per screen.
                val cached = thumbCache[m.id]
                if (cached != null) img.setImageBitmap(cached) else thumbs.execute {
                    val b = (if (m.thumb.isNotBlank() && java.io.File(m.thumb).exists()) BitmapFactory.decodeFile(m.thumb) else null)
                        ?: if (!m.isVideo) MediaImages.decode(this, m.uri, 300) else null
                    if (b != null) runOnUiThread { thumbCache[m.id] = b; img.setImageBitmap(b) }
                }
            }
            // Keep the cells the same width when the last row isn't full.
            repeat(3 - chunk.size) { row.addView(android.view.View(this), LinearLayout.LayoutParams(0, 1, 1f).apply { marginStart = ui.dp(8) }) }
            content.addView(row)
        }
    }

    private fun approve(s: BookMaker.Session) {
        val b = s.book ?: return
        content.addView(ui.text(L.t("הדמויות של הספר", "The book's characters"), 20f, bold = true, top = 16))
        content.addView(ui.hint(L.t("כך כולם ייראו בכל העמודים. כדאי לאשר רק אם זה באמת דומה. אפשר לצייר כל דמות מחדש.", "This is how everyone will look on every page. Approve only if it really looks right. You can redraw each character."), top = 4))
        content.addView(ui.text(s.childName, 17f, bold = true, top = 12))
        val f = BookStore.image(this, b, b.character)
        content.addView(ImageView(this).apply {
            adjustViewBounds = true
            setImageBitmap(BitmapFactory.decodeFile(f.absolutePath))
            setBackgroundResource(R.drawable.rounded_media)
            clipToOutline = true
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ui.dp(12) }
        })
        // The photos the character was drawn from: if it doesn't look right, the parent can see whether the photos were the problem.
        val faces = (0 until 3).map { BookStore.image(this, b, "face_$it.jpg") }.filter { it.exists() }
        if (faces.isNotEmpty()) {
            content.addView(ui.hint(L.t("צויר מהתמונות האלה:", "Drawn from these photos:"), top = 10))
            val row = ui.horizontal(top = 6)
            for (f in faces) row.addView(ImageView(this).apply {
                setImageBitmap(BitmapFactory.decodeFile(f.absolutePath))
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundResource(R.drawable.rounded_media)
                clipToOutline = true
                layoutParams = LinearLayout.LayoutParams(ui.dp(72), ui.dp(72)).apply { marginEnd = ui.dp(8) }
            })
            content.addView(row)
        }
        // Everyone else in the story, as they'll look on every page.
        for (c in b.cast) {
            content.addView(ui.text(c.name, 17f, bold = true, top = 18))
            val sheet = c.sheet.takeIf { it.isNotEmpty() }?.let { BookStore.image(this, b, it) }?.takeIf { it.exists() }
            if (sheet == null) {
                content.addView(ui.hint(L.t("לא הצלחנו לצייר את ${c.name}. בעמודים יצויר/תצויר לפי התיאור.", "We couldn't draw ${c.name}. The pages will draw them from the description."), top = 4))
            } else {
                content.addView(ImageView(this).apply {
                    adjustViewBounds = true
                    setImageBitmap(BitmapFactory.decodeFile(sheet.absolutePath))
                    setBackgroundResource(R.drawable.rounded_media)
                    clipToOutline = true
                    layoutParams = LinearLayout.LayoutParams(ui.dp(220), ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ui.dp(8) }
                })
            }
            if (c.tries <= BookMaker.CAST_TRIES) {
                content.addView(ui.action(L.t("לצייר את ${c.name} מחדש", "Draw ${c.name} again")) { s.redrawCast(c.id); render() }.apply { setPadding(0, ui.dp(8), 0, 0) })
                // Not like them? Usually the photo was the problem: draw them again from another of their moments.
                val profileName = Profile.members(this).firstOrNull { it.id == c.memberId }?.name
                if (profileName != null) {
                    content.addView(ui.action(if (castPickFor == c.id) L.t("סגירת הרגעים של ${c.name}", "Close ${c.name}'s moments") else L.t("לצייר את ${c.name} מתמונה אחרת", "Draw ${c.name} from another photo")) {
                        castPickFor = if (castPickFor == c.id) null else c.id; render()
                    }.apply { setPadding(0, ui.dp(8), 0, 0) })
                    if (castPickFor == c.id) {
                        content.addView(ui.hint(L.t("בחרו רגע שבו רואים את ${c.name} מקרוב ובאור טוב:", "Pick a moment where ${c.name} is seen up close and in good light:"), top = 4))
                        momentsGrid(s, profileName) { id -> castPickFor = null; s.redrawCastFromMoment(c.id, id, profileName) }
                    }
                }
            }
        }
        content.addView(ui.text(b.title, 16f, bold = true, top = 18))
        if (s.message.isNotBlank()) content.addView(ui.text(s.message, 15f, R.color.accent, top = 8))
        // Never by accident: the screen may have just changed under the finger (a redraw finished), so a tap right
        // after it changed is ignored, and the parent confirms before the whole book is drawn.
        content.addView(ui.button(L.t("מאשרים, ציירו את הספר", "Approve, draw the book"), primary = true, top = 14) {
            if (android.os.SystemClock.uptimeMillis() - renderedAt < 900) return@button
            AlertDialog.Builder(this)
                .setTitle(L.t("לאשר את הדמויות?", "Approve the characters?"))
                .setMessage(L.t("כך כולם ייראו בכל עמודי הספר. אחרי האישור מתחילים לצייר את הכריכה ואת ${BookMaker.PAGES} העמודים.", "This is how everyone will look on every page. After approving, the cover and the ${BookMaker.PAGES} pages are drawn."))
                .setPositiveButton(L.t("כן, ציירו את הספר", "Yes, draw the book")) { _, _ -> s.approve(); render() }
                .setNegativeButton(L.t("עוד לא", "Not yet"), null)
                .show()
        })
        if (s.triesLeft > 0) content.addView(ui.button(L.t("לצייר את ${s.childName} מחדש (נותרו ${s.triesLeft})", "Draw ${s.childName} again (${s.triesLeft} left)"), top = 8) { s.redrawCharacter() })
        content.addView(ui.action(L.t("לא דומה, בטלו את הספר (הקרדיט יוחזר אם צוירו עד 5 ציורים)", "Doesn't look right, cancel the book (credit returned if up to 5 drawings were made)")) { s.cancel(); render() }.apply { setPadding(0, ui.dp(16), 0, 0) })
    }

    /**
     * Who else is in the story: for each family member the story brought in, their best photos from the family's
     * moments. The parent taps the photo to draw them from, or "without a photo". Nothing is sent or paid yet.
     */
    private fun cast(s: BookMaker.Session) {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val b = s.book
        content.addView(ui.text(L.t("מי עוד בסיפור?", "Who else is in the story?"), 20f, bold = true, top = 16))
        b?.let { content.addView(ui.hint(L.t("הסיפור: \"${it.title}\". כדי שכולם ייראו כמו במציאות, בחרו לכל דמות תמונה ממנה נצייר אותה. עוד לא נשלח כלום ולא ירד קרדיט.", "The story: \"${it.title}\". So everyone looks like themselves, choose a photo to draw each character from. Nothing has been sent and no credit used yet."), top = 4)) }
        // The hero: their character from an earlier book is used by default (no new drawing).
        // (A new drawing needs the child's photos; without them only the saved versions are offered.)
        val hasFaces = s.book?.let { BookStore.image(this, it, "face_0.jpg").exists() } == true
        s.heroSaved?.let { saved -> savedRow(s.childName, CharacterStore.versions(this, saved.member), if (s.useHeroSaved) s.heroSaved else null,
            onPick = { s.useHero(it); render() }, onNew = if (hasFaces) ({ s.useHero(null); render() }) else null, newChosen = !s.useHeroSaved) }
        for (ch in s.castChoices.toList()) {
            content.addView(ui.text(ch.cast.name, 17f, bold = true, top = 18))
            ch.saved?.let { saved ->
                savedRow(null, CharacterStore.versions(this, saved.member), if (ch.useSaved) ch.saved else null,
                    onPick = { s.useSavedCast(ch.cast.id, it); render() }, onNew = null, newChosen = false)
                content.addView(ui.hint(if (ch.useSaved) L.t("משתמשים בדמות השמורה. כדי לצייר מחדש, בחרו תמונה למטה.", "Using the saved character. To draw anew, pick a photo below.")
                    else L.t("יצויר/תצויר מחדש מהתמונה שבחרתם.", "Will be drawn anew from the photo you picked."), top = 2))
            }
            if (ch.options.isEmpty()) content.addView(ui.hint(L.t("לא מצאנו תמונות של ${ch.cast.name} ברגעים.", "We found no photos of ${ch.cast.name} in your moments."), top = 2))
            val row = ui.horizontal(top = 8)
            ch.options.forEachIndexed { k, bmp ->
                row.addView(ImageView(this).apply {
                    setImageBitmap(bmp)
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setBackgroundResource(R.drawable.rounded_media)
                    clipToOutline = true
                    alpha = if (ch.picked == k && !ch.useSaved) 1f else 0.4f
                    layoutParams = LinearLayout.LayoutParams(ui.dp(84), ui.dp(84)).apply { if (k > 0) marginStart = ui.dp(8) }
                    setOnClickListener { s.pickCast(ch.cast.id, k); render() }
                })
            }
            if (ch.options.isNotEmpty()) content.addView(row)
            val links = ui.horizontal(top = 6)
            links.addView(ui.action(if (ch.picked < 0 && !ch.useSaved) L.t("✓ בלי תמונה", "✓ Without a photo") else L.t("בלי תמונה", "Without a photo")) { s.pickCast(ch.cast.id, -1); render() })
            links.addView(ui.action(L.t("   מהגלריה", "   From the gallery")) {
                s.galleryFor = ch.cast.id
                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            })
            content.addView(links)
        }
        content.addView(ui.button(L.t("המשיכו לציור הדמויות", "Continue to drawing the characters"), primary = true, top = 20) { s.confirmCast(); render() })
        content.addView(ui.hint(L.t("כאן יורד קרדיט אחד לספר. אם לא תאשרו את הדמויות, הוא יוחזר.", "One credit is used for the book here. If you don't approve the characters, it's returned."), top = 6))
        content.addView(ui.action(L.t("ביטול", "Cancel")) { s.cancel(); render() }.apply { setPadding(0, ui.dp(16), 0, 0) })
    }

    /** One person's saved characters: tap a version to make it the one the next book uses; delete one or all. */
    private fun characterMenu(name: String, versions: List<SavedCharacter>) {
        val main = versions.firstOrNull() ?: return
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(20), ui.dp(8), ui.dp(20), 0) }
        var dialog: AlertDialog? = null
        if (versions.size > 1) {
            box.addView(ui.hint(L.t("הגרסה המסומנת היא זו שהספר הבא ישתמש בה. לחיצה על גרסה אחרת מחליפה.", "The marked version is the one the next book uses. Tap another to switch."), top = 0))
            val scroll = android.widget.HorizontalScrollView(this)
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (v in versions) row.addView(ImageView(this).apply {
                setImageBitmap(BitmapFactory.decodeFile(CharacterStore.file(this@BookActivity, v).absolutePath, BitmapFactory.Options().apply { inSampleSize = 4 }))
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundResource(R.drawable.rounded_media)
                clipToOutline = true
                alpha = if (v.id == main.id) 1f else 0.45f
                setOnClickListener { if (!CharacterStore.makeMain(this@BookActivity, v.id)) charFail(); dialog?.dismiss(); render() }
            }, LinearLayout.LayoutParams(ui.dp(84), ui.dp(112)).apply { marginEnd = ui.dp(8) })
            scroll.addView(row)
            box.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ui.dp(8) })
        }
        dialog = AlertDialog.Builder(this)
            .setTitle(L.t("הדמות של $name", "$name's character"))
            .setView(box)
            .setPositiveButton(if (versions.size > 1) L.t("למחוק את הגרסה המסומנת", "Delete the marked version") else L.t("מחיקה", "Delete")) { _, _ -> if (!CharacterStore.delete(this, main.id)) charFail(); render() }
            .setNeutralButton(if (versions.size > 1) L.t("למחוק את כל הגרסאות", "Delete all versions") else null) { _, _ -> if (!CharacterStore.deleteAll(this, main.member)) charFail(); render() }
            .setNegativeButton(L.t("סגירה", "Close"), null)
            .show()
    }

    /** Saving or deleting a character didn't go through: say so instead of looking done. */
    private fun charFail() = android.widget.Toast.makeText(this, L.t("לא הצלחנו לשמור את השינוי בדמויות. נסו שוב.", "Couldn't save the change to the characters. Try again."), android.widget.Toast.LENGTH_LONG).show()

    /**
     * Saved characters of one person (newest first): tap one to use it (the chosen one is marked), and, for the hero,
     * "a new drawing". [title]: a heading, or null when the person's name is already shown.
     */
    private fun savedRow(title: String?, versions: List<SavedCharacter>, chosen: SavedCharacter?, onPick: (SavedCharacter) -> Unit,
                         onNew: (() -> Unit)?, newChosen: Boolean) {
        if (title != null) content.addView(ui.text(title, 17f, bold = true, top = 18))
        content.addView(ui.hint(L.t("הדמות מהספרים הקודמים:", "The character from earlier books:"), top = 2))
        val row = ui.horizontal(top = 6)
        versions.take(4).forEachIndexed { k, v ->
            val cell = android.widget.FrameLayout(this).apply { layoutParams = LinearLayout.LayoutParams(ui.dp(84), ui.dp(112)).apply { if (k > 0) marginStart = ui.dp(8) } }
            cell.addView(ImageView(this).apply {
                setImageBitmap(BitmapFactory.decodeFile(CharacterStore.file(this@BookActivity, v).absolutePath, BitmapFactory.Options().apply { inSampleSize = 4 }))
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundResource(R.drawable.rounded_media)
                clipToOutline = true
                alpha = if (chosen?.id == v.id) 1f else 0.4f
            }, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            if (chosen?.id == v.id) cell.addView(TextView(this).apply {
                text = "✓"; textSize = 14f; setTextColor(android.graphics.Color.WHITE)
                setBackgroundColor(0xDD1A6FCC.toInt()); setPadding(ui.dp(8), ui.dp(2), ui.dp(8), ui.dp(3))
            }, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, android.view.Gravity.TOP or android.view.Gravity.END))
            cell.setOnClickListener { onPick(v) }
            row.addView(cell)
        }
        content.addView(row)
        if (onNew != null) content.addView(ui.action(if (newChosen) L.t("✓ ציור חדש מהתמונות שבחרתם", "✓ A new drawing from the photos you chose") else L.t("לצייר דמות חדשה במקום", "Draw a new character instead")) { onNew() }.apply { setPadding(0, ui.dp(8), 0, 0) })
    }

    private fun pages(s: BookMaker.Session) {
        content.addView(ui.text(L.t("מציירים את הספר של ${s.childName}", "Drawing ${s.childName}'s book"), 19f, bold = true, top = 20))
        // The little book shows every picture as soon as it's drawn: the cover, then page after page.
        val book = bookView(s.bookId).apply { drawn = s.pagesDone; total = s.pagesTotal; tour = false; images = drawnPictures(s.bookId, false) }
        content.addView(book)
        val total = s.pagesTotal.coerceAtLeast(1)
        val count = ui.text("", 16f, bold = true, top = 8)
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(8)).apply { topMargin = ui.dp(8) }
        }
        val fun_ = ui.hint("", top = 8)
        content.addView(count)
        content.addView(bar)
        content.addView(fun_)
        val lines = listOf(
            L.t("מערבבים צבעים...", "Mixing colors..."),
            L.t("מחדדים את העפרונות...", "Sharpening the pencils..."),
            L.t("${s.childName} נכנס/ת לעמוד הבא...", "${s.childName} steps into the next page..."),
            L.t("מוסיפים קצת אור של שקיעה...", "Adding a little sunset light..."),
            L.t("מוודאים שכולם מחייכים...", "Making sure everyone is smiling..."),
            L.t("מפזרים קצת קסם בין השורות...", "Sprinkling a little magic between the lines..."),
        )
        // Every second: the bar creeps forward while a picture is being drawn (each takes about a minute or two),
        // never past the pictures still in progress, and jumps when one is done.
        val tick = object : Runnable {
            override fun run() {
                if (BookMaker.current !== s || s.state != BookMaker.State.PAGES) return
                val doneNow = s.pagesDone
                val sec = (System.currentTimeMillis() - s.pagesSince) / 1000f
                // Two pictures at a time, about 90 seconds each.
                val guess = s.pagesAtStart + sec / 45f
                val shown = guess.coerceIn(doneNow.toFloat(), minOf(doneNow + 1.8f, total.toFloat()))
                bar.progress = (1000 * shown / total).toInt()
                count.text = L.t("צוירו $doneNow מתוך $total ציורים (כריכה ו-${total - 1} עמודים)", "$doneNow of $total pictures drawn (cover and ${total - 1} pages)")
                fun_.text = lines[((sec / 4).toInt()) % lines.size]
                if (book.images.size != doneNow) book.images = drawnPictures(s.bookId, false)
                bar.postDelayed(this, 1000)
            }
        }
        tick.run()
        if (s.waiting) content.addView(ui.text(L.t("אין חיבור לאינטרנט כרגע. ממשיכים לבד כשהחיבור יחזור.", "No internet connection right now. We'll carry on by ourselves when it's back."), 15f, R.color.accent, top = 12))
        content.addView(ui.hint(L.t("כמה דקות. אפשר לצאת מהאפליקציה: הספר ממשיך ברקע ונשלח התראה כשהוא מוכן.", "A few minutes. You can leave the app: the book carries on and we'll notify you when it's ready."), top = 10))
    }

    /** Every page coming to life, one by one (about a minute or two each, a few at a time on the server). */
    private fun animating(s: BookMaker.Session) {
        content.addView(ui.text(L.t("מנפישים את הספר של ${s.childName}", "Animating ${s.childName}'s book"), 19f, bold = true, top = 20))
        // The finished book, leafing through its pages while they come to life.
        content.addView(bookView(s.bookId).apply { drawn = s.clipsDone; total = s.clipsTotal; tour = true; images = drawnPictures(s.bookId, true) })
        content.addView(ui.text(if (s.clipsTotal > 0) L.t("הונפשו ${s.clipsDone} מתוך ${s.clipsTotal} עמודים", "${s.clipsDone} of ${s.clipsTotal} pages animated") else s.message, 16f, bold = true, top = 8))
        content.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; progress = s.progress
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(8)).apply { topMargin = ui.dp(8) }
        })
        if (s.waiting) content.addView(ui.text(L.t("אין חיבור לאינטרנט כרגע. ממשיכים לבד כשהחיבור יחזור.", "No internet connection right now. We'll carry on by ourselves when it's back."), 15f, R.color.accent, top = 12))
        content.addView(ui.hint(L.t("כ-10 דקות. אפשר לצאת מהאפליקציה: נשלח התראה כשהסרט מוכן. הספר עצמו כבר מוכן לדפדוף.", "About 10 minutes. You can leave the app: we'll notify you when the film is ready. The book itself is already ready to read."), top = 10))
    }

    private fun done(s: BookMaker.Session) {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val b = s.book ?: return
        content.addView(ui.text(L.t("הספר מוכן 📖", "The book is ready 📖"), 20f, bold = true, top = 20))
        if (s.message.isNotEmpty()) content.addView(ui.hint(s.message, top = 4))
        val fresh = BookStore.load(this, b.id) ?: b
        val (ok, total) = BookReview.count(fresh)
        content.addView(ui.hint(
            if (ok == total) L.t("כל העמודים אושרו. אפשר להכין את הספר להדפסה מהעמוד האחרון שלו.", "All pages approved. You can prepare the book for print from its last page.")
            else L.t("לפני הדפסה: עברו על הספר, תקנו מה שצריך (טקסט או ציור) ואשרו כל עמוד. אושרו $ok מתוך $total.", "Before printing: go over the book, fix what's needed (text or picture) and approve each page. $ok of $total approved."),
            top = 8,
        ))
        content.addView(ui.button(L.t("פתחו את הספר", "Open the book"), primary = true, top = 16) { BookViewerActivity.open(this, b.id) })
        // The animated book's film: watch it, or make it now.
        if (fresh.film.isNotEmpty()) content.addView(ui.button(L.t("▶ צפייה בסרט של הספר", "▶ Watch the book's film"), top = 8) { BookViewerActivity.playFilm(this, fresh.film) })
        else content.addView(ui.button(L.t("🎬 להפוך לספר מונפש (${BookMaker.ANIMATION_EXTRA})", "🎬 Make it an animated book (${BookMaker.ANIMATION_EXTRA})"), top = 8) { Billing.ensureCredits(this, 1, Billing.ANIMATION) { s.animateNow(); render() } })
        content.addView(ui.action(L.t("ספר חדש", "A new book")) { BookMaker.clear(); render() }.apply { setPadding(0, ui.dp(16), 0, 0) })
    }

    /** No connection for a few minutes: the book is saved as it is. */
    private fun paused(s: BookMaker.Session) {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        content.addView(ui.text(L.t("הספר נשמר", "The book is saved"), 18f, bold = true, top = 22))
        content.addView(ui.hint(s.message, top = 6))
        content.addView(ui.button(L.t("המשיכו את הספר", "Continue the book"), primary = true, top = 16) { s.retry(); render() })
        content.addView(ui.action(L.t("ביטול הספר", "Cancel the book")) { s.cancel(); render() }.apply { setPadding(0, ui.dp(16), 0, 0) })
    }

    /** The credit's window is over: continuing needs the parent's OK for one more credit. */
    private fun needCredit(s: BookMaker.Session) {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        content.addView(ui.text(L.t("להמשיך את הספר?", "Continue the book?"), 18f, bold = true, top = 22))
        content.addView(ui.hint(s.message, top = 6))
        content.addView(ui.button(L.t("כן, להמשיך (קרדיט אחד)", "Yes, continue (one credit)"), primary = true, top = 16) { Billing.ensureCredits(this, 1, Billing.DIGITAL) { s.continueWithNewCredit(); render() } })
        content.addView(ui.action(L.t("לא, לבטל את הספר", "No, cancel the book")) { s.cancel(); render() }.apply { setPadding(0, ui.dp(16), 0, 0) })
    }

    private fun failed(s: BookMaker.Session) {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        content.addView(ui.text(L.t("הספר לא נוצר", "The book wasn't created"), 18f, bold = true, top = 22))
        content.addView(ui.hint(s.message, top = 6))
        content.addView(ui.button(L.t("חזרה", "Back"), primary = true, top = 16) { BookMaker.clear(); render() })
    }
}

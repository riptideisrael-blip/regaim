package com.regaim.app

import android.app.Activity
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
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * "סטודיו": one clear place for everything you can MAKE from your moments —
 * a 3D animated film, a comic, a video with music, the story of the week.
 */
class StudioActivity : AppCompatActivity() {
    companion object {
        fun open(ctx: Context) = ctx.startActivity(Intent(ctx, StudioActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))

        /** A grid of moments (videos first) to choose from. Shared by every "make something" entry point. */
        fun pickMoment(a: Activity, title: String, onPick: (Memory) -> Unit) {
            val all = MemoryStore.kept(a)
            if (all.isEmpty()) {
                Toast.makeText(a, L.t("עוד אין רגעים. צלמו או הוסיפו רגע קודם.", "No moments yet. Capture or add one first."), Toast.LENGTH_LONG).show()
                return
            }
            val ui = Ui(a)
            val items = (all.filter { it.isVideo } + all.filter { !it.isVideo }).take(60)
            val grid = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(12), ui.dp(4), ui.dp(12), ui.dp(8)) }
            if (items.any { it.isVideo }) grid.addView(ui.hint(L.t("סרטונים נותנים את התוצאה הכי טובה. הם מופיעים ראשונים.", "Videos give the best result. They're shown first."), top = 0))
            var dialog: AlertDialog? = null
            items.chunked(3).forEach { rowItems ->
                val r = LinearLayout(a).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ui.dp(6) }
                }
                rowItems.forEachIndexed { i, m ->
                    val frame = FrameLayout(a).apply {
                        setBackgroundResource(R.drawable.rounded_media)
                        clipToOutline = true
                        layoutParams = LinearLayout.LayoutParams(0, ui.dp(120), 1f).apply { if (i > 0) marginStart = ui.dp(6) }
                        setOnClickListener { dialog?.dismiss(); onPick(m) }
                    }
                    val img = ImageView(a).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    }
                    frame.addView(img)
                    Covers.load(img, m)
                    frame.addView(TextView(a).apply {
                        text = if (m.isVideo) "▶ " + Fmt.shortDuration(m.durationMs) else Fmt.day(m.time)
                        textSize = 11f
                        setTextColor(DS.onPhoto(context))
                        setShadowLayer(4f, 0f, 1f, DS.scrim(context))
                        setPadding(ui.dp(6), ui.dp(4), ui.dp(6), ui.dp(4))
                        layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START)
                    })
                    r.addView(frame)
                }
                repeat(3 - rowItems.size) { r.addView(View(a).apply { layoutParams = LinearLayout.LayoutParams(0, ui.dp(120), 1f).apply { marginStart = ui.dp(6) } }) }
                grid.addView(r)
            }
            dialog = AlertDialog.Builder(a)
                .setTitle(title)
                .setView(android.widget.ScrollView(a).apply { addView(grid) })
                .setNegativeButton(L.t("ביטול", "Cancel"), null)
                .show()
        }

        fun makeFilm(a: Activity) = pickMoment(a, L.t("איזה רגע להפוך לסרט מצויר?", "Which moment becomes a film?")) { CartoonFilmActivity.open(a, it.id) }
        fun makeComic(a: Activity) = pickMoment(a, L.t("מאיזה רגע לעשות קומיקס?", "Which moment becomes a comic?")) { ComicActivity.forMoment(a, it.id) }
    }

    private lateinit var ui: Ui
    private lateinit var content: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_screen)
        findViewById<TextView>(R.id.screenTitle).text = L.t("סטודיו", "Studio")
        findViewById<View>(R.id.btnClose).visibility = View.GONE
        findViewById<View>(R.id.bottomNav).visibility = View.VISIBLE
        Nav.setup(this, Nav.Tab.STUDIO)
        Nav.addProfileButton(this, findViewById<View>(R.id.screenTitle).parent as ViewGroup)
        content = findViewById(R.id.screenContent)
        ui = Ui(this)
    }

    private var firstShow = true

    override fun onResume() {
        super.onResume()
        render()
        // Always open at the top, where the 3D film is.
        if (firstShow) { firstShow = false; findViewById<View>(R.id.screenScroll).post { findViewById<View>(R.id.screenScroll).scrollTo(0, 0) } }
    }

    private fun render() {
        content.removeAllViews()
        content.addView(ui.hint(L.t("כאן הופכים רגעים למשהו שאפשר לשלוח, להראות ולשמור.", "Turn your moments into something to send, show and keep."), top = 4))

        // A film being made right now: the way back to it.
        CartoonFilm.current?.takeIf { it.isActive }?.let { s ->
            val card = ui.card(top = 14)
            card.addView(ui.text(L.t("סרט בהכנה", "A film is being made"), 17f, bold = true))
            card.addView(ui.hint(L.t("${s.estimate()}% · לחצו כדי לראות", "${s.estimate()}% · tap to see it"), top = 4))
            card.setOnClickListener { CartoonFilmActivity.open(this, s.memory.id) }
            content.addView(card)
        }

        // New here, nothing to make from yet: start from the gallery.
        if (MemoryStore.kept(this).isEmpty()) {
            val card = ui.card(top = 14)
            card.addView(ui.text(L.t("קודם מוסיפים רגע", "First, add a moment"), 17f, bold = true))
            card.addView(ui.hint(L.t("בחרו סרטון קצר מהגלריה, למשל של הילדים, ונהפוך אותו לסרט מצויר.", "Pick a short video from your gallery, say of your kids, and we'll turn it into an animated film."), top = 4))
            card.addView(ui.button(L.t("הוסיפו מהגלריה", "Add from your gallery"), primary = true, top = 12) { MediaImportActivity.open(this, pickNow = true) })
            content.addView(card)
        }

        // The headline feature: big, dark, impossible to miss.
        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.card_accent_bg)
            setPadding(ui.dp(22), ui.dp(22), ui.dp(22), ui.dp(22))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ui.dp(16) }
            setOnClickListener { makeFilm(this@StudioActivity) }
        }
        hero.addView(TextView(this).apply {
            text = L.t("פרימיום", "Premium")
            textSize = 13f
            setTextColor(DS.onPhoto(context, 0.75f))
        })
        hero.addView(TextView(this).apply {
            text = L.t("סרט מצויר בתלת-ממד", "3D animated film")
            textSize = 26f
            typeface = Fonts.display
            setTextColor(ContextCompat.getColor(this@StudioActivity, R.color.white))
            setPadding(0, ui.dp(6), 0, 0)
        })
        hero.addView(TextView(this).apply {
            text = L.t("בוחרים רגע, ואנחנו מציירים אותו מחדש כדמויות אנימציה בתלת-ממד, בסגנון סרטי פיקסאר, ומחברים לסרט קצר עם מוזיקה.",
                "Pick a moment and we redraw it as 3D animated characters, Pixar-movie style, cut into a short film with music.")
            textSize = 15f
            setLineSpacing(ui.dp(3).toFloat(), 1f)
            setTextColor(DS.onPhoto(context, 0.87f))
            setPadding(0, ui.dp(8), 0, ui.dp(16))
        })
        hero.addView(TextView(this).apply {
            text = L.t("בחרו רגע ›", "Choose a moment ›")
            textSize = 16f
            gravity = Gravity.CENTER
            typeface = DS.emphasis
            setTextColor(ContextCompat.getColor(this@StudioActivity, R.color.ink))
            background = DS.buttonBackground(this@StudioActivity, primary = false)
            setPadding(ui.dp(16), ui.dp(13), ui.dp(16), ui.dp(13))
            setOnClickListener { makeFilm(this@StudioActivity) }
        })
        content.addView(hero)

        // Films already made: always findable here (they also sit in the archive next to their moment).
        val films = MemoryStore.kept(this).filter { it.origin == "film" }.sortedByDescending { it.id }
        if (films.isNotEmpty()) {
            content.addView(ui.section(L.t("הסרטים שלכם", "Your films")))
            val strip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val mv = MemoryViews(this)
            films.forEach { f -> strip.addView(mv.mini(f, 104, 150) { MomentActivity.open(this, f.id) }) }
            content.addView(ui.scrollRow(strip, top = 8))
        }

        content.addView(ui.section(L.t("עוד דברים שאפשר ליצור", "More to make")))
        option(R.drawable.ic_sparkle, L.t("פיצקי · תוכנית למידה לילדים", "Pitzki · a learning program for kids"),
            L.t("תוכנית חינוכית אישית: טיולים קצרים שבהם הילד/ה לומד/ת צבעים, מספרים ועוד, בעברית ובאנגלית, כגיבור/ת הסיפור. אתם בוחרים ומלווים.", "A personal educational program: short walks where your child learns colors, numbers and more, in Hebrew and English, as the hero of the story. You choose and stay alongside.")) { PitzkiActivity.open(this) }
        if (Profile.members(this).any { it.isChild }) option(R.drawable.ic_star, L.t("ספר ילדים אישי", "A personal children's book"),
            L.t("ספר מאויר שהילד/ה הגיבור/ה שלו: נפרדים מהמוצץ, לא מפחדים מהחושך, אח חדש ועוד.", "An illustrated book starring your child: the pacifier, the dark, a new sibling and more.")) { BookActivity.open(this) }
        if (false) option(R.drawable.ic_comic, L.t("קומיקס מרגע", "Comic from a moment"),
            L.t("הרגע הופך לעמוד קומיקס עם סיפור. אפשר לבחור סגנון: ספר ילדים, מנגה, סקיצה ועוד.", "The moment becomes a comic page with a story. Styles: picture book, manga, sketch and more.")) { makeComic(this) }
        option(R.drawable.ic_videocam, L.t("סרטון עם מוזיקה", "Video with music"),
            L.t("הרגעים הכי טובים של השבוע או החודש, ערוכים לסרטון אחד עם מוזיקה.", "The best moments of the week or month, edited into one video with music.")) { PeriodVideoActivity.open(this) }
        if (ReelMaker.weekMoments(this).isNotEmpty()) option(R.drawable.ic_play, L.t("הסיפור של השבוע", "This week's story"),
            L.t("כל רגעי השבוע ברצף, כמו סטורי.", "All of this week's moments in a row, like a story.")) { StoryActivity.week(this) }
        option(R.drawable.ic_send, L.t("סיכום לסבא וסבתא", "Digest for the grandparents"),
            L.t("הרגעים של השבוע בסרטון או קולאז׳, מוכנים לשליחה בוואטסאפ. מתזכר כל יום שישי.", "The week's moments as a video or collage, ready for WhatsApp. Reminds you every Friday.")) { DigestActivity.open(this) }
        option(R.drawable.ic_photos, L.t("סיפורים לפי נושא", "Stories by topic"),
            L.t("הרגעים שלכם מסודרים לפי נושאים: אמבטיה, משחק, טיולים ועוד.", "Your moments grouped by topic: bath time, play, outings and more.")) { StoriesActivity.open(this) }
    }

    /** One thing to make: an icon in a soft circle, a title, one line about it. The whole card is the button. */
    private fun option(icon: Int, title: String, sub: String, go: () -> Unit) {
        val card = ui.card(top = 10)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(ImageView(this).apply {
            setImageResource(icon)
            setColorFilter(ui.color(R.color.accent))
            setPadding(ui.dp(11), ui.dp(11), ui.dp(11), ui.dp(11))
            background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(DS.buttonSecondary(this@StudioActivity)) }
            layoutParams = LinearLayout.LayoutParams(ui.dp(46), ui.dp(46))
        })
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(14), 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        texts.addView(ui.text(title, 17f, bold = true))
        texts.addView(ui.hint(sub, top = 2))
        row.addView(texts)
        row.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_chevron)
            setColorFilter(ui.color(R.color.ink_soft))
            if (L.isEn) scaleX = -1f // the arrow points "forward": left in Hebrew, right in English
            layoutParams = LinearLayout.LayoutParams(ui.dp(20), ui.dp(20))
        })
        card.addView(row)
        card.setOnClickListener { go() }
        content.addView(card)
    }
}

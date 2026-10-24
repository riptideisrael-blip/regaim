package com.regaim.app

import android.app.DatePickerDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.util.Calendar
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/** "הגדרות": family profile, what saves a moment, the camera, people & faces, AI keys, learning and privacy. */
open class SettingsActivity : AppCompatActivity() {

    companion object {
        fun open(ctx: Context) = ctx.startActivity(Intent(ctx, SettingsActivity::class.java))
        /** Opens the profile straight at "a new child" (from Pitzki's "add a child"). */
        const val EXTRA_ADD_KID = "add_kid"
    }

    private lateinit var ui: Ui
    private lateinit var content: LinearLayout
    private val worker = Executors.newSingleThreadExecutor()
    private var parentField: EditText? = null
    private var familyField: EditText? = null
    private var role = ""
    private var advanced = false
    private var onPhotoPicked: ((String) -> Unit)? = null
    private val pickPhoto = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val callback = onPhotoPicked
            worker.execute {
                val result = try {
                    val dir = File(filesDir, "family_photos").apply { mkdirs() }
                    val target = File(dir, "${UUID.randomUUID()}.jpg")
                    val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, options) }
                    options.inJustDecodeBounds = false
                    options.inSampleSize = (maxOf(options.outWidth, options.outHeight) / 768).coerceAtLeast(1)
                    val bitmap = contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, options) }
                        ?: throw IllegalStateException("לא ניתן לקרוא את התמונה")
                    target.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, it) }
                    bitmap.recycle()
                    Result.success(target.absolutePath)
                } catch (_: Exception) { Result.failure<String>(IllegalStateException(L.t("לא הצלחנו לשמור את התמונה. נסו תמונה אחרת.", "Couldn't save the photo. Try another one."))) }
                runOnUiThread { if (!isFinishing) result.onSuccess { callback?.invoke(it) }.onFailure { toast(it.message.orEmpty()) } }
            }
        }
        onPhotoPicked = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_screen)
        ui = Ui(this)
        Settings.load(this)
        content = findViewById(R.id.screenContent)
        val tab = this is ProfileActivity
        findViewById<TextView>(R.id.screenTitle).text = if (tab) L.t("פרופיל", "Profile") else L.t("הגדרות", "Settings")
        findViewById<View>(R.id.btnClose).apply {
            visibility = View.VISIBLE // the profile is no longer a tab: it opens from the picture at the top
            setOnClickListener { finish() }
        }
        if (tab) {
            findViewById<View>(R.id.bottomNav).visibility = View.VISIBLE
            Nav.setup(this, Nav.Tab.PROFILE)
        }
        // A clear "save" bar under the settings.
        val root = findViewById<View>(R.id.screenScroll).parent as LinearLayout
        saveBar = ui.button(L.t("✓ הכל שמור", "✓ All saved"), top = 0) { saveAll() }.apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(ui.dp(20), ui.dp(6), ui.dp(20), ui.dp(8))
            }
        }
        root.addView(saveBar, root.indexOfChild(findViewById(R.id.bottomNav)))
        ui.onChange = { if (!rendering) markDirty() }
        if (savedInstanceState == null && intent.getBooleanExtra(EXTRA_ADD_KID, false)) content.post { if (!isFinishing) editMember(null, kid = true) }
    }

    private var saveBar: com.google.android.material.button.MaterialButton? = null
    private var phrasesField: EditText? = null
    private var rendering = false

    private fun markDirty() {
        saveBar?.apply {
            text = L.t("שמור שינויים", "Save changes")
            backgroundTintList = android.content.res.ColorStateList.valueOf(ui.color(R.color.accent))
            setTextColor(ui.color(R.color.white))
        }
    }

    private fun markSaved() {
        saveBar?.apply {
            text = L.t("✓ הכל שמור", "✓ All saved")
            backgroundTintList = android.content.res.ColorStateList.valueOf(0xFFF0E6DC.toInt())
            setTextColor(ui.color(R.color.accent_dark))
        }
    }

    /** Saves everything typed on the screen (switches and choices are saved the moment you change them). */
    private fun saveAll() {
        saveParent()
        phrasesField?.let { f ->
            val list = Phrases.parse(f.text.toString())
            if (list.isNotEmpty()) {
                Status.phrases = list
                Settings.putString(this, "phrases", list.joinToString(", "))
            }
        }
        Settings.load(this)
        markSaved()
        Toast.makeText(this, L.t("נשמר ✓", "Saved ✓"), Toast.LENGTH_SHORT).show()
        if (Status.running) restartNote()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onPause() {
        super.onPause()
        // Never lose what was typed, even without pressing save.
        saveParent()
        phrasesField?.let { f ->
            val list = Phrases.parse(f.text.toString())
            if (list.isNotEmpty()) { Status.phrases = list; Settings.putString(this, "phrases", list.joinToString(", ")) }
        }
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
    private fun restartNote() { if (Status.running) toast(L.t("השינוי ייכנס לתוקף כשתעצור ותתחיל שוב", "This takes effect next time you stop and start")) }

    /**
     * Deleting the account and everything it put on Moments' server, after the parent types "מחק" to be sure.
     * The memories on the phone stay (they were never on the server); removing the app removes them.
     */
    private fun deleteAccount() {
        val word = L.t("מחק", "DELETE")
        val field = EditText(this).apply {
            hint = L.t("הקלידו: $word", "Type: $word")
            val p = ui.dp(20); setPadding(p, p / 2, p, p / 2)
        }
        AlertDialog.Builder(this)
            .setTitle(L.t("למחוק את החשבון לצמיתות?", "Delete your account for good?"))
            .setMessage(L.t(
                "יימחקו מהשרת של Moments: החשבון, הרגעים שהעליתם למעגל המשפחה (עם התגובות והתגובות שלכם), הפוסטים והספרים שפרסמתם בקהילה, הקרדיטים והספרים ששולמו ולא נוצלו. מעגל משפחה שאתם מנהלים יעבור לבן המשפחה שהצטרף ראשון, ואם אין אף אחד, יימחק.\n\n" +
                    "הזיכרונות שבטלפון נשארים בטלפון. כדי למחוק גם אותם, הסירו את האפליקציה.\n\nאי אפשר לבטל את המחיקה.",
                "Deleted from Moments' server: your account, the moments you shared to the family circle (with your comments and reactions), your community posts and books, and unused credits or paid books. A family circle you run passes to the member who joined first, or is deleted if there's nobody.\n\n" +
                    "The memories on this phone stay on the phone. To delete them too, uninstall the app.\n\nThis can't be undone.",
            ))
            .setView(field)
            .setPositiveButton(L.t("מחיקה לצמיתות", "Delete for good")) { _, _ ->
                if (field.text.toString().trim() != word) {
                    Toast.makeText(this, L.t("לא נמחק: צריך להקליד \"$word\"", "Not deleted: type \"$word\""), Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                Toast.makeText(this, L.t("מוחקים את החשבון...", "Deleting your account..."), Toast.LENGTH_SHORT).show()
                worker.execute {
                    val err = try {
                        val token = Account.token(applicationContext) ?: throw IllegalStateException(L.t("צריך להתחבר מחדש", "Please sign in again"))
                        val r = CartoonFilm.callEdge(Circle.url(applicationContext) + "/functions/v1/cartoon-film", token, mapOf("action" to "delete_account", "confirm" to "DELETE"))
                        if (r.optBoolean("ok", false)) null else r.optString("error").ifBlank { L.t("לא הצלחנו למחוק. נסו שוב.", "Couldn't delete. Try again.") }
                    } catch (e: Exception) { e.message ?: L.t("לא הצלחנו למחוק. נסו שוב.", "Couldn't delete. Try again.") }
                    if (err == null) {
                        // Signed out on this phone, with what was tied to the account (family circle, community, payments).
                        try { Account.signOut(applicationContext) } catch (_: Exception) { }
                        getSharedPreferences("billing", MODE_PRIVATE).edit().clear().apply()
                        Settings.prefs(applicationContext).edit().remove("community_terms").remove("community_terms_18").apply()
                    }
                    runOnUiThread {
                        if (isFinishing) return@runOnUiThread
                        Toast.makeText(this, err ?: L.t("החשבון נמחק", "Your account was deleted"), Toast.LENGTH_LONG).show()
                        render()
                    }
                }
            }
            .setNegativeButton(L.t("ביטול", "Cancel"), null)
            .show()
    }

    private fun render() {
        val y = findViewById<View>(R.id.screenScroll).scrollY
        rendering = true
        content.removeAllViews()
        if (this is ProfileActivity) profileHeader()
        languageSection()
        updateSection()
        profileSection()
        privacySection()
        remindersSection()
        content.addView(ui.section(L.t("הגדרות נוספות", "More settings")))
        content.addView(ui.row(L.t("הגדרות צילום, הבנת רגעים ולמידה", "Recording, moment understanding & learning"), if (advanced) L.t("הסתר", "Hide") else L.t("פתח", "Open")) {
            saveParent()
            phrasesField?.let { Settings.putString(this, "phrases", it.text.toString()) }
            advanced = !advanced
            render()
        })
        if (advanced || this !is ProfileActivity) {
            triggersSection()
            cameraSection()
            peopleSection()
            aiSection()
            learningSection()
        }
        content.post {
            findViewById<View>(R.id.screenScroll).scrollTo(0, y)
            rendering = false
        }
    }

    // ---------- language ----------

    /** "שפה": switch the whole app between Hebrew and English. */
    /** Who made the recorded music, and its license (from the app's own files). */
    private fun musicCredits() {
        val text = try {
            assets.open("music/CREDITS.txt").bufferedReader().use { it.readText() } + "\n\n" +
                assets.open("music/LICENSE.txt").bufferedReader().use { it.readText() }
        } catch (_: Exception) { return }
        AlertDialog.Builder(this)
            .setTitle(L.t("קרדיטים למוזיקה", "Music credits"))
            .setMessage(text)
            .setPositiveButton(L.t("סגירה", "Close"), null)
            .show()
    }

    private fun languageSection() {
        val card = ui.card(top = 8)
        card.addView(ui.row("🌐 " + L.t("שפה", "Language"), if (L.isEn) "English" else "עברית", top = 0) {
            val codes = arrayOf(L.HE, L.EN)
            AlertDialog.Builder(this)
                .setTitle(L.t("שפה", "Language"))
                .setSingleChoiceItems(arrayOf("עברית", "English"), if (L.isEn) 1 else 0) { d, which ->
                    d.dismiss()
                    val chosen = codes[which]
                    if (chosen != L.lang) {
                        saveParent()
                        phrasesField?.let { Settings.putString(this, "phrases", it.text.toString()) }
                        L.set(this, chosen)
                        recreate()
                    }
                }
                .setNegativeButton(L.t("ביטול", "Cancel"), null)
                .show()
        })
        card.addView(ui.row("🎵 " + L.t("קרדיטים למוזיקה", "Music credits"), "") { musicCredits() })
        // The accessibility statement and the privacy policy (on the family's web page; required for a public app).
        card.addView(ui.row("♿ " + L.t("נגישות ופנייה לרכז הנגישות", "Accessibility and contact"), "") { openWeb("accessibility.html") })
        card.addView(ui.row("🔒 " + L.t("מדיניות פרטיות", "Privacy policy"), "") { openWeb("privacy.html") })
        content.addView(card)
    }

    private fun openWeb(page: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Circle.web(this).trimEnd('/') + "/" + page))) }
        catch (_: Exception) { toast(L.t("לא נמצא דפדפן בטלפון", "No browser found on this phone")) }
    }

    // ---------- "is there a new version?" ----------

    private enum class Upd { IDLE, CHECKING, NONE, FOUND, DOWNLOADING, READY, FAILED }
    private var upd = Upd.IDLE
    private var updInfo: AppUpdate.Info? = null
    private var updFile: File? = null
    private var updProgress = 0
    private var updError = ""
    private var updCard: LinearLayout? = null

    /** The installed version, and a button that looks for a newer one on our server and installs it. */
    private fun updateSection() {
        val card = ui.card(top = 8)
        updCard = card
        fillUpdate()
        content.addView(card)
        // Looked for once when the settings open (quietly), if signed in.
        if (upd == Upd.IDLE && Account.signedIn(this)) checkUpdate()
    }

    private fun fillUpdate() {
        val card = updCard ?: return
        card.removeAllViews()
        val version = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Exception) { "" }
        card.addView(ui.text("⬆️ " + L.t("עדכון גרסה", "App updates"), 16f, top = 0).apply { typeface = DS.emphasis })
        card.addView(ui.hint(L.t("מותקנת: ${UpdatePrompt.shown(version)}", "Installed: ${UpdatePrompt.shown(version)}"), top = 2))
        // Admin only: try the protected (R8: smaller, its code scrambled) build here first, before the families get it.
        if (Account.isAdmin(this)) {
            card.addView(ui.switch(L.t("🧪 לקבל את הגרסה המוגנת (בדיקה, רק אצלי)", "🧪 Get the protected build (testing, only me)"), AppUpdater.beta(this), top = 6) { on ->
                AppUpdater.setBeta(this, on); upd = Upd.IDLE; updInfo = null; checkUpdate()
            })
            card.addView(ui.hint(L.t("גרסה קטנה יותר שהקוד שלה מוצפן. אם משהו לא עובד בה: לכבות, ולהתקין את הרגילה מ-GitHub מעליה.",
                "Smaller, with its code scrambled. If something doesn't work: turn this off and install the regular one from GitHub over it."), top = 2))
        }
        // Updates come from the family's server, which needs an account: say so plainly (instead of an error later).
        if (!Account.signedIn(this)) {
            card.addView(ui.hint(L.t("כדי לקבל גרסאות חדשות (תיקונים ושיפורים) צריך להתחבר לחשבון. הזיכרונות שבטלפון נשארים כמו שהם.",
                "To get new versions (fixes and improvements), sign in to an account. The memories on this phone stay as they are."), top = 8))
            card.addView(ui.button(L.t("התחברות או הרשמה", "Sign in or sign up"), primary = true, top = 8) { WelcomeActivity.open(this) })
            return
        }
        val info = updInfo
        when (upd) {
            Upd.IDLE, Upd.NONE, Upd.FAILED -> {
                if (upd == Upd.NONE) card.addView(ui.text(L.t("✓ יש לך את הגרסה הכי חדשה", "✓ You have the newest version"), 14f, top = 8))
                if (upd == Upd.FAILED) card.addView(ui.text(updError, 14f, R.color.ink_soft, top = 8))
                card.addView(ui.button(L.t("לבדוק אם יש גרסה חדשה", "Check for a new version"), top = 8) { checkUpdate() })
            }
            Upd.CHECKING -> card.addView(ui.text(L.t("בודקים…", "Checking…"), 14f, top = 8))
            Upd.FOUND -> {
                card.addView(ui.text(L.t("יש גרסה חדשה: ${UpdatePrompt.shown(info?.name)}", "A new version: ${UpdatePrompt.shown(info?.name)}"), 15f, bold = true, top = 8))
                card.addView(ui.button(L.t("להוריד ולהתקין (${AppUpdate.mb(info?.size ?: 0)})", "Download and install (${AppUpdate.mb(info?.size ?: 0)})"), primary = true, top = 8) { downloadUpdate() })
            }
            Upd.DOWNLOADING -> card.addView(ui.text(L.t("מורידים… $updProgress%", "Downloading… $updProgress%"), 14f, top = 8))
            Upd.READY -> {
                card.addView(ui.text(L.t("הגרסה ירדה. באישור של אנדרואיד היא תותקן, והזיכרונות נשארים.", "Downloaded. Once Android approves, it installs, and your memories stay."), 14f, top = 8))
                card.addView(ui.button(L.t("להתקין עכשיו", "Install now"), primary = true, top = 8) { updFile?.let { installUpdate(it) } })
            }
        }
    }

    private fun checkUpdate() {
        upd = Upd.CHECKING; fillUpdate()
        worker.execute {
            val result = try { Result.success(AppUpdater.check(this)) } catch (e: Exception) { Result.failure(e) }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                result.onSuccess { info ->
                    updInfo = info
                    upd = if (AppUpdate.isNewer(info, AppUpdater.installed(this))) Upd.FOUND else Upd.NONE
                }.onFailure {
                    upd = Upd.FAILED
                    updError = it.message ?: L.t("אין חיבור לרשת", "No connection")
                }
                fillUpdate()
            }
        }
    }

    private fun downloadUpdate() {
        val info = updInfo ?: return
        upd = Upd.DOWNLOADING; updProgress = 0; fillUpdate()
        worker.execute {
            var last = -1
            val result = try {
                Result.success(AppUpdater.download(this, info) { p ->
                    if (p != last) { last = p; runOnUiThread { if (upd == Upd.DOWNLOADING) { updProgress = p; fillUpdate() } } }
                })
            } catch (e: Exception) { Result.failure(e) }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                result.onSuccess { f -> updFile = f; upd = Upd.READY; fillUpdate(); installUpdate(f) }
                    .onFailure { upd = Upd.FAILED; updError = it.message ?: L.t("ההורדה נכשלה", "The download failed"); fillUpdate() }
            }
        }
    }

    private fun installUpdate(f: File) {
        try {
            if (!AppUpdater.install(this, f)) toast(L.t("צריך לאשר פעם אחת התקנה מ-Moments, ואז ללחוץ שוב \"להתקין עכשיו\"", "Allow installs from Moments once, then tap \"Install now\" again"))
        } catch (_: Exception) {
            toast(L.t("לא הצלחנו לפתוח את ההתקנה", "Couldn't open the installer"))
        }
    }

    // ---------- profile ----------

    /** Who is signed in (photo, name, email), or a way to sign in. */
    private fun accountCard() {
        val card = ui.card(top = 8)
        val row = ui.horizontal(top = 0)
        val mv = MemoryViews(this)
        val name = Profile.parentName(this).ifEmpty { Account.name(this) }.ifEmpty { L.t("החשבון שלי", "My account") }
        val avatar = mv.avatar(name, 56).apply {
            contentDescription = L.t("שינוי תמונת הפרופיל", "Change profile photo")
            setOnClickListener {
                saveParent()
                onPhotoPicked = { path -> Profile.setPhoto(this@SettingsActivity, path); render() }
                pickPhoto.launch("image/*")
            }
        }
        row.addView(avatar)
        val photo = Account.avatar(this)
        if (photo.isNotEmpty() && Profile.photo(this).isEmpty()) worker.execute {
            val bmp = Circle.image(applicationContext, photo)
            runOnUiThread {
                if (bmp != null && !isFinishing) {
                    val img = android.widget.ImageView(this).apply {
                        setImageBitmap(bmp)
                        scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                        background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL }
                        clipToOutline = true
                        layoutParams = avatar.layoutParams
                    }
                    img.setOnClickListener { avatar.performClick() }
                    img.contentDescription = L.t("שינוי תמונת הפרופיל", "Change profile photo")
                    val i = row.indexOfChild(avatar)
                    if (i >= 0) { row.removeViewAt(i); row.addView(img, i) }
                }
            }
        }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(14), 0, ui.dp(14), 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        texts.addView(ui.text(name, 18f, bold = true))
        texts.addView(ui.hint(if (Account.signedIn(this)) Account.email(this) else L.t("לא מחובר · הזיכרונות שמורים בטלפון", "Not signed in · Memories are saved on this phone"), top = 0))
        row.addView(texts)
        card.addView(row)
        if (Account.isAdmin(this)) card.addView(ui.row(L.t("ניהול", "Admin"), L.t("משתמשים ופעילות", "Users and activity")) { AdminActivity.open(this) })
        if (Account.signedIn(this)) {
            card.addView(ui.action(L.t("התנתקות", "Sign out"), R.color.ink_soft) {
                AlertDialog.Builder(this).setTitle(L.t("להתנתק?", "Sign out?"))
                    .setMessage(L.t("הזיכרונות נשארים בטלפון. מעגל המשפחה יעבוד שוב אחרי שתתחברו.", "Your memories stay on this phone. Family circle will work again once you sign back in."))
                    .setPositiveButton(L.t("התנתק", "Sign out")) { _, _ ->
                        worker.execute {
                            Account.signOut(applicationContext)
                            runOnUiThread { if (!isFinishing) render() }
                        }
                    }
                    .setNegativeButton(L.t("ביטול", "Cancel"), null).show()
            })
            card.addView(ui.action(L.t("מחיקת החשבון", "Delete account"), R.color.ink_soft) { deleteAccount() })
        } else {
            card.addView(ui.button(L.t("התחברות או הרשמה", "Sign in or sign up"), primary = true, top = 12) { WelcomeActivity.open(this) })
        }
        content.addView(card)
    }

    /** Top of the Profile tab: the family at a glance, and the ways in to what matters. */
    private fun profileHeader() {
        val all = MemoryStore.kept(this)
        accountCard()
        val card = ui.card(top = 8)
        card.addView(ui.text(Profile.homeTitle(this), 22f, bold = true))
        val members = Profile.members(this)
        val line = listOf(
            L.t("${all.size} זיכרונות", "${all.size} memories"),
            L.t("${all.count { it.favorite }} מועדפים", "${all.count { it.favorite }} favorites"),
            if (members.isNotEmpty()) members.joinToString(" · ") { it.name } else "",
        ).filter { it.isNotEmpty() }.joinToString(" · ")
        card.addView(ui.hint(line, top = 2))
        card.addView(ui.row(if (Status.running) L.t("Moments מצלם עכשיו", "Moments is recording now") else L.t("מצב צילום", "Recording mode"), L.t("פתח", "Open"), top = 8) {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        })
        card.addView(ui.row(L.t("מה Moments למד עליכם", "What Moments learned about you"), "") { LearnedActivity.open(this) })
        card.addView(ui.row(L.t("אנשים ופנים", "People and faces"), "") { PeopleActivity.open(this) })
        card.addView(ui.row(L.t("מעגל המשפחה", "Family circle"), if (Circle.joined(this)) Circle.name(this) else L.t("פתחו מעגל", "Start a circle")) { CircleActivity.open(this) })
        content.addView(card)
    }

    private fun saveParent() {
        familyField?.let { Profile.setFamilyName(this, it.text.toString()) }
        val f = parentField ?: return
        Profile.setParent(this, f.text.toString(), role)
    }

    private fun profileSection() {
        content.addView(ui.section(L.t("הפרופיל שלך", "Your profile")))
        content.addView(ui.hint(L.t("עוזר ל-AI לכתוב עליכם נכון, ולזהות אבני דרך שמתאימות לגיל.", "Helps the AI write about you accurately and spot age-appropriate milestones.")))
        val card = ui.card()
        card.addView(ui.text(L.t("שם המשפחה", "Family name"), 14f, R.color.ink_soft))
        familyField = ui.field(L.t("שם המשפחה שלכם", "Your family name"), Profile.familyName(this), top = 4).also { card.addView(it) }
        card.addView(ui.text(L.t("השם שלך", "Your name"), 14f, R.color.ink_soft, top = 10))
        parentField = ui.field(L.t("איך לקרוא לך?", "What should we call you?"), Profile.parentName(this), top = 4).also { card.addView(it) }
        role = Profile.role(this)
        val roles = listOf("אני", "אבא", "אמא", "סבא", "סבתא", "אחר")
        card.addView(ui.chips(roles.map { Profile.relationLabel(it) }, roles.indexOf(role), top = 10) { i ->
            role = roles[i]
            saveParent()
        })
        card.addView(ui.divider())
        card.addView(ui.text(L.t("בני המשפחה", "Family members"), 14f, R.color.ink_soft, top = 4))
        val members = Profile.members(this).filter { it.id != "self" }
        if (members.isEmpty()) card.addView(ui.hint(L.t("אפשר להוסיף בן או בת זוג, ילדים, וגם חיות מחמד.", "Add your partner, kids and pets.")))
        members.forEach { m ->
            val sub = listOf(Profile.relationLabel(m.relation), Profile.ageText(m.birth)).filter { it.isNotEmpty() }.joinToString(" · ")
            card.addView(ui.row(m.name, sub.ifEmpty { L.t("ערוך", "Edit") }) { saveParent(); editMember(m.id) })
        }
        card.addView(ui.button(L.t("הוסיפו בן משפחה או חיית מחמד", "Add a family member or pet")) { saveParent(); editMember(null) })
        card.addView(ui.hint(L.t("פרטי המשפחה נשמרים כאן. כדי לזהות מי מופיע בזיכרון, תנו שם לפנים במסך אנשים.", "Family details are saved here. To recognize who's in a memory, name faces in People."), top = 8))
        content.addView(card)
    }

    private fun editMember(id: String?, kid: Boolean = false) {
        val members = Profile.members(this).toMutableList()
        val old = members.firstOrNull { it.id == id }
        val memberId = old?.id ?: UUID.randomUUID().toString()
        var birth = old?.birth ?: ""
        var photo = old?.photo ?: ""
        // "Add a child" starts as a child (a new member left as "family member" wasn't a child anywhere: not in
        // Pitzki, the books or the timeline).
        var relation = old?.relation ?: Profile.newMemberRelation(kid)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(20), ui.dp(8), ui.dp(20), 0)
        }
        val name = ui.field(L.t("שם", "Name"), old?.name ?: "")
        box.addView(name)
        val relations = listOf("בן זוג", "בת זוג", "ילד", "ילדה", "אבא", "אמא", "סבא", "סבתא", "אח", "אחות", "בן משפחה", "בת משפחה", "כלב", "חתול", "חיית מחמד")
        box.addView(ui.chips(relations.map { Profile.relationLabel(it) }, relations.indexOf(relation), top = 8) { relation = relations[it] })
        // The profile photo itself, shown (round), so it's clear which photo is set.
        val avatar = android.widget.ImageView(this).apply {
            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(0xFFEDE7DF.toInt()) }
            clipToOutline = true
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            visibility = if (photo.isEmpty()) View.GONE else View.VISIBLE
            if (photo.isNotEmpty()) setImageBitmap(MediaImages.decode(this@SettingsActivity, photo, 400))
        }
        box.addView(avatar, 0, LinearLayout.LayoutParams(ui.dp(104), ui.dp(104)).apply { gravity = android.view.Gravity.CENTER_HORIZONTAL; bottomMargin = ui.dp(10) })
        val photoButton = ui.button(if (photo.isEmpty()) L.t("הוסיפו תמונה", "Add photo") else L.t("החליפו תמונה", "Change photo")) {}
        photoButton.setOnClickListener {
            onPhotoPicked = { path ->
                photo = path
                photoButton.text = L.t("התמונה נבחרה ✓", "Photo selected ✓")
                avatar.setImageBitmap(MediaImages.decode(this@SettingsActivity, path, 400))
                avatar.visibility = View.VISIBLE
            }
            pickPhoto.launch("image/*")
        }
        box.addView(photoButton)
        val birthBtn = ui.button("") {}
        fun showBirth() {
            birthBtn.text = if (birth.isEmpty()) L.t("בחר תאריך לידה", "Choose birthday") else L.t("נולד/ה: ${birth.split("-").reversed().joinToString(".")}  (${Profile.ageText(birth)})", "Born ${birth.split("-").reversed().joinToString(".")}  (${Profile.ageText(birth)})")
        }
        showBirth()
        birthBtn.setOnClickListener {
            val c = Calendar.getInstance()
            val p = birth.split("-").mapNotNull { it.toIntOrNull() }
            if (p.size == 3) c.set(p[0], p[1] - 1, p[2])
            DatePickerDialog(this, { _, yy, mm, dd ->
                birth = String.format(Locale.US, "%04d-%02d-%02d", yy, mm + 1, dd)
                showBirth()
            }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).apply {
                datePicker.maxDate = System.currentTimeMillis()
            }.show()
        }
        box.addView(birthBtn)
        val b = AlertDialog.Builder(this)
            .setTitle(when {
                old != null -> L.t("עריכת ${old.name}", "Edit ${old.name}")
                kid -> L.t("ילד או ילדה חדשים", "A new child")
                else -> L.t("בן משפחה או חיית מחמד חדשים", "New family member or pet")
            })
            .setView(android.widget.ScrollView(this).apply { addView(box) })
            // Save is handled below: a missing or taken name keeps the window open (nothing typed is lost).
            .setPositiveButton(L.t("שמירה", "Save"), null)
            .setNegativeButton(L.t("ביטול", "Cancel"), null)
        if (old != null) b.setNeutralButton(L.t("הסרה", "Remove"), null)
        val dialog = b.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val n = name.text.toString().trim()
            if (n.isEmpty()) {
                name.error = L.t("צריך לכתוב שם", "Please write a name")
                name.requestFocus()
                return@setOnClickListener
            }
            // Moments find people by name: two people with the same name would get mixed up everywhere.
            Profile.nameTaken(this, n, memberId)?.let { other ->
                name.error = L.t(
                    "כבר יש במשפחה \"${other.name}\". הוסיפו משהו שמבדיל, למשל \"$n סבא\" או \"$n הקטן\".",
                    "There's already \"${other.name}\" in the family. Add something that tells them apart, like \"Grandpa $n\".",
                )
                name.requestFocus()
                return@setOnClickListener
            }
            val updated = FamilyMember(memberId, n, relation, birth, photo)
            val current = Profile.members(this).filterNot { it.id == memberId }
            Profile.saveMembers(this, current + updated)
            if (updated.isPet || old?.isPet == true) Pets.invalidate(this)
            dialog.dismiss()
            render()
        }
        if (old != null) dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            // Asked first: a mistaken tap would take a child out of Pitzki and the books.
            AlertDialog.Builder(this)
                .setTitle(L.t("להסיר את ${old.name}?", "Remove ${old.name}?"))
                .setMessage(L.t("${old.name} יוסר מפרופיל המשפחה. הרגעים עצמם לא נמחקים.", "${old.name} will be removed from the family profile. The moments themselves aren't deleted."))
                .setPositiveButton(L.t("להסיר", "Remove")) { _, _ ->
                    Profile.saveMembers(this, Profile.members(this).filterNot { it.id == memberId })
                    if (old.isPet) Pets.invalidate(this)
                    dialog.dismiss()
                    render()
                }
                .setNegativeButton(L.t("ביטול", "Cancel"), null)
                .show()
        }
    }

    // ---------- what saves a moment ----------

    private fun triggersSection() {
        content.addView(ui.section(L.t("מה שומר רגע", "What saves a moment")))
        val card = ui.card()

        card.addView(ui.switch(L.t("צחוק", "Laughter"), Status.laughOn, top = 0) { on ->
            Status.laughOn = on
            Settings.putBool(this, "laugh_on", on)
        })
        card.addView(ui.switch(L.t("גם צעקות שמחה (\"יש!\", תשואות)", "Also happy shouts (\"yay!\", cheering)"), Status.shoutOn) { on ->
            Status.shoutOn = on
            Settings.putBool(this, "shout_on", on)
        })
        card.addView(ui.hint(L.t("כבוי כברירת מחדל: קולות חזקים של ילדים הם לא תמיד רגע שמח.", "Off by default: loud kids' voices aren't always a happy moment."), top = 0))
        val label = ui.hint("")
        val bar = SeekBar(this).apply {
            max = 55
            progress = ((0.6f - Status.threshold) * 100).roundToInt().coerceIn(0, 55)
        }
        fun showLabel() {
            val p = bar.progress
            val word = when {
                p >= 48 -> L.t("גבוהה מאוד", "very high")
                p >= 38 -> L.t("גבוהה", "high")
                p >= 22 -> L.t("בינונית", "medium")
                else -> L.t("נמוכה", "low")
            }
            label.text = L.t("רגישות: $word (שומר כשהצחוק מעל ${(Status.threshold * 100).roundToInt()}%)", "Sensitivity: $word (saves when laughter is above ${(Status.threshold * 100).roundToInt()}%)")
        }
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                Status.threshold = 0.6f - p / 100f
                Settings.putFloat(this@SettingsActivity, "threshold3", Status.threshold)
                showLabel()
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        showLabel()
        card.addView(label)
        card.addView(bar)
        card.addView(ui.hint(L.t("נשמרים רגעים מיותרים? הזז שמאלה. מפספס צחוקים? הזז ימינה.", "Saving too much? Slide left. Missing laughs? Slide right.")))
        card.addView(ui.text(L.t("זמן מינימלי בין רגעים אוטומטיים", "Minimum time between automatic moments"), 15f, top = 14))
        val gaps = listOf(1, 3, 5, 10)
        card.addView(ui.chips(gaps.map { L.t("$it דק׳", "$it min") }, gaps.indexOf(Status.gapMin).coerceAtLeast(0), top = 6) { i ->
            Status.gapMin = gaps[i]
            Settings.putInt(this, "gap_min", gaps[i])
        })
        card.addView(ui.hint(L.t("בכל פרק זמן כזה נשמר הצחוק הכי חזק: צחוק גדול יותר מחליף את הקודם (הקודם עובר לסל המחזור). שמירה בכפתור או במילת קוד עובדת תמיד.", "In each window the biggest laugh is kept: a bigger laugh replaces the earlier one (which goes to the trash). Saving with the button or a code word always works."), top = 4))

        card.addView(ui.divider())
        card.addView(ui.switch(L.t("מילים ומשפטים (״אבא תראה״, ״וואו״...)", "Words and phrases (\"Daddy look\", \"wow\"…)"), Status.wordsOn) { on ->
            Status.wordsOn = on
            Settings.putBool(this, "words_on", on)
            restartNote()
        })
        val phrases = ui.field(L.t("מילים מופרדות בפסיקים", "Words, separated by commas"), Status.phrases.joinToString(", "), multiline = true)
        phrasesField = phrases
        card.addView(phrases)
        card.addView(ui.hint(L.t("מילה שמתחילה ב״שמור״ (כמו ״שמור רגע״) תמיד שומרת, גם מיד אחרי רגע אחר.", "A phrase starting with \"שמור\" (like \"שמור רגע\") always saves, even right after another moment.")))
        val btns = ui.horizontal()
        btns.addView(ui.action(L.t("שמור מילים", "Save words")) {
            val list = Phrases.parse(phrases.text.toString())
            Status.phrases = list
            Settings.putString(this, "phrases", list.joinToString(", "))
            phrases.setText(list.joinToString(", "))
            toast(L.t("נשמרו ${list.size} מילים", "${list.size} words saved"))
        })
        btns.addView(ui.action(L.t("החזר לברירת מחדל", "Reset to default"), R.color.ink_soft) {
            Status.phrases = Phrases.DEFAULT
            Settings.putString(this, "phrases", Phrases.DEFAULT.joinToString(", "))
            phrases.setText(Phrases.DEFAULT.joinToString(", "))
        })
        card.addView(btns)

        card.addView(ui.divider())
        card.addView(ui.switch(L.t("חיוך גדול או מישהו שרץ אליכם", "A big smile or someone running to you"), Status.facesOn) { on ->
            Status.facesOn = on
            Settings.putBool(this, "faces_on", on)
            restartNote()
        })
        card.addView(ui.hint(L.t("לכל היותר רגע אחד כזה בדקה, כדי לא למלא את הגלריה.", "At most one of these a minute, so your gallery doesn't fill up.")))

        card.addView(ui.divider())
        card.addView(ui.switch(L.t("🤗 חיבוק", "🤗 Hugs"), Status.hugOn) { on ->
            Status.hugOn = on
            Settings.putBool(this, "hug_on", on)
            restartNote()
        })
        card.addView(ui.hint(L.t("כשמישהו מתקרב ומחבק אותך (המצלמה על החזה מתכסה לכ-3 שניות), או כששניים מתחבקים מולך יותר מ-3 שניות. זיהוי בקירוב: לפעמים יפספס, ולפעמים ישמור כשמשהו מכסה את המצלמה.", "When someone comes in for a hug (the chest camera is covered for about 3 seconds), or two people hug in front of you for over 3 seconds. It's approximate: it may miss some, or save when something covers the camera.")))

        card.addView(ui.divider())
        card.addView(ui.switch(L.t("❤️ ״אני אוהב אותך״", "❤️ \"I love you\""), Status.loveOn) { on ->
            Status.loveOn = on
            Settings.putBool(this, "love_on", on)
            restartNote()
        })
        card.addView(ui.hint(L.t("״אוהב אותך״, ״אוהבת אותך״, ״אוהבים אותך״ תמיד נשמרים, גם מיד אחרי רגע אחר.", "\"I love you\" (said in Hebrew) is always saved, even right after another moment.")))

        card.addView(ui.divider())
        card.addView(ui.switch(L.t("🎵 שירה", "🎵 Singing"), Status.singOn) { on ->
            Status.singOn = on
            Settings.putBool(this, "sing_on", on)
        })
        card.addView(ui.hint(L.t("מנסה לזהות שירה בזמן הצילום. לפעמים גם מוזיקה ברקע עשויה לשמור רגע.", "Listens for singing while recording. Background music may sometimes save a moment too.")))

        card.addView(ui.divider())
        card.addView(ui.text(L.t("שמירה ידנית", "Save manually"), 15f, top = 4))
        card.addView(ui.hint(L.t("כפתור ♥ במסך היום, כפתור בהתראה, או אריח \"שמור רגע\" בתפריט המהיר של הטלפון (משוך מלמעלה ← עריכה).", "The ♥ button on Today, the notification button, or the \"Save moment\" tile in Quick Settings (swipe down → Edit).")))
        content.addView(card)
    }

    // ---------- reminders ----------

    /** "תזכורות": on-this-day and the Friday digest for the grandparents. */
    private fun remindersSection() {
        content.addView(ui.section(L.t("תזכורות", "Reminders")))
        val card = ui.card()
        card.addView(ui.switch(L.t("\"היום לפני שנה\": התראה כשיש רגע מהיום הזה", "\"On this day\": a notification when there's a moment from this day"), Reminders.onThisDayOn(this), top = 0) { on ->
            Reminders.setOnThisDay(this, on)
        })
        card.addView(ui.switch(L.t("סיכום לסבא וסבתא בכל יום שישי", "Grandparents' digest every Friday"), Reminders.digestOn(this)) { on ->
            Reminders.setDigest(this, on)
        })
        card.addView(ui.row(L.t("הסיכום של השבוע", "This week's digest"), "", top = 2) { DigestActivity.open(this) })
        content.addView(card)
    }

    // ---------- camera & clips ----------

    private fun cameraSection() {
        content.addView(ui.section(L.t("מצלמה וקליפים", "Camera and clips")))
        val card = ui.card()
        card.addView(ui.text(L.t("עדשה בהתחלה", "Starting lens"), 14f, R.color.ink_soft, top = 0))
        val zooms = listOf(0.6f, 1f, 2f, 3f, 5f)
        val sel = zooms.indexOfFirst { kotlin.math.abs(it - Status.zoom) < 0.01f }.coerceAtLeast(0)
        card.addView(ui.chips(listOf(L.t("0.6x רחבה", "0.6x wide"), "1x", "2x", "3x", "5x"), sel) { i ->
            Status.zoom = zooms[i]
            Settings.putFloat(this, "zoom", zooms[i])
            if (Status.running) startService(Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_ZOOM))
        })
        card.addView(ui.hint(L.t("0.6x תופס הכי הרבה כשהטלפון על החזה.", "0.6x captures the most with the phone on your chest.")))

        val p = Settings.prefs(this)
        card.addView(ui.text(L.t("כמה לשמור לפני הרגע", "How much to keep before"), 14f, R.color.ink_soft, top = 14))
        val before = Settings.BEFORE_OPTIONS
        card.addView(ui.chips(before.map { L.t("$it שנ׳", "${it}s") }, before.indexOf(p.getInt("before_s", 15)).coerceAtLeast(0)) { i ->
            Settings.putInt(this, "before_s", before[i])
            Settings.load(this)
        })
        card.addView(ui.text(L.t("כמה לשמור אחרי", "How much to keep after"), 14f, R.color.ink_soft, top = 12))
        val after = Settings.AFTER_OPTIONS
        card.addView(ui.chips(after.map { L.t("$it שנ׳", "${it}s") }, after.indexOf(p.getInt("after_s", 15)).coerceAtLeast(0)) { i ->
            Settings.putInt(this, "after_s", after[i])
            Settings.load(this)
        })
        card.addView(ui.hint(L.t("אפשר תמיד לקצר רגע אחר כך עם \"קצר\" במסך הרגע.", "You can always trim a moment later with \"Trim\" on the moment screen.")))

        card.addView(ui.switch(L.t("איכות גבוהה (1080p): חד יותר, אבל הטלפון מתחמם יותר", "High quality (1080p): sharper, but the phone gets warmer"), Settings.prefs(this).getBoolean("hq_video", false), top = 10) { on ->
            Settings.putBool(this, "hq_video", on)
            restartNote()
        })
        card.addView(ui.row(L.t("מצלמה חיצונית (GoPro, משקפיים)", "External camera (GoPro, glasses)"), "", top = 6) { CameraImportActivity.open(this) })
        card.addView(ui.row(L.t("מעבדת זיהוי (בדיקה על סרטונים)", "Detection lab (test on videos)"), "", top = 6) { DetectionLabActivity.open(this) })
        card.addView(ui.divider())
        card.addView(ui.switch(L.t("תמונות חיות בזיכרונות (3 שניות מהרגע עצמו)", "Live photos in memories (3 seconds from the moment)"), Motion.enabled(this)) { on ->
            Settings.putBool(this, "motion_on", on)
        })
        card.addView(ui.switch(L.t("רטט קצר כשנשמר רגע", "Short vibration when a moment is saved"), Status.buzzOn) { on ->
            Status.buzzOn = on
            Settings.putBool(this, "buzz_on", on)
        })
        content.addView(card)
    }

    // ---------- people ----------

    private fun peopleSection() {
        content.addView(ui.section(L.t("אנשים וזיהוי פנים", "People and face recognition")))
        val card = ui.card()
        card.addView(ui.switch(L.t("זיהוי מי מופיע בכל רגע", "Recognize who's in each moment"), PeopleStore.peopleEnabled(this), top = 0) { on ->
            Settings.putBool(this, "people_on", on)
        })
        card.addView(ui.hint(L.t("דוגמאות זיהוי הפנים נשמרות בטלפון. אם חיברתם הבנת רגעים, תמונות הזיכרון עשויות להישלח לשירות המחובר.", "Face samples stay on this phone. If you've connected moment understanding, memory photos may be sent to that service.")))
        val people = PeopleStore.people(this)
        val unknown = try { PeopleStore.unknownGroups(this).size } catch (_: Exception) { 0 }
        card.addView(ui.row(L.t("האנשים שלך", "Your people"), if (people.isEmpty()) L.t("עוד אין", "None yet") else L.t("${people.size} אנשים", "${people.size} people")) { PeopleActivity.open(this) })
        if (people.isNotEmpty()) card.addView(ui.hint(people.joinToString(" · ") { "${it.name} (${PeopleStore.countFor(this, it.name)})" }, top = 0))
        if (unknown > 0) card.addView(ui.row(L.t("פנים שמחכות לשם", "Faces waiting for a name"), "$unknown") { PeopleActivity.open(this) })
        val pending = MemoryStore.kept(this).count { !it.facesScanned }
        if (pending > 0) card.addView(ui.button(L.t("חפש פנים ב-$pending רגעים שלא נבדקו", "Find faces in $pending unchecked moments")) {
            toast(L.t("מחפש פנים ברקע...", "Finding faces in the background…"))
            worker.execute {
                var n = 0
                while (true) {
                    val k = PeopleStore.scanPending(applicationContext, 6)
                    n += k
                    if (k == 0) break
                }
                runOnUiThread { if (!isFinishing) { toast(L.t("נבדקו $n רגעים", "$n moments checked")); render() } }
            }
        })
        card.addView(ui.action(L.t("התחל את זיהוי הפנים מאפס", "Reset face recognition"), R.color.danger) {
            AlertDialog.Builder(this)
                .setTitle(L.t("למחוק את כל זיהוי הפנים?", "Delete all face recognition?"))
                .setMessage(L.t("כל השמות והפנים יימחקו, והרגעים ייסרקו מחדש. הקליפים עצמם לא נמחקים.", "All names and faces will be deleted and your moments scanned again. Your clips won't be deleted."))
                .setPositiveButton(L.t("מחק", "Delete")) { _, _ ->
                    worker.execute {
                        PeopleStore.clearAll(applicationContext)
                        runOnUiThread { if (!isFinishing) render() }
                    }
                }
                .setNegativeButton(L.t("ביטול", "Cancel"), null)
                .show()
        })
        content.addView(card)
    }

    // ---------- AI ----------

    private fun aiSection() {
        // Global learning of the recorder: only the kind of detection and the vote leave the phone.
        content.addView(ui.section(L.t("דיוק הזיהוי", "Detection accuracy")))
        val acc = ui.card()
        acc.addView(ui.switch(L.t("לעזור לדיוק הזיהוי", "Help improve detection"), GlobalVotes.helping(this), top = 0) { on -> GlobalVotes.setHelping(this, on) })
        acc.addView(ui.hint(L.t(
            "כשאתם מאשרים או דוחים רגע בתור, נשלחים ל-Moments רק סוג הזיהוי (למשל \"צחוק\"), טווח הציון שלו וההצבעה. לא וידאו, לא תמונה, לא קול, לא שמות ולא מקום. הסיכום של כולם עוזר לכל המשפחות. כיבוי עוצר את השליחה; הלמידה בטלפון שלכם ממשיכה.",
            "When you keep or drop a moment in the queue, only the kind of detection (e.g. \"laughter\"), its score range and your vote are sent to Moments. No video, picture, sound, names or place. Everyone's summary helps every family. Turning this off stops sending; learning on your phone continues.",
        ), top = 4))
        content.addView(acc)

        // Moment understanding through Moments' server: no keys, one consent screen, a monthly family allowance.
        content.addView(ui.section(L.t("הבנת רגעים (AI)", "Moment understanding (AI)")))
        val gate = ui.card()
        gate.addView(ui.switch(L.t("לתאר רגעים ולענות על שאלות", "Describe moments and answer questions"), AiGate.consented(this), top = 0) { on ->
            if (on) AiGate.askConsent(this) { render() } else { AiGate.setConsent(this, false); render() }
            if (on && !AiGate.consented(this)) render()
        })
        gate.addView(ui.hint(L.t("דרך השרת של Moments. בלי מפתחות. נשלחות רק תמונות בודדות, לא סרטונים ולא קול.", "Through Moments' server. No keys. Only a few pictures are sent, never videos or sound."), top = 4))
        gate.addView(ui.action(L.t("מה בדיוק נשלח ›", "Exactly what is sent ›")) {
            AlertDialog.Builder(this).setTitle(L.t("מה בדיוק נשלח", "Exactly what is sent")).setMessage(AiGate.consentText()).setPositiveButton(L.t("סגור", "Close"), null).show()
        }.apply { setPadding(0, ui.dp(10), 0, 0) })
        if (AiGate.ready(this)) {
            val left = ui.hint(L.t("בודקים את המכסה...", "Checking the allowance…"), top = 8)
            gate.addView(left)
            Thread {
                val text = try { AiGate.usage(applicationContext).let { L.t("נשארו החודש ${it.left} מתוך ${it.limit} בקשות למשפחה", "${it.left} of ${it.limit} family requests left this month") } } catch (e: Exception) { e.message ?: "" }
                runOnUiThread { left.text = text }
            }.start()
        }
        content.addView(gate)
        // Personal keys are a developer tool now; families never see them.
        if (!Account.isAdmin(this)) return
        content.addView(ui.section(L.t("למפתחים: מפתחות AI", "For developers: AI keys")))
        val card = ui.card()
        val claude = ClaudeClient.hasKey(this)
        card.addView(ui.row(L.t("מפתח Claude (תיאורים, סיפור היום, שאלות)", "Claude key (descriptions, daily story, questions)"), if (claude) L.t("מחובר ✓", "Connected ✓") else L.t("לא מוגדר", "Not set"), top = 0) {
            askKey(
                L.t("מפתח Claude", "Claude key"), "sk-ant-...", ClaudeClient.apiKey(this),
                L.t("המפתח נשמר רק בטלפון. בלעדיו הרגעים נשמרים, אבל בלי תיאור, סיפור וחיפוש.", "The key is stored only on this phone. Without it, moments are still saved, but without descriptions, stories or search."),
                "https://console.anthropic.com/settings/keys",
            ) { ClaudeClient.setApiKey(this, it) }
        })
        card.addView(ui.row(L.t("מפתח Gemini (ציורי קומיקס)", "Gemini key (comic drawings)"), if (GeminiClient.hasKey(this)) L.t("מחובר ✓", "Connected ✓") else L.t("לא חובה", "Optional")) {
            askKey(
                L.t("מפתח Gemini", "Gemini key"), "AIza...", GeminiClient.apiKey(this),
                L.t("עם המפתח כל פאנל בקומיקס מצויר מחדש. התמונות מהרגע נשלחות לגוגל לציור.", "With a key, every comic panel is redrawn. Photos from the moment are sent to Google for drawing."),
                "https://aistudio.google.com/apikey",
            ) { GeminiClient.setApiKey(this, it) }
        })
        if (claude) {
            val all = MemoryStore.kept(this)
            card.addView(ui.action(L.t("תאר מחדש את כל ${all.size} הרגעים", "Re-describe all ${all.size} moments")) {
                AlertDialog.Builder(this)
                    .setTitle(L.t("לתאר מחדש את כל הרגעים?", "Re-describe all moments?"))
                    .setMessage(L.t("שימושי אחרי שעדכנתם פרופיל, שמות או מה ש-Moments למד. הפעולה משתמשת בשירות המחובר שלכם ועשויה להיות בתשלום. תיקוני כותרת ותיאור שביצעתם ידנית יוחלפו.", "Useful after updating your profile, names or what Moments learned. This uses your connected service and may cost money. Titles and descriptions you edited by hand will be replaced."))
                    .setPositiveButton(L.t("כן", "Yes")) { _, _ ->
                        for (m in all) MemoryStore.update(this, m.copy(analyzed = false))
                        toast(L.t("מתאר מחדש ברקע...", "Re-describing in the background…"))
                        worker.execute {
                            val n = MemoryStore.analyzePending(applicationContext)
                            runOnUiThread { if (!isFinishing) toast(L.t("תוארו $n רגעים", "$n moments described")) }
                        }
                    }
                    .setNegativeButton(L.t("ביטול", "Cancel"), null)
                    .show()
            })
        }
        content.addView(card)
    }

    private fun askKey(title: String, hint: String, value: String, message: String, url: String, save: (String) -> Unit) {
        val field = ui.field(hint, value, password = true)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(20), ui.dp(8), ui.dp(20), 0)
            addView(field)
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setView(box)
            .setPositiveButton(L.t("שמור", "Save")) { _, _ -> save(field.text.toString().trim()); render() }
            .setNeutralButton(L.t("איפה משיגים?", "Where do I get one?")) { _, _ ->
                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) {}
            }
            .setNegativeButton(L.t("ביטול", "Cancel"), null)
            .show()
    }

    // ---------- learning ----------

    private fun learningSection() {
        content.addView(ui.section(L.t("מה Moments למד", "What Moments learned")))
        val card = ui.card()
        val items = Learning.items(this)
        val liked = Learning.liked(this).size
        val deleted = Learning.rejected(this).size
        card.addView(ui.row(L.t("תובנות על מה שחשוב לך", "Insights on what matters to you"), if (items.isEmpty()) L.t("עוד אין", "None yet") else "${items.size}", top = 0) { LearnedActivity.open(this) })
        card.addView(ui.hint(L.t("נלמד מ-$liked רגעים ששמרת/אהבת ו-$deleted שמחקת. אפשר לראות על מה כל תובנה מבוססת ולתקן.", "Learned from $liked moments you saved or loved and $deleted you deleted. See what each insight is based on, and fix it."), top = 0))
        card.addView(ui.action(L.t("שכח את כל מה שנלמד", "Forget everything learned"), R.color.danger) {
            AlertDialog.Builder(this)
                .setTitle(L.t("לשכוח את כל מה ש-Moments למד?", "Forget everything Moments learned?"))
                .setMessage(L.t("התובנות, הכללים שכתבת והדוגמאות של רגעים שמחקת יימחקו. הרגעים עצמם נשארים.", "Insights, rules you wrote and examples of deleted moments will be erased. Your moments stay."))
                .setPositiveButton(L.t("שכח", "Forget")) { _, _ ->
                    worker.execute {
                        Learning.resetAll(applicationContext)
                        runOnUiThread { if (!isFinishing) render() }
                    }
                }
                .setNegativeButton(L.t("ביטול", "Cancel"), null)
                .show()
        })
        content.addView(card)
    }

    // ---------- privacy ----------

    private fun privacySection() {
        content.addView(ui.section(L.t("פרטיות", "Privacy")))
        val card = ui.card()
        val clips = MemoryStore.kept(this)
        card.addView(ui.text(L.t("הזיכרונות נשמרים בטלפון. שיתוף עם המשפחה מעלה רק את התוכן שבחרתם למעגל שאליו הצטרפתם.", "Memories are saved on your phone. Sharing with family uploads only what you choose, to the circle you joined."), 14f, top = 0))
        // Private by default: the moments stay inside Moments (not in the phone's gallery, so not backed up to Google Photos).
        card.addView(ui.switch(L.t("לשמור עותק גם בגלריה של הטלפון", "Also keep a copy in the phone's gallery"), ClipSaver.toGallery(this), top = 10) { on ->
            ClipSaver.setToGallery(this, on)
        })
        card.addView(ui.hint(L.t("כבוי (מומלץ): הרגעים החדשים נשמרים רק בתוך Moments, אפליקציות אחרות לא רואות אותם, והם לא עולים לגיבוי של Google Photos. רגעים קודמים נשארים איפה שהם.",
            "Off (recommended): new moments are kept only inside Moments; other apps don't see them, and they aren't backed up to Google Photos. Earlier moments stay where they are."), top = 2))
        card.addView(ui.text(L.t("כאשר מחברים שירות להבנת רגעים, תמונות מתוך הזיכרון ופרטי ההקשר נשלחים אליו. התמונות עשויות לכלול פנים; זיהוי הפנים המקומי והדוגמאות שלו נשארים בטלפון.", "When you connect a moment-understanding service, photos from the memory and context details are sent to it. Photos may include faces; on-device face recognition and its samples stay on your phone."), 14f, top = 8))
        card.addView(ui.text(L.t("ציור קומיקס שולח תמונות לשירות הציור. זיהוי מילים נעשה באמצעות שירות הדיבור שבטלפון, בהתאם להגדרות השירות.", "Comic drawing sends photos to the drawing service. Word detection uses your phone's speech service, according to its settings."), 14f, top = 8))
        card.addView(ui.hint(L.t("${clips.size} זיכרונות שמורים בטלפון · השיתוף בשליטתכם", "${clips.size} memories saved on this phone · You control sharing"), top = 10))
        val version = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Exception) { "" }
        card.addView(ui.hint(L.t("רגעים $version", "Moments $version"), top = 2))
        content.addView(card)
    }
}

/** Removes every face, name and print, so moments are scanned again from scratch. */
fun PeopleStore.clearAll(ctx: Context) {
    synchronized(this) {
        File(ctx.filesDir, "people.json").delete()
        File(ctx.filesDir, "faces.json").delete()
        File(ctx.filesDir, "people.json.unreadable").delete()
        File(ctx.filesDir, "faces.json.unreadable").delete()
        File(ctx.filesDir, "faces").listFiles()?.forEach { it.delete() }
    }
    // Family profile photos are learned again (they were marked "done" forever), and failed scans start over.
    ctx.getSharedPreferences("regaim", Context.MODE_PRIVATE).edit().remove("enrolled_family").apply()
    ctx.getSharedPreferences("face_scan_tries", Context.MODE_PRIVATE).edit().clear().apply()
    val ids = MemoryStore.all(ctx).filter { it.people.isNotEmpty() || it.facesScanned }.map { it.id }.toSet()
    MemoryStore.updateMany(ctx, ids) { it.copy(people = "", facesScanned = false) } // one write, not one per moment
}

/** The "פרופיל" tab: the same settings, as a tab with the family header. */
class ProfileActivity : SettingsActivity()

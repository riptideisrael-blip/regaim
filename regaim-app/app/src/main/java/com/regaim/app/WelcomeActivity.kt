package com.regaim.app

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import java.util.concurrent.Executors

/**
 * The first screen: welcome, then continue with Google, sign up with email, or sign in.
 * Also receives moments://auth when Google sign-in comes back from the browser.
 */
class WelcomeActivity : AppCompatActivity() {

    companion object {
        fun open(ctx: Context) = ctx.startActivity(Intent(ctx, WelcomeActivity::class.java))
    }

    private enum class Mode { START, SIGN_UP, SIGN_IN, CHECK_MAIL }

    private lateinit var ui: Ui
    private lateinit var root: LinearLayout
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var mode = Mode.START
    /** The email the account was just created with (waiting for its confirmation link); filled in on "sign in". */
    @Volatile private var sentTo = ""
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(ui.dp(28), ui.dp(48), ui.dp(28), ui.dp(32))
        }
        setContentView(ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(ContextCompat.getColor(this@WelcomeActivity, R.color.bg))
            addView(root)
        })
        render()
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    /** Back from the browser after "continue with Google". */
    private fun handle(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme != "moments" || uri.host != "auth") return
        run(L.t("מחברים אותך...", "Signing you in...")) {
            if (!Account.handleRedirect(applicationContext, uri)) throw IllegalStateException(L.t("ההתחברות לא הושלמה", "Sign-in wasn't completed"))
        }
    }

    private fun run(text: String, work: () -> Unit) {
        if (busy) return
        busy = true
        render(text)
        worker.execute {
            val r = try { work(); null } catch (e: Exception) { e.message ?: L.t("משהו השתבש", "Something went wrong") }
            runOnUiThread {
                busy = false
                if (isFinishing) return@runOnUiThread
                if (r == null && Account.signedIn(this)) done() else {
                    if (r != null) Toast.makeText(this, r, Toast.LENGTH_LONG).show()
                    render()
                }
            }
        }
    }

    private fun done() {
        val first = Profile.isEmpty(this)
        Toast.makeText(this, L.t("שלום ${Account.name(this).split(" ").first().ifEmpty { "" }} ❤️", "Hi ${Account.name(this).split(" ").first().ifEmpty { "" }} ❤️").replace("  ", " "), Toast.LENGTH_SHORT).show()
        startActivity(Intent(this, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        if (first) startActivity(Intent(this, ProfileActivity::class.java))
        finish()
    }

    // ---------- screens ----------

    private fun render(progress: String = "") {
        root.removeAllViews()
        if (mode == Mode.START && progress.isEmpty()) {
            root.setPadding(ui.dp(24), ui.dp(24), ui.dp(24), ui.dp(28))
            landing()
            start()
            return
        }
        root.setPadding(ui.dp(28), ui.dp(48), ui.dp(28), ui.dp(32))
        root.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_logo_mark)
            layoutParams = LinearLayout.LayoutParams(ui.dp(84), ui.dp(84)).apply { topMargin = ui.dp(24) }
        })
        root.addView(TextView(this).apply {
            text = "Moments"
            textSize = 34f
            gravity = Gravity.CENTER
            typeface = DS.logo
            setTextColor(ContextCompat.getColor(this@WelcomeActivity, R.color.ink))
            setPadding(0, ui.dp(14), 0, 0)
        })
        root.addView(TextView(this).apply {
            text = L.t("הזיכרונות של המשפחה, נשמרים לבד", "Your family's memories, saved on their own")
            textSize = 17f
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(this@WelcomeActivity, R.color.ink_soft))
            setPadding(0, ui.dp(6), 0, ui.dp(30))
        })
        if (progress.isNotEmpty()) {
            root.addView(TextView(this).apply {
                text = progress
                textSize = 16f
                gravity = Gravity.CENTER
                setTextColor(ContextCompat.getColor(this@WelcomeActivity, R.color.accent))
            }.also { MemoryViews(this).pulse(it) })
            return
        }
        when (mode) {
            Mode.START -> start()
            Mode.SIGN_UP -> form(signUp = true)
            Mode.SIGN_IN -> form(signUp = false)
            Mode.CHECK_MAIL -> checkMail()
        }
    }

    // ---------- landing ----------

    private val tileColors = listOf(
        0xFFFFD9B8.toInt() to 0xFFF6A97A.toInt(), 0xFFCFE3FF.toInt() to 0xFF8FB8F2.toInt(), 0xFFFFE3A8.toInt() to 0xFFF2B01E.toInt(),
        0xFFD6F0E0.toInt() to 0xFF7CCBA0.toInt(), 0xFFF3D4F0.toInt() to 0xFFD596CF.toInt(), 0xFFDAD6FA.toInt() to 0xFF9C93EE.toInt(),
    )
    private val tileEmoji = listOf("👶", "👨‍👩‍👧", "🎂", "🏖️", "👵", "🐶")

    /**
     * A photo tile. If a picture named landing_1 ... landing_6 exists in res/drawable-nodpi it is used;
     * until then, a warm colored tile stands in.
     */
    private fun photoTile(i: Int, heightDp: Int): View {
        val radius = ui.dp(20).toFloat()
        val resId = resources.getIdentifier("landing_${i + 1}", "drawable", packageName)
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(heightDp)).apply { bottomMargin = ui.dp(10) }
        if (resId != 0) {
            return ImageView(this).apply {
                setImageResource(resId)
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = GradientDrawable().apply { cornerRadius = radius; setColor(0xFFF0E6DC.toInt()) }
                clipToOutline = true
                layoutParams = lp
            }
        }
        val (a, b) = tileColors[i % tileColors.size]
        return TextView(this).apply {
            text = tileEmoji[i % tileEmoji.size]
            textSize = 44f
            gravity = Gravity.CENTER
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(a, b)).apply { cornerRadius = radius }
            layoutParams = lp
        }
    }

    private fun landing() {
        val bgColor = ContextCompat.getColor(this, R.color.bg)
        // The picture wall: three columns, the middle one lower.
        val wall = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(330))
            clipChildren = true
        }
        val heights = listOf(listOf(150, 120), listOf(120, 160), listOf(160, 110))
        for (c in 0 until 3) {
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                    marginStart = if (c == 0) 0 else ui.dp(5); marginEnd = if (c == 2) 0 else ui.dp(5)
                }
                setPadding(0, if (c == 1) ui.dp(36) else 0, 0, 0)
            }
            for (r in 0 until 2) col.addView(photoTile(c * 2 + r, heights[c][r]))
            wall.addView(col)
        }
        // The pictures take at most about a quarter of the screen, so the buttons below fit without scrolling.
        val heroH = minOf(ui.dp(330), (resources.displayMetrics.heightPixels * 0.27f).toInt())
        val hero = FrameLayout(this).apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, heroH) }
        hero.addView(wall)
        // Fade the pictures into the page.
        hero.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x00FFFFFF and bgColor, bgColor))
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(120), Gravity.BOTTOM)
        })
        // Language switch: a small pill in the top corner.
        hero.addView(TextView(this).apply {
            text = if (L.isEn) "🌐 עברית" else "🌐 English"
            contentDescription = if (L.isEn) "עברית" else "English"
            textSize = 13f
            gravity = Gravity.CENTER; minHeight = ui.dp(48)
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(this@WelcomeActivity, R.color.ink))
            background = GradientDrawable().apply {
                cornerRadius = ui.dp(16).toFloat()
                setColor(0xE6FFFFFF.toInt())
                setStroke(ui.dp(1), 0xFFF0E6DC.toInt())
            }
            setPadding(ui.dp(12), ui.dp(6), ui.dp(12), ui.dp(6))
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
                topMargin = ui.dp(8); marginEnd = ui.dp(8)
            }
            setOnClickListener {
                L.set(this@WelcomeActivity, if (L.isEn) L.HE else L.EN)
                recreate()
            }
        })
        root.addView(hero)
        MemoryViews(this).reveal(hero)

        root.addView(TextView(this).apply {
            text = "Moments"
            textSize = 36f
            gravity = Gravity.CENTER
            typeface = DS.logo
            setTextColor(ContextCompat.getColor(this@WelcomeActivity, R.color.ink))
            setPadding(0, ui.dp(4), 0, 0)
        })
        root.addView(TextView(this).apply {
            text = L.t("הרגעים הקטנים של המשפחה, נשמרים לבד", "Your family's little moments, saved on their own")
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(this@WelcomeActivity, R.color.ink_soft))
            setPadding(0, ui.dp(4), 0, ui.dp(14))
        })

        val points = listOf(
            Triple("🎥", L.t("תופס את הרגעים הטובים", "Catches the good moments"), L.t("צחוק, חיבוק ושיר נשמרים לבד, בלי שתצטרכו להחזיק מצלמה ביד.", "Laughs, hugs and songs are saved automatically, no camera in hand needed.")),
            Triple("✨", L.t("מבין מה קרה ומספר את זה", "Understands and tells the story"), L.t("כותרת, תיאור וסיפור שבועי עם מוזיקה, כדי לחזור לרגעים גם אחרי שנים.", "A title, a description and a weekly story with music, so you can relive it years from now.")),
            Triple("👨‍👩‍👧", L.t("משפחה אחת, מעגל פרטי", "One family, one private circle"), L.t("משתפים רגעים נבחרים עם סבא וסבתא ועם מי שתזמינו, ורק איתם.", "Share the moments you choose with grandparents and anyone you invite, and only them.")),
        )
        // One compact card: a line per point (the intro already told the longer story), so the buttons stay in view.
        val card = ui.card(top = 0)
        for ((i, p) in points.withIndex()) {
            val (icon, title, body) = p
            val line = ui.horizontal(top = if (i == 0) 0 else 10)
            line.contentDescription = "$title. $body"
            line.addView(TextView(this).apply { text = icon; textSize = 20f; setPadding(0, 0, ui.dp(10), 0); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO })
            line.addView(ui.text(title, 15f, bold = true).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO })
            card.addView(line)
        }
        root.addView(card)
        root.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(1, ui.dp(14)) })
    }

    private fun bigButton(text: String, filled: Boolean, onClick: () -> Unit): MaterialButton = MaterialButton(this).apply {
        this.text = text
        textSize = 16f
        setAllCaps(false)
        cornerRadius = ui.dp(28)
        insetTop = 0
        insetBottom = 0
        if (filled) {
            backgroundTintList = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(this@WelcomeActivity, R.color.ink))
            setTextColor(ContextCompat.getColor(this@WelcomeActivity, R.color.white))
        } else {
            backgroundTintList = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(this@WelcomeActivity, R.color.white))
            setTextColor(ContextCompat.getColor(this@WelcomeActivity, R.color.ink))
            strokeColor = android.content.res.ColorStateList.valueOf(0xFFF0E6DC.toInt())
            strokeWidth = ui.dp(1)
        }
        minHeight = ui.dp(56)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ui.dp(12) }
        setOnClickListener { onClick() }
    }

    private fun start() {
        root.addView(bigButton(L.t("המשך עם Google", "Continue with Google"), filled = false) {
            if (busy) return@bigButton
            busy = true
            worker.execute {
                val enabled = Account.googleEnabled(applicationContext)
                runOnUiThread {
                    busy = false
                    if (isFinishing) return@runOnUiThread
                    when (enabled) {
                        false -> Toast.makeText(this, L.t("ההתחברות עם Google לא זמינה כרגע. אפשר להירשם עם אימייל.", "Google sign-in isn't available right now. You can sign up with email."), Toast.LENGTH_LONG).show()
                        else -> try {
                            // null = could not check; try anyway, the browser shows the real answer.
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Account.googleUrl(this))))
                        } catch (_: Exception) {
                            Toast.makeText(this, L.t("לא נמצא דפדפן בטלפון", "No browser found on this phone"), Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        })
        root.addView(bigButton(L.t("הרשמה עם אימייל", "Sign up with email"), filled = true) { mode = Mode.SIGN_UP; render() })
        root.addView(link(L.t("כבר יש לי חשבון · התחברות", "I already have an account · Sign in"), R.color.accent, 18) { mode = Mode.SIGN_IN; render() })
        root.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(1, 0, 1f) })
        root.addView(link(L.t("להמשיך בלי חשבון", "Continue without an account"), R.color.ink_soft, 28) {
            Account.skip(this)
            startActivity(Intent(this, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            finish()
        })
        root.addView(ui.hint(L.t("הסרטונים נשמרים בטלפון שלכם. החשבון משמש לשיתוף עם המשפחה.", "Videos stay on your phone. Your account is only for sharing with family."), top = 10).apply { gravity = Gravity.CENTER })
    }

    private fun link(text: String, colorRes: Int, top: Int, onClick: () -> Unit) = TextView(this).apply {
        this.text = text
        textSize = 15f
        gravity = Gravity.CENTER
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(ContextCompat.getColor(this@WelcomeActivity, colorRes))
        setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ui.dp(top) }
        setOnClickListener { onClick() }
    }

    private fun input(hint: String, type: Int): EditText = EditText(this).apply {
        this.hint = hint
        inputType = type
        textSize = 16f
        setTextColor(ContextCompat.getColor(this@WelcomeActivity, R.color.ink))
        background = GradientDrawable().apply {
            cornerRadius = ui.dp(16).toFloat()
            setColor(0xFFFFFFFF.toInt())
            setStroke(ui.dp(1), 0xFFF0E6DC.toInt())
        }
        setPadding(ui.dp(16), ui.dp(14), ui.dp(16), ui.dp(14))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ui.dp(10) }
    }

    private fun form(signUp: Boolean) {
        root.addView(TextView(this).apply {
            text = if (signUp) L.t("יוצרים חשבון משפחתי", "Create a family account") else L.t("ברוכים השבים", "Welcome back")
            androidx.core.view.ViewCompat.setAccessibilityHeading(this, true)
            textSize = 20f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(this@WelcomeActivity, R.color.ink))
        })
        val name = if (signUp) input(L.t("השם שלך", "Your name"), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PERSON_NAME).also { root.addView(it) } else null
        val email = input(L.t("אימייל", "Email"), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS).also { root.addView(it) }
        if (!signUp && sentTo.isNotEmpty()) email.setText(sentTo)
        val password = input(if (signUp) L.t("סיסמה (לפחות 8 תווים)", "Password (at least 8 characters)") else L.t("סיסמה", "Password"), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD).also { root.addView(it) }
        root.addView(bigButton(if (signUp) L.t("יצירת חשבון", "Create account") else L.t("התחברות", "Sign in"), filled = true) {
            val e = email.text.toString().trim()
            val pw = password.text.toString()
            val n = name?.text?.toString()?.trim().orEmpty()
            when {
                signUp && n.isEmpty() -> Toast.makeText(this, L.t("איך לקרוא לך?", "What should we call you?"), Toast.LENGTH_SHORT).show()
                !e.contains("@") -> Toast.makeText(this, L.t("כתובת האימייל לא תקינה", "That email address doesn't look right"), Toast.LENGTH_SHORT).show()
                // New passwords: 8 at least (the server asks the same). Signing in still takes older, shorter ones.
                signUp && pw.length < 8 -> Toast.makeText(this, L.t("הסיסמה צריכה להיות לפחות 8 תווים", "Password must be at least 8 characters"), Toast.LENGTH_SHORT).show()
                pw.length < 6 -> Toast.makeText(this, L.t("הסיסמה צריכה להיות לפחות 6 תווים", "Password must be at least 6 characters"), Toast.LENGTH_SHORT).show()
                signUp -> run(L.t("יוצרים את החשבון...", "Creating your account...")) {
                    // Not an error: the account is made and waits for the email link (its own screen, not a red toast).
                    if (!Account.signUp(applicationContext, n, e, pw)) { sentTo = e; mode = Mode.CHECK_MAIL }
                }
                else -> run(L.t("מתחברים...", "Signing in...")) { Account.signIn(applicationContext, e, pw) }
            }
        })
        if (!signUp) root.addView(link(L.t("שכחתי סיסמה", "Forgot password"), R.color.accent, 10) { forgot(email.text.toString().trim()) })
        root.addView(link(if (signUp) L.t("כבר יש לי חשבון · התחברות", "I already have an account · Sign in") else L.t("אין לי חשבון · הרשמה", "No account yet · Sign up"), R.color.accent, 14) {
            mode = if (signUp) Mode.SIGN_IN else Mode.SIGN_UP
            render()
        })
        root.addView(link(L.t("‹ חזרה", "‹ Back"), R.color.ink_soft, 4) { mode = Mode.START; render() })
    }

    /** "Forgot password": the email gets a link to a page where a new password is chosen. */
    private fun forgot(typed: String) {
        val field = input(L.t("האימייל של החשבון", "Your account's email"), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS).apply { setText(typed) }
        val box = FrameLayout(this).apply { setPadding(ui.dp(20), 0, ui.dp(20), 0); addView(field) }
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(L.t("איפוס סיסמה", "Reset password"))
            .setMessage(L.t("נשלח אליכם מייל עם קישור לבחירת סיסמה חדשה.", "We'll email you a link to choose a new password."))
            .setView(box)
            .setPositiveButton(L.t("שליחה", "Send"), null)
            .setNegativeButton(L.t("ביטול", "Cancel"), null)
            .show()
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val e = field.text.toString().trim()
            if (!e.contains("@")) { field.error = L.t("כתובת האימייל לא תקינה", "That email address doesn't look right"); return@setOnClickListener }
            dialog.dismiss()
            worker.execute {
                val err = try { Account.recover(applicationContext, e); null } catch (x: Exception) { x.message }
                runOnUiThread {
                    if (isFinishing) return@runOnUiThread
                    Toast.makeText(this, err ?: L.t("אם יש חשבון עם $e, שלחנו אליו קישור. בדקו את המייל (וגם את הספאם).", "If there's an account with $e, we sent it a link. Check your email (and spam)."), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** After signing up: check the inbox, then sign in (the email already filled in). */
    private fun checkMail() {
        root.addView(TextView(this).apply {
            text = L.t("📬 בדקו את המייל", "📬 Check your email")
            textSize = 20f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(ContextCompat.getColor(this@WelcomeActivity, R.color.ink))
            androidx.core.view.ViewCompat.setAccessibilityHeading(this, true)
        })
        root.addView(TextView(this).apply {
            text = L.t("החשבון נוצר. שלחנו קישור אישור אל $sentTo.\nלחצו על הקישור במייל, ואז חזרו לכאן והתחברו.\nלא הגיע? בדקו גם בתיקיית הספאם.",
                "Your account was created. We sent a confirmation link to $sentTo.\nTap the link in the email, then come back and sign in.\nNothing yet? Check your spam folder too.")
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(this@WelcomeActivity, R.color.ink_soft))
            setPadding(0, ui.dp(10), 0, 0)
        })
        root.addView(bigButton(L.t("אישרתי, להתחברות", "Done, sign in"), filled = true) { mode = Mode.SIGN_IN; render() })
        root.addView(link(L.t("‹ חזרה", "‹ Back"), R.color.ink_soft, 4) { mode = Mode.START; render() })
    }
}

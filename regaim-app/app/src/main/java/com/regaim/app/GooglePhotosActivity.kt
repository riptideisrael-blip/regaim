package com.regaim.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import java.util.concurrent.Executors

/** Import from Google Photos: connect → choose in Google Photos (photos or whole albums) → we copy only what was chosen. */
class GooglePhotosActivity : AppCompatActivity() {
    companion object {
        fun open(ctx: Context) = ctx.startActivity(Intent(ctx, GooglePhotosActivity::class.java))
    }

    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var status: TextView
    private lateinit var actions: LinearLayout
    private var token: String? = null
    private var session: GooglePhotos.Session? = null
    private var waiting = false
    private var reopenShown = false
    @Volatile private var busy = false

    private val consent = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        try {
            onToken(Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(res.data))
        } catch (_: Exception) {
            say(L.t("לא קיבלנו הרשאה מ-Google. אפשר לנסות שוב.", "Google didn't grant access. You can try again."))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_screen)
        findViewById<TextView>(R.id.screenTitle).text = "Google Photos"
        findViewById<View>(R.id.btnClose).setOnClickListener { finish() }
        val ui = Ui(this)
        val content = findViewById<LinearLayout>(R.id.screenContent)
        content.addView(ui.text(L.t("להביא רגעים מ-Google Photos", "Bring in moments from Google Photos"), 19f, bold = true, top = 18))
        content.addView(ui.hint(L.t("בחרו תמונות וסרטונים ב-Google Photos. טיפ: לחיצה על סימן ה-✓ ליד כותרת תאריך (יום או חודש) בוחרת את כולו בבת אחת. גוגל לא מאפשרת לאפליקציות לבחור אלבום שלם, אבל אפשר לבחור עד ${GooglePhotos.MAX_ITEMS} פריטים בכל פעם, ולחזור שוב להמשך. מה שכבר הועתק מדולג. הכול נשאר פרטי.", "Pick photos and videos in Google Photos. Tip: tap the ✓ next to a date header (day or month) to select all of it. Google doesn't let apps pick a whole album, but you can pick up to ${GooglePhotos.MAX_ITEMS} items at a time and come back for more. Already-copied items are skipped. Everything stays private."), top = 8))
        actions = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        actions.addView(ui.button(L.t("התחברו ובחרו מ-Google Photos", "Connect and choose from Google Photos"), primary = true, top = 20) { if (!busy) connect() })
        content.addView(actions)
        status = ui.hint("", top = 16)
        content.addView(status)
    }

    override fun onResume() {
        super.onResume()
        if (waiting && !busy) fetchPicked()
    }

    private fun say(t: String) = runOnUiThread { if (!isFinishing) status.text = t }

    private fun connect() {
        say(L.t("מתחברים ל-Google...", "Connecting to Google..."))
        val req = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(GooglePhotos.SCOPE))).build()
        Identity.getAuthorizationClient(this).authorize(req)
            .addOnSuccessListener { r ->
                if (r.hasResolution()) {
                    try { consent.launch(IntentSenderRequest.Builder(r.pendingIntent!!.intentSender).build()) }
                    catch (_: Exception) { say(L.t("לא הצלחנו לפתוח את חלון ההרשאה.", "Couldn't open the sign-in window.")) }
                } else onToken(r)
            }
            .addOnFailureListener { say(L.t("ההתחברות ל-Google נכשלה. בדקו שהגדרות Google Cloud הושלמו (ראו את ההוראות).", "Couldn't connect to Google. Make sure the Google Cloud setup is complete (see the instructions).")) }
    }

    private fun onToken(r: AuthorizationResult) {
        val t = r.accessToken
        if (t == null) { say(L.t("לא התקבלה הרשאה מ-Google.", "Google didn't grant access.")); return }
        token = t
        busy = true
        say(L.t("פותחים את Google Photos...", "Opening Google Photos..."))
        worker.execute {
            try {
                val s = GooglePhotos.createSession(t)
                session = s
                waiting = true
                busy = false
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    status.text = L.t("בחרו ב-Google Photos ואז חזרו לכאן.", "Make your picks in Google Photos, then come back here.")
                    try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(s.pickerUri))) }
                    catch (_: Exception) { status.text = L.t("לא הצלחנו לפתוח את Google Photos.", "Couldn't open Google Photos.") }
                }
            } catch (e: Exception) {
                busy = false
                say(e.message ?: L.t("משהו השתבש", "Something went wrong"))
            }
        }
    }

    private fun fetchPicked() {
        val t = token ?: return
        val s = session ?: return
        busy = true
        say(L.t("בודקים מה בחרתם...", "Checking what you picked..."))
        worker.execute {
            try {
                var ready = false
                repeat(8) { if (!ready) { ready = GooglePhotos.picked(t, s.id); if (!ready) Thread.sleep(2000) } }
                if (!ready) {
                    busy = false
                    say(L.t("עוד לא נבחר כלום. בחרו ב-Google Photos ולחצו על 'סיום', או נסו שוב.", "Nothing picked yet. Choose in Google Photos and tap 'Done', or try again."))
                    runOnUiThread { if (!reopenShown) { reopenShown = true; actions.addView(Ui(this).button(L.t("פתחו שוב את Google Photos", "Open Google Photos again"), top = 8) { reopenShown = false; actions.removeViewAt(actions.childCount - 1); try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(s.pickerUri))) } catch (_: Exception) { } }) } }
                    return@execute
                }
                waiting = false
                val items = GooglePhotos.items(t, s.id)
                // The copying itself runs in the background (with a notification), so leaving this screen is fine.
                val before = ImportService.State.finishedJobs
                if (!ImportService.start(applicationContext, GooglePhotosJob(t, s.id, items))) {
                    busy = false
                    say(L.t("הוספה קודמת עוד רצה. נסו שוב כשהיא תסתיים (ההתראה תודיע).", "A previous import is still running. Try again when it's done (the notification will tell you)."))
                    return@execute
                }
                watch(before)
            } catch (e: Exception) {
                busy = false
                say(e.message ?: L.t("משהו השתבש", "Something went wrong"))
            }
        }
    }

    /** Shows the background copy's progress while this screen is open; when it ends, goes on as before. */
    private fun watch(before: Int) {
        val st = ImportService.State
        if (isFinishing || isDestroyed) return
        if (st.finishedJobs == before) {
            say(L.t("מעתיקים ${st.done} מתוך ${st.total}... אפשר לצאת מהמסך ומהאפליקציה, ההעתקה ממשיכה ברקע.", "Copying ${st.done} of ${st.total}... You can leave this screen and the app; copying goes on in the background."))
            status.postDelayed({ watch(before) }, 1000)
            return
        }
        busy = false
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            val last = st.lastId
            when {
                st.added == 0 && st.skipped > 0 -> status.text = L.t("כל מה שבחרתם כבר נמצא ב-Moments.", "Everything you picked is already in Moments.")
                st.added == 0 -> status.text = L.t("לא הצלחנו להעתיק את הפריטים שנבחרו. נסו שוב.", "Couldn't copy the selected items. Please try again.")
                st.added == 1 && last != null -> { MomentActivity.open(this, last); finish() }
                else -> { android.widget.Toast.makeText(this, L.t("נוספו ${st.added} רגעים ✓", "${st.added} moments added ✓"), android.widget.Toast.LENGTH_LONG).show(); startActivity(Intent(this, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)); finish() }
            }
        }
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }
}

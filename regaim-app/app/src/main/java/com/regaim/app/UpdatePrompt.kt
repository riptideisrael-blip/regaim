package com.regaim.app

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * When the app opens: is there a newer version on our server? If so, asks (once; after "not now", again only a day
 * later): "Version 121 is ready (you have 119). Install?" — and on yes downloads it and opens Android's installer.
 * Looked for at most every half hour, in the background, and only when signed in.
 */
object UpdatePrompt {
    @Volatile private var lastLook = 0L
    @Volatile private var busy = false
    private val main = Handler(Looper.getMainLooper())
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("app_update", Context.MODE_PRIVATE)

    /** "גרסה 121" (or the name as it is). */
    fun shown(name: String?): String = AppUpdate.number(name)?.let { L.t("גרסה $it", "version $it") } ?: name.orEmpty()

    fun installedName(ctx: Context): String = try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName.orEmpty() } catch (_: Exception) { "" }

    fun maybeAsk(a: Activity) {
        val now = System.currentTimeMillis()
        if (TestMode.on || busy || now - lastLook < 30 * 60_000L || !Account.signedIn(a)) return
        lastLook = now
        val app = a.applicationContext
        Thread {
            val info = try { AppUpdater.check(app) } catch (_: Exception) { null }
            if (info == null || !AppUpdate.isNewer(info, AppUpdater.installed(app))) return@Thread
            val p = prefs(app)
            if (!AppUpdate.askAgain(p.getLong("declined_code", 0), p.getLong("declined_at", 0), info.code, System.currentTimeMillis())) return@Thread
            main.post { if (!a.isFinishing && !a.isDestroyed) ask(a, info) }
        }.start()
    }

    /** The sheet: the app's mark, "a new version", the numbers, install / not now; then a plain progress bar. */
    private fun ask(a: Activity, info: AppUpdate.Info) {
        val ui = Ui(a)
        val dialog = android.app.Dialog(a)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val card = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            background = DS.rounded(DS.bg(a), ui.dp(28).toFloat())
            setPadding(ui.dp(24), ui.dp(24), ui.dp(24), ui.dp(18))
        }
        val mark = ImageView(a).apply {
            layoutParams = LinearLayout.LayoutParams(ui.dp(56), ui.dp(56))
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageResource(R.drawable.ic_logo_mark)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        card.addView(mark)
        val title = ui.heading(L.t("גרסה חדשה זמינה", "A new version is available"), 24f, top = 12).apply { gravity = Gravity.CENTER }
        card.addView(title)
        val badge = TextView(a).apply {
            text = shown(info.name); textSize = 16f; typeface = DS.emphasis; setTextColor(DS.onButton(a)); gravity = Gravity.CENTER
            background = DS.rounded(DS.button(a), ui.dp(16).toFloat()); setPadding(ui.dp(16), ui.dp(5), ui.dp(16), ui.dp(5))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ui.dp(10) }
        }
        card.addView(badge)
        val sub = ui.text(L.t("הגרסה שלך: ${shown(installedName(a))}", "Your version: ${shown(installedName(a))}"), 14f, R.color.ink_soft, top = 8).apply { gravity = Gravity.CENTER }
        card.addView(sub)
        val note = ui.text(L.t("כל הרגעים, הילדים והסרטים נשמרים.\nגודל ההורדה: ${AppUpdate.mb(info.size)}", "All your moments, children and films are kept.\nDownload size: ${AppUpdate.mb(info.size)}"), 15f, top = 12).apply { gravity = Gravity.CENTER }
        card.addView(note)

        // The download: a plain bar and the percentage.
        val track = FrameLayout(a).apply {
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(10)).apply { topMargin = ui.dp(18) }
            background = DS.rounded(DS.cardAlt(a), ui.dp(5).toFloat())
            visibility = View.GONE
        }
        val fill = View(a).apply {
            background = DS.rounded(DS.button(a), ui.dp(5).toFloat())
            layoutParams = FrameLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START)
        }
        track.addView(fill)
        card.addView(track)
        val pct = ui.text("0%", 15f, R.color.ink_soft, top = 8).apply { gravity = Gravity.CENTER; visibility = View.GONE }
        card.addView(pct)

        val install = ui.button(L.t("להתקין עכשיו", "Install now"), primary = true, top = 18) {}
        val later = ui.action(L.t("לא עכשיו", "Not now"), R.color.ink_soft) {}.apply { gravity = Gravity.CENTER; setPadding(0, ui.dp(12), 0, ui.dp(4)) }
        card.addView(install); card.addView(later)

        val outer = FrameLayout(a).apply { setPadding(ui.dp(20), 0, ui.dp(20), 0); addView(card) }
        dialog.setContentView(outer)
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        later.setOnClickListener {
            prefs(a).edit().putLong("declined_code", info.code).putLong("declined_at", System.currentTimeMillis()).apply()
            dialog.dismiss()
        }
        install.setOnClickListener {
            busy = true
            dialog.setCancelable(false)
            title.text = L.t("מורידים את הגרסה החדשה", "Downloading the new version")
            sub.visibility = View.GONE; install.visibility = View.GONE; later.visibility = View.GONE
            note.text = L.t("זה לוקח בדרך כלל פחות מדקה.", "This usually takes less than a minute.")
            track.visibility = View.VISIBLE; pct.visibility = View.VISIBLE
            fun show(p: Int) {
                pct.text = "$p%"
                pct.contentDescription = L.t("הורדה: $p אחוז", "Download: $p percent")
                val w = track.width
                if (w > 0) {
                    fill.layoutParams = (fill.layoutParams as FrameLayout.LayoutParams).apply { width = (w * p / 100f).toInt() }
                    fill.requestLayout()
                }
            }
            val app = a.applicationContext
            Thread {
                var last = -1
                val result = try {
                    Result.success(AppUpdater.download(app, info) { p -> if (p != last) { last = p; main.post { show(p) } } })
                } catch (e: Exception) { Result.failure(e) }
                main.post {
                    busy = false
                    if (a.isFinishing || a.isDestroyed) { try { dialog.dismiss() } catch (_: Exception) {}; return@post }
                    result.onSuccess { f ->
                        show(100)
                        title.text = L.t("ההורדה הושלמה", "Download complete")
                        note.text = L.t("עכשיו אנדרואיד ישאל אם להתקין. לוחצים \"התקנה\".", "Android will now ask to install. Tap \"Install\".")
                        main.postDelayed({
                            try { dialog.dismiss() } catch (_: Exception) {}
                            try {
                                if (!AppUpdater.install(a, f)) android.widget.Toast.makeText(a, L.t("צריך לאשר פעם אחת התקנה מ-Moments, ואז: הגדרות ← עדכון גרסה ← להתקין עכשיו", "Allow installs from Moments once, then Settings → App updates → Install now"), android.widget.Toast.LENGTH_LONG).show()
                            } catch (_: Exception) {
                                android.widget.Toast.makeText(a, L.t("לא הצלחנו לפתוח את ההתקנה", "Couldn't open the installer"), android.widget.Toast.LENGTH_LONG).show()
                            }
                        }, 1200)
                    }.onFailure {
                        title.text = L.t("ההורדה לא הצליחה", "The download didn't work")
                        note.text = (it.message ?: "") + L.t("\nאפשר לנסות שוב מההגדרות ← עדכון גרסה.", "\nTry again from Settings → App updates.")
                        track.visibility = View.GONE; pct.visibility = View.GONE
                        dialog.setCancelable(true)
                        later.text = L.t("סגירה", "Close"); later.visibility = View.VISIBLE
                        later.setOnClickListener { dialog.dismiss() }
                    }
                }
            }.start()
        }
        dialog.show()
    }
}

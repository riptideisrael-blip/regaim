package com.regaim.app

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Bundle
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * When the app crashes: what broke (the code's lines), on which screen, which version and phone — kept on the
 * phone and sent to our server the next time the app opens (server/crash-reports.sql), so a crash is known and
 * fixed without anyone having to tell. Nothing personal: no names, pictures, words or moments; the stack of the
 * code, the screen's name, the app version, the phone model and Android version.
 */
object CrashLog {
    @Volatile private var screen = ""
    private fun dir(ctx: Context) = File(ctx.filesDir, "crashes").apply { mkdirs() }

    fun install(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(a: Activity) { screen = a.javaClass.simpleName }
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
        val before = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val version = try { app.packageManager.getPackageInfo(app.packageName, 0).versionName.orEmpty() } catch (_: Exception) { "" }
                val o = JSONObject().put("app_version", version).put("device", Build.MANUFACTURER + " " + Build.MODEL)
                    .put("android", Build.VERSION.SDK_INT).put("screen", screen).put("thread", t.name)
                    .put("trace", android.util.Log.getStackTraceString(e).take(9000))
                File(dir(app), "crash_${System.currentTimeMillis()}.json").writeText(o.toString())
            } catch (_: Throwable) {}
            before?.uncaughtException(t, e)
        }
        // The ones from before: sent now, in the background (not from the QA build: its crashes are in the test report).
        if (!TestMode.on) Thread { try { send(app) } catch (_: Throwable) {} }.start()
    }

    /** Background thread. Sends what's waiting (at most 5 at a time); a sent one is removed. */
    fun send(ctx: Context) {
        val files = dir(ctx).listFiles()?.filter { it.name.endsWith(".json") }?.sortedBy { it.name }.orEmpty()
        if (files.isEmpty()) return
        // Too old to matter (a month): just removed.
        files.filter { System.currentTimeMillis() - it.lastModified() > 30L * 24 * 3600 * 1000 }.forEach { it.delete() }
        for (f in files.filter { it.exists() }.take(5)) {
            val body = try { f.readText() } catch (_: Exception) { f.delete(); continue }
            val ok = try {
                val conn = URL(Circle.url(ctx) + "/rest/v1/crash_reports").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 15_000; conn.readTimeout = 20_000
                val key = Circle.key(ctx)
                conn.setRequestProperty("apikey", key)
                conn.setRequestProperty("Authorization", "Bearer " + (Account.token(ctx) ?: key))
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Prefer", "return=minimal")
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                conn.disconnect()
                code in 200..299
            } catch (_: Exception) { false }
            if (ok) f.delete() else return // no server table yet, or offline: next time
        }
    }
}

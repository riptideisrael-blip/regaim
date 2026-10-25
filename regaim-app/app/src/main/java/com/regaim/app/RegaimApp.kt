package com.regaim.app

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class RegaimApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        L.init(this)
        Fonts.install(this)
        Reminders.schedule(this)
        FaceScan.schedule(this)
        FamilyNews.schedule(this) // "a new moment from the family" notifications
        registerActivityLifecycleCallbacks(SystemBars)
        A11y.install(this)
    }

    /**
     * Android 15+ draws every screen under the clock bar and the navigation bar. Every screen gets room for them
     * here, in one place, so the app looks as before (the window's background shows behind the bars). The
     * full-screen viewers (stories, the book) hide the bars themselves and are left as they are. The keyboard gets
     * room too, so typing fields aren't hidden under it.
     */
    private object SystemBars : ActivityLifecycleCallbacks {
        private fun isFullScreen(a: Activity) = a is StoryViewerActivity || a is BookViewerActivity

        override fun onActivityStarted(a: Activity) {
            // Before Android 15 the system keeps the screen out of the bars (and resizes it for the keyboard) itself.
            if (android.os.Build.VERSION.SDK_INT < 35 || isFullScreen(a)) return
            val content = a.findViewById<ViewGroup>(android.R.id.content) ?: return
            if (content.getTag(R.id.tagBarsApplied) == true) return
            content.setTag(R.id.tagBarsApplied, true)
            ViewCompat.setOnApplyWindowInsetsListener(content) { v: View, insets: WindowInsetsCompat ->
                val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
                val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
                v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
                WindowInsetsCompat.CONSUMED
            }
            ViewCompat.requestApplyInsets(content)
        }

        override fun onActivityCreated(a: Activity, b: Bundle?) {}
        override fun onActivityResumed(a: Activity) {}
        override fun onActivityPaused(a: Activity) {}
        override fun onActivityStopped(a: Activity) {}
        override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
        override fun onActivityDestroyed(a: Activity) {}
    }
}

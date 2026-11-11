package com.regaim.app

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

/**
 * Pull down to look for something new (the other parent's moments, the family feed, the shared file...). Wraps a
 * screen's existing scrolling view in place, so layouts don't change. [work] runs off the main thread; [done] on it.
 */
object PullRefresh {
    fun attach(a: Activity, scroll: View, work: () -> Unit, done: () -> Unit): SwipeRefreshLayout? {
        val parent = scroll.parent as? ViewGroup ?: return null
        val index = parent.indexOfChild(scroll)
        val lp = scroll.layoutParams
        parent.removeView(scroll)
        val srl = SwipeRefreshLayout(a).apply {
            setColorSchemeColors(DS.primary(a))
            setProgressBackgroundColorSchemeColor(DS.card(a))
        }
        srl.addView(scroll, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        parent.addView(srl, index, lp)
        lateinit var refresh: () -> Unit
        // Without the gesture (screen readers, switch access): a named action does the same.
        androidx.core.view.ViewCompat.addAccessibilityAction(srl, L.t("רענון", "Refresh")) { _, _ ->
            if (!srl.isRefreshing) { srl.isRefreshing = true; refresh() }
            true
        }
        srl.setOnRefreshListener { refresh() }
        refresh = {
            Thread {
                try { work() } catch (_: Exception) { } // no connection: what's on the phone stays
                a.runOnUiThread {
                    srl.isRefreshing = false
                    if (!a.isFinishing && !a.isDestroyed) {
                        done()
                        // Said for TalkBack users (the spinner alone isn't announced).
                        srl.announceForAccessibility(L.t("עודכן", "Updated"))
                    }
                }
            }.start()
        }
        return srl
    }
}

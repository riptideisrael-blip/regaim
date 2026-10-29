package com.regaim.app

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat

/**
 * Bottom tab bar: בית · זיכרונות · סטודיו · משפחה · תיק · קהילה. The camera is a mode (the "+" button), not a tab.
 * The profile and settings live behind the round picture at the top of each tab; "ask Moments" lives in Memories.
 */
object Nav {
    /** ASK and PROFILE are screens reached from inside a tab: no tab is highlighted there. */
    enum class Tab { HOME, MEMORIES, STUDIO, FAMILY, FILE, COMMUNITY, ASK, PROFILE }

    private val ids = mapOf(
        Tab.HOME to R.id.tabHome,
        Tab.MEMORIES to R.id.tabMemories,
        Tab.STUDIO to R.id.tabStudio,
        Tab.FAMILY to R.id.tabFamily,
        Tab.FILE to R.id.tabFile,
        Tab.COMMUNITY to R.id.tabCommunity,
    )

    private val targets: Map<Tab, Class<out Activity>> = mapOf(
        Tab.HOME to HomeActivity::class.java,
        Tab.MEMORIES to MemoriesActivity::class.java,
        Tab.STUDIO to StudioActivity::class.java,
        Tab.FAMILY to FamilyActivity::class.java,
        Tab.FILE to FamilyFileActivity::class.java,
        Tab.COMMUNITY to CommunityActivity::class.java,
    )

    /** [current] null (or ASK / PROFILE) = a screen that isn't a tab: no tab highlighted. */
    fun setup(a: Activity, current: Tab?) {
        // Design system: the active tab blue (icon and word), no pill behind it; the others soft gray.
        val off = DS.inkSoft(a)
        val on = DS.primary(a)
        for ((tab, id) in ids) {
            val v = a.findViewById<LinearLayout>(id) ?: continue
            val selected = tab == current
            val color = if (selected) on else off
            (v.getChildAt(0) as? ImageView)?.setColorFilter(color)
            (v.getChildAt(1) as? TextView)?.apply {
                setTextColor(color)
                typeface = if (selected) DS.emphasis else DS.body
            }
            v.background = DS.pressable(a, DS.rounded(android.graphics.Color.TRANSPARENT, DS.radiusChip(a)))
            v.isSelected = selected
            v.foreground = null
            v.setOnClickListener {
                if (!selected) {
                    a.startActivity(Intent(a, targets.getValue(tab)).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                    @Suppress("DEPRECATION")
                    a.overridePendingTransition(0, 0)
                }
            }
        }
    }

    /** The round "me" picture that opens the profile and settings. Added to the end of a header row. */
    fun addProfileButton(a: Activity, row: ViewGroup) {
        val mv = MemoryViews(a)
        val name = Profile.parentName(a).ifBlank { L.t("אני", "Me") }
        val v: View = mv.avatar(name, 40)
        v.contentDescription = L.t("פרופיל והגדרות", "Profile and settings")
        v.setOnClickListener { a.startActivity(Intent(a, ProfileActivity::class.java)) }
        (v.layoutParams as? ViewGroup.MarginLayoutParams)?.marginStart = mv.dp(8)
        row.addView(v)
    }
}

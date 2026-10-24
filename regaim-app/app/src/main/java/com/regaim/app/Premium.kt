package com.regaim.app

import android.content.Context

/**
 * Premium status. Payments are not wired yet: until a billing provider is added, the flag is switched on by hand
 * (the "test mode" in the unlock dialog), so the premium features can be built and tried.
 */
object Premium {
    private const val KEY = "premium_active"
    fun isPremium(ctx: Context) = Settings.prefs(ctx).getBoolean(KEY, false)
    fun set(ctx: Context, on: Boolean) = Settings.prefs(ctx).edit().putBoolean(KEY, on).apply()
}

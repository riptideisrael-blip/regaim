package com.regaim.app

import android.content.Context
import java.io.File

/**
 * Personal AI keys (a developer tool: families use the AI through Moments' server and never need one).
 * Kept encrypted on the phone (Android Keystore, like the face data), never in plain settings, and used only by the
 * admin account. A key saved in plain settings by an older version is moved here once.
 */
object AiKeys {
    private fun file(ctx: Context, name: String) = File(ctx.filesDir, "ai_key_$name.bin")
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("regaim", Context.MODE_PRIVATE)
    private fun oldPref(name: String) = if (name == "claude") "api_key" else "gemini_key"

    /** Only the admin (developer) account uses personal keys. */
    fun usable(ctx: Context) = Account.isAdmin(ctx)

    @Synchronized
    private fun stored(ctx: Context, name: String): String {
        val old = prefs(ctx).getString(oldPref(name), "").orEmpty()
        if (old.isNotEmpty()) {
            // Move an old plain key into the encrypted file; the plain copy is removed only once the encrypted one
            // was written and reads back the same (a failed write never loses the key).
            val moved = try {
                SecureFiles.writeText(file(ctx, name), old.trim())
                SecureFiles.readText(file(ctx, name)).orEmpty().trim() == old.trim()
            } catch (_: Exception) { false }
            if (moved) prefs(ctx).edit().remove(oldPref(name)).apply() else return old.trim()
        }
        return try { SecureFiles.readText(file(ctx, name)).orEmpty().trim() } catch (_: Exception) { "" }
    }

    fun get(ctx: Context, name: String): String = if (usable(ctx)) stored(ctx, name) else ""

    @Synchronized
    fun set(ctx: Context, name: String, key: String) {
        val k = key.trim()
        prefs(ctx).edit().remove(oldPref(name)).apply()
        if (k.isEmpty()) file(ctx, name).delete() else SecureFiles.writeText(file(ctx, name), k)
    }
}

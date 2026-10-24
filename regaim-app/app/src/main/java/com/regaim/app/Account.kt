package com.regaim.app

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * The family's account (Supabase Auth on the same project as the family circle):
 * Google sign-in through the browser, or email + password. Optional: the app works without it.
 */
object Account {
    const val REDIRECT = "moments://auth"

    class User(val id: String, val email: String, val name: String, val avatar: String)

    private fun p(ctx: Context) = Settings.prefs(ctx)

    fun signedIn(ctx: Context) = (p(ctx).getString("acc_refresh", "") ?: "").isNotEmpty()
    fun skipped(ctx: Context) = p(ctx).getBoolean("acc_skipped", false)
    fun skip(ctx: Context) = p(ctx).edit().putBoolean("acc_skipped", true).apply()

    fun id(ctx: Context) = p(ctx).getString("acc_id", "") ?: ""

    fun email(ctx: Context) = p(ctx).getString("acc_email", "") ?: ""
    fun name(ctx: Context) = p(ctx).getString("acc_name", "") ?: ""
    fun avatar(ctx: Context) = p(ctx).getString("acc_avatar", "") ?: ""

    // ---------- http ----------

    private fun call(ctx: Context, method: String, path: String, body: JSONObject?, bearer: String? = null): JSONObject {
        val conn = URL(Circle.url(ctx) + "/auth/v1/" + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 20_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("apikey", Circle.key(ctx))
            conn.setRequestProperty("Content-Type", "application/json")
            if (bearer != null) conn.setRequestProperty("Authorization", "Bearer $bearer")
            if (body != null) {
                conn.doOutput = true
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) throw IllegalStateException(friendly(text, code))
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }

    private fun friendly(raw: String, code: Int): String {
        val msg = try {
            val o = JSONObject(raw)
            o.optString("msg").ifEmpty { o.optString("error_description").ifEmpty { o.optString("message") } }
        } catch (_: Exception) {
            raw
        }
        val m = msg.lowercase()
        return when {
            "invalid login" in m || "invalid_credentials" in m -> L.t("האימייל או הסיסמה לא נכונים", "Wrong email or password")
            "already registered" in m || "already exists" in m -> L.t("כבר יש חשבון עם האימייל הזה. נסו להתחבר.", "There's already an account with this email. Try signing in.")
            "password" in m && "6" in m -> L.t("הסיסמה צריכה להיות לפחות 6 תווים", "Password must be at least 6 characters")
            "email not confirmed" in m -> L.t("צריך לאשר את האימייל (בדקו את תיבת הדואר) ואז להתחבר", "Please confirm your email (check your inbox), then sign in")
            "provider is not enabled" in m || "unsupported provider" in m -> L.t("ההתחברות עם Google עוד לא הופעלה בשרת", "Google sign-in isn't enabled on the server yet")
            "valid email" in m || "invalid format" in m -> L.t("כתובת האימייל לא תקינה", "That email address doesn't look right")
            code == 429 -> L.t("יותר מדי ניסיונות. נסו שוב בעוד דקה.", "Too many attempts. Try again in a minute.")
            else -> L.t("לא הצלחנו להתחבר ($code)", "Couldn't sign in ($code)")
        }
    }

    // ---------- sign in / up ----------

    /** Returns true if signed in now, false if the email still needs confirming. Background thread only. */
    fun signUp(ctx: Context, name: String, email: String, password: String): Boolean {
        val body = JSONObject().put("email", email.trim()).put("password", password)
            .put("data", JSONObject().put("full_name", name.trim()))
        val r = call(ctx, "POST", "signup", body)
        if (r.optString("access_token").isEmpty()) return false
        saveSession(ctx, r)
        return true
    }

    /** Background thread only. */
    fun signIn(ctx: Context, email: String, password: String) {
        val r = call(ctx, "POST", "token?grant_type=password", JSONObject().put("email", email.trim()).put("password", password))
        saveSession(ctx, r)
    }

    /** Where the reset link in the email opens: a small page on the family site to choose a new password. */
    const val RESET_PAGE = "https://moments-family-circle.netlify.app/reset-password.html"

    /**
     * "Forgot password": the server emails a one-time link to [email] (if there's an account; the answer is the same
     * either way, so nobody can learn who has an account). Background thread only.
     */
    fun recover(ctx: Context, email: String) {
        call(ctx, "POST", "recover?redirect_to=" + URLEncoder.encode(RESET_PAGE, "UTF-8"), JSONObject().put("email", email.trim()))
    }

    /** People who may open the admin screen (the server checks this again). */
    private val ADMINS = listOf("yossefrevivo@gmail.com")
    fun isAdmin(ctx: Context) = signedIn(ctx) && email(ctx).trim().lowercase() in ADMINS

    /** Is Google sign-in switched on in the server? null = could not check. Background thread only. */
    fun googleEnabled(ctx: Context): Boolean? = try {
        call(ctx, "GET", "settings", null).optJSONObject("external")?.optBoolean("google", false)
    } catch (_: Exception) {
        null
    }

    /** The address to open in the browser for "continue with Google". */
    fun googleUrl(ctx: Context): String {
        // Remember that this phone started a Google sign-in: a moments://auth link that arrives without it is ignored.
        p(ctx).edit().putLong("acc_google_started", System.currentTimeMillis()).apply()
        return Circle.url(ctx) + "/auth/v1/authorize?provider=google&redirect_to=" + URLEncoder.encode(REDIRECT, "UTF-8")
    }

    /** Handles moments://auth#access_token=...&refresh_token=... coming back from the browser. Background thread only. */
    fun handleRedirect(ctx: Context, uri: Uri): Boolean {
        val fragment = uri.fragment ?: uri.query ?: return false
        val params = fragment.split("&").mapNotNull { kv ->
            val i = kv.indexOf('=')
            if (i <= 0) null else kv.substring(0, i) to Uri.decode(kv.substring(i + 1))
        }.toMap()
        params["error_description"]?.let { throw IllegalStateException(it.replace('+', ' ')) }
        val access = params["access_token"] ?: return false
        val refresh = params["refresh_token"] ?: return false
        // Only once, and only right after "continue with Google" on this phone: otherwise a link could sign the phone
        // into someone else's account (and the family's moments would then go there).
        val started = p(ctx).getLong("acc_google_started", 0L)
        p(ctx).edit().remove("acc_google_started").apply()
        if (!LinkSafety.redirectExpected(started, System.currentTimeMillis())) throw IllegalStateException(L.t("ההתחברות לא התחילה מהאפליקציה. נסו שוב עם Google.", "Sign-in didn't start in the app. Try Google again."))
        val expires = params["expires_in"]?.toLongOrNull() ?: 3600L
        p(ctx).edit().putString("acc_access", access).putString("acc_refresh", refresh)
            .putLong("acc_expires", System.currentTimeMillis() + expires * 1000).apply()
        loadUser(ctx)
        return true
    }

    private fun saveSession(ctx: Context, r: JSONObject) {
        p(ctx).edit().putString("acc_access", r.optString("access_token"))
            .putString("acc_refresh", r.optString("refresh_token"))
            .putLong("acc_expires", System.currentTimeMillis() + r.optLong("expires_in", 3600L) * 1000).apply()
        r.optJSONObject("user")?.let { saveUser(ctx, it) } ?: loadUser(ctx)
    }

    private fun loadUser(ctx: Context) {
        val token = p(ctx).getString("acc_access", "") ?: return
        saveUser(ctx, call(ctx, "GET", "user", null, token))
    }

    private fun saveUser(ctx: Context, u: JSONObject) {
        val meta = u.optJSONObject("user_metadata") ?: JSONObject()
        val name = meta.optString("full_name").ifEmpty { meta.optString("name") }
        val avatar = meta.optString("avatar_url").ifEmpty { meta.optString("picture") }
        p(ctx).edit().putString("acc_id", u.optString("id")).putString("acc_email", u.optString("email"))
            .putString("acc_name", name).putString("acc_avatar", avatar).apply()
        // First sign-in fills the family profile and the name the family sees.
        if (Profile.parentName(ctx).isEmpty() && name.isNotEmpty()) Profile.setParent(ctx, name, Profile.role(ctx))
        if ((p(ctx).getString("circle_me", "") ?: "").isEmpty() && name.isNotEmpty()) Circle.setMe(ctx, name.split(" ").first())
    }

    /** A valid access token, refreshed when needed; null if not signed in. Background thread only. */
    @Synchronized
    fun token(ctx: Context): String? {
        if (!signedIn(ctx)) return null
        val exp = p(ctx).getLong("acc_expires", 0L)
        if (System.currentTimeMillis() < exp - 60_000) return p(ctx).getString("acc_access", null)
        return try {
            val r = call(ctx, "POST", "token?grant_type=refresh_token", JSONObject().put("refresh_token", p(ctx).getString("acc_refresh", "")))
            saveSession(ctx, r)
            p(ctx).getString("acc_access", null)
        } catch (_: Exception) {
            null
        }
    }

    /** Background thread only (best effort). */
    fun signOut(ctx: Context) {
        try { p(ctx).getString("acc_access", null)?.let { call(ctx, "POST", "logout", null, it) } } catch (_: Exception) {}
        Circle.clearSession(ctx)
        // Family messages this account wrote but didn't send yet never go out under another account.
        try { FamilyOutbox.clear(ctx) } catch (_: Exception) {}
        p(ctx).edit().remove("acc_access").remove("acc_refresh").remove("acc_expires").remove("acc_id")
            .remove("acc_email").remove("acc_name").remove("acc_avatar").putBoolean("acc_skipped", false).apply()
    }
}

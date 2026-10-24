package com.regaim.app

import android.app.Activity
import android.content.Context
import androidx.appcompat.app.AlertDialog
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * One server gate for all AI, like the cartoon film: the app never holds an AI key. Moments' server
 * (Supabase function "ai") holds the keys, checks the account, and keeps a monthly budget per family.
 * The family agrees once, on a screen that says exactly what is sent.
 */
object AiGate {
    private fun p(ctx: Context) = Settings.prefs(ctx)

    fun consented(ctx: Context) = p(ctx).getBoolean("ai_consent", false)
    fun setConsent(ctx: Context, on: Boolean) = p(ctx).edit().putBoolean("ai_consent", on).apply()

    /** Signed in with a real account and agreed to the consent screen. */
    fun ready(ctx: Context) = Account.signedIn(ctx) && consented(ctx)

    /** Comic / film drawings through the server: only when the server said it has a drawing key. */
    fun drawingReady(ctx: Context) = ready(ctx) && p(ctx).getBoolean("ai_drawing_on", false)

    class Usage(val left: Int, val limit: Int, val drawing: Boolean)

    /** What each kind of request sends, word for word on the consent screen. */
    fun consentText(): String = L.t(
        "כשמשתמשים בהבנת רגעים, נשלחים לשרת של Moments ומשם למודל ה-AI (Claude של Anthropic):\n\n" +
            "• תיאור רגע: 3 תמונות מתוך הסרטון (לא הסרטון, לא קול), השמות שזוהו או שאישרתם לרגע הזה, תווית הגלאי (למשל \"צחוק\"), התאריך, שם המקום אם נשמר, ומה שכתוב ב\"מה Moments למד\" על מה שאתם אוהבים לשמור.\n" +
            "• \"למצוא את הרגע המדויק\" (רק כשתבקשו): 8 תמונות סביב הרגע, עם הזמן של כל אחת.\n" +
            "• שאלה על הזיכרונות: השאלה שלכם, פרטי הטקסט של עד 20 רגעים שנמצאו מתאימים בטלפון (תאריך, מקום, שמות, תיאור), והשמות והגילאים מפרופיל המשפחה (כדי להבין \"הבן שלי\"). בלי תמונות.\n" +
            "• שאלה על רגע אחד: 3 תמונות מהרגע והשאלה. סיפור יום, קומיקס ולמידה: הטקסט של הרגעים שבחרתם ופרופיל המשפחה; ציור קומיקס: התמונות של הרגעים שבחרתם.\n\n" +
            "השרת שלנו לא שומר את התמונות ואת השאלות. יש מכסה חודשית למשפחה. אפשר לבטל את ההסכמה בכל רגע בהגדרות.",
        "When you use moment understanding, this goes to Moments' server and from there to the AI model (Anthropic's Claude):\n\n" +
            "• Describing a moment: 3 pictures from the video (not the video, no sound), the names recognized or approved for that moment, the detector's label (e.g. \"laughter\"), the date, the place name if saved, and what \"What Moments learned\" says you like to keep.\n" +
            "• \"Find the exact moment\" (only when you ask): 8 pictures around the moment, each with its time.\n" +
            "• A question about your memories: your question, the text details of up to 20 moments found on your phone (date, place, names, description), and the names and ages in your family profile (to understand \"my son\"). No pictures.\n" +
            "• A question about one moment: 3 pictures from it and the question. Day story, comics and learning: the text of the moments you chose and the family profile; comic drawings: the pictures of the moments you chose.\n\n" +
            "Our server doesn't keep the pictures or the questions. There's a monthly allowance per family. You can withdraw consent any time in Settings.",
    )

    /** Shows the consent screen once; [then] runs only after "I agree". */
    fun askConsent(a: Activity, then: () -> Unit) {
        if (consented(a)) { then(); return }
        if (!Account.signedIn(a)) {
            AlertDialog.Builder(a)
                .setMessage(L.t("כדי להשתמש בהבנת רגעים צריך להתחבר לחשבון.", "To use moment understanding, sign in to your account."))
                .setPositiveButton(L.t("התחברות", "Sign in")) { _, _ -> WelcomeActivity.open(a) }
                .setNegativeButton(L.t("לא עכשיו", "Not now"), null).show()
            return
        }
        AlertDialog.Builder(a)
            .setTitle(L.t("מה נשלח, בדיוק", "Exactly what is sent"))
            .setMessage(consentText())
            .setPositiveButton(L.t("מסכימים", "I agree")) { _, _ ->
                setConsent(a, true)
                Thread { try { usage(a.applicationContext) } catch (_: Exception) { } }.start()
                then()
            }
            .setNegativeButton(L.t("לא עכשיו", "Not now"), null)
            .show()
    }

    /** The family's allowance this month (and whether drawings are available). Background thread only. */
    fun usage(ctx: Context): Usage {
        val r = post(ctx, JSONObject().put("action", "usage"))
        val o = try { JSONObject(r.second) } catch (_: Exception) { JSONObject() }
        if (r.first !in 200..299) throw ClaudeClient.ApiException(error(r.first, o))
        val u = Usage(o.optInt("left"), o.optInt("limit"), o.optBoolean("drawing"))
        p(ctx).edit().putBoolean("ai_drawing_on", u.drawing).apply()
        return u
    }

    /** A Claude Messages request through the server; returns the reply's text. Background thread only. */
    /** [consentedNow]: the family just agreed to exactly this use on its own consent screen (a personal book). */
    /** The model that actually answered the last [claude] call on this thread (the server may use a cheaper one). */
    val lastModel = ThreadLocal<String>()

    fun claude(ctx: Context, body: JSONObject, purpose: String, images: Int, consentedNow: Boolean = false): String {
        if (!(ready(ctx) || (consentedNow && Account.signedIn(ctx)))) throw ClaudeClient.ApiException(L.t("הבנת רגעים לא מופעלת. אפשר להפעיל אותה בהגדרות.", "Moment understanding is off. You can turn it on in Settings."))
        val r = post(ctx, JSONObject().put("action", "claude").put("purpose", purpose).put("images", images).put("body", body))
        val o = try { JSONObject(r.second) } catch (_: Exception) { JSONObject() }
        if (r.first !in 200..299) throw ClaudeClient.ApiException(error(r.first, o))
        lastModel.set(o.optString("model"))
        val arr = o.optJSONArray("content") ?: JSONArray()
        val sb = StringBuilder()
        for (i in 0 until arr.length()) {
            val block = arr.optJSONObject(i) ?: continue
            if (block.optString("type") == "text") sb.append(block.optString("text"))
        }
        return sb.toString()
    }

    /** The answer stopped before Claude finished: a dropped connection, or the server's time limit ([tooSlow]). */
    class Incomplete(val tooSlow: Boolean) : java.io.IOException(if (tooSlow) "too slow" else "stream cut")

    /**
     * Like [claude], but the text comes back piece by piece while Claude writes (streaming). For long answers
     * (a whole book's story): the server answers at once and keeps sending, so neither its 150-second limit for a
     * first answer nor a mobile network that drops quiet connections cuts it. [onChars] gets the length so far.
     * Throws [Incomplete] when the answer didn't reach its end. Background thread only.
     */
    fun claudeStream(ctx: Context, body: JSONObject, purpose: String, consentedNow: Boolean = false, onChars: (Int) -> Unit = {}): String =
        claudeStreamFull(ctx, body, purpose, consentedNow, onChars).text

    /** Like [claudeStream], also telling why Claude stopped (cut at the length limit or not). */
    fun claudeStreamFull(ctx: Context, body: JSONObject, purpose: String, consentedNow: Boolean = false, onChars: (Int) -> Unit = {}): Sse.Streamed {
        TestMode.guard()
        if (!(ready(ctx) || (consentedNow && Account.signedIn(ctx)))) throw ClaudeClient.ApiException(L.t("הבנת רגעים לא מופעלת. אפשר להפעיל אותה בהגדרות.", "Moment understanding is off. You can turn it on in Settings."))
        val token = Account.token(ctx) ?: throw java.io.IOException("token refresh")
        val started = System.currentTimeMillis()
        val conn = (URL(Circle.url(ctx) + "/functions/v1/ai").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 60_000 // between pieces (Claude also sends a "ping" while it thinks)
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $token")
        }
        try {
            conn.outputStream.use { it.write(JSONObject().put("action", "claude_stream").put("purpose", purpose).put("images", 0).put("body", body).toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code == 504 || code == 546) throw Incomplete(tooSlow = true)
            if (code !in 200..299) {
                val raw = conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                val o = try { JSONObject(raw) } catch (_: Exception) { JSONObject() }
                if (code == 502 || code == 503 || code == 529) throw java.io.IOException("server $code ${o.optString("error").take(60)}") // busy for a moment: try again
                throw ClaudeClient.ApiException(error(code, o))
            }
            val got = conn.inputStream.bufferedReader(Charsets.UTF_8).use { Sse.readFull(it, onChars) }
            if (!got.finished) throw Incomplete(tooSlow = System.currentTimeMillis() - started > 100_000)
            return got
        } catch (e: java.net.SocketTimeoutException) {
            throw Incomplete(tooSlow = false)
        } finally {
            conn.disconnect()
        }
    }

    /** A Gemini generateContent request (drawings) through the server: (http code, raw reply). Background thread only. */
    /** [purpose]: what the drawing is for (counted per kind on the server, to see where the allowance goes). */
    fun gemini(ctx: Context, model: String, version: String, body: JSONObject, purpose: String = "draw"): Pair<Int, String> =
        try { post(ctx, JSONObject().put("action", "gemini").put("purpose", purpose).put("model", model).put("version", version).put("body", body)) } catch (_: Exception) { 0 to "" }

    private fun error(code: Int, o: JSONObject): String = when (code) {
        401 -> L.t("צריך להתחבר לחשבון מחדש", "Please sign in again")
        403 -> L.t("הבנת רגעים זמינה רק בחשבון עם מייל או Google", "Moment understanding needs an email or Google account")
        429 -> L.t("המכסה החודשית של המשפחה נגמרה. היא מתחדשת בתחילת החודש.", "Your family's monthly allowance is used up. It renews at the start of the month.")
        503 -> L.t("הבנת רגעים עוד לא הופעלה בשרת", "Moment understanding isn't set up on the server yet")
        else -> L.t("שגיאה מהשרת ($code)", "Server error ($code)") + o.optString("error").takeIf { it.isNotBlank() }?.let { ": ${it.take(120)}" }.orEmpty()
    }

    private fun post(ctx: Context, payload: JSONObject): Pair<Int, String> {
        TestMode.guard()
        val token = Account.token(ctx) ?: throw ClaudeClient.ApiException(L.t("צריך להתחבר לחשבון", "Please sign in"))
        val conn = (URL(Circle.url(ctx) + "/functions/v1/ai").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 120_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $token")
        }
        return try {
            conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            code to (stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: "")
        } finally {
            conn.disconnect()
        }
    }
}

package com.regaim.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.max

/**
 * Optional: Google's Gemini image model redraws each comic panel as a real illustration.
 * Only used when the user adds a Gemini key; otherwise comics use the on-phone comic filter.
 */
object GeminiClient {
    private const val MODEL = "gemini-3.1-flash-image"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("regaim", Context.MODE_PRIVATE)
    /** A personal key (developers only, encrypted on the phone); families draw through the server. */
    fun apiKey(ctx: Context) = AiKeys.get(ctx, "gemini")
    fun setApiKey(ctx: Context, key: String) = AiKeys.set(ctx, "gemini", key)
    fun hasKey(ctx: Context) = apiKey(ctx).length > 20 || AiGate.drawingReady(ctx)

    /** Returns the frame redrawn as a comic illustration, or null if it didn't work. */
    fun illustrate(ctx: Context, frame: Bitmap, scene: String, style: String = ComicStyles.PROMPTS[0]): Bitmap? {
        val key = apiKey(ctx)
        if (key.isEmpty() && !AiGate.drawingReady(ctx)) return null
        val prompt = "Redraw this photo as $style. Keep the same scene, composition, poses " +
            "and expressions. No text, no letters, no speech bubbles, no panel borders. Scene: $scene"
        val body = JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().put("parts", JSONArray()
                .put(JSONObject().put("text", prompt))
                .put(JSONObject().put("inline_data", JSONObject()
                    .put("mime_type", "image/jpeg")
                    .put("data", toJpegBase64(frame)))))))
            put("generationConfig", JSONObject().put("responseModalities", JSONArray().put("TEXT").put("IMAGE")))
        }
        for (version in listOf("v1beta", "v1")) {
            // No personal key (the normal case): through Moments' server.
            val result = if (key.isEmpty()) AiGate.gemini(ctx, MODEL, version, body, "comic_draw") else post("https://generativelanguage.googleapis.com/$version/models/$MODEL:generateContent", key, body)
            if (result.first == 404) continue
            if (result.first !in 200..299) return null
            return parseImage(result.second)
        }
        return null
    }

    /**
     * One scene of a Pixar-style film. [reference] is an already-drawn scene, so the characters keep the same look
     * from scene to scene. Returns null if it didn't work.
     */
    fun pixar(ctx: Context, frame: Bitmap, reference: Bitmap?, who: String): Bitmap? {
        val key = apiKey(ctx)
        if (key.isEmpty() && !AiGate.drawingReady(ctx)) return null
        val prompt = "Redraw the first image as a frame from a modern 3D animated family feature film in the style of Pixar: " +
            "soft cinematic lighting, rich warm colors, expressive big-eyed characters, detailed textures, depth of field. " +
            "Keep the same scene, composition, poses, clothing colors and expressions, and keep each person recognizable " +
            "(hair, age, skin tone, glasses). " + (if (who.isNotBlank()) "People: $who. " else "") +
            (if (reference != null) "The second image is an earlier scene of the same film: draw the same characters with exactly the same character design. " else "") +
            "No text, no letters, no watermark, no borders."
        val parts = JSONArray().put(JSONObject().put("text", prompt))
            .put(JSONObject().put("inline_data", JSONObject().put("mime_type", "image/jpeg").put("data", toJpegBase64(frame))))
        if (reference != null) parts.put(JSONObject().put("inline_data", JSONObject().put("mime_type", "image/jpeg").put("data", toJpegBase64(reference))))
        val body = JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().put("parts", parts)))
            put("generationConfig", JSONObject().put("responseModalities", JSONArray().put("TEXT").put("IMAGE")))
        }
        for (version in listOf("v1beta", "v1")) {
            // No personal key (the normal case): through Moments' server.
            val result = if (key.isEmpty()) AiGate.gemini(ctx, MODEL, version, body, "pixar_draw") else post("https://generativelanguage.googleapis.com/$version/models/$MODEL:generateContent", key, body)
            if (result.first == 404) continue
            if (result.first !in 200..299) return null
            return parseImage(result.second)
        }
        return null
    }

    /**
     * A new picture from [prompt] and reference pictures ([refs], in the order the prompt describes them), for
     * example Pitzki's forest scenes with the child. Same model and the same way through the server as [pixar].
     * Null if it didn't work.
     */
    fun scene(ctx: Context, prompt: String, refs: List<Bitmap>): Bitmap? {
        val key = apiKey(ctx)
        if (key.isEmpty() && !AiGate.drawingReady(ctx)) return null
        val parts = JSONArray().put(JSONObject().put("text", prompt))
        for (r in refs) parts.put(JSONObject().put("inline_data", JSONObject().put("mime_type", "image/jpeg").put("data", toJpegBase64(r))))
        val body = JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().put("parts", parts)))
            put("generationConfig", JSONObject().put("responseModalities", JSONArray().put("TEXT").put("IMAGE")))
        }
        for (version in listOf("v1beta", "v1")) {
            val result = if (key.isEmpty()) AiGate.gemini(ctx, MODEL, version, body, "pitzki_draw") else post("https://generativelanguage.googleapis.com/$version/models/$MODEL:generateContent", key, body)
            if (result.first == 404) continue
            if (result.first !in 200..299) return null
            return try { parseImage(result.second) } catch (_: Exception) { null }
        }
        return null
    }

    private fun post(url: String, key: String, body: JSONObject): Pair<Int, String> {
        TestMode.guard()
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 120_000
            doOutput = true
            setRequestProperty("content-type", "application/json")
            setRequestProperty("x-goog-api-key", key)
        }
        return try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            code to (stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: "")
        } finally {
            conn.disconnect()
        }
    }

    private fun parseImage(raw: String): Bitmap? {
        val parts = JSONObject(raw).optJSONArray("candidates")?.optJSONObject(0)
            ?.optJSONObject("content")?.optJSONArray("parts") ?: return null
        for (i in 0 until parts.length()) {
            val p = parts.optJSONObject(i) ?: continue
            val inline = p.optJSONObject("inlineData") ?: p.optJSONObject("inline_data") ?: continue
            val data = inline.optString("data")
            if (data.isEmpty()) continue
            val bytes = Base64.decode(data, Base64.DEFAULT)
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }
        return null
    }

    private fun toJpegBase64(src: Bitmap): String {
        val scale = 1024f / max(src.width, src.height)
        val bmp = if (scale < 1f) Bitmap.createScaledBitmap(src, (src.width * scale).toInt(), (src.height * scale).toInt(), true) else src
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }
}

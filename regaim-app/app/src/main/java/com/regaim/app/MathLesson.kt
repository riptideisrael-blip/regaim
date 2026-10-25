package com.regaim.app

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import org.json.JSONArray
import java.io.File

/**
 * Pitzki's math lesson, made from real scenes (not pictures pasted on top): each shot is drawn as its first and its
 * last picture (one apple on the table, then two), and the animation goes from one to the other, so the apples are
 * part of the film and the count is right at both ends. Then every half second of the shot the red apples are
 * counted ([AppleSpots]): a moment where an apple appears or vanishes is flagged. First a one-shot test.
 */
object MathLesson {
    const val SHOT = "math_apples"
    const val START = "math_apples_start"
    const val END = "math_apples_end"
    /** Apples in the picture all along: one on the table and one in his hand, then both on the table. */
    const val APPLES = 2

    private const val PITZKI = "The first image is Pitzki, a small forest elf: keep his face, curly chestnut hair, green pointed hat with a leaf, " +
        "leaf-green clothes, brown shoes and proportions exactly the same, in the same high-end soft 3D animated film style. "
    private const val PLACE = "A cozy sunny forest clearing; a small round wooden table in the middle of the picture, close to the viewer, " +
        "its top clearly visible; Pitzki stands behind the table, facing the viewer. Soft warm light. Nothing else red anywhere in the picture " +
        "(no red flowers, no red mushrooms, no red clothes). Exactly one Pitzki, no other characters, no text, no numbers."

    val START_PROMPT = PITZKI + PLACE + " On the table there is exactly ONE big shiny red apple, on the left side of the table. " +
        "Pitzki holds exactly ONE more big shiny red apple up in his right hand, smiling at the viewer. Exactly two apples in the whole picture."
    val END_PROMPT = PITZKI + "The second image is the same scene a moment earlier. Keep the camera, the framing and the zoom EXACTLY the same as in the second image: " +
        "the table at exactly the same place and size in the picture, Pitzki at exactly the same place and size, the same trees behind, the same light. " +
        "Only his arms and the second apple change. " +
        PLACE + " Now there are exactly TWO big shiny red apples side by side on the table (the one from his hand was put down next to the first one), " +
        "and his hands are empty, resting gently on the table edge on both sides of the apples, a delighted smile at the viewer. Exactly two apples in the whole picture."
    const val MOTION = "Pitzki slowly lowers the apple in his hand and gently puts it down on the table next to the other apple, then rests both hands " +
        "calmly on the table edge and smiles at the viewer. His hands stay normal hands with five fingers; no waving, no pointing, no arm gestures. " +
        "The camera does not move, pan or zoom at all. The two apples stay whole and red the whole time: no apple appears, disappears, splits or merges. " +
        "The table, the clearing and the camera stay still. Pitzki's mouth stays in a closed smile."

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("math_lesson", Context.MODE_PRIVATE)

    /** The times where the apples weren't [APPLES] (null: not checked yet). */
    fun problems(ctx: Context): List<LongRange>? {
        val clip = PitzkiStore.sharedClip(ctx, SHOT)
        val raw = prefs(ctx).getString("check2_" + clip.lastModified(), null) ?: return null
        return try { val a = JSONArray(raw); (0 until a.length()).map { a.getJSONArray(it).let { r -> r.getLong(0)..r.getLong(1) } } } catch (_: Exception) { null }
    }

    /** Background thread. Counts the apples every half second of the shot and keeps the result. */
    fun check(ctx: Context): List<LongRange> {
        val clip = PitzkiStore.sharedClip(ctx, SHOT)
        if (!clip.exists()) return emptyList()
        val patches = ArrayList<Pair<Long, List<Int>>>()
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(clip.absolutePath)
            val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            var t = 0L
            while (t < dur) {
                r.getFrameAtTime(t * 1000, MediaMetadataRetriever.OPTION_CLOSEST)?.let { f ->
                    val sw = 240; val sh = f.height * sw / f.width
                    val s = Bitmap.createScaledBitmap(f, sw, sh, true)
                    val px = IntArray(sw * sh); s.getPixels(px, 0, sw, 0, 0, sw, sh)
                    patches.add(t to AppleSpots.redPatches(px, sw, sh))
                    s.recycle(); f.recycle()
                }
                t += 500
            }
        } catch (_: Exception) {} finally { try { r.release() } catch (_: Exception) {} }
        // Counted by size (two apples side by side touch and look like one red patch), one apple's size taken from
        // the first moment, where the apples are apart.
        val single = AppleSpots.singleSize(patches.firstOrNull()?.second.orEmpty(), APPLES)
        val samples = patches.map { (t, p) -> t to AppleSpots.countBySize(p, single) }
        val bad = SceneFlags.wrongCount(samples, APPLES)
        val a = JSONArray(); bad.forEach { a.put(JSONArray().put(it.first).put(it.last + 500)) }
        prefs(ctx).edit().putString("check2_" + clip.lastModified(), a.toString()).apply()
        return bad
    }
}

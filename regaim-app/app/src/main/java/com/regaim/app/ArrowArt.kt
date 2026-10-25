package com.regaim.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

/**
 * The arrows game's pictures: Pitzki walking toward the child, away, to the side (two steps each, so his legs move),
 * jumping, bumping ("oops") and cheering; the things on the stones (rock, mushroom, log, acorn, hedgehog, little
 * house) and the clearing. Drawn once by the admin for every family (Pitzki's own picture as the reference), on
 * chroma-key green; the green is taken off on the phone ([GreenKey]) and kept as a see-through picture.
 */
object ArrowArt {
    val POSES = listOf("arrow_front", "arrow_front_step", "arrow_back", "arrow_back_step", "arrow_side", "arrow_side_step",
        "arrow_jump", "arrow_oops", "arrow_cheer")
    const val THINGS = "arrow_things"
    const val GROUND = "arrow_ground"
    val KEYS: List<String> get() = POSES + THINGS + GROUND
    /** The things' picture, left to right. */
    val THING_ORDER = listOf("rock", "mushroom", "log", "acorn", "hedgehog", "house")

    private const val PITZKI = "The first image is Pitzki, a small forest elf: keep his face, curly chestnut hair, green pointed hat with a leaf, " +
        "leaf-green clothes, brown shoes and proportions exactly the same, in the same high-end soft 3D animated style. " +
        "Draw ONLY Pitzki, his whole body from the tip of his hat to his shoes, centered, about 80 percent of the picture's height, " +
        "on a perfectly flat, even, pure chroma-key green background (#00FF00) from edge to edge: no ground, no floor, no shadow, " +
        "no light spots, no other objects, no text. Pose: "

    fun prompt(key: String): String = when (key) {
        "arrow_front" -> PITZKI + "standing and facing the viewer, arms relaxed, a warm smile."
        "arrow_front_step" -> PITZKI + "walking toward the viewer, in the middle of a step: his right foot forward and lifted a little, his left arm swinging forward, smiling."
        "arrow_back" -> PITZKI + "seen from behind (his back to the viewer: we see the back of his hat and his curls, not his face), walking away from the viewer, in the middle of a step with his left foot forward."
        "arrow_back_step" -> PITZKI + "seen from behind (his back to the viewer: we see the back of his hat and his curls, not his face), walking away from the viewer, in the middle of a step with his right foot forward."
        "arrow_side" -> PITZKI + "seen exactly from the side in profile, walking toward the right edge of the picture, a long stride with the front leg forward and the back leg behind, arms swinging, a happy face in profile."
        "arrow_side_step" -> PITZKI + "seen exactly from the side in profile, walking toward the right edge of the picture, the legs passing each other (both feet close together under him), arms down, a happy face in profile."
        "arrow_jump" -> PITZKI + "jumping high and happily, both feet off the ground and tucked up, arms raised, a delighted open smile, turned three-quarters toward the viewer."
        "arrow_oops" -> PITZKI + "wobbling back after bumping into something, surprised round eyes and a small open smile, arms out to the sides for balance, facing the viewer."
        "arrow_cheer" -> PITZKI + "cheering with both arms up high and a huge smile, facing the viewer."
        THINGS -> "The first image only shows the art style: do NOT draw the elf or any character from it. " +
            "On a perfectly flat, even, pure chroma-key green background (#00FF00) from edge to edge, six separate objects in one straight row " +
            "from left to right, far apart from each other, each one whole and not touching any other, all about the same height: " +
            "a big round mossy grey rock; a big mushroom with a red cap and white dots; a short fallen log lying on its side; a big shiny brown acorn; " +
            "a cute small hedgehog sitting and smiling; a tiny round wooden elf house with a green door and a mossy roof. " +
            "Soft high-end 3D animated style, warm light, no shadows on the background, no ground, no text."
        GROUND -> "The first image only shows the art style: do NOT draw the elf or any character from it. " +
            "A sunny forest clearing seen from a high angle, looking down at about 45 degrees: soft green grass with a few tiny flowers " +
            "covering the lower four fifths of the picture, a row of friendly trees and bushes along the top edge, warm morning light. " +
            "The middle is empty open grass: no path, no stones, no objects, no characters, no text. Soft high-end 3D animated style."
        else -> ""
    }

    // "_v2": cut-outs made before the see-through fix (black boxes around Pitzki and the things) are made again.
    private fun cutFile(ctx: Context, name: String) = File(File(File(ctx.filesDir, "pitzki"), "shared"), "${name}_v2.png")

    /** Background thread. A pose or a thing, see-through (made once from the drawing, then kept), or null. */
    fun picture(ctx: Context, name: String): Bitmap? {
        val cut = cutFile(ctx, name)
        val src = PitzkiStore.sharedScene(ctx, if (name in POSES) name else THINGS)
        if (!src.exists()) return null
        if (cut.exists() && cut.lastModified() >= src.lastModified()) return BitmapFactory.decodeFile(cut.absolutePath)
        // The old cut-out (black box) is removed.
        File(cut.parentFile, "$name.png").delete()
        val bmp = BitmapFactory.decodeFile(src.absolutePath)?.copy(Bitmap.Config.ARGB_8888, true) ?: return null
        // A JPEG has no see-through: without this the keyed-out green came out black (black boxes on the board).
        bmp.setHasAlpha(true)
        val w = bmp.width; val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        GreenKey.key(px)
        bmp.setPixels(px, 0, w, 0, 0, w, h)
        if (name in POSES) {
            val b = GreenKey.bounds(px, w, h) ?: return null
            val out = Bitmap.createBitmap(bmp, b[0], b[1], b[2] - b[0] + 1, b[3] - b[1] + 1)
            save(out, cut)
            return out
        }
        // The things' picture: cut into its six things, each kept on its own.
        val boxes = GreenKey.things(px, w, h, THING_ORDER.size)
        if (boxes.size < THING_ORDER.size) return null
        var asked: Bitmap? = null
        for ((i, b) in boxes.withIndex()) {
            val one = Bitmap.createBitmap(bmp, b[0], b[1], b[2] - b[0] + 1, b[3] - b[1] + 1)
            save(one, cutFile(ctx, "arrow_" + THING_ORDER[i]))
            if ("arrow_" + THING_ORDER[i] == name) asked = one
        }
        return asked
    }

    private fun save(b: Bitmap, f: File) {
        try { f.parentFile?.mkdirs(); f.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) } } catch (_: Exception) {}
    }

    /** All drawn (and on this phone). */
    fun ready(ctx: Context) = KEYS.all { PitzkiStore.sharedScene(ctx, it).exists() }
}

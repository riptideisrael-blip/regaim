package com.regaim.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlin.math.min

/** Profile pictures: memory-safe decoding, a face-friendly square crop (keeps the top of portraits), and name initials. */
object Avatars {
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    /** A square, [px] wide picture from a file, or null. Portraits are cropped from near the top, where faces are. */
    fun photo(path: String, px: Int): Bitmap? {
        if (path.isEmpty()) return null
        val key = "$path@$px"
        cache.get(key)?.let { return it }
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            if (bounds.outWidth <= 0) return null
            var sample = 1
            while (min(bounds.outWidth, bounds.outHeight) / (sample * 2) >= px) sample *= 2
            val src = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
            val side = min(src.width, src.height)
            val x = (src.width - side) / 2
            val y = if (src.height > src.width) ((src.height - side) * 0.22f).toInt() else (src.height - side) / 2
            val square = Bitmap.createBitmap(src, x, y, side, side)
            val out = if (side > px) Bitmap.createScaledBitmap(square, px, px, true) else square
            cache.put(key, out)
            out
        } catch (_: Throwable) {
            null
        }
    }

    // The name initials are in the design system: DS.avatar(context, name).

    fun initial(name: String): String = name.trim().firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "•"

    fun petEmoji(ctx: Context, name: String): String? = Profile.members(ctx).firstOrNull { it.name == name && it.isPet }?.let {
        when (it.petKind) { "dog" -> "🐶"; "cat" -> "🐱"; else -> "🐾" }
    }
}

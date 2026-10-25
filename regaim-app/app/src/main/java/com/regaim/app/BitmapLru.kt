package com.regaim.app

import android.graphics.Bitmap
import android.util.LruCache

/**
 * A picture cache with a size limit, used like a map (cache[key], cache[key] = bmp): when full, the pictures not
 * seen for longest are dropped, so a long book or list can't run the phone out of memory.
 */
class BitmapLru(maxBytes: Int) {
    private val lru = object : LruCache<String, Bitmap>(maxBytes.coerceAtLeast(4 * 1024 * 1024)) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    operator fun get(key: String): Bitmap? = lru.get(key)
    operator fun set(key: String, value: Bitmap) { lru.put(key, value) }
    fun clear() = lru.evictAll()

    companion object {
        /** A share of what the app may use: [part] = 8 is an eighth. */
        fun share(part: Int, cap: Int): Int = minOf(cap.toLong(), Runtime.getRuntime().maxMemory() / part).toInt()
    }
}

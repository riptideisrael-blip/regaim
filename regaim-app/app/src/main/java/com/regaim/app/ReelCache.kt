package com.regaim.app

import org.json.JSONObject

/** The reel signals cache file (pure Kotlin, tested on the computer). */
internal object ReelCache {
    /** A measurement made before "ok" existed counts as real (it was cached as one). */
    fun measured(o: JSONObject) = o.optBoolean("ok", true)

    fun load(f: java.io.File): JSONObject = when (val r = SafeFiles.readObject(f)) {
        is SafeFiles.Read.Ok -> r.value
        else -> JSONObject()
    }

    /** The moment id in a cache key "<id>_<uri hash>_<version>" (ids may contain "_" themselves, e.g. film_123). */
    fun idOf(key: String) = key.substringBeforeLast('_').substringBeforeLast('_')

    /** [now] plus [fresh], keeping only real measurements of moments that still exist. */
    fun merge(now: JSONObject, fresh: JSONObject, alive: Set<String>): JSONObject {
        val out = JSONObject()
        for (src in listOf(now, fresh)) {
            val keys = src.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                if (idOf(k) in alive && measured(src.optJSONObject(k) ?: continue)) out.put(k, src.opt(k))
            }
        }
        return out
    }
}

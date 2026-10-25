package com.regaim.app

/** Is a moment's file inside the app's own folder (no Android here, unit-tested). */
object PrivateMedia {
    fun inside(path: String, dir: String): Boolean {
        if (path.isBlank() || path.startsWith("content://")) return false
        val p = path.removePrefix("file://")
        // No way out of the folder with "..".
        if (p.split('/').any { it == ".." }) return false
        val d = dir.trimEnd('/') + "/"
        return p.startsWith(d)
    }
}

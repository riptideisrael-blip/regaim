package com.regaim.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * Small file rules for data the app keeps (pure Kotlin, tested on the computer):
 *  - a file is written to a temporary copy first and only then put in place, so a crash or a full phone never
 *    leaves half a file behind;
 *  - a file that can't be read is moved aside ("name.bad"), never silently overwritten, so it can be recovered.
 */
object SafeFiles {
    /** Writes [text] into [file] all or nothing. False if it couldn't be written (the old file is left as it was). */
    fun write(file: File, text: String): Boolean {
        val tmp = File(file.parentFile, file.name + ".tmp")
        return try {
            file.parentFile?.mkdirs()
            FileOutputStream(tmp).use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
                out.flush()
                try { out.fd.sync() } catch (_: Exception) { }
            }
            // On Android (Linux) a rename replaces the old file in one step. If it fails, the old file stays.
            tmp.renameTo(file)
        } catch (_: Exception) {
            false
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    /** Same, for a picture or any bytes. */
    fun writeBytes(file: File, bytes: ByteArray): Boolean {
        val tmp = File(file.parentFile, file.name + ".tmp")
        return try {
            FileOutputStream(tmp).use { out -> out.write(bytes); out.flush(); try { out.fd.sync() } catch (_: Exception) { } }
            tmp.renameTo(file)
        } catch (_: Exception) {
            false
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    /** What reading a JSON file found. */
    sealed class Read<out T> {
        /** No file yet: nothing was saved, an empty start is right. */
        object Missing : Read<Nothing>()
        class Ok<T>(val value: T) : Read<T>()
        /** The file is there but couldn't be read; it was moved to [savedAs] (null if even that failed). */
        class Broken(val savedAs: File?) : Read<Nothing>()
    }

    fun readObject(file: File): Read<JSONObject> = read(file) { JSONObject(it) }
    fun readArray(file: File): Read<JSONArray> = read(file) { JSONArray(it) }

    private fun <T> read(file: File, parse: (String) -> T): Read<T> {
        if (!file.exists()) return Read.Missing
        return try {
            Read.Ok(parse(file.readText(Charsets.UTF_8)))
        } catch (_: Exception) {
            Read.Broken(quarantine(file))
        }
    }

    /** Moves an unreadable file aside (keeps the latest 3), so it's not overwritten and can be looked at later. */
    fun quarantine(file: File): File? {
        val dir = file.parentFile ?: return null
        return try {
            val old = dir.listFiles { f -> f.name.startsWith(file.name + ".bad") }.orEmpty().sortedByDescending { it.lastModified() }
            old.drop(2).forEach { it.delete() }
            val to = File(dir, file.name + ".bad" + System.currentTimeMillis())
            if (file.renameTo(to)) to else null
        } catch (_: Exception) { null }
    }
}

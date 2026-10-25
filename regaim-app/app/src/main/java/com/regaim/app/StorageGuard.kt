package com.regaim.app

import android.content.Context
import android.os.Environment

/**
 * Room on the phone before saving a clip. A clip is a few MB to a few tens of MB; below [MIN_FREE] saving could fail
 * halfway or leave the phone itself without room, so the clip isn't saved and the parent is told why.
 */
object StorageGuard {
    const val MIN_FREE = 200L * 1024 * 1024

    /** Pure: is [freeBytes] enough to save a clip? Unknown (negative) counts as enough (never block by mistake). */
    fun enough(freeBytes: Long): Boolean = freeBytes < 0 || freeBytes >= MIN_FREE

    /** Free bytes where clips and the app's files go (the smaller of the two), or -1 if it can't be read. */
    fun free(ctx: Context): Long = try {
        val a = ctx.filesDir.usableSpace
        @Suppress("DEPRECATION")
        val b = try { Environment.getExternalStorageDirectory().usableSpace } catch (_: Exception) { a }
        minOf(a, if (b > 0) b else a)
    } catch (_: Exception) { -1L }

    fun message() = L.t(
        "אין מספיק מקום פנוי בטלפון, ולכן הרגע לא נשמר. פנו מקום (תמונות, סרטונים, אפליקציות) כדי שהצילום ימשיך לשמור רגעים.",
        "There's not enough free space on the phone, so the moment wasn't saved. Free up some space so capturing can keep saving moments.",
    )
}

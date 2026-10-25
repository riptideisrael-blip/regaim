package com.regaim.app

/** The server's storage, in words (no Android here, unit-tested). */
object StorageMeter {
    const val FREE_PLAN = 1L shl 30

    fun percent(used: Long, of: Long): Int = if (of <= 0) 0 else ((used * 100) / of).toInt().coerceAtLeast(0)

    fun mb(bytes: Long): String = if (bytes >= 1L shl 30) "%.2f GB".format(java.util.Locale.US, bytes / 1073741824.0)
        else "%.0f MB".format(java.util.Locale.US, bytes / 1048576.0)

    fun folderName(id: String) = when (id) {
        "moments" -> L.t("זיכרונות משותפים של המשפחות", "Families' shared memories")
        "pitzki-shared" -> L.t("הסצנות של פיצקי", "Pitzki's scenes")
        "app-updates" -> L.t("גרסאות האפליקציה", "App versions")
        "community" -> L.t("הקהילה", "Community")
        "print" -> L.t("ספרים להדפסה", "Books for print")
        "build-secrets" -> L.t("מפתח החתימה", "Signing key")
        else -> id
    }
}

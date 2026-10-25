package com.regaim.app

/** A bounded video preview and the evidence used to choose it. */
data class PreviewWindow(
    val startMs: Long,
    val endMs: Long,
    val source: String,
) {
    val isSelected: Boolean get() = source == "user"
    val isFallback: Boolean get() = source == "fallback"

    val label: String get() = when (source) {
        "user" -> L.t("קטע שבחרתם", "Your selected clip")
        "trigger" -> L.t("סביב רגע השמירה", "Around the saved moment")
        "ai" -> L.t("הרגע, לפי 8 תמונות", "The moment, from 8 pictures")
        else -> L.t("תמונה מתוך הזיכרון", "A still from this memory")
    }
}

/**
 * Chooses a calm preview without claiming an AI-selected highlight.
 * User selections are trusted only when their complete interval is valid.
 * A capture marker identifies a save trigger, not the video's best moment.
 */
object HighlightPolicy {
    private const val DEFAULT_PREVIEW_MS = 4_000L
    private const val MIN_SELECTED_PREVIEW_MS = 1_000L
    private const val MAX_SELECTED_PREVIEW_MS = 6_000L

    fun window(
        isVideo: Boolean,
        durationMs: Long,
        markerMs: Long,
        startMs: Long,
        endMs: Long,
        source: String,
    ): PreviewWindow? {
        if (!isVideo || durationMs <= 0L) return null

        if (source == "user" && startMs >= 0L && endMs > startMs && endMs <= durationMs) {
            val length = endMs - startMs
            if (length in MIN_SELECTED_PREVIEW_MS..MAX_SELECTED_PREVIEW_MS) {
                return PreviewWindow(startMs, endMs, "user")
            }
        }

        // The extended look found the moment with confidence (8 pictures, at most 8 seconds).
        if (source == "ai" && startMs >= 0L && endMs > startMs && endMs <= durationMs && endMs - startMs in MIN_SELECTED_PREVIEW_MS..8_000L) {
            return PreviewWindow(startMs, endMs, "ai")
        }

        val span = minOf(DEFAULT_PREVIEW_MS, durationMs)
        val hasMarker = markerMs in 0L..durationMs
        val center = if (hasMarker) markerMs else durationMs / 2L
        val start = (center - span / 2L).coerceIn(0L, durationMs - span)
        return PreviewWindow(start, start + span, if (hasMarker) "trigger" else "fallback")
    }
}

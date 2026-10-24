package com.regaim.app

/**
 * The guided photo of the child for Pitzki's film: three pictures (from the front, then turned a little to each side,
 * so the drawing doesn't have to guess), each taken by itself once the face is right. What's checked on every frame,
 * and the one short hint the parent sees. No Android here (unit-tested).
 */
object FaceGuide {
    /** The three pictures: from the front, then a little to one side, then to the other. */
    enum class Step { FRONT, SIDE_A, SIDE_B }

    /**
     * One frame's face, measured: its [yaw] (degrees, + or − by the side it turns to), its size and center in the
     * frame (0..1), how open the eyes are (null: unknown), the face's brightness (0..255) and how far it moved since
     * the frame before (0..1 of the frame's width).
     */
    data class Face(val yaw: Float, val width: Float, val cx: Float, val cy: Float, val eyes: Float?, val light: Int, val moved: Float)

    /** What the frame is missing (a hint to show), or [READY]. */
    const val READY = ""

    /** The side the first side picture turned to (the other one goes the other way); 0 before it. */
    fun check(step: Step, faces: Int, f: Face?, firstSide: Float = 0f): String = when {
        faces == 0 || f == null -> L.t("מכוונים את המצלמה לפנים של הילד", "Point the camera at the child's face")
        faces > 1 -> L.t("רק ילד אחד בתמונה", "Only one child in the picture")
        f.width < 0.28f -> L.t("קרבו קצת את המצלמה", "A little closer")
        f.width > 0.75f -> L.t("קצת יותר רחוק", "A little further away")
        kotlin.math.abs(f.cx - 0.5f) > 0.18f || kotlin.math.abs(f.cy - 0.45f) > 0.22f -> L.t("הפנים באמצע העיגול", "The face in the middle of the circle")
        f.light < 70 -> L.t("חשוך מדי: עוברים למקום מואר", "Too dark: move somewhere brighter")
        f.light > 230 -> L.t("יותר מדי אור מאחור או מלמעלה", "Too much light")
        step == Step.FRONT && kotlin.math.abs(f.yaw) > 12f -> L.t("שהילד יסתכל ישר למצלמה", "The child looks straight at the camera")
        step != Step.FRONT && kotlin.math.abs(f.yaw) < 14f -> L.t("שהילד יסובב קצת את הראש לצד", "The child turns the head a little to the side")
        step != Step.FRONT && kotlin.math.abs(f.yaw) > 40f -> L.t("פחות סיבוב: רק קצת לצד", "Less: just a little to the side")
        step == Step.SIDE_B && firstSide != 0f && kotlin.math.sign(f.yaw) == kotlin.math.sign(firstSide) -> L.t("עכשיו לצד השני", "Now to the other side")
        f.eyes != null && f.eyes < 0.45f -> L.t("עיניים פתוחות 🙂", "Eyes open 🙂")
        f.moved > 0.04f -> L.t("רגע בלי לזוז…", "Hold still a moment…")
        else -> READY
    }

    /** What to ask the child for at each step (said by the parent). */
    fun ask(step: Step): String = when (step) {
        Step.FRONT -> L.t("1 מתוך 3: מסתכלים ישר למצלמה", "1 of 3: looking straight at the camera")
        Step.SIDE_A -> L.t("2 מתוך 3: מסובבים קצת את הראש לצד", "2 of 3: turning the head a little to the side")
        Step.SIDE_B -> L.t("3 מתוך 3: ועכשיו קצת לצד השני", "3 of 3: and a little to the other side")
    }

    /**
     * The part of the frame kept: a square around the face, big enough for the hair, the ears and the neck (the
     * drawing needs them), kept inside the frame. Returns left, top, side (in the frame's pixels).
     */
    fun crop(fl: Float, ft: Float, fw: Float, fh: Float, w: Int, h: Int): Triple<Int, Int, Int> {
        val side = (maxOf(fw, fh) * 2.2f).toInt().coerceAtMost(minOf(w, h)).coerceAtLeast(1)
        val cx = fl + fw / 2f; val cy = ft + fh / 2f + fh * 0.12f
        val left = (cx - side / 2f).toInt().coerceIn(0, w - side)
        val top = (cy - side / 2f).toInt().coerceIn(0, h - side)
        return Triple(left, top, side)
    }

    /** How sharp a gray picture is (the variance of its edges): the sharpest of a few frames is kept. */
    fun sharpness(gray: IntArray, w: Int, h: Int): Double {
        if (w < 3 || h < 3) return 0.0
        var sum = 0.0; var sum2 = 0.0; var n = 0
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            val lap = (4 * gray[i] - gray[i - 1] - gray[i + 1] - gray[i - w] - gray[i + w]).toDouble()
            sum += lap; sum2 += lap * lap; n++
        }
        val mean = sum / n
        return sum2 / n - mean * mean
    }
}

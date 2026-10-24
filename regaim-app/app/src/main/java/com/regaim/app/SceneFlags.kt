package com.regaim.app

/** [SceneScan]'s reading of the face counts, with no Android in it (unit-tested). */
object SceneFlags {
    /** Pure: the times (ms) where more than [expected] faces were seen in two samples in a row (or more). */
    fun flagged(samples: List<Pair<Long, Int>>, expected: Int): List<LongRange> {
        val out = ArrayList<LongRange>()
        var start = -1L; var last = -1L; var run = 0
        fun close() { if (run >= 2) out.add(start..last); start = -1L; run = 0 }
        for ((t, n) in samples.sortedBy { it.first }) {
            if (n > expected) { if (run == 0) start = t; last = t; run++ } else close()
        }
        close()
        return out
    }

    /** Pure: the times (ms) where the count wasn't [expected] in two samples in a row (or more): an apple appeared or vanished. */
    fun wrongCount(samples: List<Pair<Long, Int>>, expected: Int): List<LongRange> =
        flagged(samples.map { (t, n) -> t to if (n != expected) 1 else 0 }, 0)

    /** Pure: the times where the count was outside [allowed] in two samples in a row (or more). */
    fun outside(samples: List<Pair<Long, Int>>, allowed: Set<Int>): List<LongRange> =
        flagged(samples.map { (t, n) -> t to if (n in allowed) 0 else 1 }, 0)

    /** "0:06" */
    fun time(ms: Long): String = "${ms / 60_000}:${"%02d".format((ms / 1000) % 60)}"

}

package com.regaim.app

import kotlin.math.sqrt

/**
 * Pure math on face prints (no Android), so it can be tested on any computer.
 * A face print is a length-1 vector; two prints of the same person are close (distance near 0, at most 2).
 */
object FacePrints {
    /** "Not comparable": farther than any two real prints can be, so it never matches anyone. */
    const val FAR = 9f

    fun valid(a: FloatArray): Boolean = a.isNotEmpty() && a.all { it.isFinite() }

    /**
     * Distance between two prints. A damaged print (empty, wrong length, NaN) used to compare as 0 = "the same
     * person as everyone"; now it is [FAR] and never matches.
     */
    fun dist(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size || a.isEmpty()) return FAR
        var s = 0f
        for (i in a.indices) {
            val d = a[i] - b[i]
            s += d * d
        }
        return if (s.isFinite()) sqrt(s) else FAR
    }

    /** Length 1, or null when the model gave nothing usable. */
    fun normalize(v: FloatArray): FloatArray? {
        if (!valid(v)) return null
        var n = 0f
        for (x in v) n += x * x
        n = sqrt(n)
        if (!n.isFinite() || n < 1e-6f) return null
        return FloatArray(v.size) { v[it] / n }
    }

    /**
     * The [n] most typical prints of a group (smallest median distance to the rest of the group). When the
     * family names a "Who's this?" group, these are the ones the person is remembered by, so one odd face in the
     * group (a sibling that slipped in, a blurry frame) doesn't teach the app the wrong face.
     */
    fun typical(prints: List<FloatArray>, n: Int): List<FloatArray> {
        val ok = prints.filter { valid(it) }
        // Three: one can be told apart only when the other two clearly agree and it is clearly far from both
        // (a sibling or a blur that slipped into the group). Anything less clear keeps all three.
        if (ok.size == 3) {
            for (i in 0 until 3) {
                val a = ok[(i + 1) % 3]; val b = ok[(i + 2) % 3]; val odd = ok[i]
                val pair = dist(a, b)
                val away = minOf(dist(odd, a), dist(odd, b))
                if (pair < ODD_PAIR && away > ODD_AWAY && away > pair * 1.8f) return listOf(a, b).take(n)
            }
            return ok.take(n)
        }
        if (ok.size < 3) return ok.take(n) // two: no way to tell which one is odd
        val scored = ok.map { p ->
            val ds = ok.filter { it !== p }.map { dist(p, it) }.sorted()
            p to ds[ds.size / 2]
        }
        // A print far from the rest of its group (beyond the usual spread) is left out even if there's room.
        val medians = scored.map { it.second }.sorted()
        val usual = medians[medians.size / 2]
        return scored.filter { it.second <= usual * 1.35f + 0.02f }.sortedBy { it.second }.take(n).map { it.first }
    }

    /**
     * Samples from every age: the newest [keep] stay as they are (the child as they look now); older ones that make
     * room move to [past], thinned evenly across time to at most [keepPast], so earlier years are still recognized
     * (old photos imported later) without old faces outweighing the current one. Returns (recent, past).
     */
    fun roll(recent: List<FloatArray>, past: List<FloatArray>, add: List<FloatArray>, keep: Int = 15, keepPast: Int = 8): Pair<List<FloatArray>, List<FloatArray>> {
        val all = recent + add
        if (all.size <= keep) return all to past
        val older = past + all.dropLast(keep)
        val thinned = if (older.size <= keepPast) older
            else (0 until keepPast).map { older[it * (older.size - 1) / (keepPast - 1)] }
        return all.takeLast(keep) to thinned
    }

    /** For [typical] with three faces: the two that agree are this close, the odd one is at least this far. */
    const val ODD_PAIR = 0.75f
    const val ODD_AWAY = 0.95f

    /**
     * Who a print belongs to, among [people] (id, samples, "not them" samples): the closest, only when closer than
     * [match] and clearly closer than the second best ([margin]); null = not sure. The same rule the app names faces by.
     */
    fun bestMatch(print: FloatArray, people: List<Triple<String, List<FloatArray>, List<FloatArray>>>, match: Float, margin: Float): String? {
        var best: String? = null
        var bestD = Float.MAX_VALUE
        var second = Float.MAX_VALUE
        for ((id, samples, rejects) in people) {
            val d = samples.minOfOrNull { dist(print, it) } ?: continue
            val r = rejects.minOfOrNull { dist(print, it) }
            if (r != null && r < d) continue
            if (d < bestD) { second = bestD; bestD = d; best = id } else if (d < second) second = d
        }
        if (best == null || bestD >= match) return null
        if (bestD > 0.05f && second - bestD < margin) return null
        return best
    }

    /** Why a face has no name (for the lab), checked by the same rule faces are named by. */
    enum class Unnamed { NO_PEOPLE, SMALL, NOT_THEM, TIE, FAR_NEAR, FAR, WOULD_MATCH, NO_EYES }

    /** A face "almost" close enough: within this much beyond the naming distance (would a small change help?). */
    const val NEAR = 0.08f

    /**
     * Pure: why the face with [print] (its smaller side [side] pixels) isn't named. WOULD_MATCH = it would be named
     * now (it was scanned before the app knew enough about that person).
     */
    fun whyUnnamed(print: FloatArray, side: Int, minSide: Int, people: List<Triple<String, List<FloatArray>, List<FloatArray>>>, match: Float, margin: Float): Unnamed {
        if (people.none { it.second.isNotEmpty() }) return Unnamed.NO_PEOPLE
        if (side in 1 until minSide) return Unnamed.SMALL
        if (bestMatch(print, people, match, margin) != null) return Unnamed.WOULD_MATCH
        var closestAny = Float.MAX_VALUE; var closestRejected = false
        var best = Float.MAX_VALUE
        for ((_, samples, rejects) in people) {
            val d = samples.minOfOrNull { dist(print, it) } ?: continue
            val r = rejects.minOfOrNull { dist(print, it) }
            val rejected = r != null && r < d
            if (d < closestAny) { closestAny = d; closestRejected = rejected }
            if (!rejected && d < best) best = d
        }
        return when {
            closestRejected && closestAny < match -> Unnamed.NOT_THEM
            best < match -> Unnamed.TIE
            best < match + NEAR -> Unnamed.FAR_NEAR
            else -> Unnamed.FAR
        }
    }

    /**
     * Pure: a group of unnamed faces that the app may name by itself, without asking: at least 2 faces, and every
     * one of them is clearly ([margin]) the same person within [strong] (looser than naming one face alone, because
     * several faces agreeing is stronger evidence). Null = ask the parent.
     */
    fun groupVote(prints: List<FloatArray>, people: List<Triple<String, List<FloatArray>, List<FloatArray>>>, strong: Float, margin: Float): String? {
        if (prints.size < 2) return null
        var who: String? = null
        for (p in prints) {
            val v = bestMatch(p, people, strong, margin) ?: return null
            if (who != null && v != who) return null
            who = v
        }
        return who
    }

    /** Lowercase hex of bytes (for the model's SHA-256). */
    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

/**
 * When a moment couldn't be scanned for faces (the clip isn't on the phone yet, the phone ran out of memory...),
 * it's tried again on later runs instead of being skipped forever. It's given up on only after [MAX] failed runs.
 */
object ScanTries {
    const val MAX = 3

    /**
     * Why a moment couldn't be scanned: its file is gone (no point trying again), it couldn't be decoded (a few
     * more tries), or it's temporary (not downloaded from Google Photos yet, no memory, no connection: worth
     * trying on later days).
     */
    enum class Why(val key: String, val limit: Int) { MISSING("missing", 1), DECODE("decode", MAX), TEMPORARY("temporary", 10) }

    fun why(key: String?): Why = Why.values().firstOrNull { it.key == key } ?: Why.DECODE

    /** [error]: the failure's class and message; [exists]: whether the file is still there (null = can't tell). */
    fun classify(error: String, exists: Boolean?): Why {
        val e = error.lowercase()
        return when {
            exists == false -> Why.MISSING
            "filenotfound" in e || "enoent" in e || "no such file" in e -> Why.MISSING
            "outofmemory" in e || "timeout" in e || "socket" in e || "unknownhost" in e || "interrupted" in e ||
                "ioexception" in e || "pending" in e || "not downloaded" in e -> Why.TEMPORARY
            else -> Why.DECODE
        }
    }

    fun giveUp(failedRuns: Int, why: Why = Why.DECODE): Boolean = failedRuns >= why.limit

    /** Which moments to scan now: not tried in this run yet, not given up on (by why they failed), fewest failures first, then newest. */
    fun order(ids: List<Pair<String, Long>>, tries: Map<String, Int>, triedThisRun: Set<String>, whys: Map<String, Why> = emptyMap()): List<String> =
        ids.filter { it.first !in triedThisRun && !giveUp(tries[it.first] ?: 0, whys[it.first] ?: Why.DECODE) }
            .sortedWith(compareBy<Pair<String, Long>> { tries[it.first] ?: 0 }.thenByDescending { it.second })
            .map { it.first }
}

/**
 * Measuring face recognition on the family's own confirmed faces (leave-one-out): each face the parent named is
 * hidden from what the app knows, and the app is asked who it is. Nothing leaves the phone.
 */
object FaceBench {
    /** One confirmed face: who it really is, its print. */
    class Sample(val personId: String, val print: FloatArray)

    class PersonResult(val id: String, var faces: Int = 0, var right: Int = 0, var unsure: Int = 0, var wrongAs: Int = 0, var takenFrom: Int = 0) {
        /** Of the faces named as this person, how many really were. */
        val precision: Float get() = if (right + takenFrom == 0) 0f else right.toFloat() / (right + takenFrom)
        /** Of this person's faces, how many were named right. */
        val recall: Float get() = if (faces == 0) 0f else right.toFloat() / faces
    }

    class Result(val people: Map<String, PersonResult>, val confusion: Map<Pair<String, String>, Int>, val total: Int, val right: Int, val unsure: Int, val wrong: Int) {
        /** Faces named as the wrong person, of all faces tried. */
        val falseMatchRate: Float get() = if (total == 0) 0f else wrong.toFloat() / total
        val recall: Float get() = if (total == 0) 0f else right.toFloat() / total
        val precision: Float get() = if (right + wrong == 0) 0f else right.toFloat() / (right + wrong)
    }

    /**
     * [people]: (id, samples, "not them" samples) as the app knows them. For each sample, any sample of its own
     * person that is (almost) the same print is left out first, so a face is never recognized by itself.
     */
    fun evaluate(samples: List<Sample>, people: List<Triple<String, List<FloatArray>, List<FloatArray>>>, match: Float, margin: Float): Result {
        val per = HashMap<String, PersonResult>()
        val confusion = HashMap<Pair<String, String>, Int>()
        var right = 0; var unsure = 0; var wrong = 0
        for (s in samples) {
            val known = people.map { (id, prints, rejects) ->
                Triple(id, if (id == s.personId) prints.filter { FacePrints.dist(it, s.print) > 0.05f } else prints, rejects)
            }
            val got = FacePrints.bestMatch(s.print, known, match, margin)
            val me = per.getOrPut(s.personId) { PersonResult(s.personId) }
            me.faces++
            when (got) {
                s.personId -> { me.right++; right++ }
                null -> { me.unsure++; unsure++ }
                else -> {
                    me.wrongAs++; wrong++
                    per.getOrPut(got) { PersonResult(got) }.takenFrom++
                    confusion[s.personId to got] = (confusion[s.personId to got] ?: 0) + 1
                }
            }
        }
        return Result(per, confusion, samples.size, right, unsure, wrong)
    }
}

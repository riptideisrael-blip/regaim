package com.regaim.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class FaceGroupsTest {
    private val rnd = Random(7)
    private fun unit(v: FloatArray): FloatArray { val n = kotlin.math.sqrt(v.sumOf { (it * it).toDouble() }).toFloat(); return FloatArray(v.size) { v[it] / n } }
    private fun near(c: FloatArray, noise: Float) = unit(FloatArray(c.size) { c[it] + (rnd.nextFloat() - 0.5f) * noise })
    private val dim = 32
    private val uri = unit(FloatArray(dim) { rnd.nextFloat() - 0.5f })
    // A sibling: similar, but a clearly different face.
    private val noam = unit(FloatArray(dim) { uri[it] + (rnd.nextFloat() - 0.5f) * 0.9f })
    private val anchor = near(uri, 0.25f)
    private fun cand(k: String, p: FloatArray) = FaceGroups.Cand(k, p, FaceGroups.dist(p, anchor))

    @Test fun trustedDropsASiblingLearnedByMistake() {
        // Uri's remembered faces include two of Noam (a wrong name in the past).
        val learned = (1..6).map { near(uri, 0.3f) } + (1..2).map { near(noam, 0.3f) }
        val noamFaces = learned.takeLast(2)
        // With the profile photo: Noam's faces are dropped.
        val withProfile = FaceGroups.trusted(learned, anchor, emptyList())
        assertTrue(withProfile.none { t -> noamFaces.any { it === t } })
        assertTrue(withProfile.size >= 5)
        // Without a profile photo, after the parent rejected one of Noam's faces: dropped too.
        val afterReject = FaceGroups.trusted(learned, null, listOf(near(noam, 0.2f)))
        assertTrue(afterReject.none { t -> noamFaces.any { it === t } })
        assertTrue(afterReject.isNotEmpty())
    }

    @Test fun anOldProfilePhotoDoesNotLead() {
        // A baby photo that doesn't look like the child today: the remembered faces are used instead of it.
        val baby = unit(FloatArray(dim) { rnd.nextFloat() - 0.5f })
        val learned = (1..6).map { near(uri, 0.3f) }
        val t = FaceGroups.trusted(learned, baby, emptyList())
        assertTrue(t.none { it === baby })
        assertTrue(t.size >= 5)
    }

    private fun seen(k: String, p: FloatArray, other: Float, reject: Float = 9f, named: Boolean = false, sharp: Float = 0.8f) =
        BookPick.Seen(FaceQuality.Stats(k, k, 320, sharp, 140f, 0.02f, 3f, 2f, 0.9f, FaceGroups.dist(p, anchor), 20, false),
            p, FaceGroups.dist(p, anchor), other, reject, named)

    @Test fun strictWhenThereIsEnough() {
        val sure = (1..4).map { seen("u$it", near(uri, 0.2f), other = 1.2f) }
        val unsure = (1..2).map { seen("x$it", near(uri, 0.2f), other = 0.01f + FaceGroups.dist(near(uri, 0.2f), anchor)) }
        val got = BookPick.choose(sure + unsure, emptyList(), 8)
        assertTrue(got.size >= 3)
        assertTrue(got.all { it.st.key.startsWith("u") })
    }

    @Test fun looserRatherThanNothing() {
        // Every face is only a little closer to the child than to the nearest other person: strict finds none.
        val faces = (1..4).map { k -> near(uri, 0.2f).let { p -> seen("u$k", p, other = FaceGroups.dist(p, anchor) + 0.04f) } }
        val got = BookPick.choose(faces, emptyList(), 8)
        assertTrue(got.size >= 3)
    }

    @Test fun theFamilysTagsWhenRecognitionIsUnsure() {
        // Recognition is far from sure about every face, but the moments are tagged with the child.
        val faces = (1..5).map { k ->
            val p = unit(FloatArray(dim) { rnd.nextFloat() - 0.5f })
            BookPick.Seen(FaceQuality.Stats("t$k", "t$k", 320, 0.8f, 140f, 0.02f, 3f, 2f, 0.9f, 0.8f, 20, false), p, 1.1f, 1.0f, 9f, false, tagged = true)
        }
        assertTrue(faces.all { BookPick.maybe(it) })
        assertEquals(5, BookPick.choose(faces, emptyList(), 8).size)
    }

    @Test fun neverAFaceCloserToARejectedOne() {
        val faces = (1..4).map { k -> near(uri, 0.2f).let { p -> seen("u$k", p, other = 1.2f, reject = FaceGroups.dist(p, anchor) - 0.01f) } }
        assertTrue(BookPick.choose(faces.filter { BookPick.maybe(it) }, emptyList(), 8).isEmpty())
    }

    @Test fun keepsOnlyTheChildWhenASiblingSlipsIn() {
        val c = (1..5).map { cand("u$it", near(uri, 0.3f)) } + (1..3).map { cand("n$it", near(noam, 0.3f)) }
        val kept = FaceGroups.sameChild(c)
        assertTrue(kept.isNotEmpty())
        assertTrue(kept.all { it.key.startsWith("u") })
        assertEquals(5, kept.size)
    }

    @Test fun oneChildStaysWhole() {
        val c = (1..6).map { cand("u$it", near(uri, 0.3f)) }
        assertEquals(6, FaceGroups.sameChild(c).size)
    }

    @Test fun aRejectedFaceTakesItsLookAlikesButNotTheChild() {
        val n1 = near(noam, 0.3f)
        val c = (1..4).map { cand("u$it", near(uri, 0.3f)) } + (1..2).map { cand("n$it", near(n1, 0.1f)) }
        val left = FaceGroups.notRejected(c, listOf(n1))
        assertTrue(left.none { it.key.startsWith("n") })
        assertEquals(4, left.size)
    }
}

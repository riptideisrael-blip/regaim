package com.regaim.app

import android.content.Context
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions

/**
 * Pets from the profile (a dog, a cat...) found in moments. On-device image labels tell us "a dog is here";
 * the name is added only when the profile has exactly one pet of that kind, so we never guess between two dogs.
 */
object Pets {
    private val DOG = setOf("dog", "puppy")
    private val CAT = setOf("cat", "kitten")
    private val ANY = setOf("pet", "animal", "mammal")
    private val scanning = java.util.concurrent.atomic.AtomicBoolean(false)

    fun hasPets(ctx: Context) = Profile.members(ctx).any { it.isPet }

    /** Scans moments not yet checked for pets. Background thread only. */
    fun scanPending(ctx: Context, limit: Int = 8): Int {
        if (!hasPets(ctx)) return 0
        if (!scanning.compareAndSet(false, true)) return 0
        try {
            var n = 0
            for (m in MemoryStore.all(ctx).filter { !it.petsScanned }.take(limit)) {
                scan(ctx, m)
                n++
            }
            return n
        } finally {
            scanning.set(false)
        }
    }

    fun scan(ctx: Context, m: Memory) {
        val pets = Profile.members(ctx).filter { it.isPet }
        if (pets.isEmpty()) return
        val labeler = ImageLabeling.getClient(ImageLabelerOptions.Builder().setConfidenceThreshold(0.55f).build())
        var dogHits = 0; var catHits = 0; var anyHits = 0
        var dogStrong = false; var catStrong = false; var anyStrong = false
        try {
            for (frame in ComicMaker.framesFromClip(ctx, m, 4)) {
                val labels = try { Tasks.await(labeler.process(InputImage.fromBitmap(frame, 0))) } catch (_: Exception) { emptyList() }
                var d = false; var c = false; var a = false
                for (l in labels) {
                    val t = l.text.lowercase()
                    if (t in DOG) { d = true; if (l.confidence >= 0.7f) dogStrong = true }
                    if (t in CAT) { c = true; if (l.confidence >= 0.7f) catStrong = true }
                    if (t in ANY) { a = true; if (l.confidence >= 0.7f) anyStrong = true }
                }
                if (d) dogHits++
                if (c) catHits++
                if (a) anyHits++
            }
        } finally {
            labeler.close()
        }
        // A strong sighting in one frame, or a weaker one in two frames.
        val dog = dogStrong || dogHits >= 2
        val cat = catStrong || catHits >= 2
        val any = anyStrong || anyHits >= 2
        val found = mutableListOf<String>()
        fun only(kind: String): String? = pets.filter { it.petKind == kind }.singleOrNull()?.name
        if (dog) only("dog")?.let { found.add(it) }
        if (cat) only("cat")?.let { found.add(it) }
        // A generic "pet" (rabbit, bird...) matches only when it is the profile's single pet of that kind.
        if (any && !dog && !cat) only("pet")?.let { found.add(it) }
        val joined = found.distinct().joinToString(", ")
        val fresh = MemoryStore.get(ctx, m.id) ?: return
        val again = joined.isNotEmpty() && joined != fresh.pets && !fresh.descriptionByUser
        MemoryStore.update(ctx, fresh.copy(pets = joined, petsScanned = true, analyzed = if (again) false else fresh.analyzed))
    }

    /** The profile's pets changed: every moment will be checked again (in the background, a few at a time). */
    fun invalidate(ctx: Context) {
        val ids = MemoryStore.all(ctx).filter { it.petsScanned || it.pets.isNotEmpty() }.map { it.id }.toSet()
        MemoryStore.updateMany(ctx, ids) { it.copy(pets = "", petsScanned = false) }
    }

    /** Looks again at one moment (after adding a pet to the profile, or from the "scan again" button). */
    fun rescan(ctx: Context, m: Memory) {
        MemoryStore.get(ctx, m.id)?.let { scan(ctx, it) }
    }
}

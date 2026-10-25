package com.regaim.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryAnalysisMergeTest {
    private fun memory() = Memory(
        id = "memory-1",
        uri = "content://memories/original",
        name = "original.mp4",
        time = 1_700_000_000_000L,
        label = "manual",
        lat = null,
        lon = null,
        place = "",
        thumb = "original-thumb.jpg",
        durationMs = 60_000L,
    )

    @Test fun userChoicesMadeWhileAnalysisRunsSurviveTheStaleResult() {
        val snapshot = memory()
        val fresh = snapshot.copy(
            favorite = true,
            chosen = true,
            noLearn = true,
            highlightMs = 38_200L,
            highlightStartMs = 38_200L,
            highlightEndMs = 43_700L,
            highlightSource = "user",
        )
        // The network request started before the user's changes and returns its old snapshot.
        val staleResult = snapshot.copy(title = "Analyzed title", reason = "A shared moment", score = 7)

        val merged = MemoryStore.mergeAnalysis(snapshot, fresh, staleResult)

        assertEquals(fresh.favorite, merged.favorite)
        assertEquals(fresh.chosen, merged.chosen)
        assertEquals(fresh.noLearn, merged.noLearn)
        assertEquals(fresh.highlightMs, merged.highlightMs)
        assertEquals(fresh.highlightStartMs, merged.highlightStartMs)
        assertEquals(fresh.highlightEndMs, merged.highlightEndMs)
        assertEquals(fresh.highlightSource, merged.highlightSource)
        assertEquals(PreviewWindow(38_200L, 43_700L, "user"), merged.previewWindow)
        assertEquals("Analyzed title", merged.title)
        assertTrue(merged.analyzed)
    }

    @Test fun changedIdentityContextRejectsTheStaleNarrativeAndRequestsRetry() {
        val snapshot = memory().copy(
            people = "known person, mistaken person",
            title = "Current title",
            description = "Current description",
            tags = "current tags",
            category = "Current category",
            milestone = "Current milestone",
            reason = "Current reason",
            score = 2,
        )
        val staleResult = snapshot.copy(
            title = "Stale AI title",
            description = "Stale AI description about the old context",
            descriptionSource = "sampled_images",
            tags = "stale tags",
            category = "Stale category",
            milestone = "Stale milestone",
            reason = "Stale reason",
            score = 9,
            analyzed = true,
        )
        val changedContexts = listOf(
            snapshot.copy(uri = "content://memories/changed"),
            snapshot.copy(mediaType = "image"),
            snapshot.copy(people = "newly recognized person"),
            snapshot.copy(extraPeople = "newly named person"),
            snapshot.copy(hiddenPeople = "mistaken person"),
            snapshot.copy(place = "Corrected place"),
            snapshot.copy(time = snapshot.time + 60_000L),
            snapshot.copy(timeSource = "imported"),
        )

        for (changed in changedContexts) {
            // Even a previously completed flag must be reset when its evidence became stale.
            val fresh = changed.copy(analyzed = true)
            val merged = MemoryStore.mergeAnalysis(snapshot, fresh, staleResult)

            assertEquals(fresh.copy(analyzed = false), merged)
            assertEquals(fresh.allPeople, merged.allPeople)
            assertFalse(merged.analyzed)
        }
    }

    @Test fun changedContextKeepsACompleteUserAuthoredDescription() {
        val snapshot = memory()
        val fresh = snapshot.copy(
            extraPeople = "Corrected participant",
            description = "The family's own description",
            descriptionByUser = true,
            descriptionSource = "user",
        )
        val staleResult = snapshot.copy(description = "AI's description of the old context")

        val merged = MemoryStore.mergeAnalysis(snapshot, fresh, staleResult)

        assertEquals(fresh.copy(analyzed = true), merged)
        assertTrue(merged.analyzed)
    }

    @Test fun narrativeCorrectionsMadeDuringAnalysisAreNeverReplaced() {
        val snapshot = memory().copy(
            title = "Old title",
            description = "Old description",
            tags = "old tags",
            category = "Old category",
            milestone = "Old milestone",
        )
        val fresh = snapshot.copy(
            title = "User's corrected title",
            description = "User's corrected description",
            tags = "corrected tags",
            category = "Corrected category",
            milestone = "Corrected milestone",
            reason = "User's corrected reason",
            descriptionSource = "user",
        )
        val staleResult = snapshot.copy(
            title = "AI title",
            description = "AI description",
            tags = "AI tags",
            category = "AI category",
            milestone = "AI milestone",
            reason = "Analysis reason",
            score = 8,
            descriptionSource = "sampled_images",
        )

        val merged = MemoryStore.mergeAnalysis(snapshot, fresh, staleResult)

        assertEquals(fresh.title, merged.title)
        assertEquals(fresh.description, merged.description)
        assertEquals(fresh.tags, merged.tags)
        assertEquals(fresh.category, merged.category)
        assertEquals(fresh.milestone, merged.milestone)
        assertEquals("user", merged.descriptionSource)
        assertEquals(fresh.reason, merged.reason)
        assertEquals(staleResult.score, merged.score)
        assertTrue(merged.analyzed)
    }

    @Test fun clearingAFieldIsProtectedWhileUntouchedFieldsReceiveAnalysis() {
        val snapshot = memory().copy(title = "Title to remove", tags = "Tag to remove", category = "Old category")
        val fresh = snapshot.copy(title = "", tags = "")
        val staleResult = snapshot.copy(
            title = "AI title",
            description = "New description",
            tags = "AI tags",
            category = "New category",
            milestone = "New milestone",
        )

        val merged = MemoryStore.mergeAnalysis(snapshot, fresh, staleResult)

        assertEquals("", merged.title)
        assertEquals("", merged.tags)
        assertEquals("New description", merged.description)
        assertEquals("New category", merged.category)
        assertEquals("New milestone", merged.milestone)
    }

    @Test fun explicitUserAuthorshipProtectsFieldsAlreadyPresentInTheSnapshot() {
        val snapshot = memory().copy(
            title = "User-authored title",
            description = "User-authored summary",
            titleByUser = true,
            descriptionByUser = true,
            descriptionSource = "user",
            reason = "The user's reason for keeping this moment",
        )
        val result = snapshot.copy(
            title = "AI replacement title",
            description = "AI replacement summary",
            titleByUser = false,
            descriptionByUser = false,
            descriptionSource = "sampled_images",
            tags = "new analysis tag",
            reason = "AI replacement reason",
        )

        val merged = MemoryStore.mergeAnalysis(snapshot, snapshot, result)

        assertEquals(snapshot.title, merged.title)
        assertEquals(snapshot.description, merged.description)
        assertTrue(merged.titleByUser)
        assertTrue(merged.descriptionByUser)
        assertEquals("user", merged.descriptionSource)
        assertEquals(snapshot.reason, merged.reason)
        assertEquals("new analysis tag", merged.tags)
        assertTrue(merged.analyzed)
    }

    @Test fun unchangedNarrativeFieldsReceiveTheCompletedAnalysis() {
        val snapshot = memory().copy(title = "Previous title", description = "Previous summary")
        val result = snapshot.copy(
            title = "Analysis title",
            description = "Analysis summary",
            tags = "together, outdoors",
            category = "Family",
            milestone = "A first step",
            reason = "A meaningful interaction",
            score = 9,
            analyzed = false,
            descriptionSource = "sampled_images",
        )

        val merged = MemoryStore.mergeAnalysis(snapshot, snapshot, result)

        assertEquals(result.title, merged.title)
        assertEquals(result.description, merged.description)
        assertEquals(result.tags, merged.tags)
        assertEquals(result.category, merged.category)
        assertEquals(result.milestone, merged.milestone)
        assertEquals(result.reason, merged.reason)
        assertEquals(result.score, merged.score)
        assertEquals("sampled_images", merged.descriptionSource)
        assertTrue(merged.analyzed)
    }

    @Test fun newerMediaAndCaptureMetadataCannotBeRolledBackByAnalysis() {
        val snapshot = memory()
        val fresh = snapshot.copy(
            uri = "content://memories/newer",
            name = "newer.jpg",
            time = snapshot.time + 60_000L,
            timeSource = "imported",
            label = "imported",
            lat = 31.8,
            lon = 34.6,
            place = "User's corrected place",
            thumb = "newer-thumb.jpg",
            durationMs = 0L,
            mediaType = "image",
            origin = "import",
        )
        val staleResult = snapshot.copy(title = "New analysis title")

        val merged = MemoryStore.mergeAnalysis(snapshot, fresh, staleResult)

        assertEquals(fresh.id, merged.id)
        assertEquals(fresh.uri, merged.uri)
        assertEquals(fresh.name, merged.name)
        assertEquals(fresh.time, merged.time)
        assertEquals(fresh.timeSource, merged.timeSource)
        assertEquals(fresh.label, merged.label)
        assertEquals(fresh.lat, merged.lat)
        assertEquals(fresh.lon, merged.lon)
        assertEquals(fresh.place, merged.place)
        assertEquals(fresh.thumb, merged.thumb)
        assertEquals(fresh.durationMs, merged.durationMs)
        assertEquals(fresh.mediaType, merged.mediaType)
        assertEquals(fresh.origin, merged.origin)
        assertEquals(fresh.title, merged.title)
        assertFalse(merged.analyzed)
        assertNull(merged.previewWindow)
    }

    @Test fun legacyVideoDefaultsGiveAnHonestMidpointPreview() {
        val legacy = memory()

        assertTrue(legacy.isVideo)
        assertEquals("video", legacy.mediaType)
        assertEquals(-1L, legacy.highlightMs)
        assertEquals(PreviewWindow(28_000L, 32_000L, "fallback"), legacy.previewWindow)
        assertTrue(legacy.previewWindow!!.isFallback)
        assertFalse(legacy.previewWindow!!.isSelected)
    }

    @Test fun legacyCaptureMarkerGetsATriggerPreviewWithoutClaimingSelection() {
        val legacy = memory().copy(highlightMs = 58_000L)

        assertEquals(PreviewWindow(56_000L, 60_000L, "trigger"), legacy.previewWindow)
        assertFalse(legacy.previewWindow!!.isSelected)
        assertFalse(legacy.previewWindow!!.isFallback)
    }

    @Test fun imageNeverGetsAVideoPreviewEvenWithLegacyVideoMetadata() {
        val image = memory().copy(mediaType = "image", highlightMs = 10_000L)

        assertFalse(image.isVideo)
        assertNull(image.previewWindow)
    }
}

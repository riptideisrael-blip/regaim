package com.regaim.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HighlightPolicyTest {
    private fun preview(
        duration: Long = 60_000L,
        marker: Long = -1L,
        start: Long = -1L,
        end: Long = -1L,
        source: String = "",
        isVideo: Boolean = true,
    ) = HighlightPolicy.window(isVideo, duration, marker, start, end, source)

    @Test fun imagesNeverGetAVideoWindow() {
        assertNull(preview(isVideo = false))
        assertNull(preview(isVideo = false, start = 1_000L, end = 5_000L, source = "user"))
    }

    @Test fun nonpositiveDurationsHaveNoWindow() {
        assertNull(preview(duration = 0L))
        assertNull(preview(duration = -1L))
    }

    @Test fun shortClipsUseTheirEntireDuration() {
        for (duration in listOf(1L, 499L, 1_000L, 3_999L, 4_000L)) {
            assertEquals(PreviewWindow(0L, duration, "fallback"), preview(duration = duration))
            assertEquals(PreviewWindow(0L, duration, "trigger"), preview(duration = duration, marker = duration))
        }
    }

    @Test fun knownMarkerCentersThePreview() {
        assertEquals(PreviewWindow(36_200L, 40_200L, "trigger"), preview(marker = 38_200L))
    }

    @Test fun markersNearEitherEdgeClampWithoutShorteningThePreview() {
        assertEquals(PreviewWindow(0L, 4_000L, "trigger"), preview(marker = 0L))
        assertEquals(PreviewWindow(0L, 4_000L, "trigger"), preview(marker = 1_999L))
        assertEquals(PreviewWindow(56_000L, 60_000L, "trigger"), preview(marker = 59_000L))
        assertEquals(PreviewWindow(56_000L, 60_000L, "trigger"), preview(marker = 60_000L))
    }

    @Test fun missingOrInvalidMarkersUseTheMiddleWithFallbackProvenance() {
        for (marker in listOf(-1L, Long.MIN_VALUE, 60_001L, Long.MAX_VALUE)) {
            assertEquals(PreviewWindow(28_000L, 32_000L, "fallback"), preview(marker = marker))
        }
    }

    @Test fun selectedIntervalIsPreservedEvenWhenItsMarkerIsElsewhere() {
        assertEquals(
            PreviewWindow(38_200L, 43_700L, "user"),
            preview(marker = 5_000L, start = 38_200L, end = 43_700L, source = "user"),
        )
    }

    @Test fun selectedIntervalsAcceptBothLengthBoundariesAndClipEdges() {
        assertEquals(PreviewWindow(0L, 1_000L, "user"), preview(start = 0L, end = 1_000L, source = "user"))
        assertEquals(PreviewWindow(54_000L, 60_000L, "user"), preview(start = 54_000L, end = 60_000L, source = "user"))
    }

    @Test fun malformedOrUnsuitableSelectionsFallBackToKnownMarker() {
        val invalidIntervals = listOf(
            -1L to 4_000L,
            4_000L to 4_000L,
            5_000L to 4_000L,
            58_000L to 62_000L,
            0L to 999L,
            0L to 6_001L,
            Long.MIN_VALUE to Long.MAX_VALUE,
        )
        for ((start, end) in invalidIntervals) {
            assertEquals(
                PreviewWindow(18_000L, 22_000L, "trigger"),
                preview(marker = 20_000L, start = start, end = end, source = "user"),
            )
        }
    }

    @Test fun malformedSelectionWithoutAMarkerUsesFallback() {
        assertEquals(PreviewWindow(28_000L, 32_000L, "fallback"), preview(start = 0L, end = 60_000L, source = "user"))
    }

    @Test fun unsupportedSourcesNeverBecomeSelectedHighlights() {
        for (source in listOf("automatic", "trigger", "USER", "AI", "")) {
            assertEquals(
                PreviewWindow(28_000L, 32_000L, "fallback"),
                preview(start = 38_200L, end = 43_700L, source = source),
            )
            assertEquals(
                PreviewWindow(18_000L, 22_000L, "trigger"),
                preview(marker = 20_000L, start = 38_200L, end = 43_700L, source = source),
            )
        }
    }

    /** "ai" is set only by the explicit 8-picture look, and only when the AI was confident. */
    @Test fun extendedLookRangeIsTheMomentOnlyWithinItsEightSeconds() {
        assertEquals(PreviewWindow(38_200L, 43_700L, "ai"), preview(marker = 40_000L, start = 38_200L, end = 43_700L, source = "ai"))
        assertEquals(PreviewWindow(18_000L, 22_000L, "trigger"), preview(marker = 20_000L, start = 10_000L, end = 30_000L, source = "ai"))
        assertEquals(PreviewWindow(18_000L, 22_000L, "trigger"), preview(marker = 20_000L, start = 20_000L, end = 20_500L, source = "ai"))
    }

    @Test fun largeDurationsDoNotOverflowWindowBounds() {
        assertEquals(
            PreviewWindow(Long.MAX_VALUE - 4_000L, Long.MAX_VALUE, "trigger"),
            preview(duration = Long.MAX_VALUE, marker = Long.MAX_VALUE),
        )
        val fallback = preview(duration = Long.MAX_VALUE)!!
        assertEquals(4_000L, fallback.endMs - fallback.startMs)
        assertTrue(fallback.startMs >= 0L)
        assertTrue(fallback.endMs <= Long.MAX_VALUE)
    }

    @Test fun provenanceFlagsAndLabelsMatchTheActualChoice() {
        val selected = PreviewWindow(0L, 4_000L, "user")
        assertTrue(selected.isSelected)
        assertFalse(selected.isFallback)
        assertEquals("קטע שבחרתם", selected.label)

        val trigger = PreviewWindow(0L, 4_000L, "trigger")
        assertFalse(trigger.isSelected)
        assertFalse(trigger.isFallback)
        assertEquals("סביב רגע השמירה", trigger.label)

        val fallback = PreviewWindow(0L, 4_000L, "fallback")
        assertFalse(fallback.isSelected)
        assertTrue(fallback.isFallback)
        assertEquals("תמונה מתוך הזיכרון", fallback.label)
    }
}

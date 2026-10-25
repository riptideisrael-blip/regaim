package com.regaim.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class AnalysisRetryTest {
    private val h = 60 * 60_000L

    @Test fun connectionProblemsAreNetwork() {
        assertEquals(AnalysisRetry.Kind.NETWORK, AnalysisRetry.classify(java.net.UnknownHostException("x")))
        assertEquals(AnalysisRetry.Kind.NETWORK, AnalysisRetry.classify(RuntimeException("wrapped", IOException("reset"))))
        assertEquals(AnalysisRetry.Kind.OTHER, AnalysisRetry.classify(IllegalStateException("bad json")))
    }

    @Test fun waitsLongerThenStops() {
        val t = 1_000_000_000L
        assertTrue(AnalysisRetry.mayTry(0, 0, t))
        assertFalse(AnalysisRetry.mayTry(1, t, t + h - 1))
        assertTrue(AnalysisRetry.mayTry(1, t, t + h))
        assertFalse(AnalysisRetry.mayTry(2, t, t + 5 * h))
        assertTrue(AnalysisRetry.mayTry(2, t, t + 6 * h))
        assertFalse("gives up after MAX", AnalysisRetry.mayTry(AnalysisRetry.MAX, t, t + 1000 * h))
    }
}

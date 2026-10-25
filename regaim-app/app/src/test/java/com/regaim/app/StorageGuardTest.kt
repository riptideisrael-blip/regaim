package com.regaim.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageGuardTest {
    @Test fun thresholds() {
        assertFalse(StorageGuard.enough(50L * 1024 * 1024))
        assertTrue(StorageGuard.enough(StorageGuard.MIN_FREE))
        assertTrue("unknown never blocks", StorageGuard.enough(-1))
    }
}

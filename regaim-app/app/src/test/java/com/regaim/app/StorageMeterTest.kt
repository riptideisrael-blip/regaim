package com.regaim.app

import org.junit.Assert.assertEquals
import org.junit.Test

class StorageMeterTest {
    @Test fun howFull() {
        assertEquals(50, StorageMeter.percent(StorageMeter.FREE_PLAN / 2, StorageMeter.FREE_PLAN))
        assertEquals(0, StorageMeter.percent(10, 0))
        assertEquals("150 MB", StorageMeter.mb(150L * 1048576))
        assertEquals("1.50 GB", StorageMeter.mb(1536L * 1048576))
        assertEquals("הסצנות של פיצקי", StorageMeter.folderName("pitzki-shared"))
    }
}

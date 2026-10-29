package com.regaim.app

import org.junit.Assert.assertEquals
import org.junit.Test

class PartnerShareNewTest {
    private fun m(time: Long, added: Long = 0L) = Memory(id = "m", uri = "content://m", name = "m.jpg", time = time, label = "manual",
        lat = null, lon = null, place = "", thumb = "", durationMs = 0L, added = added)

    @Test fun galleryPhotoAddedAfterSharingGoesEvenIfTakenBefore() {
        val on = 1_000L
        assertEquals(true, PartnerShare.isNew(m(time = 2_000L), on))          // taken after
        assertEquals(true, PartnerShare.isNew(m(time = 10L, added = 1_500L), on)) // an old photo added after
        assertEquals(false, PartnerShare.isNew(m(time = 10L, added = 500L), on))  // added before sharing
        assertEquals(false, PartnerShare.isNew(m(time = 10L), on))                // from before 'added' was kept
        // 'added' survives saving.
        assertEquals(1_500L, Memory.fromJson(m(10L, 1_500L).toJson()).added)
    }
}

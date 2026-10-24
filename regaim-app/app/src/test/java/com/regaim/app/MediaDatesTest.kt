package com.regaim.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class MediaDatesTest {
    private val utc = TimeZone.getTimeZone("UTC")

    @Test fun exifDates() {
        assertEquals(1686763325000L, MediaDates.parseExif("2023:06:14 17:22:05", utc))
        assertEquals(0L, MediaDates.parseExif("0000:00:00 00:00:00", utc))
        assertEquals(0L, MediaDates.parseExif("", utc))
        assertEquals(0L, MediaDates.parseExif(null, utc))
        assertEquals("too old to be real", 0L, MediaDates.parseExif("1970:01:01 00:00:00", utc))
    }

    @Test fun videoDates() {
        assertEquals(1686763325000L, MediaDates.parseVideo("20230614T172205.000Z"))
        assertEquals("1904 = no date", 0L, MediaDates.parseVideo("19040101T000000.000Z"))
        assertEquals(0L, MediaDates.parseVideo("garbage"))
    }

    @Test fun plausibleRange() {
        val now = 1_790_000_000_000L
        assertTrue(MediaDates.plausible(1686763325000L, now))
        assertTrue(!MediaDates.plausible(now + 2 * 86_400_000L, now))
    }
}

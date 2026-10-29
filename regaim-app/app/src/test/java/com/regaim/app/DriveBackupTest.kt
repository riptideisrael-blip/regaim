package com.regaim.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import kotlin.random.Random

class DriveBackupTest {
    private val key = BackupCrypto.keyOf(ByteArray(32) { it.toByte() })

    @Test fun encryptsAndDecryptsAnySize() {
        for (n in listOf(0, 1, 1000, (1 shl 20), (1 shl 20) + 1, 3 * (1 shl 20) + 77)) {
            val data = Random(n).nextBytes(n)
            val enc = BackupCrypto.encryptBytes(key, data)
            assertArrayEquals("size $n", data, BackupCrypto.decryptBytes(key, enc))
        }
    }

    @Test fun aChangedFileOrAnotherKeyIsRefused() {
        val enc = BackupCrypto.encryptBytes(key, "hello family".toByteArray())
        enc[enc.size - 3] = (enc[enc.size - 3] + 1).toByte()
        assertTrue(runCatching { BackupCrypto.decryptBytes(key, enc) }.isFailure)
        val other = BackupCrypto.keyOf(ByteArray(32) { 7 })
        assertTrue(runCatching { BackupCrypto.decryptBytes(other, BackupCrypto.encryptBytes(key, byteArrayOf(1, 2, 3))) }.isFailure)
        // Two encryptions of the same data differ (a new IV each time).
        assertNotEquals(BackupCrypto.encryptBytes(key, byteArrayOf(1)).toList(), BackupCrypto.encryptBytes(key, byteArrayOf(1)).toList())
    }

    @Test fun whatIsBackedUp() {
        assertFalse(DriveBackup.skip("memories.json"))
        assertFalse(DriveBackup.skip("moments_media/abc.mp4"))
        assertFalse(DriveBackup.skip("family_file/r1.jpg"))
        assertFalse(DriveBackup.skip("pitzki/m1/scene_intro.jpg"))
        assertTrue(DriveBackup.skip("mobilefacenet.tflite"))
        assertTrue(DriveBackup.skip("memories.json.tmp"))
        assertTrue(DriveBackup.skip("ai_key_claude.bin"))
        assertTrue(DriveBackup.skip("circle_feed_x.json"))
        assertTrue(DriveBackup.skip("backup_key.bin"))
        assertTrue(DriveBackup.skip("faces.json.unreadable.123"))
    }

    @Test fun signInSecretsStayOnThePhone() {
        assertTrue(DriveBackup.keepPref("regaim", "partner_id"))
        assertTrue(DriveBackup.keepPref("pitzki", "pitzki_world"))
        assertFalse(DriveBackup.keepPref("regaim", "acc_refresh"))
        assertFalse(DriveBackup.keepPref("regaim", "acc_id"))
        assertFalse(DriveBackup.keepPref("regaim", "google_token"))
        assertFalse(DriveBackup.keepPref("regaim", "backup_last"))
        assertFalse(DriveBackup.keepPref("billing", "anything"))
        assertFalse(DriveBackup.keepPref("androidx.work.util.preferences", "x"))
    }

    @Test fun driveNamesSayNothing() {
        val n = DriveBackup.driveName("family_file/receipt_uri.jpg")
        assertTrue(n.startsWith("f_") && n.endsWith(".mbk"))
        assertFalse(n.contains("receipt"))
        assertEquals(n, DriveBackup.driveName("family_file/receipt_uri.jpg"))
    }
}

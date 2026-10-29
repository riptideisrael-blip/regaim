package com.regaim.app

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.io.FileOutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypted files for the family's face prints (who is who). The key lives in the phone's secure hardware
 * (Android Keystore) and can't be copied out, so the file alone is useless on another device or to someone who
 * pulled it off the phone.
 *
 * - Files written before this existed (plain JSON) are still read, and become encrypted on the next save.
 * - A file that can't be decrypted is never silently replaced: it's kept aside as "*.unreadable" first.
 */
object SecureFiles {
    private const val ALIAS = "moments_faces_v1"
    private val MAGIC = byteArrayOf('M'.code.toByte(), 'S'.code.toByte(), 'F'.code.toByte(), 1)
    private const val IV_LEN = 12

    @Synchronized
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private fun isSealed(b: ByteArray) = b.size > MAGIC.size + IV_LEN && (MAGIC.indices).all { b[it] == MAGIC[it] }

    /**
     * The file's text, or null if it doesn't exist. Plain (old) files are read as they are.
     * Throws if the file is encrypted and can't be opened, after moving it aside, so no caller overwrites it.
     */
    fun readText(f: File): String? {
        if (!f.exists()) return null
        val b = f.readBytes()
        if (!isSealed(b)) return String(b, Charsets.UTF_8)
        return try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, b, MAGIC.size, IV_LEN))
            String(c.doFinal(b, MAGIC.size + IV_LEN, b.size - MAGIC.size - IV_LEN), Charsets.UTF_8)
        } catch (e: Exception) {
            // Moved aside only when the data really can't be opened any more (wrong tag, or the key is gone for good),
            // with the time in the name so an earlier copy is never overwritten. A passing Keystore hiccup (known on
            // some phones) just throws: the file stays, and the next read tries again.
            if (unopenable(e)) try { f.renameTo(File(f.parentFile, f.name + ".unreadable." + System.currentTimeMillis())) } catch (_: Exception) { }
            throw e
        }
    }

    private fun unopenable(e: Throwable): Boolean =
        e is javax.crypto.AEADBadTagException ||
            e is android.security.keystore.KeyPermanentlyInvalidatedException ||
            e is java.security.UnrecoverableKeyException

    /**
     * Encrypts [data] into [f] atomically. Unlike [writeText] there's no plain fallback: a document that can't be
     * encrypted isn't saved (throws). For the family file's receipts and documents.
     */
    fun writeBytes(f: File, data: ByteArray) {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val iv = c.iv
        check(iv.size == IV_LEN)
        val out = MAGIC + iv + c.doFinal(data)
        val tmp = File(f.parentFile, f.name + ".tmp")
        FileOutputStream(tmp).use { it.write(out); it.fd.sync() }
        if (!tmp.renameTo(f)) {
            f.delete()
            if (!tmp.renameTo(f)) throw java.io.IOException("couldn't save ${f.name}")
        }
    }

    /** Whether [f] is encrypted with this phone's key (so a backup carries its contents, not the phone-only seal). */
    fun sealedFile(f: File): Boolean = try {
        f.inputStream().use { i -> val h = ByteArray(MAGIC.size); i.read(h) == MAGIC.size && h.contentEquals(MAGIC) } && f.length() > MAGIC.size + IV_LEN
    } catch (_: Exception) { false }

    /** The decrypted bytes of a file written by [writeBytes], or null if it doesn't exist. Throws if it can't be opened. */
    fun readBytes(f: File): ByteArray? {
        if (!f.exists()) return null
        val b = f.readBytes()
        if (!isSealed(b)) return b
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, b, MAGIC.size, IV_LEN))
        return c.doFinal(b, MAGIC.size + IV_LEN, b.size - MAGIC.size - IV_LEN)
    }

    /** Encrypts and writes atomically (temp file, then rename). Falls back to plain only if the Keystore is broken. */
    fun writeText(f: File, text: String) {
        val data = text.toByteArray(Charsets.UTF_8)
        val out = try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, key())
            val iv = c.iv
            check(iv.size == IV_LEN)
            MAGIC + iv + c.doFinal(data)
        } catch (_: Exception) {
            // Some phones have a broken Keystore: keep working (as before) rather than lose the family's names.
            data
        }
        val tmp = File(f.parentFile, f.name + ".tmp")
        FileOutputStream(tmp).use { it.write(out); it.fd.sync() }
        if (!tmp.renameTo(f)) {
            f.delete()
            if (!tmp.renameTo(f)) throw java.io.IOException("couldn't save ${f.name}")
        }
    }
}

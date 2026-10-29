package com.regaim.app

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The backup's encryption: AES-256-GCM in 1 MB pieces, each with its own random IV, so a video of any size is
 * encrypted and decrypted as a stream (never whole in memory) and any change to the file is detected. Format:
 * "MBK1", then for each piece its length (4 bytes), the IV (12) and the encrypted piece with its tag.
 * The key is 32 bytes kept in the parent's Moments account (not with the files, which are in their Google Drive).
 * Pure JVM, unit tested.
 */
object BackupCrypto {
    private val MAGIC = byteArrayOf('M'.code.toByte(), 'B'.code.toByte(), 'K'.code.toByte(), '1'.code.toByte())
    private const val CHUNK = 1 shl 20
    private const val IV = 12
    private val random = SecureRandom()

    fun keyOf(raw: ByteArray): SecretKeySpec {
        require(raw.size == 32) { "backup key must be 32 bytes" }
        return SecretKeySpec(raw, "AES")
    }

    fun encrypt(key: SecretKeySpec, input: InputStream, output: OutputStream) {
        val out = DataOutputStream(output)
        out.write(MAGIC)
        val buf = ByteArray(CHUNK)
        while (true) {
            val n = readFully(input, buf)
            if (n <= 0) break
            val iv = ByteArray(IV).also { random.nextBytes(it) }
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
            val sealed = c.doFinal(buf, 0, n)
            out.writeInt(sealed.size)
            out.write(iv)
            out.write(sealed)
            if (n < CHUNK) break
        }
        out.flush()
    }

    /** Throws when the data isn't a backup file, was changed, or the key is wrong. */
    fun decrypt(key: SecretKeySpec, input: InputStream, output: OutputStream) {
        val inp = DataInputStream(input)
        val head = ByteArray(MAGIC.size)
        inp.readFully(head)
        if (!head.contentEquals(MAGIC)) throw IllegalStateException("not a Moments backup file")
        while (true) {
            val len = try { inp.readInt() } catch (_: EOFException) { break }
            if (len <= 16 || len > CHUNK + 16) throw IllegalStateException("damaged backup file")
            val iv = ByteArray(IV).also { inp.readFully(it) }
            val sealed = ByteArray(len).also { inp.readFully(it) }
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            output.write(c.doFinal(sealed))
        }
        output.flush()
    }

    fun encryptBytes(key: SecretKeySpec, b: ByteArray): ByteArray =
        java.io.ByteArrayOutputStream().also { encrypt(key, b.inputStream(), it) }.toByteArray()

    fun decryptBytes(key: SecretKeySpec, b: ByteArray): ByteArray =
        java.io.ByteArrayOutputStream().also { decrypt(key, b.inputStream(), it) }.toByteArray()

    private fun readFully(input: InputStream, buf: ByteArray): Int {
        var total = 0
        while (total < buf.size) {
            val n = input.read(buf, total, buf.size - total)
            if (n < 0) break
            total += n
        }
        return total
    }
}

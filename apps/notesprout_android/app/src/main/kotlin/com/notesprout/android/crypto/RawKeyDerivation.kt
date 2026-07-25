package com.notesprout.android.crypto

import java.io.File
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Derives the SQLCipher raw encryption key for a passphrase, and formats it for a raw-key open.
 *
 * Opening an encrypted .soil normally runs SQLCipher's KDF (PBKDF2-HMAC-SHA512, 256000 iterations)
 * on every connection — 300–700 ms depending on device. That cost is the same key every time, so we
 * derive it once, cache it ([DerivedKeyStore] / [KeyMaterial]), and reopen with the raw 32-byte key
 * via `PRAGMA key = "x'<hex>'"`, which SQLCipher accepts directly and skips the KDF (~35 ms incl.
 * Room overhead — verified on-device).
 *
 * The derived key is byte-exact to what SQLCipher derives from the passphrase (verified: the result
 * opens a file that was encrypted with the passphrase, for both ASCII and UTF-8 passphrases). This is
 * what preserves portability — the file is still passphrase-keyed; the raw key is merely the KDF's
 * output, so a stock SQLCipher build still opens it with the passphrase.
 *
 * Parameters are SQLCipher 4.x stock defaults; [CLAUDE.md] forbids customizing `kdf_iter` or page
 * size, so these hold for every .soil the app produces.
 *
 * Key material is NEVER logged.
 */
object RawKeyDerivation {

    const val KDF_ITER = 256_000
    const val KEY_LEN = 32   // AES-256
    const val SALT_LEN = 16  // SQLCipher stores the 16-byte KDF salt as the file's plaintext header

    /** Read the 16-byte KDF salt from the plaintext header of an encrypted (or plaintext) .soil. */
    fun readSalt(file: File): ByteArray {
        val salt = ByteArray(SALT_LEN)
        file.inputStream().use {
            val n = it.read(salt)
            require(n == SALT_LEN) { "short salt read ($n bytes)" }
        }
        return salt
    }

    /**
     * Derive the raw key for [passphrase] against [file]'s salt. Expensive (~1.2 s+); call off the UI
     * thread (Dispatchers.IO/Default) and cache the result. The passphrase bytes are UTF-8 — the one
     * canonical encoding (see [SoilCrypto.keyBytes]).
     */
    fun deriveKey(file: File, passphrase: String): ByteArray =
        pbkdf2HmacSha512(passphrase.toByteArray(Charsets.UTF_8), readSalt(file), KDF_ITER, KEY_LEN)

    /** SQLCipher raw-key literal `x'<hex>'`. Passed where a passphrase string would go; SQLCipher
     *  recognizes this form as a raw key and skips the KDF. */
    fun rawKeyLiteral(rawKey: ByteArray): String = "x'${rawKey.toHex()}'"

    /** Manual PBKDF2-HMAC-SHA512 over UTF-8 password bytes. Byte-exact to SQLCipher's default KDF and
     *  faster on-device than javax SecretKeyFactory("PBKDF2WithHmacSHA512") (measured). */
    private fun pbkdf2HmacSha512(password: ByteArray, salt: ByteArray, iterations: Int, dkLen: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA512").apply { init(SecretKeySpec(password, "HmacSHA512")) }
        val hLen = mac.macLength
        val out = ByteArray(dkLen)
        var offset = 0
        var block = 1
        while (offset < dkLen) {
            mac.update(salt)
            mac.update(byteArrayOf((block ushr 24).toByte(), (block ushr 16).toByte(), (block ushr 8).toByte(), block.toByte()))
            var u = mac.doFinal()
            val t = u.copyOf()
            for (i in 2..iterations) {
                u = mac.doFinal(u)
                for (k in t.indices) t[k] = (t[k].toInt() xor u[k].toInt()).toByte()
            }
            val n = minOf(hLen, dkLen - offset)
            System.arraycopy(t, 0, out, offset, n)
            offset += n
            block++
        }
        return out
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}

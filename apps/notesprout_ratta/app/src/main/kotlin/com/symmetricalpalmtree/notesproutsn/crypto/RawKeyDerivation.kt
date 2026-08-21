package com.symmetricalpalmtree.notesproutsn.crypto

import java.io.File
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Derives the SQLCipher raw encryption key for a passphrase, and formats it for a raw-key open.
 *
 * SQLCipher normally runs PBKDF2-HMAC-SHA512 × 256,000 on every connection (300–700 ms on e-ink
 * CPUs). The output is the same key every time, so it is derived once, cached ([KeyMaterial]),
 * and later opens pass `x'<hex>'` which SQLCipher applies directly (~35 ms). The derived key is
 * byte-exact to SQLCipher's own KDF output over the file's 16-byte plaintext salt, so the file
 * stays passphrase-portable (stock SQLCipher 4 defaults — never customise `kdf_iter` /
 * `cipher_page_size`). Pure JVM apart from `java.io.File`; key material is NEVER logged.
 */
object RawKeyDerivation {

    const val KDF_ITER = 256_000
    const val KEY_LEN = 32
    const val SALT_LEN = 16

    /** The 16-byte KDF salt SQLCipher stores as the file's plaintext header. */
    fun readSalt(file: File): ByteArray {
        val salt = ByteArray(SALT_LEN)
        file.inputStream().use {
            val n = it.read(salt)
            require(n == SALT_LEN) { "short salt read ($n bytes)" }
        }
        return salt
    }

    /** Expensive — call on Dispatchers.IO/Default and cache. Passphrase bytes are UTF-8. */
    fun deriveKey(file: File, passphrase: String): ByteArray =
        pbkdf2HmacSha512(SoilCrypto.keyBytes(passphrase), readSalt(file), KDF_ITER, KEY_LEN)

    /** SQLCipher raw-key literal `x'<hex>'`. */
    fun rawKeyLiteral(rawKey: ByteArray): String = "x'${toHex(rawKey)}'"

    /** Lower-case ASCII hex, locale-independent (a default-locale format could emit non-ASCII digits). */
    fun toHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) sb.append(String.format(Locale.ROOT, "%02x", b.toInt() and 0xFF))
        return sb.toString()
    }

    internal fun pbkdf2HmacSha512(password: ByteArray, salt: ByteArray, iterations: Int, dkLen: Int): ByteArray {
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
}

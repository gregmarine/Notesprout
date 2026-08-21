package com.symmetricalpalmtree.notesproutsn.crypto

import android.content.Context
import java.security.SecureRandom

/**
 * The device's global passphrase — auto-minted so encryption-by-default costs nothing at first
 * launch. The minted string doubles as the user's **recovery key**: the one secret that unlocks
 * the library after a reinstall or on another device. The prefix and dashes are part of the
 * passphrase string. Never logged.
 *
 * Format-compatible with the Paper family: `NSPT-` + 8 dash-separated groups of 4 Crockford
 * base32 characters over 160 bits of entropy.
 */
object GlobalKey {

    /** Crockford base32 — omits I, L, O, U to avoid transcription confusion. */
    const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    const val PREFIX = "NSPT-"
    private const val ENTROPY_BYTES = 20 // 160 bits → 32 base32 chars → 8 groups of 4

    /** Cached global passphrase, minting + caching one if absent. Synchronized so two concurrent
     *  first calls can never mint two different keys. */
    @Synchronized
    fun ensure(context: Context): String {
        PassphraseStore.getGlobalPassphrase(context)?.let { return it }
        val generated = mint()
        PassphraseStore.setGlobalPassphrase(context, generated)
        return generated
    }

    /** A fresh 160-bit recovery key, e.g. "NSPT-4K7P-9WXQ-…" (8 groups of 4). */
    fun mint(): String = format(ByteArray(ENTROPY_BYTES).also { SecureRandom().nextBytes(it) })

    /**
     * Fold a hand-transcribed key back onto the canonical alphabet before verifying it: upper-case,
     * then map the confusables the alphabet deliberately omits (O→0, I/L→1). A correct key never
     * contains I/L/O/U, so the fold can't corrupt a valid key — it only rescues a reader who wrote
     * "O" for "0" or "l" for "1". This is the only recovery path, so it must be forgiving.
     */
    fun normalize(typed: String): String = buildString(typed.length) {
        for (c in typed.uppercase()) append(when (c) { 'O' -> '0'; 'I', 'L' -> '1'; else -> c })
    }

    /** Deterministic formatting of 20 entropy bytes — split out so it is unit-testable. */
    fun format(entropy: ByteArray): String {
        require(entropy.size == ENTROPY_BYTES) { "expected $ENTROPY_BYTES bytes" }
        return PREFIX + base32(entropy).chunked(4).joinToString("-")
    }

    /** Standard base32 over the Crockford alphabet, no padding. 20 bytes → 32 chars. */
    private fun base32(data: ByteArray): String {
        val sb = StringBuilder()
        var buffer = 0
        var bitsLeft = 0
        for (b in data) {
            buffer = (buffer shl 8) or (b.toInt() and 0xFF)
            bitsLeft += 8
            while (bitsLeft >= 5) {
                bitsLeft -= 5
                sb.append(ALPHABET[(buffer shr bitsLeft) and 0x1F])
            }
        }
        if (bitsLeft > 0) sb.append(ALPHABET[(buffer shl (5 - bitsLeft)) and 0x1F])
        return sb.toString()
    }
}

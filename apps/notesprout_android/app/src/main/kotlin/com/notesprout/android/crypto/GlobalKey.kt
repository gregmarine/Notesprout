package com.notesprout.android.crypto

import android.content.Context
import java.security.SecureRandom

/**
 * The device's global passphrase — generated automatically so encryption-by-default costs the user
 * nothing at first launch.
 *
 * [ensure] returns the cached global passphrase, or mints a fresh 160-bit random one (Crockford
 * base32, grouped for legibility) and caches it in [PassphraseStore]. That generated string doubles
 * as the user's **recovery key**: it is the one secret that unlocks all global-encrypted content and
 * the only way to open the library on another device or after a reinstall. Onboarding (Phase 3) shows
 * it and lets the user replace it with a memorable passphrase via the rotation flow.
 *
 * The value is a normal passphrase string fed to SQLCipher's KDF — the dashes/prefix are just
 * formatting and are part of the string the user types elsewhere. Never logged.
 */
object GlobalKey {

    /** Crockford base32 — omits I, L, O, U to avoid transcription confusion. */
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private const val ENTROPY_BYTES = 20 // 160 bits

    /** Global passphrase for this device, generating+caching one if absent. Never returns null.
     *  Synchronized: concurrent first calls (index open vs. training-db open use different locks)
     *  could otherwise mint two different globals, and the loser's write would strand whatever the
     *  winner already encrypted behind a passphrase the user was never shown. */
    @Synchronized
    fun ensure(context: Context): String {
        PassphraseStore.getGlobalPassphrase(context)?.let { return it }
        val generated = generate()
        PassphraseStore.setGlobalPassphrase(context, generated)
        return generated
    }

    /** A fresh 160-bit recovery key, e.g. "NSPT-4K7P-9WXQ-2M3F-8VBN-5H0T-…" (8 groups of 4). */
    fun generate(): String {
        val bytes = ByteArray(ENTROPY_BYTES).also { SecureRandom().nextBytes(it) }
        return "NSPT-" + base32(bytes).chunked(4).joinToString("-")
    }

    /** Standard base32 (Crockford alphabet), no padding. 20 bytes → 32 chars. */
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

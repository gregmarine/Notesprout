package com.symmetricalpalmtree.notesproutsn.crypto

/**
 * Process-RAM copy of the global passphrase, set by the bootstrap once the index is open.
 *
 * Lets later opens (a notebook whose raw key isn't cached yet) reach the passphrase without a
 * Keystore round-trip and without any caller re-reading [PassphraseStore]. Never written to an
 * Intent, prefs, or disk from here; cleared with the process.
 */
object KeySession {
    @Volatile
    private var passphrase: String? = null

    fun set(value: String) { passphrase = value }

    fun get(): String? = passphrase

    fun clear() { passphrase = null }
}

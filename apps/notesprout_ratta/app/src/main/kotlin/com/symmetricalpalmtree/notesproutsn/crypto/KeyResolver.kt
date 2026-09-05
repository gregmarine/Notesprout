package com.symmetricalpalmtree.notesproutsn.crypto

import android.content.Context

/**
 * **Which key an open should try** (arc 26 / U4, D3) — pure decision; the prompts are UI.
 *
 * Every open site of a `.soil` asks here first and acts on the answer:
 *
 *  - [Resolved.Passphrases]: a `GLOBAL` notebook — open with [Resolved.Passphrases.candidates] through
 *    [KeyOpener.roomFactoryFor]. The list is the cached global passphrase and, while a rotation
 *    is in flight, the marker's new one **second**: between `GlobalRotation.start` and its commit
 *    the library is in two keys, and a notebook already re-keyed opens under the new one only.
 *  - [Resolved.Unlocked]: a `NOTEBOOK` notebook the person unlocked this process
 *    ([NotebookUnlocks]) whose raw key is cached — open with it, no prompt. The caller verifies
 *    the raw key against the file first (`SoilCrypto.verifyRawKey`, the V4 rule); a stale one
 *    means the file changed under the id, and the answer becomes [Resolved.NeedsPrompt].
 *  - [Resolved.NeedsPrompt]: a `NOTEBOOK` notebook — the notebook screen and a link follow put
 *    up [NotebookPassphrasePrompt]; a silent reader (`SoilDatabase.readOnce`, the backup
 *    compaction, the picker's grid) treats it as **closed** and moves on.
 *  - [Resolved.NoKey]: no global passphrase on this device at all — only possible before the
 *    bootstrap has run, which every open site is behind; reported rather than assumed away.
 *
 * [decide] is the whole table, over plain inputs, and is what the tests pin. [forOpen] wires it
 * to the stores. Nothing here logs, stores, or derives a key.
 */
object KeyResolver {

    sealed interface Resolved {
        /** Passphrases to try in order — the cached global, then the rotation marker's new one;
         *  or, built by a caller that just prompted, the one the person typed. */
        data class Passphrases(val candidates: List<String>) : Resolved {
            constructor(single: String) : this(listOf(single))
        }
        /** The cached raw key of a notebook unlocked this process. */
        class Unlocked(val rawKey: ByteArray) : Resolved
        object NeedsPrompt : Resolved
        object NoKey : Resolved
    }

    /** The prompt-free answer for [id]'s [scope]. Blocking on a Keystore read for `NOTEBOOK`: IO. */
    fun forOpen(context: Context, id: String, scope: KeyScope): Resolved = decide(
        scope = scope,
        global = PassphraseStore.getGlobalPassphrase(context),
        markerNew = PassphraseStore.getRotationMarker(context)?.newPassphrase,
        unlocked = NotebookUnlocks.has(id),
        rawKey = if (scope == KeyScope.NOTEBOOK) KeyMaterial.peekOrLoad(context, id) else null,
    )

    /**
     * The decision table. [global] is the cached global passphrase (null before bootstrap),
     * [markerNew] the in-flight rotation's new passphrase (null when none), [unlocked] whether
     * [NotebookUnlocks] holds the id, [rawKey] the notebook's cached raw key if any.
     */
    fun decide(scope: KeyScope, global: String?, markerNew: String?, unlocked: Boolean, rawKey: ByteArray?): Resolved =
        when (scope) {
            KeyScope.GLOBAL -> when {
                global == null -> Resolved.NoKey
                markerNew != null && markerNew != global -> Resolved.Passphrases(listOf(global, markerNew))
                else -> Resolved.Passphrases(listOf(global))
            }
            KeyScope.NOTEBOOK -> when {
                unlocked && rawKey != null -> Resolved.Unlocked(rawKey)
                else -> Resolved.NeedsPrompt
            }
        }
}

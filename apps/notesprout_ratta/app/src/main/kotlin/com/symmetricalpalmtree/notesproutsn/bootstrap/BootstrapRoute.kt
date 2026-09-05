package com.symmetricalpalmtree.notesproutsn.bootstrap

/**
 * Where an opened index sends the person (arc 26 / U3) — the pure decision every post-open screen
 * shares: `BootstrapActivity` after READY / FIRST_LAUNCH, `UnlockActivity` after a successful
 * unlock, and `RecoveryKeyActivity`'s Continue.
 *
 *  1. The recovery key was never acknowledged → show it ([Next.RECOVERY_KEY]). A minted rotation
 *     clears the acknowledgement at commit, so this is also how the NEW key is shown once.
 *  2. A rotation marker exists → the Encryption screen ([Next.ENCRYPTION]), whose banner is the
 *     resume — resume path 2: it cannot be missed. Back from there is the library.
 *  3. Otherwise the library.
 *
 * The order matters only when both flags are set — a commit that died between clearing the
 * acknowledgement and clearing the marker — and then the key shown IS the marker's, so showing it
 * first is right; the marker settles on the next open anyway.
 */
object BootstrapRoute {

    enum class Next { RECOVERY_KEY, ENCRYPTION, LIBRARY }

    fun afterOpen(acknowledged: Boolean, hasMarker: Boolean): Next = when {
        !acknowledged -> Next.RECOVERY_KEY
        hasMarker -> Next.ENCRYPTION
        else -> Next.LIBRARY
    }

    /** Whether the "then open Backup" request (`EXTRA_THEN_BACKUP`) survives this hop. It rides
     *  the recovery-key screen (the library comes right after) and dies at a resume (the rotation
     *  is not done, so there is nothing to back up yet). */
    fun carriesThenBackup(next: Next): Boolean = next != Next.ENCRYPTION
}

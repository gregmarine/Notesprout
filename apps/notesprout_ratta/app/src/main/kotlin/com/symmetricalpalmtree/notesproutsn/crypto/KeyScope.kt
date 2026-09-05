package com.symmetricalpalmtree.notesproutsn.crypto

import com.symmetricalpalmtree.notesproutsn.data.soil.KEY_SCOPE_GLOBAL
import com.symmetricalpalmtree.notesproutsn.data.soil.KEY_SCOPE_NOTEBOOK

/**
 * Which key opens a notebook (arc 26 / U4, D3) — the typed face of the index's `keyScope` string
 * column and `notebook_meta.keyScope`, which have carried `GLOBAL` since arc 1 and mean it now.
 *
 * [GLOBAL]: the device's global passphrase (the recovery key or the one the person chose), whose
 * raw key is cached and never prompted for after the bootstrap. [NOTEBOOK]: the notebook's own
 * passphrase — prompted for on **every** open of the notebook screen (decision 12), never cached as
 * a passphrase, its raw key cached in the Keystore like any other file's for speed. The library
 * shows such a notebook as a lock card with no cover (decision 11), and its file is never opened
 * unattended: no compaction, no silent read (`SoilDatabase.readOnce` answers null) unless the
 * person unlocked it this process ([NotebookUnlocks]).
 */
enum class KeyScope(val column: String) {
    GLOBAL(KEY_SCOPE_GLOBAL),
    NOTEBOOK(KEY_SCOPE_NOTEBOOK);

    companion object {
        /** The scope a column value means. Anything but the `NOTEBOOK` literal — including the null
         *  an ancient row could carry — is [GLOBAL], the scope every SN notebook was born with. */
        fun of(column: String?): KeyScope = if (column == KEY_SCOPE_NOTEBOOK) NOTEBOOK else GLOBAL
    }
}

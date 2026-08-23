package com.symmetricalpalmtree.notesproutsn.data.index

/** Sentinel ids in the global index. Created on demand by idempotent `ensure…` calls, never by a migration. */
object ListIds {
    /** The pinned-notebooks list: "pinned" spelled out in hex as the last UUID group (70 69 6e 6e 65 64). */
    const val PINNED_LIST_ID = "00000000-0000-0000-0000-70696e6e6564"

    /** The single global clipboard slot (arc 7): "clipbd" in hex (63 6c 69 70 62 64). */
    const val CLIPBOARD_ID = "00000000-0000-0000-0000-636c69706264"
}

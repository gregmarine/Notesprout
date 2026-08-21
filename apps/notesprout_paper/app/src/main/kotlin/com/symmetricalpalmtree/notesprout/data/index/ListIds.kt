package com.symmetricalpalmtree.notesprout.data.index

/** Sentinel ids in the global index. Created on demand by idempotent `ensure…` calls, never by a migration. */
object ListIds {
    /** The pinned-notebooks list: "pinned" spelled out in hex as the last UUID group (70 69 6e 6e 65 64). */
    const val PINNED_LIST_ID = "00000000-0000-0000-0000-70696e6e6564"
}

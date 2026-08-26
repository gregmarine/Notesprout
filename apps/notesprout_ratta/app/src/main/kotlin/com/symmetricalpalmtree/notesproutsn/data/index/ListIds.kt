package com.symmetricalpalmtree.notesproutsn.data.index

/** Sentinel ids in the global index. Created on demand by idempotent `ensure…` calls, never by a migration. */
object ListIds {
    /** The pinned-notebooks list: "pinned" spelled out in hex as the last UUID group (70 69 6e 6e 65 64). */
    const val PINNED_LIST_ID = "00000000-0000-0000-0000-70696e6e6564"

    /** The single global clipboard slot (arc 7): "clipbd" in hex (63 6c 69 70 62 64). */
    const val CLIPBOARD_ID = "00000000-0000-0000-0000-636c69706264"

    // ── Template library (arc 13) ────────────────────────────────────────────
    //
    // These five are NOT rows. Nothing is seeded at bootstrap, nothing can be deleted or renamed,
    // nothing needs repairing if an index is restored from a backup, and there is no migration:
    // they are hardcoded ids for cards the screen composes itself. Same hex-ASCII spelling as the
    // two list sentinels above, so a stray id is recognisable at a glance in a dump.

    /** The **Blank** card at the templates root — "_blank" (5f 62 6c 61 6e 6b). No paper at all. */
    const val TEMPLATE_BLANK_ID = "00000000-0000-0000-0000-5f626c616e6b"

    /** The reserved **Default** folder at the templates root — "deflt_" (64 65 66 6c 74 5f). */
    const val TEMPLATE_DEFAULT_ID = "00000000-0000-0000-0000-6465666c745f"

    /** The Lined built-in, inside [TEMPLATE_DEFAULT_ID] — "lined_" (6c 69 6e 65 64 5f). */
    const val TEMPLATE_LINED_ID = "00000000-0000-0000-0000-6c696e65645f"

    /** The Dotted built-in — "dotted" (64 6f 74 74 65 64). */
    const val TEMPLATE_DOTTED_ID = "00000000-0000-0000-0000-646f74746564"

    /** The Grid built-in — "_grid_" (5f 67 72 69 64 5f). */
    const val TEMPLATE_GRID_ID = "00000000-0000-0000-0000-5f677269645f"
}

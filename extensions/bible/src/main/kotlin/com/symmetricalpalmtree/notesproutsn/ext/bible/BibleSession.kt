package com.symmetricalpalmtree.notesproutsn.ext.bible

import com.symmetricalpalmtree.notesproutsn.extension.BibleNoteTarget
import com.symmetricalpalmtree.notesproutsn.extension.IExtensionStore
import com.symmetricalpalmtree.notesproutsn.extension.ResolvedReference

/**
 * Process-wide state shared by [BibleService] (the host's held bind) and [BibleActivity] (the
 * screen) — they live in the same process (the tag manager's `TagSession` shape, arc 21 / W1).
 *
 * It holds **only what the host lent for this showing**: the store binder from `begin`. `end()`
 * clears it. Nothing here is ever written to disk by the extension itself — the reader's one row
 * of per-device state lives in the host's store.
 */
object BibleSession {

    @Volatile
    var store: IExtensionStore? = null

    /**
     * The reference this showing opens on (arc 38 / R1, `beginAt`) — the wire form, never
     * decoded here; null for a plain `begin`, which opens the reader where it was left. The
     * screen reads it once in `onCreate` to pick its mode. Never logged.
     */
    @Volatile
    var reference: String? = null

    /**
     * The reference the screen's Send to notebook parked (B9) — read once by
     * `BibleService.takeOutgoingReference` on the held bind, the calendar's `outbound` shape on
     * a reference instead of ink. Cleared by that read and by `end()`. Never logged.
     */
    @Volatile
    var outgoing: ResolvedReference? = null

    /** Once-only: the parked reference, cleared on the way out. */
    @Synchronized
    fun takeOutgoing(): ResolvedReference? = outgoing.also { outgoing = null }

    /**
     * The note row the screen's Notes panel picked (arc 42) — read once by
     * `BibleService.takeOutgoingNote` on the held bind after the screen returned
     * `RESULT_BIBLE_OPEN_NOTE`. Cleared by that read and by `end()`.
     */
    @Volatile
    var outgoingNote: BibleNoteTarget? = null

    /** Once-only: the parked note target, cleared on the way out. */
    @Synchronized
    fun takeOutgoingNote(): BibleNoteTarget? = outgoingNote.also { outgoingNote = null }

    @Synchronized
    fun clear() {
        store = null
        reference = null
        outgoing = null
        outgoingNote = null
    }
}

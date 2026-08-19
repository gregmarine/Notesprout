package com.symmetricalpalmtree.notesprout.ext.scratchpad

import com.symmetricalpalmtree.notesprout.extension.IExtensionStore
import com.symmetricalpalmtree.notesprout.extension.PaperStroke

/**
 * Process-wide state shared by [ScratchPadService] (the host's held bind) and [ScratchPadActivity]
 * (the screen) — they live in the same process (arc 6). It holds **only what the host lent for
 * this showing** (rule 25): the store binder from `begin`, the inbound ink from `receiveInk` (S2),
 * the outbound ink for `takeOutgoing` (S2) and the ids to open selected. `end()` clears it all.
 * **Nothing here is ever written to disk by the extension itself** — its data lives in the host
 * store (the arc-2 rule).
 */
object ScratchSession {
    @Volatile var store: IExtensionStore? = null

    /** S2: inbound chunks accumulating until `last`. */
    val inbound = ArrayList<PaperStroke>()

    /** S2: what the pad's Send put up for the host to drain, plus its page size. */
    @Volatile var outbound: List<PaperStroke> = emptyList()
    @Volatile var outboundPageWidth: Float = 0f
    @Volatile var outboundPageHeight: Float = 0f

    /** S2: stroke ids to select when the screen next opens (after a `receiveInk`), or null. */
    @Volatile var openReceived: List<String>? = null

    @Synchronized
    fun clear() {
        store = null
        inbound.clear()
        outbound = emptyList()
        outboundPageWidth = 0f
        outboundPageHeight = 0f
        openReceived = null
    }
}

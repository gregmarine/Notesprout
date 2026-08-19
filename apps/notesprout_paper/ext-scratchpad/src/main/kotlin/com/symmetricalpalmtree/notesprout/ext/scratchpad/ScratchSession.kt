package com.symmetricalpalmtree.notesprout.ext.scratchpad

import com.symmetricalpalmtree.notesprout.extension.IExtensionStore
import com.symmetricalpalmtree.notesprout.extension.PaperStroke

/**
 * Process-wide state shared by [ScratchPadService] (the host's held bind) and [ScratchPadActivity]
 * (the screen) — they live in the same process (arc 6). It holds **only what the host lent for
 * this showing** (rule 25): the store binder from `begin`, the inbound ink accumulating over
 * `receiveInk` chunks (S2), the outbound chunks for `takeOutgoing` (S2) and the one-shot "open
 * selected" record. `end()` clears it all. **Nothing here is ever written to disk by the extension
 * itself** — its data lives in the host store (the arc-2 rule).
 */
object ScratchSession {
    @Volatile var store: IExtensionStore? = null

    /** S2: inbound chunks accumulating until `last` (Binder thread; guarded by the service's lock). */
    val inbound = ArrayList<PaperStroke>()
    var inboundPoints: Int = 0
    var inboundPageWidth: Float = 0f
    var inboundPageHeight: Float = 0f

    /** S2: what the pad's Send put up for the host to drain — already chunked per Binder call — plus its page size. */
    @Volatile var outbound: List<List<PaperStroke>> = emptyList()
    @Volatile var outboundPageWidth: Float = 0f
    @Volatile var outboundPageHeight: Float = 0f

    /** S2: the page + stroke ids a `receiveInk` placed, to select when the screen next opens (one shot). */
    @Volatile var received: ScratchStore.Received? = null

    @Synchronized
    fun clearInbound() {
        inbound.clear()
        inboundPoints = 0
        inboundPageWidth = 0f
        inboundPageHeight = 0f
    }

    @Synchronized
    fun clear() {
        store = null
        clearInbound()
        outbound = emptyList()
        outboundPageWidth = 0f
        outboundPageHeight = 0f
        received = null
    }
}

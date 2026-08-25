package com.symmetricalpalmtree.notesproutsn.ext.scratchpad

import com.symmetricalpalmtree.notesproutsn.extension.IExtensionStore
import com.symmetricalpalmtree.notesproutsn.extension.WireStroke

/**
 * Process-wide state shared by [ScratchPadService] (the host's held bind) and `ScratchPadActivity`
 * (the screen) — they live in the same process. It holds **only what the host lent for this
 * showing**: the store binder from `begin`, the inbound ink accumulating over `receiveInk` chunks
 * (J5), the outbound chunks for `takeOutgoing` (J5) and the one-shot "open selected" record.
 * `end()` clears it all. **Nothing here is ever written to disk by the extension itself** — its data
 * lives in the host store.
 */
object ScratchSession {
    @Volatile var store: IExtensionStore? = null

    /** J5: inbound chunks accumulating until `last` (Binder thread; guarded by the service's lock). */
    val inbound = ArrayList<WireStroke>()
    var inboundPoints: Int = 0
    var inboundPageWidth: Float = 0f
    var inboundPageHeight: Float = 0f

    /** J5: what the pad's Send put up for the host to drain — already chunked per Binder call — plus its page size. */
    @Volatile var outbound: List<List<WireStroke>> = emptyList()
    @Volatile var outboundPageWidth: Float = 0f
    @Volatile var outboundPageHeight: Float = 0f

    /** J5: the page + stroke ids a `receiveInk` placed, to select when the screen next opens (one shot). */
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

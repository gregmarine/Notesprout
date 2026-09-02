package com.symmetricalpalmtree.notesproutsn.ext.calendar

import com.symmetricalpalmtree.notesproutsn.extension.CalendarTarget
import com.symmetricalpalmtree.notesproutsn.extension.IExtensionStore
import com.symmetricalpalmtree.notesproutsn.extension.WireStroke

/**
 * Process-wide state shared by [CalendarService] (the host's held bind) and `CalendarActivity`
 * (the screen) — they live in the same process. It holds **only what the host lent for this
 * showing**: the store binder from `begin`, the inbound ink accumulating over `receiveInk` chunks
 * with the page it is bound for, the outbound chunks for `takeOutgoing` and the one-shot "open
 * selected" record. `end()` clears it all. **Nothing here is ever written to disk by the extension
 * itself** — its data lives in the host store.
 */
object CalendarSession {
    @Volatile var store: IExtensionStore? = null

    /** Inbound chunks accumulating until `last` (Binder thread; guarded by the service's lock). */
    val inbound = ArrayList<WireStroke>()
    var inboundPoints: Int = 0
    var inboundTarget: CalendarTarget? = null

    /** What the screen's Send put up for the host to drain — already chunked per Binder call — plus its page size. */
    @Volatile var outbound: List<List<WireStroke>> = emptyList()
    @Volatile var outboundPageWidth: Float = 0f
    @Volatile var outboundPageHeight: Float = 0f

    /** The page + stroke ids a `receiveInk` placed, to open on and select when the screen next opens (one shot). */
    @Volatile var received: CalendarStore.Received? = null

    @Synchronized
    fun clearInbound() {
        inbound.clear()
        inboundPoints = 0
        inboundTarget = null
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

package com.symmetricalpalmtree.notesproutsn.ink

import android.os.SystemClock
import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.notesproutsn.extension.ExtensionContract
import com.symmetricalpalmtree.notesproutsn.extension.IExtensionStore
import com.symmetricalpalmtree.notesproutsn.extension.InkBundle
import com.symmetricalpalmtree.notesproutsn.extension.WireStroke

/**
 * The process-wide state of **one showing** of a paper-owning extension, and the bodies of the two
 * transfer stubs that move ink across it (arc 11 / J3 + J5 as the pad's `ScratchSession` and
 * service; the calendar's copy at arc 23 / Y1; **one class here** since the arc-23 sweep).
 *
 * It holds only what the host lent for this showing — the store binder from `begin`, the inbound
 * ink accumulating over `receiveInk` chunks, the outbound chunks for `takeOutgoing`, and the
 * one-shot record the screen consumes when it opens. `end` clears it all. **Nothing here is ever
 * written to disk by the extension itself**: its data lives in the host store.
 *
 * The service and the screen live in the same process, which is what makes a shared object the
 * seam between them; [P] is the placement the host names on every chunk (the pad's placement int,
 * the calendar's `CalendarTarget`) and [R] the store's record of what a placement landed.
 *
 * **The transfers run on the Binder thread, under this object's one monitor** — `begin`, `end` and
 * the whole accumulate-and-place take the same lock, so a host that restarts mid-transfer can never
 * interleave with a placement. Neither ever touches the paper: the screen is not up during a
 * `receiveInk` (the host sends and then launches) and it has already parked its ink before a
 * `takeOutgoing`.
 *
 * **The placement is bound by the first chunk and may not change** ([receiveChunk]). The chunk
 * itself is already through `InkBundle.requireValid` at unmarshal; what is left to check is what no
 * single bundle can see — the running totals across chunks, and that every chunk of one transfer
 * names the same page. Either refusal drops the whole inbound and throws
 * `IllegalArgumentException`; a store that has gone away is [STORE_UNAVAILABLE] as an
 * `IllegalStateException`. Those two and `SecurityException` are the only exceptions that cross
 * Binder intact — anything else kills the transaction silently and the caller reads an empty reply
 * as success.
 *
 * [recordInboundPageSize] is the one place the two consumers genuinely differ, and it is a
 * parameter rather than a second copy: the **pad** takes the sender's page size from the first
 * chunk, because a placement onto a new page mints that page at the size the ink was authored in;
 * the **calendar** drops it and mints `0 × 0`, because a calendar page's size is the screen's the
 * first time a screen shows it — the sender's page size is the sender's.
 */
abstract class InkTransferSession<P : Any, R : Any>(
    private val recordInboundPageSize: Boolean,
) {

    /** The store the host lent for this showing, or null before `begin` / after `end`. */
    @Volatile
    var store: IExtensionStore? = null

    /** Inbound chunks accumulating until `last` (Binder thread; guarded by this object's monitor). */
    val inbound = ArrayList<WireStroke>()
    var inboundPoints: Int = 0

    /** The sender's page size, recorded from the first chunk only when [recordInboundPageSize]. */
    var inboundPageWidth: Float = 0f
    var inboundPageHeight: Float = 0f

    /** The page every chunk of the transfer in flight must name. Bound by the first chunk. */
    var inboundPlacement: P? = null

    /** What the screen's Send put up for the host to drain — already chunked per Binder call. */
    @Volatile
    var outbound: List<List<WireStroke>> = emptyList()

    @Volatile
    var outboundPageWidth: Float = 0f

    @Volatile
    var outboundPageHeight: Float = 0f

    /** What a `receiveInk` placed, for the screen to consume **once** when it opens. */
    @Volatile
    var received: R? = null

    /** What one completed placement did, for the caller's own log line. */
    class Placed<R>(val received: R, val strokes: Int, val millis: Long)

    // ── The bind ─────────────────────────────────────────────────────────────

    /** `begin`: take the lent store, dropping anything a previous showing left (the host restarted). */
    @Synchronized
    fun begin(store: IExtensionStore) {
        clear()
        this.store = store
    }

    @Synchronized
    fun clearInbound() {
        inbound.clear()
        inboundPoints = 0
        inboundPageWidth = 0f
        inboundPageHeight = 0f
        inboundPlacement = null
    }

    /** `end`: nothing of this showing survives it. */
    @Synchronized
    fun clear() {
        store = null
        clearInbound()
        outbound = emptyList()
        outboundPageWidth = 0f
        outboundPageHeight = 0f
        received = null
    }

    // ── Notebook → here ──────────────────────────────────────────────────────

    /**
     * One `receiveInk` chunk: accumulate, and on [last] mint fresh ids ([InkWire.toStrokes] —
     * nothing from the wire is trusted beyond its geometry) and hand the lot to [place], which is
     * the consumer's own store placement. Returns null for every chunk but the last.
     *
     * The store binder is read **before** the monitor is taken, exactly as the two copies did.
     */
    fun receiveChunk(
        chunk: InkBundle?,
        placement: P,
        last: Boolean,
        place: (store: IExtensionStore, strokes: List<Stroke>, placement: P) -> R,
    ): Placed<R>? {
        requireNotNull(chunk) { "chunk is null" }
        val store = store ?: throw IllegalStateException(STORE_UNAVAILABLE)
        synchronized(this) {
            val bound = inboundPlacement
            if (bound == null) {
                inboundPlacement = placement
                if (recordInboundPageSize) {
                    inboundPageWidth = chunk.pageWidth
                    inboundPageHeight = chunk.pageHeight
                }
            } else if (bound != placement) {
                clearInbound()
                throw IllegalArgumentException("placement changed mid-transfer")
            }
            val strokes = inbound.size + chunk.strokes.size
            val points = inboundPoints + chunk.pointCount
            if (strokes > ExtensionContract.MAX_TRANSFER_STROKES || points > ExtensionContract.MAX_TRANSFER_POINTS) {
                clearInbound()
                throw IllegalArgumentException("transfer over the caps ($strokes strokes, $points points)")
            }
            inbound += chunk.strokes
            inboundPoints = points
            if (!last) return null

            val t0 = SystemClock.elapsedRealtime()
            val minted = InkWire.toStrokes(inbound)
            val placed = try {
                place(store, minted, placement)
            } catch (e: StoreUnavailable) {
                clearInbound()
                throw IllegalStateException(STORE_UNAVAILABLE)
            }
            received = placed
            clearInbound()
            return Placed(placed, minted.size, SystemClock.elapsedRealtime() - t0)
        }
    }

    // ── Here → notebook ──────────────────────────────────────────────────────

    /** The screen's Send, parked for the host to drain on the bind it is still holding. */
    fun park(chunks: List<List<WireStroke>>, pageWidth: Float, pageHeight: Float) {
        outbound = chunks
        outboundPageWidth = pageWidth
        outboundPageHeight = pageHeight
    }

    /**
     * `takeOutgoing`: one parked chunk. An index past the end is an **empty bundle, not an error** —
     * "done" is exactly what the host is asking about, and it probes one chunk past the budget on
     * purpose.
     */
    fun outgoing(chunkIndex: Int): InkBundle =
        InkBundle(outbound.getOrNull(chunkIndex).orEmpty(), outboundPageWidth, outboundPageHeight)

    companion object {
        /** The store binder is gone (`begin` never ran, or the host revoked it mid-transfer). The
         *  one store-failure text on this seam — an `IllegalStateException`, which survives Binder. */
        const val STORE_UNAVAILABLE = "store unavailable"
    }
}

package com.symmetricalpalmtree.notesproutsn.ext.scratchpad

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.SystemClock
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.extension.ExtensionContract
import com.symmetricalpalmtree.notesproutsn.extension.HostCallerCheck
import com.symmetricalpalmtree.notesproutsn.extension.IExtensionStore
import com.symmetricalpalmtree.notesproutsn.extension.IScratchPad
import com.symmetricalpalmtree.notesproutsn.extension.InkBundle
import com.symmetricalpalmtree.notesproutsn.ink.InkWire
import com.symmetricalpalmtree.notesproutsn.ink.StoreUnavailable

/**
 * The SCRATCH_PAD point (arc 11 / J3) — the host's **held** bind for one showing of the screen.
 * Every method: `HostCallerCheck.enforce` first. `begin` holds the store binder for the screen's
 * life in [ScratchSession] (a second `begin` while one is held replaces it — the host restarted) and
 * reads the page list on the Binder thread (first run creates one blank page; the count is logged).
 * `end` clears everything.
 *
 * **The two transfers (J5).** Both run on the **Binder thread**, both under [ScratchSession]'s one
 * monitor, and neither ever touches the paper: the screen is not up during a `receiveInk` (the host
 * sends and then launches) and it has already parked its ink before a `takeOutgoing`.
 *
 * `receiveInk` accumulates chunks until `last`, **re-checking the running totals** against the
 * transfer caps as it goes — the host checks before any bind, and this is the untrusted-input half
 * of the same rule (over → `IllegalArgumentException`, the whole inbound dropped). On `last` it
 * mints fresh ids ([InkWire.toStrokes] — nothing from the wire is trusted beyond its geometry)
 * and places the lot through [ScratchStore.receive], leaving [ScratchSession.received] for the
 * screen to consume once. A scratch page has **no size ceiling** since arc 22 / X2 — the placement
 * is one store transaction, so the only failure left is the store being gone.
 *
 * `takeOutgoing` hands back one parked chunk; an empty bundle says "done", which is also the honest
 * answer for an index past the end.
 *
 * Exceptions that cross Binder intact are the only ones thrown — anything else kills the transaction
 * silently and the caller reads an empty reply as success. Logs: counts + durations — never ink.
 *
 * A debug-only `StoreProbe` briefly lived here: a once-per-process cross-process 4 MiB
 * large-value round trip from `begin`, the arc's open question about ashmem over a **real**
 * Binder (the host's own self-test never leaves its process). It answered — **916 ms on the Nomad**,
 * inside `begin`'s 2 s budget, matching Paper's 917 ms — and was removed in the same phase, as Paper
 * removed its own: left in, it would sit inside the first pad open of every session and muddy J4's
 * timings.
 */
class ScratchPadService : Service() {

    private val binder = object : IScratchPad.Stub() {

        override fun begin(store: IExtensionStore?) {
            enforce()
            requireNotNull(store) { "store is null" }
            val t0 = SystemClock.elapsedRealtime()
            ScratchSession.clear()
            ScratchSession.store = store
            val pages = try { ScratchStore(store).load().ids.size } catch (e: StoreUnavailable) { -1 }
            Slog.d(TAG) { "begin: pages=$pages in ${SystemClock.elapsedRealtime() - t0} ms" }
        }

        override fun receiveInk(chunk: InkBundle?, placement: Int, last: Boolean) {
            enforce()
            requireNotNull(chunk) { "chunk is null" }
            require(placement == ExtensionContract.PLACEMENT_NEW_PAGE || placement == ExtensionContract.PLACEMENT_CURRENT_PAGE) {
                "unknown placement ($placement)"
            }
            // The chunk is already through `InkBundle.requireValid` at unmarshal; what is left to
            // check is the running total across chunks, which no single bundle can see.
            val store = ScratchSession.store ?: throw IllegalStateException(STORE_UNAVAILABLE)
            // One monitor for the whole accumulate-and-place: `begin` and `end` take the same one,
            // so a host that restarts mid-transfer can never interleave with a placement.
            synchronized(ScratchSession) {
                if (ScratchSession.inbound.isEmpty()) {
                    ScratchSession.inboundPageWidth = chunk.pageWidth
                    ScratchSession.inboundPageHeight = chunk.pageHeight
                }
                val strokes = ScratchSession.inbound.size + chunk.strokes.size
                val points = ScratchSession.inboundPoints + chunk.pointCount
                if (strokes > ExtensionContract.MAX_TRANSFER_STROKES || points > ExtensionContract.MAX_TRANSFER_POINTS) {
                    ScratchSession.clearInbound()
                    throw IllegalArgumentException("transfer over the caps ($strokes strokes, $points points)")
                }
                ScratchSession.inbound += chunk.strokes
                ScratchSession.inboundPoints = points
                if (!last) return

                val t0 = SystemClock.elapsedRealtime()
                val minted = InkWire.toStrokes(ScratchSession.inbound)
                val w = ScratchSession.inboundPageWidth
                val h = ScratchSession.inboundPageHeight
                val newPage = placement == ExtensionContract.PLACEMENT_NEW_PAGE
                val received = try {
                    ScratchStore(store).receive(minted, w, h, newPage)
                } catch (e: StoreUnavailable) {
                    ScratchSession.clearInbound()
                    throw IllegalStateException(STORE_UNAVAILABLE)
                }
                ScratchSession.received = received
                ScratchSession.clearInbound()
                Slog.d(TAG) {
                    "receiveInk: ${minted.size} strokes placed (newPage=$newPage) in ${SystemClock.elapsedRealtime() - t0} ms"
                }
            }
        }

        override fun takeOutgoing(chunkIndex: Int): InkBundle {
            enforce()
            // An index past the end is an empty bundle, not an error: "done" is exactly what the
            // host is asking about, and it probes one chunk past the budget on purpose.
            val chunk = ScratchSession.outbound.getOrNull(chunkIndex).orEmpty()
            return InkBundle(chunk, ScratchSession.outboundPageWidth, ScratchSession.outboundPageHeight)
        }

        override fun end() {
            enforce()
            ScratchSession.clear()
            Slog.d(TAG) { "end" }
        }

        private fun enforce() = HostCallerCheck.enforce(this@ScratchPadService, BuildConfig.HOST_PACKAGE)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    companion object {
        private const val TAG = "ScratchPadService"

        /** The store binder is gone (`begin` never ran, or the host revoked it mid-transfer).
         *  An `IllegalStateException` — one of the three that survive Binder marshalling. */
        private const val STORE_UNAVAILABLE = "store unavailable"
    }
}

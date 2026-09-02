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
import com.symmetricalpalmtree.notesproutsn.ink.StoreUnavailable

/**
 * The SCRATCH_PAD point (arc 11 / J3) — the host's **held** bind for one showing of the screen.
 * Every method: `HostCallerCheck.enforce` first. `begin` holds the store binder for the screen's
 * life in [ScratchSession] (a second `begin` while one is held replaces it — the host restarted) and
 * reads the page list on the Binder thread (first run creates one blank page; the count is logged).
 * `end` clears everything.
 *
 * **The two transfers (J5) are `:ext-ink`'s** since arc 23 — [ScratchSession] is an
 * `InkTransferSession`, and the accumulate-and-place body, the running-total caps re-check, the
 * one-monitor rule, the placement bound by the first chunk and the `"store unavailable"` mapping
 * all live there, one copy shared with the calendar. What is the pad's own is what is left here:
 * the placement int's own validity check, the page-list read at `begin`, and the wording of the
 * logs. Both transfers run on the **Binder thread** and neither ever touches the paper: the screen
 * is not up during a `receiveInk` (the host sends and then launches) and it has already parked its
 * ink before a `takeOutgoing`.
 *
 * `receiveInk` accumulates chunks until `last`, then mints fresh ids (`InkWire.toStrokes` — nothing
 * from the wire is trusted beyond its geometry) and places the lot through [ScratchStore.receive],
 * leaving `ScratchSession.received` for the screen to consume once. A scratch page has **no size
 * ceiling** since arc 22 / X2 — the placement is one store transaction, so the only failure left is
 * the store being gone.
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
            ScratchSession.begin(store)
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
            // check is what no single bundle can see — the running total across chunks and that
            // every chunk names the same placement. Both are the shared session's, under its one
            // monitor: `begin` and `end` take the same lock, so a host that restarts mid-transfer
            // can never interleave with a placement.
            val newPage = placement == ExtensionContract.PLACEMENT_NEW_PAGE
            val placed = ScratchSession.receiveChunk(chunk, placement, last) { store, strokes, _ ->
                // The page size the first chunk carried: a new page is minted at the size the ink
                // was authored in (`recordInboundPageSize` — the pad's answer).
                ScratchStore(store).receive(strokes, ScratchSession.inboundPageWidth, ScratchSession.inboundPageHeight, newPage)
            } ?: return
            Slog.d(TAG) { "receiveInk: ${placed.strokes} strokes placed (newPage=$newPage) in ${placed.millis} ms" }
        }

        override fun takeOutgoing(chunkIndex: Int): InkBundle {
            enforce()
            // An index past the end is an empty bundle, not an error: "done" is exactly what the
            // host is asking about, and it probes one chunk past the budget on purpose.
            return ScratchSession.outgoing(chunkIndex)
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
    }
}

package com.symmetricalpalmtree.notesprout.ext.scratchpad

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.SystemClock
import com.symmetricalpalmtree.notesprout.core.Slog
import com.symmetricalpalmtree.notesprout.extension.ExtensionContract
import com.symmetricalpalmtree.notesprout.extension.HostCallerCheck
import com.symmetricalpalmtree.notesprout.extension.IExtensionStore
import com.symmetricalpalmtree.notesprout.extension.IScratchPad
import com.symmetricalpalmtree.notesprout.extension.InkBundle

/**
 * The SCRATCH_PAD point (arc 6 / S0 + S2) — the host's **held** bind for one showing of the screen.
 * Every method: `HostCallerCheck.enforce` first. `begin` holds the store binder for the screen's
 * life in [ScratchSession] (a second `begin` while one is held replaces it — the host restarted) and
 * reads the page list on the Binder thread (first run creates one blank page; the count is logged);
 * `end` clears everything.
 *
 * **`receiveInk`** (S2, notebook → pad): every chunk is `requireValid` at unmarshal; the running
 * totals are re-checked against `MAX_TRANSFER_STROKES` / `MAX_TRANSFER_POINTS` (over → refused, the
 * inbound dropped); on `last` the strokes get fresh ids ([ScratchInk.toStrokes]) and are placed
 * through [ScratchStore.receive] **on the Binder thread** (never Main) — a new page after the current
 * one or the current page — and recorded as [ScratchSession.received] for the screen to open
 * selected. A page that would cross the store cap → `IllegalStateException(SCRATCH_PAGE_FULL)` (the
 * host types it); any store failure → `IllegalStateException` (the host says "didn't respond").
 * **`takeOutgoing`** (pad → notebook): chunk `i` of what the screen's Send put up, an empty bundle
 * past the end.
 *
 * Exceptions that cross Binder intact are the only ones thrown. Logs: counts + durations — never ink.
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
            require(placement == ExtensionContract.PLACEMENT_NEW_PAGE || placement == ExtensionContract.PLACEMENT_CURRENT_PAGE) { "bad placement $placement" }
            val store = ScratchSession.store ?: throw IllegalStateException("no store held — begin() first")
            synchronized(ScratchSession) {
                val s = ScratchSession
                if (s.inbound.size + chunk.strokes.size > ExtensionContract.MAX_TRANSFER_STROKES ||
                    s.inboundPoints + chunk.pointCount > ExtensionContract.MAX_TRANSFER_POINTS) {
                    s.clearInbound()
                    throw IllegalArgumentException("transfer exceeds the caps")
                }
                if (s.inbound.isEmpty()) { s.inboundPageWidth = chunk.pageWidth; s.inboundPageHeight = chunk.pageHeight }
                s.inbound.addAll(chunk.strokes)
                s.inboundPoints += chunk.pointCount
                if (!last) return
                val t0 = SystemClock.elapsedRealtime()
                val strokes = ScratchInk.toStrokes(s.inbound)
                val w = s.inboundPageWidth; val h = s.inboundPageHeight
                s.clearInbound()
                val received = try {
                    ScratchStore(store).receive(strokes, w, h, newPage = placement == ExtensionContract.PLACEMENT_NEW_PAGE)
                } catch (e: PageFullException) {
                    Slog.d(TAG) { "receiveInk: page full (${e.bytes} B)" }
                    throw IllegalStateException(ExtensionContract.SCRATCH_PAGE_FULL)
                } catch (e: StoreUnavailable) {
                    Slog.d(TAG) { "receiveInk: store unavailable: ${e.message}" }
                    throw IllegalStateException("store unavailable")
                }
                s.received = received
                Slog.d(TAG) { "receiveInk: placed ${strokes.size} strokes (${if (placement == ExtensionContract.PLACEMENT_NEW_PAGE) "new page" else "current page"}) in ${SystemClock.elapsedRealtime() - t0} ms" }
            }
        }

        override fun takeOutgoing(chunkIndex: Int): InkBundle {
            enforce()
            val chunks = ScratchSession.outbound
            val chunk = if (chunkIndex in chunks.indices) chunks[chunkIndex] else emptyList()
            Slog.d(TAG) { "takeOutgoing $chunkIndex: ${chunk.size} strokes" }
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
    }
}

package com.symmetricalpalmtree.notesprout.ext.scratchpad

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.SystemClock
import com.symmetricalpalmtree.notesprout.core.Slog
import com.symmetricalpalmtree.notesprout.extension.HostCallerCheck
import com.symmetricalpalmtree.notesprout.extension.IExtensionStore
import com.symmetricalpalmtree.notesprout.extension.IScratchPad
import com.symmetricalpalmtree.notesprout.extension.InkBundle

/**
 * The SCRATCH_PAD point (arc 6 / S0) — the host's **held** bind for one showing of the screen.
 * Every method: `HostCallerCheck.enforce` first. `begin` holds the store binder for the screen's
 * life in [ScratchSession] (a second `begin` while one is held replaces it — the host restarted) and
 * reads the page list on the Binder thread (first run creates one blank page; the count is logged —
 * S0 also ran a once-per-process cross-process 4 MiB `putLarge` / `getLarge` probe here, verified on
 * all three devices and removed again; see the plan's S0 Outcome);
 * `end` clears everything. `receiveInk` / `takeOutgoing` are S2 — until then they throw
 * `UnsupportedOperationException` (marshalable; the S1 host wires nothing to them).
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
            throw UnsupportedOperationException("receiveInk arrives in S2")
        }

        override fun takeOutgoing(chunkIndex: Int): InkBundle {
            enforce()
            throw UnsupportedOperationException("takeOutgoing arrives in S2")
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

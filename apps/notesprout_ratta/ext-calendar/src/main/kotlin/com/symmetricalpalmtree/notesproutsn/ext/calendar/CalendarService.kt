package com.symmetricalpalmtree.notesproutsn.ext.calendar

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.SystemClock
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.extension.CalendarTarget
import com.symmetricalpalmtree.notesproutsn.extension.ExtensionContract
import com.symmetricalpalmtree.notesproutsn.extension.HostCallerCheck
import com.symmetricalpalmtree.notesproutsn.extension.ICalendar
import com.symmetricalpalmtree.notesproutsn.extension.IExtensionStore
import com.symmetricalpalmtree.notesproutsn.extension.InkBundle
import com.symmetricalpalmtree.notesproutsn.ink.InkWire
import com.symmetricalpalmtree.notesproutsn.ink.StoreUnavailable

/**
 * The CALENDAR point (arc 23 / Y1) — the host's **held** bind for one showing of the screen, the
 * pad's service shape with the placement made a real type. Every method: `HostCallerCheck.enforce`
 * first. `begin` holds the store binder for the screen's life in [CalendarSession] (a second `begin`
 * while one is held replaces it — the host restarted), declares the schema and reads the bookmark
 * on the Binder thread; the row counts are logged (the Y1 walk's "browsing wrote nothing" proof —
 * `sqlite3` cannot read a SQLCipher file). `end` clears everything.
 *
 * **The two transfers.** Both run on the **Binder thread**, both under [CalendarSession]'s one
 * monitor, and neither ever touches the paper: the screen is not up during a `receiveInk` (the host
 * sends and then launches) and it has already parked its ink before a `takeOutgoing`.
 *
 * `receiveInk` accumulates chunks until `last`, **re-checking the running totals** against the
 * transfer caps as it goes and requiring every chunk to name the same target — the host checks
 * before any bind, and this is the untrusted-input half of the same rule (over, or a target that
 * changes mid-transfer → `IllegalArgumentException`, the whole inbound dropped). On `last` it mints
 * fresh ids ([InkWire.toStrokes] — nothing from the wire is trusted beyond its geometry) and places
 * the lot through [CalendarStore.receive], leaving [CalendarSession.received] for the screen to
 * consume once. A page has no size ceiling — the placement is one store transaction, so the only
 * failure left is the store being gone.
 *
 * `takeOutgoing` hands back one parked chunk; an empty bundle says "done", which is also the honest
 * answer for an index past the end.
 *
 * Exceptions that cross Binder intact are the only ones thrown — anything else kills the transaction
 * silently and the caller reads an empty reply as success. Logs: counts + durations — never ink.
 */
class CalendarService : Service() {

    private val binder = object : ICalendar.Stub() {

        override fun begin(store: IExtensionStore?) {
            enforce()
            requireNotNull(store) { "store is null" }
            val t0 = SystemClock.elapsedRealtime()
            CalendarSession.clear()
            CalendarSession.store = store
            val summary = try {
                val s = CalendarStore(store)
                val at = s.open()
                val counts = s.counts()
                "at=${at?.let { "${it.kind}/${it.date}/${it.half}" } ?: "(none)"} rows: ${counts.periods} period(s), ${counts.pages} page(s), ${counts.strokes} stroke(s)"
            } catch (e: StoreUnavailable) {
                "store unavailable (${e.message})"
            }
            Slog.d(TAG) { "begin: $summary in ${SystemClock.elapsedRealtime() - t0} ms" }
        }

        override fun receiveInk(chunk: InkBundle?, target: CalendarTarget?, last: Boolean) {
            enforce()
            requireNotNull(chunk) { "chunk is null" }
            requireNotNull(target) { "target is null" }   // already through requireValid at unmarshal
            val store = CalendarSession.store ?: throw IllegalStateException(STORE_UNAVAILABLE)
            // One monitor for the whole accumulate-and-place: `begin` and `end` take the same one,
            // so a host that restarts mid-transfer can never interleave with a placement.
            synchronized(CalendarSession) {
                val bound = CalendarSession.inboundTarget
                if (bound == null) {
                    CalendarSession.inboundTarget = target
                } else if (bound != target) {
                    CalendarSession.clearInbound()
                    throw IllegalArgumentException("target changed mid-transfer")
                }
                val strokes = CalendarSession.inbound.size + chunk.strokes.size
                val points = CalendarSession.inboundPoints + chunk.pointCount
                if (strokes > ExtensionContract.MAX_TRANSFER_STROKES || points > ExtensionContract.MAX_TRANSFER_POINTS) {
                    CalendarSession.clearInbound()
                    throw IllegalArgumentException("transfer over the caps ($strokes strokes, $points points)")
                }
                CalendarSession.inbound += chunk.strokes
                CalendarSession.inboundPoints = points
                if (!last) return

                val t0 = SystemClock.elapsedRealtime()
                val minted = InkWire.toStrokes(CalendarSession.inbound)
                val received = try {
                    CalendarStore(store).receive(minted, target)
                } catch (e: StoreUnavailable) {
                    CalendarSession.clearInbound()
                    throw IllegalStateException(STORE_UNAVAILABLE)
                }
                CalendarSession.received = received
                CalendarSession.clearInbound()
                Slog.d(TAG) {
                    "receiveInk: ${minted.size} strokes placed on ${target.kind}/${target.date}/${target.half} in ${SystemClock.elapsedRealtime() - t0} ms"
                }
            }
        }

        override fun takeOutgoing(chunkIndex: Int): InkBundle {
            enforce()
            // An index past the end is an empty bundle, not an error: "done" is exactly what the
            // host is asking about, and it probes one chunk past the budget on purpose.
            val chunk = CalendarSession.outbound.getOrNull(chunkIndex).orEmpty()
            return InkBundle(chunk, CalendarSession.outboundPageWidth, CalendarSession.outboundPageHeight)
        }

        override fun end() {
            enforce()
            CalendarSession.clear()
            Slog.d(TAG) { "end" }
        }

        private fun enforce() = HostCallerCheck.enforce(this@CalendarService, BuildConfig.HOST_PACKAGE)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    companion object {
        private const val TAG = "CalendarService"

        /** The store binder is gone (`begin` never ran, or the host revoked it mid-transfer).
         *  An `IllegalStateException` — one of the three that survive Binder marshalling. */
        private const val STORE_UNAVAILABLE = "store unavailable"
    }
}

package com.symmetricalpalmtree.notesproutsn.ext.calendar

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.SystemClock
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.extension.CalendarTarget
import com.symmetricalpalmtree.notesproutsn.extension.HostCallerCheck
import com.symmetricalpalmtree.notesproutsn.extension.ICalendar
import com.symmetricalpalmtree.notesproutsn.extension.IExtensionStore
import com.symmetricalpalmtree.notesproutsn.extension.InkBundle
import com.symmetricalpalmtree.notesproutsn.ink.StoreUnavailable

/**
 * The CALENDAR point (arc 23 / Y1) — the host's **held** bind for one showing of the screen, the
 * pad's service shape with the placement made a real type. Every method: `HostCallerCheck.enforce`
 * first. `begin` holds the store binder for the screen's life in [CalendarSession] (a second `begin`
 * while one is held replaces it — the host restarted), declares the schema and reads the bookmark
 * on the Binder thread; the row counts are logged (the Y1 walk's "browsing wrote nothing" proof —
 * `sqlite3` cannot read a SQLCipher file). `end` clears everything.
 *
 * **The two transfers are `:ext-ink`'s** — [CalendarSession] is an `InkTransferSession`, and the
 * accumulate-and-place body, the running-total caps re-check, the one-monitor rule, the page bound
 * by the first chunk (a target that changes mid-transfer → `IllegalArgumentException`, the whole
 * inbound dropped) and the `"store unavailable"` mapping all live there, one copy shared with the
 * pad. What is the calendar's own is what is left here: the target's null check — it is already
 * through `requireValid` at unmarshal — and the wording of the logs. Both run on the **Binder
 * thread** and neither ever touches the paper: the screen is not up during a `receiveInk` (the host
 * sends and then launches) and it has already parked its ink before a `takeOutgoing`.
 *
 * `receiveInk` accumulates chunks until `last`, then mints fresh ids (`InkWire.toStrokes` — nothing
 * from the wire is trusted beyond its geometry) and places the lot through [CalendarStore.receive],
 * leaving `CalendarSession.received` for the screen to consume once. A page has no size ceiling —
 * the placement is one store transaction, so the only failure left is the store being gone.
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
            CalendarSession.begin(store)
            val summary = try {
                val s = CalendarStore(store)
                val at = s.open()
                val counts = s.counts()
                "at=${at?.let { "${it.kind}/${it.date}/${it.half}" } ?: "(none)"} rows: ${counts.periods} period(s), ${counts.pages} page(s), ${counts.strokes} stroke(s), ${counts.events} event(s)"
            } catch (e: StoreUnavailable) {
                "store unavailable (${e.message})"
            }
            Slog.d(TAG) { "begin: $summary in ${SystemClock.elapsedRealtime() - t0} ms" }
        }

        override fun receiveInk(chunk: InkBundle?, target: CalendarTarget?, last: Boolean) {
            enforce()
            requireNotNull(chunk) { "chunk is null" }
            requireNotNull(target) { "target is null" }   // already through requireValid at unmarshal
            val placed = CalendarSession.receiveChunk(chunk, target, last) { store, strokes, t ->
                CalendarStore(store).receive(strokes, t)
            } ?: return
            Slog.d(TAG) {
                "receiveInk: ${placed.strokes} strokes placed on ${target.kind}/${target.date}/${target.half} in ${placed.millis} ms"
            }
        }

        override fun takeOutgoing(chunkIndex: Int): InkBundle {
            enforce()
            // An index past the end is an empty bundle, not an error: "done" is exactly what the
            // host is asking about, and it probes one chunk past the budget on purpose.
            return CalendarSession.outgoing(chunkIndex)
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
    }
}

package com.symmetricalpalmtree.notesproutsn.extension

import android.content.Context
import android.os.IBinder

/**
 * The host's client for the one calendar (arc 23 / Y1) — the pad's held bind on `ICalendar`, and
 * since Y4 literally the pad's: every rule (the pre-open on IO, the uid-bound store binder, `begin`
 * under the call budget, the two-boolean Intent, the chunked send whose last chunk is settled rather
 * than believed, the drain under [TransferCaps.Drain], the settle-then-`end`-then-unbind-and-revoke
 * teardown) lives once in [HeldInkClient].
 *
 * All that is the calendar's own is [Point] — and the one shape difference the seam has: the pad's
 * placement is an `Int`, the calendar's is a real [CalendarTarget], which rides **every** chunk.
 */
class CalendarClient(context: Context, ref: ProviderRef) :
    HeldInkClient<ICalendar, CalendarTarget>(context, ref, Point) {

    /** The calendar's names and budgets — the whole of what makes a [HeldInkClient] this point's. */
    companion object Point : HeldInkPoint<ICalendar, CalendarTarget> {

        const val TAG = "CalendarClient"
        const val CALL_TIMEOUT_MS = 2_000L

        /** The last `receiveInk` chunk — the extension places the whole transfer inside this call.
         *  The pad's number, kept after Y3 measured 119 ms for 19 strokes on the Nomad. */
        const val PLACE_TIMEOUT_MS = 10_000L

        /** How long `finish` waits for a call a timeout orphaned before it tears the bind down —
         *  the placement budget again: a placement that has not returned in twice its budget is a
         *  hung extension, and the store is revoked under it as the lesser harm. */
        const val SETTLE_TIMEOUT_MS = PLACE_TIMEOUT_MS

        override val tag: String get() = TAG
        override val serviceAction: String get() = ExtensionContract.ACTION_CALENDAR
        override val screenAction: String get() = ExtensionContract.ACTION_CALENDAR_SCREEN
        override val sendEnabledExtra: String get() = ExtensionContract.EXTRA_CALENDAR_SEND_ENABLED
        override val openReceivedExtra: String get() = ExtensionContract.EXTRA_CALENDAR_OPEN_RECEIVED
        override val callTimeoutMs: Long get() = CALL_TIMEOUT_MS
        override val placeTimeoutMs: Long get() = PLACE_TIMEOUT_MS
        override val settleTimeoutMs: Long get() = SETTLE_TIMEOUT_MS

        override fun asInterface(binder: IBinder): ICalendar? = ICalendar.Stub.asInterface(binder)

        override fun begin(iface: ICalendar, store: IExtensionStore) = iface.begin(store)

        /** The [placement] is the page the ink lands on, and it rides every chunk: the extension
         *  refuses a transfer whose target changes mid-way, so one target is built and reused. */
        override fun receiveInk(iface: ICalendar, chunk: InkBundle, placement: CalendarTarget, last: Boolean) =
            iface.receiveInk(chunk, placement, last)

        override fun takeOutgoing(iface: ICalendar, chunkIndex: Int): InkBundle =
            iface.takeOutgoing(chunkIndex)

        override fun end(iface: ICalendar) = iface.end()

        override fun describe(placement: CalendarTarget): String =
            "target=${placement.kind}/${placement.date}/${placement.half}"
    }
}

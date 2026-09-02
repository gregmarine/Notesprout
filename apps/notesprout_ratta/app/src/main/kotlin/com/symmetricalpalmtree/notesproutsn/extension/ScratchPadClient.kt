package com.symmetricalpalmtree.notesproutsn.extension

import android.content.Context
import android.os.IBinder

/**
 * The host's client for the one scratch pad (arc 11 / J3) — SN's first **held** bind: the operation
 * is the showing of the extension's screen, so the bind brackets it.
 *
 * Every rule it follows — the pre-open on IO, the uid-bound store binder, `begin` under the call
 * budget, the two-boolean Intent, the chunked send with the last chunk's placement budget and its
 * settle, the drain under [TransferCaps.Drain], and the settle-then-`end`-then-unbind-and-revoke
 * teardown — is [HeldInkClient]'s, shared with the calendar (arc 23 / Y4). All that is the pad's own
 * is [Point]: the two actions, the two extras, `IScratchPad`'s four calls, and the budgets below.
 */
class ScratchPadClient(context: Context, ref: ProviderRef) :
    HeldInkClient<IScratchPad, Int>(context, ref, Point) {

    /** The pad's names and budgets — the whole of what makes a [HeldInkClient] this point's. */
    companion object Point : HeldInkPoint<IScratchPad, Int> {

        const val TAG = "ScratchPadClient"
        const val CALL_TIMEOUT_MS = 2_000L

        /** The last `receiveInk` chunk — the extension places the whole transfer inside this call.
         *  Still 10 s after arc 22 / X2: a placement is inserts now, cheaper than the whole-page
         *  re-encode it was first sized for. */
        const val PLACE_TIMEOUT_MS = 10_000L

        /** How long `finish` waits for a call a timeout orphaned before it tears the bind down — the
         *  placement budget again: a placement that has not returned in twice its budget is a hung
         *  extension, and the store is revoked under it as the lesser harm. */
        const val SETTLE_TIMEOUT_MS = PLACE_TIMEOUT_MS

        override val tag: String get() = TAG
        override val serviceAction: String get() = ExtensionContract.ACTION_SCRATCH_PAD
        override val screenAction: String get() = ExtensionContract.ACTION_SCRATCH_PAD_SCREEN
        override val sendEnabledExtra: String get() = ExtensionContract.EXTRA_SCRATCH_SEND_ENABLED
        override val openReceivedExtra: String get() = ExtensionContract.EXTRA_SCRATCH_OPEN_RECEIVED
        override val callTimeoutMs: Long get() = CALL_TIMEOUT_MS
        override val placeTimeoutMs: Long get() = PLACE_TIMEOUT_MS
        override val settleTimeoutMs: Long get() = SETTLE_TIMEOUT_MS

        override fun asInterface(binder: IBinder): IScratchPad? = IScratchPad.Stub.asInterface(binder)

        override fun begin(iface: IScratchPad, store: IExtensionStore) = iface.begin(store)

        /** [placement] is `PLACEMENT_NEW_PAGE` / `PLACEMENT_CURRENT_PAGE` — a scratch page has no
         *  size ceiling since arc 22 / X2, so there is no "page full" answer any more. */
        override fun receiveInk(iface: IScratchPad, chunk: InkBundle, placement: Int, last: Boolean) =
            iface.receiveInk(chunk, placement, last)

        override fun takeOutgoing(iface: IScratchPad, chunkIndex: Int): InkBundle =
            iface.takeOutgoing(chunkIndex)

        override fun end(iface: IScratchPad) = iface.end()

        override fun describe(placement: Int): String = "placement=$placement"
    }
}

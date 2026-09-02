package com.symmetricalpalmtree.notesproutsn.extension

import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.symmetricalpalmtree.notesproutsn.R

/**
 * The Scratch Pad's entry button (arc 11 / J4, its transfers J5) — both doors, the library's and the
 * notebook's, on [ExtensionScreenEntry]: visibility and re-discovery, the busy latch, the
 * "Opening…" wait, [beforeLaunch], both transfers' host half and the held bind's life are all that
 * class's, shared with the calendar since arc 23 / Y4.
 *
 * All that is the pad's own is what is below: its registry lookup, its client, its four strings and
 * its send result code.
 */
class ScratchPadEntry(
    activity: AppCompatActivity,
    button: View,
    /** True when this caller can receive ink back — the notebook. The pad's Send buttons exist only
     *  then, and a `RESULT_SCRATCH_SEND` can only arrive from a screen that had them. */
    sendEnabled: Boolean = false,
    /** Run immediately before the screen is launched — the notebook's `releaseForHandoff()`. */
    beforeLaunch: () -> Unit = {},
    /** An outbound send is across — fired **after** the last `receiveInk` returns, never at the tap. */
    onSent: () -> Unit = {},
    /** Ink the pad sent back, already sanitized and capped; the bind is finished the moment this
     *  returns. */
    onDrained: suspend (DrainedInk) -> Unit = {},
    /** The showing is over (Y4): the calendar's pad chain reopens the calendar on a plain close. */
    onClosed: (resultCode: Int) -> Unit = {},
) : ExtensionScreenEntry<IScratchPad, Int>(
    activity = activity,
    button = button,
    tag = TAG,
    discover = { ExtensionRegistry.scratchPad(it) },
    newClient = { context, ref -> ScratchPadClient(context, ref) },
    wording = WORDING,
    resultSend = ExtensionContract.RESULT_SCRATCH_SEND,
    sendEnabled = sendEnabled,
    beforeLaunch = beforeLaunch,
    onSent = onSent,
    onDrained = onDrained,
    onClosed = onClosed,
) {

    private companion object {
        const val TAG = "ScratchPadEntry"
        val WORDING = EntryWording(
            failedTitleRes = R.string.scratch_failed_title,
            failedBodyRes = R.string.scratch_failed_body,
            drainFailedTitleRes = R.string.scratch_drain_failed_title,
            drainFailedBodyRes = R.string.scratch_drain_failed_body,
        )
    }
}

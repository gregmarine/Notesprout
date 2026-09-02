package com.symmetricalpalmtree.notesproutsn.extension

import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.symmetricalpalmtree.notesproutsn.R

/**
 * The Calendar's entry button (arc 23 / Y1, its transfers Y3) — both doors, the library's and the
 * notebook's, on [ExtensionScreenEntry]: the pad's door shape, and since Y4 the pad's door itself.
 * Visibility and re-discovery, the busy latch, the "Opening…" wait, [beforeLaunch], both transfers'
 * host half and the held bind's life all live there, once.
 *
 * All that is the calendar's own is what is below: its registry lookup, its client (whose placement
 * is a real [CalendarTarget] where the pad's is an int), its four strings and its send result code.
 */
class CalendarEntry(
    activity: AppCompatActivity,
    button: View,
    /** True when this caller can receive ink back — the notebook (Y3). */
    sendEnabled: Boolean = false,
    /** Run immediately before the screen is launched — the notebook's `releaseForHandoff()`. */
    beforeLaunch: () -> Unit = {},
    /** An outbound send is across — fired **after** the last `receiveInk` returns, never at the tap. */
    onSent: () -> Unit = {},
    /** Ink the calendar sent back, already sanitized and capped; the bind is finished the moment this
     *  returns. */
    onDrained: suspend (DrainedInk) -> Unit = {},
    /** The showing is over (Y4): `RESULT_CALENDAR_OPEN_SCRATCH_PAD` asks the caller to open the pad
     *  and bring the calendar back afterwards. */
    onClosed: (resultCode: Int) -> Unit = {},
) : ExtensionScreenEntry<ICalendar, CalendarTarget>(
    activity = activity,
    button = button,
    tag = TAG,
    discover = { ExtensionRegistry.calendar(it) },
    newClient = { context, ref -> CalendarClient(context, ref) },
    wording = WORDING,
    resultSend = ExtensionContract.RESULT_CALENDAR_SEND,
    sendEnabled = sendEnabled,
    beforeLaunch = beforeLaunch,
    onSent = onSent,
    onDrained = onDrained,
    // The calendar's own Scratch Pad door (Y4): it exists only when the host finds a trusted pad —
    // discovery is the host's, an extension never queries for another.
    decorateIntent = { ctx, intent ->
        intent.putExtra(ExtensionContract.EXTRA_CALENDAR_SCRATCH_PAD_AVAILABLE, ExtensionRegistry.scratchPad(ctx) != null)
    },
    onClosed = onClosed,
) {

    private companion object {
        const val TAG = "CalendarEntry"
        val WORDING = EntryWording(
            failedTitleRes = R.string.calendar_failed_title,
            failedBodyRes = R.string.calendar_failed_body,
            drainFailedTitleRes = R.string.calendar_drain_failed_title,
            drainFailedBodyRes = R.string.calendar_drain_failed_body,
        )
    }
}

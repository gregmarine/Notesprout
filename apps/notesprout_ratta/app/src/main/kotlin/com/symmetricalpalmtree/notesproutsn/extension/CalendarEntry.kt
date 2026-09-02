package com.symmetricalpalmtree.notesproutsn.extension

import android.view.View
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.notesproutsn.R
import com.symmetricalpalmtree.notesproutsn.core.Dialogs
import com.symmetricalpalmtree.notesproutsn.core.OpeningOverlay
import com.symmetricalpalmtree.notesproutsn.core.Slog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The host's side of the Calendar entry button (arc 23 / Y1) — [ScratchPadEntry]'s shape, **one
 * class for both doors**, the library's (Y1) and the notebook's (Y3), because everything about them
 * is the same except the one line that is not: the notebook hands the EPD pipeline over before it
 * launches, and the library has no pipeline to hand over.
 *
 * What it owns:
 *  - **Visibility.** The button is `GONE` unless a trusted `CALENDAR` extension is installed, and
 *    discovery re-runs on every [refresh] (each `onResume`) **and after a failed open**. Never
 *    `isEnabled = false`: a disabled control is invisible on e-ink.
 *  - **The busy guard.** One showing at a time, latched at the tap.
 *  - **The wait.** [OpeningOverlay] goes up at tap time and the open runs only once its frame is on
 *    the glass — a cold open is seconds (SQLCipher's KDF creating the store) and a tap with no
 *    answer reads as a tap that missed.
 *  - **[beforeLaunch]**, run in the instant between the successful `begin` (and any outbound send)
 *    and the launch: the notebook's `paper.releaseForHandoff()` (Y3).
 *  - **Both transfers' host half** (Y3 wires the doors): [open] takes an optional [Send] — the ink
 *    goes over the **held bind** before the screen is launched, never the Intent — and a failure
 *    there stops the whole thing. Coming back, a `RESULT_CALENDAR_SEND` is drained on the bind that
 *    is *still held* and handed to [onDrained] before the bind is finished.
 *  - **The bind's life.** [CalendarClient.finish] runs from the result callback — after the drain —
 *    and from [close] as the backstop for a caller destroyed while the calendar is up.
 */
class CalendarEntry(
    private val activity: AppCompatActivity,
    private val button: View,
    /** True when this caller can receive ink back — the notebook (Y3). */
    private val sendEnabled: Boolean = false,
    /** Run immediately before the screen is launched — the notebook's `releaseForHandoff()`. */
    private val beforeLaunch: () -> Unit = {},
    /** An outbound [Send] is across — fired **after** the last `receiveInk` returns, never at the tap. */
    private val onSent: () -> Unit = {},
    /** Ink the calendar sent back, already sanitized and capped. Invoked on Main with the bind still
     *  held — finished the moment it returns. The caller guards its own liveness and does the pasting. */
    private val onDrained: suspend (CalendarClient.Drained) -> Unit = {},
) {

    /** Ink to hand the calendar **before** its screen is launched (Y3): the notebook's lasso
     *  selection, the page px size it was authored in, and the page it lands on. The whole-transfer
     *  caps are the caller's to check **before** any of this — a refusal must never cost a bind. */
    class Send(
        val strokes: List<Stroke>,
        val pageWidth: Float,
        val pageHeight: Float,
        val target: CalendarTarget,
    )

    private val launcher: ActivityResultLauncher<android.content.Intent> =
        // Registered from the caller's onCreate — a launcher may not be registered after STARTED.
        activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            onResult(result)
        }

    private var ref: ProviderRef? = null
    private var client: CalendarClient? = null

    /** Latched **at the tap**, released with the result (after any drain) or the moment the open fails. */
    private var opening = false

    /** Re-discover and show or hide the button. Called from the caller's `onResume` and after a failed open. */
    fun refresh() {
        activity.lifecycleScope.launch {
            val found = ExtensionRegistry.calendar(activity)
            if (activity.isFinishing || activity.isDestroyed) return@launch
            ref = found
            button.visibility = if (found == null) View.GONE else View.VISIBLE
        }
    }

    /** Whether a trusted extension is installed right now — what a selection toolbar reads (Y3). */
    val isAvailable: Boolean get() = ref != null

    /**
     * Tap, or a Send from the selection toolbar. Raises the box, then — behind it — pre-opens the
     * store on IO, holds the bind, `begin`s, hands over [send]'s ink if there is any, runs
     * [beforeLaunch] and launches the screen for a result. Any failure hides the box, explains
     * itself in a dialog and re-runs discovery.
     */
    fun open(send: Send? = null) {
        val provider = ref ?: return
        if (opening) { Slog.d(TAG) { "open: already showing" }; return }
        opening = true
        OpeningOverlay.showThen(activity) {
            activity.lifecycleScope.launch {
                val fresh = CalendarClient(activity, provider)
                client = fresh
                val intent = fresh.open(sendEnabled = sendEnabled, openReceived = send != null)
                if (activity.isFinishing || activity.isDestroyed) {
                    client = null; opening = false; fresh.finish(); return@launch
                }
                if (intent == null) {
                    fail(fresh, R.string.calendar_failed_title, R.string.calendar_failed_body)
                    return@launch
                }
                if (send != null && !handOver(fresh, send)) return@launch
                // The pipeline goes over the instant before the launch, and not one step earlier.
                beforeLaunch()
                launcher.launch(intent)
            }
        }
    }

    private suspend fun handOver(open: CalendarClient, send: Send): Boolean {
        val chunks = withContext(Dispatchers.Default) {
            TransferCaps.chunk(TransferCaps.toWireStrokes(send.strokes))
        }
        if (chunks.isEmpty()) {
            fail(open, R.string.calendar_failed_title, R.string.calendar_failed_body)
            return false
        }
        try {
            open.send(chunks, send.pageWidth, send.pageHeight, send.target)
            onSent()
        } catch (e: ExtensionCallException) {
            Slog.d(TAG) { "send failed: ${e.message}" }
            fail(open, R.string.calendar_failed_title, R.string.calendar_failed_body)
            return false
        }
        return true
    }

    private fun problem(titleRes: Int, bodyRes: Int) {
        if (activity.isFinishing || activity.isDestroyed) return
        Dialogs.problem(activity, titleRes, bodyRes)
    }

    /** Every failure path: release everything, take the box down, explain, re-run discovery. */
    private suspend fun fail(open: CalendarClient, titleRes: Int, bodyRes: Int) {
        client = null
        opening = false
        open.finish()
        if (activity.isFinishing || activity.isDestroyed) return
        OpeningOverlay.hide(activity)
        Dialogs.problem(activity, titleRes, bodyRes)
        refresh()
    }

    /** One showing is over. A `RESULT_CALENDAR_SEND` is drained on the bind that is **still held**,
     *  and only then is the client finished; [opening] is released last, after the drain. */
    private fun onResult(result: ActivityResult) {
        val open = client
        client = null
        Slog.d(TAG) { "calendar returned: resultCode=${result.resultCode}" }
        MainScope().launch {
            try {
                if (open != null && result.resultCode == ExtensionContract.RESULT_CALENDAR_SEND) {
                    val drained = runCatching { open.drainOutgoing() }
                        .onFailure { Slog.d(TAG) { "drain failed: ${it.message}" } }
                        .getOrNull()
                    try {
                        if (drained != null && drained.strokes.isNotEmpty()) onDrained(drained)
                        else problem(R.string.calendar_drain_failed_title, R.string.calendar_drain_failed_body)
                    } finally {
                        open.finish()
                    }
                } else {
                    open?.finish()
                }
            } finally {
                opening = false
            }
        }
    }

    /** The backstop: the bind must not outlive the screen that opened it. Called from the caller's `onDestroy`. */
    fun close() {
        opening = false
        val open = client ?: return
        client = null
        MainScope().launch { open.finish() }
    }

    private companion object {
        const val TAG = "CalendarEntry"
    }
}

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
 * The host's side of the Scratch Pad entry button (arc 11 / J4, grown with the transfers in J5) —
 * **one class for both doors**, the library's and the notebook's, because everything about them is
 * the same except the one line that is not: the notebook hands the EPD pipeline over before it
 * launches, and the library has no pipeline to hand over. The alternative was two near-identical
 * files, which is exactly the sibling-copy trap `:sn-screen` exists to keep out of this app.
 *
 * What it owns:
 *  - **Visibility.** The button is `GONE` unless a trusted `SCRATCH_PAD` extension is installed, and
 *    discovery re-runs on every [refresh] (each `onResume`) **and after a failed open** — a package
 *    can be disabled or replaced under us, and a button that lies is worse than one that is absent.
 *    Never `isEnabled = false`: a disabled control is invisible on e-ink.
 *  - **The busy guard.** One showing at a time. E-ink gives a tap no feedback for hundreds of ms, so
 *    the second tap is taken as read.
 *  - **The wait.** [OpeningOverlay] goes up at tap time and the open runs only once its frame is on
 *    the glass: a **cold** open measured 3 123 ms on the Nomad (SQLCipher's KDF creating the store)
 *    against 114 ms warm, and a tap with no answer for three seconds reads as a tap that missed.
 *    In the notebook this rides the C1 frame-silence exception — it is the same act as the Contents
 *    and Recents buttons, a deliberate chrome tap that raises a full-screen thing, and the tap has
 *    already released the render on its way through `dispatchTouchEvent`.
 *  - **[beforeLaunch]**, run in the instant between the successful `begin` (and any outbound send)
 *    and the launch: the notebook's `paper.releaseForHandoff()`. The pad releases its own before
 *    every `finish()`.
 *  - **Both transfers' host half (J5).** [open] takes an optional [Send] — the ink goes over the
 *    **held bind** before the screen is launched, and never rides the Intent — and a
 *    [ScratchPageFullException] there stops the whole thing: the dialog says so and the pad is not
 *    opened, because nothing was placed. Coming back, a `RESULT_SCRATCH_SEND` is drained on the bind
 *    that is *still held* and handed to [onDrained] before the bind is finished.
 *  - **The bind's life.** [ScratchPadClient.finish] runs from the result callback — after the drain,
 *    never before it — and from [close] as the backstop for a caller destroyed while the pad is up.
 *
 * The pad opens **no `.soil`**, and the notebook is **not** sealed behind it — the one way this hop
 * differs from arc 10's notebook switch. What the notebook gives up is the pipeline, not its data:
 * its session, its undo stack and its unsaved page are all still there when the result comes back,
 * which is exactly what J5's paste lands on.
 */
class ScratchPadEntry(
    private val activity: AppCompatActivity,
    private val button: View,
    /** True when this caller can receive ink back — the notebook. The pad's Send buttons exist only
     *  then, and a `RESULT_SCRATCH_SEND` can only arrive from a screen that had them. */
    private val sendEnabled: Boolean = false,
    /** Run immediately before the screen is launched — the notebook's `releaseForHandoff()`. */
    private val beforeLaunch: () -> Unit = {},
    /** An outbound [Send] is across (J5) — fired **after** the last `receiveInk` returns, never at
     *  the tap, so the caller's confirmation only ever confirms something that has happened. */
    private val onSent: () -> Unit = {},
    /** Ink the pad sent back (J5), already sanitized and capped. Runs on Main with the bind still
     *  held; the caller guards its own liveness and does the pasting. */
    private val onDrained: suspend (ScratchPadClient.Drained) -> Unit = {},
) {

    /** Ink to hand the pad **before** its screen is launched: the notebook's lasso selection, the
     *  page px size it was authored in, and where it should land ([ExtensionContract.PLACEMENT_NEW_PAGE]
     *  / [ExtensionContract.PLACEMENT_CURRENT_PAGE]). The whole-transfer caps are the caller's to
     *  check **before** any of this — a refusal must never cost a bind. */
    class Send(
        val strokes: List<Stroke>,
        val pageWidth: Float,
        val pageHeight: Float,
        val placement: Int,
    )

    private val launcher: ActivityResultLauncher<android.content.Intent> =
        // Registered from the caller's onCreate — a launcher may not be registered after STARTED.
        activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            onResult(result)
        }

    private var ref: ProviderRef? = null
    private var client: ScratchPadClient? = null

    /**
     * Latched **at the tap**, not when the client lands. E-ink gives a tap no feedback for hundreds
     * of ms so users tap twice, and the open is asynchronous twice over (a pre-draw hop, then the
     * store and the bind): a guard that watched [client] alone would still be open on the second
     * tap and start a second showing. Released with the result — **after** any drain has finished
     * — or the moment the open fails.
     */
    private var opening = false

    /**
     * Re-discover and show or hide the button. Called from the caller's `onResume` and after a
     * failed open. Discovery is IO; the button is left as it was until the answer arrives.
     */
    fun refresh() {
        activity.lifecycleScope.launch {
            val found = ExtensionRegistry.scratchPad(activity)
            if (activity.isFinishing || activity.isDestroyed) return@launch
            ref = found
            button.visibility = if (found == null) View.GONE else View.VISIBLE
        }
    }

    /** Whether a trusted extension is installed right now — what the notebook's selection toolbar
     *  reads to decide whether its Pad button has anywhere to send to. */
    val isAvailable: Boolean get() = ref != null

    /**
     * Tap, or a Send from the selection toolbar. Raises the box, then — behind it — pre-opens the
     * store on IO, holds the bind, `begin`s, hands over [send]'s ink if there is any, runs
     * [beforeLaunch] and launches the screen for a result. Any failure hides the box, explains
     * itself in a dialog (a tap that did nothing is never a toast on e-ink) and re-runs discovery,
     * so a package disabled under us takes its button with it.
     */
    fun open(send: Send? = null) {
        val provider = ref ?: return
        if (opening) { Slog.d(TAG) { "open: already showing" }; return }
        opening = true
        OpeningOverlay.showThen(activity) {
            activity.lifecycleScope.launch {
                val fresh = ScratchPadClient(activity, provider)
                client = fresh
                val intent = fresh.open(sendEnabled = sendEnabled, openReceived = send != null)
                if (activity.isFinishing || activity.isDestroyed) {
                    client = null; opening = false; fresh.finish(); return@launch
                }
                if (intent == null) {
                    fail(fresh, R.string.scratch_failed_title, R.string.scratch_failed_body)
                    return@launch
                }
                if (send != null && !handOver(fresh, send)) return@launch
                // The pipeline goes over the instant before the launch, and not one step earlier:
                // until here the open could still have failed and left this screen writing.
                beforeLaunch()
                launcher.launch(intent)
            }
        }
    }

    /**
     * The outbound half: chunk and hand the strokes over on the held bind. False = it did not go,
     * everything is already released and the pad was **not** opened — which is the honest answer,
     * because a `SCRATCH_PAGE_FULL` means nothing was placed.
     */
    private suspend fun handOver(open: ScratchPadClient, send: Send): Boolean {
        // Off Main: a full selection is 10 000 strokes of float copying, and the box is already up.
        val chunks = withContext(Dispatchers.Default) {
            TransferCaps.chunk(TransferCaps.toWireStrokes(send.strokes))
        }
        if (chunks.isEmpty()) {
            // Every stroke was point-less — nothing the wire can carry. Not reachable from a real
            // selection, but a silent no-op here would be a tap that did nothing.
            fail(open, R.string.scratch_failed_title, R.string.scratch_failed_body)
            return false
        }
        try {
            open.send(chunks, send.pageWidth, send.pageHeight, send.placement)
            onSent()
        } catch (e: ScratchPageFullException) {
            // Nothing was placed — so nothing is opened either, and the message says which page.
            fail(open, R.string.scratch_page_full_host_title, R.string.scratch_page_full_host_body)
            return false
        } catch (e: ExtensionCallException) {
            Slog.d(TAG) { "send failed: ${e.message}" }
            fail(open, R.string.scratch_failed_title, R.string.scratch_failed_body)
            return false
        }
        return true
    }

    /** Every failure path: release everything, take the box down, explain, re-run discovery. */
    private suspend fun fail(open: ScratchPadClient, titleRes: Int, bodyRes: Int) {
        client = null
        opening = false
        open.finish()
        if (activity.isFinishing || activity.isDestroyed) return
        OpeningOverlay.hide(activity)
        Dialogs.problem(activity, titleRes, bodyRes)
        refresh()   // it may have been disabled or replaced under us
    }

    /**
     * One showing is over. A `RESULT_SCRATCH_SEND` is drained on the bind that is **still held** —
     * that is the whole reason the bind is held across the screen — and only then is the client
     * finished. [opening] is released last, after the drain: a second launch mid-drain would
     * `begin()` a new showing and wipe the parked chunks.
     */
    private fun onResult(result: ActivityResult) {
        val open = client
        client = null
        Slog.d(TAG) { "scratch pad returned: resultCode=${result.resultCode}" }
        // A detached scope: `finish` has an `end()` call plus an unbind and a revoke to run, and the
        // caller may be on its way out.
        MainScope().launch {
            try {
                if (open != null && result.resultCode == ExtensionContract.RESULT_SCRATCH_SEND) {
                    val drained = runCatching { open.drainOutgoing() }
                        .onFailure { Slog.d(TAG) { "drain failed: ${it.message}" } }
                        .getOrNull()
                    open.finish()
                    if (drained != null && drained.strokes.isNotEmpty()) onDrained(drained)
                } else {
                    open?.finish()
                }
            } finally {
                opening = false
            }
        }
    }

    /** The backstop: the bind must not outlive the screen that opened it, result or no result.
     *  Called from the caller's `onDestroy`. */
    fun close() {
        opening = false
        val open = client ?: return
        client = null
        MainScope().launch { open.finish() }
    }

    private companion object {
        const val TAG = "ScratchPadEntry"
    }
}

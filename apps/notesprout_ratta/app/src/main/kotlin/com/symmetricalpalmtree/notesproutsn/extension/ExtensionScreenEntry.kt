package com.symmetricalpalmtree.notesproutsn.extension

import android.content.Context
import android.view.View
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.notesproutsn.core.Dialogs
import com.symmetricalpalmtree.notesproutsn.core.OpeningOverlay
import com.symmetricalpalmtree.notesproutsn.core.Slog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Ink to hand an extension **before** its screen is launched: the notebook's lasso selection, the
 *  page px size it was authored in, and where it should land ([P] — the pad's `PLACEMENT_*` int, the
 *  calendar's [CalendarTarget]). The whole-transfer caps are the caller's to check **before** any of
 *  this — a refusal must never cost a bind. */
class InkSend<P>(
    val strokes: List<Stroke>,
    val pageWidth: Float,
    val pageHeight: Float,
    val placement: P,
)

/** The four strings an entry says in its extension's name — with the result code and the registry
 *  lookup, the whole of what one entry differs from another by. */
class EntryWording(
    val failedTitleRes: Int,
    val failedBodyRes: Int,
    val drainFailedTitleRes: Int,
    val drainFailedBodyRes: Int,
)

/**
 * The host's side of **one door to one ink-carrying, screen-owning extension** — the scratch pad's
 * (arc 11 / J4, grown with the transfers in J5) and the calendar's (arc 23 / Y1, its transfers Y3),
 * one class since Y4 because everything either of them owns is the same: two near-identical files is
 * exactly the sibling-copy trap `:sn-screen` exists to keep out of this app, and the drift it had
 * already grown (the settle rule, landed on the calendar's copy alone) is what closed them.
 *
 * It is also **one class for both of each point's doors**, the library's and the notebook's, for the
 * same reason it always was: everything about them is the same except the one line that is not — the
 * notebook hands the EPD pipeline over before it launches ([beforeLaunch]), and the library has no
 * pipeline to hand over.
 *
 * What it owns:
 *  - **Visibility.** The button is `GONE` unless [discover] finds a trusted extension, and discovery
 *    re-runs on every [refresh] (each `onResume`) **and after a failed open** — a package can be
 *    disabled or replaced under us, and a button that lies is worse than one that is absent. Never
 *    `isEnabled = false`: a disabled control is invisible on e-ink.
 *  - **The busy guard.** One showing at a time. E-ink gives a tap no feedback for hundreds of ms, so
 *    the second tap is taken as read.
 *  - **The wait.** [OpeningOverlay] goes up at tap time and the open runs only once its frame is on
 *    the glass: a **cold** open measured 3 123 ms on the Nomad (SQLCipher's KDF creating the store)
 *    against 114 ms warm, and a tap with no answer for three seconds reads as a tap that missed.
 *    In the notebook this rides the C1 frame-silence exception — it is the same act as the Contents
 *    and Recents buttons, a deliberate chrome tap that raises a full-screen thing, and the tap has
 *    already released the render on its way through `dispatchTouchEvent`.
 *  - **[beforeLaunch]**, run in the instant between the successful `begin` (and any outbound send)
 *    and the launch: the notebook's `paper.releaseForHandoff()`. The extension releases its own
 *    before every `finish()`.
 *  - **Both transfers' host half.** [open] takes an optional [InkSend] — the ink goes over the
 *    **held bind** before the screen is launched, and never rides the Intent — and a failure there
 *    stops the whole thing: the dialog says so and the screen is not opened, because nothing was
 *    placed. Coming back, [resultSend] is drained on the bind that is *still held* and handed to
 *    [onDrained] before the bind is finished.
 *  - **The bind's life.** [HeldInkClient.finish] runs from the result callback — after the drain,
 *    never before it — and from [close] as the backstop for a caller destroyed while the screen is up.
 *
 * Neither extension opens a `.soil`, and the notebook is **not** sealed behind either — the one way
 * this hop differs from arc 10's notebook switch. What the notebook gives up is the pipeline, not its
 * data: its session, its undo stack and its unsaved page are all still there when the result comes
 * back, which is exactly what the transfer paste lands on.
 */
open class ExtensionScreenEntry<I : Any, P>(
    private val activity: AppCompatActivity,
    private val button: View,
    /** This entry's own log tag — counts and result codes, never a stroke. */
    private val tag: String,
    /** The point's `ExtensionRegistry` lookup: the one trusted provider, or null. */
    private val discover: suspend (Context) -> ProviderRef?,
    /** How one showing's client is minted — the point's own [HeldInkClient] subclass. */
    private val newClient: (Context, ProviderRef) -> HeldInkClient<I, P>,
    /** The four strings this door says when something goes wrong. */
    private val wording: EntryWording,
    /** The result code that means "there is ink to drain" (`RESULT_SCRATCH_SEND` / `RESULT_CALENDAR_SEND`). */
    private val resultSend: Int,
    /** True when this caller can receive ink back — the notebook. The extension's Send buttons exist
     *  only then, and [resultSend] can only arrive from a screen that had them. */
    private val sendEnabled: Boolean,
    /** Run immediately before the screen is launched — the notebook's `releaseForHandoff()`. */
    private val beforeLaunch: () -> Unit,
    /** An outbound [InkSend] is across — fired **after** the last `receiveInk` returns, never at the
     *  tap, so the caller's confirmation only ever confirms something that has happened. */
    private val onSent: () -> Unit,
    /** Ink the extension sent back, already sanitized and capped. **Invoked** on Main with the bind
     *  still held — but the bind is finished the moment it returns, so anything needing the extension
     *  must happen before then, not in work this callback defers (the notebook's paste reads only the
     *  materialised [DrainedInk], so it is free to). The caller guards its own liveness and does the
     *  pasting. */
    private val onDrained: suspend (DrainedInk) -> Unit,
) {

    private val launcher: ActivityResultLauncher<android.content.Intent> =
        // Registered from the caller's onCreate — a launcher may not be registered after STARTED.
        activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            onResult(result)
        }

    private var ref: ProviderRef? = null
    private var client: HeldInkClient<I, P>? = null

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
            val found = discover(activity)
            if (activity.isFinishing || activity.isDestroyed) return@launch
            ref = found
            button.visibility = if (found == null) View.GONE else View.VISIBLE
        }
    }

    /** Whether a trusted extension is installed right now — what the notebook's selection toolbar
     *  reads to decide whether its button has anywhere to send to. */
    val isAvailable: Boolean get() = ref != null

    /**
     * Tap, or a Send from the selection toolbar. Raises the box, then — behind it — pre-opens the
     * store on IO, holds the bind, `begin`s, hands over [send]'s ink if there is any, runs
     * [beforeLaunch] and launches the screen for a result. Any failure hides the box, explains
     * itself in a dialog (a tap that did nothing is never a toast on e-ink) and re-runs discovery,
     * so a package disabled under us takes its button with it.
     */
    fun open(send: InkSend<P>? = null) {
        val provider = ref ?: return
        if (opening) { Slog.d(tag) { "open: already showing" }; return }
        opening = true
        OpeningOverlay.showThen(activity) {
            activity.lifecycleScope.launch {
                val fresh = newClient(activity, provider)
                client = fresh
                val intent = fresh.open(sendEnabled = sendEnabled, openReceived = send != null)
                if (activity.isFinishing || activity.isDestroyed) {
                    client = null; opening = false; fresh.finish(); return@launch
                }
                if (intent == null) {
                    fail(fresh)
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
     * everything is already released and the screen was **not** opened — which is the honest answer,
     * because a placement is one store transaction: it landed whole or not at all.
     */
    private suspend fun handOver(open: HeldInkClient<I, P>, send: InkSend<P>): Boolean {
        // Off Main: a full selection is 10 000 strokes of float copying, and the box is already up.
        val chunks = withContext(Dispatchers.Default) {
            TransferCaps.chunk(TransferCaps.toWireStrokes(send.strokes))
        }
        if (chunks.isEmpty()) {
            // Every stroke was point-less — nothing the wire can carry. Not reachable from a real
            // selection, but a silent no-op here would be a tap that did nothing.
            fail(open)
            return false
        }
        try {
            open.send(chunks, send.pageWidth, send.pageHeight, send.placement)
            onSent()
        } catch (e: ExtensionCallException) {
            Slog.d(tag) { "send failed: ${e.message}" }
            fail(open)
            return false
        }
        return true
    }

    /** Explain a failure that has nothing left to release (the extension has already closed itself). */
    private fun problem(titleRes: Int, bodyRes: Int) {
        if (activity.isFinishing || activity.isDestroyed) return
        Dialogs.problem(activity, titleRes, bodyRes)
    }

    /** Every failure path: release everything, take the box down, explain, re-run discovery. */
    private suspend fun fail(open: HeldInkClient<I, P>) {
        client = null
        opening = false
        open.finish()
        if (activity.isFinishing || activity.isDestroyed) return
        OpeningOverlay.hide(activity)
        Dialogs.problem(activity, wording.failedTitleRes, wording.failedBodyRes)
        refresh()   // it may have been disabled or replaced under us
    }

    /**
     * One showing is over. A [resultSend] is drained on the bind that is **still held** — that is the
     * whole reason the bind is held across the screen — and only then is the client finished.
     * [opening] is released last, after the drain: a second launch mid-drain would `begin()` a new
     * showing and wipe the parked chunks.
     */
    private fun onResult(result: ActivityResult) {
        val open = client
        client = null
        Slog.d(tag) { "screen returned: resultCode=${result.resultCode}" }
        // A detached scope: `finish` has an `end()` call plus an unbind and a revoke to run, and the
        // caller may be on its way out.
        MainScope().launch {
            try {
                if (open != null && result.resultCode == resultSend) {
                    val drained = runCatching { open.drainOutgoing() }
                        .onFailure { Slog.d(tag) { "drain failed: ${it.message}" } }
                        .getOrNull()
                    try {
                        // The extension already closed saying it sent something. Nothing arriving —
                        // a dead bind, a timeout mid-drain, or an empty reply — is a tap that did
                        // nothing, and on e-ink that reads as broken. The ink is still over there;
                        // say so.
                        if (drained != null && drained.strokes.isNotEmpty()) onDrained(drained)
                        else problem(wording.drainFailedTitleRes, wording.drainFailedBodyRes)
                    } finally {
                        // After `onDrained`, per the contract above: the callback may still read the
                        // bind, and `finish()` revokes the store binder along with it.
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

    /** The backstop: the bind must not outlive the screen that opened it, result or no result.
     *  Called from the caller's `onDestroy`. */
    fun close() {
        opening = false
        val open = client ?: return
        client = null
        MainScope().launch { open.finish() }
    }
}

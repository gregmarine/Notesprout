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
import com.symmetricalpalmtree.notesproutsn.R
import com.symmetricalpalmtree.notesproutsn.core.OpeningOverlay
import com.symmetricalpalmtree.notesproutsn.core.RecognizingOverlay
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.data.prefs.ChromePrefs
import com.symmetricalpalmtree.notesproutsn.data.prefs.PenShadePrefs
import com.symmetricalpalmtree.notesproutsn.data.prefs.Surface
import com.symmetricalpalmtree.notesproutsn.data.prefs.SurfaceEntry
import com.symmetricalpalmtree.notesproutsn.data.prefs.SurfaceStack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

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
    /** The screen closed asking the host to walk through a door, and would not say which page it
     *  meant (arc 31 / HV4 — the calendar's Export). A point with no such door says what its drain
     *  failure says: it is the same shape of nothing coming back. */
    /** The box that stands over the caller while a send is drained and landed (arc 35 / HA1 — a
     *  two-page Day send takes long enough on e-ink to read as a hang without it). */
    val receivingRes: Int = R.string.transfer_receiving,
    val exportFailedTitleRes: Int = drainFailedTitleRes,
    val exportFailedBodyRes: Int = drainFailedBodyRes,
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
 *    [onDrained] before the bind is finished — with the page's **paper** beside the ink when the
 *    point carries it and the send was a whole page ([paperOnPageSend], arc 31 / HV5).
 *  - **The bind's life.** [HeldInkClient.finish] runs from the result callback — after the drain,
 *    never before it — and from [close] as the backstop for a caller destroyed while the screen is up.
 *  - **Its place on the surface stack** (arc 32 / RS1). An extension screen is another process, so
 *    it has no lifecycle of its own here to maintain it from: the entry does it instead. The
 *    [stackEntry] is pushed the instant [launcher] has actually launched — never at the tap, where
 *    the open can still fail with nothing on the glass — and popped at the very top of [onResult],
 *    synchronously, because a result callback runs **before** the host's `onResume` and that
 *    `markTop` must find this entry already gone. [close] pops as the backstop. Never from an
 *    `onDestroy`, the extension's least of all: a killed process gets none, which is the whole
 *    point of the stack.
 *  - **The chrome flag, both ways** (arc 33 / F3). Both of these screens are paper screens, and
 *    "hide the chrome" is one global way of working ([ChromePrefs]): [open] hands the flag over as
 *    [ExtensionContract.EXTRA_CHROME_HIDDEN] on every launch — entry-level, so every door on both
 *    hosts carries it with no per-entry code — and [onResult] reads the state the screen was left
 *    in off the result Intent ([ChromeResult]) and persists it **synchronously, first**, before the
 *    launched coroutine: the calendar's pad chain ([onClosed] → the pad's [open]) must hand the pad
 *    the value the calendar just reported, and the host's own `onResume` re-sync runs after this
 *    callback. No data (a killed process, an older extension) writes nothing.
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
    /** Which surface this door raises, for the surface stack (arc 32 / RS1). */
    private val surface: Surface,
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
    /** Run when the launch itself was refused **after** [beforeLaunch] had run (arc 34 / M8) — the
     *  notebook's `resumeDrawing()`, the re-arm its `onResume` does after a real return; the
     *  refusal leaves this screen resumed, so nothing else would. */
    private val afterLaunchFailed: () -> Unit = {},
    /** An outbound [InkSend] is across — fired **after** the last `receiveInk` returns, never at the
     *  tap, so the caller's confirmation only ever confirms something that has happened. */
    private val onSent: () -> Unit,
    /** Ink the extension sent back, already sanitized and capped. **Invoked** on Main with the bind
     *  still held — but the bind is finished the moment it returns, so anything needing the extension
     *  must happen before then, not in work this callback defers (the notebook's paste reads only the
     *  materialised [DrainedInk], so it is free to). The caller guards its own liveness and does the
     *  pasting. */
    private val onDrained: suspend (List<DrainedInk>) -> Unit,
    /** Anything else the screen's Intent should carry — booleans only, by the seam's rule (the
     *  calendar's "a pad is installed", arc 23 / Y4). Runs after `begin` succeeded, before launch.
     *  The [ProviderRef] is handed in because one of those booleans is about the extension itself
     *  (arc 31 / HV4: whether this calendar declares the API version that can draw a page). */
    private val decorateIntent: suspend (Context, ProviderRef, android.content.Intent) -> Unit = { _, _, _ -> },

    /**
     * The result code that means *the screen closed asking the host to export the page it was
     * showing* (arc 31 / HV4 — `RESULT_CALENDAR_EXPORT`), or null for a point with no such door.
     * Handled exactly as [resultSend] is: the page is read on the bind that is still held, the bind
     * is finished, and only then does [onExport] run.
     */
    private val resultExport: Int? = null,

    /** The page the extension asked to have exported, read off the held bind. Runs on Main with the
     *  bind already finished — everything it needs is in the target it is handed. */
    private val onExport: (P) -> Unit = {},

    /**
     * True when a **whole-page** send from this point comes back with the page's paper as well as
     * its ink (arc 31 / HV5 — the calendar's grid). The extension is asked for the page it sent
     * ([HeldInkClient.outgoingTarget], null after a selection send, which is what keeps a selection
     * ink-only) and then to draw it ([HeldInkClient.renderPaper]); both happen on the bind that is
     * still held, before it is finished. Gated again at the tap on the version the drawing call was
     * born at — a point that declares less has no `render` to be asked for.
     */
    private val paperOnPageSend: Boolean = false,
    /** The showing is over and the bind is finished; [opening] is already released, so the caller
     *  may open another door from here (the calendar's pad chain, arc 23 / Y4). The result code is
     *  the screen's own — a drained send, a cancel, or a door it asked the host to walk through. */
    private val onClosed: (resultCode: Int) -> Unit = {},
) {

    private val launcher: ActivityResultLauncher<android.content.Intent> =
        // Registered from the caller's onCreate — a launcher may not be registered after STARTED.
        activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            onResult(result)
        }

    private var ref: ProviderRef? = null
    private var client: HeldInkClient<I, P>? = null

    /** The surface stack this door pushes onto (arc 32 / RS1) — the prefs door, nothing more. */
    private val stack = SurfaceStack(activity)

    /** The one global chrome flag (arc 33 / F3) — out on the launch Intent, back off the result. */
    private val chromePrefs = ChromePrefs(activity)

    /** The one device-wide pen shade (arc 49 / P4) — the chrome flag's shape: out on the launch
     *  Intent, back off the result, persisted here so the host screen's `onResume` re-arms from it. */
    private val penShadePrefs = PenShadePrefs(activity)

    /**
     * One token per **entry instance**, not per surface: a host holds one entry per door for its
     * whole life, and it can raise its screen many times — the same token each time is exactly
     * right, because only one of those showings can ever be up at once (the [opening] latch). A
     * second door on another host screen is a second instance and mints its own.
     */
    private val token: String = UUID.randomUUID().toString()

    /** This door's entry on the stack. Public because the calendar's pad chain re-attaches it from
     *  the host: the latch that brings the calendar back is persisted structurally, as a `CALENDAR`
     *  entry beneath the pad's (arc 32 / RS1, D1). */
    val stackEntry: SurfaceEntry get() = SurfaceEntry(token, surface)

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
        activity.lifecycleScope.launch { discovered() }
    }

    /**
     * [refresh]'s body as something a caller can **await** (arc 32 / RS2): the same discovery, the
     * same [ref] and the same button visibility, answering whether a trusted extension was found.
     *
     * The cold-launch replay needs this rather than [isAvailable]: `refresh()`'s discovery is IO in
     * one coroutine and the replay is another, so which of them finishes first is a race — a reopen
     * reading [isAvailable] would drop the screen roughly half the time. A caller that awaits this
     * has the answer by definition. False also when the caller went away mid-discovery, so the
     * result doubles as "still worth opening".
     */
    suspend fun discovered(): Boolean {
        val found = discover(activity)
        if (activity.isFinishing || activity.isDestroyed) return false
        ref = found
        button.visibility = if (found == null) View.GONE else View.VISIBLE
        return found != null
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
                decorateIntent(activity, provider, intent)
                // Arc 33 / F3: the screen opens in the chrome state the person is working in.
                intent.putExtra(ExtensionContract.EXTRA_CHROME_HIDDEN, chromePrefs.hidden)
                // Arc 49 / P4: …and writing in the shade they are writing in.
                intent.putExtra(ExtensionContract.EXTRA_PEN_SHADE, penShadePrefs.level)
                if (send != null && !handOver(fresh, send)) return@launch
                // The pipeline goes over the instant before the launch, and not one step earlier:
                // until here the open could still have failed and left this screen writing. The
                // launch itself can still be refused (arc 34 / M8 — `open` validated the service,
                // not the screen): an Intent that does not resolve is found out BEFORE the pipeline
                // goes over, and a refusal thrown by the launch re-arms it and takes the ordinary
                // failure road — a dialog, never a crash, never a leaked showing.
                val launched = ScreenLaunch.attempt(
                    resolves = { intent.resolveActivity(activity.packageManager) != null },
                    beforeLaunch = beforeLaunch,
                    launch = { launcher.launch(intent) },
                    afterFailure = afterLaunchFailed,
                )
                if (launched != ScreenLaunch.Outcome.Launched) {
                    Slog.d(tag) { "launch refused: ${(launched as? ScreenLaunch.Outcome.Refused)?.cause?.javaClass?.simpleName ?: "unresolved"}" }
                    fail(fresh)
                    return@launch
                }
                // On the stack only once the launch has actually happened (arc 32 / RS1): every
                // path above this line leaves nothing on the glass, so there is nothing to restore.
                stack.attach(stackEntry)
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
        // First, and synchronously (arc 32 / RS1): a result callback runs **before** the host's
        // own `onResume`, whose `markTop` drops everything above it — this entry must already be
        // gone by then, or the host would drop it as if it were still showing.
        stack.pop(token)
        // Second, still synchronously (arc 33 / F3): the state the screen left its chrome in is
        // persisted before anything else runs — `onClosed` may open the next door from the value,
        // and the host's `onResume` re-syncs from it. Absent (a dead process, an older extension)
        // writes nothing: the flag the person set stands.
        ChromeResult.read(result.data)?.let { chromePrefs.hidden = it }
        // Arc 49 / P4, the same synchronous rule: the shade the screen left its pen on is the
        // device's before the host's `onResume` re-arms its own pen from it. Absent writes nothing.
        PenShadeResult.read(result.data)?.let { penShadePrefs.level = it }
        val open = client
        client = null
        Slog.d(tag) { "screen returned: resultCode=${result.resultCode}" }
        // A detached scope: `finish` has an `end()` call plus an unbind and a revoke to run, and the
        // caller may be on its way out.
        MainScope().launch {
            try {
                if (open != null && result.resultCode == resultSend) {
                    // The wait is visible from the first frame back (arc 35 / HA1): the drain, the
                    // paper and the landing together run for seconds on a two-page send, and a
                    // screen that sits still for that long reads as broken on e-ink. The box also
                    // swallows the second tap the wait invites. Taken down in `finally` below.
                    if (!activity.isFinishing && !activity.isDestroyed) RecognizingOverlay.show(activity, wording.receivingRes)
                    var drained = runCatching { open.drainOutgoing() }
                        .onFailure { Slog.d(tag) { "drain failed: ${it.message}" } }
                        .getOrNull()
                    try {
                        // Inside the `try`: the paper is read on the same held bind, and whatever
                        // it answers, `finish()` below still ends the showing.
                        if (drained != null) drained = withPaperIfWholePage(open, drained)
                        // The pages behind the first (arc 35 / HA1 — a Day send parks both halves):
                        // asked only of a point that declares the version the question was born
                        // at, each drained and papered exactly as the first, in the order the
                        // extension parked them. A failure mid-way keeps what arrived: pages that
                        // landed are a smaller wrong than a send that vanished.
                        val pages = ArrayList<DrainedInk>(2)
                        if (drained != null) pages += drained
                        if (drained != null) pages += drainFurtherPages(open)
                        // The extension already closed saying it sent something. Nothing arriving —
                        // a dead bind, a timeout mid-drain, or an empty reply — is a tap that did
                        // nothing, and on e-ink that reads as broken. The ink is still over there;
                        // say so. Since HV5 a whole page may arrive with no ink at all and still be
                        // something: its paper is the thing the send was for.
                        val landing = pages.filter { it.strokes.isNotEmpty() || it.paper != null }
                        if (landing.isNotEmpty()) onDrained(landing)
                        else problem(wording.drainFailedTitleRes, wording.drainFailedBodyRes)
                    } finally {
                        RecognizingOverlay.hide(activity)
                        // After `onDrained`, per the contract above: the callback may still read the
                        // bind, and `finish()` revokes the store binder along with it.
                        open.finish()
                    }
                } else if (open != null && resultExport != null && result.resultCode == resultExport) {
                    // The same shape as the drain (arc 31 / HV4): the answer lives on the held
                    // bind, so it is asked for before `finish()` takes the bind and the store away.
                    val target = try {
                        runCatching { open.outgoingTarget() }
                            .onFailure { Slog.d(tag) { "the export target could not be read: ${it.message}" } }
                            .getOrNull()
                    } finally {
                        open.finish()
                    }
                    // Nothing came back — a dead bind, a timeout, or a calendar that closed on the
                    // code without parking anything. A door that opens on nothing is a tap that did
                    // nothing, and on e-ink that reads as broken; say so instead.
                    if (target != null) onExport(target)
                    else problem(wording.exportFailedTitleRes, wording.exportFailedBodyRes)
                } else {
                    open?.finish()
                }
            } finally {
                opening = false
            }
            if (!activity.isFinishing && !activity.isDestroyed) onClosed(result.resultCode)
        }
    }

    /**
     * Every page parked behind the first (arc 35 / HA1), each drained and papered like the first,
     * on the held bind. Gated on [ExtensionContract.MIN_API_VERSION_FOR_CALENDAR_DAY_SEND] — a point
     * declaring less has no `advanceOutgoing` to be asked, and answers nothing further. Bounded by
     * [MAX_FURTHER_PAGES] against an extension that keeps saying yes. A failure at any step is
     * logged and ends the loop with what arrived.
     */
    private suspend fun drainFurtherPages(open: HeldInkClient<I, P>): List<DrainedInk> {
        val version = ref?.apiVersion ?: return emptyList()
        if (version < ExtensionContract.MIN_API_VERSION_FOR_CALENDAR_DAY_SEND) return emptyList()
        val more = ArrayList<DrainedInk>(1)
        while (more.size < MAX_FURTHER_PAGES) {
            val advanced = runCatching { open.advanceOutgoing() }
                .onFailure { Slog.d(tag) { "advance failed: ${it.message}" } }
                .getOrNull() ?: break
            if (!advanced) break
            val next = runCatching { open.drainOutgoing() }
                .onFailure { Slog.d(tag) { "drain of a further page failed: ${it.message}" } }
                .getOrNull() ?: break
            more += withPaperIfWholePage(open, next)
        }
        return more
    }

    /**
     * The drained ink with the page's paper on it, when this point carries paper on a whole-page
     * send and this showing was a whole-page send (arc 31 / HV5). Both questions are the
     * extension's own: [HeldInkClient.outgoingTarget] answers null after a selection send, which is
     * the only thing that tells the two apart, and everything here runs on the bind that is still
     * held — [HeldInkClient.finish] is what takes the store and the bind away.
     *
     * A failure at either step is **logged and dropped**, and the drain travels on as it is: ink
     * that landed on the displayed page is a smaller wrong than a send that did nothing. An empty
     * send whose paper failed carries nothing, and the caller's "nothing arrived" road says so.
     */
    private suspend fun withPaperIfWholePage(open: HeldInkClient<I, P>, drained: DrainedInk): DrainedInk {
        if (!paperOnPageSend) return drained
        val version = ref?.apiVersion ?: return drained
        if (version < ExtensionContract.MIN_API_VERSION_FOR_CALENDAR_RENDER) return drained
        // The page size is the extension's own answer, and it is what the paper is asked for at;
        // a drain that came back without one has nothing to draw against.
        val width = drained.pageWidth.toInt()
        val height = drained.pageHeight.toInt()
        if (width <= 0 || height <= 0) return drained
        val target = runCatching { open.outgoingTarget() }
            .onFailure { Slog.d(tag) { "the sent page could not be read: ${it.message}" } }
            .getOrNull() ?: return drained
        val paper = runCatching { open.renderPaper(target, width, height, ExtensionContract.RENDER_GRID) }
            .onFailure { Slog.d(tag) { "the paper could not be drawn: ${it.message}" } }
            .getOrNull() ?: return drained
        return drained.withPaper(paper)
    }

    private companion object {
        /** A Day is two halves; anything past that is an extension that lost count. */
        const val MAX_FURTHER_PAGES = 3
    }

    /** The backstop: the bind must not outlive the screen that opened it, result or no result.
     *  Called from the caller's `onDestroy`. */
    fun close() {
        opening = false
        // The stack's backstop too (arc 32 / RS1) — unconditional, before the early return: a door
        // whose caller is going away has nothing left to restore, showing or not.
        stack.pop(token)
        val open = client ?: return
        client = null
        MainScope().launch { open.finish() }
    }
}

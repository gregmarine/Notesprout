package com.symmetricalpalmtree.notesproutsn.extension

import android.content.Intent
import android.view.View
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.notesproutsn.R
import com.symmetricalpalmtree.notesproutsn.core.Dialogs
import com.symmetricalpalmtree.notesproutsn.core.OpeningOverlay
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.data.prefs.ChromePrefs
import com.symmetricalpalmtree.notesproutsn.data.prefs.Surface
import com.symmetricalpalmtree.notesproutsn.data.prefs.SurfaceEntry
import com.symmetricalpalmtree.notesproutsn.data.prefs.SurfaceStack
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * The host's side of the Sketch door (arc 43 / K4) — **[DocumentEditorEntry] plus
 * [ExtensionScreenEntry]'s paper pieces**, which is exactly what this point is: a screen-owning
 * extension that reaches back through a host callback binder (the editor's shape — nothing to hand
 * over before the launch, nothing to drain on the way back) and that **draws** (the pad's shape —
 * the EPD pipeline goes over the instant before the launch and is reclaimed on the way home, and
 * the one global chrome flag travels both ways).
 *
 * What it owns:
 *  - **Visibility, on two conditions.** The button is `GONE` unless this notebook is a Sketch
 *    notebook ([offered], decision 9 — the door exists nowhere else) **and** a trusted
 *    `ACTION_SKETCH` extension is installed. Discovery re-runs on every [refresh] (each
 *    `onResume`) and after a failed open — a package can be disabled or replaced under us, and a
 *    button that lies is worse than one that is absent. Never `isEnabled = false`: a disabled
 *    control is invisible on e-ink.
 *  - **The busy guard**, latched **at the tap**: e-ink gives a tap no feedback for hundreds of ms,
 *    so the second tap is taken as read.
 *  - **The wait.** [OpeningOverlay] goes up at tap time and the open runs only once its frame is on
 *    the glass. In the notebook this rides the same frame-silence exception the Contents, Recents
 *    and Scratch Pad buttons do: a deliberate chrome tap that raises a full-screen thing.
 *  - **The order of the hand-over**, which is the whole of what this door has to get right:
 *    **drain → open (bind + `begin`) → chrome extra → [beforeLaunch] (dismiss the floating chrome,
 *    end a running transform, `releaseForHandoff`) → launch.** Everything above the launch can
 *    still fail with nothing on the glass, so the pipeline is the last thing to go; and a launch
 *    the system refuses *after* it has gone is put back by [afterLaunchFailed] ([ScreenLaunch],
 *    arc 34 / M8) rather than left for an `onResume` that will never come.
 *  - **The way home**: pop the stack → persist the chrome flag the screen left → [reclaimPipeline]
 *    → the teardown [Job] → [onClosed]. The first three are synchronous because a result callback
 *    runs **before** the caller's `onResume`, and all three are things that `onResume` would
 *    otherwise do too late.
 *  - **The bind's life.** [SketchClient.finish] runs from the result callback and from [close] as
 *    the backstop for a caller destroyed while the screen is up. There is no drain to sequence it
 *    after — a save is committed by the time `saveSketchChunk` returns, which is exactly what the
 *    callback binder buys. [finishJob] is what the caller's seal joins: flush-before-seal.
 *  - **Its place on the surface stack** (arc 32 / RS1) — pushed the instant [launcher] has actually
 *    launched, popped at the very top of [onResult], never from an `onDestroy`.
 *  - **The reconnect.** A host killed behind the live sketch screen leaves the extension holding
 *    pixels and a dead binder. [reconnect] re-opens the client without launching anything, and the
 *    fresh `begin` is what the extension re-pushes its parked save against.
 */
class SketchEntry(
    private val activity: AppCompatActivity,
    private val button: View,
    /** The open notebook's read and write half, handed to the [SketchHostBinder] minted per
     *  showing. Its methods all run on Binder threads — see [SketchHostBinder.Hooks]. */
    private val hooks: SketchHostBinder.Hooks,
    /** Whether this notebook is a Sketch notebook — read at every discovery, because the session
     *  answers honestly (false) until it has read the index bit. */
    private val offered: () -> Boolean,
    /** Run before the bind: the notebook's queued writes, drained, so the screen's first `current()`
     *  reads a `.soil` that holds everything just drawn. */
    private val drainWrites: suspend () -> Unit = {},
    /** Run immediately before the screen is launched, and only after a successful `begin` — the
     *  notebook's floating-chrome dismissal, transform end and `releaseForHandoff`. */
    private val beforeLaunch: () -> Unit = {},
    /** Run when the launch itself was refused **after** [beforeLaunch] had run — the notebook's
     *  `resumeDrawing()`; the refusal leaves the caller resumed, so nothing else would. */
    private val afterLaunchFailed: () -> Unit = {},
    /** The first thing done when the screen comes back: the caller's `resumeDrawing()`. A result
     *  callback runs before `onResume`, and the paper must be the caller's again by then. */
    private val reclaimPipeline: () -> Unit = {},
    /**
     * The showing ended. Runs at the **top** of [onResult] (after the stack pop, the chrome flag
     * and the reclaim), on the caller's Main thread and **before** the detached `finish()`
     * coroutine — so the caller reads the page the face ended on while the hooks still hold it.
     * Not called on the [close] backstop: a screen being destroyed has nothing to catch up to.
     */
    private val onClosed: (resultCode: Int) -> Unit = {},
) {

    private val launcher: ActivityResultLauncher<Intent> =
        // Registered from the caller's onCreate — a launcher may not be registered after STARTED.
        activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            onResult(result)
        }

    private var ref: ProviderRef? = null
    private var client: SketchClient? = null

    /** The surface stack this door pushes onto (arc 32 / RS1) — the prefs door, nothing more. */
    private val stack = SurfaceStack(activity)

    /** The one global chrome flag (arc 33 / F3) — out on the launch Intent, back off the result. */
    private val chromePrefs = ChromePrefs(activity)

    /** One token per **entry instance**, not per surface: the caller holds one entry for its whole
     *  life and can raise the face many times, but only one showing can be up at once. */
    private val token: String = UUID.randomUUID().toString()

    /** This door's entry on the stack (arc 32 / RS1). */
    val stackEntry: SurfaceEntry get() = SurfaceEntry(token, Surface.SKETCH)

    /** Latched at the tap, released with the result or the moment the open fails. */
    private var opening = false

    /** The in-flight [reconnect], if any — held so [onResult] can **join** it rather than race it. */
    private var reconnectJob: Job? = null

    /**
     * Whether a showing is live right now. The caller persists this in `onSaveInstanceState` and
     * hands it back to [reconnect] — it is the whole of what survives the host's death. [client] is
     * assigned only at the launch itself (the M11 rule): a saved state written during the slow
     * bind/`begin` window must not record a showing that never reached the screen.
     */
    val isShowing: Boolean get() = client != null

    /** The result path's teardown — `end()`, unbind, revoke — as a joinable [Job]. The caller's
     *  seal awaits it so the screen's final flush lands before the `.soil` seals; assigned (LAZY,
     *  unstarted) before [onClosed] runs so a close decided inside that callback can see it. */
    var finishJob: Job? = null
        private set

    /** Re-discover and show or hide the button. Called from the caller's `onResume` and after a
     *  failed open. Discovery is IO; the button is left as it was until the answer arrives. */
    fun refresh() {
        activity.lifecycleScope.launch { discovered() }
    }

    /**
     * [refresh]'s body as something a caller can **await** — the open route needs it, because
     * `refresh()`'s discovery and the route are two coroutines whose finishing order is a race and
     * a Sketch notebook must not fall back to its canvas merely because the answer had not arrived
     * (the RS2 lesson, [ExtensionScreenEntry.discovered]'s reason).
     *
     * **Both conditions live here**, so there is one answer and one place the button's visibility
     * is decided: not a Sketch notebook ⇒ no door, whatever is installed.
     */
    suspend fun discovered(): Boolean {
        val wanted = offered()
        val found = if (wanted) ExtensionRegistry.sketch(activity) else null
        if (activity.isFinishing || activity.isDestroyed) return false
        ref = found
        button.visibility = if (found == null) View.GONE else View.VISIBLE
        return found != null
    }

    /** Whether a trusted extension is installed right now **and** this notebook has the door. */
    val isAvailable: Boolean get() = ref != null

    /**
     * Tap, or the open route of a Sketch notebook. Raises the box, then — behind it — drains the
     * notebook's writes, mints the host binder, holds the bind, `begin`s, puts the chrome flag on
     * the Intent, hands the pipeline over and launches the screen for a result. Any failure hides
     * the box, explains itself in a dialog (a tap that did nothing is never a toast on e-ink) and
     * re-runs discovery, so a package disabled under us takes its button with it.
     */
    fun open() {
        val provider = ref ?: return
        if (opening) { Slog.d(TAG) { "open: already showing" }; return }
        opening = true
        OpeningOverlay.showThen(activity) {
            activity.lifecycleScope.launch {
                // 1. The drain, first: the screen's `current()` lands within milliseconds of
                //    `begin` and must see the ink that was just committed.
                runCatching { drainWrites() }
                    .onFailure { Slog.d(TAG) { "open: drain failed ${it.javaClass.simpleName} — opening anyway" } }
                val fresh = SketchClient(activity, provider)
                // 2. The bind and the handshake.
                val intent = fresh.open(hooks)
                if (activity.isFinishing || activity.isDestroyed) {
                    opening = false; fresh.finish(); return@launch
                }
                if (intent == null) {
                    fail(fresh)
                    return@launch
                }
                // 3. The chrome flag — the only thing that rides this Intent (arc 33 / F3).
                intent.putExtra(ExtensionContract.EXTRA_CHROME_HIDDEN, chromePrefs.hidden)
                // The latch and the launch move together: [client] set any earlier makes
                // [isShowing] true through the slow bind/`begin` window.
                client = fresh
                // 4. …then the floating chrome, the transform and the pipeline, in [beforeLaunch]
                //    — and only if the Intent actually resolves (arc 34 / M8).
                val launched = ScreenLaunch.attempt(
                    resolves = { intent.resolveActivity(activity.packageManager) != null },
                    beforeLaunch = beforeLaunch,
                    launch = { launcher.launch(intent) },
                    afterFailure = afterLaunchFailed,
                )
                if (launched != ScreenLaunch.Outcome.Launched) {
                    Slog.d(TAG) {
                        "launch refused: ${(launched as? ScreenLaunch.Outcome.Refused)?.cause?.javaClass?.simpleName ?: "unresolved"}"
                    }
                    client = null
                    fail(fresh)
                    return@launch
                }
                // On the stack only once the launch has actually happened (arc 32 / RS1).
                stack.attach(stackEntry)
            }
        }
    }

    /** Every failure path: release everything, take the box down, explain, re-run discovery. */
    private suspend fun fail(open: SketchClient) {
        client = null
        opening = false
        open.finish()
        if (activity.isFinishing || activity.isDestroyed) return
        OpeningOverlay.hide(activity)
        Dialogs.problem(activity, R.string.sketch_failed_title, R.string.sketch_failed_body)
        refresh()   // it may have been disabled or replaced under us
    }

    /**
     * Re-open the client for a showing that is **still on screen**. Called from the caller's
     * `onCreate` when its saved state says [isShowing] was true — the host was killed or
     * config-destroyed with the sketch screen on top of it, so the extension's own process (and its
     * pixels) survived while the host binder it was holding died with us.
     *
     * The recovery is host-driven because only the host can tell that this happened: the extension
     * sees a dead binder and can only park its rasters and retry. A fresh `begin` reaching it
     * **is**
     * the retry answering — its handler re-pushes the parked save through the new host binder.
     *
     * So: no launch (the screen is already up), no [OpeningOverlay] (it would paint under the
     * screen, over nothing the user can see), no [opening] flip (that latch guards the tap path),
     * and **no [beforeLaunch]** — the pipeline went over before the process died and the extension
     * still holds it. Discovery runs **inside** the coroutine: a null [ref] at `onCreate` means
     * "not looked up", never "not installed", and [offered] may not be answerable yet either, so
     * the registry is asked directly rather than through [discovered].
     *
     * A failure is silent by design: there is no window to put a dialog in, the extension keeps its
     * pixels and keeps retrying, and the [close] backstop still runs when this screen goes.
     *
     * **The surface stack is not touched here** (arc 32 / RS1) — the old instance's entry already
     * recorded this showing; attaching would put a second entry on for one screen.
     */
    fun reconnect() {
        if (client != null) { Slog.d(TAG) { "reconnect: already open" }; return }
        if (reconnectJob?.isActive == true) { Slog.d(TAG) { "reconnect: already in flight" }; return }
        reconnectJob = activity.lifecycleScope.launch {
            val provider = ref ?: ExtensionRegistry.sketch(activity)?.also { ref = it }
            if (provider == null) { Slog.d(TAG) { "reconnect: no extension installed" }; return@launch }
            if (activity.isFinishing || activity.isDestroyed || client != null) return@launch
            val fresh = SketchClient(activity, provider)
            client = fresh
            // The Intent is discarded — the screen this would have launched is already showing.
            val intent = fresh.open(hooks)
            if (intent == null || activity.isFinishing || activity.isDestroyed) {
                client = null
                fresh.finish()
                Slog.d(TAG) { "reconnect: failed — the screen keeps its pixels and retries" }
            } else {
                Slog.d(TAG) { "reconnect: host binder re-minted for the live showing" }
            }
        }
    }

    /** One showing is over: reclaim the paper, then `end()`, unbind, revoke. Nothing to drain —
     *  every save landed in the `.soil` as its last chunk crossed. */
    private fun onResult(result: ActivityResult) {
        // First, and synchronously (arc 32 / RS1) — ahead of everything below: a result callback
        // runs before the caller's own `onResume`, whose `markTop` drops everything above it.
        stack.pop(token)
        // Second, still synchronously (arc 33 / F3): the state the screen left its chrome in is
        // persisted before anything else runs. Absent (a dead process) writes nothing.
        ChromeResult.read(result.data)?.let { chromePrefs.hidden = it }
        // Third: the paper is the caller's again. The pad's rule — the reclaim belongs to the
        // result callback, which runs before `onResume` would get to it.
        reclaimPipeline()
        val pending = reconnectJob
        Slog.d(TAG) { "sketch screen returned: resultCode=${result.resultCode}" }
        // A detached scope: `finish` has an `end()` call plus an unbind and a revoke to run, and the
        // caller may be on its way out. Built LAZY and assigned BEFORE [onClosed] runs, so a close
        // the callback decides on can join it (the seal-vs-flush race); started after.
        val job = MainScope().launch(start = CoroutineStart.LAZY) {
            try {
                // **Join, never cancel.** A result can land moments after `onCreate` — before the
                // reconnect above has finished its `open()` — and this is precisely the case the
                // reconnect exists for: the `end()` inside `finish()` is what makes the extension
                // re-push the pixels it is still holding, and `end()` needs a live bind to travel
                // on. The wait is bounded by the client's own call timeouts.
                pending?.join()
                val open = client
                client = null
                open?.finish()
            } finally {
                opening = false
            }
        }
        finishJob = job
        // Synchronously, before the teardown starts: the caller still needs the showing's target.
        onClosed(result.resultCode)
        job.start()
    }

    /** The backstop: the bind must not outlive the screen that opened it, result or no result.
     *  Called from the caller's `onDestroy`. Returns the teardown's [Job] (null when there was
     *  nothing to close) so the caller's fallback seal can wait for the screen's `end()` flush. */
    fun close(): Job? {
        opening = false
        // The stack's backstop too (arc 32 / RS1) — unconditional, before the early return.
        stack.pop(token)
        // A destroy is the one place the reconnect is cancelled rather than joined: there is no
        // showing left to serve, and a bind opened after this point would outlive its screen.
        reconnectJob?.cancel()
        reconnectJob = null
        val open = client ?: return finishJob
        client = null
        return MainScope().launch { open.finish() }.also { finishJob = it }
    }

    private companion object {
        const val TAG = "SketchEntry"
    }
}

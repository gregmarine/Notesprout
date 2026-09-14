package com.symmetricalpalmtree.notesproutsn.extension

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
import com.symmetricalpalmtree.notesproutsn.data.prefs.Surface
import com.symmetricalpalmtree.notesproutsn.data.prefs.SurfaceEntry
import com.symmetricalpalmtree.notesproutsn.data.prefs.SurfaceStack
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * The host's side of the Document entry button (arc 19 / M3) — [ScratchPadEntry]'s shape with the
 * ink transfers taken out, because this seam moves no ink at all: the editor pulls its text and
 * pushes its saves through the **host callback binder** that went across at `begin`, so there is
 * nothing to hand over before the launch and nothing to drain on the way back.
 *
 * What it owns:
 *  - **Visibility.** The button is `GONE` unless a trusted `DOCUMENT_EDITOR` extension is installed,
 *    and discovery re-runs on every [refresh] (each `onResume`) **and after a failed open** — a
 *    package can be disabled or replaced under us, and a button that lies is worse than one that is
 *    absent. Never `isEnabled = false`: a disabled control is invisible on e-ink.
 *  - **The busy guard.** One showing at a time, latched **at the tap**: e-ink gives a tap no
 *    feedback for hundreds of ms, so the second tap is taken as read.
 *  - **The wait.** [OpeningOverlay] goes up at tap time and the open runs only once its frame is on
 *    the glass — a cold open pays SQLCipher's KDF for the extension store (≈ 3 s on the Nomad,
 *    measured at arc 11 / J4), and a tap with no answer for three seconds reads as a tap that
 *    missed. In the notebook this rides the same frame-silence exception the Contents, Recents and
 *    Scratch Pad buttons do: a deliberate chrome tap that raises a full-screen thing.
 *  - **The bind's life.** [DocumentEditorClient.finish] runs from the result callback and from
 *    [close] as the backstop for a caller destroyed while the editor is up. There is no drain to
 *    sequence it after — a save is committed by the time `saveChunk` returns, which is exactly what
 *    the callback binder buys.
 *  - **Its place on the surface stack** (arc 32 / RS1). The editor is another process with no
 *    lifecycle here to maintain it from, so this entry does it: [stackEntry] is pushed the instant
 *    [launcher] has actually launched — never at the tap, where the open can still fail with
 *    nothing on the glass — and popped at the very top of [onResult], synchronously, ahead of both
 *    [onClosed] and the lazy teardown, because a result callback runs **before** the caller's
 *    `onResume` and that `markTop` must find this entry already gone. [close] pops as the backstop.
 *    Never from an `onDestroy`, the editor's least of all: a killed process gets none, which is the
 *    whole point of the stack. [reconnect] touches nothing — see its doc.
 *  - **The reconnect** (M4). A host killed behind the live editor leaves the extension holding text
 *    and a dead binder. [reconnect] re-opens the client without launching anything, and the fresh
 *    `begin` is what the extension flushes against — see its doc, and [onResult] for the one race
 *    that follows from it.
 *
 * [beforeLaunch] exists for the same reason it does on the pad — the caller's instant between a
 * successful `begin` and the launch. The notebook deliberately passes **nothing** at M3: the editor
 * draws no ink, so it is chrome rather than a second paper surface (the arc-13 template-picker
 * precedent). See the wiring in `NotebookActivity` for the on-device question that decides whether
 * that holds.
 */
class DocumentEditorEntry(
    private val activity: AppCompatActivity,
    private val button: View,
    /** The open notebook's read and write half, handed to the [DocumentHostBinder] minted per
     *  showing. Its two methods run on Binder threads — see [DocumentHostBinder.Hooks]. */
    private val hooks: DocumentHostBinder.Hooks,
    /** Run immediately before the screen is launched, and only after a successful `begin`. */
    private val beforeLaunch: () -> Unit = {},
    /** Arc 39 "Lookup": whether a Bible reader that understands references is installed **right
     *  now** — the host's own discovery, read at the open and put on the editor's Intent as its one
     *  boolean so the editor can offer its selection-toolbar Bible item. */
    private val bibleAvailable: () -> Boolean = { false },
    /**
     * The showing ended (M6). Runs at the **top** of [onResult], on the caller's Main thread and
     * **before** the detached `finish()` coroutine — so the caller reads the page the editor ended
     * on while the hooks still hold it, and can catch the notebook up to it (og's
     * `navigateToPage(endedOn)`). Not called on the [close] backstop: a screen being destroyed has
     * nothing to catch up to.
     */
    private val onClosed: () -> Unit = {},
) {

    private val launcher: ActivityResultLauncher<android.content.Intent> =
        // Registered from the caller's onCreate — a launcher may not be registered after STARTED.
        activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            onResult(result)
        }

    private var ref: ProviderRef? = null
    private var client: DocumentEditorClient? = null

    /** The surface stack this door pushes onto (arc 32 / RS1) — the prefs door, nothing more. */
    private val stack = SurfaceStack(activity)

    /** One token per **entry instance**, not per surface: the caller holds one entry for its whole
     *  life and can raise the editor many times, but only one showing can be up at once. */
    private val token: String = UUID.randomUUID().toString()

    /** This door's entry on the stack (arc 32 / RS1). */
    val stackEntry: SurfaceEntry get() = SurfaceEntry(token, Surface.DOCUMENT_EDITOR)

    /** Latched at the tap, released with the result or the moment the open fails — see the class doc. */
    private var opening = false

    /** The in-flight [reconnect], if any — held so [onResult] can **join** it rather than race it.
     *  See [reconnect] for why joining is the only safe answer. */
    private var reconnectJob: Job? = null

    /** Whether a showing is live right now. The caller persists this in `onSaveInstanceState` and
     *  hands it back to [reconnect] — it is the whole of what survives the host's death. [open]
     *  assigns [client] only at the launch itself (M11): a saved state written during the slow
     *  store-open/begin window must not record a showing that never reached the screen — a
     *  recreated host would route EDITOR_RECONNECT and sit under its overlay for an editor that
     *  does not exist. */
    val isShowing: Boolean get() = client != null

    /** The result path's teardown — `end()`, unbind, both revokes — as a joinable [Job] (M11).
     *  The caller's seal awaits it so the extension's final flush lands before the `.soil` seals;
     *  assigned (LAZY, unstarted) before [onClosed] runs so a close decided inside that callback
     *  can already see it. Null until the first result. */
    var finishJob: Job? = null
        private set

    /**
     * Re-discover and show or hide the button. Called from the caller's `onResume` and after a
     * failed open. Discovery is IO; the button is left as it was until the answer arrives.
     */
    fun refresh() {
        activity.lifecycleScope.launch { discovered() }
    }

    /**
     * [refresh]'s body as something a caller can **await** (arc 32 / RS2) — see
     * [ExtensionScreenEntry.discovered] for why the cold-launch replay may not read [isAvailable]
     * instead: `refresh()`'s discovery and the replay are two coroutines whose finishing order is
     * a race either way.
     */
    suspend fun discovered(): Boolean {
        val found = ExtensionRegistry.documentEditor(activity)
        if (activity.isFinishing || activity.isDestroyed) return false
        ref = found
        button.visibility = if (found == null) View.GONE else View.VISIBLE
        return found != null
    }

    /** Whether a trusted extension is installed right now. */
    val isAvailable: Boolean get() = ref != null

    /** Arc 39 "Lookup": the editor's package — the one caller the host's lookup screen admits for
     *  a reference parked on this editor's behalf. */
    val providerPackage: String? get() = ref?.packageName

    /**
     * Tap. Raises the box, then — behind it — pre-opens the store on IO, mints both binders, holds
     * the bind, `begin`s, runs [beforeLaunch] and launches the screen for a result. Any failure
     * hides the box, explains itself in a dialog (a tap that did nothing is never a toast on e-ink)
     * and re-runs discovery, so a package disabled under us takes its button with it.
     *
     * A document larger than [DocumentContract.MAX_DOCUMENT_CHARS] fails here too, and by exactly
     * this path: the hook's `setWindow` refuses it, `begin`'s `current()` never gets a state, and
     * the editor is not opened. Nothing special to check at M3 — the cap enforces itself where the
     * text is first touched.
     */
    fun open() {
        val provider = ref ?: return
        if (opening) { Slog.d(TAG) { "open: already showing" }; return }
        opening = true
        OpeningOverlay.showThen(activity) {
            activity.lifecycleScope.launch {
                val fresh = DocumentEditorClient(activity, provider)
                val intent = fresh.open(hooks, bibleAvailable())
                if (activity.isFinishing || activity.isDestroyed) {
                    opening = false; fresh.finish(); return@launch
                }
                if (intent == null) {
                    fail(fresh)
                    return@launch
                }
                beforeLaunch()
                // The latch and the launch move together (M11): [client] set any earlier makes
                // [isShowing] true through the slow store-open/begin window, and a saved state
                // written there records a showing that never launched.
                client = fresh
                launcher.launch(intent)
                // On the stack only once the launch has actually happened (arc 32 / RS1): every
                // path above this line leaves nothing on the glass, so there is nothing to restore.
                stack.attach(stackEntry)
            }
        }
    }

    /** Every failure path: release everything, take the box down, explain, re-run discovery. */
    private suspend fun fail(open: DocumentEditorClient) {
        client = null
        opening = false
        open.finish()
        if (activity.isFinishing || activity.isDestroyed) return
        OpeningOverlay.hide(activity)
        Dialogs.problem(activity, R.string.document_failed_title, R.string.document_failed_body)
        refresh()   // it may have been disabled or replaced under us
    }

    /**
     * Re-open the client for a showing that is **still on screen** (M4). Called from the caller's
     * `onCreate` when its saved state says [isShowing] was true — the host was killed or
     * config-destroyed with the editor on top of it, so the editor's own process (and its text)
     * survived while the host binder it was holding died with us.
     *
     * The recovery is host-driven because only the host can tell that this happened: the extension
     * sees a dead binder and can only park and retry. A fresh `begin` reaching it **is** the retry
     * answering — its handler pushes the parked text straight back through the new host binder.
     *
     * So: no launch (the screen is already up), no [OpeningOverlay] (it would paint under the
     * editor, over nothing the user can see), and no [opening] flip — that latch guards the tap
     * path and this is not a tap. Discovery runs **inside** the coroutine: [refresh] has not
     * necessarily answered yet at `onCreate` time, and a null [ref] here means "not looked up",
     * never "not installed".
     *
     * A failure is silent by design. There is no window to put a dialog in, and nothing is lost by
     * staying quiet: the extension keeps its text and keeps retrying, and the [close] backstop
     * still runs when this screen finally goes.
     *
     * **The surface stack is not touched here** (arc 32 / RS1). This mints a binder for a showing
     * already on the glass, which the old instance's entry already recorded; the recreated host's
     * own `onResume` `markTop` clears that stale entry, and the pop at the result is the new
     * token's alone. Attaching here would put a second entry on for one showing.
     */
    fun reconnect() {
        if (client != null) { Slog.d(TAG) { "reconnect: already open" }; return }
        if (reconnectJob?.isActive == true) { Slog.d(TAG) { "reconnect: already in flight" }; return }
        reconnectJob = activity.lifecycleScope.launch {
            val provider = ref ?: ExtensionRegistry.documentEditor(activity)?.also { ref = it }
            if (provider == null) { Slog.d(TAG) { "reconnect: no extension installed" }; return@launch }
            if (activity.isFinishing || activity.isDestroyed || client != null) return@launch
            val fresh = DocumentEditorClient(activity, provider)
            client = fresh
            // The Intent is discarded — the screen this would have launched is already showing.
            val intent = fresh.open(hooks)
            if (intent == null || activity.isFinishing || activity.isDestroyed) {
                client = null
                fresh.finish()
                Slog.d(TAG) { "reconnect: failed — the editor keeps its text and retries" }
            } else {
                Slog.d(TAG) { "reconnect: host binder re-minted for the live showing" }
            }
        }
    }

    /** One showing is over: `end()`, unbind, revoke both binders. Nothing to drain — every save
     *  landed in the `.soil` as its last chunk crossed. */
    private fun onResult(result: ActivityResult) {
        // First, and synchronously (arc 32 / RS1) — ahead of [onClosed] and the lazy teardown
        // below: a result callback runs **before** the caller's own `onResume`, whose `markTop`
        // drops everything above it, and this entry must already be gone by then.
        stack.pop(token)
        val pending = reconnectJob
        Slog.d(TAG) { "document editor returned: resultCode=${result.resultCode}" }
        // A detached scope: `finish` has an `end()` call plus an unbind and two revokes to run, and
        // the caller may be on its way out. Built LAZY and assigned BEFORE [onClosed] runs, so a
        // close the callback decides on can join it (the seal-vs-flush race, M11); started after.
        val job = MainScope().launch(start = CoroutineStart.LAZY) {
            try {
                // **Join, never cancel.** A result can land moments after `onCreate` — before the
                // reconnect above has finished its `open()` — and this is precisely the case the
                // reconnect exists for: the `end()` inside `finish()` is what makes the extension
                // flush the text it is still holding, and `end()` needs a live bind to travel on.
                // Cancelling here, or reading `client` before the join, would drop the reconnect on
                // the floor and take the user's last edit with it. The wait is bounded by the
                // client's own call timeouts.
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
        onClosed()
        job.start()
    }

    /** The backstop: the bind must not outlive the screen that opened it, result or no result.
     *  Called from the caller's `onDestroy`. Returns the teardown's [Job] (null when there was
     *  nothing to close) so the caller's fallback seal can wait for the extension's `end()` flush
     *  — flush-before-seal, on the destroy path too (M11). */
    fun close(): Job? {
        opening = false
        // The stack's backstop too (arc 32 / RS1) — unconditional, before the early return: a door
        // whose caller is going away has nothing left to restore, showing or not.
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
        const val TAG = "DocumentEditorEntry"
    }
}

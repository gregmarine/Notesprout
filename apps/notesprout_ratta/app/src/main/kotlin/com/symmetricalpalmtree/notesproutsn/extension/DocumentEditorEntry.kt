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
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

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
) {

    private val launcher: ActivityResultLauncher<android.content.Intent> =
        // Registered from the caller's onCreate — a launcher may not be registered after STARTED.
        activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            onResult(result)
        }

    private var ref: ProviderRef? = null
    private var client: DocumentEditorClient? = null

    /** Latched at the tap, released with the result or the moment the open fails — see the class doc. */
    private var opening = false

    /**
     * Re-discover and show or hide the button. Called from the caller's `onResume` and after a
     * failed open. Discovery is IO; the button is left as it was until the answer arrives.
     */
    fun refresh() {
        activity.lifecycleScope.launch {
            val found = ExtensionRegistry.documentEditor(activity)
            if (activity.isFinishing || activity.isDestroyed) return@launch
            ref = found
            button.visibility = if (found == null) View.GONE else View.VISIBLE
        }
    }

    /** Whether a trusted extension is installed right now. */
    val isAvailable: Boolean get() = ref != null

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
                client = fresh
                val intent = fresh.open(hooks)
                if (activity.isFinishing || activity.isDestroyed) {
                    client = null; opening = false; fresh.finish(); return@launch
                }
                if (intent == null) {
                    fail(fresh)
                    return@launch
                }
                beforeLaunch()
                launcher.launch(intent)
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

    /** One showing is over: `end()`, unbind, revoke both binders. Nothing to drain — every save
     *  landed in the `.soil` as its last chunk crossed. */
    private fun onResult(result: ActivityResult) {
        val open = client
        client = null
        Slog.d(TAG) { "document editor returned: resultCode=${result.resultCode}" }
        // A detached scope: `finish` has an `end()` call plus an unbind and two revokes to run, and
        // the caller may be on its way out.
        MainScope().launch {
            try {
                open?.finish()
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
        const val TAG = "DocumentEditorEntry"
    }
}

package com.symmetricalpalmtree.notesprout.notebook

import android.app.Activity
import android.content.Intent
import android.view.View
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.TooltipCompat
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.gpaper.core.PaperView
import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.notesprout.R
import com.symmetricalpalmtree.notesprout.core.ActionSheetDialog
import com.symmetricalpalmtree.notesprout.core.Dialogs
import com.symmetricalpalmtree.notesprout.core.Slog
import com.symmetricalpalmtree.notesprout.extension.ActionApplies
import com.symmetricalpalmtree.notesprout.extension.ExtensionCallException
import com.symmetricalpalmtree.notesprout.extension.ExtensionContract
import com.symmetricalpalmtree.notesprout.extension.ExtensionRegistry
import com.symmetricalpalmtree.notesprout.extension.PaperStroke
import com.symmetricalpalmtree.notesprout.extension.ProviderRef
import com.symmetricalpalmtree.notesprout.extension.ScratchPadClient
import com.symmetricalpalmtree.notesprout.extension.ScratchPageFullException
import com.symmetricalpalmtree.notesprout.extension.TransferCaps
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The notebook's side of the Scratch Pad (arc 6 / S1 + S2): the top-bar **sketching** button and the
 * core `scratch` selection-toolbar action — both present only while a trusted `SCRATCH_PAD`
 * extension is installed ([refresh], re-discovered on every resume, the Naming / Contents rule) —
 * and the showing of the extension's screen with the two ink transfers around it.
 *
 * **Open** ([open], the button): a [busy] guard (a second tap is dropped) → [ScratchPadClient.open]
 * (store pre-open → held bind → `begin`) → `paper.releaseForHandoff()` (rule 27 — the pad's process
 * claims the EPD pipeline next; the notebook's pen stays live through the open, which can be seconds
 * on a cold store) → the Intent through the [launcher] (`startActivityForResult`-style, so the
 * extension's caller check can pass — risk 2); a null Intent → the core's `scratch_failed` dialog.
 *
 * **Send to Scratch Pad** ([sendSelection], S2 — the core action on an ink-only lasso): the placement
 * sheet (New page / Current page; tap outside cancels) → [TransferCaps.withinLimits] else
 * `scratch_too_large` **before any bind** → `open` → [ScratchPadClient.send] (the chunks through the
 * held bind) → toast `scratch_sent` → handoff → launch with `openReceived = true`, so the pad opens
 * on the received page with the strokes selected. Send is a **copy** — the notebook keeps its ink and
 * records nothing. A `ScratchPageFullException` → `scratch_page_full_host`, nothing launched.
 *
 * **Result** ([onResult]): `RESULT_SCRATCH_SEND` → [ScratchPadClient.drainOutgoing] → fresh ids
 * ([TransferCaps.toStrokes]) → the host's [onPaste] (one undoable `Pasted` step, left selected; the
 * coordinates are kept 1:1 — S2 Q1) → `scratch_truncated` if the caps cut it; any code →
 * [ScratchPadClient.finish] (`end` → unbind → revoke). [close] from the host's `onDestroy` finishes an
 * open client on a scope that outlives it.
 */
class ScratchPadFlow(
    private val activity: AppCompatActivity,
    private val paper: PaperView,
    private val button: View,
    /** `opened && !closing` on the host. */
    private val alive: () -> Boolean,
    private val whenPenIdle: (() -> Unit) -> Unit,
    /** Called when the button's visibility changed — the host re-pushes its exclusion rects. */
    private val onVisibilityChanged: () -> Unit,
    /** The current page's px size — the geometry the sent strokes were authored in. */
    private val pageSize: () -> Pair<Int, Int>,
    /** The host pastes [strokes] on its current page as one undoable step and leaves them selected. */
    private val onPaste: (strokes: List<Stroke>) -> Unit,
) {
    private var ref: ProviderRef? = null
    private var client: ScratchPadClient? = null
    private var busy = false
    private var refreshGen = 0

    /** Registered at construction — the host builds the flow in `onCreate`. */
    private val launcher: ActivityResultLauncher<Intent> =
        activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result -> onResult(result.resultCode) }

    private val scratchAction: ToolbarAction by lazy {
        ToolbarAction(
            SelectionActions.CORE_SCRATCH_ID, activity.getString(R.string.scratch_action_label), R.drawable.ic_sketching,
            activity.getString(R.string.scratch_action_hint), ActionApplies.INK, 0,
        )
    }

    init {
        TooltipCompat.setTooltipText(button, button.contentDescription)
        button.setOnClickListener { open() }
    }

    /** The core "Send to Scratch Pad" toolbar action — null while no extension is installed. */
    fun toolbarAction(): ToolbarAction? = if (ref != null) scratchAction else null

    /** Re-discover the extension (IO) and show / hide the button (pen-idle). Newest generation wins. */
    fun refresh() {
        val gen = ++refreshGen
        activity.lifecycleScope.launch {
            val found = try { ExtensionRegistry.scratchPad(activity) } catch (e: Exception) { Slog.d(TAG) { "discovery failed: ${e.message}" }; null }
            if (gen != refreshGen || activity.isFinishing || activity.isDestroyed) return@launch
            ref = found
            whenPenIdle {
                val vis = if (ref != null) View.VISIBLE else View.GONE
                if (button.visibility != vis) { button.visibility = vis; onVisibilityChanged() }
            }
        }
    }

    /** The top-bar tap: open the pad from this notebook (Send enabled; nothing received). */
    fun open() {
        if (busy || !alive()) return
        val r = ref ?: return
        paper.releaseRender()
        launchPad(r, send = null)
    }

    /** The core `scratch` action on an ink-only selection ([strokes] in writing order): ask where, then send + open. */
    fun sendSelection(strokes: List<Stroke>) {
        if (busy || !alive() || strokes.isEmpty()) return
        val r = ref ?: return
        paper.releaseRender()
        ActionSheetDialog(activity)
            .title(activity.getString(R.string.scratch_send_title))
            .addAction(R.drawable.ic_plus, activity.getString(R.string.scratch_new_page)) { startSend(r, strokes, ExtensionContract.PLACEMENT_NEW_PAGE) }
            .addAction(R.drawable.ic_sketching, activity.getString(R.string.scratch_current_page)) { startSend(r, strokes, ExtensionContract.PLACEMENT_CURRENT_PAGE) }
            .show()
    }

    private fun startSend(r: ProviderRef, strokes: List<Stroke>, placement: Int) {
        if (busy || !alive()) return
        val points = TransferCaps.pointCount(strokes)
        if (!TransferCaps.withinLimits(strokes.size, points)) {   // refused before any bind
            Slog.d(TAG) { "send refused: ${strokes.size} strokes / $points points over the caps" }
            Dialogs.problem(activity, R.string.scratch_send_title, activity.getString(R.string.scratch_too_large, strokes.size))
            return
        }
        val (w, h) = pageSize()
        launchPad(r, Send(TransferCaps.chunk(TransferCaps.toPaperStrokes(strokes)), w.toFloat(), h.toFloat(), placement))
    }

    /** The ink to hand over before the launch (null = plain open). */
    private class Send(val chunks: List<List<PaperStroke>>, val pageWidth: Float, val pageHeight: Float, val placement: Int)

    /** open → [send] → handoff → launch; every failure releases the client and says why. */
    private fun launchPad(r: ProviderRef, send: Send?) {
        busy = true
        val c = ScratchPadClient(activity, r)
        client = c
        activity.lifecycleScope.launch {
            val intent = c.open(sendEnabled = true, openReceived = send != null)
            if (intent == null || !alive() || activity.isFinishing || activity.isDestroyed) {
                client = null; busy = false
                if (intent != null) c.finish()
                else if (!activity.isFinishing && !activity.isDestroyed) {
                    Dialogs.problem(activity, R.string.cd_scratch_pad, activity.getString(R.string.scratch_failed, r.label))
                    refresh()   // a bind that fails because the package was disabled meanwhile (BOOX re-disables sideloads) hides the button now, not at the next resume
                }
                return@launch
            }
            if (send != null) {
                val failure = try {
                    c.send(send.chunks, send.pageWidth, send.pageHeight, send.placement); null
                } catch (e: ScratchPageFullException) {
                    Slog.d(TAG) { "send: page full" }; R.string.scratch_page_full_host
                } catch (e: ExtensionCallException) {
                    Slog.d(TAG) { "send failed: ${e.message}" }; R.string.scratch_failed
                }
                if (failure != null || !alive() || activity.isFinishing || activity.isDestroyed) {
                    client = null; busy = false
                    c.finish()
                    if (failure != null && !activity.isFinishing && !activity.isDestroyed) {
                        val msg = if (failure == R.string.scratch_failed) activity.getString(failure, r.label) else activity.getString(failure)
                        Dialogs.problem(activity, R.string.scratch_send_title, msg)
                    }
                    return@launch
                }
                paper.clearSelection()   // the notebook keeps its ink (send = copy); the selection needn't survive the handoff
                Toast.makeText(activity, R.string.scratch_sent, Toast.LENGTH_SHORT).show()
            }
            Slog.d(TAG) { "launching the pad" + if (send != null) " (received)" else "" }
            paper.releaseForHandoff()   // immediately before launching another paper-hosting screen (g-paper §Lifecycle)
            try {
                launcher.launch(intent)
            } catch (e: Exception) {   // ActivityNotFound / SecurityException: the screen vanished between the bind and the launch (BOOX freeze, a pad without the exported Activity)
                Slog.d(TAG) { "launch failed: ${e.javaClass.simpleName}: ${e.message}" }
                client = null; busy = false
                paper.resumeDrawing()   // the handoff above released our pipeline — take it back, nothing is coming
                c.finish()
                if (!activity.isFinishing && !activity.isDestroyed) {
                    Dialogs.problem(activity, R.string.cd_scratch_pad, activity.getString(R.string.scratch_failed, r.label))
                    refresh()
                }
            }
        }
    }

    private fun onResult(resultCode: Int) {
        Slog.d(TAG) { "result $resultCode" + if (resultCode == Activity.RESULT_CANCELED) " (cancelled)" else "" }
        val c = client
        if (c == null) {
            // The host process was killed and this screen recreated while the pad was up: the launcher
            // survives, the client did not. A Send has nowhere to land — say so rather than drop it silently
            // (the pad's ink is still on the pad). Anything else needs nothing.
            if (resultCode == ExtensionContract.RESULT_SCRATCH_SEND && !activity.isFinishing && !activity.isDestroyed) {
                Dialogs.problem(activity, R.string.cd_scratch_pad, activity.getString(R.string.scratch_result_lost))
            }
            return
        }
        if (resultCode != ExtensionContract.RESULT_SCRATCH_SEND) {
            client = null; busy = false
            appScope.launch { withContext(NonCancellable) { c.finish() } }
            return
        }
        // Pad → notebook: drain on the still-held bind, then release it; paste under the host's page-op lock.
        // `busy` / `client` stay set until the drain is over — a second launch meanwhile would `begin()` a
        // new showing (wiping the pad's parked chunks mid-drain) and this client's `end()` would then
        // tear that showing's store down.
        activity.lifecycleScope.launch {
            val drained = try {
                c.drainOutgoing()
            } catch (e: ExtensionCallException) {
                Slog.d(TAG) { "drain failed: ${e.message}" }; null
            } finally {
                if (client === c) { client = null; busy = false }
                appScope.launch { withContext(NonCancellable) { c.finish() } }
            }
            if (drained == null) {
                if (!activity.isFinishing && !activity.isDestroyed) Dialogs.problem(activity, R.string.cd_scratch_pad, activity.getString(R.string.scratch_failed, c.ref.label))
                return@launch
            }
            if (!alive() || activity.isFinishing || activity.isDestroyed) return@launch
            val strokes = TransferCaps.toStrokes(drained.strokes)
            Slog.d(TAG) { "paste ${strokes.size} strokes from the pad (${drained.pageWidth.toInt()}x${drained.pageHeight.toInt()})" }
            if (strokes.isNotEmpty()) onPaste(strokes)
            if (drained.truncated) Dialogs.problem(activity, R.string.cd_scratch_pad, activity.getString(R.string.scratch_truncated, strokes.size))
        }
    }

    /** The host's `onDestroy`: an open client is finished on a scope that outlives the screen. */
    fun close() {
        val c = client ?: return
        client = null
        busy = false
        appScope.launch { withContext(NonCancellable) { c.finish() } }
    }

    companion object {
        private const val TAG = "ScratchPadFlow"
        /** Outlives the Activity so `end` → unbind → revoke always completes. */
        internal val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }
}

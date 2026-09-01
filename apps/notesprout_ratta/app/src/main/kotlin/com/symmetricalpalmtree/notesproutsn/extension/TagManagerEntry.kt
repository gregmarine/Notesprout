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
import com.symmetricalpalmtree.notesproutsn.core.RecognizingOverlay
import com.symmetricalpalmtree.notesproutsn.core.Slog
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

/**
 * The host's side of every tag door (arc 21 / W1) — **one class for all of them**, the
 * [ScratchPadEntry] pattern and for the same reason: the library's sheet row, the notebook's toolbar
 * (W2) and the lasso's Tag (W3) differ only in the [TagShowing] they hand over, and two
 * near-identical files is the sibling-copy trap `:sn-screen` exists to keep out of this app.
 *
 * W3 added the one door that opens **no screen at all** — [assign], the lasso's silent heading→tag.
 * It lives here rather than beside its caller because availability, the busy latch and the way a
 * failure is worded are the same questions for a door with a screen and a door without one.
 *
 * What it owns:
 *  - **Availability.** [discover] re-runs the package query every time a caller is about to offer a
 *    tag door, because a package can be disabled or replaced under a standing screen. Callers make
 *    the door **GONE** when it answers false — never disabled: a disabled control is invisible on
 *    e-ink, and a door that lies is worse than one that is absent. A caller whose door is a
 *    standing button hands it over as [button] and calls [refresh] from its `onResume` instead —
 *    the [ScratchPadEntry] / [DocumentEditorEntry] shape, for callers whose door is not built at
 *    the moment it is offered.
 *  - **The busy guard.** One showing at a time, latched **at the tap**: e-ink gives a tap no
 *    feedback for hundreds of ms, and the open is asynchronous twice over (the overlay's frame, then
 *    the store and the bind).
 *  - **The wait.** [OpeningOverlay] goes up at tap time and the open runs only once its frame is on
 *    the glass — a cold `ExtensionStores.open` is seconds on the Nomad (SQLCipher's KDF), and a tap
 *    with no answer for that long reads as a tap that missed.
 *  - **The bind's life.** [TagClient.finish] runs from the result callback and from [close] as the
 *    backstop for a caller destroyed while the screen is up.
 *
 * There is **no EPD handoff here and there must not be one.** The tag screen carries no paper, and
 * M3 measured that a non-drawing child screen needs nothing beyond the caller being stopped behind
 * it — cross-process included.
 */
class TagManagerEntry(
    private val activity: AppCompatActivity,
    /** A standing button this entry shows or hides on every [refresh] — the notebook's `ic_tag`
     *  (W2). Null for callers that ask [discover] at the moment they build the door, which is what
     *  the library's action sheet does. */
    private val button: View? = null,
    /** Run on Main after a showing that changed something (`RESULT_OK`). W1 has nothing to redraw;
     *  W4's search shelf re-runs its query here. */
    private val onChanged: () -> Unit = {},
) {

    private val launcher: ActivityResultLauncher<android.content.Intent> =
        // Registered from the caller's onCreate — a launcher may not be registered after STARTED.
        activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            onResult(result)
        }

    private var ref: ProviderRef? = null
    private var client: TagClient? = null
    private var opening = false

    /** Whether a trusted tag manager is installed **right now**. Suspends — it is a package query. */
    suspend fun discover(): Boolean {
        ref = ExtensionRegistry.tagManager(activity)
        button?.visibility = if (ref == null) View.GONE else View.VISIBLE
        return ref != null
    }

    /**
     * Re-discover and show or hide [button]. Called from the caller's `onResume` and after a failed
     * open. Discovery is IO; the button is left as it was until the answer arrives.
     */
    fun refresh() {
        activity.lifecycleScope.launch {
            val found = ExtensionRegistry.tagManager(activity)
            if (activity.isFinishing || activity.isDestroyed) return@launch
            ref = found
            button?.visibility = if (found == null) View.GONE else View.VISIBLE
        }
    }

    /** The answer [discover] last gave, without asking again. */
    val isAvailable: Boolean get() = ref != null

    /**
     * Raise the box, then — behind it — pre-open the store, hold the bind, hand over [showing] and
     * launch the screen for a result. Any failure hides the box and explains itself in a dialog (a
     * tap that did nothing is never a toast on e-ink).
     */
    fun open(showing: TagShowing) {
        val provider = ref ?: return
        if (opening) { Slog.d(TAG) { "open: already showing" }; return }
        opening = true
        OpeningOverlay.showThen(activity) {
            activity.lifecycleScope.launch {
                val fresh = TagClient(activity, provider)
                client = fresh
                val intent = fresh.open(showing)
                if (activity.isFinishing || activity.isDestroyed) {
                    client = null; opening = false; fresh.finish(); return@launch
                }
                if (intent == null) {
                    client = null
                    opening = false
                    fresh.finish()
                    OpeningOverlay.hide(activity)
                    Dialogs.problem(activity, R.string.tags_failed_title, R.string.tags_failed_body)
                    // It may have been disabled or replaced under us — ask again before it is offered.
                    discover()
                    return@launch
                }
                launcher.launch(intent)
            }
        }
    }

    /**
     * **The silent door** (arc 21 / W3): create-if-absent and attach [text] to one target, with no
     * screen at all — the lasso's heading→tag, which is one tap and a toast.
     *
     * Bind-per-call ([TagClient.assign]), so it holds nothing and needs no bracket; but the store is
     * still pre-opened inside the call, and the *first* tag operation of a host process pays
     * SQLCipher's KDF for it. That is seconds on a Nomad, so the wait gets the same box the heading
     * convert's does — a tap with no frame for that long reads as a tap that missed.
     *
     * [onDone] runs on Main with the tag's **canonical display form** — the casing it was first
     * entered in, which is not necessarily the casing just handed over, and is the whole reason the
     * call answers with a string rather than a boolean. Every failure explains itself in a problem
     * dialog and [onDone] does not run.
     */
    fun assign(text: String, notebookId: String, pageId: String?, onDone: (String) -> Unit) {
        val provider = ref ?: return
        if (opening) { Slog.d(TAG) { "assign: a showing is opening" }; return }
        opening = true
        RecognizingOverlay.show(activity, R.string.tag_applying)
        activity.lifecycleScope.launch {
            val failure = try {
                val display = TagClient.assign(activity, provider, text, notebookId, pageId)
                RecognizingOverlay.hide(activity)
                if (activity.isFinishing || activity.isDestroyed) { opening = false; return@launch }
                opening = false
                onDone(display)
                return@launch
            } catch (e: TagIndexFullException) {
                R.string.tags_full_body
            } catch (e: ExtensionCallException) {
                Slog.d(TAG) { "assign failed: ${e.javaClass.simpleName}: ${e.message}" }
                R.string.tags_assign_failed_body
            } finally {
                RecognizingOverlay.hide(activity)   // idempotent backstop for every path above
            }
            opening = false
            if (activity.isFinishing || activity.isDestroyed) return@launch
            Dialogs.problem(activity, R.string.tags_failed_title, failure)
            // It may have been disabled or replaced under us — ask again before it is offered again.
            discover()
        }
    }

    /** One showing is over. The bind is finished on a detached scope — the caller may be leaving. */
    private fun onResult(result: ActivityResult) {
        val open = client
        client = null
        Slog.d(TAG) { "tag screen returned: resultCode=${result.resultCode}" }
        MainScope().launch {
            try {
                open?.finish()
            } finally {
                opening = false
            }
            if (result.resultCode == android.app.Activity.RESULT_OK &&
                !activity.isFinishing && !activity.isDestroyed
            ) {
                onChanged()
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
        const val TAG = "TagManagerEntry"
    }
}

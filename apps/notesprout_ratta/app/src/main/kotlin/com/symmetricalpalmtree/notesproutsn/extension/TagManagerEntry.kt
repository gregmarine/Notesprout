package com.symmetricalpalmtree.notesproutsn.extension

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
 * The host's side of every tag door (arc 21 / W1) — **one class for all of them**, the
 * [ScratchPadEntry] pattern and for the same reason: the library's sheet row, the notebook's toolbar
 * (W2) and the lasso's Tag (W3) differ only in the [TagShowing] they hand over, and two
 * near-identical files is the sibling-copy trap `:sn-screen` exists to keep out of this app.
 *
 * What it owns:
 *  - **Availability.** [discover] re-runs the package query every time a caller is about to offer a
 *    tag door, because a package can be disabled or replaced under a standing screen. Callers make
 *    the door **GONE** when it answers false — never disabled: a disabled control is invisible on
 *    e-ink, and a door that lies is worse than one that is absent.
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
        return ref != null
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

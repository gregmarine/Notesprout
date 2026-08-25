package com.symmetricalpalmtree.notesproutsn.notebook

import android.util.Log
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.TooltipCompat
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.gpaper.core.PaperView
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.data.index.IndexRepository
import kotlinx.coroutines.launch

/**
 * The Recents flow (arc 10 / T1) — what the top bar's clock button and the two-finger swipe-down
 * both call. [ContentsFlow]'s twin, minus the availability machinery: the button is **always**
 * visible (an empty list says so in the panel), so there is no gate to refresh, nothing to hide, and
 * no generation counter.
 *
 * A [busy] guard (a second tap / swipe while gathering or showing is dropped) → a pen-gated
 * `releaseRender` → [RecentsSource.gather] on IO → on Main: the screen going away → nothing; else
 * [RecentsDialog]. **While the dialog is up the whole paper is one exclusion rect** ([showing] — the
 * host's `pushExclusions` reads it and pushes BLOCK_ALL, because the Ratta ink daemon draws firmware
 * ink beneath any Android window) and the chrome rects come back on dismiss.
 *
 * A row tap → dismiss → [switchTo] with the target's **notebook id**. The host re-reads the index,
 * seals this notebook and launches the target — this flow never promises the target still exists.
 *
 * The dialog's show/hide **rides frame-silence exception 6** rather than adding one: it is the same
 * act as the Contents dialog's — one chrome frame raising a full-height panel, after a chrome tap or
 * a swipe that already passed the gesture detector's pen gate, never a repaint under live ink. The
 * show is deliberately *not* idle-gated for the same reason: `isPenActive` counts hover, and a
 * hovering pen would hold the screen hostage.
 */
class RecentsFlow(
    private val activity: AppCompatActivity,
    private val paper: PaperView,
    private val repo: IndexRepository,
    private val notebookId: String,
    /** `opened && !closing` on the host. */
    private val alive: () -> Boolean,
    /** Called when [showing] flips — the host swaps the exclusion rects. */
    private val onShowingChanged: () -> Unit,
    /** Seal this notebook and open that one (the host's close-then-launch). */
    private val switchTo: (notebookId: String) -> Unit,
    /** The top bar's clock button — owned here: tap wired to [open]. */
    private val button: View,
) {
    init {
        TooltipCompat.setTooltipText(button, button.contentDescription)
        button.setOnClickListener { open() }
    }

    private var busy = false

    /** True while the panel is on screen — the host's BLOCK_ALL condition. */
    var showing: Boolean = false
        private set

    private var dialog: RecentsDialog? = null

    fun open() {
        if (busy || !alive()) return
        busy = true
        // Pen-gated (the R3 API contract): both entry points already passed a pen gate, but the pen
        // may have gone active in the gap — an ungated release inside that window can cost a live
        // stroke.
        if (!paper.isPenActive) paper.releaseRender()
        activity.lifecycleScope.launch {
            val rows = try {
                RecentsSource.gather(activity, repo, notebookId)
            } catch (e: Exception) {
                // Degrade with a log, never a crash: the index can be closing under us on the way
                // out of the screen, and a panel is never worth taking the notebook down for.
                busy = false
                if (alive()) Log.w(TAG, "gather failed — nothing shown", e)
                return@launch
            }
            if (!alive() || activity.isFinishing || activity.isDestroyed) { busy = false; return@launch }
            Slog.d(TAG) { "open: ${rows.size} rows" }
            showing = true
            onShowingChanged()   // BLOCK_ALL must be up before the dialog's first frame
            dialog = RecentsDialog(
                activity, rows,
                onDismissed = {
                    dialog = null
                    showing = false; busy = false
                    onShowingChanged()
                },
                onNotebookSelected = { id -> if (alive()) { Slog.d(TAG) { "switch → $id" }; switchTo(id) } },
            ).also { it.show() }
        }
    }

    /** The host's close hygiene — a Dialog outliving its finishing Activity is a window leak. */
    fun dismissIfShowing() {
        dialog?.dismiss()
    }

    private companion object {
        const val TAG = "RecentsFlow"
    }
}

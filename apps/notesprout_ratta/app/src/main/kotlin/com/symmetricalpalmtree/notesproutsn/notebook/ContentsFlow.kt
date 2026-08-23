package com.symmetricalpalmtree.notesproutsn.notebook

import android.util.Log
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.TooltipCompat
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.gpaper.core.PaperView
import com.symmetricalpalmtree.notesproutsn.core.Slog
import kotlinx.coroutines.launch

/**
 * The Contents flow (arc 4 / C1) — what the top-bar list button and the one-finger swipe-down both
 * call: a [busy] guard (a second tap / swipe while gathering or showing is dropped) → a pen-gated
 * `releaseRender` → [ContentsSource.gather] on IO → on Main: the screen going away → nothing; an
 * empty gather (a race with a delete) → nothing opens and [refresh] re-runs; else [ContentsDialog].
 * **While the dialog is up the whole paper is one exclusion rect** ([showing] — the host's
 * `pushExclusions` reads it and pushes its BLOCK_ALL, the `!opened` shield's trick: the Ratta ink
 * daemon draws firmware ink beneath any Android window) and the chrome rects come back on dismiss.
 * A row tap → dismiss → [navigate] with the entry's **page id** — never an index, because a page op
 * that committed under the gather (an escrowed undo, a queued insert) reindexes `session.pages` and
 * a snapshot index would land one page away. The host resolves the id at tap time (its
 * `refreshToPage` under the page-op lock; gone → no-op, current → no-op — nothing is selected on
 * arrival, the locked decision).
 *
 * The dialog's show/hide is **frame-silence exception #6**: both are one chrome frame at a
 * deliberate act that already passed a pen gate (a chrome tap, or a swipe committed through
 * `gateOpen()`), never a repaint under live ink — and the show is deliberately *not* idle-gated,
 * because `isPenActive` counts hover and a hovering pen would hold the screen hostage (the R3/P1
 * lesson; same reasoning as the selection toolbar's show).
 *
 * **Availability** ([refresh]): the button shows and the swipe acts only while
 * [ContentsSource.available] — ≥ 1 live heading on a live page. The host calls [refresh] after the
 * open, every `navigateTo` and every heading mutation; the button's visibility change goes through
 * [whenPenIdle] (frame-silence rule), and the flip triggers the root's layout listener, which
 * re-pushes the exclusion rects. Overlapping refreshes are generation-counted, and the pen-idle
 * closure reads [available] **when it fires** rather than carrying a target decided earlier — so
 * the button always ends in agreement with the gate (Paper's C2-hardened shape).
 *
 * This flow owns [button] outright (visibility, tooltip, tap) — `NotebookToolbar` keeps its
 * arming-only charter.
 */
class ContentsFlow(
    private val activity: AppCompatActivity,
    private val paper: PaperView,
    private val session: () -> NotebookSession,
    private val currentPageIndex: () -> Int,
    /** `opened && !closing` on the host. */
    private val alive: () -> Boolean,
    /** Called when [showing] or the button's visibility flips — the host swaps the exclusion rects. */
    private val onShowingChanged: () -> Unit,
    private val navigate: (pageId: String) -> Unit,
    /** The top-bar list button — owned here: shown / hidden by [refresh], tap wired to [open]. */
    private val button: View,
    private val whenPenIdle: (() -> Unit) -> Unit,
) {
    init {
        TooltipCompat.setTooltipText(button, button.contentDescription)
        button.setOnClickListener { open() }
    }

    private var busy = false

    /** [ContentsSource.available] as last computed — the swipe gate. */
    var available: Boolean = false
        private set

    /** True while the Contents dialog is on screen — the host's BLOCK_ALL condition. */
    var showing: Boolean = false
        private set

    /** Generation of the latest [refresh] — an older computation that finishes late is dropped. */
    private var refreshGen = 0

    private var dialog: ContentsDialog? = null

    /** Recompute [available] (IO, cheap) and show / hide [button] accordingly (pen-idle). */
    fun refresh() {
        if (!alive()) return
        val gen = ++refreshGen
        activity.lifecycleScope.launch {
            val now = try {
                ContentsSource.available(session())
            } catch (e: Exception) {
                // Closing under us (writer / db sealed on the app scope) — or a transient read
                // fault (disk pressure, WAL contention) on a live screen. This runs on every flip
                // and every neighbouring DB path degrades with a log (`runPageOp`, the writer, the
                // seal); a crash is never worth a button, so keep the last answer.
                if (alive()) Log.w(TAG, "refresh failed — keeping last answer", e)
                return@launch
            }
            if (!alive() || gen != refreshGen) return@launch
            available = now
            whenPenIdle {
                if (!alive()) return@whenPenIdle
                val vis = if (available) View.VISIBLE else View.GONE
                if (button.visibility != vis) { button.visibility = vis; onShowingChanged() }
            }
        }
    }

    fun open() {
        if (busy || !alive() || !available) return
        busy = true
        // Pen-gated (the R3 API contract): both entry points already passed a pen gate, but the
        // pen may have gone active in the gap — an ungated release inside that window can cost a
        // live stroke.
        if (!paper.isPenActive) paper.releaseRender()
        val t0 = System.currentTimeMillis()
        activity.lifecycleScope.launch {
            val outline = try {
                ContentsSource.gather(session())
            } catch (e: Exception) {
                // Same degrade-with-a-log rule as refresh(): closing under us, or a transient
                // read fault — nothing opens, the next tap retries.
                busy = false
                if (alive()) Log.w(TAG, "gather failed — nothing shown", e)
                return@launch
            }
            if (!alive() || activity.isFinishing || activity.isDestroyed) { busy = false; return@launch }
            if (outline.isEmpty) {
                // The last heading died between the gate and the gather — open nothing, re-gate.
                busy = false
                Slog.d(TAG) { "open: no entries — nothing shown" }
                refresh()
                return@launch
            }
            val current = currentPageIndex()
            Slog.d(TAG) {
                "open: entries=${outline.count} truncated=${outline.truncated} current=$current " +
                    "gathered in ${System.currentTimeMillis() - t0} ms"
            }
            showing = true
            onShowingChanged()   // BLOCK_ALL must be up before the dialog's first frame
            dialog = ContentsDialog(
                activity, outline, current,
                onDismissed = {
                    dialog = null
                    showing = false; busy = false
                    onShowingChanged()
                },
                onPageSelected = { pageId ->
                    if (alive()) { Slog.d(TAG) { "navigate → page $pageId" }; navigate(pageId) }
                },
            ).also { it.show() }
        }
    }

    /** The host's close hygiene — a Dialog outliving its finishing Activity is a window leak. */
    fun dismissIfShowing() {
        dialog?.dismiss()
    }

    private companion object {
        const val TAG = "ContentsFlow"
    }
}

package com.symmetricalpalmtree.notesprout.notebook

import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.gpaper.core.PaperView
import com.symmetricalpalmtree.notesprout.R
import com.symmetricalpalmtree.notesprout.core.Dialogs
import com.symmetricalpalmtree.notesprout.core.Slog
import kotlinx.coroutines.launch

/**
 * The Contents flow (arc 5 / C1) — what the top-bar list button and the one-finger swipe-down both
 * call: a [busy] guard (a second tap / swipe while gathering or showing is dropped) →
 * `paper.releaseRender()` → [ContentsSource.gather] on IO → on Main: the screen is going away →
 * nothing; [ContentsSource.Result.Failed] → the honest `objects_provider_failed` dialog naming the
 * extension under the "Contents" title (nothing opens — C0 Q4); [ContentsSource.Result.Ok] →
 * [ContentsDialog]. **While the dialog is up the whole paper is one exclusion rect** ([showing] —
 * the host's `pushExclusions` reads it, like the "Opening…" popup; the Onyx raw pen path bypasses the
 * window stack) and the chrome rects come back on dismiss. A row tap → dismiss → [navigate] with the
 * page index (the host's `navigateTo` under its page-op lock; a no-op when already there).
 *
 * **Availability** ([refresh], user's call after C1 item 9): the button shows and the swipe acts only
 * while [ContentsSource.available] — an outline-capable provider is loaded **and** the notebook holds an
 * object of one. The host calls [refresh] after every provider load, page change and object mutation;
 * the button's visibility change goes through [whenPenIdle] (frame-silence rule) and the host re-pushes
 * its exclusion rects. An empty gather (a race with a delete) opens nothing and re-refreshes.
 */
class ContentsFlow(
    private val activity: AppCompatActivity,
    private val paper: PaperView,
    private val session: () -> NotebookSession,
    private val providers: () -> ObjectProviders,
    private val currentPageIndex: () -> Int,
    /** `opened && !closing` on the host. */
    private val alive: () -> Boolean,
    /** Called when [showing] flips — the host swaps the exclusion rects. */
    private val onShowingChanged: () -> Unit,
    private val navigate: (pageIndex: Int) -> Unit,
    /** The top-bar list button — shown / hidden by [refresh]. */
    private val button: View,
    private val whenPenIdle: (() -> Unit) -> Unit,
) {
    private var busy = false

    /** [ContentsSource.available] as last computed — the swipe gate. */
    var available: Boolean = false
        private set

    /** Generation of the latest [refresh] — an older computation that finishes late is dropped (C2). */
    private var refreshGen = 0

    /**
     * Recompute [available] (IO, cheap) and show / hide [button] accordingly (pen-idle). Overlapping
     * refreshes: only the newest generation may write [available], and the pen-idle closure reads
     * [available] **when it fires** rather than carrying a target decided earlier — so the button
     * always ends in agreement with the gate (C2 review).
     */
    fun refresh() {
        if (!alive()) return
        val gen = ++refreshGen
        activity.lifecycleScope.launch {
            val now = try {
                ContentsSource.available(session(), providers())
            } catch (e: Exception) {
                // The screen is closing under us (writer / db sealed on the app scope) — nothing to show.
                if (alive()) throw e
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

    /** True while the Contents dialog is on screen. */
    var showing: Boolean = false
        private set

    fun open() {
        if (busy || !alive() || !available) return
        busy = true
        paper.releaseRender()
        val t0 = System.currentTimeMillis()
        activity.lifecycleScope.launch {
            val result = try {
                ContentsSource.gather(activity, session(), providers())
            } catch (e: Exception) {
                busy = false
                if (alive()) throw e   // closing under us → nothing to show; anything else is a bug worth a crash log
                return@launch
            }
            if (!alive() || activity.isFinishing || activity.isDestroyed) { busy = false; return@launch }
            when (result) {
                is ContentsSource.Result.Failed -> {
                    busy = false
                    Dialogs.problem(activity, R.string.contents_title, activity.getString(R.string.objects_provider_failed, result.providerLabel))
                }
                is ContentsSource.Result.Ok -> {
                    if (result.isEmpty) { busy = false; Slog.d(TAG) { "open: no entries — nothing shown" }; refresh(); return@launch }
                    val current = currentPageIndex()
                    Slog.d(TAG) { "open: entries=${result.count} truncated=${result.truncated} current=$current gathered in ${System.currentTimeMillis() - t0} ms" }
                    showing = true
                    onShowingChanged()
                    ContentsDialog(
                        activity, result, current,
                        onDismissed = {
                            showing = false; busy = false
                            onShowingChanged()
                        },
                        onPageSelected = { index ->
                            if (index != currentPageIndex() && alive()) { Slog.d(TAG) { "navigate → $index" }; navigate(index) }
                        },
                    ).show()
                }
            }
        }
    }

    private companion object {
        const val TAG = "ContentsFlow"
    }
}

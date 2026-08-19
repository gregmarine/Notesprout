package com.symmetricalpalmtree.notesprout.notebook

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
) {
    private var busy = false

    /** True while the Contents dialog is on screen. */
    var showing: Boolean = false
        private set

    fun open() {
        if (busy || !alive()) return
        busy = true
        paper.releaseRender()
        val t0 = System.currentTimeMillis()
        activity.lifecycleScope.launch {
            val result = ContentsSource.gather(activity, session(), providers())
            if (!alive() || activity.isFinishing || activity.isDestroyed) { busy = false; return@launch }
            when (result) {
                is ContentsSource.Result.Failed -> {
                    busy = false
                    Dialogs.problem(activity, R.string.contents_title, activity.getString(R.string.objects_provider_failed, result.providerLabel))
                }
                is ContentsSource.Result.Ok -> {
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

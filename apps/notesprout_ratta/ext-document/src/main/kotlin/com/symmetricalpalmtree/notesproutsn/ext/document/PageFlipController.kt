package com.symmetricalpalmtree.notesproutsn.ext.document

import android.app.Activity
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.ext.document.databinding.ActivityDocumentEditorBinding
import com.symmetricalpalmtree.notesproutsn.extension.DocumentPageState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Page flips (arc 19 / M6) — the editor walking the notebook, and the phase's most dangerous act.
 *
 * **The order is load-bearing**, and it is the whole reason this lives in its own file:
 *
 * 1. On Main, before anything async and before [DocumentSaver.pageKey] can move:
 *    [DocumentSaver.prepareFlip] cancels the timers, hands the **outgoing** caret over under the
 *    **outgoing** key, and snapshots the buffer with the draft claim it carries.
 * 2. That snapshot is pushed **first**, blocking, behind the ordinary push lock. If it does not
 *    land, the flip is abandoned and the editor stays: moving on would leave a page's words
 *    unwritten with no way back to them.
 * 3. Only then `requestPage`, which moves the host's target and swaps its read window atomically. A
 *    null answer means the target did **not** move — the editor stays, silently, exactly as og does.
 * 4. The incoming text is installed with `setText`, **not** through the `Editable`: arriving at
 *    another page is a new document, not an edit to this one, and it must never sit on the undo
 *    stack. Undoing "the flip" would otherwise drop the page you left into the page you arrived at,
 *    and the next autosave would store it there.
 *
 * Between 2 and 4 is **the no-save zone**: the host is being keyed to the incoming page while the
 * buffer still shows the outgoing one. The editor guards it with [DocumentSaver.suspended]; the host
 * guards its side by key. Both guards are needed — a save refused by key is a save that already
 * crossed the boundary.
 *
 * The progress dialog is the editor's own and is **delayed** by [READING_POPUP_DELAY_MS]: a page
 * that is already drafted flips instantly, and a dialog that flashes up and away on e-ink is a full
 * black frame and back.
 *
 * **The notebook behind this screen is not told anything.** It is stopped, and driving its drawing
 * surface from here is forbidden by the EPD rules; the host catches up when the editor closes.
 *
 * **No document text is logged here** — directions, lengths and class names only.
 */
internal class PageFlipController(
    private val activity: Activity,
    private val binding: ActivityDocumentEditorBinding,
    private val saver: DocumentSaver,
    /** The screen's lifecycle scope: a flip that outlives the screen has nothing to adopt into. */
    private val scope: CoroutineScope,
    /** Chrome that must go before the buffer is swapped — the find bar's count is stale across it. */
    private val onFlipStarting: () -> Unit,
    /** Install text without it landing on the undo stack (the Activity owns `applyingEdit`). */
    private val installText: (String) -> Unit,
    /** The incoming target has landed: the Activity redraws the header, the strip and the preview. */
    private val onAdopted: (DocumentPageState) -> Unit,
) {

    /** True from the moment a flip is allowed to start until the incoming page has landed (or the
     *  flip has been abandoned). Read by the guards in [FlipRules] and by the source strip. */
    var inFlight: Boolean = false
        private set

    private val popup = ReadingPopup(activity)

    /**
     * Flip one page. The caller has already asked [FlipRules] whether this is allowed — the edge
     * check is local and never costs a Binder round trip.
     */
    fun flip(direction: Int) {
        if (inFlight) return
        inFlight = true
        onFlipStarting()
        // Everything that must happen on Main, before the target can move.
        val outgoing = saver.prepareFlip()
        saver.suspended = true
        // Only if the flip is still running by then: a drafted page arrives with no dialog at all.
        popup.showAfter(READING_POPUP_DELAY_MS)

        scope.launch {
            if (outgoing != null) {
                val landed = withContext(Dispatchers.IO) { saver.pushForFlip(outgoing) }
                if (!landed) {
                    // The words are parked and the buffer stays dirty (the saver's own bookkeeping).
                    // The editor does not move.
                    abandon("outgoing save failed")
                    return@launch
                }
            }
            val arrived = withContext(Dispatchers.IO) { load(direction) }
            if (arrived == null) {
                // No page there, the load failed, or the host is gone. The host guarantees its
                // target did not move, so the editor is still where it was and later saves still
                // name the right page.
                abandon("no page")
                return@launch
            }
            if (activity.isFinishing || activity.isDestroyed) {
                // Done or Close arrived mid-flip. The leave path has already run; there is nothing
                // to adopt into.
                finish()
                return@launch
            }
            adopt(arrived)
        }
    }

    /** What one flip brought back, all of it materialised off Main. */
    private class Arrived(val state: DocumentPageState, val text: String, val caret: Int)

    /** **Blocking, on IO.** The request, the chunk pull and the caret lookup are one hop: the state
     *  names how many `readChunk` calls serve the window it just parked. */
    private fun load(direction: Int): Arrived? = try {
        val host = EditorSession.host
        val state = host?.requestPage(direction)
        if (state == null) {
            null
        } else {
            val text = buildString {
                for (i in 0 until state.textChunks) append(host.readChunk(i))
            }
            // The load-time caret lookup lives in the Activity's `load()`, so this path needs its
            // own (the M5 handoff note). A fresh seed opens at the top: there is no "where you left
            // off" for text that has never been on screen.
            val caret = if (state.seeded) 0 else EditorPrefs.caret(state.pageKey)
            Arrived(state, text, caret)
        }
    } catch (e: Exception) {
        Slog.d(TAG) { "flip failed: ${e.javaClass.simpleName}" }
        null
    }

    /** The incoming page becomes this screen's document. **Main thread.** */
    private fun adopt(arrived: Arrived) {
        popup.hide()
        val state = arrived.state
        // setText, never an Editable edit: a flip is a new document, not an edit to this one.
        installText(arrived.text)
        // Only NOW — the outgoing push named the old key, and so did the outgoing caret.
        saver.pageKey = state.pageKey
        // A flip seeds an undrafted page exactly as opening one does — same rule, same class.
        val drafted = saver.adoptWindow(arrived.text, state.seeded)
        binding.editor.setSelection(arrived.caret.coerceIn(0, arrived.text.length))
        finish()
        onAdopted(state)
        // A seed is unsaved by definition — arm the save that makes it real.
        if (drafted) saver.schedule()
        Slog.d(TAG) { "flipped to page ${state.pageIndex + 1}/${state.pageCount} (seeded=${state.seeded})" }
    }

    /** The screen is going (`onDestroy`). The coroutine paths that normally hide the popup ride the
     *  cancelled lifecycle scope and will never run — this is the hide they cannot do. */
    fun close() {
        popup.hide()
    }

    /** The flip did not happen. The editor stays exactly where it was — og reverts silently. */
    private fun abandon(why: String) {
        finish()
        Slog.d(TAG) { "flip abandoned: $why" }
    }

    private fun finish() {
        popup.hide()
        saver.suspended = false
        inFlight = false
    }

    companion object {
        const val TAG = "DocumentEditor"

        /** og's constant: below this a flip is instant enough that a dialog would only flash. */
        const val READING_POPUP_DELAY_MS = 350L
    }
}

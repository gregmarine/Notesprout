package com.symmetricalpalmtree.notesproutsn.ext.document

import android.app.Activity
import androidx.appcompat.widget.TooltipCompat
import com.symmetricalpalmtree.notesproutsn.core.ActionSheetDialog
import com.symmetricalpalmtree.notesproutsn.core.Dialogs
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.ext.document.databinding.ActivityDocumentEditorBinding
import com.symmetricalpalmtree.notesproutsn.extension.DocumentContract
import com.symmetricalpalmtree.notesproutsn.extension.DocumentPageState
import com.symmetricalpalmtree.notesproutsn.markdown.DocumentDraft
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The source strip (arc 19 / M6): one line of provenance and the two acts that can change it —
 * og's, on SN's chrome.
 *
 * ```
 *  Page has changed since this draft        [Reflow] [Bring in]
 * ```
 *
 * It says where this document came from ("Drafted from this page" / "Page has changed since this
 * draft" / "Not drafted from this page"), and it is **the only route by which the page can
 * overwrite the document**. The seeding path writes a draft into the editor once, at open or at a
 * flip; after that, recognition never touches these words again unless the writer asks here.
 *
 * *Reflow* sits to the **left** of *Bring in* so that Bring in keeps its position under the hand.
 *
 * The Replace-or-Append choice is made **before** recognition runs (og's order), so there is never a
 * "cancel after waiting" state and no watermark to un-stamp — and the reading popup goes up
 * immediately, because a Bring in always reads the page in full.
 *
 * Both choices are applied through the buffer's `Editable`, the same route the format bar takes, so
 * the editor's own Ctrl+Z takes a refresh back within the session.
 *
 * **Nothing here logs a character of the document, or of what was read** — lengths and class names
 * only, on both the success and the failure path.
 */
internal class SourceStrip(
    private val activity: Activity,
    private val binding: ActivityDocumentEditorBinding,
    private val saver: DocumentSaver,
    /** The screen's lifecycle scope — a bring-in that outlives the screen has nothing to apply to. */
    private val scope: CoroutineScope,
    /** False while a flip owns the buffer, or while the screen is leaving. */
    private val canBringIn: () -> Boolean,
    private val onReflow: () -> Unit,
    /** The page's text landed in the buffer: the Activity re-reads the header and the strip from
     *  this state, and scrolls to the caret. */
    private val onBroughtIn: (DocumentPageState) -> Unit,
) {

    /** True while a Bring in is reading the page — a flip must not start over it, nor it over a
     *  flip. */
    var inFlight: Boolean = false
        private set

    private val popup = ReadingPopup(activity)

    /** Listeners and hints. Called once, from the Activity's chrome build. */
    fun install() {
        binding.btnReflow.setOnClickListener { onReflow() }
        binding.btnBringIn.setOnClickListener { promptBringIn() }
        for (button in listOf(binding.btnReflow, binding.btnBringIn)) {
            TooltipCompat.setTooltipText(button, button.contentDescription)
        }
    }

    /** Draw the line for [source] — one of [DocumentContract]'s three `SOURCE_*`. */
    fun show(source: Int) {
        binding.sourceLabel.setText(
            when (source) {
                DocumentContract.SOURCE_DRAFTED -> R.string.document_source_drafted
                DocumentContract.SOURCE_STALE -> R.string.document_source_stale
                else -> R.string.document_source_none
            },
        )
    }

    /** The line as it reads now — for the debug automation hook only. */
    fun label(): String = binding.sourceLabel.text?.toString().orEmpty()

    /** The screen is going (`onDestroy`). A bring-in's coroutine rides the cancelled lifecycle
     *  scope and its own `popup.hide()` will never run — this is the hide it cannot do. */
    fun close() {
        popup.hide()
    }

    // ── Bring in ──────────────────────────────────────────────────────────────

    /** og's sheet: the choice first, the reading afterwards. */
    private fun promptBringIn() {
        if (inFlight || !canBringIn()) return
        ActionSheetDialog(activity)
            .title(activity.getString(R.string.bring_in_title))
            .addAction(null, activity.getString(R.string.bring_in_replace)) {
                bringIn(DocumentContract.BRING_REPLACE)
            }
            .addAction(null, activity.getString(R.string.bring_in_append)) {
                bringIn(DocumentContract.BRING_APPEND)
            }
            .show()
    }

    /**
     * Read the current page and bring its text in — [DocumentContract.BRING_REPLACE] or
     * [DocumentContract.BRING_APPEND]. The sheet is the ordinary way here; the debug hook calls this
     * directly, which is the same path minus the tap.
     */
    fun bringIn(mode: Int) {
        if (inFlight || !canBringIn()) return
        inFlight = true
        // Immediately, not on a delay: this always recognizes the page in full, so there is always
        // a wait to explain.
        popup.show()
        scope.launch {
            val brought = withContext(Dispatchers.IO) { read(mode) }
            popup.hide()
            inFlight = false
            if (activity.isFinishing || activity.isDestroyed) return@launch
            val state = brought.state
            if (state == null) {
                showFailure(brought.error)
                return@launch
            }
            apply(mode, state, brought.text)
        }
    }

    /** What one Bring in came back with — the state and its window's text, or the exception. */
    private class Brought(val state: DocumentPageState?, val text: String, val error: Exception?)

    /** **Blocking, on IO.** The request and the chunk pull are one hop: the state names how many
     *  `readChunk` calls serve the window it just parked. */
    private fun read(mode: Int): Brought = try {
        val host = EditorSession.host ?: throw IllegalStateException("no showing")
        val state = host.requestSeed(mode)
        val text = buildString {
            for (i in 0 until state.textChunks) append(host.readChunk(i))
        }
        Brought(state, text, null)
    } catch (e: Exception) {
        // The class name only: an exception's message from either side of this seam could carry a
        // path — and one of them is a contract string the failure dialog reads below.
        Slog.d(TAG) { "bring in failed: ${e.javaClass.simpleName}" }
        Brought(null, "", e)
    }

    /** Install the page's text through the `Editable`, then anchor it with a save of its own. */
    private fun apply(mode: Int, state: DocumentPageState, drafted: String) {
        val editable = binding.editor.text ?: return
        val append = mode == DocumentContract.BRING_APPEND
        // The `---` join is :markdown's rule, and it only writes the rule when there is something on
        // both sides of it.
        val merged = if (append) DocumentDraft.append(editable.toString(), drafted) else drafted
        // One `Editable` edit, so one Ctrl+Z takes the whole refresh back.
        editable.replace(0, editable.length, merged)
        // Replace lands the reader at the top of what arrived; Append lands them at the end of it,
        // which is where the new words are.
        binding.editor.setSelection(if (append) editable.length else 0)

        saver.armDraft()
        show(DocumentContract.SOURCE_DRAFTED)
        onBroughtIn(state)
        // Promptly, and FORCED past the unchanged-text drop: a Bring in re-anchors the watermark
        // even when the draft came out identical to what was already here (og's rule — the
        // re-anchoring is the whole act), and it is this save that consumes the host's parked
        // watermark. A draft that is only in the buffer is also a draft a process kill loses.
        saver.saveDraftNow()
        Slog.d(TAG) { "brought in ${drafted.length} chars (${if (append) "append" else "replace"})" }
    }

    /**
     * Why nothing arrived. "Recognition isn't available" is something the reader can act on — no
     * recognizer installed, or a model still to download — and it is a typed refusal carrying
     * exactly [DocumentContract.SEED_UNAVAILABLE], matched with `==` on the nose. Everything else is
     * a failure, and says so without pretending to know more.
     */
    private fun showFailure(error: Exception?) {
        if (error is IllegalStateException && error.message == DocumentContract.SEED_UNAVAILABLE) {
            Dialogs.problem(
                activity,
                R.string.document_seed_unavailable_title,
                R.string.document_seed_unavailable_body,
            )
        } else {
            Dialogs.problem(
                activity,
                R.string.document_seed_failed_title,
                R.string.document_seed_failed_body,
            )
        }
    }

    private companion object {
        const val TAG = "DocumentEditor"
    }
}

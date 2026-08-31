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
 * The source strip (arc 19 / M6, grown at M7): one line of provenance and the two acts that can
 * change it — og's, on SN's chrome.
 *
 * ```
 *  Page has changed since this draft          [Reflow] [Bring in]     ← a page's document
 *  Pages have changed since this merge        [Reflow] [Merge]        ← the notebook document
 * ```
 *
 * It says where this document came from, and it is **the only route by which the pages can overwrite
 * the document**. The seeding path writes a draft into the editor once, at open, at a flip or at a
 * scope switch; after that, recognition never touches these words again unless the writer asks here.
 *
 * **One button, two acts.** In the page scope it is *Bring in* over `requestSeed` — this page's
 * text. In the notebook scope it is *Merge* over `requestMerge` — every page's, joined. Same sheet
 * shape, same Replace / Append, same Editable-and-one-Ctrl+Z application; what differs is the word
 * on the button, the sheet's title, and the one rule below that only the notebook needs.
 *
 * **A blank merge is a silent no-op** ([ScopeRules.mergeLands]). The host can answer honestly with
 * an empty window when the notebook's pages had nothing to give, and applying Replace then would
 * blank a hand-authored document in exchange for nothing. Nothing is touched: not the buffer, not
 * the claim, not the strip.
 *
 * *Reflow* sits to the **left** of the act so that the act keeps its position under the hand.
 *
 * The Replace-or-Append choice is made **before** recognition runs (og's order), so there is never a
 * "cancel after waiting" state and no watermark to un-stamp — and the reading popup goes up
 * immediately, because both acts always read in full. A **merge** can walk every page, so its popup
 * carries a Cancel; a page's Bring in reads one page and does not.
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
    /**
     * The adopted state's [DocumentPageState.scope]. A supplier rather than an argument because the
     * strip is also redrawn from the saver's draft callbacks, which know nothing about targets — one
     * source of truth beats three call sites that could disagree.
     */
    private val documentScope: () -> Int,
    /** False while a flip or a scope switch owns the buffer, or while the screen is leaving. */
    private val canBringIn: () -> Boolean,
    private val onReflow: () -> Unit,
    /** The text landed in the buffer: the Activity re-reads the header and the strip from this
     *  state, and scrolls to the caret. */
    private val onBroughtIn: (DocumentPageState) -> Unit,
) {

    /** True while a Bring in / Merge is reading — a flip or a scope switch must not start over it,
     *  nor it over one of them. */
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

    /** Draw the line for [source] — one of [DocumentContract]'s three `SOURCE_*` — as the current
     *  scope reads it, and put the matching word on the act's button. */
    fun show(source: Int) {
        val scopeNow = documentScope()
        when (ScopeRules.provenance(scopeNow, source)) {
            ScopeRules.SourceLine.DRAFTED -> binding.sourceLabel.setText(R.string.document_source_drafted)
            ScopeRules.SourceLine.STALE -> binding.sourceLabel.setText(R.string.document_source_stale)
            ScopeRules.SourceLine.NONE -> binding.sourceLabel.setText(R.string.document_source_none)
            ScopeRules.SourceLine.MERGED -> binding.sourceLabel.setText(R.string.document_source_merged)
            ScopeRules.SourceLine.MERGE_STALE -> binding.sourceLabel.setText(R.string.document_source_pages_stale)
            // The notebook document with no merge behind it is a document the writer made on
            // purpose; naming its lack of provenance would be noise (og).
            ScopeRules.SourceLine.SILENT -> binding.sourceLabel.text = ""
        }
        relabel(ScopeRules.isNotebook(scopeNow))
    }

    /** The line as it reads now — for the debug automation hook only. */
    fun label(): String = binding.sourceLabel.text?.toString().orEmpty()

    /** The screen is going (`onDestroy`). A bring-in's coroutine rides the cancelled lifecycle
     *  scope and its own `popup.hide()` will never run — this is the hide it cannot do. */
    fun close() {
        popup.hide()
    }

    /** Bring in ↔ Merge. Idempotent, and driven from [show] so the word and the line can never
     *  describe different scopes. */
    private fun relabel(notebook: Boolean) {
        val label = if (notebook) R.string.document_merge else R.string.document_bring_in
        val hint = activity.getString(
            if (notebook) R.string.cd_document_merge else R.string.cd_document_bring_in,
        )
        binding.btnBringIn.setText(label)
        binding.btnBringIn.contentDescription = hint
        TooltipCompat.setTooltipText(binding.btnBringIn, hint)
    }

    // ── Bring in / Merge ──────────────────────────────────────────────────────

    /** og's sheet: the choice first, the reading afterwards. */
    private fun promptBringIn() {
        if (inFlight || !canBringIn()) return
        val notebook = ScopeRules.isNotebook(documentScope())
        ActionSheetDialog(activity)
            .title(activity.getString(if (notebook) R.string.merge_title else R.string.bring_in_title))
            .addAction(null, activity.getString(R.string.bring_in_replace)) {
                bringIn(DocumentContract.BRING_REPLACE)
            }
            .addAction(null, activity.getString(R.string.bring_in_append)) {
                bringIn(DocumentContract.BRING_APPEND)
            }
            .show()
    }

    /**
     * Read — this page in the page scope, every page in the notebook scope — and bring the text in:
     * [DocumentContract.BRING_REPLACE] or [DocumentContract.BRING_APPEND]. The sheet is the ordinary
     * way here; the debug hook calls this directly, which is the same path minus the tap.
     */
    fun bringIn(mode: Int) {
        if (inFlight || !canBringIn()) return
        inFlight = true
        // Fixed for the whole run: a scope switch cannot start over an in-flight read (its guard
        // reads `inFlight`), so the scope that asked is the scope that applies.
        val scopeNow = documentScope()
        val notebook = ScopeRules.isNotebook(scopeNow)
        // Immediately, not on a delay: both acts always read in full, so there is always a wait to
        // explain. Only the notebook's walk of every page is worth a way out of.
        popup.show(
            if (notebook) R.string.document_reading_pages else R.string.document_reading_page,
            onCancel = if (notebook) ({ HostCancel.fire(scope) }) else null,
        )
        scope.launch {
            val brought = withContext(Dispatchers.IO) { read(mode, notebook) }
            popup.hide()
            inFlight = false
            if (activity.isFinishing || activity.isDestroyed) return@launch
            val state = brought.state
            if (state == null) {
                showFailure(brought.error)
                return@launch
            }
            if (!ScopeRules.mergeLands(scopeNow, brought.text)) {
                // An honest empty answer: the pages had nothing to give. The buffer, the claim and
                // the strip are all left exactly as they were — a Replace over empty pages must
                // never blank a document somebody wrote by hand (og).
                Slog.d(TAG) { "merge came back empty — nothing applied" }
                return@launch
            }
            apply(mode, state, brought.text)
        }
    }

    /** What one Bring in / Merge came back with — the state and its window's text, or the
     *  exception. */
    private class Brought(val state: DocumentPageState?, val text: String, val error: Exception?)

    /** **Blocking, on IO.** The request and the chunk pull are one hop: the state names how many
     *  `readChunk` calls serve the window it just parked. */
    private fun read(mode: Int, notebook: Boolean): Brought = try {
        val host = EditorSession.host ?: throw IllegalStateException("no showing")
        val state = if (notebook) host.requestMerge(mode) else host.requestSeed(mode)
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

    /** Install the text through the `Editable`, then anchor it with a save of its own. */
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
        // Promptly, and FORCED past the unchanged-text drop: a Bring in / Merge re-anchors the
        // watermark to the state just read even when the draft came out identical to what was
        // already here (og's rule — the re-anchoring is the whole act), and it is this save that
        // consumes the host's parked watermark. A draft that is only in the buffer is also a draft
        // a process kill loses.
        saver.saveDraftNow()
        Slog.d(TAG) { "brought in ${drafted.length} chars (${if (append) "append" else "replace"})" }
    }

    /**
     * Why nothing arrived — or why nothing was supposed to.
     *
     * "Recognition isn't available" is something the reader can act on — no recognizer installed, or
     * a model still to download — and it is a typed refusal carrying exactly
     * [DocumentContract.SEED_UNAVAILABLE], matched with `==` on the nose.
     * [DocumentContract.MERGE_CANCELLED] is the reader's own Cancel coming back: nothing was
     * written, and a dialog explaining a cancellation they asked for would be noise, so it is
     * **silent**. Everything else is a failure, and says so without pretending to know more.
     */
    private fun showFailure(error: Exception?) {
        when ((error as? IllegalStateException)?.message) {
            DocumentContract.MERGE_CANCELLED -> Slog.d(TAG) { "merge cancelled" }
            DocumentContract.SEED_UNAVAILABLE -> Dialogs.problem(
                activity,
                R.string.document_seed_unavailable_title,
                R.string.document_seed_unavailable_body,
            )

            else -> Dialogs.problem(
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

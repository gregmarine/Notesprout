package com.notesprout.android

/**
 * In-memory hand-off between [NotebookActivity] and [DocumentEditorActivity] — and the editor's only
 * route to the notebook.
 *
 * The editor never opens the `.soil` itself. `SoilDatabase` is one-instance-per-open-notebook by
 * design, and a second writing connection to a file another activity holds open is the shape of this
 * project's worst data-loss bugs (see docs/encryption.md). So the host, which already owns the live
 * connection, does every read and write on the editor's behalf — the same division of labour as
 * [StickyNoteEditorTransfer].
 *
 * [live] is the editor's current buffer, republished on every autosave. It exists so a teardown the
 * editor cannot see — the host being destroyed under it, taking the DB with it — can still flush the
 * text before the connection closes.
 */
object DocumentTransfer {

    /** A document's text plus the page state it was drafted from (null = authored by hand). */
    data class Draft(val text: String, val srcUpdatedAt: Long?)

    /**
     * One page's document, as the editor needs to show it: the text, its provenance, and where the page
     * sits in the notebook. Handed over at open and again on every page flip.
     */
    data class Session(
        val text: String,
        val srcUpdatedAt: Long?,
        /** True when the page has been written on since [text] was drafted from it. */
        val stale: Boolean,
        /** Header label — the page's place in the notebook, e.g. "4 / 12". */
        val pageLabel: String,
        val hasPrev: Boolean,
        val hasNext: Boolean,
        /** Where the caret was left last time; 0 (the top) when this page has not been open before. */
        val caret: Int,
        /**
         * True when this is the **notebook document** — the whole notebook's merged final draft —
         * rather than one page's. Page flips don't apply there, and saves land on the
         * notebook-parented row. See docs/documents.md.
         */
        val notebook: Boolean = false,
    )

    /** What the host does for the editor. Implemented by [NotebookActivity]. */
    interface Host {
        /**
         * Persist [text] as the open page's document, keeping the current source watermark, and
         * remember [caret] as where the writer left off on this page.
         */
        fun saveDocument(text: String, caret: Int)

        /**
         * Recognize the page afresh and call back with its text on the main thread (null when
         * recognition is unavailable or the page has nothing to give). Powers "bring in page text".
         */
        fun requestPageDraft(onResult: (Draft?) -> Unit)

        /**
         * Move [delta] pages and call back with that page's document on the main thread (null when
         * there is no such page). The caller must have stored its current text first — the host
         * switches which page it writes to as part of this call.
         *
         * The notebook itself only catches up when the editor closes: it is stopped while the editor is
         * in front, and driving the drawing surface then is exactly what the EPD rules forbid.
         */
        fun requestPage(delta: Int, onResult: (Session?) -> Unit)

        /**
         * Switch to the **notebook document** and call back with its session on the main thread —
         * merging every page's text to seed it if it does not exist yet (that run shows a
         * cancellable progress popup; cancel calls back null and the editor stays where it is).
         * The caller must have stored its current text first, same contract as [requestPage].
         * Toggling back to page mode is `requestPage(0)` — the host retains which page the editor
         * was on.
         */
        fun requestNotebookDocument(onResult: (Session?) -> Unit)

        /**
         * Re-merge the pages' text and call back with it on the main thread (null when the merge
         * is unavailable, produced nothing, or was cancelled). The notebook-mode twin of
         * [requestPageDraft]; powers the source strip's Merge action.
         */
        fun requestNotebookMerge(onResult: (Draft?) -> Unit)

        /**
         * Stop an in-flight [requestNotebookDocument] / [requestNotebookMerge] — the popup's
         * Cancel button. The cancelled request still calls back (with null), which is what puts
         * the editor's UI right; a no-op when nothing is running.
         */
        fun cancelDocumentRequest()

        /**
         * Rename the notebook — text documents let the editor's title do this. The host owns the
         * duplicate check against siblings, the index write, the meta refresh, and its own title;
         * calls back on the main thread with null on success or a user-facing error message.
         */
        fun renameNotebook(name: String, onResult: (String?) -> Unit)
    }

    /** Installed before launching the editor; cleared when the host is destroyed. */
    var host: Host? = null

    /** The page to open on — existing text, or a fresh draft seeded from the page. */
    var input: Session? = null

    /** The editor's current text, for the host's teardown flush. */
    var live: String? = null

    /** The caret that goes with [live], so a teardown remembers the place as well as the words. */
    var liveCaret: Int = 0

    /** Drop everything but [host] — called by the host once a session's text is safely stored. */
    fun clearSession() {
        input = null
        live = null
        liveCaret = 0
    }
}

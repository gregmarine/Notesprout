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
        /** Header label — e.g. "Page 4/12". */
        val pageLabel: String,
        val hasPrev: Boolean,
        val hasNext: Boolean,
    )

    /** What the host does for the editor. Implemented by [NotebookActivity]. */
    interface Host {
        /** Persist [text] as the open page's document, keeping the current source watermark. */
        fun saveDocument(text: String)

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
    }

    /** Installed before launching the editor; cleared when the host is destroyed. */
    var host: Host? = null

    /** The page to open on — existing text, or a fresh draft seeded from the page. */
    var input: Session? = null

    /** The editor's current text, for the host's teardown flush. */
    var live: String? = null

    /** Drop everything but [host] — called by the host once a session's text is safely stored. */
    fun clearSession() {
        input = null
        live = null
    }
}

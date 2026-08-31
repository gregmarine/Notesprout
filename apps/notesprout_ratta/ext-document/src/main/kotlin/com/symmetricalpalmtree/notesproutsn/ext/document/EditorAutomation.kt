package com.symmetricalpalmtree.notesproutsn.ext.document

/**
 * The debug automation seam — **one nullable static and an interface of accessors, and nothing
 * else**.
 *
 * It exists because the Supernote's IME swallows `adb shell input text`, so a walk agent cannot type
 * into this screen at all. The commands that drive it live entirely in `src/debug/` (the receiver);
 * this file is in `main` only because the screen has to be able to hand *something* over, and a
 * class in `debug` cannot be named from `main`.
 *
 * In a release build [peer] is never assigned — the registration is behind `BuildConfig.DEBUG` and
 * the receiver that would read it is not in the APK at all. So release ships the declarations and no
 * automation behaviour: nothing can reach the buffer through here.
 *
 * Every member is called **on the main thread** and none of them logs anything — the automation path
 * carries whole documents, and document text is never logged on either side of this seam.
 */
internal interface AutomationPeer {

    /** Replace the buffer, or append at the caret when [append] is true. */
    fun setText(text: String, append: Boolean)

    /** The live buffer. */
    fun text(): String

    fun caret(): Int
    fun setCaret(position: Int)

    fun isPreviewing(): Boolean
    fun setPreviewing(on: Boolean)

    /** Whether the buffer differs from what the host is known to hold. */
    fun isDirty(): Boolean

    /** The header's `n / m`, or "" for a target that is not a page. */
    fun pageLabel(): String

    /** Fire a save now rather than waiting for the idle debounce. */
    fun saveNow()

    /** The Done path (final save, `RESULT_OK`, finish) and the Close path (`RESULT_CANCELED`). */
    fun done()
    fun close()

    // ── M5's tools ────────────────────────────────────────────────────────────
    // The find query is user content too: it crosses here and is never logged either.

    /** Open the find bar on [query]; returns how many matches it has. */
    fun findOpen(query: String): Int

    /** Step to the next / previous match; returns the count field as it now reads. */
    fun findStep(backwards: Boolean): String

    /** Replace every match with [replacement]; returns how many were replaced. */
    fun findReplaceAll(replacement: String): Int

    fun findClose()

    /** Join wrapped lines — the selection's, or the document's. */
    fun reflow()

    /** (words, characters) over the same slice the toast reports on. */
    fun wordCount(): Pair<Int, Int>

    /** The editor's own undo — what Ctrl+Z does, which a walk cannot press. */
    fun undo()

    /** The size preference in sp (**not** `editor.textSize`, which is px). */
    fun textSize(): Float
    fun setTextSize(sp: Float)

    // ── M6's flips and the source strip ───────────────────────────────────────
    // The page's recognized text is user content like everything else here: it crosses on the
    // bring-in path and is never logged.

    /**
     * Flip a page — `PAGE_PREV` / `PAGE_NEXT`. Returns nothing on purpose: the flip pushes the
     * outgoing page and reads the incoming one, so a walk polls [pageLabel] (and [text]) afterwards
     * rather than expecting an answer here. At an edge it toasts and nothing moves.
     */
    fun flip(direction: Int)

    /**
     * Bring the page's text in — `BRING_REPLACE` / `BRING_APPEND`. The same path the sheet's two
     * rows take, minus the sheet. Also asynchronous: poll [text] and [sourceLabel].
     */
    fun bringIn(mode: Int)

    /** The source strip's line as it now reads. */
    fun sourceLabel(): String

    // ── M7's scope toggle and the notebook merge ──────────────────────────────

    /** The adopted target's scope — `SCOPE_PAGE` / `SCOPE_NOTEBOOK`, and `SCOPE_PAGE` before the
     *  first state has landed. */
    fun scope(): Int

    /**
     * The header toggle's tap: page ↔ notebook document. Asynchronous like a flip — it pushes the
     * outgoing text and reads the incoming target, and entering the notebook scope may auto-merge
     * the whole notebook — so poll [scope] / [pageLabel] / [text] afterwards. Silent when the
     * guards refuse it, exactly as the button is.
     */
    fun toggleScope()

    /**
     * The notebook Merge — `BRING_REPLACE` / `BRING_APPEND`, the sheet's two rows without the
     * sheet. Returns false, having done nothing, when the target is not the notebook document: a
     * walk that thinks it merged when it brought a page in would report the wrong thing. Also
     * asynchronous: poll [text] and [sourceLabel].
     */
    fun merge(mode: Int): Boolean

    // ── M8's text documents ───────────────────────────────────────────────────
    // The notebook's name is user content too: it crosses here and is never logged.

    /**
     * The "Show pages" button's tap — the text document's exit to its canvas. Returns false, having
     * done nothing, when the button is not on screen (not a text document, or not the notebook
     * scope): a walk that thinks it opened the pages when there was no button would report the wrong
     * thing. Leaves the screen like every other exit, flushing first.
     */
    fun showPages(): Boolean

    /**
     * Rename the notebook, **without the dialog** — a walk cannot type into an `AlertDialog` any
     * more than it can type into the editor. Same guards and same host call as the title's tap.
     * Returns false when it did not start (not a text document, one already running, blank, or the
     * name it already has). Asynchronous: poll [title].
     */
    fun rename(name: String): Boolean

    /** The header's title as it now reads. */
    fun title(): String
}

/** The live screen's peer, or null when no screen is up (and always null in release). */
internal object EditorAutomation {
    @Volatile
    var peer: AutomationPeer? = null
}

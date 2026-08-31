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
}

/** The live screen's peer, or null when no screen is up (and always null in release). */
internal object EditorAutomation {
    @Volatile
    var peer: AutomationPeer? = null
}

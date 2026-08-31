package com.symmetricalpalmtree.notesproutsn.notebook

import com.symmetricalpalmtree.notesproutsn.extension.DocumentContract

/**
 * Where the notebook screen goes when the notebook it opened is a **text document** (arc 19 / M8) —
 * **pure, no Android, no `.soil`**, for the same reason [DocumentTargetRules] is: `NotebookActivity`
 * cannot be constructed in a JVM test, and what it must get right is exactly the two tables below.
 *
 * A text document is an ordinary notebook underneath — pages, ink, everything — flagged
 * ([com.symmetricalpalmtree.notesproutsn.data.index.NotebookFlags.TEXT_DOCUMENT]) so that it *opens*
 * into the editor rather than onto paper. Two things follow, and they are what these tables encode:
 *
 *  - **The canvas load is deferred, and often never happens.** Opening into the editor runs the
 *    lightweight setup only — the page id the hooks fall back to, and nothing else: no strokes, no
 *    headings, no links, no template, no `loadStrokes`. A close straight back to the library never
 *    touches the paper at all, which is the whole point (a text document should cost what a text
 *    document costs).
 *  - **Once the canvas IS shown, the notebook is ordinary for the rest of its life.** [canvasShown]
 *    is a one-way latch for the incarnation: the Document button reopens the editor through the
 *    normal seed flow, and the showing after that ends in the ordinary catch-up, never in a seal.
 *
 * **The advisory's absence is a decision, not a gap.** `mode` is what the editor said through
 * `IDocumentHost.closeNotebook` — [DocumentContract.CLOSE_SHOW_PAGES] (✓ Done) or
 * [DocumentContract.CLOSE_TO_LIBRARY] (the header's leave door). **null = it never said**: the back
 * arrow, a process-death edge, a debug hook. That reads as to-library, deliberately, because the two
 * mistakes are not the same size: a notebook wrongly sealed is reopened with one tap, while a canvas
 * wrongly loaded cannot be un-loaded — the strokes are on the paper and the seal is owed a cover.
 */
object TextDocRouting {

    /** What [NotebookActivity]'s open does once the session is up. */
    enum class Open {
        /** The ordinary open: strokes, headings, links, template, paper — every notebook's path. */
        CANVAS,

        /** The lightweight setup, then launch the editor (the fresh text-document open). */
        EDITOR_LAUNCH,

        /** The lightweight setup and nothing else: an editor is already on screen and the host has
         *  re-bound to it ([com.symmetricalpalmtree.notesproutsn.extension.DocumentEditorEntry.reconnect]).
         *  Launching a second one would bind twice over one showing. */
        EDITOR_RECONNECT,

        /** Seal and leave without ever touching the paper: the showing ended while this open was
         *  still on IO, and it ended toward the library. */
        SEAL_AND_LEAVE,
    }

    /** What the end of a showing means to the screen underneath it. */
    enum class Close {
        /** Every ordinary notebook, and every text document whose canvas is already up: follow the
         *  editor to the page it ended on (og's `navigateToPage(endedOn)`). */
        CATCH_UP,

        /** ✓ Done on a text document that has never shown its pages: load the canvas now, on the
         *  page the editor ended on. */
        LOAD_CANVAS,

        /** The text document is done: cover, meta, seal, finish — the paper is never loaded. */
        SEAL_TO_LIBRARY,
    }

    /**
     * The open route. [parkedClose] is a showing whose end landed while the session was still
     * opening ([parkClose]) and has since been re-decided with [closeDecision] — it outranks
     * everything, because it is news about a showing that is already over: sealing wins, and
     * anything else is the canvas. Never a launch there; the editor it would launch is the one the
     * user just left.
     *
     * After that the order is: an ordinary notebook and a text document with its canvas up are both
     * the ordinary open; a live showing is a reconnect; and only a fresh, canvas-less text document
     * launches.
     */
    fun openDecision(
        isTextDocument: Boolean,
        canvasShown: Boolean,
        reconnectPending: Boolean,
        parkedClose: Close? = null,
    ): Open = when {
        parkedClose == Close.SEAL_TO_LIBRARY -> Open.SEAL_AND_LEAVE
        parkedClose != null -> Open.CANVAS
        !isTextDocument -> Open.CANVAS
        canvasShown -> Open.CANVAS
        reconnectPending -> Open.EDITOR_RECONNECT
        else -> Open.EDITOR_LAUNCH
    }

    /**
     * What the end of a showing means. [mode] is the editor's advisory — see the class doc for why
     * null (and any value this build does not know) reads as to-library for a text document, and
     * why it is only ever asked about a text document whose canvas has never been shown.
     */
    fun closeDecision(isTextDocument: Boolean, canvasShown: Boolean, mode: Int?): Close = when {
        !isTextDocument -> Close.CATCH_UP
        canvasShown -> Close.CATCH_UP
        mode == DocumentContract.CLOSE_SHOW_PAGES -> Close.LOAD_CANVAS
        else -> Close.SEAL_TO_LIBRARY
    }

    /**
     * Whether the end of a showing must be **parked** for the open to answer rather than acted on
     * where it arrives. An `ActivityResult` callback runs before `onResume`, so it can land while a
     * recreated host's open is still on IO (the arc's S2 trap): there is no session to seal, no
     * canvas to load from, and — since the flag is read off the index at open — no way to know yet
     * whether this is even a text document. So the answer is parked whole and re-decided the moment
     * the open finishes, with [closeDecision]'s same table.
     */
    fun parkClose(opened: Boolean): Boolean = !opened
}

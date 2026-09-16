package com.symmetricalpalmtree.notesproutsn.notebook

/**
 * Where the notebook screen goes when the notebook it opened has a **face** — a surface that is not
 * the paper and that the person meant to be looking at (arc 43 / K3). **Pure, no Android, no
 * `.soil`**, for the reason [DocumentTargetRules] is: `NotebookActivity` cannot be constructed in a
 * JVM test, and what it must get right is exactly the two tables below.
 *
 * Arc 19 wrote these tables once, for the document editor ([TextDocRouting], which is now this file
 * with its own words filled in). Arc 43 needs the same shape for the sketch face ([SketchRouting]),
 * and the shape really is the same — which is the argument for lifting it rather than porting it: a
 * second hand-written copy of a routing table is the `RattaNotebookView` sibling-copy trap one file
 * at a time, and the two faces would drift the first time one of them learned something.
 *
 * What is generic here, and what each face keeps for itself:
 *
 *  - **Generic: the shape of the decision.** A notebook with no face always loads its canvas. A
 *    notebook whose canvas has already been shown is ordinary for the rest of its life
 *    ([canvasShown] is a one-way latch for the incarnation). A showing whose end lands while the
 *    open is still on IO is parked whole and re-decided ([parkClose]).
 *  - **The face's own: which result code means "show me the pages".** The document editor says so
 *    through `IDocumentHost.closeNotebook`'s advisory; the sketch screen says so with an Activity
 *    result. Both are an `Int` this table compares against one the caller supplies
 *    ([showPagesMode]), so neither contract leaks in here.
 *
 * **The advisory's absence is a decision, not a gap.** `mode` is what the face said. **null = it
 * never said**: the back arrow, a process-death edge, a debug hook. That reads as to-library,
 * deliberately, because the two mistakes are not the same size — a notebook wrongly sealed is
 * reopened with one tap, while a canvas wrongly loaded cannot be un-loaded: the strokes are on the
 * paper and the seal is owed a cover.
 */
object FaceRouting {

    /** What [NotebookActivity]'s open does once the session is up. */
    enum class Open {
        /** The ordinary open: strokes, headings, links, template, paper — every notebook's path. */
        CANVAS,

        /** The lightweight setup, then launch the face (the fresh open of a notebook that has one). */
        EDITOR_LAUNCH,

        /** The lightweight setup and nothing else: a face is already on screen and the host has
         *  re-bound to it
         *  ([com.symmetricalpalmtree.notesproutsn.extension.DocumentEditorEntry.reconnect]).
         *  Launching a second one would bind twice over one showing. */
        EDITOR_RECONNECT,

        /** Seal and leave without ever touching the paper: the showing ended while this open was
         *  still on IO, and it ended toward the library. */
        SEAL_AND_LEAVE,
    }

    /** What the end of a showing means to the screen underneath it. */
    enum class Close {
        /** Every ordinary notebook, and every faced notebook whose canvas is already up: follow the
         *  face to the page it ended on (og's `navigateToPage(endedOn)`). */
        CATCH_UP,

        /** *Show pages* on a faced notebook that has never shown them: load the canvas now, on the
         *  page the face ended on. */
        LOAD_CANVAS,

        /** The faced notebook is done: cover, meta, seal, finish — the paper is never loaded. */
        SEAL_TO_LIBRARY,
    }

    /**
     * The open route. [parkedClose] is a showing whose end landed while the session was still
     * opening ([parkClose]) and has since been re-decided with [closeDecision] — it outranks
     * everything, because it is news about a showing that is already over: sealing wins, and
     * anything else is the canvas. Never a launch there; the face it would launch is the one the
     * user just left.
     *
     * After that the order is: a notebook with no face and a faced one with its canvas up are both
     * the ordinary open; a live showing is a reconnect; and only a fresh, canvas-less faced
     * notebook launches.
     */
    fun openDecision(
        opensIntoFace: Boolean,
        canvasShown: Boolean,
        reconnectPending: Boolean,
        parkedClose: Close? = null,
    ): Open = when {
        parkedClose == Close.SEAL_TO_LIBRARY -> Open.SEAL_AND_LEAVE
        parkedClose != null -> Open.CANVAS
        !opensIntoFace -> Open.CANVAS
        canvasShown -> Open.CANVAS
        reconnectPending -> Open.EDITOR_RECONNECT
        else -> Open.EDITOR_LAUNCH
    }

    /**
     * What the end of a showing means. [mode] is the face's advisory and [showPagesMode] the one
     * value of it that means "put the pages back up" — see the class doc for why null (and any
     * value this build does not know) reads as to-library, and why it is only ever asked about a
     * faced notebook whose canvas has never been shown.
     */
    fun closeDecision(
        opensIntoFace: Boolean,
        canvasShown: Boolean,
        mode: Int?,
        showPagesMode: Int,
    ): Close = when {
        !opensIntoFace -> Close.CATCH_UP
        canvasShown -> Close.CATCH_UP
        mode == showPagesMode -> Close.LOAD_CANVAS
        else -> Close.SEAL_TO_LIBRARY
    }

    /**
     * Whether the end of a showing must be **parked** for the open to answer rather than acted on
     * where it arrives. An `ActivityResult` callback runs before `onResume`, so it can land while a
     * recreated host's open is still on IO (arc 19's S2 trap): there is no session to seal, no
     * canvas to load from, and — since the kind is read off the index at open — no way to know yet
     * whether this notebook even has a face. So the answer is parked whole and re-decided the
     * moment the open finishes, with [closeDecision]'s same table.
     */
    fun parkClose(opened: Boolean): Boolean = !opened
}

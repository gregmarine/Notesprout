package com.symmetricalpalmtree.notesproutsn.notebook

import com.symmetricalpalmtree.notesproutsn.extension.DocumentContract

/**
 * Where the notebook screen goes when the notebook it opened is a **text document** (arc 19 / M8) —
 * the **document editor's face**, and since arc 43 / K3 a thin facade over the generic
 * [FaceRouting] table rather than a copy of it. The two tables are the same shape, and the sketch
 * face needed the same shape a fourth time; one table with the face's own answers passed in is what
 * keeps the two from drifting. The rules, the reasoning and the null-advisory decision all live in
 * [FaceRouting]'s KDoc — this file is what the editor's answer to each of them is.
 *
 * A text document is an ordinary notebook underneath — pages, ink, everything — flagged
 * ([com.symmetricalpalmtree.notesproutsn.data.index.NotebookFlags.TEXT_DOCUMENT]) so that it *opens*
 * into the editor rather than onto paper. Two things follow:
 *
 *  - **The canvas load is deferred, and often never happens.** Opening into the editor runs the
 *    lightweight setup only — the page id the hooks fall back to, and nothing else: no strokes, no
 *    headings, no links, no template, no `loadStrokes`. A close straight back to the library never
 *    touches the paper at all, which is the whole point (a text document should cost what a text
 *    document costs).
 *  - **Once the canvas IS shown, the notebook is ordinary for the rest of its life.** `canvasShown`
 *    is a one-way latch for the incarnation: the Document button reopens the editor through the
 *    normal seed flow, and the showing after that ends in the ordinary catch-up, never in a seal.
 *
 * The face's own answer to [FaceRouting]'s one open question is
 * [DocumentContract.CLOSE_SHOW_PAGES]: what the editor says through `IDocumentHost.closeNotebook`
 * when ✓ *Done* was tapped. Its other value ([DocumentContract.CLOSE_TO_LIBRARY], the header's
 * leave door) and **silence** both read as to-library.
 */
object TextDocRouting {

    /** [FaceRouting.Open] under the arc-19 names, so this door reads the same at every call site it
     *  has had since M8. The values **are** [FaceRouting]'s — not a parallel vocabulary. */
    object Open {
        val CANVAS = FaceRouting.Open.CANVAS
        val EDITOR_LAUNCH = FaceRouting.Open.EDITOR_LAUNCH
        val EDITOR_RECONNECT = FaceRouting.Open.EDITOR_RECONNECT
        val SEAL_AND_LEAVE = FaceRouting.Open.SEAL_AND_LEAVE
    }

    /** [FaceRouting.Close] under the arc-19 names — see [Open]. */
    object Close {
        val CATCH_UP = FaceRouting.Close.CATCH_UP
        val LOAD_CANVAS = FaceRouting.Close.LOAD_CANVAS
        val SEAL_TO_LIBRARY = FaceRouting.Close.SEAL_TO_LIBRARY
    }

    /** [FaceRouting.openDecision] for the editor's face. */
    fun openDecision(
        isTextDocument: Boolean,
        canvasShown: Boolean,
        reconnectPending: Boolean,
        parkedClose: FaceRouting.Close? = null,
    ): FaceRouting.Open = FaceRouting.openDecision(
        opensIntoFace = isTextDocument,
        canvasShown = canvasShown,
        reconnectPending = reconnectPending,
        parkedClose = parkedClose,
    )

    /** [FaceRouting.closeDecision] with the editor's show-pages advisory. */
    fun closeDecision(isTextDocument: Boolean, canvasShown: Boolean, mode: Int?): FaceRouting.Close =
        FaceRouting.closeDecision(
            opensIntoFace = isTextDocument,
            canvasShown = canvasShown,
            mode = mode,
            showPagesMode = DocumentContract.CLOSE_SHOW_PAGES,
        )

    /** [FaceRouting.parkClose] — nothing about the park is the face's. */
    fun parkClose(opened: Boolean): Boolean = FaceRouting.parkClose(opened)
}

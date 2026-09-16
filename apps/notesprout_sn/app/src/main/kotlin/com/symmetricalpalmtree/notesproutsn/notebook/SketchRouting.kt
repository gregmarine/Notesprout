package com.symmetricalpalmtree.notesproutsn.notebook

import com.symmetricalpalmtree.notesproutsn.extension.SketchContract

/**
 * Where the notebook screen goes when the notebook it opened is a **sketch notebook** (arc 43 / K3)
 * — the sketch face's answers to [FaceRouting]'s generic table, [TextDocRouting]'s sibling. The
 * rules and the reasoning live in [FaceRouting]'s KDoc; this file is what the sketch screen says.
 *
 * A sketch notebook is an ordinary notebook underneath — pages, ink, documents, everything —
 * flagged ([com.symmetricalpalmtree.notesproutsn.data.index.NotebookFlags.SKETCH]) so that it
 * *opens* into the sketch face rather than onto paper, and the same two things follow as for a text
 * document: the canvas load is deferred and often never happens, and once the canvas IS shown the
 * notebook is ordinary for the rest of its incarnation.
 *
 * **The one place the two faces differ is what "it never said" means, and it is why this is a
 * second facade rather than the same one with a different constant.** The editor's advisory is a
 * *field it sets on the way out*, so its absence is a genuine silence. The sketch screen's advisory
 * is the **Activity result code**, and there is no silence there: the framework always delivers one.
 * `RESULT_CANCELED` — 0, what Back gives — is the screen saying "I am done, and I did not ask for
 * the pages", which is a to-library answer and not an unanswered question. So the sketch face's
 * show-pages value is [SketchContract.RESULT_SKETCH_SHOW_PAGES] (1) and **everything else,
 * `RESULT_CANCELED` and null and an unknown code alike, seals to the library** — which is exactly
 * [FaceRouting]'s table with 1 as the show-pages mode, and is why no extra rule is needed here.
 *
 * (The K4 host wiring is not this phase. In K3 a sketch notebook still loads its canvas, because
 * nothing calls these functions yet.)
 */
object SketchRouting {

    /** [FaceRouting.Open] under this face's name — the values **are** [FaceRouting]'s. */
    object Open {
        val CANVAS = FaceRouting.Open.CANVAS
        val SKETCH_LAUNCH = FaceRouting.Open.EDITOR_LAUNCH
        val SKETCH_RECONNECT = FaceRouting.Open.EDITOR_RECONNECT
        val SEAL_AND_LEAVE = FaceRouting.Open.SEAL_AND_LEAVE
    }

    /** [FaceRouting.Close] under this face's name — see [Open]. */
    object Close {
        val CATCH_UP = FaceRouting.Close.CATCH_UP
        val LOAD_CANVAS = FaceRouting.Close.LOAD_CANVAS
        val SEAL_TO_LIBRARY = FaceRouting.Close.SEAL_TO_LIBRARY
    }

    /** [FaceRouting.openDecision] for the sketch face. */
    fun openDecision(
        isSketch: Boolean,
        canvasShown: Boolean,
        reconnectPending: Boolean,
        parkedClose: FaceRouting.Close? = null,
    ): FaceRouting.Open = FaceRouting.openDecision(
        opensIntoFace = isSketch,
        canvasShown = canvasShown,
        reconnectPending = reconnectPending,
        parkedClose = parkedClose,
    )

    /** [FaceRouting.closeDecision] with the sketch screen's result code as the advisory — see the
     *  class doc for why `RESULT_CANCELED` needs no rule of its own. */
    fun closeDecision(isSketch: Boolean, canvasShown: Boolean, mode: Int?): FaceRouting.Close =
        FaceRouting.closeDecision(
            opensIntoFace = isSketch,
            canvasShown = canvasShown,
            mode = mode,
            showPagesMode = SketchContract.RESULT_SKETCH_SHOW_PAGES,
        )

    /** [FaceRouting.parkClose] — nothing about the park is the face's. */
    fun parkClose(opened: Boolean): Boolean = FaceRouting.parkClose(opened)
}

package com.symmetricalpalmtree.notesprout.ext.scratchpad

import com.symmetricalpalmtree.gpaper.core.model.Stroke

/**
 * The scratch pad's undo action set (arc 6 / S1) for the shared `UndoRedoStack<ScratchUndo.Action>`
 * — pad-level history (survives page turns, dies with the screen). Replaying an action is
 * [ScratchDocument.revert] / [ScratchDocument.reapply]; this file only names the shapes.
 */
object ScratchUndo {

    sealed interface Action {
        /** The page the action happened on (for [Page]: the page the user is left on after it). */
        val pageId: String

        data class Drew(override val pageId: String, val stroke: Stroke) : Action
        /** An eraser pass or the selection toolbar's Delete — the strokes it took, in writing order. */
        data class Erased(override val pageId: String, val strokes: List<Stroke>) : Action
        data class Moved(override val pageId: String, val ids: List<String>, val dx: Float, val dy: Float) : Action

        /**
         * A page insert or delete — the page list + current page before and after, and the one page
         * that came or went ([changedId]) with its ink ([blob], captured when it was removed — null
         * while it exists in the store or never had ink). A lone page's delete **empties** it instead:
         * `before == after`, [changedId] = that page, [blob] = the ink taken.
         */
        data class Page(
            val before: List<String>,
            val beforeCurrent: String,
            val after: List<String>,
            val afterCurrent: String,
            val changedId: String,
            var blob: ByteArray?,
        ) : Action {
            override val pageId: String get() = afterCurrent
        }

        /** S2: ink that arrived from the notebook (`receiveInk`) — undo on the pad removes it. */
        data class Pasted(override val pageId: String, val strokes: List<Stroke>) : Action
    }
}

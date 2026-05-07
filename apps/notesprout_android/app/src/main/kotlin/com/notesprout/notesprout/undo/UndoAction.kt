package com.notesprout.notesprout.undo

import com.notesprout.notesprout.data.LayerModel
import com.notesprout.notesprout.data.PageModel
import com.notesprout.notesprout.data.StrokeModel

sealed class UndoAction {
    data class DrawStroke(val stroke: StrokeModel) : UndoAction()
    data class EraseStrokes(val strokes: List<StrokeModel>) : UndoAction()
    data class AddPage(val page: PageModel, val layer: LayerModel) : UndoAction()
    data class DeletePage(
        val page: PageModel,
        val layer: LayerModel,
        val strokesAliveAtDeletion: List<StrokeModel>
    ) : UndoAction()
}

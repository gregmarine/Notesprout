package com.symmetricalpalmtree.notesprout.notebook

import com.symmetricalpalmtree.gpaper.core.PaperView
import com.symmetricalpalmtree.gpaper.core.Tool
import com.symmetricalpalmtree.gpaper.core.model.Selection
import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.notesprout.core.Slog

/**
 * Ink pasted from the scratch pad (arc 6 / S2 — lifted out of `NotebookActivity` at its line cap in
 * arc 7 / L2): fresh rows + on the paper + one undoable [NotebookUndo.Action.Pasted], left selected
 * (host-initiated) **on the lasso tool** — a selection under the pen can't be dragged or dismissed;
 * the prior tool comes back when the selection is dismissed ([restoreTool]).
 */
class PasteFlow(
    private val paper: PaperView,
    /** `opened && !closing` on the host. */
    private val alive: () -> Boolean,
    private val session: () -> NotebookSession,
    private val liveStrokes: () -> LinkedHashMap<String, Stroke>,
    private val undo: UndoRedoStack<NotebookUndo.Action>,
    /** Keep the toolbar's tool highlight in step (`PaperToolbar.sync`). */
    private val syncTool: (Tool) -> Unit,
    /** Host-initiated selection presentation (sets the host's selection state + shows the toolbar). */
    private val presentSelection: (Selection) -> Unit,
    private val whenPenIdle: (() -> Unit) -> Unit,
) {
    /** The tool to go back to once the pasted selection is dismissed (null = already on the lasso). */
    private var toolBefore: Tool? = null

    /** Paste [strokes] on the current page. The caller runs it under the host's page-op lock. */
    suspend fun paste(strokes: List<Stroke>) {
        val pageId = session().currentPage.id
        session().store.insert(pageId, strokes)
        for (s in strokes) liveStrokes()[s.id] = s
        paper.addStrokes(strokes)
        undo.record(NotebookUndo.Action.Pasted(pageId, strokes))
        val bounds = strokes.map { it.bounds }.reduce { a, b -> a.union(b) }
        toolBefore = paper.tool.takeIf { it != Tool.LASSO }
        if (paper.tool != Tool.LASSO) { paper.tool = Tool.LASSO; syncTool(Tool.LASSO) }   // before setSelection — a tool change dismisses
        paper.setSelection(strokes.map { it.id }.toSet(), emptySet(), bounds)
        presentSelection(Selection(strokes.map { it.id }.toSet(), emptySet(), bounds))
        Slog.d(TAG) { "pasted ${strokes.size} strokes on $pageId" }
    }

    /** After the pasted selection goes away: back to the tool the user had — pen-idle (a pen tap-away
     *  dismisses at pen-down), and only if they haven't picked another tool meanwhile. */
    fun restoreTool() {
        val t = toolBefore ?: return
        toolBefore = null
        whenPenIdle { if (paper.tool == Tool.LASSO && alive()) { paper.tool = t; syncTool(t) } }
    }

    private companion object {
        const val TAG = "PasteFlow"
    }
}

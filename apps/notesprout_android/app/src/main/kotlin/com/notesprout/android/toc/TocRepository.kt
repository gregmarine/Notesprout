package com.notesprout.android.toc

import android.graphics.RectF
import com.notesprout.android.data.HeadingObject
import com.notesprout.android.data.HeadingStroke
import com.notesprout.android.data.NotebookDao
import com.notesprout.android.data.NotebookObject
import com.notesprout.android.data.parseBoundingBox

class TocRepository(private val dao: NotebookDao) {

    suspend fun buildTocEntries(): List<TocEntry> {
        val pages = dao.getAllPages()
        val pageIndexById = pages.mapIndexed { index, page -> page.id to index }.toMap()

        // layer.id → page.id, needed to resolve heading.parentId (layer) → page
        val layers = dao.getObjectsByType("layer")
        val pageIdByLayerId = layers.associate { it.id to it.parentId }

        val headingRows = dao.getAllHeadingObjects()

        // Group the topmost heading per page
        val topmostByPageId = mutableMapOf<String, HeadingStroke>()
        for (row in headingRows) {
            val pageId = pageIdByLayerId[row.parentId] ?: continue
            val box = row.parseBoundingBox() ?: continue
            val headingObject = try {
                HeadingObject.fromJson(row.data)
            } catch (e: Exception) {
                continue
            }
            val candidate = HeadingStroke(id = row.id, boundingBox = box, strokes = headingObject.strokes, recognizedText = headingObject.recognizedText, level = headingObject.level)
            val current = topmostByPageId[pageId]
            if (current == null || isHigher(candidate.boundingBox, current.boundingBox)) {
                topmostByPageId[pageId] = candidate
            }
        }

        return topmostByPageId.entries
            .mapNotNull { (pageId, heading) ->
                val pageIndex = pageIndexById[pageId] ?: return@mapNotNull null
                TocEntry(pageNumber = pageIndex + 1, pageIndex = pageIndex, pageId = pageId, heading = heading)
            }
            .sortedBy { it.pageNumber }
    }

    // Returns true if candidate is higher (smaller top), or equal top and further left.
    private fun isHigher(candidate: RectF, current: RectF): Boolean =
        candidate.top < current.top || (candidate.top == current.top && candidate.left < current.left)

    private fun NotebookObject.parseBoundingBox(): RectF? = parseBoundingBox(boundingBox)
}

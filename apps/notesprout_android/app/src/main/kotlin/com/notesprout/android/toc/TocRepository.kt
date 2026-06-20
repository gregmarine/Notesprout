package com.notesprout.android.toc

import android.graphics.RectF
import com.notesprout.android.data.HeadingObject
import com.notesprout.android.data.HeadingStroke
import com.notesprout.android.data.NotebookDao
import com.notesprout.android.data.NotebookObject
import com.notesprout.android.data.parseBoundingBox

class TocRepository(private val dao: NotebookDao) {

    /**
     * Builds the TOC as a tree of [TocNode]s in document order.
     *
     * - All non-deleted headings are resolved to their page via the layer→page map.
     * - Document order: sorted by pageIndex asc, then boundingBox.top asc, then left asc.
     * - Tree is built in a single pass using running [currentH1] and [currentH2] pointers:
     *   - level 1 → new top-level node; resets currentH2.
     *   - level 2 → appended as child of currentH1 (if present); orphans are skipped.
     *   - level 3 → appended as child of currentH2 (if present); orphans are skipped.
     * - currentH1/currentH2 persist across page boundaries.
     *
     * Returns the list of top-level (H1) nodes. H2/H3 nodes are reachable via [TocNode.children].
     */
    suspend fun buildTocTree(): List<TocNode> {
        val pages = dao.getAllPages()
        val pageIndexById = pages.mapIndexed { index, page -> page.id to index }.toMap()
        val pageNumberById = pages.mapIndexed { index, page -> page.id to (index + 1) }.toMap()

        // layer.id → page.id, needed to resolve heading.parentId (layer) → page
        val layers = dao.getObjectsByType("layer")
        val pageIdByLayerId = layers.associate { it.id to it.parentId }

        val headingRows = dao.getAllHeadingObjects()

        // Collect all valid headings with their resolved page info
        data class HeadingEntry(
            val pageId: String,
            val pageIndex: Int,
            val pageNumber: Int,
            val box: RectF,
            val stroke: HeadingStroke,
        )

        val entries = mutableListOf<HeadingEntry>()
        for (row in headingRows) {
            val pageId = pageIdByLayerId[row.parentId] ?: continue
            val box = row.parseBoundingBox() ?: continue
            val pageIndex = pageIndexById[pageId] ?: continue
            val pageNumber = pageNumberById[pageId] ?: continue
            val headingObject = try {
                HeadingObject.fromJson(row.data)
            } catch (e: Exception) {
                continue
            }
            val stroke = HeadingStroke(
                id = row.id,
                boundingBox = box,
                strokes = headingObject.strokes,
                recognizedText = headingObject.recognizedText,
                level = headingObject.level,
            )
            entries += HeadingEntry(pageId, pageIndex, pageNumber, box, stroke)
        }

        // Document order: pageIndex asc, top asc, left asc
        entries.sortWith(compareBy({ it.pageIndex }, { it.box.top }, { it.box.left }))

        // Single pass building the tree
        val roots = mutableListOf<TocNode>()
        var currentH1: TocNode? = null
        var currentH2: TocNode? = null

        for (entry in entries) {
            val level = entry.stroke.level.coerceIn(1, 3)
            val title = HeadingObject.stripHeadingPrefix(entry.stroke.recognizedText ?: "")
            val node = TocNode(
                pageNumber = entry.pageNumber,
                pageIndex = entry.pageIndex,
                pageId = entry.pageId,
                level = level,
                title = title,
                heading = entry.stroke,
            )
            when (level) {
                1 -> {
                    roots += node
                    currentH1 = node
                    currentH2 = null
                }
                2 -> {
                    val h1 = currentH1 ?: continue  // orphan — skip
                    h1.children += node
                    currentH2 = node
                }
                3 -> {
                    val h2 = currentH2 ?: continue  // orphan — skip
                    h2.children += node
                }
            }
        }

        return roots
    }

    private fun NotebookObject.parseBoundingBox(): RectF? = parseBoundingBox(boundingBox)
}

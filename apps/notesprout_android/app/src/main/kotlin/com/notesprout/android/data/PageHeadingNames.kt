package com.notesprout.android.data

import android.database.sqlite.SQLiteDatabase
import android.graphics.RectF

/**
 * Reads the "page name" for every page in an open `.soil` database, mirroring the TOC's
 * page-name rule (see [com.notesprout.android.toc.TocRepository]): a page's name is the
 * recognized text of its top-left-most heading.
 *
 * "Top-left-most" = the heading with the smallest boundingBox top; ties broken by the
 * smallest left. This is the same comparison TocRepository uses.
 *
 * Returns pageId → display name. A page only appears in the map when its top-left-most
 * heading has non-blank recognized text (the leading "# " markdown prefix is stripped).
 * Callers fall back to "Page N" for any page absent from the map (no heading at all, or
 * the top heading hasn't been handwriting-recognized yet).
 *
 * [db] must already be open and readable on the notebook `.soil` file. This function neither
 * opens nor closes [db].
 */
fun topHeadingNamesByPageId(db: SQLiteDatabase): Map<String, String> {
    // A heading's parentId is its layer; the layer's parentId is the page. Build layer → page.
    val pageIdByLayerId = HashMap<String, String>()
    db.rawQuery(
        "SELECT id, parentId FROM notebook WHERE type = 'layer' AND deletedAt IS NULL",
        null
    ).use { c ->
        while (c.moveToNext()) {
            val layerId = c.getString(0) ?: continue
            val pageId = c.getString(1) ?: continue
            pageIdByLayerId[layerId] = pageId
        }
    }

    // Track the top-left-most heading seen so far per page, with its recognized text.
    val topBoxByPage = HashMap<String, RectF>()
    val topTextByPage = HashMap<String, String?>()
    db.rawQuery(
        "SELECT parentId, boundingBox, data FROM notebook WHERE type = 'heading' AND deletedAt IS NULL",
        null
    ).use { c ->
        while (c.moveToNext()) {
            val layerId = c.getString(0) ?: continue
            val pageId = pageIdByLayerId[layerId] ?: continue
            val box = parseBoundingBox(c.getString(1) ?: continue) ?: continue
            val data = c.getString(2) ?: continue
            val text = try {
                HeadingObject.fromJson(data).recognizedText
            } catch (e: Exception) {
                null
            }
            val current = topBoxByPage[pageId]
            val isHigher = current == null ||
                box.top < current.top ||
                (box.top == current.top && box.left < current.left)
            if (isHigher) {
                topBoxByPage[pageId] = box
                topTextByPage[pageId] = text
            }
        }
    }

    val result = HashMap<String, String>()
    for ((pageId, text) in topTextByPage) {
        val name = text?.removePrefix("# ")?.trim()
        if (!name.isNullOrEmpty()) result[pageId] = name
    }
    return result
}

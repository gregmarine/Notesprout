package com.notesprout.android.data

import android.database.sqlite.SQLiteDatabase
import android.graphics.RectF

/**
 * Reads the "page name" for every page in an open `.soil` database using the authoritative
 * page-name rule (Architecture Decision #5):
 *
 * 1. The page's name is the recognized text of its **top-left-most H1** heading (level == 1).
 * 2. If there is no H1 on the page, the name is the recognized text of the **top-left-most
 *    heading of any level**.
 * 3. If the page has no heading with recognized text at all, the caller falls back to "Page N".
 *
 * "Top-left-most" = smallest `boundingBox.top`; ties broken by smallest `left`.
 *
 * Returns pageId → display name. A page only appears in the map when the selected heading
 * has non-blank recognized text (the heading markdown prefix is stripped via
 * [HeadingObject.stripHeadingPrefix]).
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

    // Per page, track two candidates:
    //   bestH1:    top-left-most heading with level == 1
    //   bestAny:   top-left-most heading of any level (fallback when no H1 exists)
    data class Candidate(val box: RectF, val text: String?)

    val bestH1ByPage = HashMap<String, Candidate>()
    val bestAnyByPage = HashMap<String, Candidate>()

    db.rawQuery(
        "SELECT parentId, boundingBox, data FROM notebook WHERE type = 'heading' AND deletedAt IS NULL",
        null
    ).use { c ->
        while (c.moveToNext()) {
            val layerId = c.getString(0) ?: continue
            val pageId = pageIdByLayerId[layerId] ?: continue
            val box = parseBoundingBox(c.getString(1) ?: continue) ?: continue
            val data = c.getString(2) ?: continue
            val headingObject = try {
                HeadingObject.fromJson(data)
            } catch (e: Exception) {
                continue
            }
            val text = headingObject.recognizedText
            val level = headingObject.level
            val candidate = Candidate(box, text)

            // Update bestAny — top-left-most of any level
            val currentAny = bestAnyByPage[pageId]
            if (currentAny == null || isHigher(box, currentAny.box)) {
                bestAnyByPage[pageId] = candidate
            }

            // Update bestH1 — top-left-most with level == 1 only
            if (level == 1) {
                val currentH1 = bestH1ByPage[pageId]
                if (currentH1 == null || isHigher(box, currentH1.box)) {
                    bestH1ByPage[pageId] = candidate
                }
            }
        }
    }

    val result = HashMap<String, String>()
    // Collect all page IDs seen in either map
    val pageIds = bestH1ByPage.keys + bestAnyByPage.keys
    for (pageId in pageIds) {
        // Prefer H1; fall back to any-level
        val chosen = bestH1ByPage[pageId] ?: bestAnyByPage[pageId] ?: continue
        val name = chosen.text
            ?.let { HeadingObject.stripHeadingPrefix(it) }
            ?.trim()
        if (!name.isNullOrEmpty()) result[pageId] = name
    }
    return result
}

/** Returns true if [candidate] is higher (smaller top), or same top and further left. */
private fun isHigher(candidate: RectF, current: RectF): Boolean =
    candidate.top < current.top || (candidate.top == current.top && candidate.left < current.left)

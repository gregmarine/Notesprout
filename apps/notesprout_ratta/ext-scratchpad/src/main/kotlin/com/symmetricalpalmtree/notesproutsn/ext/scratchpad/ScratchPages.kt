package com.symmetricalpalmtree.notesproutsn.ext.scratchpad

import com.symmetricalpalmtree.notesproutsn.notebook.PageMath

/**
 * The scratch pad's page-list arithmetic — pure, JVM-tested (arc 11 / J3). The store side (keys,
 * blobs) lives in [ScratchStore]; this is only the id-list math, on top of the shared [PageMath].
 */
object ScratchPages {

    /** [newId] inserted right after [currentId] (or at the end if it is not in the list). */
    fun insertAfter(ids: List<String>, currentId: String?, newId: String): List<String> {
        val i = ids.indexOf(currentId)
        val pos = if (i < 0) ids.size else PageMath.insertPosition(i, after = true)
        return ids.toMutableList().also { it.add(pos, newId) }
    }

    /** [newId] inserted right before [currentId] (or at the start if it is not in the list). */
    fun insertBefore(ids: List<String>, currentId: String?, newId: String): List<String> {
        val i = ids.indexOf(currentId)
        val pos = if (i < 0) 0 else PageMath.insertPosition(i, after = false)
        return ids.toMutableList().also { it.add(pos, newId) }
    }

    /**
     * Remove [id]; the landing page is the previous one (or the first). Never below one page: with a
     * single page the list is returned unchanged (the caller empties it instead) and the landing id
     * is that page.
     */
    fun delete(ids: List<String>, id: String): Pair<List<String>, String> {
        val i = ids.indexOf(id)
        if (i < 0) return ids to clampCurrent(ids, null)
        if (ids.size <= 1) return ids to id
        val rest = ids.toMutableList().also { it.removeAt(i) }
        return rest to rest[PageMath.indexAfterDelete(i, ids.size)]
    }

    /** [currentId] if it is in [ids], else the first id (ids must not be empty). */
    fun clampCurrent(ids: List<String>, currentId: String?): String {
        require(ids.isNotEmpty()) { "no pages" }
        return if (currentId != null && currentId in ids) currentId else ids[0]
    }
}

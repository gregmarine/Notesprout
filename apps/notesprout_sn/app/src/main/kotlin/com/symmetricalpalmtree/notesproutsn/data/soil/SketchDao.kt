package com.symmetricalpalmtree.notesproutsn.data.soil

import androidx.room.Dao
import androidx.room.Query

/**
 * The `sketch` row's own queries (arc 43 / K3) — [DocumentDao]'s shape, kept apart from [SoilDao]
 * for a reason of its own: **every read here is a decision about megabytes**. A page's sketch is
 * the one blob in this file that is measured in the millions of bytes rather than the thousands,
 * so the question a caller is really asking — is there one? how big? which pages have one? — must
 * be answerable without materialising the PNG, and putting the blob-free projections next to the
 * one read that *does* pull pixels is what makes choosing between them a choice rather than an
 * accident. Higher-level logic lives in [SketchRepository], the only writer.
 *
 * Adding this DAO does not move Room's identity hash: the entity set is unchanged, and the hash is
 * a statement about the schema, not about the interfaces that query it ([DocumentDao] says the
 * same, and for the same format contract — a `.soil` must stay openable by Paper).
 *
 * There is no write here. A sketch is written through [SoilDao.upsert] (the first save, a whole
 * row) and [SoilDao.setBlob] (every save after it, the pixels in place with `createdAt` kept), and
 * cleared through [SoilDao.softDelete] — three doors that already exist and already carry the
 * family's rules, so a fourth would only be a second place to get them wrong.
 */
@Dao
interface SketchDao {

    /**
     * Is there a sketch on [pageId], and how many bytes is it? — **blob-free**, and the read every
     * caller that is not about to draw should be asking. `length(blob)` is answered by SQLite out
     * of the row without materialising the PNG, exactly as [SoilDao.templateDigests] leans on for
     * the same reason one level down.
     *
     * At most one live row per page by construction ([SketchRepository] never inserts a second), so
     * `LIMIT 1` is a cap on damage rather than a choice between candidates.
     */
    @Query(
        """SELECT id, length(blob) AS blobLength FROM notebook
           WHERE type = 'sketch' AND parentId = :pageId AND deletedAt IS NULL LIMIT 1"""
    )
    suspend fun sketchDigest(pageId: String): SketchDigest?

    /**
     * [pageId]'s live sketch row **whole, pixels included** — the one read in the app that pulls a
     * page-sized PNG out of the file, and therefore the one to reach for only when something is
     * about to put it on a screen or into an export. Everything else wants [sketchDigest].
     *
     * Full entity deliberately: `blob` is the payload and the caller needs `id` beside it (a row
     * that fails the header guard is soft-deleted by id rather than overwritten — see
     * [SketchRepository.get]), so a projection would drop only the columns a sketch contractually
     * leaves null.
     */
    @Query(
        """SELECT * FROM notebook
           WHERE type = 'sketch' AND parentId = :pageId AND deletedAt IS NULL LIMIT 1"""
    )
    suspend fun sketchFor(pageId: String): SoilObjectEntity?

    /**
     * The ids of [rootId]'s live pages that carry a live sketch — asked **once per notebook**, ids
     * only, by the callers that need to know which pages have one before they walk them (K4's
     * cover, K7's export bundle). The alternative is [sketchDigest] per page, which is the same
     * answer at the cost of one index hit per page; this is one.
     *
     * Both halves are live on purpose: a sketch under a soft-deleted page is a row nobody looks up
     * (the purge takes it at close), and a soft-deleted sketch is an absent sketch — [sketchDigest]'s
     * own rule, carried over whole.
     *
     * Unordered, like [DocumentDao.pageDocumentsIn] and for the same reason: the caller already
     * holds the page rows in page order and uses this as a set.
     */
    @Query(
        """SELECT DISTINCT p.id FROM notebook p
           JOIN notebook s ON s.parentId = p.id
           WHERE p.type = 'page' AND p.parentId = :rootId AND p.deletedAt IS NULL
             AND s.type = 'sketch' AND s.deletedAt IS NULL"""
    )
    suspend fun pagesWithSketch(rootId: String): List<String>
}

/** A sketch row without its pixels — [SketchDao.sketchDigest]. [blobLength] is `length(blob)`:
 *  null for a NULL blob, 0 for an empty one, which both read as "no sketch here". */
data class SketchDigest(val id: String, val blobLength: Int?)

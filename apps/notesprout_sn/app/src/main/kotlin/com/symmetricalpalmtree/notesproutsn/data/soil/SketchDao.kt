package com.symmetricalpalmtree.notesproutsn.data.soil

import androidx.room.Dao
import androidx.room.Query

/**
 * The sketch rows' own queries (arc 43 / K3, per-layer since arc 45 / G2) — [DocumentDao]'s shape,
 * kept apart from [SoilDao] for a reason of its own: **every read here is a decision about
 * megabytes**. A page's sketch is the one blob in this file that is measured in the millions of
 * bytes rather than the thousands, so the question a caller is really asking — is there one? how
 * big? which pages have one? — must be answerable without materialising the image, and putting the
 * blob-free projections next to the one read that *does* pull pixels is what makes choosing between
 * them a choice rather than an accident. Higher-level logic lives in [SketchRepository], the only
 * writer.
 *
 * **A page carries two rasters since G2** — `sketch_graphite` and `sketch_ink` — so the two
 * single-page reads take the row type as a **bound parameter** rather than spelling it in the SQL:
 * one query each, asked once per layer, with [SketchRows.typeFor] the only place a layer becomes a
 * name. [pagesWithSketch] is the one that cannot be per-layer — the question it answers is "which
 * pages have *anything* drawn on them" — so it names both live types itself and answers pages with
 * either.
 *
 * **The dead arc-43 row name (`sketch`) is never named here at all** (decision 4: no legacy). A
 * type-parameterised read cannot reach it because nothing passes it, and `pagesWithSketch`'s `IN`
 * list holds only the two live names — so a leftover PNG row on a device that ran an older build is
 * invisible to every question this DAO answers.
 *
 * Adding this DAO does not move Room's identity hash: the entity set is unchanged, and the hash is
 * a statement about the schema, not about the interfaces that query it ([DocumentDao] says the
 * same, and for the same format contract — a `.soil` must stay openable by Paper). G2 changed only
 * the strings inside these queries, so it does not move it either.
 *
 * There is no write here. A raster is written through [SoilDao.upsert] (its first save, a whole
 * row) and [SoilDao.setBlob] (every save after it, the pixels in place with `createdAt` kept), and
 * cleared through [SoilDao.softDelete] — three doors that already exist and already carry the
 * family's rules, so a fourth would only be a second place to get them wrong.
 */
@Dao
interface SketchDao {

    /**
     * Is there a raster of [type] on [pageId], and how many bytes is it? — **blob-free**, and the
     * read every caller that is not about to draw should be asking. `length(blob)` is answered by
     * SQLite out of the row without materialising the image, exactly as [SoilDao.templateDigests]
     * leans on for the same reason one level down.
     *
     * [type] is one of the two live names, from [SketchRows.typeFor]. At most one live row per page
     * per type by construction ([SketchRepository] never inserts a second), so `LIMIT 1` is a cap on
     * damage rather than a choice between candidates.
     */
    @Query(
        """SELECT id, length(blob) AS blobLength FROM notebook
           WHERE type = :type AND parentId = :pageId AND deletedAt IS NULL LIMIT 1"""
    )
    suspend fun sketchDigest(pageId: String, type: String): SketchDigest?

    /**
     * [pageId]'s live raster row of [type] **whole, pixels included** — the one read in the app that
     * pulls a page-sized image out of the file, and therefore the one to reach for only when
     * something is about to put it on a screen or into an export. Everything else wants
     * [sketchDigest]. A page showing both rasters pays this twice, once per layer, which is the
     * honest price of two pictures.
     *
     * Full entity deliberately: `blob` is the payload and the caller needs `id` beside it (a row
     * that fails the header guard is soft-deleted by id rather than overwritten — see
     * [SketchRepository.get]), so a projection would drop only the columns a sketch contractually
     * leaves null.
     */
    @Query(
        """SELECT * FROM notebook
           WHERE type = :type AND parentId = :pageId AND deletedAt IS NULL LIMIT 1"""
    )
    suspend fun sketchFor(pageId: String, type: String): SoilObjectEntity?

    /**
     * The ids of [rootId]'s live pages that carry **either** live raster — asked **once per
     * notebook**, ids only, by the callers that need to know which pages have a sketch before they
     * walk them (K4's cover, K7's export bundle). The alternative is [sketchDigest] per page per
     * layer, which is the same answer at the cost of two index hits per page; this is one.
     *
     * "Either" is the whole point (G2): a page with only ink is a drawn page exactly as a page with
     * only graphite is, and the readers downstream flatten whatever is there. The two live names are
     * spelled in the SQL rather than bound because this is a set question, not a layer question —
     * and the dead `sketch` name is deliberately not among them.
     *
     * Both halves are live on purpose: a raster under a soft-deleted page is a row nobody looks up
     * (the purge takes it at close), and a soft-deleted raster is an absent one — [sketchDigest]'s
     * own rule, carried over whole.
     *
     * Unordered, like [DocumentDao.pageDocumentsIn] and for the same reason: the caller already
     * holds the page rows in page order and uses this as a set.
     */
    @Query(
        """SELECT DISTINCT p.id FROM notebook p
           JOIN notebook s ON s.parentId = p.id
           WHERE p.type = 'page' AND p.parentId = :rootId AND p.deletedAt IS NULL
             AND s.type IN ('sketch_graphite', 'sketch_ink') AND s.deletedAt IS NULL"""
    )
    suspend fun pagesWithSketch(rootId: String): List<String>
}

/** A sketch row without its pixels — [SketchDao.sketchDigest]. [blobLength] is `length(blob)`:
 *  null for a NULL blob, 0 for an empty one, which both read as "no raster here". */
data class SketchDigest(val id: String, val blobLength: Int?)

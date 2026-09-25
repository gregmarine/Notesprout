package com.symmetricalpalmtree.notesproutsn.data.soil

import androidx.room.Dao
import androidx.room.Query

/**
 * The guide rows' own queries (arc 51 "Guides" / J2) — [SketchDao]'s shape, and kept apart from it
 * for the same reason that one is kept apart from [SoilDao]: the reference image is measured in
 * megabytes, so "is there one, and what are its settings?" must be answerable without pulling the
 * picture, and the one read that does pull it sits beside the ones that do not.
 *
 * The two type names are spelled in the SQL rather than bound: each query is about exactly one of
 * them, and there is no layer number here to translate.
 *
 * Adding this DAO does not move Room's identity hash — the entity set is unchanged ([SketchDao]
 * says the same). There is no write here: [GuideRepository] writes through [SoilDao.upsert],
 * [SoilDao.setText], [SoilDao.setBlob] and [SoilDao.softDelete].
 */
@Dao
interface GuideDao {

    /** [pageId]'s live grid row — it has no blob, so the whole row is the cheap read. `LIMIT 1` is
     *  a cap on damage: [GuideRepository] never mints a second. */
    @Query(
        """SELECT * FROM notebook
           WHERE type = 'guide_grid' AND parentId = :pageId AND deletedAt IS NULL LIMIT 1"""
    )
    suspend fun gridFor(pageId: String): SoilObjectEntity?

    /** [pageId]'s live image row **without its pixels** — id, settings text and `length(blob)`:
     *  what a settings write and the save's "is this unchanged?" both ask first. */
    @Query(
        """SELECT id, text, length(blob) AS blobLength FROM notebook
           WHERE type = 'guide_image' AND parentId = :pageId AND deletedAt IS NULL LIMIT 1"""
    )
    suspend fun imageDigest(pageId: String): GuideImageDigest?

    /** [pageId]'s live image row **whole, pixels included** — only for a caller about to hand the
     *  picture to the face, or to prove a same-length save unchanged. */
    @Query(
        """SELECT * FROM notebook
           WHERE type = 'guide_image' AND parentId = :pageId AND deletedAt IS NULL LIMIT 1"""
    )
    suspend fun imageFor(pageId: String): SoilObjectEntity?
}

/** An image row without its pixels — [GuideDao.imageDigest]. [blobLength] null or 0 is no picture. */
data class GuideImageDigest(val id: String, val text: String?, val blobLength: Int?)

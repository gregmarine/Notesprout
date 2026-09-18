package com.symmetricalpalmtree.notesproutsn.data.soil

import com.symmetricalpalmtree.notesproutsn.notebook.FakeSoilDao

/**
 * [SketchDao] over [FakeSoilDao]'s in-memory `notebook` table — [FakeDocumentDao]'s recipe, so a
 * repository holding both DAOs behaves as it does against one file.
 *
 * Every method below is the SQL rewritten in Kotlin, deliberately: what the suites pin is the
 * *shape* of the three queries (which rows count as live, what `length(blob)` answers, how far the
 * page join reaches, and — since arc 45 / G2 — that the type is a bound parameter on two of them
 * and an `IN` of the two **live** names on the third) — a drift between this file and the `@Query`
 * strings is exactly the kind of thing the tests are meant to make visible in review.
 */
class FakeSketchDao(private val soil: FakeSoilDao) : SketchDao {

    private val rows get() = soil.rows

    override suspend fun sketchDigest(pageId: String, type: String): SketchDigest? =
        live(pageId, type)?.let { SketchDigest(it.id, it.blob?.size) }

    override suspend fun sketchFor(pageId: String, type: String): SoilObjectEntity? = live(pageId, type)

    override suspend fun pagesWithSketch(rootId: String): List<String> {
        val pageIds = rows.values
            .filter { it.type == SoilSchema.TYPE_PAGE && it.parentId == rootId && it.deletedAt == null }
            .map { it.id }
        return pageIds.filter { page -> LIVE_TYPES.any { live(page, it) != null } }
    }

    private fun live(pageId: String, type: String): SoilObjectEntity? = rows.values.firstOrNull {
        it.type == type && it.parentId == pageId && it.deletedAt == null
    }

    private companion object {
        /** The `IN` list of `pagesWithSketch`, verbatim — the dead arc-43 name is not in it. */
        val LIVE_TYPES = listOf(SoilSchema.TYPE_SKETCH_GRAPHITE, SoilSchema.TYPE_SKETCH_INK)
    }
}

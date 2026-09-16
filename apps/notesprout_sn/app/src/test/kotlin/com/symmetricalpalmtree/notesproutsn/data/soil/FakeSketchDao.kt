package com.symmetricalpalmtree.notesproutsn.data.soil

import com.symmetricalpalmtree.notesproutsn.notebook.FakeSoilDao

/**
 * [SketchDao] over [FakeSoilDao]'s in-memory `notebook` table — [FakeDocumentDao]'s recipe, so a
 * repository holding both DAOs behaves as it does against one file.
 *
 * Every method below is the SQL rewritten in Kotlin, deliberately: what the suites pin is the
 * *shape* of the three queries (which rows count as live, what `length(blob)` answers, how far the
 * page join reaches) — a drift between this file and the `@Query` strings is exactly the kind of
 * thing the tests are meant to make visible in review.
 */
class FakeSketchDao(private val soil: FakeSoilDao) : SketchDao {

    private val rows get() = soil.rows

    override suspend fun sketchDigest(pageId: String): SketchDigest? =
        live(pageId)?.let { SketchDigest(it.id, it.blob?.size) }

    override suspend fun sketchFor(pageId: String): SoilObjectEntity? = live(pageId)

    override suspend fun pagesWithSketch(rootId: String): List<String> {
        val pageIds = rows.values
            .filter { it.type == SoilSchema.TYPE_PAGE && it.parentId == rootId && it.deletedAt == null }
            .map { it.id }
        return pageIds.filter { live(it) != null }
    }

    private fun live(pageId: String): SoilObjectEntity? = rows.values.firstOrNull {
        it.type == SoilSchema.TYPE_SKETCH && it.parentId == pageId && it.deletedAt == null
    }
}

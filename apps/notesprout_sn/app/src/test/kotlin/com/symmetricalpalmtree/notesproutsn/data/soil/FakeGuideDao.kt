package com.symmetricalpalmtree.notesproutsn.data.soil

import com.symmetricalpalmtree.notesproutsn.notebook.FakeSoilDao

/**
 * [GuideDao] over [FakeSoilDao]'s in-memory `notebook` table — [FakeSketchDao]'s recipe: each
 * method is its `@Query` rewritten in Kotlin, so the repository suite runs against one table.
 */
class FakeGuideDao(private val soil: FakeSoilDao) : GuideDao {

    override suspend fun gridFor(pageId: String): SoilObjectEntity? = live(pageId, SoilSchema.TYPE_GUIDE_GRID)

    override suspend fun imageDigest(pageId: String): GuideImageDigest? =
        live(pageId, SoilSchema.TYPE_GUIDE_IMAGE)?.let { GuideImageDigest(it.id, it.text, it.blob?.size) }

    override suspend fun imageFor(pageId: String): SoilObjectEntity? = live(pageId, SoilSchema.TYPE_GUIDE_IMAGE)

    private fun live(pageId: String, type: String): SoilObjectEntity? = soil.rows.values.firstOrNull {
        it.type == type && it.parentId == pageId && it.deletedAt == null
    }
}

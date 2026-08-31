package com.symmetricalpalmtree.notesproutsn.data.soil

import com.symmetricalpalmtree.notesproutsn.notebook.FakeSoilDao

/**
 * [DocumentDao] over [FakeSoilDao]'s in-memory `notebook` table — the same rows the [SoilDao] half
 * sees, so a repository holding both DAOs behaves as it does against one file.
 *
 * Every method below is the SQL rewritten in Kotlin, deliberately: what these suites pin is the
 * *shape* of the four queries (which types count, how far down the tree they reach, what
 * `COALESCE` answers for an empty page) — a drift between this file and the `@Query` strings is
 * exactly the kind of thing the tests are meant to make visible in review.
 */
class FakeDocumentDao(private val soil: FakeSoilDao) : DocumentDao {

    private val rows get() = soil.rows

    /** The staleness whitelist — `document` is deliberately absent (see [DocumentDao]). */
    private val content = setOf(
        SoilSchema.TYPE_STROKE, SoilSchema.TYPE_HEADING, SoilSchema.TYPE_LINK,
    )

    override suspend fun documentFor(parentId: String): SoilObjectEntity? =
        rows.values.firstOrNull {
            it.type == SoilSchema.TYPE_DOCUMENT && it.parentId == parentId && it.deletedAt == null
        }

    /** Page documents only, live only — the notebook document is excluded by its parent being the
     *  root rather than a page, exactly as the query's subselect excludes it. */
    override suspend fun pageDocumentsIn(rootId: String): List<SoilObjectEntity> {
        val pageIds = rows.values
            .filter { it.type == SoilSchema.TYPE_PAGE && it.parentId == rootId }
            .map { it.id }
            .toSet()
        return rows.values.filter {
            it.type == SoilSchema.TYPE_DOCUMENT && it.deletedAt == null && it.parentId in pageIds
        }
    }

    // Both sweeps count soft-deleted rows — the queries carry no `deletedAt` clause at any level,
    // and neither does the fake: a soft-delete stamps `updatedAt` with the deletion time, which is
    // how an erase (or a deleted page) reaches the draft written from it.

    override suspend fun maxContentUpdatedAt(pageId: String): Long {
        val linkIds = linkIdsOn(setOf(pageId))
        return rows.values
            .filter { it.type in content && (it.parentId == pageId || it.parentId in linkIds) }
            .maxOfOrNull { it.updatedAt } ?: 0L
    }

    override suspend fun notebookMaxContentUpdatedAt(rootId: String): Long {
        val pageIds = rows.values
            .filter { it.type == SoilSchema.TYPE_PAGE && it.parentId == rootId }
            .map { it.id }
            .toSet()
        val linkIds = linkIdsOn(pageIds)
        return rows.values
            .filter {
                (it.parentId in pageIds && (it.type in content || it.type == SoilSchema.TYPE_DOCUMENT)) ||
                    (it.parentId in linkIds && it.type in content)
            }
            .maxOfOrNull { it.updatedAt } ?: 0L
    }

    override suspend fun setDocumentText(id: String, text: String, at: Long) {
        rows[id]?.let { rows[id] = it.copy(text = text, updatedAt = at) }
        soil.events += "setDocumentText:$id"
    }

    override suspend fun setDocumentDrafted(id: String, text: String, flags: Long, at: Long) {
        rows[id]?.let { rows[id] = it.copy(text = text, flags = flags, updatedAt = at) }
        soil.events += "setDocumentDrafted:$id"
    }

    private fun linkIdsOn(pageIds: Set<String>): Set<String> =
        rows.values
            .filter { it.type == SoilSchema.TYPE_LINK && it.parentId in pageIds }
            .map { it.id }
            .toSet()
}

package com.symmetricalpalmtree.notesproutsn.data.soil

import com.symmetricalpalmtree.notesproutsn.core.Slog
import java.util.UUID

/** A stored document: its markdown, and the page state it was last drafted from. */
data class Document(
    val id: String,
    val text: String,
    /** The row's `flags` — [DocumentDao.maxContentUpdatedAt] (or the notebook-wide sweep) at the
     *  last seed/refresh; null = authored by hand, never drafted from the page. */
    val srcUpdatedAt: Long?,
)

/**
 * The `document` row's read and write rules (arc 19 / M2) — og's `DocumentRepository`, adapted to
 * this family's shape: the watermark rides `flags` rather than a column of its own
 * ([SoilSchema.TYPE_DOCUMENT]), and the parent is a page id (a page document) or the notebook root
 * row's id (the notebook document — the merged final draft). One row shape, two parents, and the
 * rules below are identical for both.
 *
 * Deliberately narrow, and deliberately the **only** writer of a `document` row: nothing in the
 * recognition path may write one, which is what keeps an edited document safe from a later pass
 * over the same page. Pure `suspend` over the two DAOs (no Android, no `SoilDatabase`), so every
 * rule below is provable off-device.
 *
 * Two rules carry the feature:
 *  - **Blank means absent.** A blank document is never inserted, and saving blank text over a live
 *    row soft-deletes it. That is what lets "seed once" work with no separate "has been seeded"
 *    flag: no text ⇒ undrafted ⇒ offer the page's text again. Enforced on the read too ([get]
 *    answers null for a blank row), so a row a foreign writer left empty reads as absent.
 *  - **The watermark moves in [saveDrafted] and nowhere else.** A hand edit ([save]) rewrites the
 *    text and leaves `flags` exactly as it was — structural, not incidental: if a keystroke
 *    re-anchored the document to the page, "the page has changed since this draft" would answer
 *    false forever after the first one.
 *
 * `updatedAt` is sacred (the family rule): a save that would change nothing writes nothing, so
 * opening a document and closing it untouched cannot re-flag the notebook for backup.
 *
 * Document text is never logged — lengths and ids only, like every other user-content path here.
 */
class DocumentRepository(
    private val dao: DocumentDao,
    private val soil: SoilDao,
    /** Injected so the id remap is assertable off-device; production is `UUID.randomUUID`. */
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {

    /** [parentId]'s document, or null when it has none — **or holds only blank text** (see class doc). */
    suspend fun get(parentId: String): Document? {
        val row = dao.documentFor(parentId) ?: return null
        val text = row.text.orEmpty()
        if (text.isBlank()) return null
        return Document(id = row.id, text = text, srcUpdatedAt = row.flags)
    }

    /**
     * A **hand edit** — the editor's autosave and its Done. The watermark is untouched: whatever
     * page state this document was drafted from, it is still that state it was drafted from.
     *
     * Blank [text] deletes the live row (soft, like every delete in the family) and writes nothing
     * else; blank text with no row at all writes nothing at all, so an untouched editor leaves no
     * trace and the parent stays eligible to be seeded later. Unchanged text is dropped — the
     * redundant first save the editor makes costs one read, not an `updatedAt` bump.
     */
    suspend fun save(parentId: String, text: String, now: Long = System.currentTimeMillis()) {
        val row = dao.documentFor(parentId)
        if (text.isBlank()) {
            row ?: return
            soil.softDelete(listOf(row.id), now)
            Slog.d(TAG) { "blank save cleared the document of $parentId" }
            return
        }
        if (row == null) {
            insert(parentId, text, srcUpdatedAt = null, now = now)
            return
        }
        if (row.text == text) return
        dao.setDocumentText(row.id, text, now)
        Slog.d(TAG) { "saved ${text.length} chars for $parentId (watermark kept)" }
    }

    /**
     * A **seed or a "bring in" refresh** — the one place the watermark moves. [srcUpdatedAt] is the
     * page (or notebook) content maximum read *before* the draft was built, so a change made while
     * it was building reads as "changed since", never as fresh.
     *
     * Blank handling is [save]'s. Unchanged text **and** an unchanged watermark write nothing;
     * unchanged text with a moved watermark still writes, because re-anchoring is the whole act
     * even when the draft came out identical.
     */
    suspend fun saveDrafted(
        parentId: String,
        text: String,
        srcUpdatedAt: Long,
        now: Long = System.currentTimeMillis(),
    ) {
        val row = dao.documentFor(parentId)
        if (text.isBlank()) {
            row ?: return
            soil.softDelete(listOf(row.id), now)
            Slog.d(TAG) { "blank draft cleared the document of $parentId" }
            return
        }
        if (row == null) {
            insert(parentId, text, srcUpdatedAt = srcUpdatedAt, now = now)
            return
        }
        if (row.text == text && row.flags == srcUpdatedAt) return
        dao.setDocumentDrafted(row.id, text, srcUpdatedAt, now)
        Slog.d(TAG) { "drafted ${text.length} chars for $parentId (watermark $srcUpdatedAt)" }
    }

    /** The insert both writers share: one row per parent, nothing to order, everything else null. */
    private suspend fun insert(parentId: String, text: String, srcUpdatedAt: Long?, now: Long) {
        soil.upsert(
            SoilObjectEntity(
                id = newId(),
                parentId = parentId,
                type = SoilSchema.TYPE_DOCUMENT,
                order = 0,
                createdAt = now,
                updatedAt = now,
                text = text,
                flags = srcUpdatedAt,
            )
        )
        Slog.d(TAG) { "new document for $parentId (${text.length} chars, watermark $srcUpdatedAt)" }
    }

    private companion object {
        const val TAG = "DocumentRepo"
    }
}

package com.symmetricalpalmtree.notesproutsn.data.soil

import com.symmetricalpalmtree.notesproutsn.data.soil.SoilCompactor.Row
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The purge's pure half (arc 17 / K1). The stakes: a purge that reaches a template row breaks
 * arc 13's reuse-before-mint, a cascade that starts from "parent missing" turns one corrupt
 * `parentId` into destroyed content, and a sweep that misses the cascade leaves unreachable rows
 * paying rent in an encrypted file forever.
 */
class SoilCompactorTest {

    private val nb = "notebook-id"

    private fun root() = Row(nb, SoilSchema.ROOT_PARENT, SoilSchema.TYPE_NOTEBOOK, deleted = false)
    private fun page(id: String, deleted: Boolean = false) = Row(id, nb, SoilSchema.TYPE_PAGE, deleted)
    private fun stroke(id: String, parent: String, deleted: Boolean = false) =
        Row(id, parent, SoilSchema.TYPE_STROKE, deleted)
    private fun template(id: String, deleted: Boolean = false) = Row(id, nb, SoilSchema.TYPE_TEMPLATE, deleted)

    @Test
    fun aCleanFilePurgesNothing() {
        val rows = listOf(root(), template("t1"), page("p1"), stroke("s1", "p1"))
        assertTrue(SoilCompactor.purgeIds(rows).isEmpty())
    }

    @Test
    fun softDeletedRowsGo() {
        val rows = listOf(root(), page("p1"), stroke("s1", "p1"), stroke("s2", "p1", deleted = true))
        assertEquals(setOf("s2"), SoilCompactor.purgeIds(rows))
    }

    @Test
    fun aDeletedPageTakesItsAliveDescendants() {
        // Page delete soft-deletes descendants too since arc 4, but older or foreign rows may hold
        // alive children under a dead page — the cascade is what makes the purge complete.
        val rows = listOf(
            root(), template("t1"),
            page("p1", deleted = true),
            stroke("s1", "p1"),                       // alive under the dead page
            Row("l1", "p1", SoilSchema.TYPE_LINK, deleted = false),
            stroke("s2", "l1"),                       // wrapped grandchild, alive
            Row("h1", "l1", SoilSchema.TYPE_HEADING, deleted = true),
            page("p2"), stroke("s3", "p2"),
        )
        assertEquals(setOf("p1", "s1", "l1", "s2", "h1"), SoilCompactor.purgeIds(rows))
    }

    @Test
    fun templateRowsAreExemptEvenSoftDeleted() {
        // Nothing ever soft-deletes a template (arc 13) — a foreign file that did must still not
        // lose paper an alive page may point at.
        val rows = listOf(root(), template("t1", deleted = true), page("p1"))
        assertTrue(SoilCompactor.purgeIds(rows).isEmpty())
    }

    @Test
    fun templateRowsAreExemptFromTheCascade() {
        // Defensive: a template parented somewhere odd must not ride a cascade out of the file.
        val rows = listOf(
            root(),
            page("p1", deleted = true),
            Row("t1", "p1", SoilSchema.TYPE_TEMPLATE, deleted = false),
        )
        assertEquals(setOf("p1"), SoilCompactor.purgeIds(rows))
    }

    @Test
    fun aDanglingParentIsNotAnOrphan() {
        // The parent was never in the file — evidence of damage, and never-delete-on-corruption
        // applies: the cascade starts only from rows this purge deletes.
        val rows = listOf(root(), page("p1"), stroke("s1", "missing-parent"))
        assertTrue(SoilCompactor.purgeIds(rows).isEmpty())
    }

    @Test
    fun theCascadeSurvivesACycle() {
        val rows = listOf(
            root(),
            Row("a", "b", SoilSchema.TYPE_STROKE, deleted = true),
            Row("b", "a", SoilSchema.TYPE_STROKE, deleted = false),
        )
        assertEquals(setOf("a", "b"), SoilCompactor.purgeIds(rows))
    }

    @Test
    fun aLiveDocumentOnALivePageSurvives() {
        // A document is a product of the page, not content on it — but it *is* the user's writing,
        // and the purge only ever removes what is soft-deleted or unreachable (arc 19 / M2).
        val rows = listOf(
            root(), page("p1"), stroke("s1", "p1"),
            Row("doc-p1", "p1", SoilSchema.TYPE_DOCUMENT, deleted = false),
            Row("doc-nb", nb, SoilSchema.TYPE_DOCUMENT, deleted = false),   // the notebook document
        )
        assertTrue(SoilCompactor.purgeIds(rows).isEmpty())
    }

    @Test
    fun aPurgedPageTakesItsDocumentWithIt() {
        // The cascade is untyped on purpose: a document under a dead page is unreachable exactly
        // like the page's ink, and it goes the same way.
        val rows = listOf(
            root(), page("p1", deleted = true),
            Row("doc-p1", "p1", SoilSchema.TYPE_DOCUMENT, deleted = false),
            page("p2"), Row("doc-p2", "p2", SoilSchema.TYPE_DOCUMENT, deleted = false),
        )
        assertEquals(setOf("p1", "doc-p1"), SoilCompactor.purgeIds(rows))
    }

    @Test
    fun emptyInputIsEmptyOutput() {
        assertTrue(SoilCompactor.purgeIds(emptyList()).isEmpty())
    }

    // ── Sidecar hygiene decision table ───────────────────────────────────────

    @Test
    fun sidecarDecisionTable() {
        // No WAL at all → a stray -shm describes nothing; sweep.
        assertTrue(SoilCompactor.sidecarsRemovable(walExists = false, walLength = 0L))
        // Zero-length WAL → fully checkpointed; sweep the pair.
        assertTrue(SoilCompactor.sidecarsRemovable(walExists = true, walLength = 0L))
        // Non-empty WAL → a failed checkpoint's live data; never.
        assertFalse(SoilCompactor.sidecarsRemovable(walExists = true, walLength = 1L))
        assertFalse(SoilCompactor.sidecarsRemovable(walExists = true, walLength = 4096L))
    }
}

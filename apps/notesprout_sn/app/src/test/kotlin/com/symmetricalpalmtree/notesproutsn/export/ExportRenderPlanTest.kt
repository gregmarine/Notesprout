package com.symmetricalpalmtree.notesproutsn.export

import com.symmetricalpalmtree.notesproutsn.data.soil.SoilObjectEntity
import com.symmetricalpalmtree.notesproutsn.data.soil.SoilSchema
import com.symmetricalpalmtree.notesproutsn.extension.PageBundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the pure half of the page render (arc 18 / D1): how the bake reads the page rows. The
 * drawing itself is Android and is eye-checked on the device; these are the decisions that would
 * silently produce a wrong document — a page at the wrong size, pages in the wrong order, blank
 * paper read as missing paper, or a page quietly dropped.
 *
 * Since arc 43 / K7 it also pins the **bundle's shape**: a sketched page exports as two pages (its
 * ink, then its sketch), so where each page lands in the bundle is no longer its own number, and
 * the endnote links and the container's cap are counted against the interleaved list.
 */
class ExportRenderPlanTest {

    private var next = 0L

    private fun page(
        id: String,
        order: Int,
        width: Float? = 1404f,
        height: Float? = 1872f,
        refId: String? = null,
    ) = SoilObjectEntity(
        id = id, parentId = "nb", type = SoilSchema.TYPE_PAGE, order = order,
        createdAt = ++next, updatedAt = next, width = width, height = height, refId = refId,
    )

    /** The rows as the whole notebook's scope hands them over: numbered 1.. in the DAO's order. */
    private fun scoped(vararg rows: SoilObjectEntity) =
        rows.mapIndexed { index, row -> ExportScope.ScopedPage(row, index + 1) }

    @Test
    fun keepsTheRowOrderItWasGiven() {
        // The DAO sorts by "order"; the plan must not re-sort, re-group or drop.
        val plan = ExportRender.plan(
            scoped(page("p1", 0), page("p2", 1), page("p3", 2)))!!
        assertEquals(listOf("p1", "p2", "p3"), plan.map { it.id })
    }

    @Test
    fun takesEachPagesOwnSize() {
        val plan = ExportRender.plan(
            scoped(page("p1", 0), page("p2", 1, width = 1920f, height = 2560f))
        )!!
        assertEquals(1404, plan[0].widthPx)
        assertEquals(1872, plan[0].heightPx)
        // A page authored on another panel exports at the edge it was written at.
        assertEquals(1920, plan[1].widthPx)
        assertEquals(2560, plan[1].heightPx)
    }

    @Test
    fun blankPaperIsTheEmptyToken() {
        // No refId is what blank IS in the format — the bake draws white, it does not go looking.
        val plan = ExportRender.plan(
            scoped(page("p1", 0), page("p2", 1, refId = "t1")))!!
        assertEquals("", plan[0].templateId)
        assertEquals("t1", plan[1].templateId)
    }

    @Test
    fun refusesWholesaleWhenAPageHasNoSize() {
        // Not a skip: a document silently missing a page is worse than one that refuses out loud.
        assertNull(ExportRender.plan(
            scoped(page("p1", 0), page("p2", 1, width = 0f))))
        assertNull(ExportRender.plan(
            scoped(page("p1", 0, height = null))))
        assertNull(ExportRender.plan(
            scoped(page("p1", 0, width = -3f))))
    }

    @Test
    fun noPagesIsAnEmptyPlan() {
        // Empty is not the same refusal as unsized — the caller answers it with EMPTY.
        assertEquals(emptyList<ExportRender.PageBake>(), ExportRender.plan(emptyList()))
    }

    // ── Sketch pages (arc 43 / K7, decision 5) ───────────────────────────────

    @Test
    fun plansASketchPageFromTheSetItWasGiven() {
        // The set is one blob-free query of the pages carrying a sketch; the plan only marks.
        val plan = ExportRender.plan(
            scoped(page("p1", 0), page("p2", 1), page("p3", 2)), setOf("p2", "gone"))!!
        assertEquals(listOf(false, true, false), plan.map { it.hasSketch })
        // Nothing else moves: a sketch is a page's property, not a page.
        assertEquals(listOf("p1", "p2", "p3"), plan.map { it.id })
        assertEquals(listOf(1, 2, 3), plan.map { it.number })
        // And with no set at all nothing is marked — every caller that has none says so by omission.
        assertEquals(
            listOf(false, false, false),
            ExportRender.plan(scoped(page("p1", 0), page("p2", 1), page("p3", 2)))!!.map { it.hasSketch },
        )
    }

    @Test
    fun aNotebookWithNoSketchesBundlesExactlyItsPages() {
        val plan = ExportRender.plan(scoped(page("p1", 0), page("p2", 1), page("p3", 2)))!!
        val bundle = ExportRender.bundlePages(plan)
        assertEquals(3, bundle.size)
        assertEquals(listOf(0, 1, 2), bundle.map { it.index })
        assertEquals(listOf(false, false, false), bundle.map { it.sketch })
        // Every page is where the notebook says it is.
        assertEquals(listOf(1, 2, 3), ExportRender.bundlePositions(plan))
    }

    @Test
    fun everyPageSketchedIsTwoPagesEach() {
        val plan = ExportRender.plan(
            scoped(page("p1", 0), page("p2", 1)), setOf("p1", "p2"))!!
        val bundle = ExportRender.bundlePages(plan)
        // Ink then its own sketch, page by page — never all the ink and then all the sketches.
        assertEquals(listOf(0, 0, 1, 1), bundle.map { it.index })
        assertEquals(listOf(false, true, false, true), bundle.map { it.sketch })
        assertEquals(4, bundle.size)
        assertEquals(listOf(1, 3), ExportRender.bundlePositions(plan))
    }

    @Test
    fun aMixedNotebookInterleavesAndShiftsWhatFollows() {
        val plan = ExportRender.plan(
            scoped(page("p1", 0), page("p2", 1), page("p3", 2), page("p4", 3)),
            setOf("p2", "p4"),
        )!!
        val bundle = ExportRender.bundlePages(plan)
        assertEquals(6, bundle.size)
        assertEquals(listOf(0, 1, 1, 2, 3, 3), bundle.map { it.index })
        assertEquals(listOf(false, false, true, false, false, true), bundle.map { it.sketch })
        // p3 is the notebook's third page and the bundle's fourth: a link that addressed the
        // notebook's numbering would land on p2's sketch.
        assertEquals(listOf(1, 2, 4, 5), ExportRender.bundlePositions(plan))
        // The count the bundle's header declares is the interleaved one.
        assertEquals(plan.size + plan.count { it.hasSketch }, bundle.size)
    }

    @Test
    fun theContainersCapCountsSketchPages() {
        // The cap is the container's and is measured against what the bundle holds, never against
        // the notebook's pages: just over half the cap, sketched throughout, is already past it.
        val rows = (0..PageBundle.MAX_PAGES / 2).map { page("p$it", it) }
        val plain = ExportRender.plan(scoped(*rows.toTypedArray()))!!
        assertTrue(ExportRender.bundlePages(plain).size <= PageBundle.MAX_PAGES)
        val sketched = ExportRender.plan(scoped(*rows.toTypedArray()), rows.map { it.id }.toSet())!!
        assertEquals(rows.size, sketched.size)
        assertTrue(ExportRender.bundlePages(sketched).size > PageBundle.MAX_PAGES)
    }
}

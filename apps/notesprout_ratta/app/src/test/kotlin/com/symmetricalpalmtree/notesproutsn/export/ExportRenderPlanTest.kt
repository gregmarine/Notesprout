package com.symmetricalpalmtree.notesproutsn.export

import com.symmetricalpalmtree.notesproutsn.data.soil.SoilObjectEntity
import com.symmetricalpalmtree.notesproutsn.data.soil.SoilSchema
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the pure half of the page render (arc 18 / D1): how the bake reads the page rows. The
 * drawing itself is Android and is eye-checked on the device; these are the decisions that would
 * silently produce a wrong document — a page at the wrong size, pages in the wrong order, blank
 * paper read as missing paper, or a page quietly dropped.
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

    @Test
    fun keepsTheRowOrderItWasGiven() {
        // The DAO sorts by "order"; the plan must not re-sort, re-group or drop.
        val plan = ExportRender.plan(listOf(page("p1", 0), page("p2", 1), page("p3", 2)))!!
        assertEquals(listOf("p1", "p2", "p3"), plan.map { it.id })
    }

    @Test
    fun takesEachPagesOwnSize() {
        val plan = ExportRender.plan(
            listOf(page("p1", 0), page("p2", 1, width = 1920f, height = 2560f))
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
        val plan = ExportRender.plan(listOf(page("p1", 0), page("p2", 1, refId = "t1")))!!
        assertEquals("", plan[0].templateId)
        assertEquals("t1", plan[1].templateId)
    }

    @Test
    fun refusesWholesaleWhenAPageHasNoSize() {
        // Not a skip: a document silently missing a page is worse than one that refuses out loud.
        assertNull(ExportRender.plan(listOf(page("p1", 0), page("p2", 1, width = 0f))))
        assertNull(ExportRender.plan(listOf(page("p1", 0, height = null))))
        assertNull(ExportRender.plan(listOf(page("p1", 0, width = -3f))))
    }

    @Test
    fun noPagesIsAnEmptyPlan() {
        // Empty is not the same refusal as unsized — the caller answers it with EMPTY.
        assertEquals(emptyList<ExportRender.PageBake>(), ExportRender.plan(emptyList()))
    }
}

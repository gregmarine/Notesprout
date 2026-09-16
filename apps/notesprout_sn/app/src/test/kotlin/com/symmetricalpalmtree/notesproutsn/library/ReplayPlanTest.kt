package com.symmetricalpalmtree.notesproutsn.library

import com.symmetricalpalmtree.notesproutsn.data.prefs.Surface
import com.symmetricalpalmtree.notesproutsn.data.prefs.SurfaceEntry
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [ReplayPlan.of]'s shape rules (arc 32 / RS1): a notebook at the bottom carries the chain above
 * it up to (not including) the next NOTEBOOK entry, a library-level stack reports only its top and
 * whether the calendar's pad door latch applies, and anything that cannot be stood on is
 * [ReplayPlan.Nothing].
 *
 * Plus (arc 32 / RS2) [ReplayPlan.legalAbove]'s normalization — the five shapes SN can actually
 * put back, and the truncation of everything else to its longest legal prefix — and
 * [ReplayPlan.decodeAbove], the `EXTRA_RESUME_ABOVE` read that treats a surface name as untrusted.
 */
class ReplayPlanTest {

    private fun entry(surface: Surface, notebookId: String? = null, viaLink: Boolean = false) =
        SurfaceEntry(token = "t-${surface.name}-$notebookId-$viaLink", surface = surface, notebookId = notebookId, viaLink = viaLink)

    @Test
    fun `an empty stack answers Nothing`() {
        assertEquals(ReplayPlan.Nothing, ReplayPlan.of(emptyList()))
    }

    @Test
    fun `a lone notebook entry answers Notebook with no surfaces above`() {
        val stack = listOf(entry(Surface.NOTEBOOK, notebookId = "nb-1", viaLink = true))
        assertEquals(ReplayPlan.Notebook("nb-1", true, emptyList()), ReplayPlan.of(stack))
    }

    @Test
    fun `a notebook with a calendar above it carries the calendar`() {
        val stack = listOf(entry(Surface.NOTEBOOK, notebookId = "nb-1"), entry(Surface.CALENDAR))
        assertEquals(ReplayPlan.Notebook("nb-1", false, listOf(Surface.CALENDAR)), ReplayPlan.of(stack))
    }

    @Test
    fun `a notebook with calendar then scratch pad above it carries both in order`() {
        val stack = listOf(
            entry(Surface.NOTEBOOK, notebookId = "nb-1"),
            entry(Surface.CALENDAR),
            entry(Surface.SCRATCH_PAD),
        )
        assertEquals(
            ReplayPlan.Notebook("nb-1", false, listOf(Surface.CALENDAR, Surface.SCRATCH_PAD)),
            ReplayPlan.of(stack),
        )
    }

    @Test
    fun `a notebook with a document editor above it carries the editor`() {
        val stack = listOf(entry(Surface.NOTEBOOK, notebookId = "nb-1"), entry(Surface.DOCUMENT_EDITOR))
        assertEquals(ReplayPlan.Notebook("nb-1", false, listOf(Surface.DOCUMENT_EDITOR)), ReplayPlan.of(stack))
    }

    @Test
    fun `a second notebook ends the chain, leaving nothing above the first`() {
        val stack = listOf(entry(Surface.NOTEBOOK, notebookId = "nb-a"), entry(Surface.NOTEBOOK, notebookId = "nb-b"))
        assertEquals(ReplayPlan.Notebook("nb-a", false, emptyList()), ReplayPlan.of(stack))
    }

    @Test
    fun `above stops at the first notebook entry even with more surfaces past it`() {
        val stack = listOf(
            entry(Surface.NOTEBOOK, notebookId = "nb-a"),
            entry(Surface.CALENDAR),
            entry(Surface.NOTEBOOK, notebookId = "nb-b"),
            entry(Surface.SCRATCH_PAD),
        )
        assertEquals(ReplayPlan.Notebook("nb-a", false, listOf(Surface.CALENDAR)), ReplayPlan.of(stack))
    }

    @Test
    fun `a bottom notebook with a null notebookId answers Nothing`() {
        val stack = listOf(entry(Surface.NOTEBOOK, notebookId = null))
        assertEquals(ReplayPlan.Nothing, ReplayPlan.of(stack))
    }

    @Test
    fun `a lone calendar entry answers LibraryLevel with no calendar beneath`() {
        val stack = listOf(entry(Surface.CALENDAR))
        assertEquals(ReplayPlan.LibraryLevel(Surface.CALENDAR, false), ReplayPlan.of(stack))
    }

    @Test
    fun `a lone scratch pad entry answers LibraryLevel with no calendar beneath`() {
        val stack = listOf(entry(Surface.SCRATCH_PAD))
        assertEquals(ReplayPlan.LibraryLevel(Surface.SCRATCH_PAD, false), ReplayPlan.of(stack))
    }

    @Test
    fun `a calendar beneath a scratch pad sets the calendar-beneath latch`() {
        val stack = listOf(entry(Surface.CALENDAR), entry(Surface.SCRATCH_PAD))
        assertEquals(ReplayPlan.LibraryLevel(Surface.SCRATCH_PAD, true), ReplayPlan.of(stack))
    }

    // ── the Bible (arc 37 / B0): a one-screen chain wherever it stands ──────

    @Test
    fun `a lone bible entry answers LibraryLevel with no calendar beneath`() {
        assertEquals(ReplayPlan.LibraryLevel(Surface.BIBLE, false), ReplayPlan.of(listOf(entry(Surface.BIBLE))))
    }

    @Test
    fun `a notebook with a bible above it carries the bible`() {
        val stack = listOf(entry(Surface.NOTEBOOK, notebookId = "nb-1"), entry(Surface.BIBLE))
        assertEquals(ReplayPlan.Notebook("nb-1", false, listOf(Surface.BIBLE)), ReplayPlan.of(stack))
    }

    @Test
    fun `a bible over a calendar at library level reopens the bible and never latches`() {
        val stack = listOf(entry(Surface.CALENDAR), entry(Surface.BIBLE))
        assertEquals(ReplayPlan.LibraryLevel(Surface.BIBLE, false), ReplayPlan.of(stack))
    }

    @Test
    fun `legalAbove leaves a lone bible unchanged and truncates anything after it`() {
        assertEquals(listOf(Surface.BIBLE), ReplayPlan.legalAbove(listOf(Surface.BIBLE)))
        assertEquals(listOf(Surface.BIBLE), ReplayPlan.legalAbove(listOf(Surface.BIBLE, Surface.CALENDAR)))
        assertEquals(listOf(Surface.CALENDAR), ReplayPlan.legalAbove(listOf(Surface.CALENDAR, Surface.BIBLE)))
    }

    @Test
    fun `decodeAbove reads a bible name`() {
        assertEquals(listOf(Surface.BIBLE), ReplayPlan.decodeAbove(listOf("BIBLE")))
    }

    /** Arc 43 / K4: the sketch face is one screen like the Bible's — it has no door to another
     *  extension, so it is only ever the last surface and never latches anything behind it. */
    @Test
    fun `the sketch face is a one-screen chain above a notebook`() {
        val stack = listOf(entry(Surface.NOTEBOOK, notebookId = "nb-1"), entry(Surface.SKETCH))
        assertEquals(ReplayPlan.Notebook("nb-1", false, listOf(Surface.SKETCH)), ReplayPlan.of(stack))
        assertEquals(listOf(Surface.SKETCH), ReplayPlan.legalAbove(listOf(Surface.SKETCH)))
        assertEquals(listOf(Surface.SKETCH), ReplayPlan.legalAbove(listOf(Surface.SKETCH, Surface.CALENDAR)))
        assertEquals(listOf(Surface.SKETCH), ReplayPlan.decodeAbove(listOf("SKETCH")))
    }

    @Test
    fun `a notebook on top of a library-level entry is not a shape and answers Nothing`() {
        val stack = listOf(entry(Surface.CALENDAR), entry(Surface.NOTEBOOK, notebookId = "nb-1"))
        assertEquals(ReplayPlan.Nothing, ReplayPlan.of(stack))
    }

    // ── legalAbove (RS2) ─────────────────────────────────────────────────────

    @Test
    fun `legalAbove leaves an empty list empty`() {
        assertEquals(emptyList<Surface>(), ReplayPlan.legalAbove(emptyList()))
    }

    @Test
    fun `legalAbove leaves a lone calendar unchanged`() {
        assertEquals(listOf(Surface.CALENDAR), ReplayPlan.legalAbove(listOf(Surface.CALENDAR)))
    }

    @Test
    fun `legalAbove leaves a lone scratch pad unchanged`() {
        assertEquals(listOf(Surface.SCRATCH_PAD), ReplayPlan.legalAbove(listOf(Surface.SCRATCH_PAD)))
    }

    @Test
    fun `legalAbove leaves a lone document editor unchanged`() {
        assertEquals(listOf(Surface.DOCUMENT_EDITOR), ReplayPlan.legalAbove(listOf(Surface.DOCUMENT_EDITOR)))
    }

    @Test
    fun `legalAbove leaves the calendar-then-pad pair unchanged`() {
        val pair = listOf(Surface.CALENDAR, Surface.SCRATCH_PAD)
        assertEquals(pair, ReplayPlan.legalAbove(pair))
    }

    @Test
    fun `legalAbove truncates an editor above a calendar to the calendar`() {
        assertEquals(
            listOf(Surface.CALENDAR),
            ReplayPlan.legalAbove(listOf(Surface.CALENDAR, Surface.DOCUMENT_EDITOR)),
        )
    }

    @Test
    fun `legalAbove truncates a calendar above a pad to the pad`() {
        assertEquals(
            listOf(Surface.SCRATCH_PAD),
            ReplayPlan.legalAbove(listOf(Surface.SCRATCH_PAD, Surface.CALENDAR)),
        )
    }

    @Test
    fun `legalAbove drops everything when a notebook leads`() {
        assertEquals(
            emptyList<Surface>(),
            ReplayPlan.legalAbove(listOf(Surface.NOTEBOOK, Surface.CALENDAR)),
        )
    }

    @Test
    fun `legalAbove keeps only the pair when a third surface follows it`() {
        assertEquals(
            listOf(Surface.CALENDAR, Surface.SCRATCH_PAD),
            ReplayPlan.legalAbove(listOf(Surface.CALENDAR, Surface.SCRATCH_PAD, Surface.CALENDAR)),
        )
    }

    @Test
    fun `of normalizes an illegal chain above the notebook`() {
        val stack = listOf(
            entry(Surface.NOTEBOOK, notebookId = "nb-1"),
            entry(Surface.SCRATCH_PAD),
            entry(Surface.CALENDAR),
        )
        assertEquals(
            ReplayPlan.Notebook("nb-1", false, listOf(Surface.SCRATCH_PAD)),
            ReplayPlan.of(stack),
        )
    }

    // ── decodeAbove (RS2) ────────────────────────────────────────────────────

    @Test
    fun `decodeAbove reads surface names in order`() {
        assertEquals(
            listOf(Surface.CALENDAR, Surface.SCRATCH_PAD),
            ReplayPlan.decodeAbove(listOf("CALENDAR", "SCRATCH_PAD")),
        )
    }

    @Test
    fun `decodeAbove drops an unknown name rather than crashing`() {
        assertEquals(
            listOf(Surface.CALENDAR, Surface.SCRATCH_PAD),
            ReplayPlan.decodeAbove(listOf("CALENDAR", "BOGUS", "SCRATCH_PAD")),
        )
    }

    @Test
    fun `decodeAbove normalizes what it decoded`() {
        assertEquals(
            listOf(Surface.CALENDAR),
            ReplayPlan.decodeAbove(listOf("CALENDAR", "DOCUMENT_EDITOR")),
        )
    }

    @Test
    fun `decodeAbove answers empty for null and for an empty list`() {
        assertEquals(emptyList<Surface>(), ReplayPlan.decodeAbove(null))
        assertEquals(emptyList<Surface>(), ReplayPlan.decodeAbove(emptyList()))
    }

    @Test
    fun `decodeAbove answers empty when every name is unknown`() {
        assertEquals(emptyList<Surface>(), ReplayPlan.decodeAbove(listOf("BOGUS", "ALSO_BOGUS")))
    }
}

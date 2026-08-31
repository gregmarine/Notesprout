package com.symmetricalpalmtree.notesproutsn.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the pure half of the Document PDF (arc 19 / M9): where the text sits on the page and how
 * big it is. The layout itself is Android and is eye-checked against the editor's Preview on the
 * device; these are the decisions that would silently produce a wrong-looking document — prose
 * running off the edge, a page too small for its own margins accepted as if it were fine, or a
 * stored text size read at the wrong scale.
 */
class DocumentPdfMetricsTest {

    /** A Manta page at 3x, which is where every number below comes out in whole px. */
    private val density = 3f

    @Test
    fun putsTheContentBoxInsideTheEditorsMargins() {
        // 16dp left/right/top, 32dp at the foot — the Preview's own padding.
        val box = DocumentPdfMetrics.box(1404, 1872, density)!!
        assertEquals(48, box.left)
        assertEquals(48, box.top)
        assertEquals(1404 - 48 - 48, box.width)
        assertEquals(1872 - 48 - 96, box.height)
    }

    @Test
    fun theFootMarginIsWiderThanTheHead() {
        // Not a tidy-up candidate: it is the Preview's scroll tail, and it reads as a document's
        // wider foot margin on paper. A "fix" that evened them would change every exported page.
        val box = DocumentPdfMetrics.box(1000, 1000, density)!!
        val foot = 1000 - box.top - box.height
        assertEquals(96, foot)
        assertEquals(48, box.top)
    }

    @Test
    fun refusesAPageWithNoRoomLeftForText() {
        // A page smaller than its own margins is a damaged or foreign-written row — the caller
        // turns this null into DAMAGED rather than asking a layout engine for a zero-width line.
        assertNull(DocumentPdfMetrics.box(90, 1872, density))    // 48 + 48 leaves nothing across
        assertNull(DocumentPdfMetrics.box(1404, 140, density))   // 48 + 96 leaves nothing down
        assertNull(DocumentPdfMetrics.box(0, 0, density))
        assertNull(DocumentPdfMetrics.box(-10, 1872, density))
        // Exactly one pixel of room is still room.
        assertNotNull(DocumentPdfMetrics.box(97, 145, density))
    }

    @Test
    fun scalesTheMarginsWithTheDisplay() {
        val box = DocumentPdfMetrics.box(1000, 1000, 2f)!!
        assertEquals(32, box.left)
        assertEquals(1000 - 32 - 64, box.height)
    }

    @Test
    fun readsTheEditorsStoredSize() {
        assertEquals(18f, DocumentPdfMetrics.textSizeSp("18.0"), 0f)
        assertEquals(21f, DocumentPdfMetrics.textSizeSp(" 21.0 "), 0f)
    }

    @Test
    fun coercesAStoredSizeIntoTheOfferedRange() {
        // A value from a future build with a wider range must not lay the export out at a size
        // this one cannot draw.
        assertEquals(14f, DocumentPdfMetrics.textSizeSp("9.0"), 0f)
        assertEquals(25f, DocumentPdfMetrics.textSizeSp("64.0"), 0f)
    }

    @Test
    fun fallsBackToTheDefaultOnAnythingUnreadable() {
        // No editor, no store, no key, a foreign value: a text size is comfort, and an export
        // never refuses over one.
        assertEquals(16f, DocumentPdfMetrics.textSizeSp(null), 0f)
        assertEquals(16f, DocumentPdfMetrics.textSizeSp(""), 0f)
        assertEquals(16f, DocumentPdfMetrics.textSizeSp("large"), 0f)
        assertEquals(16f, DocumentPdfMetrics.textSizeSp("NaN"), 0f)
    }

    @Test
    fun laysOutAtThePreviewsBumpedSize() {
        // The Preview reads a little larger than the Markdown source it came from, and the export
        // is a picture of the Preview — so the bump is applied before the sp → px scale, not after.
        assertEquals((16f + 2f) * 3f, DocumentPdfMetrics.textSizePx(16f, 3f), 0.001f)
        assertEquals((25f + 2f) * 2.5f, DocumentPdfMetrics.textSizePx(25f, 2.5f), 0.001f)
    }
}

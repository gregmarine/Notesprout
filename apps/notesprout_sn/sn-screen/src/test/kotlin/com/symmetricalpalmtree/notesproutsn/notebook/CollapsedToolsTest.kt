package com.symmetricalpalmtree.notesproutsn.notebook

import com.symmetricalpalmtree.gpaper.core.Tool
import com.symmetricalpalmtree.notesproutsn.screen.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The collapsed chrome's rules (arc 36 / C1): the corner button's glyph per tool, the armed
 * mini-toolbar button, and the outside-tap dismissal — the one with a trap in it (the corner
 * button must be excluded or its own tap would close-then-reopen the rows).
 */
class CollapsedToolsTest {

    @Test fun `the order is pen, point eraser, lasso eraser, lasso`() {
        assertEquals(listOf(Tool.PEN, Tool.ERASER, Tool.LASSO_ERASER, Tool.LASSO), CollapsedTools.ORDER)
    }

    @Test fun `each tool wears its own glyph`() {
        assertEquals(R.drawable.ic_pen, CollapsedTools.iconFor(Tool.PEN))
        assertEquals(R.drawable.ic_eraser, CollapsedTools.iconFor(Tool.ERASER))
        assertEquals(R.drawable.ic_lasso_eraser, CollapsedTools.iconFor(Tool.LASSO_ERASER))
        assertEquals(R.drawable.ic_lasso, CollapsedTools.iconFor(Tool.LASSO))
    }

    @Test fun `the lasso wears the clipboard mark while objects are on the clipboard`() {
        assertEquals(R.drawable.ic_lasso_clipboard, CollapsedTools.iconFor(Tool.LASSO, clipboardLoaded = true))
        // Only the lasso: a loaded clipboard changes nothing about the other glyphs.
        assertEquals(R.drawable.ic_pen, CollapsedTools.iconFor(Tool.PEN, clipboardLoaded = true))
        assertEquals(R.drawable.ic_eraser, CollapsedTools.iconFor(Tool.ERASER, clipboardLoaded = true))
    }

    @Test fun `the pen wears the ballpen - the pencil glyph is the sketch face's own`() {
        // 2026-09-22: `ic_pen` is Tabler's ballpen everywhere a pen is meant; the pencil glyph is
        // `ic_pencil`, worn only by the sketch face's Pencil, which paints its own glyph and never
        // asks this rule. So there is one pen glyph here and no "alt" flag to keep in step.
        assertEquals(R.drawable.ic_pen, CollapsedTools.iconFor(Tool.PEN))
        assertEquals(R.drawable.ic_pen, CollapsedTools.iconFor(Tool.NONE))
    }

    @Test fun `exactly one of the two pen buttons reads as armed, and only under PEN`() {
        assertTrue(CollapsedTools.penButtonSelected(Tool.PEN, altPenArmed = false, isAltButton = false))
        assertFalse(CollapsedTools.penButtonSelected(Tool.PEN, altPenArmed = false, isAltButton = true))
        assertTrue(CollapsedTools.penButtonSelected(Tool.PEN, altPenArmed = true, isAltButton = true))
        assertFalse(CollapsedTools.penButtonSelected(Tool.PEN, altPenArmed = true, isAltButton = false))
        listOf(Tool.ERASER, Tool.LASSO_ERASER, Tool.LASSO, Tool.NONE).forEach { tool ->
            assertFalse(CollapsedTools.penButtonSelected(tool, altPenArmed = false, isAltButton = false))
            assertFalse(CollapsedTools.penButtonSelected(tool, altPenArmed = true, isAltButton = true))
        }
    }

    @Test fun `the corner button's painted report follows the primary pen button`() {
        // Arc 44 / T3: a screen may paint the primary kind's glyph itself (the sketch face's pencil
        // filled with the armed shade), and the corner button wears it exactly when the PRIMARY pen
        // button reads as armed — this rule, not a second spelling of "is the pencil on the paper?".
        assertTrue(CollapsedTools.penButtonSelected(Tool.PEN, altPenArmed = false, isAltButton = false))
        // The alt kind and every other tool wear their own glyphs, untouched by the report.
        assertFalse(CollapsedTools.penButtonSelected(Tool.PEN, altPenArmed = true, isAltButton = false))
        assertFalse(CollapsedTools.penButtonSelected(Tool.ERASER, altPenArmed = false, isAltButton = false))
        // The trap: NONE wears `ic_pen` as "what a tap will bring back", but nothing is on the
        // paper, so it keeps the plain glyph rather than reporting a shade.
        assertFalse(CollapsedTools.penButtonSelected(Tool.NONE, altPenArmed = false, isAltButton = false))
        assertEquals(R.drawable.ic_pen, CollapsedTools.iconFor(Tool.NONE))
    }

    @Test fun `a screen with one pen gets the rule it always had`() {
        // The defaults every existing caller passes: "is the pen armed?", and nothing else.
        CollapsedTools.ORDER.forEach { tool ->
            assertEquals(
                tool == Tool.PEN,
                CollapsedTools.penButtonSelected(tool, altPenArmed = false, isAltButton = false),
            )
        }
    }

    @Test fun `NONE wears the pen and arms no button`() {
        assertEquals(R.drawable.ic_pen, CollapsedTools.iconFor(Tool.NONE))
        assertNull(CollapsedTools.selectedFor(Tool.NONE))
    }

    @Test fun `every ordered tool selects itself`() {
        CollapsedTools.ORDER.forEach { assertEquals(it, CollapsedTools.selectedFor(it)) }
    }

    @Test fun `an overflow of one or two sits on the mini toolbar - three or more go behind the dots`() {
        assertFalse(CollapsedTools.overflowInline(0))
        assertTrue(CollapsedTools.overflowInline(1))
        assertTrue(CollapsedTools.overflowInline(2))
        assertFalse(CollapsedTools.overflowInline(3))
        assertFalse(CollapsedTools.overflowInline(8))
    }

    @Test fun `nothing showing - nothing to dismiss`() {
        assertFalse(CollapsedTools.outsideTapDismisses(showing = false, onChrome = false, keep = false))
    }

    @Test fun `a contact on the corner button or the rows never dismisses - the button's click toggles`() {
        assertFalse(CollapsedTools.outsideTapDismisses(showing = true, onChrome = true, keep = false))
    }

    @Test fun `a contact inside a sub-bar hung off the rows keeps them`() {
        assertFalse(CollapsedTools.outsideTapDismisses(showing = true, onChrome = false, keep = true))
    }

    @Test fun `any other contact takes the rows down`() {
        assertTrue(CollapsedTools.outsideTapDismisses(showing = true, onChrome = false, keep = false))
    }
}

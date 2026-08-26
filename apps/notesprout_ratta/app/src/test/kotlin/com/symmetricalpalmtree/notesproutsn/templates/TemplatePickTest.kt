package com.symmetricalpalmtree.notesproutsn.templates

import com.symmetricalpalmtree.notesproutsn.data.index.ListIds
import com.symmetricalpalmtree.notesproutsn.data.template.TemplateKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The result contract between the template browser and its three hosts (arc 13 / G3): a pick names
 * a card, crosses an Activity boundary as one short string, and the reader does the read.
 *
 * The rule worth defending is what a **bad** string means. A pick that will not decode is the same
 * answer as backing out — never Blank — because the two are indistinguishable from the caller's
 * side and only one of them is safe: guessing Blank would wipe the paper the page already had.
 */
class TemplatePickTest {

    private fun roundTrip(pick: TemplatePick) = assertEquals(pick, TemplatePick.decode(pick.encode()))

    @Test
    fun `every case round-trips`() {
        roundTrip(TemplatePick.Blank)
        for (kind in TemplateKind.entries.filter { it != TemplateKind.BLANK }) {
            roundTrip(TemplatePick.BuiltIn(kind))
        }
        roundTrip(TemplatePick.Static("8ba1c0de-0000-4000-8000-000000000001"))
    }

    @Test
    fun `a static id keeps its dashes`() {
        val id = "8ba1c0de-0000-4000-8000-000000000001"
        assertEquals(TemplatePick.Static(id), TemplatePick.decode(TemplatePick.Static(id).encode()))
    }

    @Test
    fun `an unreadable pick is null, never Blank`() {
        assertNull(TemplatePick.decode(null))
        assertNull(TemplatePick.decode(""))
        assertNull(TemplatePick.decode("kind:CORNELL"))
        assertNull(TemplatePick.decode("static:"))
        assertNull(TemplatePick.decode("who knows"))
    }

    @Test
    fun `a kind of BLANK decodes to the Blank card, not a built-in with no paper`() {
        // Nothing writes this, but a future or foreign encoder could; folding it into Blank is the
        // one reading that cannot produce a template row naming paper that does not exist.
        assertEquals(TemplatePick.Blank, TemplatePick.decode("kind:BLANK"))
    }

    // ── cardId ──────────────────────────────────────────────────────────────

    @Test
    fun `each pick names the card it came from`() {
        assertEquals(ListIds.TEMPLATE_BLANK_ID, TemplatePick.Blank.cardId)
        assertEquals(ListIds.TEMPLATE_LINED_ID, TemplatePick.BuiltIn(TemplateKind.LINED).cardId)
        assertEquals(ListIds.TEMPLATE_DOTTED_ID, TemplatePick.BuiltIn(TemplateKind.DOTTED).cardId)
        assertEquals(ListIds.TEMPLATE_GRID_ID, TemplatePick.BuiltIn(TemplateKind.GRID).cardId)
        assertEquals("row-1", TemplatePick.Static("row-1").cardId)
    }

    @Test
    fun `the birth kind a new notebook records`() {
        assertEquals(TemplateKind.BLANK.name, TemplatePicks.birthKind(TemplatePick.Blank))
        assertEquals("GRID", TemplatePicks.birthKind(TemplatePick.BuiltIn(TemplateKind.GRID)))
        assertEquals(TemplateLibrary.KIND_IMAGE, TemplatePicks.birthKind(TemplatePick.Static("row-1")))
    }
}

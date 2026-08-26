package com.symmetricalpalmtree.notesproutsn.data.template

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `.soil` template token (arc 13 / G3) — the one string that decides whether re-papering a page
 * costs a megabyte or nothing, so every rule about it is checked here rather than on the glass.
 *
 * The headline case is the first: the built-ins' tokens **must not move**. Every `.soil` this family
 * has ever written carries `LINED` / `DOTTED` / `GRID`, and a Paper build on the same device still
 * reads them.
 */
class TemplateTokenTest {

    @Test
    fun `the built-in tokens are exactly the old names`() {
        assertEquals("LINED", TemplateToken.of(TemplateKind.LINED))
        assertEquals("DOTTED", TemplateToken.of(TemplateKind.DOTTED))
        assertEquals("GRID", TemplateToken.of(TemplateKind.GRID))
    }

    @Test
    fun `blank has no token at all`() {
        // Not a token this build happens not to write: blank IS the absence of a template row.
        assertEquals("", TemplateToken.of(TemplateKind.BLANK))
    }

    @Test
    fun `a built-in token round-trips`() {
        for (kind in TemplateKind.entries) {
            assertEquals(kind, TemplateToken.kindOf(TemplateToken.of(kind)))
        }
    }

    @Test
    fun `an empty token reads as blank, and a foreign one as nothing`() {
        assertEquals(TemplateKind.BLANK, TemplateToken.kindOf(""))
        assertNull(TemplateToken.kindOf("CORNELL"))
        assertNull(TemplateToken.kindOf("BLANK"))     // blank is "", never the word
    }

    // ── Imported pictures ────────────────────────────────────────────────────

    @Test
    fun `an image token has the locked shape`() {
        val token = TemplateToken.ofImage(byteArrayOf(1, 2, 3, 4), fit = TemplateFit.FIT)
        assertTrue(token.startsWith("IMG#"))
        assertEquals(4 + 8, token.length)
        assertTrue(token.drop(4).all { it in "0123456789abcdef" })
        assertTrue(TemplateToken.isImage(token))
    }

    @Test
    fun `the same picture at the same fit is the same token`() {
        val a = TemplateToken.ofImage(byteArrayOf(7, 7, 7), fit = TemplateFit.FIT)
        val b = TemplateToken.ofImage(byteArrayOf(7, 7, 7), fit = TemplateFit.FIT)
        assertEquals(a, b)
    }

    @Test
    fun `different bytes give different tokens`() {
        assertNotEquals(
            TemplateToken.ofImage(byteArrayOf(1), fit = TemplateFit.FIT),
            TemplateToken.ofImage(byteArrayOf(2), fit = TemplateFit.FIT),
        )
    }

    @Test
    fun `the fit is part of the identity`() {
        // The stored bytes are the same picture; the page pixels are not. Reusing across fits would
        // hand the user the wrong paper with no way to ask again.
        val bytes = byteArrayOf(3, 1, 4, 1, 5)
        assertNotEquals(
            TemplateToken.ofImage(bytes, fit = TemplateFit.FIT),
            TemplateToken.ofImage(bytes, fit = TemplateFit.STRETCH),
        )
        assertNotEquals(
            TemplateToken.ofImage(bytes, fit = TemplateFit.STRETCH),
            TemplateToken.ofImage(bytes, fit = TemplateFit.FILL),
        )
    }

    @Test
    fun `an image token is never mistaken for a built-in, or the other way round`() {
        val token = TemplateToken.ofImage(byteArrayOf(1), fit = TemplateFit.FIT)
        assertNull(TemplateToken.kindOf(token))
        assertFalse(TemplateToken.isImage("LINED"))
        assertFalse(TemplateToken.isImage(""))
        // Shape, not just prefix: a truncated or padded digest is not one of ours.
        assertFalse(TemplateToken.isImage("IMG#abc"))
        assertFalse(TemplateToken.isImage("IMG#0123456789"))
    }
}

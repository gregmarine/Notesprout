package com.symmetricalpalmtree.notesproutsn.data

import com.symmetricalpalmtree.notesproutsn.data.template.DensityAxis
import com.symmetricalpalmtree.notesproutsn.data.template.DensityMode
import com.symmetricalpalmtree.notesproutsn.data.template.TemplateKind
import com.symmetricalpalmtree.notesproutsn.data.template.TemplateSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TemplateSpec] — a generator written down (arc 13 / G2): what it is worth, what it is called in
 * a `.soil` file, and what it does with input it did not write.
 */
class TemplateSpecTest {

    // ── The token ────────────────────────────────────────────────────────────

    @Test
    fun aStockSpecKeepsTheBareKindNameEveryOlderFileAlreadyUses() {
        assertEquals("LINED", TemplateSpec.stock(TemplateKind.LINED).token())
        assertEquals("DOTTED", TemplateSpec.stock(TemplateKind.DOTTED).token())
        assertEquals("GRID", TemplateSpec.stock(TemplateKind.GRID).token())
    }

    @Test
    fun anAdjustedSpecIsTheKindPlusEightHex() {
        val token = TemplateSpec.stock(TemplateKind.LINED).copy(topMm = 12f).token()
        assertTrue(token, token.startsWith("LINED#"))
        assertEquals(6 + 8, token.length)
        assertTrue(token.substring(6).all { it in "0123456789abcdef" })
    }

    @Test
    fun theSameSpecAlwaysDigestsTheSame() {
        val a = TemplateSpec.stock(TemplateKind.GRID).copy(leftMm = 20f, marginRule = true, shade = 9)
        val b = TemplateSpec.stock(TemplateKind.GRID).copy(shade = 9, marginRule = true, leftMm = 20f)
        assertEquals(a.canonical(), b.canonical())
        assertEquals(a.token(), b.token())
    }

    @Test
    fun everyFieldThatChangesThePaperChangesTheToken() {
        val base = TemplateSpec.stock(TemplateKind.GRID)
        val variants = listOf(
            base.copy(topMm = 5f), base.copy(bottomMm = 5f), base.copy(leftMm = 5f), base.copy(rightMm = 5f),
            base.copy(marginRule = true), base.copy(thicknessMm = 0.5f), base.copy(shade = 8),
            base.copy(rows = DensityAxis(DensityMode.SPACING, spacingMm = 6f)),
            base.copy(cols = DensityAxis(DensityMode.SPACING, spacingMm = 6f)),
            base.copy(rows = DensityAxis(DensityMode.COUNT, count = 20)),
        )
        val tokens = variants.map { it.token() } + base.token()
        assertEquals(tokens.size, tokens.toSet().size)
    }

    @Test
    fun afieldTheKindIgnoresDoesNotChangeItsIdentity() {
        // Lined has no columns and no dots; touching them must not fork the library.
        val lined = TemplateSpec.stock(TemplateKind.LINED)
        assertEquals(lined.token(), lined.copy(cols = DensityAxis(DensityMode.COUNT, count = 99)).token())
        assertEquals(lined.token(), lined.copy(dotMm = 2f).token())
        // Dotted does have dots, so there the same change is a different paper.
        val dotted = TemplateSpec.stock(TemplateKind.DOTTED)
        assertNotEquals(dotted.token(), dotted.copy(dotMm = 2f).token())
    }

    @Test
    fun aSpacingModeAndACountModeAreDifferentStatements() {
        val base = TemplateSpec.stock(TemplateKind.GRID)
        val counted = base.copy(rows = DensityAxis(DensityMode.COUNT, count = 19))
        assertNotEquals(base.token(), counted.token())
        assertTrue(counted.canonical(), counted.canonical().contains("y=C:19"))
        assertTrue(base.canonical(), base.canonical().contains("y=S:8.0000"))
    }

    @Test
    fun theCanonicalFormIsLocaleProof() {
        val previous = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            val spec = TemplateSpec.stock(TemplateKind.LINED).copy(topMm = 8.5f)
            // A German locale writes 8,5000 — which would fork the library by device settings.
            assertTrue(spec.canonical(), spec.canonical().contains("8.5000"))
            assertFalse(spec.canonical(), spec.canonical().contains("8,5000"))
        } finally {
            java.util.Locale.setDefault(previous)
        }
    }

    // ── Sanitizing ───────────────────────────────────────────────────────────

    @Test
    fun aStockSpecIsAlreadyItsOwnSanitizedSelf() {
        for (kind in listOf(TemplateKind.LINED, TemplateKind.DOTTED, TemplateKind.GRID)) {
            assertEquals(TemplateSpec.stock(kind), TemplateSpec.stock(kind).sanitized())
            assertTrue(TemplateSpec.stock(kind).isStock)
        }
    }

    @Test
    fun nonsenseIsClampedRatherThanTrusted() {
        val wild = TemplateSpec(
            kind = TemplateKind.BLANK,
            rows = DensityAxis(DensityMode.COUNT, spacingMm = -3f, count = 9_000),
            topMm = 500f, bottomMm = Float.NaN, leftMm = Float.NEGATIVE_INFINITY, rightMm = -2f,
            thicknessMm = 40f, dotMm = 0f, shade = 99,
        ).sanitized()
        // Blank is not a generator; it becomes the one a generator screen can actually draw.
        assertEquals(TemplateKind.LINED, wild.kind)
        assertEquals(TemplateSpec.MAX_COUNT, wild.rows.count)
        assertEquals(TemplateSpec.MIN_SPACING_MM, wild.rows.spacingMm, 0f)
        assertEquals(TemplateSpec.MAX_INSET_MM, wild.topMm, 0f)
        assertEquals(0f, wild.bottomMm, 0f)
        assertEquals(0f, wild.leftMm, 0f)
        assertEquals(0f, wild.rightMm, 0f)
        assertEquals(TemplateSpec.MAX_THICKNESS_MM, wild.thicknessMm, 0f)
        assertEquals(TemplateSpec.MIN_DOT_MM, wild.dotMm, 0f)
        assertEquals(TemplateSpec.SHADE_BLACK, wild.shade)
    }

    @Test
    fun aCountModeAxisIsNeverZero() {
        val spec = TemplateSpec.stock(TemplateKind.GRID)
            .copy(rows = DensityAxis(DensityMode.COUNT, count = 0)).sanitized()
        assertEquals(TemplateSpec.MIN_COUNT, spec.rows.count)
        // In spacing mode the count is only a read-out, so it is left alone.
        val spacing = TemplateSpec.stock(TemplateKind.GRID).sanitized()
        assertEquals(0, spacing.rows.count)
    }

    // ── Round trip ───────────────────────────────────────────────────────────

    @Test
    fun aSpecSurvivesTheTripThroughAnIndexRow() {
        val spec = TemplateSpec.stock(TemplateKind.DOTTED).copy(
            rows = DensityAxis(DensityMode.COUNT, count = 30),
            cols = DensityAxis(DensityMode.COUNT, count = 22),
            topMm = 12.5f, leftMm = 18f, marginRule = true, dotMm = 0.9f, shade = 11,
        )
        val back = TemplateSpec.decode(spec.encode())
        assertEquals(spec.sanitized(), back)
        assertEquals(spec.token(), back!!.token())
    }

    @Test
    fun anUnusablePayloadReadsAsNoSpecRatherThanThrowing() {
        assertNull(TemplateSpec.decode(null))
        assertNull(TemplateSpec.decode(ByteArray(0)))
        assertNull(TemplateSpec.decode("not json".toByteArray()))
        assertNull(TemplateSpec.decode("{\"kind\":\"HOLOGRAM\"}".toByteArray()))
        assertNull(TemplateSpec.decode(ByteArray(TemplateSpec.MAX_BYTES + 1)))
    }

    @Test
    fun anUnknownFieldFromALaterBuildIsIgnoredNotFatal() {
        val json = "{\"kind\":\"GRID\",\"topMm\":4.0,\"someLaterIdea\":true}"
        val spec = TemplateSpec.decode(json.toByteArray())
        assertEquals(TemplateKind.GRID, spec!!.kind)
        assertEquals(4f, spec.topMm, 0f)
    }
}

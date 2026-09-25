package com.symmetricalpalmtree.notesproutsn.ext.sketch

import com.symmetricalpalmtree.gpaper.core.model.StrokeStyle
import com.symmetricalpalmtree.notesproutsn.extension.SketchContract
import com.symmetricalpalmtree.notesproutsn.extension.SketchToolSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The face's tool state (arc 44 / T3, remade by arc 46 "Palette"): what each armed kind draws
 * with, that a switch keeps both kinds' shades, that a pick lands on the armed kind alone, and —
 * the part that matters most — that **every number read from outside lands on something legal**,
 * field by field.
 *
 * The round trip is the seam's whole promise: indices go out, the same indices come back, and the
 * host learns nothing about what they name.
 */
class SketchToolStateTest {

    private val pen = SketchContract.TOOL_PEN
    private val pencil = SketchContract.TOOL_PENCIL

    @Test fun `the default is the pencil at 505050 with a black pen waiting`() {
        val state = SketchToolState.DEFAULT
        assertFalse(state.isPen)
        assertEquals(pencil, state.tool)
        assertEquals(SketchPalette.DEFAULT_SHADE, state.pencilShade)
        assertEquals(SketchPalette.DEFAULT_PEN_SHADE, state.penShade)
        assertEquals(StrokeStyle.PENCIL, state.penStyle)
        assertEquals(0xFF505050.toInt(), state.penColor)
        assertEquals(SketchPalette.PENCIL_WIDTH_PX, state.penWidth, 0f)
        assertEquals(1, state.armedShade)
    }

    @Test fun `the gel pen draws PEN at its own width and its own shade`() {
        val state = SketchToolState.DEFAULT.withTool(pen)
        assertTrue(state.isPen)
        assertEquals(StrokeStyle.PEN, state.penStyle)
        assertEquals(SketchPalette.PEN_WIDTH_PX, state.penWidth, 0f)
        assertEquals(0xFF000000.toInt(), state.penColor)
        assertEquals(0, state.armedShade)
        val grey = state.withShade(9)
        assertEquals(0xFFAAAAAA.toInt(), grey.penColor)
        assertEquals(9, grey.armedShade)
    }

    @Test fun `a pick lands on the armed kind and leaves the other alone`() {
        val pencilPicked = SketchToolState.DEFAULT.withShade(12)
        assertEquals(12, pencilPicked.pencilShade)
        assertEquals(SketchPalette.DEFAULT_PEN_SHADE, pencilPicked.penShade)
        val penPicked = pencilPicked.withTool(pen).withShade(3)
        assertEquals(12, penPicked.pencilShade)
        assertEquals(3, penPicked.penShade)
        // And back: the pencil is exactly as it was left.
        val back = penPicked.withTool(pencil)
        assertEquals(12, back.armedShade)
        assertEquals(0xFFC8C8C8.toInt(), back.penColor)
        assertEquals(3, back.penShade)
    }

    @Test fun `the pencil draws its picked shade including white at one width`() {
        val white = SketchToolState.DEFAULT.withShade(SketchPalette.WHITE_SHADE)
        assertEquals(0xFFFFFFFF.toInt(), white.penColor)
        assertEquals(StrokeStyle.PENCIL, white.penStyle)
        assertEquals(SketchPalette.PENCIL_WIDTH_PX, white.penWidth, 0f)
    }

    @Test fun `each button reports its own kind's shade whatever is armed`() {
        val s = SketchToolState.DEFAULT.withShade(7).withTool(pen).withShade(14)
        assertEquals(0xFF909090.toInt(), s.pencilReport)
        assertEquals(0xFFDDDDDD.toInt(), s.penReport)
        assertEquals(s.penReport, s.penColor)
        val t = s.withTool(pencil)
        assertEquals(0xFF909090.toInt(), t.pencilReport)
        assertEquals(0xFFDDDDDD.toInt(), t.penReport)
        assertEquals(t.pencilReport, t.penColor)
    }

    @Test fun `every offered level reports a distinct tone on both kinds`() {
        val pencilTones = SketchPalette.SHADE_LEVELS.map { SketchToolState.DEFAULT.withShade(it).pencilReport }
        assertEquals(pencilTones.size, pencilTones.distinct().size)
        val penTones = SketchPalette.SHADE_LEVELS.map { SketchToolState.DEFAULT.withTool(pen).withShade(it).penReport }
        assertEquals(penTones.size, penTones.distinct().size)
    }

    @Test fun `nothing remembered is the default`() {
        assertEquals(SketchToolState.DEFAULT, SketchToolState.fromSettings(null))
    }

    @Test fun `the round trip carries both shades and writes a dead size of 0`() {
        val state = SketchToolState.of(pen, pencilShade = 11, penShade = 2)
        val parcel = state.toSettings()
        assertEquals(SketchToolSettings(pen, 11, 0, 2), parcel)
        assertEquals(0, parcel.size)
        assertEquals(state, SketchToolState.fromSettings(parcel))
        // A remembered size from arc 44 is simply not read.
        assertEquals(state, SketchToolState.fromSettings(SketchToolSettings(pen, 11, 7, 2)))
    }

    @Test fun `an unknown tool reads as the pencil`() {
        assertEquals(pencil, SketchToolState.of(7, 5, 0).tool)
        assertEquals(pencil, SketchToolState.of(255, 5, 0).tool)
        assertEquals(pen, SketchToolState.of(pen, 5, 0).tool)
    }

    @Test fun `a level this build does not offer falls to that field's default and keeps the rest`() {
        val a = SketchToolState.of(pen, pencilShade = 200, penShade = 9)
        assertEquals(pen, a.tool)
        assertEquals(SketchPalette.DEFAULT_SHADE, a.pencilShade)
        assertEquals(9, a.penShade)
        val b = SketchToolState.of(pencil, pencilShade = 9, penShade = 255)
        assertEquals(9, b.pencilShade)
        assertEquals(SketchPalette.DEFAULT_PEN_SHADE, b.penShade)
    }

    @Test fun `the seam's whole sanity bound is survivable`() {
        val max = SketchContract.MAX_TOOL_SETTING_INDEX
        val s = SketchToolState.fromSettings(SketchToolSettings(max, max, max, max))
        assertEquals(pencil, s.tool)
        assertEquals(SketchPalette.DEFAULT_SHADE, s.pencilShade)
        assertEquals(SketchPalette.DEFAULT_PEN_SHADE, s.penShade)
    }

    @Test fun `every state a pick can make is a legal parcel and round-trips`() {
        for (tool in listOf(pencil, pen)) {
            for (p in SketchPalette.SHADE_LEVELS) {
                for (q in SketchPalette.SHADE_LEVELS) {
                    val state = SketchToolState.of(tool, p, q)
                    val parcel = state.toSettings()
                    assertEquals(state, SketchToolState.fromSettings(parcel))
                }
            }
        }
    }
}

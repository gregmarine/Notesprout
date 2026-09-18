package com.symmetricalpalmtree.notesproutsn.ext.sketch

import com.symmetricalpalmtree.gpaper.core.model.StrokeStyle
import com.symmetricalpalmtree.notesproutsn.extension.SketchContract
import com.symmetricalpalmtree.notesproutsn.extension.SketchToolSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The face's tool state (arc 44 / T3): what each armed kind draws with, that a switch keeps the
 * pencil's choices, and — the part that matters most — that **every number read from outside lands
 * on something legal**, field by field.
 *
 * The round trip is the seam's whole promise: indices go out, the same indices come back, and the
 * host learns nothing about what they name.
 */
class SketchToolStateTest {

    @Test fun `the default is the pencil at level 5 and the finest lead`() {
        val state = SketchToolState.DEFAULT
        assertFalse(state.isPen)
        assertEquals(SketchContract.TOOL_PENCIL, state.tool)
        assertEquals(SketchPalette.DEFAULT_SHADE, state.shadeLevel)
        assertEquals(SketchPalette.DEFAULT_SIZE, state.sizeIndex)
        assertEquals(StrokeStyle.PENCIL, state.penStyle)
        assertEquals(0xFF555555.toInt(), state.penColor)
        assertEquals(1.2f, state.penWidth, 0f)
    }

    @Test fun `the gel pen is PEN, black, five px - whatever the pencil is set to`() {
        val state = SketchToolState.of(SketchContract.TOOL_PEN, shadeLevel = 9, sizeIndex = 11)
        assertTrue(state.isPen)
        assertEquals(StrokeStyle.PEN, state.penStyle)
        assertEquals(SketchPalette.PEN_COLOR, state.penColor)
        assertEquals(SketchPalette.PEN_WIDTH_PX, state.penWidth, 0f)
    }

    @Test fun `a switch to the pen and back keeps the shade and the lead`() {
        val pencil = SketchToolState.DEFAULT.withShade(9).withSize(3)
        val pen = pencil.withTool(SketchContract.TOOL_PEN)
        assertEquals(9, pen.shadeLevel)
        assertEquals(3, pen.sizeIndex)
        assertEquals(pencil, pen.withTool(SketchContract.TOOL_PENCIL))
    }

    @Test fun `the pencil draws the shade and the lead that were picked`() {
        val state = SketchToolState.DEFAULT.withShade(1).withSize(4)
        assertEquals(StrokeStyle.PENCIL, state.penStyle)
        assertEquals(0xFF111111.toInt(), state.penColor)
        assertEquals(12f, state.penWidth, 0f)
        // Both ends of the widened list, and one of the shades the walk kept to try.
        val heaviest = SketchToolState.DEFAULT.withShade(9).withSize(11)
        assertEquals(0xFF999999.toInt(), heaviest.penColor)
        assertEquals(96f, heaviest.penWidth, 0f)
    }

    @Test fun `the Pencil button reports the pencil's shade whatever kind is armed`() {
        // Arc 44 / T3, the user's answer: the button's fill is the armed shade — and a button says
        // what a tap on it will bring back, so the gel pen does not blank it or turn it black.
        val pencil = SketchToolState.DEFAULT.withShade(9)
        assertEquals(SketchPalette.shade(9), pencil.reportedShade)
        val pen = pencil.withTool(SketchContract.TOOL_PEN)
        assertEquals(pencil.reportedShade, pen.reportedShade)
        // …which is exactly where it parts from `penColor`: that one is what the ARMED kind draws.
        assertEquals(SketchPalette.PEN_COLOR, pen.penColor)
    }

    @Test fun `the darkest lead reports its own tone, and so does every other offered level`() {
        assertEquals(0xFF111111.toInt(), SketchToolState.DEFAULT.withShade(1).reportedShade)
        // Distinct levels are distinct fills — the ARGB is the token both bars compare, so two
        // levels sharing one would leave a pick showing the tone before it.
        val tones = SketchPalette.SHADE_LEVELS.map { SketchToolState.DEFAULT.withShade(it).reportedShade }
        assertEquals(tones.size, tones.toSet().size)
    }

    @Test fun `a shade this build does not offer reports the default's tone, never a stray one`() {
        // A level of the ladder this build simply does not offer (0), and one off the ladder
        // entirely (15) — the stored number is the LEVEL, so both arrive the same way.
        listOf(0, 15).forEach { level ->
            val past = SketchToolState.of(SketchContract.TOOL_PENCIL, level, 0)
            assertEquals(SketchToolState.DEFAULT.reportedShade, past.reportedShade)
            // The fill and the ink agree: the button can never report a tone the pencil would not
            // draw.
            assertEquals(past.penColor, past.reportedShade)
        }
    }

    @Test fun `nothing remembered is the defaults, not a failure`() {
        assertEquals(SketchToolState.DEFAULT, SketchToolState.fromSettings(null))
    }

    @Test fun `a remembered state comes back exactly as it went out`() {
        val state = SketchToolState.of(SketchContract.TOOL_PEN, shadeLevel = 9, sizeIndex = 2)
        val settings = state.toSettings()
        assertEquals(SketchContract.TOOL_PEN, settings.tool)
        assertEquals(9, settings.shade)
        assertEquals(2, settings.size)
        assertEquals(state, SketchToolState.fromSettings(settings))
    }

    @Test fun `an unknown tool reads as the pencil`() {
        assertEquals(SketchContract.TOOL_PENCIL, SketchToolState.of(7, 5, 0).tool)
        assertEquals(SketchContract.TOOL_PENCIL, SketchToolState.of(-1, 5, 0).tool)
        val stranger = SketchToolSettings(SketchContract.MAX_TOOL_SETTING_INDEX, 5, 0)
        assertEquals(SketchContract.TOOL_PENCIL, SketchToolState.fromSettings(stranger).tool)
    }

    @Test fun `a dropped level reads as the default - and keeps the rest`() {
        // A ladder level this build does not offer: exactly what a device remembering one of the
        // nine the walk left out hands back, and what any stale level is.
        val dropped = 12
        val state = SketchToolState.fromSettings(SketchToolSettings(SketchContract.TOOL_PEN, dropped, 3))
        assertEquals(SketchPalette.DEFAULT_SHADE, state.shadeLevel)
        // Field by field: losing the shade must not lose the pen or the lead with it.
        assertTrue(state.isPen)
        assertEquals(3, state.sizeIndex)
    }

    @Test fun `a size index off the list reads as the default - and keeps the rest`() {
        val past = SketchPalette.SIZES_PX.size
        val state = SketchToolState.fromSettings(SketchToolSettings(SketchContract.TOOL_PENCIL, 9, past))
        assertEquals(SketchPalette.DEFAULT_SIZE, state.sizeIndex)
        assertEquals(9, state.shadeLevel)
    }

    @Test fun `the seam's sanity bound is survivable at both ends`() {
        val top = SketchToolSettings(
            SketchContract.MAX_TOOL_SETTING_INDEX,
            SketchContract.MAX_TOOL_SETTING_INDEX,
            SketchContract.MAX_TOOL_SETTING_INDEX,
        )
        assertEquals(SketchToolState.DEFAULT, SketchToolState.fromSettings(top))
        assertEquals(SketchToolState.DEFAULT, SketchToolState.of(-9, -9, -9))
    }

    @Test fun `every state a pick can make is a legal parcel`() {
        // `toSettings` hands three ints to a constructor that refuses anything outside the seam's
        // bound — so every reachable state must be inside it, at both ends of every list.
        val kinds = listOf(SketchContract.TOOL_PENCIL, SketchContract.TOOL_PEN)
        kinds.forEach { tool ->
            SketchPalette.SHADE_LEVELS.forEach { level ->
                SketchPalette.SIZES_PX.indices.forEach { index ->
                    val settings = SketchToolState.of(tool, level, index).toSettings()
                    assertEquals(tool, settings.tool)
                    assertEquals(level, settings.shade)
                    assertEquals(index, settings.size)
                }
            }
        }
    }
}

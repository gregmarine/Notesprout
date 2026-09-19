package com.symmetricalpalmtree.notesproutsn.ext.sketch

import com.symmetricalpalmtree.gpaper.core.model.StrokeStyle
import com.symmetricalpalmtree.notesproutsn.extension.SketchContract
import com.symmetricalpalmtree.notesproutsn.extension.SketchToolSettings

/**
 * What the sketch face's drawing tools are set to (arc 44 / T3, remade by arc 46 "Palette") — the
 * armed kind, the pencil's shade and the gel pen's shade, as **three indices** into
 * [SketchPalette]. It is the face's whole tool state and the only thing that crosses the seam as
 * `SketchToolSettings`.
 *
 * **One state, two kinds.** The pencil and the gel pen are both `Tool.PEN` to g-paper — they differ
 * only in the style, width and colour the engine is armed with — so "which pen is armed" is not
 * something the engine can be asked and has to live here, where the bar reads it
 * ([SketchToolbar]) and the buttons paint from it. **Each kind keeps its own shade** through a
 * switch to the other and back: picking the pen is not forgetting the pencil, and a grey chosen
 * for the pen is not the pencil's.
 *
 * **The eraser is not in here** and never will be. It is a tool the bar arms directly, it has no
 * settings of its own (the rubbing eraser's 12 px is the engine's), and a face that opened on the
 * eraser would read as a broken pencil — so it is never remembered (the seam says so too). That is
 * also why [tool] is always a pen kind: with the rubber armed, the palette panel edits the kind
 * that was last armed, which is exactly what this field holds.
 *
 * **Out of range is the default, never an exception** ([of]). A remembered level arrives from the
 * host as an integer the host never checked against a palette it does not know, and which levels a
 * build offers may change (T3's walk cut fifteen to six, decision 6 to four, arc 46 opened all
 * sixteen), so every number read from outside comes in through [of] or [fromSettings] and lands
 * on something legal. The primary constructor is for values that are already a palette's — a
 * `with…` off a legal state, or [of]'s own answer.
 */
data class SketchToolState(
    /** [SketchContract.TOOL_PENCIL] or [SketchContract.TOOL_PEN]. */
    val tool: Int,
    /** The pencil's grey, as a level of [SketchPalette]'s ladder. Kept while the pen is armed. */
    val pencilShade: Int,
    /** The gel pen's grey, as a level of the same ladder. Kept while the pencil is armed. */
    val penShade: Int,
) {

    /** Whether the **gel pen** is the armed kind rather than the pencil. */
    val isPen: Boolean get() = tool == SketchContract.TOOL_PEN

    /** What the armed kind draws with — graphite, or the uniform line of a pen. */
    val penStyle: StrokeStyle get() = if (isPen) StrokeStyle.PEN else StrokeStyle.PENCIL

    /** The armed kind's width in px: the pencil's one lead, or the gel pen's one width. */
    val penWidth: Float get() = if (isPen) SketchPalette.PEN_WIDTH_PX else SketchPalette.PENCIL_WIDTH_PX

    /** The armed kind's colour: its own chosen grey. */
    val penColor: Int get() = if (isPen) penReport else pencilReport

    /** The armed kind's shade, as a ladder level — what the palette panel paints as selected. */
    val armedShade: Int get() = if (isPen) penShade else pencilShade

    /**
     * The grey the **Pencil button** reports — the pencil's own shade, **whatever kind is armed**.
     *
     * A button says what a tap on it will bring back, so while the gel pen or the rubber is armed
     * the Pencil still carries the shade it is remembering. Reading it here rather than at the two
     * bars is also what keeps the fill honest across a changed ladder: [SketchPalette.shade] folds
     * a level this build does not have onto the default's grey, exactly as the engine's own colour
     * does, so the button can never report a tone the pencil would not draw.
     */
    val pencilReport: Int get() = SketchPalette.shade(pencilShade, SketchPalette.DEFAULT_SHADE)

    /** The grey the **Pen button** reports — the gel pen's own shade, whatever kind is armed
     *  ([pencilReport]'s rule, for the same reason). */
    val penReport: Int get() = SketchPalette.shade(penShade, SketchPalette.DEFAULT_PEN_SHADE)

    /** Arm a kind, keeping both shades — a switch is not a reset. */
    fun withTool(tool: Int): SketchToolState = of(tool, pencilShade, penShade)

    /** Pick a shade **for the armed kind** — the palette panel's one verb. The other kind's shade
     *  does not move. */
    fun withShade(level: Int): SketchToolState =
        if (isPen) of(tool, pencilShade, level) else of(tool, level, penShade)

    /** This state as the seam's parcel. Every field is already a legal level ([of]'s guarantee),
     *  which is what keeps it inside `SketchToolSettings`' own sanity bound. `size` is arc 44's
     *  dead slot on the wire — always 0 from this face. */
    fun toSettings(): SketchToolSettings =
        SketchToolSettings(tool = tool, shade = pencilShade, size = 0, penShade = penShade)

    companion object {

        /** The face with nothing remembered: the pencil at level 5, the pen at black. */
        val DEFAULT: SketchToolState = SketchToolState(
            tool = SketchContract.TOOL_PENCIL,
            pencilShade = SketchPalette.DEFAULT_SHADE,
            penShade = SketchPalette.DEFAULT_PEN_SHADE,
        )

        /**
         * A state from three numbers of unknown provenance: **each field falls back on its own
         * default**, independently. A remembered pen with a pencil shade this build no longer has
         * is still a remembered pen — losing the whole state over one stale level would throw away
         * two choices to fix one.
         *
         * An unrecognised tool reads as the pencil, which is the face's own first answer and what
         * the seam documents a stranger as.
         */
        fun of(tool: Int, pencilShade: Int, penShade: Int): SketchToolState = SketchToolState(
            tool = if (tool == SketchContract.TOOL_PEN) SketchContract.TOOL_PEN else SketchContract.TOOL_PENCIL,
            pencilShade = if (SketchPalette.isShade(pencilShade)) pencilShade else SketchPalette.DEFAULT_SHADE,
            penShade = if (SketchPalette.isShade(penShade)) penShade else SketchPalette.DEFAULT_PEN_SHADE,
        )

        /**
         * What the host remembered, read as a state — **null is not a failure**: it is the seam's
         * own word for "nothing has been remembered on this device yet" (a first showing, cleared
         * app data), and the defaults live here rather than in the host precisely so that answer can
         * be honest. The parcel's `size` is not read: there is nothing for it to name any more.
         */
        fun fromSettings(settings: SketchToolSettings?): SketchToolState =
            if (settings == null) DEFAULT else of(settings.tool, settings.shade, settings.penShade)
    }
}

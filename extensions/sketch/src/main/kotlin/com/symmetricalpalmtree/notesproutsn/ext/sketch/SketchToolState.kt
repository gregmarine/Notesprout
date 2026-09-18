package com.symmetricalpalmtree.notesproutsn.ext.sketch

import com.symmetricalpalmtree.gpaper.core.model.StrokeStyle
import com.symmetricalpalmtree.notesproutsn.extension.SketchContract
import com.symmetricalpalmtree.notesproutsn.extension.SketchToolSettings

/**
 * What the sketch face's drawing tools are set to (arc 44 / T3) — the armed kind, the pencil's
 * shade and the pencil's size, as **three indices** into [SketchPalette]. It is the face's whole
 * tool state and the only thing that crosses the seam as `SketchToolSettings`.
 *
 * **One state, two kinds.** The pencil and the gel pen are both `Tool.PEN` to g-paper — they differ
 * only in the style, width and colour the engine is armed with — so "which pen is armed" is not
 * something the engine can be asked and has to live here, where the bar reads it
 * ([SketchToolbar]) and the buttons paint from it. The shade and the size are kept **through** a
 * switch to the gel pen and back: picking the pen is not forgetting the pencil.
 *
 * **The eraser is not in here** and never will be. It is a tool the bar arms directly, it has no
 * settings of its own (the rubbing eraser's 12 px is the engine's), and a face that opened on the
 * eraser would read as a broken pencil — so it is never remembered (the seam says so too).
 *
 * **Out of range is the default, never an exception** ([of]). A remembered index arrives from the
 * host as an integer the host never checked against a palette it does not know, and which levels a
 * build offers may change (T3's walk cut the fifteen offered to six), so every number read from
 * outside comes in through [of] or [fromSettings] and lands on something legal. The primary
 * constructor is for values that are already a palette's — a `with…` off a legal state, or [of]'s
 * own answer.
 */
data class SketchToolState(
    /** [SketchContract.TOOL_PENCIL] or [SketchContract.TOOL_PEN]. */
    val tool: Int,
    /** The pencil's grey, as a level of [SketchPalette]'s ladder. Kept while the pen is armed. */
    val shadeLevel: Int,
    /** The pencil's lead, as an index into [SketchPalette.SIZES_PX]. Kept while the pen is armed. */
    val sizeIndex: Int,
) {

    /** Whether the **gel pen** is the armed kind rather than the pencil. */
    val isPen: Boolean get() = tool == SketchContract.TOOL_PEN

    /** What the armed kind draws with — graphite, or the uniform line of a pen. */
    val penStyle: StrokeStyle get() = if (isPen) StrokeStyle.PEN else StrokeStyle.PENCIL

    /** The armed kind's width in px: the pencil's chosen lead, or the gel pen's one width. */
    val penWidth: Float get() = if (isPen) SketchPalette.PEN_WIDTH_PX else SketchPalette.size(sizeIndex)

    /** The armed kind's colour: the pencil's chosen grey, or the gel pen's black. */
    val penColor: Int get() = if (isPen) SketchPalette.PEN_COLOR else SketchPalette.shade(shadeLevel)

    /**
     * The grey the **Pencil button** reports (arc 44 / T3) — the pencil's own shade, **whatever
     * kind is armed**, which is what makes it different from [penColor].
     *
     * A button says what a tap on it will bring back, so while the gel pen or the rubber is armed
     * the Pencil still carries the shade it is remembering. Reading it here rather than at the two
     * bars is also what keeps the fill honest across a shortened ladder: [SketchPalette.shade]
     * folds an index this build no longer has onto the default's grey, exactly as the engine's own
     * colour does, so the button can never report a tone the pencil would not draw.
     */
    val reportedShade: Int get() = SketchPalette.shade(shadeLevel)

    /** Arm a kind, keeping the pencil's shade and size — a switch is not a reset. */
    fun withTool(tool: Int): SketchToolState = of(tool, shadeLevel, sizeIndex)

    /** Pick a shade. Deliberately legal while the pen is armed: the shade belongs to the pencil,
     *  and nothing about the pen changes when it moves. */
    fun withShade(level: Int): SketchToolState = of(tool, level, sizeIndex)

    /** Pick a size — [withShade]'s rule, for the same reason. */
    fun withSize(index: Int): SketchToolState = of(tool, shadeLevel, index)

    /** This state as the seam's parcel. Every field is already a legal index ([of]'s guarantee),
     *  which is what keeps it inside `SketchToolSettings`' own sanity bound. */
    fun toSettings(): SketchToolSettings = SketchToolSettings(tool, shadeLevel, sizeIndex)

    companion object {

        /** The face with nothing remembered: the pencil, level 5, the finest lead. */
        val DEFAULT: SketchToolState = SketchToolState(
            tool = SketchContract.TOOL_PENCIL,
            shadeLevel = SketchPalette.DEFAULT_SHADE,
            sizeIndex = SketchPalette.DEFAULT_SIZE,
        )

        /**
         * A state from three numbers of unknown provenance: **each field falls back on its own
         * default**, independently. A remembered pen with a shade this build no longer has is still
         * a remembered pen — losing the whole state over one stale index would throw away two
         * choices to fix one.
         *
         * An unrecognised tool reads as the pencil, which is the face's own first answer and what
         * the seam documents a stranger as.
         */
        fun of(tool: Int, shadeLevel: Int, sizeIndex: Int): SketchToolState = SketchToolState(
            tool = if (tool == SketchContract.TOOL_PEN) SketchContract.TOOL_PEN else SketchContract.TOOL_PENCIL,
            shadeLevel = if (SketchPalette.isShade(shadeLevel)) shadeLevel else SketchPalette.DEFAULT_SHADE,
            sizeIndex = if (SketchPalette.isSize(sizeIndex)) sizeIndex else SketchPalette.DEFAULT_SIZE,
        )

        /**
         * What the host remembered, read as a state — **null is not a failure**: it is the seam's
         * own word for "nothing has been remembered on this device yet" (a first showing, cleared
         * app data), and the defaults live here rather than in the host precisely so that answer can
         * be honest.
         */
        fun fromSettings(settings: SketchToolSettings?): SketchToolState =
            if (settings == null) DEFAULT else of(settings.tool, settings.shade, settings.size)
    }
}

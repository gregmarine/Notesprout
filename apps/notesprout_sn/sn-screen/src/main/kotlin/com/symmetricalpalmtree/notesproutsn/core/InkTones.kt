package com.symmetricalpalmtree.notesproutsn.core

/**
 * **The sixteen greys** (arc 46 "Palette" for the sketch face; every writing face since arc 49 /
 * P4) — Atelier's tones, the one list every pen in the family draws from, and the reason it is here
 * in `:sn-screen` rather than in `:ext-sketch` where it was born: the notebook, the sticky editor,
 * the scratch pad and the calendar hang the same shade panel under their pen since P4, and a list
 * that lived in one extension would be a second copy in each of them (the `RattaNotebookView`
 * sibling-copy trap, one file at a time).
 *
 * **The coordinate system is a level** — a position in [TONES], darkest first, so that level 0 is
 * black and level 15 is white, the two names every build ever stored. What crosses a seam, sits in
 * a pref or rides an Intent is the level, never the ARGB: the host stores a small integer and never
 * learns what it names, so retuning a tone here strands nothing anywhere. Until arc 46 the ladder
 * was the e-paper's own `0x11` steps; Atelier's tones sit closer together in the dark half (`#505050`
 * … `#707070`) and spread out in the light, which is where shading actually lives.
 *
 * **Level 15 is white.** On the sketch pencil it is a lightener lead; on a gel pen and on the
 * writing pens it is a stroke that shows nothing over bare paper and covers what is under it. It
 * is offered because Atelier offers it, and a palette with a hole in it reads as broken.
 *
 * **All sixteen are offered** ([LEVELS]) — the one line that changes which shades exist. Every row
 * ([rows]), every bound and every "is that a shade?" test derives from it, so a device that
 * remembers a level from another build needs no migration: a level this build does not offer
 * already reads as the caller's fallback ([tone]). Four rows of four ([ROW_BREAK]), white first,
 * as Atelier lays them out.
 *
 * There is no Android here on purpose: the numbers and the rules that read them are the part
 * worth testing, and everything that draws them (`PaletteBar`, `ShadeIcon`) is the part that
 * cannot be. The sketch face's `SketchPalette` reads through this object and adds only what is
 * its own — its two widths and its two kinds' defaults.
 */
object InkTones {

    /**
     * Atelier's sixteen tones as opaque ARGB, **darkest first** — level *n* is `TONES[n]`. The
     * user's list of 2026-09-19, verbatim, reversed so that 0 is black and 15 is white.
     */
    val TONES: List<Int> = listOf(
        0x000000, 0x505050, 0x606060, 0x686868, 0x707070, 0x808080, 0x888888, 0x909090,
        0xA0A0A0, 0xAAAAAA, 0xB6B6B6, 0xC0C0C0, 0xC8C8C8, 0xD0D0D0, 0xDDDDDD, 0xFFFFFF,
    ).map { (0xFF shl 24) or it }

    /** The levels this build offers, in order — **the one line that changes which shades exist**. */
    val LEVELS: List<Int> = TONES.indices.toList()

    /** Level 0 — black: every writing pen's default, and the gel pen's. */
    const val BLACK: Int = 0

    /** Level 15 — white. */
    const val WHITE: Int = 15

    /** Where the swatches wrap: four rows of four, Atelier's grid. */
    const val ROW_BREAK: Int = 4

    /** Whether [level] names a shade **this** build offers. */
    fun isLevel(level: Int): Boolean = level in LEVELS

    /**
     * The opaque ARGB grey [level] names — **or [fallback]'s**, for anything this build does not
     * offer. That fallback is the whole reason a stored level is safe across a changed palette: a
     * remembered 200 against this build is an ordinary miss, never a crash and never a black page.
     * [fallback] is always [BLACK]'s level or another offered one; a caller passing something
     * else gets black, so the answer can never be off the ladder.
     */
    fun tone(level: Int, fallback: Int = BLACK): Int =
        TONES[if (isLevel(level)) level else if (isLevel(fallback)) fallback else BLACK]

    /**
     * A level as a level: [level] when this build offers it, else [fallback] — what a reader of a
     * pref or an Intent extra calls before it stores or arms anything. The same fold as [tone],
     * answered in the coordinate that crosses seams.
     */
    fun levelOrElse(level: Int, fallback: Int = BLACK): Int =
        if (isLevel(level)) level else if (isLevel(fallback)) fallback else BLACK

    /**
     * The swatch rows, in order, as the bar lays them out — **white first**, Atelier's own order
     * (its list runs `ffffff` … `000000`), [ROW_BREAK] to a row. Derived rather than written down,
     * so [LEVELS] is genuinely the only line to change.
     */
    fun rows(): List<List<Int>> = LEVELS.asReversed().chunked(ROW_BREAK)
}

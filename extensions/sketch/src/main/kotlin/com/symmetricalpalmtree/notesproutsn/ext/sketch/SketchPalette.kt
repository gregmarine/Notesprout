package com.symmetricalpalmtree.notesproutsn.ext.sketch

/**
 * The sketch face's palette (arc 44 / T3, remade by arc 46 "Palette") — **the one place the greys
 * and the two widths are written down**, and the reason the seam carries indices rather than values
 * (`SketchToolSettings`): the host stores small integers and never learns what they name, so
 * retuning a width or changing which shades are offered here strands nothing.
 *
 * **The coordinate system is the sixteen-level e-paper ladder**: `#000000` … `#FFFFFF` in `0x11`
 * steps, level *n* being the grey `n * 0x11` repeated across all three channels, always opaque. A
 * stored shade is **a level of that ladder, not a position in [SHADE_LEVELS]** — which is what makes
 * the level mean the same thing in every build, whichever subset a build happens to offer.
 *
 * **All sixteen are offered, to both kinds** ([SHADE_LEVELS] — the user's decision of 2026-09-19,
 * arc 46's decisions 2 and 3). Arc 44 offered six, then arc 45's decision 6 cut them to the
 * firmware's four tones, because the Ratta *needle* previewed one tone per arming and a lead that
 * previews a shade off is a lead the hand aims wrong with. Since g-paper 0.1.41 the Supernote
 * pencil — and since 0.1.43 the gel pen — go direct to the panel and the glass shows a blue-noise
 * dither of the true grey, so every level previews as it bakes and the four-tone limit no longer
 * applies. **Level 15 is white**: on the pencil it is a different kind of lead — it lays nothing on
 * bare paper under the `DARKEN` flatten and **pales the graphite under it** (the flecks go down
 * over the raster), the precision lightener the rubber does not give; on the gel pen it draws
 * nothing at all (white ink is invisible under `DARKEN`) yet still lands pixels in the ink raster —
 * offered by the user's word, documented in `docs/sketch.md` § Traps. Two rows of eight
 * ([shadeRows]).
 *
 * **Changing which shades are offered is [SHADE_LEVELS] and nothing else.** The rows ([shadeRows]),
 * the bar built from them and every range check derive from that one list, and a level this build
 * does not offer already reads as the kind's default ([shade] / [SketchToolState.of]) — so a device
 * that remembers a level from another build needs no migration and refuses no parcel.
 *
 * **There is no size choice** (arc 46's decision 1, withdrawing arc 44's twelve leads): the pencil
 * is one width, [PENCIL_WIDTH_PX], "the size of a real pencil"; the gel pen is [PEN_WIDTH_PX].
 *
 * There is no Android here on purpose: the numbers and the rules that read them are the part worth
 * testing, and everything that draws them ([PaletteBar]) is the part that cannot be.
 */
object SketchPalette {

    // ── The greys ──────

    /**
     * The ladder levels this build offers, in order — **the one line that changes which shades
     * exist**. Every row, every bound and every "is that a shade?" test below is derived from this
     * list, and nothing outside this file decides for itself what a shade is.
     *
     * The user's sixteen (2026-09-19): the whole ladder, black to white, for the pencil and the
     * gel pen alike.
     */
    val SHADE_LEVELS: List<Int> = (0..15).toList()

    /** The step between levels: `0x11`, so level *n* is the grey `n * 0x11` and level 15 is
     *  white. */
    private const val SHADE_STEP: Int = 0x11

    /** Level 0 — black, the gel pen's default and its hint's name. */
    const val BLACK_SHADE: Int = 0

    /** Level 15 — white: the pencil's lightener lead; on the pen, a stroke that shows nothing. */
    const val WHITE_SHADE: Int = 15

    /** Where the swatches wrap onto a second row: eight over eight. Eight 62 dp cells plus the
     *  bar's padding is ≈ 500 dp, inside the Nomad's 749 dp, and the 44 dp tier is narrower still. */
    const val ROW_BREAK: Int = 8

    /** The pencil's default shade: level 5, `#555555` — arc 43's one graphite tone rounded onto
     *  the ladder, which the hand called "spot on" at K1. */
    const val DEFAULT_SHADE: Int = 5

    /** The gel pen's default shade: black — what the pen always was before arc 46. */
    const val DEFAULT_PEN_SHADE: Int = BLACK_SHADE

    // ── The widths ──────

    /**
     * The pencil's one lead width in px (arc 46's decision 1): **4**, "the size of a real pencil".
     * Atelier's nib was never measured — its black HB reads back ~2.7 black px per px of stroke
     * length on the Nomad — so this is arc 44's third lead, walked against Atelier by hand; it is
     * the one knob if the lead reads heavy. Above g-paper's every floor (`GraphiteGrain`'s 1 px,
     * the EMR hairline 120) and far below its 96 px ceiling.
     */
    const val PENCIL_WIDTH_PX: Float = 4f

    /** The gel pen's width in px. Arc 44's decision 4 started it at the notebook pen's 3 px "until
     *  some testing"; the hand found that "a tad small" on T3's Nomad walk and asked for 7, then
     *  found 7 too heavy and settled on **5**. */
    const val PEN_WIDTH_PX: Float = 5f

    // ── Reading them ──────

    /** Whether [level] names a shade **this** build offers. */
    fun isShade(level: Int): Boolean = level in SHADE_LEVELS

    /**
     * The opaque ARGB grey [level] names — **or [fallback]'s**, for anything this build does not
     * offer. That fallback is the whole reason a stored ladder level is safe across a changed
     * palette: a remembered 200 against this build is an ordinary miss, never a crash and never a
     * black page. The fallback is the *kind's* default — the pencil's [DEFAULT_SHADE] unless the
     * caller says otherwise.
     */
    fun shade(level: Int, fallback: Int = DEFAULT_SHADE): Int =
        greyOf(if (isShade(level)) level else fallback)

    /**
     * The swatch rows, in order, as the bar lays them out: [ROW_BREAK] levels then the rest. Derived
     * rather than written down, so [SHADE_LEVELS] is genuinely the only line to change — at sixteen
     * offered levels this is two rows of eight.
     */
    fun shadeRows(): List<List<Int>> = SHADE_LEVELS.chunked(ROW_BREAK)

    /** Level *n* as an opaque grey: `n * 0x11` on all three channels. */
    private fun greyOf(level: Int): Int {
        val v = level * SHADE_STEP
        return (0xFF shl 24) or (v shl 16) or (v shl 8) or v
    }
}

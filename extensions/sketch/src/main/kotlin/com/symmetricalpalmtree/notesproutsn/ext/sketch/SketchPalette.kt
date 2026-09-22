package com.symmetricalpalmtree.notesproutsn.ext.sketch

/**
 * The sketch face's palette (arc 44 / T3, remade by arc 46 "Palette") — **the one place the greys
 * and the two widths are written down**, and the reason the seam carries indices rather than values
 * (`SketchToolSettings`): the host stores small integers and never learns what they name, so
 * retuning a width or changing which shades are offered here strands nothing.
 *
 * **The coordinate system is Atelier's sixteen tones** ([TONES] — the user's list of 2026-09-19,
 * arc 46's decision 2 as amended on the first walk: "the shades seem off from what Atelier has"),
 * kept **darkest first** so that level 0 is black and level 15 is white, the two names every
 * earlier build already stored. A stored shade is **a level — a position in [TONES]**, and
 * [SHADE_LEVELS] is the subset a build offers (all sixteen). Until arc 46 the ladder was the
 * e-paper's own `0x11` steps; Atelier's tones sit closer together in the dark half (`#505050` …
 * `#707070`) and spread out in the light, which is where a pencil's shading actually lives.
 *
 * **All sixteen are offered, to both kinds** (arc 46's decisions 2 and 3). Arc 44 offered six,
 * then arc 45's decision 6 cut them to the firmware's four tones, because the Ratta *needle*
 * previewed one tone per arming. Since g-paper 0.1.41 the Supernote pencil — and since 0.1.43 the
 * gel pen — go direct to the panel and the glass shows a blue-noise dither of the true grey, so
 * every tone previews as it bakes. **Level 15 is white**: on the pencil a different kind of lead
 * — it lays nothing on bare paper and **pales the graphite under it** (the flecks go down over the
 * raster), the precision lightener the rubber does not give; on the gel pen, since g-paper 0.1.44
 * flattens ink **over** graphite, it covers pencil and darker ink alike — "a white gel pen can
 * write over anything" — and shows nothing only over bare paper. Four rows of four
 * ([shadeRows]), white first as Atelier lays them out.
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
     * Atelier's sixteen tones as opaque ARGB, **darkest first** — level *n* is `TONES[n]`. The
     * user's list, verbatim, reversed so that 0 is black and 15 is white.
     */
    val TONES: List<Int> = listOf(
        0x000000, 0x505050, 0x606060, 0x686868, 0x707070, 0x808080, 0x888888, 0x909090,
        0xA0A0A0, 0xAAAAAA, 0xB6B6B6, 0xC0C0C0, 0xC8C8C8, 0xD0D0D0, 0xDDDDDD, 0xFFFFFF,
    ).map { (0xFF shl 24) or it }

    /**
     * The levels this build offers, in order — **the one line that changes which shades exist**.
     * Every row, every bound and every "is that a shade?" test below is derived from this list,
     * and nothing outside this file decides for itself what a shade is.
     *
     * The user's sixteen (2026-09-19): every tone, for the pencil and the gel pen alike.
     */
    val SHADE_LEVELS: List<Int> = TONES.indices.toList()

    /** Level 0 — black, the gel pen's default and its hint's name. */
    const val BLACK_SHADE: Int = 0

    /** Level 15 — white: the pencil's lightener lead; on the pen, a stroke that shows nothing. */
    const val WHITE_SHADE: Int = 15

    /** Where the swatches wrap: four rows of four, Atelier's grid (the user's word on the first
     *  arc 46 walk, over the two rows of eight it opened with). */
    const val ROW_BREAK: Int = 4

    /** The pencil's default shade: level 1, `#505050` — arc 43's one graphite tone (`#505050`
     *  exactly), which the hand called "spot on" at K1. */
    const val DEFAULT_SHADE: Int = 1

    /** The gel pen's default shade: black — what the pen always was before arc 46. */
    const val DEFAULT_PEN_SHADE: Int = BLACK_SHADE

    // ── The widths ──────

    /**
     * The pencil's one lead width in px (arc 46's decision 1): **1** since 2026-09-21 — the
     * user's trials after the flank came off (4 → 2 → 1 the same evening); **4** before that,
     * "the size of a real pencil". Atelier's nib was never measured — its black HB
     * reads back ~2.7 black px per px of stroke length on the Nomad — so this is walked against
     * Atelier by hand; it is the one knob if the lead reads heavy or thin. Above g-paper's every
     * floor (`GraphiteGrain`'s 1 px, the EMR hairline 120) and far below its 96 px ceiling. At
     * 1 px `GraphiteGrain.fleckPx` caps every fleck at the lead's width (1 px, the 0.75 px
     * floor under that), so the mark is a grained hairline: one lane of flecks.
     */
    const val PENCIL_WIDTH_PX: Float = 1f

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
     * The swatch rows, in order, as the bar lays them out — **white first**, Atelier's own order
     * (its list runs `ffffff` … `000000`), [ROW_BREAK] to a row. Derived rather than written down,
     * so [SHADE_LEVELS] is genuinely the only line to change — at sixteen offered levels this is
     * four rows of four, white at the top left and black at the bottom right.
     */
    fun shadeRows(): List<List<Int>> = SHADE_LEVELS.asReversed().chunked(ROW_BREAK)

    /** Level *n* as its opaque tone. */
    private fun greyOf(level: Int): Int = TONES[level]
}

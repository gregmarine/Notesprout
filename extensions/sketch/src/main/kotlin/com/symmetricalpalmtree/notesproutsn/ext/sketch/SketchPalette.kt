package com.symmetricalpalmtree.notesproutsn.ext.sketch

/**
 * The sketch face's pencil palette (arc 44 / T3) — **the one place the greys and the widths are
 * written down**, and the reason the seam carries indices rather than values
 * (`SketchToolSettings`): the host stores three small integers and never learns what they name, so
 * retuning a width or changing which shades are offered here strands nothing.
 *
 * **The coordinate system is the e-paper ladder without white** (decision 2): fifteen levels
 * `#000000` … `#EEEEEE` in `0x11` steps, level *n* being the grey `n * 0x11` repeated across all
 * three channels, always opaque. A stored `shade` is **a level of that ladder, not a position in
 * [SHADE_LEVELS]** — which is what makes the level mean the same thing in every build, whichever
 * subset a build happens to offer.
 *
 * **Six of the fifteen are offered** ([SHADE_LEVELS] — the user's decision on the Nomad walk of
 * 2026-09-17, amending decision 2's "fifteen"): levels **1, 3, 5, 7, 9 and 11**. The hand walked
 * all fifteen and found only 0, 5 and 9 (`#000000`, `#555555`, `#999999`) previewing *exactly* as
 * they bake — the rungs `RattaInkMap.pencilPreviewFor` names, BLACK / DARK_GRAY / GRAY; the
 * firmware paints one tone per arming and cannot be talked out of it. The list then moved twice on
 * the same walk, settling on **every other rung from 1 to 11** — pure black off the pencil
 * altogether (the gel pen is black), two leads in each firmware band. Every offered level
 * previews in its band's tone (0–2 → BLACK, 3–6 → DARK_GRAY, 7–14 → GRAY) while baking its own
 * grey. Level 5 stays the default, exactly as it was. One row of six ([shadeRows]).
 *
 * **Changing which shades are offered is [SHADE_LEVELS] and nothing else.** The rows ([shadeRows]),
 * the bar built from them and every range check derive from that one list, and a level this build
 * does not offer already reads as the default ([shade] / [SketchToolState.fromSettings]) — so a
 * device that remembers a level from another build needs no migration and refuses no parcel.
 *
 * **The sizes are px, not a ladder of names** (decision 3, as the walk of 2026-09-17 widened it):
 * twelve, 1.2 … 96, every one of them walked on the Nomad. They lay out as **two rows of six**
 * ([sizeRows]), under the one row of shades — three rows of six in all.
 *
 * **The gel pen is one line of this file** (decision 4): `StrokeStyle.PEN`, black, 5 px. One size,
 * one tone, no options, nothing to choose.
 *
 * There is no Android here on purpose: the numbers and the rules that read them are the part worth
 * testing, and everything that draws them ([PencilBar]) is the part that cannot be.
 */
object SketchPalette {

    // ── The greys ──────

    /**
     * The ladder levels this build offers, in order — **the one line that changes which shades
     * exist**. Every row, every bound and every "is that a shade?" test below is derived from this
     * list, and nothing outside this file decides for itself what a shade is.
     *
     * The user's six, from the walk of 2026-09-17: **1, 3, 5, 7, 9, 11**. 5 and 9 are rungs the
     * Ratta preview hits exactly (DARK_GRAY / GRAY); the rest preview in their band's tone (0–2
     * BLACK, 3–6 DARK_GRAY, 7–14 GRAY) while baking their own grey. No pure black: that is the
     * gel pen's.
     */
    val SHADE_LEVELS: List<Int> = listOf(1, 3, 5, 7, 9, 11)

    /** The step between levels: `0x11`, so level *n* is the grey `n * 0x11` and level 15 would be
     *  white — which is why the ladder stops one short of it. */
    private const val SHADE_STEP: Int = 0x11

    /** Where the swatches wrap onto a second row (decision 5: 8 over 7, when there were fifteen).
     *  At six offered levels this leaves a single row of six. */
    const val ROW_BREAK: Int = 8

    /** The default shade: level 5, `#555555` — arc 43's one graphite tone rounded onto the ladder,
     *  and `pencilPreviewFor`'s DARK_GRAY rung, which the hand called "spot on" at K1. */
    const val DEFAULT_SHADE: Int = 5

    // ── The sizes ──────

    /**
     * The pencil's twelve lead widths in px, finest first (decision 3, as the walk of 2026-09-17
     * widened it from five) — every one of them walked on the Nomad against a lifted firmware
     * ceiling.
     *
     * **The engine's bounds.** The bottom of the list sits on `RattaEmr`'s `PENCIL` hairline floor
     * 120; the top sits exactly on its ceiling, 9600 (= 96 px) since g-paper **Phase 24 → 0.1.37**
     * — raised from 1200 for this list, because the hand walked every lead above 12 px and each
     * previewed at the width it bakes. A width past the ceiling would still bake true but preview
     * clamped, which is why 96 is the last entry and not a round number beyond it.
     */
    val SIZES_PX: List<Float> =
        listOf(1.2f, 2f, 4f, 7f, 12f, 16f, 20f, 24f, 32f, 48f, 64f, 96f)

    /** Where the size dots wrap onto a second row — six, so the row is the shades' width. Six
     *  62 dp cells plus the bar's padding is ≈ 380 dp, well inside the Nomad's 749 dp, and the
     *  44 dp tier is narrower still. Twelve sizes then read as two rows of six. */
    const val SIZE_ROW_BREAK: Int = 6

    /** The default size: the finest, which is arc 43's only one. */
    const val DEFAULT_SIZE: Int = 0

    // ── The gel pen ──────

    /** The gel pen's width in px. Decision 4 started it at the notebook pen's 3 px "until some
     *  testing"; the hand found that "a tad small" on T3's Nomad walk and asked for 7, then found
     *  7 too heavy on the walk of 2026-09-17 and settled on **5**. */
    const val PEN_WIDTH_PX: Float = 5f

    /** The gel pen's one tone: opaque black. It is not on the ladder and never moves. */
    const val PEN_COLOR: Int = 0xFF000000.toInt()

    // ── Reading them ──────

    /** Whether [level] names a shade **this** build offers. */
    fun isShade(level: Int): Boolean = level in SHADE_LEVELS

    /**
     * The opaque ARGB grey [level] names — **or the default's**, for anything this build does not
     * offer. That fallback is the whole reason a stored ladder level is safe across a changed
     * palette: a remembered 12 against this six-shade build is an ordinary miss, never a crash
     * and never a black page.
     */
    fun shade(level: Int): Int = greyOf(if (isShade(level)) level else DEFAULT_SHADE)

    /** Whether [index] names a size of this build's list. */
    fun isSize(index: Int): Boolean = index in SIZES_PX.indices

    /** The width in px [index] names, or the default's — [shade]'s rule, for the same reason. */
    fun size(index: Int): Float = SIZES_PX[if (isSize(index)) index else DEFAULT_SIZE]

    /**
     * The swatch rows, in order, as the bar lays them out: [ROW_BREAK] levels then the rest. Derived
     * rather than written down, so [SHADE_LEVELS] is genuinely the only line to change — at six
     * offered levels this is a single row of six.
     */
    fun shadeRows(): List<List<Int>> = SHADE_LEVELS.chunked(ROW_BREAK)

    /**
     * The size rows, in order, as **indices** into [SIZES_PX]: [SIZE_ROW_BREAK] per row, then the
     * rest. [shadeRows]' shape and for its reason — twelve sizes read as two rows of six, and
     * lengthening the list is one line here too.
     */
    fun sizeRows(): List<List<Int>> = SIZES_PX.indices.toList().chunked(SIZE_ROW_BREAK)

    /** Level *n* as an opaque grey: `n * 0x11` on all three channels. */
    private fun greyOf(level: Int): Int {
        val v = level * SHADE_STEP
        return (0xFF shl 24) or (v shl 16) or (v shl 8) or v
    }
}

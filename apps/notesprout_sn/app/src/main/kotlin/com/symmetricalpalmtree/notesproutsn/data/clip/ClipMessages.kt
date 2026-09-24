package com.symmetricalpalmtree.notesproutsn.data.clip

import androidx.annotation.StringRes
import com.symmetricalpalmtree.notesproutsn.R
import com.symmetricalpalmtree.notesproutsn.data.soil.SoilSchema

/**
 * **What the screen says when a copy is refused** — [ExportMessages]' shape
 * ([com.symmetricalpalmtree.notesproutsn.export.ExportMessages]), for the one clipboard refusal
 * that has more than one sentence to choose between.
 *
 * Resource ids only, no `Context`: the screen still does the resolving, and the *choice* — which
 * is the part that could be got wrong quietly — is pure and therefore pinned by test rather than
 * by copying a page on the Nomad and reading the dialog.
 */
object ClipMessages {

    /**
     * The over-cap copy's sentence for a payload made of [rows] (arc 43 / K3; two raster types
     * since arc 45 / G2).
     *
     * A refused envelope carrying **either sketch raster** names the sketch — one sentence for
     * both, because the person did not draw a graphite raster and an ink raster, they drew a
     * picture. A page's sketch is measured in megabytes where everything else on a page is measured
     * in kilobytes, so it is almost always the reason [ClipEnvelope.MAX_BYTES] was passed, and
     * "there is too much on this page" over a page of ordinary ink and one drawing reads as a puzzle
     * rather than an explanation. The cap itself does not move — it is the cursor window, not a
     * preference.
     *
     * A **guide image** (arc 51 / J2) counts as the sketch here too: it is the other megabyte-sized
     * row a sketch page carries, and to the person it is part of the sketch page, not of the ink.
     */
    @StringRes
    fun tooLarge(rows: List<ClipRow>): Int =
        if (rows.any { it.type in SKETCH_PAGE_IMAGES }) {
            R.string.clip_too_large_sketch
        } else {
            R.string.clip_too_large
        }

    /** The row types whose presence makes an over-cap copy "the page's sketch". */
    private val SKETCH_PAGE_IMAGES = setOf(
        SoilSchema.TYPE_SKETCH_GRAPHITE,
        SoilSchema.TYPE_SKETCH_INK,
        SoilSchema.TYPE_GUIDE_IMAGE,
    )
}

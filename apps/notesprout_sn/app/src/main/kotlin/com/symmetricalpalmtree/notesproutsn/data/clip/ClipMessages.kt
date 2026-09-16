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
     * The over-cap copy's sentence for a payload made of [rows] (arc 43 / K3).
     *
     * A refused envelope carrying a **sketch** row names it. A page's sketch is a PNG measured in
     * megabytes where everything else on a page is measured in kilobytes, so it is almost always
     * the reason [ClipEnvelope.MAX_BYTES] was passed, and "there is too much on this page" over a
     * page of ordinary ink and one drawing reads as a puzzle rather than an explanation. The cap
     * itself does not move — it is the cursor window, not a preference.
     */
    @StringRes
    fun tooLarge(rows: List<ClipRow>): Int =
        if (rows.any { it.type == SoilSchema.TYPE_SKETCH }) R.string.clip_too_large_sketch
        else R.string.clip_too_large
}

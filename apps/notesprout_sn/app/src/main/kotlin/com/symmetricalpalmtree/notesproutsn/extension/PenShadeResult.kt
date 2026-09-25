package com.symmetricalpalmtree.notesproutsn.extension

import android.content.Intent
import com.symmetricalpalmtree.notesproutsn.core.InkTones

/**
 * The pen's shade an ink screen (the pad, the calendar) echoes on its result Intent (arc 49 / P4)
 * — [ExtensionContract.EXTRA_PEN_SHADE], [ChromeResult]'s shape for the second datum ever on this
 * seam's result.
 *
 * The same one rule: **present → its value, absent → null**, and the result code never enters into
 * it. A plain Back leaves with `RESULT_CANCELED` and still carries the level; a screen whose
 * process was killed under a live host hands back no data, and an extension that predates the
 * extra returns none either. Null means "the host writes nothing", never "black". A present value
 * this build's ladder does not have folds to black *here*, before it is stored: the host is what
 * arms a pen with it.
 */
object PenShadeResult {

    /** The level on [data], folded onto the ladder, or null when there is no data or no extra. */
    fun read(data: Intent?): Int? =
        decode(
            present = data?.hasExtra(ExtensionContract.EXTRA_PEN_SHADE) == true,
            value = data?.getIntExtra(ExtensionContract.EXTRA_PEN_SHADE, InkTones.BLACK) ?: InkTones.BLACK,
        )

    /** The rule under [read], free of `Intent` so it can be tested: [value] only when [present],
     *  and only ever a level this build offers. */
    fun decode(present: Boolean, value: Int): Int? =
        if (present) InkTones.levelOrElse(value, InkTones.BLACK) else null
}

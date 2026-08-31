package com.symmetricalpalmtree.notesproutsn.importing

import com.symmetricalpalmtree.notesproutsn.extension.ImporterContract

/**
 * What happens to the bytes **after** an importer has delivered them (arc 19 / M8) — the fork, as
 * pure rules so the decision itself is provable off-device.
 *
 * Delivery is identical for every importer: two fds, a verbatim stream, a byte count checked twice.
 * The descriptor's `resultKind` is the only thing that separates the two pipelines that follow, and
 * an unknown kind can never reach here (`ImporterInfo`'s constructor refuses one — unmarshal is
 * validation), so these are total.
 */
object ImportRouting {

    /** True when the delivered bytes are document text: decode, create a text document, open it.
     *  False is the arc-16 `.soil` pipeline — probe, unlock, re-key, manifest, placement. */
    fun isTextDocument(resultKind: Int): Boolean = resultKind == ImporterContract.RESULT_TEXT_DOCUMENT

    /**
     * Whether a **zero-byte delivery** is a refusal.
     *
     * For a notebook it is: no `.soil` is empty, and the arc-16 flow would otherwise carry nothing
     * all the way to a probe that was always going to reject it.
     *
     * For text it is not: an empty `.txt` is a legal file and imports as an empty text document —
     * which is precisely what an empty text file is. (Blank text writes no `document` row at all,
     * the repository's blank-means-absent rule, so the notebook lands genuinely empty rather than
     * holding an empty string.)
     */
    fun rejectsEmptyDelivery(resultKind: Int): Boolean = !isTextDocument(resultKind)
}

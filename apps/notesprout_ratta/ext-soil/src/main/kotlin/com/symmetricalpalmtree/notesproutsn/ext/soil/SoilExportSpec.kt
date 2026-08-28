package com.symmetricalpalmtree.notesproutsn.ext.soil

import com.symmetricalpalmtree.notesproutsn.extension.ExporterContract

/**
 * What this exporter will accept in an [com.symmetricalpalmtree.notesproutsn.extension.ExportSpec]
 * — pure, so it is JVM-tested rather than device-tested (arc 15 / E1).
 *
 * Two rules, and they point in opposite directions on purpose:
 *
 *  - **A keying value must be one this exporter declared.** `describe()` offers the full trio
 *    (E2), and a spec asking for anything else is a caller asking for work that was never offered
 *    — refused with an `IllegalArgumentException`, which is marshalable and so actually reaches
 *    the host (a non-marshalable exception kills the transaction silently and the host reads an
 *    empty reply as success). **Absent is fine**: no entry means the default, which is Keep. Every
 *    declared keying is the same work on this side of the seam — the host has already run the
 *    transform, and the extension streams whatever artifact it was handed.
 *  - **Unknown keys are ignored.** A newer host paired with this extension may send options a
 *    newer descriptor declared; nothing here is entitled to refuse an export over a key it simply
 *    does not know. The seam is forward-compatible in that direction and closed in the other.
 */
object SoilExportSpec {

    /** The full keying trio (E2). Host-executed, all three — this set is about honesty at the
     *  seam, not capability: a spec naming a keying that was never declared is still refused. */
    val SUPPORTED_KEYING: Set<String> = setOf(
        ExporterContract.KEYING_KEEP,
        ExporterContract.KEYING_REKEY,
        ExporterContract.KEYING_PLAIN,
    )

    /**
     * The keying the host asked for, defaulted and validated.
     *
     * @throws IllegalArgumentException if the spec names a keying this exporter never offered.
     */
    fun keying(values: Map<String, String>): String {
        val asked = values[ExporterContract.OPTION_KEYING] ?: return ExporterContract.KEYING_KEEP
        require(asked in SUPPORTED_KEYING) { "keying '$asked' is not offered by this exporter" }
        return asked
    }
}

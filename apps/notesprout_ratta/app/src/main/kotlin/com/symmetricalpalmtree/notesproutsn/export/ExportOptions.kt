package com.symmetricalpalmtree.notesproutsn.export

import com.symmetricalpalmtree.notesproutsn.extension.ExporterContract
import com.symmetricalpalmtree.notesproutsn.extension.ExporterInfo
import com.symmetricalpalmtree.notesproutsn.extension.OptionDescriptor

/**
 * The host's half of the **declarative options seam** (arc 15 / E1), as rules rather than widgets:
 * which descriptors this build can draw, what the panel starts out showing, and what actually
 * crosses to `export()`. Pure and JVM-tested — the screen keeps the views, this keeps the answers.
 *
 * Three rules, and each one is a decision:
 *
 *  - **Renderable or dropped.** A descriptor the host cannot draw takes its exporter out of the
 *    list with a log line, never a crash (the inward-is-untrusted rule). In E1 the one unrenderable
 *    kind is [ExporterContract.KIND_PASSPHRASE] — E2 builds those fields — so an exporter that
 *    declares one simply is not offered yet. **GONE, never disabled**, applied to a whole exporter.
 *  - **The chosen value is always a declared one.** [specValues] re-checks every value against the
 *    descriptor that asked for it and falls back to that option's default. A panel cannot send a
 *    choice the exporter never offered, whatever the screen's state got up to (a restored instance
 *    state, a descriptor that changed under a resume).
 *  - **A passphrase option never appears in the spec at all.** Not empty, not blank — absent. The
 *    host collects and consumes the secret itself; the contract says so and this is where the host
 *    keeps its word.
 */
object ExportOptions {

    /** True when every option this exporter declares is one the host can draw today. */
    fun isRenderable(info: ExporterInfo): Boolean =
        info.options.none { it.kind == ExporterContract.KIND_PASSPHRASE }

    /**
     * The spec map for [info] given the panel's [chosen] values — declaration order, one entry per
     * renderable option, every value validated against its own descriptor.
     *
     * It is also what the **panel** holds, not just what crosses the wire: passing the panel's own
     * map back through here after every descriptor change is what keeps the two from ever
     * disagreeing — a value the screen shows as unselected can then never be the value that is
     * sent. With an empty [chosen] it is simply the declared defaults.
     */
    fun specValues(info: ExporterInfo, chosen: Map<String, String>): Map<String, String> {
        val out = LinkedHashMap<String, String>(info.options.size)
        for (d in info.options) {
            val value = when (d.kind) {
                ExporterContract.KIND_SINGLE_CHOICE ->
                    chosen[d.id]?.takeIf { it in d.choiceIds } ?: d.defaultValue
                ExporterContract.KIND_TOGGLE ->
                    chosen[d.id]?.takeIf { it == "0" || it == "1" } ?: d.defaultValue
                // Passphrase: the host consumed the secret. Nothing crosses — not even a key.
                else -> null
            }
            if (value != null) out[d.id] = value
        }
        return out
    }

    /**
     * True when this option is shown as a fixed line rather than a control: a single-choice option
     * with exactly one choice is not a choice, and a radio for it would be a control that cannot be
     * operated. Same principle as the chooser collapsing to a label when one exporter is installed.
     */
    fun isFixed(d: OptionDescriptor): Boolean =
        d.kind == ExporterContract.KIND_SINGLE_CHOICE && d.choiceIds.size == 1

    /** The label to show for [d]'s current [value] — the choice's own label, or the id as a last
     *  resort (an id is always non-empty, so a fixed row can never render blank). */
    fun choiceLabel(d: OptionDescriptor, value: String): String {
        val i = d.choiceIds.indexOf(value)
        return if (i >= 0) d.choiceLabels[i] else value
    }
}

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
 *    list with a log line, never a crash (the inward-is-untrusted rule). The one unrenderable kind
 *    is a free-standing [ExporterContract.KIND_PASSPHRASE]: a passphrase option exists to ask the
 *    host for a host-executed step, and the one such step the host implements (E2) is the reserved
 *    keying option's *rekey* choice — whose fields the host shows on its own recognizance, no
 *    descriptor kind involved. A passphrase kind with no host-executed meaning stays undrawable
 *    until an arc gives it one. **GONE, never disabled**, applied to a whole exporter.
 *  - **The chosen value is always a declared one.** [specValues] re-checks every value against the
 *    descriptor that asked for it and falls back to that option's default. A panel cannot send a
 *    choice the exporter never offered, whatever the screen's state got up to (a restored instance
 *    state, a descriptor that changed under a resume).
 *  - **A passphrase option never appears in the spec at all.** Not empty, not blank — absent. The
 *    host collects and consumes the secret itself; the contract says so and this is where the host
 *    keeps its word.
 */
object ExportOptions {

    /** True when every option this exporter declares is one the host can draw today — and, for the
     *  reserved keying option, one the host can *execute*: keying is host-executed, so a choice id
     *  outside the known trio is a step the host has no transform for, and it takes its exporter
     *  out of the list the same way an undrawable kind does. Without this check the unknown value
     *  would surface only at export time, as [com.symmetricalpalmtree.notesproutsn.crypto.ExportKeying.plan]'s
     *  IllegalArgumentException — which the flow reads as the *passphrase-lost* state and explains
     *  wrongly (arc-15 review). */
    fun isRenderable(info: ExporterInfo): Boolean {
        if (info.options.any { it.kind == ExporterContract.KIND_PASSPHRASE }) return false
        val keying = info.options.firstOrNull {
            it.id == ExporterContract.OPTION_KEYING && it.kind == ExporterContract.KIND_SINGLE_CHOICE
        } ?: return true
        return keying.choiceIds.all {
            it == ExporterContract.KEYING_KEEP ||
                it == ExporterContract.KEYING_REKEY ||
                it == ExporterContract.KEYING_PLAIN
        }
    }

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

    // ── The reserved keying option (arc 15 / E2 — recognized by id, executed by the host) ──

    /**
     * The armed keying value, or null when this exporter never declared the reserved option.
     * Validated the same way [specValues] validates — a stale or foreign value falls back to the
     * declared default — so what this answers is always exactly what the spec would carry.
     */
    fun keying(info: ExporterInfo, chosen: Map<String, String>): String? {
        val d = info.options.firstOrNull {
            it.id == ExporterContract.OPTION_KEYING && it.kind == ExporterContract.KIND_SINGLE_CHOICE
        } ?: return null
        return chosen[d.id]?.takeIf { it in d.choiceIds } ?: d.defaultValue
    }

    /** True while *New passphrase…* is armed — the host shows its own passphrase + confirm
     *  fields. The typed secret is consumed host-side and never enters the spec. */
    fun needsPassphrase(info: ExporterInfo, chosen: Map<String, String>): Boolean =
        keying(info, chosen) == ExporterContract.KEYING_REKEY

    /** True while *Remove encryption* is armed — the inline plain warning is on screen
     *  (og's pattern: a plain inkBlack line, no popup, no extra tap). */
    fun showsPlainWarning(info: ExporterInfo, chosen: Map<String, String>): Boolean =
        keying(info, chosen) == ExporterContract.KEYING_PLAIN
}

package com.symmetricalpalmtree.notesproutsn.ext.pdf

/**
 * What this exporter will accept in an [com.symmetricalpalmtree.notesproutsn.extension.ExportSpec]
 * — pure, so it is JVM-tested rather than device-tested (arc 18 / D1).
 *
 * **An option this build cannot act on is refused, not ignored** — the opposite of the soil
 * exporter's forward-compat rule, and deliberately so. There, every declared option was
 * *host-executed*: the transform had already run and an unknown key changed nothing the extension
 * did. Here an option **is work this side must do** (render the paper under the ink; protect the
 * output), so ignoring one would hand back a PDF that is not the one the user asked for and report
 * it as a success. D1 declares no options, so [SUPPORTED_OPTIONS] is empty and any entry is a
 * mismatch; D2 lands the pair here and in the descriptor together — a control exists in both places
 * or in neither.
 *
 * The refusal is an [IllegalArgumentException] because that is one of the three shapes that
 * actually reach the host; a non-marshalable exception kills the transaction silently and the host
 * reads an empty reply as success.
 */
internal object PdfExportSpec {

    /** Option ids this build can act on. D2's page-template + password toggles land here. */
    val SUPPORTED_OPTIONS: Set<String> = emptySet()

    /**
     * @throws IllegalArgumentException if the spec asks for an option this build never declared, or
     *   carries an export secret this build cannot serve (the password path is D2). Neither message
     *   ever echoes a secret — an option id is an id, a secret is never named, quoted or measured.
     */
    fun require(values: Map<String, String>, exportSecret: String?) {
        val unknown = values.keys.filter { it !in SUPPORTED_OPTIONS }.sorted()
        require(unknown.isEmpty()) {
            "options not offered by this exporter: ${unknown.joinToString(", ")}"
        }
        require(exportSecret == null) { "this exporter cannot serve an export secret" }
    }
}

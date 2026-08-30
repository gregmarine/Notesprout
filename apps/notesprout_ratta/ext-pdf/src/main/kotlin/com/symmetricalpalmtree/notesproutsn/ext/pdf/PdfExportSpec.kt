package com.symmetricalpalmtree.notesproutsn.ext.pdf

import com.symmetricalpalmtree.notesproutsn.extension.ExporterContract

/**
 * What this exporter will accept in an [com.symmetricalpalmtree.notesproutsn.extension.ExportSpec]
 * — pure, so it is JVM-tested rather than device-tested (arc 18 / D1, grown in D2).
 *
 * **An option this build cannot act on is refused, not ignored** — the opposite of the soil
 * exporter's forward-compat rule, and deliberately so. There, every declared option was
 * *host-executed*: the transform had already run and an unknown key changed nothing the extension
 * did. Here an option **is work someone must do** (render the paper under the ink; protect the
 * output), so ignoring one would hand back a PDF that is not the one the user asked for and report
 * it as a success. [SUPPORTED_OPTIONS] is therefore [PdfDescriptor]'s ids exactly — a control exists
 * in both places or in neither.
 *
 * The D2 rule is **consistency**, not absence: the protect toggle and the export secret are one
 * answer arriving in two pieces, and either piece without the other is a delivery that lost half of
 * itself. Armed with no secret would silently write an unprotected PDF the user believes is locked;
 * a secret with the toggle off would lock a file the user never asked to lock, and neither of those
 * may be quietly resolved this side. (The page-template toggle needs no such check: the host
 * executed it before the bundle existed, and its value arrives here only so this side can see what
 * was asked.)
 *
 * Every refusal is an [IllegalArgumentException] because that is one of the three shapes that
 * actually reach the host; a non-marshalable exception kills the transaction silently and the host
 * reads an empty reply as success. **No message ever names, quotes or measures the secret** — not
 * its length, not whether it was blank: an id is the contract, a secret is never anything but
 * present or absent.
 */
internal object PdfExportSpec {

    /** Option ids this build can act on — [PdfDescriptor]'s pair, and nothing else. */
    val SUPPORTED_OPTIONS: Set<String> = setOf(
        ExporterContract.OPTION_PAGE_TEMPLATE,
        ExporterContract.OPTION_PROTECT,
    )

    /**
     * @throws IllegalArgumentException if the spec asks for an option this exporter never declared,
     *   or if the protect toggle and the export secret disagree about whether this PDF is being
     *   protected.
     */
    fun require(values: Map<String, String>, exportSecret: String?) {
        val unknown = values.keys.filter { it !in SUPPORTED_OPTIONS }.sorted()
        require(unknown.isEmpty()) {
            "options not offered by this exporter: ${unknown.joinToString(", ")}"
        }
        if (values[ExporterContract.OPTION_PROTECT] == "1") {
            require(exportSecret != null) { "password-protect is armed but no export secret arrived" }
        } else {
            require(exportSecret == null) { "an export secret arrived that nothing asked for" }
        }
    }
}

package com.symmetricalpalmtree.notesproutsn.ext.pdf

import com.symmetricalpalmtree.notesproutsn.extension.ExporterContract
import com.symmetricalpalmtree.notesproutsn.extension.ExporterInfo
import com.symmetricalpalmtree.notesproutsn.extension.OptionDescriptor

/**
 * What this exporter answers `describe()` with — lifted out of the service so the shape is pinned
 * by a JVM test rather than by a device walk. Nothing here touches Android: the parcelables validate
 * in their constructors and are only *written* to a `Parcel` later, so a malformed descriptor fails
 * in this module's own tests instead of dropping the exporter silently on a Supernote.
 *
 * **The pair, and nothing else** (arc 18 / D2). Two toggles, and each is here because the answer
 * has to reach a different side of the seam:
 *
 *  - [ExporterContract.OPTION_PAGE_TEMPLATE] is declared here and **executed by the host**. The
 *    bundle carries finished pixels, so paper is either baked into the page or was never in it;
 *    there is nothing this side could add or strip afterwards. Default `"1"` — the page as written.
 *  - [ExporterContract.OPTION_PROTECT] is declared here and **executed here**. Arming it makes the
 *    host collect a password with its own dual masked fields and send it on the spec's export-secret
 *    carrier; this process encrypts with it and drops it. Default `"0"` — a password is a thing a
 *    user asks for, never a thing that happens to them.
 *
 * The labels are the exporter's own words, which is the point of a declarative descriptor: the host
 * draws them with its e-ink widgets and never has to know what they mean.
 */
internal object PdfDescriptor {

    val options: List<OptionDescriptor> = listOf(
        OptionDescriptor(
            id = ExporterContract.OPTION_PAGE_TEMPLATE,
            label = "Include page template",
            kind = ExporterContract.KIND_TOGGLE,
            choiceIds = emptyList(),
            choiceLabels = emptyList(),
            defaultValue = "1",
        ),
        OptionDescriptor(
            id = ExporterContract.OPTION_PROTECT,
            label = "Password-protect",
            kind = ExporterContract.KIND_TOGGLE,
            choiceIds = emptyList(),
            choiceLabels = emptyList(),
            defaultValue = "0",
        ),
    )

    fun info(): ExporterInfo = ExporterInfo(
        formatLabel = "PDF document",
        fileExtension = "pdf",
        mimeType = "application/pdf",
        options = options,
        sourceKind = ExporterContract.SOURCE_PAGES,
    )
}

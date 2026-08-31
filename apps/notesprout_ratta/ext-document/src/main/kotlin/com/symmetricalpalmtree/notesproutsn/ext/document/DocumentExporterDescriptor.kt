package com.symmetricalpalmtree.notesproutsn.ext.document

import com.symmetricalpalmtree.notesproutsn.extension.ExporterContract
import com.symmetricalpalmtree.notesproutsn.extension.ExporterInfo
import com.symmetricalpalmtree.notesproutsn.extension.OptionDescriptor

/**
 * What [DocumentExporterService] answers `describe()` with — lifted out of the service so the shape
 * is pinned by a JVM test rather than by a device walk. Nothing here touches Android: the
 * parcelables validate in their constructors and are only *written* to a `Parcel` later, so a
 * malformed descriptor fails in this module's own tests instead of dropping the exporter silently on
 * a Supernote.
 *
 * **One option, and it is executed by the host — twice over** (arc 19 / M9).
 * [ExporterContract.OPTION_TEXT_FORMAT] is declared here because the user has to be asked, and it is
 * answered entirely on the other side of the seam:
 *
 *  - it decides what the host **assembles** into the read fd — Markdown verbatim, or the
 *    `:markdown` engine's plain-text strip; and
 *  - it renames the **destination** — the suggested filename's extension and the picker's MIME type
 *    follow the choice, which is why this descriptor's own [ExporterInfo.fileExtension] /
 *    [ExporterInfo.mimeType] are only the Markdown defaults, not the last word.
 *
 * The chosen value still crosses in the spec map like any single-choice, so this side learns what
 * was asked — but by then there is nothing left for it to do about it: the bytes on the read fd are
 * already final, and this service streams them verbatim.
 *
 * The labels are the exporter's own words, which is the point of a declarative descriptor: the host
 * draws them with its e-ink widgets and never has to know what they mean.
 */
internal object DocumentExporterDescriptor {

    val options: List<OptionDescriptor> = listOf(
        OptionDescriptor(
            id = ExporterContract.OPTION_TEXT_FORMAT,
            label = "Format",
            kind = ExporterContract.KIND_SINGLE_CHOICE,
            choiceIds = listOf(
                ExporterContract.TEXT_FORMAT_MARKDOWN,
                ExporterContract.TEXT_FORMAT_PLAIN,
            ),
            choiceLabels = listOf(
                "Markdown (.md)",
                "Plain text (.txt)",
            ),
            // The document *is* Markdown; stripping it is the deliberate second choice.
            defaultValue = ExporterContract.TEXT_FORMAT_MARKDOWN,
        ),
    )

    fun info(): ExporterInfo = ExporterInfo(
        formatLabel = "Markdown / text document",
        fileExtension = "md",
        mimeType = "text/markdown",
        options = options,
        sourceKind = ExporterContract.SOURCE_DOCUMENT,
    )
}

package com.symmetricalpalmtree.notesproutsn.export

import com.symmetricalpalmtree.notesproutsn.extension.ExporterContract
import com.symmetricalpalmtree.notesproutsn.extension.ExporterInfo
import com.symmetricalpalmtree.notesproutsn.markdown.MarkdownText

/**
 * The document export's decision core (arc 19 / M9) — pure, so every rule the two new export
 * shapes stand on is pinned by JVM test rather than by a device walk:
 *
 *  - **What the document of a notebook *is*** ([assemble]): the notebook document when one exists,
 *    else the per-page documents in page order joined by blank lines, undocumented pages skipped —
 *    and **never recognition** (export never recognizes; a notebook with no document assembles to
 *    null, which the callers surface as their own honest refusals). The join is the M7 merge join
 *    on purpose — `"\n\n"` between parts, parts untrimmed, the whole trimmed — so the text export
 *    of a never-merged notebook reads exactly like the merge the editor would have shown.
 *  - **What the stream holds** ([finalText]): the reserved [ExporterContract.OPTION_TEXT_FORMAT]
 *    choice, executed host-side — Markdown verbatim, or the `:markdown` engine's plain-text strip —
 *    and null when that leaves nothing, so the "honest refusal, never an empty file" rule survives
 *    the strip as well as the assembly. The extension receives *final* bytes and streams them
 *    verbatim, which is what lets [ExportVerification] hold [ExporterContract.SOURCE_DOCUMENT] to
 *    the soil equality.
 *  - **What the destination is called** ([fileExtension] / [mimeType]): the same choice, again
 *    host-executed — a `.txt` export must not be offered to the picker as `text/markdown` with a
 *    `.md` name. Every other source kind keeps its descriptor's own answers.
 *  - **What the screen shows** ([listed] / [sourceRowVisible]): a [ExporterContract.SOURCE_DOCUMENT]
 *    exporter is listed only when the notebook has a document at all (a chooser row that can only
 *    refuse is a tap that does nothing), and the host-owned Source row (Notebook pages / Document)
 *    exists only under a [ExporterContract.SOURCE_PAGES] exporter with a document to offer —
 *    GONE otherwise, never disabled (the family rule).
 */
object ExportDocumentRules {

    /**
     * The notebook's document as one Markdown text, or null when it has none. [notebookDoc] is the
     * stored notebook document (null when absent **or blank** — the repository's blank-means-absent
     * rule, honoured here too so a foreign-written blank row cannot shadow the pages);
     * [pageDocs] each page's document text in page order, null/blank for an undocumented page.
     */
    fun assemble(notebookDoc: String?, pageDocs: List<String?>): String? {
        if (!notebookDoc.isNullOrBlank()) return notebookDoc
        val parts = pageDocs.filter { !it.isNullOrBlank() }.filterNotNull()
        if (parts.isEmpty()) return null
        return parts.joinToString("\n\n").trim()
    }

    /** The bytes-to-stream for [markdown] under the armed format choice — verbatim, or the shared
     *  engine's strip (og's `toPlainText`, ported). Any unknown value reads as Markdown, but an
     *  unknown value cannot be armed: [ExportOptions.isRenderable] dropped its exporter.
     *
     *  **Null when the strip leaves nothing** — a document of pure syntax (`---` alone) is a
     *  document in Markdown and no text file at all, and the honest answer is the same refusal
     *  [assemble] gives, never a 0-byte file under a success dialog. The Markdown branch cannot
     *  answer null: [assemble] trims and returns null rather than blank. */
    fun finalText(markdown: String, format: String): String? =
        if (format == ExporterContract.TEXT_FORMAT_PLAIN) {
            MarkdownText.toPlainText(markdown).takeIf { it.isNotBlank() }
        } else {
            markdown.takeIf { it.isNotBlank() }
        }

    /** The suggested filename's extension — the armed format's for a document exporter, the
     *  descriptor's own for everything else. */
    fun fileExtension(info: ExporterInfo, chosen: Map<String, String>): String =
        if (info.sourceKind != ExporterContract.SOURCE_DOCUMENT) info.fileExtension
        else when (ExportOptions.textFormat(info, chosen)) {
            ExporterContract.TEXT_FORMAT_PLAIN -> "txt"
            else -> "md"
        }

    /** The picker's MIME type — same rule as [fileExtension]. */
    fun mimeType(info: ExporterInfo, chosen: Map<String, String>): String =
        if (info.sourceKind != ExporterContract.SOURCE_DOCUMENT) info.mimeType
        else when (ExportOptions.textFormat(info, chosen)) {
            ExporterContract.TEXT_FORMAT_PLAIN -> "text/plain"
            else -> "text/markdown"
        }

    /** Whether an exporter of [sourceKind] belongs in the chooser at all: a document exporter is
     *  listed only when the notebook [hasDocument] — the host's assembly would refuse anyway, and
     *  the chooser must not offer a format that can only fail. */
    fun listed(sourceKind: Int, hasDocument: Boolean): Boolean =
        sourceKind != ExporterContract.SOURCE_DOCUMENT || hasDocument

    /** Whether the host-owned Source row (Notebook pages / Document) is on screen: only under a
     *  [ExporterContract.SOURCE_PAGES] exporter, and only when there is a document to offer. */
    fun sourceRowVisible(hasDocument: Boolean, sourceKind: Int): Boolean =
        hasDocument && sourceKind == ExporterContract.SOURCE_PAGES
}

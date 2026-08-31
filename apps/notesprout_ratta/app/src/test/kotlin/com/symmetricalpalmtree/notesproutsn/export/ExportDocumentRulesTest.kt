package com.symmetricalpalmtree.notesproutsn.export

import com.symmetricalpalmtree.notesproutsn.extension.ExporterContract
import com.symmetricalpalmtree.notesproutsn.extension.ExporterInfo
import com.symmetricalpalmtree.notesproutsn.extension.OptionDescriptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The document export's decision core, pinned (arc 19 / M9): the assembly (notebook document
 * first, else the M7 merge join over page documents — never recognition), the host-executed
 * format strip, the per-choice destination naming, and the two visibility tables the Export
 * screen stands on.
 */
class ExportDocumentRulesTest {

    // ── assemble ──────────────────────────────────────────────────────────────

    @Test
    fun theNotebookDocumentWinsWholeWhenItExists() {
        assertEquals("final draft", ExportDocumentRules.assemble("final draft", listOf("page one", "page two")))
    }

    @Test
    fun aBlankNotebookDocumentReadsAsAbsent() {
        // The repository's blank-means-absent rule, honoured here too: a foreign-written blank row
        // must not shadow the pages.
        assertEquals("page one", ExportDocumentRules.assemble("   \n", listOf("page one")))
    }

    @Test
    fun pageDocumentsJoinWithOneBlankLineInPageOrderSkippingUndocumentedPages() {
        assertEquals(
            "page one\n\npage three",
            ExportDocumentRules.assemble(null, listOf("page one", null, "page three")),
        )
        assertEquals(
            "a\n\nb",
            ExportDocumentRules.assemble(null, listOf("a", "  ", "b")),
        )
    }

    @Test
    fun partsAreNotTrimmedButTheWholeIs() {
        // The M7 merge-join lock, og-verbatim: "\n\n" between parts, parts untrimmed, whole
        // trimmed — a trailing newline inside a part survives into the join.
        assertEquals(
            "a\n\n\nb",
            ExportDocumentRules.assemble(null, listOf("a\n", "b")),
        )
    }

    @Test
    fun nothingToAssembleIsNullNeverEmpty() {
        assertNull(ExportDocumentRules.assemble(null, emptyList()))
        assertNull(ExportDocumentRules.assemble(null, listOf(null, "", "   ")))
        assertNull(ExportDocumentRules.assemble("", emptyList()))
    }

    // ── finalText ─────────────────────────────────────────────────────────────

    @Test
    fun markdownStreamsVerbatimAndPlainStrips() {
        val md = "# Title\n\nSome **bold** text."
        assertEquals(md, ExportDocumentRules.finalText(md, ExporterContract.TEXT_FORMAT_MARKDOWN))
        // The strip itself is MarkdownText's and pinned in :markdown — one case here proves the
        // routing, not the grammar.
        assertEquals("Title\n\nSome bold text.", ExportDocumentRules.finalText(md, ExporterContract.TEXT_FORMAT_PLAIN))
    }

    // ── destination naming ────────────────────────────────────────────────────

    private fun documentInfo(vararg options: OptionDescriptor) =
        ExporterInfo(
            "Markdown / text document", "md", "text/markdown", options.toList(),
            ExporterContract.SOURCE_DOCUMENT,
        )

    private val textFormat = OptionDescriptor(
        ExporterContract.OPTION_TEXT_FORMAT, "Format", ExporterContract.KIND_SINGLE_CHOICE,
        listOf(ExporterContract.TEXT_FORMAT_MARKDOWN, ExporterContract.TEXT_FORMAT_PLAIN),
        listOf("Markdown (.md)", "Plain text (.txt)"),
        ExporterContract.TEXT_FORMAT_MARKDOWN,
    )

    @Test
    fun theDestinationNameFollowsTheArmedChoice() {
        val i = documentInfo(textFormat)
        assertEquals("md", ExportDocumentRules.fileExtension(i, emptyMap()))
        assertEquals("text/markdown", ExportDocumentRules.mimeType(i, emptyMap()))
        val txt = mapOf(ExporterContract.OPTION_TEXT_FORMAT to ExporterContract.TEXT_FORMAT_PLAIN)
        assertEquals("txt", ExportDocumentRules.fileExtension(i, txt))
        assertEquals("text/plain", ExportDocumentRules.mimeType(i, txt))
    }

    @Test
    fun otherSourceKindsKeepTheirDescriptorsOwnName() {
        val soil = ExporterInfo("Notesprout notebook", "soil", "application/octet-stream", emptyList())
        assertEquals("soil", ExportDocumentRules.fileExtension(soil, emptyMap()))
        assertEquals("application/octet-stream", ExportDocumentRules.mimeType(soil, emptyMap()))
        val pdf = ExporterInfo("PDF document", "pdf", "application/pdf", emptyList(), ExporterContract.SOURCE_PAGES)
        assertEquals("pdf", ExportDocumentRules.fileExtension(pdf, emptyMap()))
        assertEquals("application/pdf", ExportDocumentRules.mimeType(pdf, emptyMap()))
    }

    // ── the two visibility tables ─────────────────────────────────────────────

    @Test
    fun aDocumentExporterIsListedOnlyWhenTheNotebookHasADocument() {
        assertTrue(ExportDocumentRules.listed(ExporterContract.SOURCE_DOCUMENT, hasDocument = true))
        assertFalse(ExportDocumentRules.listed(ExporterContract.SOURCE_DOCUMENT, hasDocument = false))
        // The other kinds never consult the document at all.
        assertTrue(ExportDocumentRules.listed(ExporterContract.SOURCE_SOIL, hasDocument = false))
        assertTrue(ExportDocumentRules.listed(ExporterContract.SOURCE_PAGES, hasDocument = false))
    }

    @Test
    fun theSourceRowExistsOnlyUnderAPagesExporterWithADocumentToOffer() {
        assertTrue(ExportDocumentRules.sourceRowVisible(hasDocument = true, sourceKind = ExporterContract.SOURCE_PAGES))
        assertFalse(ExportDocumentRules.sourceRowVisible(hasDocument = false, sourceKind = ExporterContract.SOURCE_PAGES))
        assertFalse(ExportDocumentRules.sourceRowVisible(hasDocument = true, sourceKind = ExporterContract.SOURCE_SOIL))
        assertFalse(ExportDocumentRules.sourceRowVisible(hasDocument = true, sourceKind = ExporterContract.SOURCE_DOCUMENT))
    }
}

package com.symmetricalpalmtree.notesproutsn.ext.document

import com.symmetricalpalmtree.notesproutsn.extension.ExporterContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The descriptor's shape, pinned here rather than on a device: what the host draws is entirely this
 * list, and the host's own M9 gates lean on these pins — the source kind is what makes the host
 * assemble a document instead of a `.soil`, and the format option's ids are what it switches its
 * assembly *and* its destination naming on. A wrong id or a wrong default would be a control that
 * writes a different file from the one it names.
 */
class DocumentExporterDescriptorTest {

    @Test
    fun theSourceIsTheHostAssembledDocument() {
        // Not SOURCE_SOIL (no key crosses) and not SOURCE_PAGES (this is text, not pixels): the
        // host builds final UTF-8 bytes and this exporter streams them verbatim.
        assertEquals(ExporterContract.SOURCE_DOCUMENT, DocumentExporterDescriptor.info().sourceKind)
    }

    @Test
    fun theOneOptionIsTheReservedTextFormatChoice() {
        val info = DocumentExporterDescriptor.info()
        assertEquals(1, info.options.size)
        val option = info.options.single()
        // The reserved id is what the host recognizes; an id of our own would be an inert control.
        assertEquals(ExporterContract.OPTION_TEXT_FORMAT, option.id)
        assertEquals(ExporterContract.KIND_SINGLE_CHOICE, option.kind)
        // Exactly the two known choices, Markdown first — an unknown choice takes the exporter out
        // of the list at discovery, and declaration order is panel order.
        assertEquals(
            listOf(ExporterContract.TEXT_FORMAT_MARKDOWN, ExporterContract.TEXT_FORMAT_PLAIN),
            option.choiceIds,
        )
        assertEquals(option.choiceIds.size, option.choiceLabels.size)
        assertTrue(option.choiceLabels.all { it.isNotBlank() })
        // The document is Markdown; stripping it is the deliberate second choice.
        assertEquals(ExporterContract.TEXT_FORMAT_MARKDOWN, option.defaultValue)
    }

    @Test
    fun theFormatIdentityIsTheMarkdownDefault() {
        val info = DocumentExporterDescriptor.info()
        // Only the defaults: the chosen format renames the destination host-side.
        assertEquals("md", info.fileExtension)
        assertEquals("text/markdown", info.mimeType)
        assertTrue(info.formatLabel.isNotBlank())
    }

    @Test
    fun noKeyingOptionIsDeclared() {
        // The keying trio is `.soil`-specific: the device key is the host's business and this
        // exporter never sees a file to key.
        assertTrue(DocumentExporterDescriptor.options.none { it.id == ExporterContract.OPTION_KEYING })
    }
}

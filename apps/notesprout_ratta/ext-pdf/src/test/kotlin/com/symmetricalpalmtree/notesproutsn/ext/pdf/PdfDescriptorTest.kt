package com.symmetricalpalmtree.notesproutsn.ext.pdf

import com.symmetricalpalmtree.notesproutsn.extension.ExporterContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The descriptor's shape, pinned here rather than on a device: what the host draws is entirely this
 * list, and a wrong id or a wrong default would be a control that does the opposite of what it says
 * — or, for the protect toggle, one that arms a password nobody asked for.
 */
class PdfDescriptorTest {

    @Test
    fun theDescriptorIsTheHostRenderedPagePairAndNothingElse() {
        val info = PdfDescriptor.info()
        assertEquals("pdf", info.fileExtension)
        assertEquals("application/pdf", info.mimeType)
        // A PDF exporter can never receive the .soil: no key crosses the seam.
        assertEquals(ExporterContract.SOURCE_PAGES, info.sourceKind)
        // Declaration order is panel order — the paper question before the password one.
        assertEquals(
            listOf(ExporterContract.OPTION_PAGE_TEMPLATE, ExporterContract.OPTION_PROTECT),
            info.options.map { it.id },
        )
        assertTrue(info.options.all { it.kind == ExporterContract.KIND_TOGGLE })
        assertTrue(info.options.all { it.choiceIds.isEmpty() && it.choiceLabels.isEmpty() })
    }

    @Test
    fun theDefaultsAreTheFullFidelityUnprotectedPage() {
        val defaults = PdfDescriptor.options.associate { it.id to it.defaultValue }
        // The page as it was written…
        assertEquals("1", defaults[ExporterContract.OPTION_PAGE_TEMPLATE])
        // …and a password is a thing a user asks for, never a thing that happens to them.
        assertEquals("0", defaults[ExporterContract.OPTION_PROTECT])
    }

    @Test
    fun noKeyingOptionIsDeclared() {
        // The keying trio is `.soil`-specific: the device key is the host's business and this
        // exporter never sees a file to key.
        assertTrue(PdfDescriptor.options.none { it.id == ExporterContract.OPTION_KEYING })
    }
}

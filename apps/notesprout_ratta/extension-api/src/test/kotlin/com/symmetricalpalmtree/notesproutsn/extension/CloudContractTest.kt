package com.symmetricalpalmtree.notesproutsn.extension

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The cloud point's constants and its pure checks (arc 25 / V1). The two refusal messages are
 * compared verbatim by the host's dialogs and the action strings by discovery — a drift in either
 * is a silent "no provider installed" or a wrong dialog, so they are pinned by test.
 */
class CloudContractTest {

    @Test
    fun constants() {
        assertEquals(8, CloudContract.MIN_API_VERSION_FOR_CLOUD)
        assertEquals(8, CloudContract.MAX_PATH_DEPTH)
        assertEquals(255, CloudContract.MAX_NAME_CHARS)
        assertEquals(256, CloudContract.MAX_ENTRY_ID_CHARS)
        assertEquals(128, CloudContract.MAX_MIME_CHARS)
        assertEquals(254, CloudContract.MAX_ACCOUNT_LABEL_CHARS)
        assertEquals(64, CloudContract.MAX_PROVIDER_NAME_CHARS)
        assertEquals(1_000, CloudContract.MAX_LIST_ENTRIES)
        assertEquals("not connected", CloudContract.NOT_CONNECTED)
        assertEquals("network", CloudContract.NETWORK)
        // The point was born at the current API version — the floor IS the version.
        assertEquals(ExtensionContract.API_VERSION, CloudContract.MIN_API_VERSION_FOR_CLOUD)
    }

    @Test
    fun aNameIsOneFilesystemSegment() {
        assertTrue(CloudContract.isName("Exports"))
        assertTrue(CloudContract.isName("My notebook 2026-09-04.soil"))
        assertTrue(CloudContract.isName("café — notes"))
        assertTrue(CloudContract.isName("x".repeat(CloudContract.MAX_NAME_CHARS)))
        assertFalse(CloudContract.isName(""))
        assertFalse(CloudContract.isName("x".repeat(CloudContract.MAX_NAME_CHARS + 1)))
        assertFalse(CloudContract.isName("."))
        assertFalse(CloudContract.isName(".."))
        assertFalse(CloudContract.isName("a/b"))
        assertFalse(CloudContract.isName("a\\b"))
        assertFalse(CloudContract.isName("a" + '\n' + "b"))
        assertFalse(CloudContract.isName(" leading"))
        assertFalse(CloudContract.isName("trailing "))
        assertFalse(CloudContract.isName("   "))
        // A dot-prefixed name that is not `.`/`..` is fine — `.hidden` is a name.
        assertTrue(CloudContract.isName(".hidden"))
    }

    @Test
    fun anEntryIdIsOpaqueButBounded() {
        assertTrue(CloudContract.isEntryId("1AbC_-xyz"))
        assertTrue(CloudContract.isEntryId("x".repeat(CloudContract.MAX_ENTRY_ID_CHARS)))
        assertFalse(CloudContract.isEntryId(""))
        assertFalse(CloudContract.isEntryId("x".repeat(CloudContract.MAX_ENTRY_ID_CHARS + 1)))
        assertFalse(CloudContract.isEntryId("a b"))
        assertFalse(CloudContract.isEntryId("a" + '\t' + "b"))
    }

    @Test
    fun aLabelIsPrintableAndBounded() {
        assertTrue(CloudContract.isLabel("", 10))
        assertTrue(CloudContract.isLabel("someone@example.com", CloudContract.MAX_ACCOUNT_LABEL_CHARS))
        assertFalse(CloudContract.isLabel("x".repeat(11), 10))
        assertFalse(CloudContract.isLabel("a" + '\u0007' + "b", 10))
    }

    @Test
    fun aMimeIsTypeSlashSubtype() {
        assertTrue(CloudContract.isMime("application/octet-stream"))
        assertTrue(CloudContract.isMime("application/pdf"))
        assertTrue(CloudContract.isMime("text/markdown"))
        assertFalse(CloudContract.isMime(""))
        assertFalse(CloudContract.isMime("pdf"))
        assertFalse(CloudContract.isMime("/pdf"))
        assertFalse(CloudContract.isMime("application/"))
        assertFalse(CloudContract.isMime("a/b/c"))
        assertFalse(CloudContract.isMime("text/ plain"))
        assertFalse(CloudContract.isMime("x".repeat(CloudContract.MAX_MIME_CHARS) + "/y"))
    }

    @Test
    fun aPathIsBoundedNames() {
        assertArrayEquals(arrayOf<String>(), CloudContract.requireValidPath(arrayOf()))
        assertArrayEquals(arrayOf("Exports", "Trip"), CloudContract.requireValidPath(arrayOf("Exports", "Trip")))
        CloudContract.requireValidPath(Array(CloudContract.MAX_PATH_DEPTH) { "d$it" })
        assertRefused { CloudContract.requireValidPath(null) }
        assertRefused { CloudContract.requireValidPath(Array(CloudContract.MAX_PATH_DEPTH + 1) { "d$it" }) }
        assertRefused { CloudContract.requireValidPath(arrayOf("Exports", "")) }
        assertRefused { CloudContract.requireValidPath(arrayOf("Exports", "a/b")) }
        assertRefused { CloudContract.requireValidPath(arrayOf("..")) }
    }

    private fun assertRefused(block: () -> Unit) {
        try {
            block()
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
        }
    }
}

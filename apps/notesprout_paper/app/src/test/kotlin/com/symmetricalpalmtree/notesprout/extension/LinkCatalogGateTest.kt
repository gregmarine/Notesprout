package com.symmetricalpalmtree.notesprout.extension

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The uid + revocation gate behind `LinkCatalogBinder` (arc 7 / L0) — the `ExtensionStoreGateTest` shape. */
class LinkCatalogGateTest {

    private val extUid = 10_123

    @Test
    fun happyPathPasses() {
        val gate = LinkCatalogGate(extUid) { extUid }
        gate.check()   // no throw
    }

    @Test
    fun anotherUidIsRefused() {
        val gate = LinkCatalogGate(extUid) { extUid + 1 }
        try {
            gate.check()
            fail("expected SecurityException")
        } catch (expected: SecurityException) {
        }
    }

    @Test
    fun revokedIsRefusedEvenForTheRightUid() {
        val gate = LinkCatalogGate(extUid) { extUid }
        gate.revoke()
        assertTrue(gate.revoked)
        try {
            gate.check()
            fail("expected SecurityException")
        } catch (expected: SecurityException) {
        }
    }

    @Test
    fun entryTruncatesTheLabel() {
        val gate = LinkCatalogGate(extUid) { extUid }
        val long = "x".repeat(ExtensionContract.MAX_CATALOG_LABEL_CHARS + 40)
        val entry = gate.entry("id-1", ExtensionContract.CATALOG_NOTEBOOK, long)
        assertEquals(ExtensionContract.MAX_CATALOG_LABEL_CHARS, entry.label.length)
        assertEquals("id-1", entry.id)
        assertEquals(ExtensionContract.CATALOG_NOTEBOOK, entry.kind)
    }

    @Test
    fun capCutsAReplyAtTheEntryCap() {
        val gate = LinkCatalogGate(extUid) { extUid }
        val big = List(ExtensionContract.MAX_CATALOG_ENTRIES + 5) { it }
        assertEquals(ExtensionContract.MAX_CATALOG_ENTRIES, gate.cap(big).size)
        assertEquals(listOf(1, 2), gate.cap(listOf(1, 2)))
    }
}

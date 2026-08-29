package com.symmetricalpalmtree.notesproutsn.importing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/** og's `isSafeImportId` rule, pinned: an id out of an incoming file is a UUID or it is not used. */
class SafeImportIdTest {

    @Test
    fun acceptsAMintedUuid() {
        repeat(20) { assertTrue(SafeImportId.isSafe(UUID.randomUUID().toString())) }
    }

    @Test
    fun acceptsUpperCaseHex() {
        assertTrue(SafeImportId.isSafe("8F14E45F-CEEA-467A-9B8D-8F14E45FCEEA"))
    }

    @Test
    fun rejectsPathTraversal() {
        // The whole reason the rule exists: this would name a file outside the Garden.
        assertFalse(SafeImportId.isSafe("../../notesprout"))
        assertFalse(SafeImportId.isSafe("8f14e45f-ceea-467a-9b8d-8f14e45fceea/../x"))
        assertFalse(SafeImportId.isSafe("/etc/passwd"))
    }

    @Test
    fun rejectsNearMisses() {
        assertFalse(SafeImportId.isSafe(null))
        assertFalse(SafeImportId.isSafe(""))
        assertFalse(SafeImportId.isSafe(" "))
        // No dashes.
        assertFalse(SafeImportId.isSafe("8f14e45fceea467a9b8d8f14e45fceea"))
        // Braced and URN forms are not relaxed into.
        assertFalse(SafeImportId.isSafe("{8f14e45f-ceea-467a-9b8d-8f14e45fceea}"))
        assertFalse(SafeImportId.isSafe("urn:uuid:8f14e45f-ceea-467a-9b8d-8f14e45fceea"))
        // Non-hex, and one character short / long.
        assertFalse(SafeImportId.isSafe("8g14e45f-ceea-467a-9b8d-8f14e45fceea"))
        assertFalse(SafeImportId.isSafe("8f14e45-ceea-467a-9b8d-8f14e45fceea"))
        assertFalse(SafeImportId.isSafe("8f14e45ff-ceea-467a-9b8d-8f14e45fceea"))
        // Trailing whitespace or a newline is not trimmed away into acceptance.
        assertFalse(SafeImportId.isSafe("8f14e45f-ceea-467a-9b8d-8f14e45fceea "))
        assertFalse(SafeImportId.isSafe("8f14e45f-ceea-467a-9b8d-8f14e45fceea\n"))
    }

    @Test
    fun orNullIsTheShapeCallersWant() {
        val id = UUID.randomUUID().toString()
        assertEquals(id, SafeImportId.orNull(id))
        assertNull(SafeImportId.orNull("not-an-id"))
        assertNull(SafeImportId.orNull(null))
    }
}

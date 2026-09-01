package com.symmetricalpalmtree.notesproutsn.extension

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/**
 * [TagRecord]'s constructor `require`s — unmarshal is the validation (family rule). A real `Parcel`
 * round trip is not available here (`:extension-api` runs plain JVM tests with no Robolectric), so
 * what is pinned is the gate either side of the wire.
 */
class TagRecordTest {

    private val id = "11111111-1111-4111-8111-111111111111"

    private fun assertRefused(build: () -> TagRecord) {
        try {
            build()
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
        }
    }

    @Test
    fun aCanonicalRecordCarriesItsIdentity() {
        val record = TagRecord(id, "Reading List")
        assertEquals(id, record.id)
        assertEquals("Reading List", record.display)
        assertEquals("reading list", record.identityKey)
    }

    @Test
    fun theIdMustBeAUuid() {
        assertRefused { TagRecord("t0", "draft") }
        assertRefused { TagRecord("", "draft") }
        assertRefused { TagRecord("{$id}", "draft") }
    }

    @Test
    fun theDisplayMustBeATag() {
        assertRefused { TagRecord(id, "   ") }
        assertRefused { TagRecord(id, "") }
        assertRefused { TagRecord(id, "x".repeat(ExtensionContract.MAX_TAG_CHARS + 1)) }
    }

    /** The stored form IS the normalized form: a record that would have to be fixed on the way in
     *  is a store that disagrees with [TagRules], and that is worth failing over. */
    @Test
    fun theDisplayMustAlreadyBeNormalized() {
        assertRefused { TagRecord(id, " draft") }
        assertRefused { TagRecord(id, "reading  list") }
        assertRefused { TagRecord(id, "reading\tlist") }
    }
}

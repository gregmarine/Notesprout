package com.symmetricalpalmtree.notesproutsn.importing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The import source question (arc 25 / V5): without a cloud provider there is no question at all,
 * with one there are two answers in a fixed order, and an index that names nothing is an answer the
 * flow can survive.
 */
class ImportSourceTest {

    @Test
    fun `without a provider there is one source and no question`() {
        assertEquals(listOf(ImportSource.Source.LOCAL), ImportSource.choices(providerInstalled = false))
        assertFalse(ImportSource.asksSource(providerInstalled = false))
    }

    @Test
    fun `with a provider this device comes first, then the cloud`() {
        assertEquals(
            listOf(ImportSource.Source.LOCAL, ImportSource.Source.CLOUD),
            ImportSource.choices(providerInstalled = true),
        )
        assertTrue(ImportSource.asksSource(providerInstalled = true))
    }

    @Test
    fun `an index names the answer it was drawn from`() {
        assertEquals(ImportSource.Source.LOCAL, ImportSource.sourceAt(0, providerInstalled = true))
        assertEquals(ImportSource.Source.CLOUD, ImportSource.sourceAt(1, providerInstalled = true))
        assertEquals(ImportSource.Source.LOCAL, ImportSource.sourceAt(0, providerInstalled = false))
    }

    @Test
    fun `an index out of range is null, never a crash`() {
        assertNull(ImportSource.sourceAt(1, providerInstalled = false))
        assertNull(ImportSource.sourceAt(2, providerInstalled = true))
        assertNull(ImportSource.sourceAt(-1, providerInstalled = true))
        assertNull(ImportSource.sourceAt(Int.MAX_VALUE, providerInstalled = true))
    }
}

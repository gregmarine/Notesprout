package com.symmetricalpalmtree.notesproutsn.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `extensionStoreFile` and `extensionStoreFiles` need a `Context`, so what is testable here is the
 * guard that keeps a package name from becoming a path — the store file's stem is a package name,
 * and a `/` or a `..` in it would put the file somewhere other than `Garden/` — and the rule
 * deciding which entries in that directory the backup run (arc 21 / W5) treats as stores.
 */
class SoilFileTest {

    @Test
    fun validExtensionPackages() {
        assertTrue(isValidExtensionPackage("com.symmetricalpalmtree.notesproutsn.ext.scratchpad"))
        assertTrue(isValidExtensionPackage("com.symmetricalpalmtree.notesproutsn.ext.scratchpad.dev"))
        assertTrue(isValidExtensionPackage("probe.test"))
        assertTrue(isValidExtensionPackage("a_b1"))
    }

    @Test
    fun invalidExtensionPackages() {
        assertFalse(isValidExtensionPackage(""))
        assertFalse(isValidExtensionPackage("../notesprout"))
        assertFalse(isValidExtensionPackage("a/b"))
        assertFalse(isValidExtensionPackage("a b"))
        assertFalse(isValidExtensionPackage("a-b"))
        assertFalse(isValidExtensionPackage("com.x "))
    }

    @Test
    fun storeFilesAreNamedForTheirPackage() {
        assertEquals(
            "com.symmetricalpalmtree.notesproutsn.ext.tags.dev",
            extensionStorePackage("com.symmetricalpalmtree.notesproutsn.ext.tags.dev.db"),
        )
        assertEquals("a_b1", extensionStorePackage("a_b1.db"))
    }

    /** The other things that live in `Garden/` — none of them is a store, and the run must not
     *  copy a notebook under a package name or an import that is still in flight. */
    @Test
    fun nothingElseInTheGardenIsAStore() {
        assertNull(extensionStorePackage("3f6a1c2e-0000-4000-8000-000000000001.soil"))
        assertNull(extensionStorePackage("3f6a1c2e-0000-4000-8000-000000000001.soil.importing"))
        assertNull(extensionStorePackage("com.x.db-wal"))
        assertNull(extensionStorePackage("com.x.db-shm"))
        assertNull(extensionStorePackage("com.x.db.part"))
        assertNull(extensionStorePackage("com.x.db.old"))
        assertNull(extensionStorePackage(".db"))
        assertNull(extensionStorePackage("some app.db"))
        assertNull(extensionStorePackage("db"))
    }

    /** Every store name round-trips through the one path constructor: what the backup enumerates is
     *  exactly what `extensionStoreFile` would build for that package. */
    @Test
    fun storeNamesRoundTrip() {
        val pkg = "com.symmetricalpalmtree.notesproutsn.ext.scratchpad"
        assertEquals(pkg, extensionStorePackage(pkg + EXTENSION_STORE_SUFFIX))
    }
}

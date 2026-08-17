package com.symmetricalpalmtree.notesprout.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The package-name guard behind `extensionStoreFile` (the `File` half needs a Context — device only). */
class SoilFileTest {
    @Test
    fun validExtensionPackages() {
        assertTrue(isValidExtensionPackage("com.symmetricalpalmtree.notesprout.ext.naming"))
        assertTrue(isValidExtensionPackage("com.symmetricalpalmtree.notesprout.ext.naming.dev"))
        assertTrue(isValidExtensionPackage("probe.test"))
        assertTrue(isValidExtensionPackage("a_b.C9"))
    }

    @Test
    fun invalidExtensionPackages() {
        assertFalse(isValidExtensionPackage(""))
        assertFalse(isValidExtensionPackage("../notesprout"))
        assertFalse(isValidExtensionPackage("a/b"))
        assertFalse(isValidExtensionPackage("a b"))
        assertFalse(isValidExtensionPackage("a-b"))
        assertFalse(isValidExtensionPackage("a:b"))
        assertFalse(isValidExtensionPackage("a\tb"))
    }
}

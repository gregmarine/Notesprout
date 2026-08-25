package com.symmetricalpalmtree.notesproutsn.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `extensionStoreFile` needs a `Context`, so what is testable here is the guard that keeps a
 * package name from becoming a path: the store file's stem is a package name, and a `/` or a `..`
 * in it would put the file somewhere other than `Garden/`.
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
}

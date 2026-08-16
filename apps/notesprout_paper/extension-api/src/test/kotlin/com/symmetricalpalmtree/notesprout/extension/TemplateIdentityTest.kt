package com.symmetricalpalmtree.notesprout.extension

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TemplateIdentityTest {

    @Test
    fun roundTrip() {
        val pkg = "com.symmetricalpalmtree.notesprout.ext.templates"
        val s = ExtensionContract.templateIdentity(pkg, "lined")
        assertEquals("$pkg:lined", s)
        assertEquals(pkg to "lined", ExtensionContract.parseIdentity(s))
    }

    @Test
    fun splitsAtFirstColon() {
        assertEquals("a.b" to "x:y:z", ExtensionContract.parseIdentity("a.b:x:y:z"))
    }

    @Test
    fun malformedIsNull() {
        assertNull(ExtensionContract.parseIdentity(""))
        assertNull(ExtensionContract.parseIdentity("BLANK"))
        assertNull(ExtensionContract.parseIdentity("LINED"))
        assertNull(ExtensionContract.parseIdentity(":lined"))
        assertNull(ExtensionContract.parseIdentity("pkg:"))
        assertNull(ExtensionContract.parseIdentity(":"))
    }
}

package com.symmetricalpalmtree.notesprout.extension

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure `require`s behind [CreatedObject] + the H3 contract constants (no Parcel on the JVM). */
class CreatedObjectTest {

    @Test
    fun acceptsWellFormed() {
        CreatedObject.requireValid("heading", "## Meeting notes")
        CreatedObject.requireValid("a-b_c9", "x")
        CreatedObject.requireValid("a".repeat(ExtensionContract.MAX_TYPE_ID_CHARS), "x")
    }

    @Test
    fun rejectsBadTypeIds() {
        assertThrows(IllegalArgumentException::class.java) { CreatedObject.requireValid("", "x") }
        assertThrows(IllegalArgumentException::class.java) { CreatedObject.requireValid("Heading", "x") }   // upper case
        assertThrows(IllegalArgumentException::class.java) { CreatedObject.requireValid("has space", "x") }
        assertThrows(IllegalArgumentException::class.java) { CreatedObject.requireValid("dot.ted", "x") }
        assertThrows(IllegalArgumentException::class.java) {
            CreatedObject.requireValid("a".repeat(ExtensionContract.MAX_TYPE_ID_CHARS + 1), "x")
        }
    }

    @Test
    fun rejectsBlankPayload() {
        assertThrows(IllegalArgumentException::class.java) { CreatedObject.requireValid("heading", "") }
        assertThrows(IllegalArgumentException::class.java) { CreatedObject.requireValid("heading", "  \n") }
    }

    @Test
    fun typeIdPredicate() {
        assertTrue(ExtensionContract.isTypeId("heading"))
        assertTrue(ExtensionContract.isTypeId("h-1_x"))
        assertFalse(ExtensionContract.isTypeId(""))
        assertFalse(ExtensionContract.isTypeId("H"))
        assertFalse(ExtensionContract.isTypeId("a:b"))
    }

    @Test
    fun objectIdentityRoundTrips() {
        val id = ExtensionContract.objectIdentity("com.example.ext", "heading")
        assertEquals("com.example.ext:heading", id)
        assertEquals("com.example.ext" to "heading", ExtensionContract.parseIdentity(id))
    }

    @Test
    fun constants() {
        assertEquals("com.symmetricalpalmtree.notesprout.extension.OBJECT_PROVIDER", ExtensionContract.ACTION_OBJECT_PROVIDER)
        assertEquals(32, ExtensionContract.MAX_TYPE_ID_CHARS)
        assertEquals(16, ExtensionContract.MAX_TYPES)
        assertEquals(200, ExtensionContract.MAX_OBJECTS_PER_PAGE)
        assertEquals("recognizer required", ExtensionContract.RECOGNIZER_REQUIRED)
        assertEquals("markdown required", ExtensionContract.MARKDOWN_REQUIRED)
        assertTrue(ExtensionContract.RECOGNIZER_REQUIRED != ExtensionContract.RECOGNIZER_NOT_READY)
    }
}

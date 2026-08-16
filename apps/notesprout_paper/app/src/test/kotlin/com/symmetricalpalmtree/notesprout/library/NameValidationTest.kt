package com.symmetricalpalmtree.notesprout.library

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class NameValidationTest {

    @Test
    fun validNames() {
        assertNull(NewNotebookActivity.validateName("My Notebook"))
        assertNull(NewNotebookActivity.validateName("20260815_120000"))
        assertNull(NewNotebookActivity.validateName("test-name_v2.0"))
        assertNull(NewNotebookActivity.validateName("a"))
    }

    @Test
    fun emptyName() {
        assertNotNull(NewNotebookActivity.validateName(""))
    }

    @Test
    fun dotOnly() {
        assertNotNull(NewNotebookActivity.validateName("."))
        assertNotNull(NewNotebookActivity.validateName(".."))
    }

    @Test
    fun invalidCharacters() {
        assertNotNull(NewNotebookActivity.validateName("hello/world"))
        assertNotNull(NewNotebookActivity.validateName("test\ttab"))
        assertNotNull(NewNotebookActivity.validateName("name@domain"))
        assertNotNull(NewNotebookActivity.validateName("a<b>c"))
    }
}

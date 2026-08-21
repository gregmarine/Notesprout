package com.symmetricalpalmtree.notesproutsn.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NameRulesTest {

    @Test
    fun acceptsTheEverydayNames() {
        assertNull(NameRules.validate("My Notebook"))
        assertNull(NameRules.validate("20260820_101530"))
        assertNull(NameRules.validate("test-name_v2.0"))
        assertNull(NameRules.validate("a"))
        assertNull(NameRules.validate("Notes 2026"))
    }

    @Test
    fun rejectsEmptyAndWhitespaceOnly() {
        assertEquals(NameRules.Problem.EMPTY, NameRules.validate(""))
        assertEquals(NameRules.Problem.EMPTY, NameRules.validate("   "))
    }

    @Test
    fun rejectsTheTwoReservedDotNames() {
        assertEquals(NameRules.Problem.RESERVED, NameRules.validate("."))
        assertEquals(NameRules.Problem.RESERVED, NameRules.validate(".."))
    }

    @Test
    fun allowsDotsThatAreNotTheReservedNames() {
        assertNull(NameRules.validate("..."))
        assertNull(NameRules.validate(".hidden"))
        assertNull(NameRules.validate("v1.2.3"))
    }

    @Test
    fun rejectsCharactersOutsideTheWhitelist() {
        assertEquals(NameRules.Problem.CHARSET, NameRules.validate("hello/world"))
        assertEquals(NameRules.Problem.CHARSET, NameRules.validate("back\\slash"))
        assertEquals(NameRules.Problem.CHARSET, NameRules.validate("name@domain"))
        assertEquals(NameRules.Problem.CHARSET, NameRules.validate("a<b>c"))
        assertEquals(NameRules.Problem.CHARSET, NameRules.validate("semi;colon"))
        assertEquals(NameRules.Problem.CHARSET, NameRules.validate("quote\"d"))
        assertEquals(NameRules.Problem.CHARSET, NameRules.validate("emoji🌱"))
    }

    @Test
    fun rejectsControlCharactersThatTrimDoesNotRemoveMidString() {
        assertEquals(NameRules.Problem.CHARSET, NameRules.validate("tab\there"))
        assertEquals(NameRules.Problem.CHARSET, NameRules.validate("new\nline"))
    }

    @Test
    fun callersTrimBeforeValidating() {
        // The screens pass name.trim(); the rules themselves see no surrounding whitespace.
        assertNull(NameRules.validate("  padded  ".trim()))
    }

    @Test
    fun isValidMirrorsValidate() {
        assertEquals(true, NameRules.isValid("ok"))
        assertEquals(false, NameRules.isValid(".."))
    }
}

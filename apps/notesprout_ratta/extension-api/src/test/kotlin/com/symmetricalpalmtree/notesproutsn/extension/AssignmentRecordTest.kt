package com.symmetricalpalmtree.notesproutsn.extension

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** [AssignmentRecord]'s constructor `require`s, and the `""` ⇄ null page rule the store's primary
 *  key forces (in SQL `NULL` is not equal to `NULL`, so a nullable page column would let the same
 *  notebook tag be inserted twice). */
class AssignmentRecordTest {

    private val t1 = "11111111-1111-4111-8111-111111111111"
    private val n1 = "22222222-2222-4222-8222-222222222222"
    private val p1 = "aaaaaaaa-1111-4111-8111-111111111111"

    private fun assertRefused(build: () -> AssignmentRecord) {
        try {
            build()
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
        }
    }

    @Test
    fun anEmptyPageIsANotebookTag() {
        val record = AssignmentRecord(t1, n1)
        assertTrue(record.isNotebookTag)
        assertNull(record.pageIdOrNull)
        assertEquals("", record.pageId)
        assertTrue(record.isOn(n1, null))
        assertFalse(record.isOn(n1, p1))
    }

    @Test
    fun aPageTagCarriesBothIds() {
        val record = AssignmentRecord(t1, n1, p1)
        assertFalse(record.isNotebookTag)
        assertEquals(p1, record.pageIdOrNull)
        assertTrue(record.isOn(n1, p1))
        assertFalse(record.isOn(n1, null))
        // The same page id under another notebook is a different target — the W4 rule.
        assertFalse(record.isOn(t1, p1))
    }

    @Test
    fun everyIdMustBeAUuidExceptTheAbsentPage() {
        assertRefused { AssignmentRecord("t1", n1) }
        assertRefused { AssignmentRecord(t1, "n1") }
        assertRefused { AssignmentRecord(t1, "") }
        assertRefused { AssignmentRecord(t1, n1, "p1") }
        assertRefused { AssignmentRecord(t1, n1, "\u0000") }
    }
}

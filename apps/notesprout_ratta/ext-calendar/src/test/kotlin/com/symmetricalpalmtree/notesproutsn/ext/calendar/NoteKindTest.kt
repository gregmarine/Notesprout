package com.symmetricalpalmtree.notesproutsn.ext.calendar

import org.junit.Assert.assertEquals
import org.junit.Test

/** Which half of the note an event opens on (arc 24 / Z3) — all four combinations, because the
 *  rule has exactly one exception and an exception is what gets inverted in a refactor. */
class NoteKindTest {

    @Test
    fun inkAlwaysWins_andTextOnlyIsTheOneException() {
        assertEquals(NoteKind.HANDWRITING, NoteKind.defaultFor(hasStrokes = true, hasText = false))
        assertEquals("ink wins the tie", NoteKind.HANDWRITING, NoteKind.defaultFor(hasStrokes = true, hasText = true))
        assertEquals(NoteKind.TEXT, NoteKind.defaultFor(hasStrokes = false, hasText = true))
    }

    @Test
    fun anEmptyNoteIsAnInvitationToWriteOnIt() {
        assertEquals(NoteKind.HANDWRITING, NoteKind.defaultFor(hasStrokes = false, hasText = false))
    }
}

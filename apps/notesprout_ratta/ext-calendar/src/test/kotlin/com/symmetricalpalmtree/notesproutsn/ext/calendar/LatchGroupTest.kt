package com.symmetricalpalmtree.notesproutsn.ext.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** [LatchGroup]: the exclusivity behind a one-armed latch row (arc 24 / Z5a) — the Ends row's
 *  [Never][On a date][After], and any future row of the same shape. */
class LatchGroupTest {

    private val group = LatchGroup(listOf(EndMode.NEVER, EndMode.UNTIL, EndMode.COUNT))

    // ── pressed ──────────────────────────────────────────────────────────────

    @Test
    fun exactlyOneTrueForEachOption() {
        assertEquals(listOf(true, false, false), group.pressed(EndMode.NEVER))
        assertEquals(listOf(false, true, false), group.pressed(EndMode.UNTIL))
        assertEquals(listOf(false, false, true), group.pressed(EndMode.COUNT))
    }

    @Test
    fun anUnknownSelectionLeavesTheFirstDown() {
        // A row with nothing down reads as broken on e-ink — the first option is the fallback,
        // never none.
        val oneOption = LatchGroup(listOf(EndMode.NEVER))
        assertEquals(listOf(true), oneOption.pressed(EndMode.UNTIL))
    }

    // ── resolve ──────────────────────────────────────────────────────────────

    @Test
    fun resolvePicksTheTappedOption() {
        assertEquals(EndMode.COUNT, group.resolve(EndMode.NEVER, EndMode.COUNT))
    }

    @Test
    fun resolveIgnoresAnUnknownTap() {
        val oneOption = LatchGroup(listOf(EndMode.NEVER))
        assertEquals(EndMode.NEVER, oneOption.resolve(EndMode.NEVER, EndMode.COUNT))
    }

    @Test
    fun tappingTheDownLatchKeepsItDown() {
        // There is no "off" — a latch already down stays down when tapped again.
        assertEquals(EndMode.UNTIL, group.resolve(EndMode.UNTIL, EndMode.UNTIL))
    }

    // ── construction ─────────────────────────────────────────────────────────

    @Test
    fun emptyOptionsThrow() {
        assertThrows(IllegalArgumentException::class.java) { LatchGroup(emptyList<EndMode>()) }
    }

    @Test
    fun duplicateOptionsThrow() {
        assertThrows(IllegalArgumentException::class.java) {
            LatchGroup(listOf(EndMode.NEVER, EndMode.NEVER))
        }
    }
}

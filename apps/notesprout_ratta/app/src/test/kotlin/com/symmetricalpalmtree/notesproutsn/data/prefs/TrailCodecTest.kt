package com.symmetricalpalmtree.notesproutsn.data.prefs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The link trail's algebra (K4) — the cap, the LIFO, and above all [TrailCodec.decode] treating
 * stored prefs as untrusted input: a walk-back must never crash the notebook, and a corrupt blob
 * must never make the walk longer than the flow's bound.
 */
class TrailCodecTest {

    private fun entry(i: Int) = TrailEntry("nb-$i", "pg-$i")

    @Test
    fun `entries round-trip through encode and decode`() {
        val entries = listOf(entry(1), entry(2), entry(3))
        assertEquals(entries, TrailCodec.decode(TrailCodec.encode(entries)))
    }

    @Test
    fun `an empty trail round-trips`() {
        assertEquals(emptyList<TrailEntry>(), TrailCodec.decode(TrailCodec.encode(emptyList())))
    }

    @Test
    fun `null blank garbage and wrong-shaped JSON all read as empty`() {
        assertEquals(emptyList<TrailEntry>(), TrailCodec.decode(null))
        assertEquals(emptyList<TrailEntry>(), TrailCodec.decode(""))
        assertEquals(emptyList<TrailEntry>(), TrailCodec.decode("   "))
        assertEquals(emptyList<TrailEntry>(), TrailCodec.decode("]]not json[["))
        // Valid JSON, wrong shape: an object where a list belongs, and a list of the wrong objects.
        assertEquals(emptyList<TrailEntry>(), TrailCodec.decode("""{"notebookId":"a","pageId":"b"}"""))
        assertEquals(emptyList<TrailEntry>(), TrailCodec.decode("""[{"foo":1}]"""))
    }

    @Test
    fun `push appends the newest last`() {
        val out = TrailCodec.push(listOf(entry(1)), entry(2))
        assertEquals(listOf(entry(1), entry(2)), out)
    }

    @Test
    fun `push caps the trail and drops the oldest`() {
        var trail = emptyList<TrailEntry>()
        for (i in 1..TrailCodec.MAX_ENTRIES + 5) trail = TrailCodec.push(trail, entry(i))
        assertEquals(TrailCodec.MAX_ENTRIES, trail.size)
        assertEquals(entry(6), trail.first())                                  // 1..5 were dropped
        assertEquals(entry(TrailCodec.MAX_ENTRIES + 5), trail.last())          // newest survives
    }

    @Test
    fun `pop is LIFO and leaves the rest`() {
        val (top, rest) = TrailCodec.pop(listOf(entry(1), entry(2), entry(3)))
        assertEquals(entry(3), top)
        assertEquals(listOf(entry(1), entry(2)), rest)
    }

    @Test
    fun `popping an empty trail answers null and the same list`() {
        val (top, rest) = TrailCodec.pop(emptyList())
        assertNull(top)
        assertTrue(rest.isEmpty())
    }

    @Test
    fun `decode truncates an over-cap stored trail keeping the newest`() {
        val stored = TrailCodec.encode((1..TrailCodec.MAX_ENTRIES + 10).map { entry(it) })
        val read = TrailCodec.decode(stored)
        assertEquals(TrailCodec.MAX_ENTRIES, read.size)
        assertEquals(entry(11), read.first())
        assertEquals(entry(TrailCodec.MAX_ENTRIES + 10), read.last())
    }
}

package com.symmetricalpalmtree.notesproutsn.library

import com.symmetricalpalmtree.notesproutsn.data.prefs.RecentEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one rule the recents shelf must not lose: **stored order wins**. Everything else here is the
 * hygiene around it — dead ids out, duplicates collapsed, nothing invented.
 */
class RecentsAssemblyTest {

    private fun entry(id: String, t: Long = 0L) = RecentEntry(id, t)

    @Test
    fun `stored order is kept, not sorted`() {
        // Deliberately anti-alphabetical and anti-chronological: neither may reorder this.
        val entries = listOf(entry("zeta", 300), entry("alpha", 100), entry("mid", 200))
        val ids = RecentsAssembly.visibleIds(entries, setOf("zeta", "alpha", "mid"))
        assertEquals(listOf("zeta", "alpha", "mid"), ids)
    }

    @Test
    fun `dead ids are dropped and the rest keep their order`() {
        val entries = listOf(entry("a"), entry("gone"), entry("b"), entry("also-gone"), entry("c"))
        val ids = RecentsAssembly.visibleIds(entries, setOf("a", "b", "c"))
        assertEquals(listOf("a", "b", "c"), ids)
    }

    @Test
    fun `a duplicated id shows once, at its newest position`() {
        val entries = listOf(entry("a"), entry("b"), entry("a"))
        val ids = RecentsAssembly.visibleIds(entries, setOf("a", "b"))
        assertEquals(listOf("a", "b"), ids)
    }

    @Test
    fun `an id that is not alive can never appear, even alone`() {
        val ids = RecentsAssembly.visibleIds(listOf(entry("ghost")), emptySet())
        assertTrue(ids.isEmpty())
    }

    @Test
    fun `no entries is an empty shelf, not a crash`() {
        assertTrue(RecentsAssembly.visibleIds(emptyList(), setOf("a")).isEmpty())
    }

    @Test
    fun `alive ids that were never visited are not invented`() {
        val ids = RecentsAssembly.visibleIds(listOf(entry("a")), setOf("a", "b", "c"))
        assertEquals(listOf("a"), ids)
    }
}

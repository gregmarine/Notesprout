package com.symmetricalpalmtree.notesprout.data

import com.symmetricalpalmtree.notesprout.data.index.ListIds
import org.junit.Assert.assertEquals
import org.junit.Test

class ListIdsTest {
    @Test
    fun pinnedSentinel_spellsPinnedInHex() {
        assertEquals("00000000-0000-0000-0000-70696e6e6564", ListIds.PINNED_LIST_ID)
        val tail = ListIds.PINNED_LIST_ID.substringAfterLast('-')
        val word = tail.chunked(2).map { it.toInt(16).toChar() }.joinToString("")
        assertEquals("pinned", word)
    }
}

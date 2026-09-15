package com.symmetricalpalmtree.notesproutsn.notebook

import com.symmetricalpalmtree.notesproutsn.extension.BibleNote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Arc 42 "Notes": the coalescer's rules — latest per page, structural supersedes, no-link gate. */
class BibleNoteSyncRulesTest {

    private val page = "22222222-2222-4222-8222-222222222222"
    private val note = BibleNote("11111111-1111-4111-8111-111111111111", page, 1, "JHN:3:16-3:16", 0L)

    @Test
    fun aNotebookWithNoBibleLinkNeverPushesAnEmptyPage() {
        val rules = BibleNoteSyncRules(hasBibleLinks = false)
        rules.markPage(page, 1, emptyList())
        rules.markStructural()
        assertTrue(rules.isEmpty)
        assertEquals(BibleNoteSyncRules.Flush.Nothing, rules.take())
    }

    @Test
    fun theLatestMarkOfAPageWinsAndTheFlagFlips() {
        val rules = BibleNoteSyncRules(hasBibleLinks = false)
        rules.markPage(page, 1, listOf(note))
        assertTrue(rules.hasBibleLinks)
        rules.markPage(page, 1, emptyList())   // a delete after the create — still pushed, now the flag is on
        val flush = rules.take() as BibleNoteSyncRules.Flush.Pages
        assertEquals(1, flush.marks.size)
        assertTrue(flush.marks[0].notes.isEmpty())
        assertTrue(rules.isEmpty)
    }

    @Test
    fun aStructuralMarkSupersedesThePageMarks() {
        val rules = BibleNoteSyncRules(hasBibleLinks = true)
        rules.markPage(page, 1, listOf(note))
        rules.markStructural()
        assertEquals(BibleNoteSyncRules.Flush.Notebook, rules.take())
        assertEquals(BibleNoteSyncRules.Flush.Nothing, rules.take())
    }
}

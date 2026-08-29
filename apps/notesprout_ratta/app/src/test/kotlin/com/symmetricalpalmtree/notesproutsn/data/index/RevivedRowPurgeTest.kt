package com.symmetricalpalmtree.notesproutsn.data.index

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Arc 17 / K1: the index purge hard-deletes soft-deleted rows, and three paths used to count on
 * finding one to revive in place — `createNotebook` over a deleted notebook's id,
 * `importNotebookRow` (an id-collision Replace whose old row was purged between sessions), and
 * `setScheme` on a folder whose cleared naming row is gone. Each must take its fresh-create branch
 * when the row has been purged; these tests are the K1 plan's "revived-row paths tolerate purged
 * rows" gate, against the real repository over the in-memory dao.
 */
class RevivedRowPurgeTest {

    private val dao = FakeObjectDao()
    private val repo = IndexRepository(dao)

    /** A soft-deleted row that the purge then removes entirely. */
    private fun purgeAway(id: String) {
        dao.rows.remove(id)
    }

    @Test
    fun createNotebookInsertsFreshWhereAPurgedRowStood() = runBlocking {
        repo.createNotebook("nb-1", "Old", null, "LINED", now = 10L)
        repo.deleteNotebook("nb-1", now = 20L)
        purgeAway("nb-1")

        val row = repo.createNotebook("nb-1", "New", null, "GRID", now = 30L)
        assertEquals("New", row.name)
        assertEquals(30L, row.createdAt)
        assertNull(row.deletedAt)
        assertNotNull(dao.rows["nb-1"])
    }

    @Test
    fun importReplaceInsertsFreshWhereAPurgedRowStood() = runBlocking {
        repo.createNotebook("nb-2", "Old", null, "LINED", now = 10L)
        repo.deleteNotebook("nb-2", now = 20L)
        purgeAway("nb-2")

        // With no existing row, the imported file's own createdAt must win (there is nothing to keep).
        val row = repo.importNotebookRow("nb-2", "Imported", null, 3, createdAt = 5L, updatedAt = 6L, templateKind = "GRID")
        assertEquals(5L, row.createdAt)
        assertEquals(6L, row.updatedAt)
        assertNull(row.deletedAt)
        assertEquals(3, row.pageCount)
    }

    @Test
    fun setSchemeMintsANewRowWhereAPurgedOneStood() = runBlocking {
        repo.setScheme("folder-1", "Note {n}", now = 10L)
        repo.clearScheme("folder-1", now = 20L)
        val oldRowId = dao.rows.values.single { it.type == ObjectType.NAMING }.id
        purgeAway(oldRowId)

        repo.setScheme("folder-1", "Idea {n}", now = 30L)
        assertEquals("Idea {n}", repo.scheme("folder-1"))
        // One naming row again — a fresh mint, not an accumulation.
        assertEquals(1, dao.rows.values.count { it.type == ObjectType.NAMING })
    }
}

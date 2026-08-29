package com.symmetricalpalmtree.notesproutsn.data.backup

import com.symmetricalpalmtree.notesproutsn.data.index.FakeObjectDao
import com.symmetricalpalmtree.notesproutsn.data.index.IndexRepository
import com.symmetricalpalmtree.notesproutsn.data.index.ListIds
import com.symmetricalpalmtree.notesproutsn.data.index.NotebookFlags
import com.symmetricalpalmtree.notesproutsn.data.index.ObjectEntity
import com.symmetricalpalmtree.notesproutsn.data.index.ObjectType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The backup row's store, and the exclude toggle's one non-negotiable: neither ever bumps a
 * notebook's `updatedAt` — the library sort key **and** the needs-backup flag (og's rule; a bump
 * would re-flag the notebook the moment it was backed up or toggled).
 */
class BackupStoreTest {

    @Test
    fun `config round-trips through the singleton row`() = runBlocking {
        val dao = FakeObjectDao()
        val store = BackupStore(dao)
        assertEquals(BackupConfig(), store.read()) // absent row reads as a fresh config

        val config = BackupConfig(treeUri = "content://tree/x", lastRunAt = 7L, stamps = mapOf("nb" to 3L))
        assertTrue(store.write(config, now = 100L))
        assertEquals(config, store.read())

        val row = dao.rows[ListIds.BACKUP_ID]!!
        assertEquals(ObjectType.BACKUP, row.type)
        assertEquals(BackupConfig.VERSION, row.flags)
    }

    @Test
    fun `a rewrite replaces the previous config whole`() = runBlocking {
        val store = BackupStore(FakeObjectDao())
        store.write(BackupConfig(treeUri = "content://tree/x", stamps = mapOf("a" to 1L)))
        store.write(BackupConfig(treeUri = "content://tree/y"))
        assertEquals(BackupConfig(treeUri = "content://tree/y"), store.read())
    }

    @Test
    fun `exclude toggle sets and clears the bit without touching updatedAt`() = runBlocking {
        val dao = FakeObjectDao()
        val repo = IndexRepository(dao)
        dao.upsert(
            ObjectEntity(
                id = "nb", type = ObjectType.NOTEBOOK, name = "N", parentId = null,
                createdAt = 1L, updatedAt = 42L, flags = NotebookFlags.ENCRYPTED,
            )
        )

        repo.setExcludeFromBackup("nb", excluded = true)
        assertEquals(NotebookFlags.ENCRYPTED or NotebookFlags.EXCLUDE_FROM_BACKUP, dao.rows["nb"]!!.flags)
        assertEquals(42L, dao.rows["nb"]!!.updatedAt)

        repo.setExcludeFromBackup("nb", excluded = false)
        assertEquals(NotebookFlags.ENCRYPTED, dao.rows["nb"]!!.flags)
        assertEquals(42L, dao.rows["nb"]!!.updatedAt)
    }
}

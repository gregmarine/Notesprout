package com.symmetricalpalmtree.notesproutsn.data.backup

import com.symmetricalpalmtree.notesproutsn.data.index.NotebookFlags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * og's D8 rule and the work-list math over it — the decisions a backup run makes before it touches
 * a single file. The stamp semantics matter most: equal-to-stamp means backed up (the stamp *is*
 * the copied `updatedAt`), and a failed copy never stamps, which lives in the engine but is pinned
 * here as "no stamp → copy".
 */
class BackupPredicatesTest {

    // ── needsBackup: the D8 table ────────────────────────────────────────────

    @Test
    fun `no stamp means copy`() = assertTrue(BackupPredicates.needsBackup(100L, null, excluded = false))

    @Test
    fun `edited since the stamp means copy`() =
        assertTrue(BackupPredicates.needsBackup(101L, 100L, excluded = false))

    @Test
    fun `stamp equal to updatedAt means backed up`() =
        assertFalse(BackupPredicates.needsBackup(100L, 100L, excluded = false))

    @Test
    fun `stamp newer than updatedAt means backed up`() =
        assertFalse(BackupPredicates.needsBackup(100L, 200L, excluded = false))

    @Test
    fun `excluded wins over everything`() {
        assertFalse(BackupPredicates.needsBackup(100L, null, excluded = true))
        assertFalse(BackupPredicates.needsBackup(101L, 100L, excluded = true))
    }

    // ── isExcluded: the flags bit ────────────────────────────────────────────

    @Test
    fun `exclude bit is bit 1, independent of the encrypted bit`() {
        assertFalse(BackupPredicates.isExcluded(null))
        assertFalse(BackupPredicates.isExcluded(0))
        assertFalse(BackupPredicates.isExcluded(NotebookFlags.ENCRYPTED))
        assertTrue(BackupPredicates.isExcluded(NotebookFlags.EXCLUDE_FROM_BACKUP))
        assertTrue(BackupPredicates.isExcluded(NotebookFlags.ENCRYPTED or NotebookFlags.EXCLUDE_FROM_BACKUP))
    }

    // ── workList: the counts the summary owes the user ───────────────────────

    private fun candidate(id: String, updatedAt: Long, flags: Int? = null) =
        BackupPredicates.Candidate(id, updatedAt, flags)

    @Test
    fun `work list splits copy, excluded and up-to-date`() {
        val work = BackupPredicates.workList(
            listOf(
                candidate("fresh", 100L),                                       // no stamp → copy
                candidate("edited", 300L),                                      // stamp 200 → copy
                candidate("clean", 200L),                                       // stamp 200 → up to date
                candidate("skipped", 999L, NotebookFlags.EXCLUDE_FROM_BACKUP),  // excluded
            ),
            stamps = mapOf("edited" to 200L, "clean" to 200L),
        )
        assertEquals(listOf("fresh", "edited"), work.toCopy.map { it.id })
        assertEquals(1, work.excluded)
        assertEquals(1, work.upToDate)
    }

    @Test
    fun `an excluded notebook is never also counted up-to-date`() {
        val work = BackupPredicates.workList(
            listOf(candidate("both", 100L, NotebookFlags.EXCLUDE_FROM_BACKUP)),
            stamps = mapOf("both" to 100L),
        )
        assertEquals(0, work.toCopy.size)
        assertEquals(1, work.excluded)
        assertEquals(0, work.upToDate)
    }

    @Test
    fun `empty library is an empty run`() {
        val work = BackupPredicates.workList(emptyList(), emptyMap())
        assertTrue(work.toCopy.isEmpty())
        assertEquals(0, work.excluded)
        assertEquals(0, work.upToDate)
    }

    // ── pruneStamps ──────────────────────────────────────────────────────────

    @Test
    fun `prune drops stamps for purged notebooks and keeps the rest`() {
        val pruned = BackupPredicates.pruneStamps(
            mapOf("alive" to 100L, "gone" to 200L),
            aliveIds = setOf("alive"),
        )
        assertEquals(mapOf("alive" to 100L), pruned)
    }

    // ── Filename scheme (og D5): pinned — a changed name orphans every backup ─

    @Test
    fun `destination names are pinned`() {
        assertEquals("nb-1.soil", BackupPredicates.soilName("nb-1"))
        assertEquals("notesprout.db", BackupPredicates.INDEX_NAME)
        assertEquals("-wal", BackupPredicates.WAL_SUFFIX)
        assertEquals(".part", BackupPredicates.PART_SUFFIX)
        assertEquals(".old", BackupPredicates.OLD_SUFFIX)
        assertEquals("dev", BackupPredicates.DEV_SUBDIR)
    }
}

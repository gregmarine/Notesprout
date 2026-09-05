package com.symmetricalpalmtree.notesproutsn.crypto

import com.symmetricalpalmtree.notesproutsn.crypto.RekeyCommit.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** D1 step 3 — og's `commitReplace` order, and every exit of it, over a fake filesystem. */
class RekeyCommitTest {

    private val garden = File("/g")
    private val original = File(garden, "n.soil")
    private val tmp = RekeyNames.tmpFor(original)
    private val bak = RekeyNames.bakFor(original)
    private val wal = File(original.path + "-wal")
    private val shm = File(original.path + "-shm")

    private fun fs(vararg files: Pair<String, String>) = FakeRekeyFs(files.toMap())

    @Test
    fun names() {
        assertEquals("/g/n.soil.rekey.tmp", tmp.path)
        assertEquals("/g/n.soil.old.bak", bak.path)
    }

    @Test
    fun happyPath_orderAndResult() {
        val fs = fs(original.path to "OLD", tmp.path to "NEW", shm.path to "", wal.path to "")
        assertEquals(Outcome.Committed, RekeyCommit.commitReplace(fs, original, tmp))
        assertEquals(mapOf(original.path to "NEW"), fs.files)
        assertEquals(
            listOf(
                "fsync:${tmp.path}",
                "delete:${wal.path}",
                "delete:${shm.path}",
                "rename:${original.path}->${bak.path}",
                "rename:${tmp.path}->${original.path}",
                "fsyncDir:${garden.path}",
                "delete:${bak.path}",
            ),
            fs.log,
        )
    }

    @Test
    fun liveWal_refusedAndNothingTouched() {
        val fs = fs(original.path to "OLD", tmp.path to "NEW", wal.path to "live-writes")
        assertEquals(Outcome.RefusedLiveWal, RekeyCommit.commitReplace(fs, original, tmp))
        assertEquals("OLD", fs.files[original.path])
        assertEquals("live-writes", fs.files[wal.path])
        assertEquals("NEW", fs.files[tmp.path]) // the caller drops it, not the commit
        assertFalse(fs.log.any { it.startsWith("rename") || it.startsWith("delete") })
    }

    @Test
    fun staleBak_clearedBeforeTheOriginalMoves() {
        val fs = fs(original.path to "OLD", tmp.path to "NEW", bak.path to "ANCIENT")
        assertEquals(Outcome.Committed, RekeyCommit.commitReplace(fs, original, tmp))
        assertEquals(mapOf(original.path to "NEW"), fs.files)
        assertTrue(fs.log.indexOf("delete:${bak.path}") < fs.log.indexOf("rename:${original.path}->${bak.path}"))
    }

    @Test
    fun firstRenameFails_originalNeverMoved() {
        val fs = fs(original.path to "OLD", tmp.path to "NEW")
        fs.failing += "rename:${original.path}->${bak.path}"
        assertEquals(Outcome.OriginalNotMoved, RekeyCommit.commitReplace(fs, original, tmp))
        assertEquals("OLD", fs.files[original.path])
        assertEquals("NEW", fs.files[tmp.path])
    }

    @Test
    fun secondRenameFails_rolledBack() {
        val fs = fs(original.path to "OLD", tmp.path to "NEW")
        fs.failing += "rename:${tmp.path}->${original.path}"
        assertEquals(Outcome.RolledBack, RekeyCommit.commitReplace(fs, original, tmp))
        assertEquals("OLD", fs.files[original.path])
        assertFalse(bak.path in fs.files)
        assertEquals("NEW", fs.files[tmp.path])
    }

    @Test
    fun secondRenameAndRollbackFail_bothKept() {
        val fs = fs(original.path to "OLD", tmp.path to "NEW")
        fs.failing += "rename:${tmp.path}->${original.path}"
        fs.failing += "rename:${bak.path}->${original.path}"
        assertEquals(Outcome.BothKept, RekeyCommit.commitReplace(fs, original, tmp))
        assertFalse(original.path in fs.files)
        assertEquals("OLD", fs.files[bak.path])
        assertEquals("NEW", fs.files[tmp.path])
        assertFalse(fs.log.any { it.startsWith("delete:") && (it.endsWith(bak.path) || it.endsWith(tmp.path)) })
    }

    @Test
    fun bakDeleteFails_stillCommitted() {
        val fs = fs(original.path to "OLD", tmp.path to "NEW")
        fs.failing += "delete:${bak.path}"
        assertEquals(Outcome.Committed, RekeyCommit.commitReplace(fs, original, tmp))
        assertEquals("NEW", fs.files[original.path])
        assertEquals("OLD", fs.files[bak.path]) // recovery's to clean
    }

    @Test
    fun leftoverOriginals_groupsBothSuffixesAndIgnoresTheRest() {
        val names = listOf(
            "a.soil", "a.soil.rekey.tmp", "a.soil.rekey.tmp-journal",
            "b.soil.old.bak", "c.db.rekey.tmp", "c.db.old.bak",
            "notesprout.db", ".rekey.tmp", "x.soil-wal",
        )
        assertEquals(setOf("a.soil", "b.soil", "c.db"), RekeyNames.leftoverOriginals(names))
    }
}

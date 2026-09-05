package com.symmetricalpalmtree.notesproutsn.crypto

import com.symmetricalpalmtree.notesproutsn.crypto.RekeyRecovery.Plan
import com.symmetricalpalmtree.notesproutsn.crypto.RekeyRecovery.Presence.ABSENT
import com.symmetricalpalmtree.notesproutsn.crypto.RekeyRecovery.Presence.UNVERIFIED
import com.symmetricalpalmtree.notesproutsn.crypto.RekeyRecovery.Presence.VERIFIES
import com.symmetricalpalmtree.notesproutsn.crypto.RekeyRecovery.Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** D1 — the recovery decision table, and the executor's "delete only once the survivor verifies". */
class RekeyRecoveryTest {

    private val garden = File("/g")
    private val original = File(garden, "n.soil")
    private val tmp = RekeyNames.tmpFor(original)
    private val bak = RekeyNames.bakFor(original)

    /** The trusted key opens exactly the files whose content is "GOOD" or "NEW" — never "JUNK". */
    private fun verifier(fs: FakeRekeyFs): (File) -> Boolean = { f -> fs.files[f.path].let { it == "GOOD" || it == "NEW" } }

    @Test
    fun decisionTable() {
        for (t in RekeyRecovery.Presence.values()) for (b in RekeyRecovery.Presence.values()) {
            assertEquals(Plan.DropLeftovers, RekeyRecovery.decide(VERIFIES, t, b))
            assertEquals(Plan.Leave, RekeyRecovery.decide(UNVERIFIED, t, b))
        }
        assertEquals(Plan.RestoreTmp, RekeyRecovery.decide(ABSENT, VERIFIES, ABSENT))
        assertEquals(Plan.RestoreTmp, RekeyRecovery.decide(ABSENT, VERIFIES, VERIFIES))
        assertEquals(Plan.RestoreTmp, RekeyRecovery.decide(ABSENT, VERIFIES, UNVERIFIED))
        assertEquals(Plan.RestoreBak, RekeyRecovery.decide(ABSENT, ABSENT, VERIFIES))
        assertEquals(Plan.RestoreBak, RekeyRecovery.decide(ABSENT, UNVERIFIED, VERIFIES))
        assertEquals(Plan.Leave, RekeyRecovery.decide(ABSENT, ABSENT, ABSENT))
        assertEquals(Plan.Leave, RekeyRecovery.decide(ABSENT, UNVERIFIED, UNVERIFIED))
        assertEquals(Plan.Leave, RekeyRecovery.decide(ABSENT, ABSENT, UNVERIFIED))
        assertEquals(Plan.Leave, RekeyRecovery.decide(ABSENT, UNVERIFIED, ABSENT))
    }

    @Test
    fun nothingBeside_nothingToDo() {
        val fs = FakeRekeyFs(mapOf(original.path to "GOOD"))
        assertEquals(Result.NOTHING_TO_DO, RekeyRecovery.recover(fs, original, verifier(fs)))
        assertTrue(fs.log.isEmpty())
    }

    @Test
    fun originalGood_leftoversDroppedWithSidecars() {
        val fs = FakeRekeyFs(mapOf(
            original.path to "GOOD", tmp.path to "NEW", "${tmp.path}-journal" to "j", bak.path to "GOOD",
        ))
        assertEquals(Result.CLEANED, RekeyRecovery.recover(fs, original, verifier(fs)))
        assertEquals(mapOf(original.path to "GOOD"), fs.files)
    }

    @Test
    fun originalPresentButUnverified_leftAlone() {
        val fs = FakeRekeyFs(mapOf(original.path to "JUNK", tmp.path to "NEW", bak.path to "GOOD"))
        assertEquals(Result.LEFT_ALONE, RekeyRecovery.recover(fs, original, verifier(fs)))
        assertEquals(3, fs.files.size)
        assertFalse(fs.log.any { it.startsWith("delete") || it.startsWith("rename") })
    }

    @Test
    fun deathBetweenRenames_tmpVerifies_tmpRenamedInThenBakDropped() {
        val fs = FakeRekeyFs(mapOf(tmp.path to "NEW", bak.path to "GOOD"))
        assertEquals(Result.RESTORED_TMP, RekeyRecovery.recover(fs, original, verifier(fs)))
        assertEquals(mapOf(original.path to "NEW"), fs.files)
        assertTrue(fs.log.indexOf("rename:${tmp.path}->${original.path}") < fs.log.indexOf("delete:${bak.path}"))
    }

    @Test
    fun deathBetweenRenames_tmpGarbage_bakRenamedBackThenTmpDropped() {
        val fs = FakeRekeyFs(mapOf(tmp.path to "JUNK", "${tmp.path}-journal" to "j", bak.path to "GOOD"))
        assertEquals(Result.RESTORED_BAK, RekeyRecovery.recover(fs, original, verifier(fs)))
        assertEquals(mapOf(original.path to "GOOD"), fs.files)
    }

    @Test
    fun deathBetweenRenames_neitherVerifies_bothKept() {
        val fs = FakeRekeyFs(mapOf(tmp.path to "JUNK", bak.path to "JUNK"))
        assertEquals(Result.LEFT_ALONE, RekeyRecovery.recover(fs, original, verifier(fs)))
        assertEquals(2, fs.files.size)
    }

    @Test
    fun renameInFails_nothingDeleted() {
        val fs = FakeRekeyFs(mapOf(tmp.path to "NEW", bak.path to "GOOD"))
        fs.failing += "rename:${tmp.path}->${original.path}"
        assertEquals(Result.FAILED, RekeyRecovery.recover(fs, original, verifier(fs)))
        assertEquals(setOf(tmp.path, bak.path), fs.files.keys)
    }

    @Test
    fun restoredFileMustStillVerifyBeforeItsFallbackGoes() {
        // A verifier whose answer changes once the file moves (a flaky medium): the bak must stay.
        val fs = FakeRekeyFs(mapOf(tmp.path to "NEW", bak.path to "GOOD"))
        val verify: (File) -> Boolean = { f -> f.path != original.path && fs.files[f.path] != "JUNK" }
        assertEquals(Result.RESTORED_TMP, RekeyRecovery.recover(fs, original, verify))
        assertEquals("GOOD", fs.files[bak.path])
        assertEquals("NEW", fs.files[original.path])
    }

    @Test
    fun idempotent() {
        val fs = FakeRekeyFs(mapOf(tmp.path to "NEW", bak.path to "GOOD"))
        RekeyRecovery.recover(fs, original, verifier(fs))
        assertEquals(Result.NOTHING_TO_DO, RekeyRecovery.recover(fs, original, verifier(fs)))
    }
}

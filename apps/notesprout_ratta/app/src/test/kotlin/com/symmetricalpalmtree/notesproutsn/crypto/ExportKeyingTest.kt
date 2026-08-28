package com.symmetricalpalmtree.notesproutsn.crypto

import com.symmetricalpalmtree.notesproutsn.data.soil.KEY_SCOPE_GLOBAL
import com.symmetricalpalmtree.notesproutsn.data.soil.KEY_SCOPE_NOTEBOOK
import com.symmetricalpalmtree.notesproutsn.data.soil.NotebookMeta
import com.symmetricalpalmtree.notesproutsn.extension.ExporterContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure half of the keying transforms (arc 15 / E2): the spec → plan mapping and the SQL
 * literal the ATTACH statements are built from. The transforms themselves need SQLCipher's native
 * library and are proven on the device and by the arc's Mac-CLI checklist, not here.
 */
class ExportKeyingTest {

    @Test
    fun absentAndKeepBothPlanAPureCopy() {
        assertEquals(ExportKeying.Plan.KEEP, ExportKeying.plan(null, hasNewPassphrase = false))
        assertEquals(ExportKeying.Plan.KEEP, ExportKeying.plan(ExporterContract.KEYING_KEEP, hasNewPassphrase = false))
        // A passphrase someone typed and then armed Keep over is simply not used.
        assertEquals(ExportKeying.Plan.KEEP, ExportKeying.plan(ExporterContract.KEYING_KEEP, hasNewPassphrase = true))
    }

    @Test
    fun rekeyPlansOnlyWithAPassphraseInHand() {
        assertEquals(
            ExportKeying.Plan.REKEY,
            ExportKeying.plan(ExporterContract.KEYING_REKEY, hasNewPassphrase = true),
        )
        // A rekey with nothing collected is a host bug surfaced loudly, never a silent Keep.
        assertThrows(IllegalArgumentException::class.java) {
            ExportKeying.plan(ExporterContract.KEYING_REKEY, hasNewPassphrase = false)
        }
    }

    @Test
    fun plainPlansThePlaintextTransform() {
        assertEquals(ExportKeying.Plan.PLAIN, ExportKeying.plan(ExporterContract.KEYING_PLAIN, hasNewPassphrase = false))
    }

    @Test
    fun anUnknownKeyingIsRefused() {
        assertThrows(IllegalArgumentException::class.java) { ExportKeying.plan("nonsense", hasNewPassphrase = true) }
        assertThrows(IllegalArgumentException::class.java) { ExportKeying.plan("", hasNewPassphrase = false) }
    }

    @Test
    fun restampRewritesOnlyTheEncryptionFields() {
        val meta = NotebookMeta(
            notebookId = "nb", name = "Field notes", createdAt = 1L, updatedAt = 2L,
            encrypted = true, keyScope = KEY_SCOPE_GLOBAL, exportedAt = 3L, appVersionCode = 4,
        )
        // Plain: `encrypted: false` is the governing fact and the scope key is simply absent from
        // the JSON. (The family codec omits nulls and defaults an absent scope to GLOBAL on read —
        // an explicit "no scope" is not representable, and `encrypted` is what a reader trusts.)
        val plainJson = ExportKeying.restampedJson(meta.toJson(), encrypted = false, keyScope = null)!!
        assertTrue(plainJson.contains("\"encrypted\":false"))
        assertFalse(plainJson.contains("keyScope"))
        assertEquals(meta.copy(encrypted = false), NotebookMeta.fromJson(plainJson))
        // Rekey: its own scope, everything else untouched.
        val rekeyed = NotebookMeta.fromJson(
            ExportKeying.restampedJson(meta.toJson(), encrypted = true, keyScope = KEY_SCOPE_NOTEBOOK)!!
        )
        assertEquals(meta.copy(keyScope = KEY_SCOPE_NOTEBOOK), rekeyed)
    }

    @Test
    fun restampOfAnUnparseableRowIsSkippedNeverGuessed() {
        assertNull(ExportKeying.restampedJson("not json at all", encrypted = false, keyScope = null))
        assertNull(ExportKeying.restampedJson("{\"formatVersion\":1}", encrypted = false, keyScope = null))
    }

    @Test
    fun sqlLiteralQuotesAndDoubles() {
        assertEquals("'plain'", ExportKeying.sqlLiteral("plain"))
        assertEquals("'it''s'", ExportKeying.sqlLiteral("it's"))
        assertEquals("''''''", ExportKeying.sqlLiteral("''"))
        // An empty literal IS how a plaintext key is spelled in ATTACH ... KEY ''.
        assertEquals("''", ExportKeying.sqlLiteral(""))
        // Nothing else is escaped — a passphrase crosses into the literal byte-for-byte.
        assertEquals("'pa\"ss\\phrase'", ExportKeying.sqlLiteral("pa\"ss\\phrase"))
    }
}

package com.symmetricalpalmtree.notesproutsn.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The config codec. The decode's one promise is that it never throws and anything unusable reads
 * as a fresh config — whose worst case is re-copying everything, the safe direction for a backup.
 */
class BackupConfigTest {

    @Test
    fun `round-trips a full config`() {
        val config = BackupConfig(
            treeUri = "content://com.android.externalstorage.documents/tree/primary%3ABackups",
            lastRunAt = 1_700_000_000_000L,
            lastCopied = 12,
            lastSkipped = 3,
            stamps = mapOf("nb-1" to 100L, "nb-2" to 200L),
        )
        val bytes = BackupConfig.encode(config)
        assertNotNull(bytes)
        assertEquals(config, BackupConfig.decode(bytes))
    }

    @Test
    fun `round-trips the empty default`() {
        assertEquals(BackupConfig(), BackupConfig.decode(BackupConfig.encode(BackupConfig())))
    }

    @Test
    fun `null, empty and malformed bytes read as a fresh config`() {
        assertEquals(BackupConfig(), BackupConfig.decode(null))
        assertEquals(BackupConfig(), BackupConfig.decode(ByteArray(0)))
        assertEquals(BackupConfig(), BackupConfig.decode("not json".toByteArray()))
        assertEquals(BackupConfig(), BackupConfig.decode("""{"version":}""".toByteArray()))
    }

    @Test
    fun `a newer grammar version reads as a fresh config`() {
        val newer = """{"version":${BackupConfig.VERSION + 1},"treeUri":"x"}"""
        assertEquals(BackupConfig(), BackupConfig.decode(newer.toByteArray()))
    }

    @Test
    fun `unknown keys from a newer same-version build are ignored`() {
        val withExtra =
            """{"version":1,"treeUri":"t","lastRunAt":5,"stamps":{"a":1},"futureField":true}"""
        val config = BackupConfig.decode(withExtra.toByteArray())
        assertEquals("t", config.treeUri)
        assertEquals(5L, config.lastRunAt)
        assertEquals(mapOf("a" to 1L), config.stamps)
    }

    @Test
    fun `a blob written before cloudEnabled existed decodes with it false`() {
        // The additive-growth promise (arc 25 / V2): an old row has no such field, and the default
        // fills in as "nobody asked for a cloud backup" — the honest reading, and the safe one.
        val old = """{"version":1,"treeUri":"content://tree/x","lastRunAt":5,"stamps":{"nb":9}}"""
        val config = BackupConfig.decode(old.toByteArray())
        assertEquals("content://tree/x", config.treeUri)
        assertEquals(mapOf("nb" to 9L), config.stamps)
        assertFalse(config.cloudEnabled)
    }

    @Test
    fun `cloudEnabled round-trips true`() {
        val config = BackupConfig(cloudEnabled = true)
        val decoded = BackupConfig.decode(BackupConfig.encode(config))
        assertTrue(decoded.cloudEnabled)
        assertEquals(config, decoded)
    }

    @Test
    fun `an unknown future field does not break the decode`() {
        val future = """{"version":1,"cloudEnabled":true,"cloudFolder":"Nomad"}"""
        val config = BackupConfig.decode(future.toByteArray())
        assertTrue(config.cloudEnabled)
    }
}

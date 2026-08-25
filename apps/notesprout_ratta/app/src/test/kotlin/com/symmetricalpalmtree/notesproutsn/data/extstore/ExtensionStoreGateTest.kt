package com.symmetricalpalmtree.notesproutsn.data.extstore

import com.symmetricalpalmtree.notesproutsn.extension.ExtensionContract
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TreeMap

/**
 * `ExtensionStoreBinder` is an `IExtensionStore.Stub` (an `android.os.Binder`) and cannot be
 * constructed on the JVM; every check and cap it applies lives in [ExtensionStoreGate], which is
 * what these drive — over a fake [KvDao] with an injectable calling uid. The binder's own two steps
 * (the ashmem copy and the deferred region close) are on-device only: the debug library's
 * "Extension store self-test" is their check.
 */
class ExtensionStoreGateTest {

    /** In-memory `KvDao`; [keysWithPrefix] is the same exact, case-sensitive "starts with" the real query is. */
    private class FakeDao : KvDao {
        val rows = TreeMap<String, ByteArray>()
        var failNext: RuntimeException? = null
        override fun get(key: String): ByteArray? { fail(); return rows[key] }
        override fun upsert(row: KvEntity) { fail(); rows[row.key] = row.value }
        override fun delete(key: String) { fail(); rows.remove(key) }
        override fun keysWithPrefix(prefix: String): List<String> {
            fail()
            return rows.keys.filter { it.startsWith(prefix) }
        }
        override fun count(): Int = rows.size
        private fun fail() { failNext?.let { failNext = null; throw it } }
    }

    private val ext = 10_123
    private var caller = ext
    private val dao = FakeDao()
    private val gate = ExtensionStoreGate(dao, ext, { caller }, { 42L })

    @Test
    fun roundTrip() {
        gate.put("a", byteArrayOf(1, 2, 3))
        assertArrayEquals(byteArrayOf(1, 2, 3), gate.get("a"))
        gate.put("a", byteArrayOf(9))
        assertArrayEquals(byteArrayOf(9), gate.get("a"))
        gate.delete("a")
        assertNull(gate.get("a"))
        gate.delete("a") // absent: a no-op, not a failure
    }

    @Test
    fun uidMismatch_isSecurityException_onEveryMethod() {
        caller = ext + 1
        assertThrows(SecurityException::class.java) { gate.get("k") }
        assertThrows(SecurityException::class.java) { gate.put("k", byteArrayOf(1)) }
        assertThrows(SecurityException::class.java) { gate.delete("k") }
        assertThrows(SecurityException::class.java) { gate.keys("") }
        assertThrows(SecurityException::class.java) { gate.putLarge("k", byteArrayOf(1)) }
        assertThrows(SecurityException::class.java) { gate.getLarge("k") }
        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun revoked_isSecurityException_onEveryMethod() {
        gate.put("k", byteArrayOf(1))
        gate.revoke()
        assertTrue(gate.revoked)
        assertThrows(SecurityException::class.java) { gate.get("k") }
        assertThrows(SecurityException::class.java) { gate.put("k", byteArrayOf(2)) }
        assertThrows(SecurityException::class.java) { gate.delete("k") }
        assertThrows(SecurityException::class.java) { gate.keys("") }
        assertThrows(SecurityException::class.java) { gate.putLarge("k", byteArrayOf(2)) }
        assertThrows(SecurityException::class.java) { gate.getLarge("k") }
        assertArrayEquals(byteArrayOf(1), dao.rows["k"]) // untouched
    }

    @Test
    fun keyCaps() {
        assertThrows(IllegalArgumentException::class.java) { gate.put("", byteArrayOf(1)) }
        assertThrows(IllegalArgumentException::class.java) { gate.get("") }
        assertThrows(IllegalArgumentException::class.java) { gate.delete(null) }
        val max = "k".repeat(ExtensionContract.STORE_MAX_KEY_CHARS)
        gate.put(max, byteArrayOf(1))
        assertThrows(IllegalArgumentException::class.java) { gate.put(max + "x", byteArrayOf(1)) }
        assertThrows(IllegalArgumentException::class.java) { gate.get(max + "x") }
        assertThrows(IllegalArgumentException::class.java) { gate.putLarge("", byteArrayOf(1)) }
    }

    @Test
    fun valueCap_inlinePath() {
        gate.put("ok", ByteArray(ExtensionContract.STORE_MAX_INLINE_BYTES))
        assertThrows(IllegalArgumentException::class.java) {
            gate.put("big", ByteArray(ExtensionContract.STORE_MAX_INLINE_BYTES + 1))
        }
        assertThrows(IllegalArgumentException::class.java) { gate.put("nul", null) }
        assertNull(dao.rows["big"])
    }

    @Test
    fun valueCap_largePath() {
        gate.putLarge("ok", ByteArray(ExtensionContract.STORE_MAX_VALUE_BYTES))
        assertEquals(ExtensionContract.STORE_MAX_VALUE_BYTES, dao.rows["ok"]!!.size)
        assertThrows(IllegalArgumentException::class.java) {
            gate.putLarge("big", ByteArray(ExtensionContract.STORE_MAX_VALUE_BYTES + 1))
        }
        assertThrows(IllegalArgumentException::class.java) { gate.putLarge("nul", null) }
        assertNull(dao.rows["big"])
    }

    @Test
    fun get_aboveInlineCap_throwsStoreValueLarge_getLargeReadsAnySize() {
        val big = ByteArray(ExtensionContract.STORE_MAX_INLINE_BYTES + 1) { 7 }
        gate.putLarge("big", big)
        val e = assertThrows(IllegalStateException::class.java) { gate.get("big") }
        assertEquals(ExtensionContract.STORE_VALUE_LARGE, e.message)
        assertArrayEquals(big, gate.getLarge("big"))
        // Small values read on both paths; an empty value is a value, not an absence.
        gate.put("small", byteArrayOf(1, 2))
        assertArrayEquals(byteArrayOf(1, 2), gate.get("small"))
        assertArrayEquals(byteArrayOf(1, 2), gate.getLarge("small"))
        gate.put("empty", ByteArray(0))
        assertArrayEquals(ByteArray(0), gate.get("empty"))
        assertArrayEquals(ByteArray(0), gate.getLarge("empty"))
        assertNull(gate.getLarge("absent"))
    }

    @Test
    fun keysCap_rejectsNewKey_butAllowsReplace() {
        for (i in 0 until ExtensionContract.STORE_MAX_KEYS) dao.rows["k$i"] = byteArrayOf(0)
        assertThrows(IllegalStateException::class.java) { gate.put("new", byteArrayOf(1)) }
        assertThrows(IllegalStateException::class.java) { gate.putLarge("new", byteArrayOf(1)) }
        gate.put("k0", byteArrayOf(7)) // an existing key: replace is fine at the cap
        assertArrayEquals(byteArrayOf(7), dao.rows["k0"])
        assertEquals(ExtensionContract.STORE_MAX_KEYS, dao.rows.size)
    }

    @Test
    fun keys_orderedAscending_prefixFilters() {
        for (k in listOf("page:b", "page:a", "other:z", "page:c")) gate.put(k, byteArrayOf(1))
        assertEquals(listOf("other:z", "page:a", "page:b", "page:c"), gate.keys(""))
        assertEquals(listOf("page:a", "page:b", "page:c"), gate.keys("page:"))
        assertEquals(listOf("other:z", "page:a", "page:b", "page:c"), gate.keys(null))
        assertTrue(gate.keys("zzz").isEmpty())
    }

    @Test
    fun keys_prefixIsLiteralAndCaseSensitive() {
        for (k in listOf("a%b", "axb", "a_b", "Page:1", "page:2")) gate.put(k, byteArrayOf(1))
        assertEquals(listOf("a%b"), gate.keys("a%"))
        assertEquals(listOf("a_b"), gate.keys("a_"))
        assertEquals(listOf("page:2"), gate.keys("page:"))
    }

    @Test
    fun daoFailure_becomesIllegalState() {
        // Something Binder cannot marshal (SQLiteFullException, say) must surface as
        // IllegalStateException — never as a silently failed transaction the caller reads as success.
        class DiskFull : RuntimeException("disk full")
        dao.failNext = DiskFull()
        assertThrows(IllegalStateException::class.java) { gate.put("a", byteArrayOf(1)) }
        dao.failNext = DiskFull()
        assertThrows(IllegalStateException::class.java) { gate.get("a") }
        dao.failNext = DiskFull()
        assertThrows(IllegalStateException::class.java) { gate.getLarge("a") }
        dao.failNext = DiskFull()
        assertThrows(IllegalStateException::class.java) { gate.delete("a") }
        dao.failNext = DiskFull()
        assertThrows(IllegalStateException::class.java) { gate.keys("") }
    }
}

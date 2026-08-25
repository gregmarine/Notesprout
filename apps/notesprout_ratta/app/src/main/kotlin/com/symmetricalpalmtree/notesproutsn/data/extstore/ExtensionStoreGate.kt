package com.symmetricalpalmtree.notesproutsn.data.extstore

import com.symmetricalpalmtree.notesproutsn.extension.ExtensionContract

/**
 * The checks and caps behind [ExtensionStoreBinder], with **no Android types precisely so they run
 * on the JVM** — the binder itself is an `android.os.Binder` and cannot be constructed in a unit
 * test, so everything worth testing lives here.
 *
 * Every method first requires the caller's uid to be [extUid] and the gate not to be [revoked] —
 * else `SecurityException`. Caps (`ExtensionContract.STORE_*`): key `1..STORE_MAX_KEY_CHARS`
 * chars; value `≤ STORE_MAX_INLINE_BYTES` on the `byte[]` path ([put] / [get]) and
 * `≤ STORE_MAX_VALUE_BYTES` on the large path ([putLarge] / [getLarge], whose bytes arrive and
 * leave in an ashmem region the binder copies in and out of); [get] of a *stored* value above the
 * inline cap → `IllegalStateException(STORE_VALUE_LARGE)` ("use getLarge"); a put of a **new** key
 * when the store already holds `STORE_MAX_KEYS` → `IllegalStateException` (replacing an existing
 * key is still fine at the cap). Bad arguments → `IllegalArgumentException`.
 *
 * A DAO failure (SQLite full / locked / I/O) is rethrown as `IllegalStateException` too. Those
 * three — `SecurityException`, `IllegalArgumentException`, `IllegalStateException` — are the whole
 * set Binder carries across the boundary intact. Anything else kills the transaction **silently**
 * and the extension reads the empty reply as null / success, which in Paper made a page read as
 * blank and then get saved over. The extension treats every one of them as "store unavailable".
 */
class ExtensionStoreGate(
    private val dao: KvDao,
    private val extUid: Int,
    private val callingUid: () -> Int,
    private val now: () -> Long = System::currentTimeMillis,
) {
    @Volatile
    var revoked: Boolean = false
        private set

    /** After this every method throws `SecurityException`. Called from the client's `finally`. */
    fun revoke() {
        revoked = true
    }

    /** The inline path: a stored value above [ExtensionContract.STORE_MAX_INLINE_BYTES] is refused
     *  with the exact message [ExtensionContract.STORE_VALUE_LARGE]. */
    fun get(key: String?): ByteArray? {
        check()
        val k = validKey(key)
        val v = io { dao.get(k) } ?: return null
        if (v.size > ExtensionContract.STORE_MAX_INLINE_BYTES) {
            throw IllegalStateException(ExtensionContract.STORE_VALUE_LARGE)
        }
        return v
    }

    /** The large path: any stored size (the binder wraps it in a region). */
    fun getLarge(key: String?): ByteArray? {
        check()
        val k = validKey(key)
        return io { dao.get(k) }
    }

    /** The inline path: `value.size ≤ STORE_MAX_INLINE_BYTES`. */
    fun put(key: String?, value: ByteArray?) {
        check()
        requireNotNull(value) { "value is null" }
        require(value.size <= ExtensionContract.STORE_MAX_INLINE_BYTES) {
            "value exceeds ${ExtensionContract.STORE_MAX_INLINE_BYTES} bytes — use putLarge"
        }
        upsert(validKey(key), value)
    }

    /** The large path: `value.size ≤ STORE_MAX_VALUE_BYTES` (the binder has already copied the
     *  bytes out of the caller's region). */
    fun putLarge(key: String?, value: ByteArray?) {
        check()
        requireNotNull(value) { "value is null" }
        require(value.size <= ExtensionContract.STORE_MAX_VALUE_BYTES) {
            "value exceeds ${ExtensionContract.STORE_MAX_VALUE_BYTES} bytes"
        }
        upsert(validKey(key), value)
    }

    @Synchronized
    private fun upsert(k: String, value: ByteArray) {
        io {
            if (dao.get(k) == null && dao.count() >= ExtensionContract.STORE_MAX_KEYS) {
                throw IllegalStateException("store holds ${ExtensionContract.STORE_MAX_KEYS} keys")
            }
            dao.upsert(KvEntity(k, value, now()))
        }
    }

    fun delete(key: String?) {
        check()
        val k = validKey(key)
        io { dao.delete(k) }
    }

    fun keys(prefix: String?): List<String> {
        check()
        val p = prefix ?: ""
        return io { dao.keysWithPrefix(p) }
    }

    /** Runs a DAO call; a failure that Binder could not carry becomes `IllegalStateException`. */
    private inline fun <T> io(block: () -> T): T =
        try {
            block()
        } catch (e: IllegalStateException) {
            throw e
        } catch (e: Exception) {
            throw IllegalStateException("store I/O failed: ${e.javaClass.simpleName}", e)
        }

    private fun check() {
        if (revoked) throw SecurityException("store binder revoked")
        if (callingUid() != extUid) throw SecurityException("store binder belongs to another uid")
    }

    private fun validKey(key: String?): String {
        requireNotNull(key) { "key is null" }
        require(key.isNotEmpty()) { "key is empty" }
        require(key.length <= ExtensionContract.STORE_MAX_KEY_CHARS) {
            "key exceeds ${ExtensionContract.STORE_MAX_KEY_CHARS} chars"
        }
        return key
    }
}

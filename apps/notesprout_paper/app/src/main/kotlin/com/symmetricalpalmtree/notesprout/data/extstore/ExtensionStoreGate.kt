package com.symmetricalpalmtree.notesprout.data.extstore

import com.symmetricalpalmtree.notesprout.extension.ExtensionContract

/**
 * The checks and caps behind [ExtensionStoreBinder], with no Android types so they run on the JVM.
 *
 * Every method first requires the caller's uid to be [extUid] and the gate not to be [revoked] —
 * else `SecurityException`. Caps (`ExtensionContract.STORE_*`): key `1..STORE_MAX_KEY_CHARS`
 * chars, value `≤ STORE_MAX_VALUE_BYTES`, and a `put` of a *new* key when the store already holds
 * `STORE_MAX_KEYS` keys → `IllegalStateException`. Bad arguments → `IllegalArgumentException`.
 * All of these are in the set AIDL marshals across the boundary; the extension treats any of them as
 * "store unavailable".
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

    fun get(key: String?): ByteArray? {
        check()
        return dao.get(validKey(key))
    }

    @Synchronized
    fun put(key: String?, value: ByteArray?) {
        check()
        val k = validKey(key)
        requireNotNull(value) { "value is null" }
        require(value.size <= ExtensionContract.STORE_MAX_VALUE_BYTES) {
            "value exceeds ${ExtensionContract.STORE_MAX_VALUE_BYTES} bytes"
        }
        if (dao.get(k) == null && dao.count() >= ExtensionContract.STORE_MAX_KEYS) {
            throw IllegalStateException("store holds ${ExtensionContract.STORE_MAX_KEYS} keys")
        }
        dao.upsert(KvEntity(k, value, now()))
    }

    fun delete(key: String?) {
        check()
        dao.delete(validKey(key))
    }

    fun keys(prefix: String?): List<String> {
        check()
        return dao.keysLike(likePattern(prefix ?: ""))
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

    companion object {
        /** `LIKE` pattern matching keys that start with [prefix]: `%`, `_`, `\` escaped with `\`, then `%`. */
        fun likePattern(prefix: String): String {
            val sb = StringBuilder(prefix.length + 4)
            for (c in prefix) {
                if (c == '%' || c == '_' || c == '\\') sb.append('\\')
                sb.append(c)
            }
            return sb.append('%').toString()
        }
    }
}

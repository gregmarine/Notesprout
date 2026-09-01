package com.symmetricalpalmtree.notesproutsn.ext.tags

import android.os.IBinder
import com.symmetricalpalmtree.notesproutsn.extension.ExtensionContract
import com.symmetricalpalmtree.notesproutsn.extension.IExtensionStore
import com.symmetricalpalmtree.notesproutsn.extension.LargeValue

/**
 * A JVM stand-in for the host's store (arc 21 / W1 tests) — the `:ext-scratchpad` fake, kept as its
 * own copy because a test double is not shared logic: `:ext-tags` may not depend on another
 * extension, and a fake that grew a scratch-pad rule would quietly change what these tests prove.
 *
 * It keeps the one rule [TagStore] actually depends on: `get` of a value above the inline cap throws
 * `IllegalStateException(STORE_VALUE_LARGE)`, which is what sends a read to the large path. Real
 * ashmem cannot run on the JVM, so [LargeValue] never appears — the large path is covered on-device.
 */
class FakeExtensionStore : IExtensionStore {

    val values = LinkedHashMap<String, ByteArray>()

    /** Set to make the next store call fail the way a revoked or broken binder would. */
    var failWith: (() -> Throwable)? = null

    override fun get(key: String): ByteArray? {
        failWith?.let { throw it() }
        val v = values[key] ?: return null
        check(v.size <= ExtensionContract.STORE_MAX_INLINE_BYTES) { ExtensionContract.STORE_VALUE_LARGE }
        return v
    }

    override fun put(key: String, value: ByteArray) {
        failWith?.let { throw it() }
        require(key.isNotEmpty() && key.length <= ExtensionContract.STORE_MAX_KEY_CHARS) { "bad key" }
        require(value.size <= ExtensionContract.STORE_MAX_INLINE_BYTES) { "value too large — use putLarge" }
        values[key] = value
    }

    override fun delete(key: String) {
        failWith?.let { throw it() }
        values.remove(key)
    }

    override fun keys(prefix: String): List<String> {
        failWith?.let { throw it() }
        return values.keys.filter { it.startsWith(prefix) }.sorted()
    }

    override fun putLarge(key: String, value: LargeValue?) = error("the JVM has no ashmem")

    override fun getLarge(key: String): LargeValue? = error("the JVM has no ashmem")

    override fun asBinder(): IBinder? = null
}

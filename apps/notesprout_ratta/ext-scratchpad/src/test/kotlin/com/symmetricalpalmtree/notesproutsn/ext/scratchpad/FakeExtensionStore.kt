package com.symmetricalpalmtree.notesproutsn.ext.scratchpad

import android.os.IBinder
import com.symmetricalpalmtree.notesproutsn.extension.ExtensionContract
import com.symmetricalpalmtree.notesproutsn.extension.IExtensionStore
import com.symmetricalpalmtree.notesproutsn.extension.LargeValue

/**
 * A JVM stand-in for the host's store (arc 11 / J4 tests) — a plain map behind the real
 * `IExtensionStore` interface, which is implementable off-device because it is an interface and
 * nothing here constructs an Android class.
 *
 * It keeps the two rules the pad's code actually depends on: `get` of a value above the inline cap
 * throws `IllegalStateException(STORE_VALUE_LARGE)` (which is what sends [ScratchStore.readPage] to
 * the large path), and `putLarge` / `getLarge` carry the value whole. Real ashmem cannot run on the
 * JVM, so [LargeValue] never appears — the large path is covered on-device by the store self-test.
 */
class FakeExtensionStore : IExtensionStore {

    val values = LinkedHashMap<String, ByteArray>()

    /** Set to make the next store call fail the way a revoked or broken binder would. */
    var failWith: (() -> Throwable)? = null

    /** Run inside [put], after the value has landed — how a test drops ink into the window a real
     *  flush's IO hop opens (the re-flush-until-clean rule). */
    var onPut: ((String) -> Unit)? = null

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
        onPut?.invoke(key)
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

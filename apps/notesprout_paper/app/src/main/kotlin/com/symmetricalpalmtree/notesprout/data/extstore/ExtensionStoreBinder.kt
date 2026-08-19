package com.symmetricalpalmtree.notesprout.data.extstore

import android.os.Binder
import android.os.Parcel
import android.os.SharedMemory
import com.symmetricalpalmtree.notesprout.extension.IExtensionStore
import com.symmetricalpalmtree.notesprout.extension.LargeValue
import com.symmetricalpalmtree.notesprout.extension.SharedBytes

/**
 * The `IExtensionStore` binder the host hands an extension as an in-parameter — minted **per bind**
 * by the client, bound to that extension's uid ([extUid], from `PackageManager.getPackageUid`), and
 * [revoke]d in the same `finally` as the unbind. Every method runs synchronously on the host's
 * Binder thread over the blocking DAO (never Main). Checks and caps live in [ExtensionStoreGate].
 *
 * The two large-value methods (arc 6 / S0) do the ashmem copy around the gate: [putLarge] copies the
 * extension's region in and closes the host's handle on it at once; [getLarge] wraps the stored bytes
 * in a region the host creates and parks it in a per-Binder-thread slot that [onTransact]'s `finally`
 * closes **after** the reply (holding a dup of the descriptor) is written — the `RenderedTemplate`
 * discipline.
 */
class ExtensionStoreBinder(db: ExtensionStoreDatabase, extUid: Int) : IExtensionStore.Stub() {

    private val gate = ExtensionStoreGate(db.kv(), extUid, Binder::getCallingUid)
    private val pending = ThreadLocal<SharedMemory>()

    /** After this every method throws `SecurityException`. */
    fun revoke() = gate.revoke()

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        try {
            return super.onTransact(code, data, reply, flags)
        } finally {
            pending.get()?.close()
            pending.remove()
        }
    }

    override fun get(key: String?): ByteArray? = gate.get(key)

    override fun put(key: String?, value: ByteArray?) = gate.put(key, value)

    override fun delete(key: String?) = gate.delete(key)

    override fun keys(prefix: String?): MutableList<String> = gate.keys(prefix).toMutableList()

    override fun putLarge(key: String?, value: LargeValue?) {
        requireNotNull(value) { "value is null" }
        // Copy in, then close OUR handle on the extension's region whatever the gate says.
        val bytes = SharedBytes.readAndClose(value)
        gate.putLarge(key, bytes)
    }

    override fun getLarge(key: String?): LargeValue? {
        val bytes = gate.getLarge(key) ?: return null
        val v = SharedBytes.write(bytes)
        pending.set(v.memory)   // closed in onTransact's finally, after the reply is marshalled
        return v
    }
}

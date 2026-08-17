package com.symmetricalpalmtree.notesprout.data.extstore

import android.os.Binder
import com.symmetricalpalmtree.notesprout.extension.IExtensionStore

/**
 * The `IExtensionStore` binder the host hands an extension as an in-parameter — minted **per bind**
 * by the client, bound to that extension's uid ([extUid], from `PackageManager.getPackageUid`), and
 * [revoke]d in the same `finally` as the unbind. Every method runs synchronously on the host's
 * Binder thread over the blocking DAO (never Main). Checks and caps live in [ExtensionStoreGate].
 */
class ExtensionStoreBinder(db: ExtensionStoreDatabase, extUid: Int) : IExtensionStore.Stub() {

    private val gate = ExtensionStoreGate(db.kv(), extUid, Binder::getCallingUid)

    /** After this every method throws `SecurityException`. */
    fun revoke() = gate.revoke()

    override fun get(key: String?): ByteArray? = gate.get(key)

    override fun put(key: String?, value: ByteArray?) = gate.put(key, value)

    override fun delete(key: String?) = gate.delete(key)

    override fun keys(prefix: String?): MutableList<String> = gate.keys(prefix).toMutableList()
}

package com.symmetricalpalmtree.notesproutsn.data.extstore

import android.os.Binder
import android.os.Parcel
import android.os.SharedMemory
import com.symmetricalpalmtree.notesproutsn.extension.ExtensionContract
import com.symmetricalpalmtree.notesproutsn.extension.IExtensionStore
import com.symmetricalpalmtree.notesproutsn.extension.SharedBytes
import com.symmetricalpalmtree.notesproutsn.extension.StorePayload
import com.symmetricalpalmtree.notesproutsn.extension.StoreResult
import com.symmetricalpalmtree.notesproutsn.extension.StoreSchema

/**
 * The `IExtensionStore` binder the host hands an extension as an in-parameter — minted **per bind**,
 * bound to that extension's uid ([extUid], from `PackageManager.getPackageUid`), and [revoke]d in
 * the same `finally` as the unbind. Every method runs synchronously on the host's Binder thread over
 * the store's one connection (never Main). Every check and cap lives in [ExtensionStoreGate].
 *
 * The ashmem step sits around the gate: an incoming [StorePayload]'s region is copied in and the
 * host's handle on it closed at once (`putLarge`'s handshake, arc 11 / J2); an outgoing chunk above
 * the inline cap is wrapped in a region the host creates and parked in a per-Binder-thread slot that
 * [onTransact]'s `finally` closes **after** the reply (which holds a dup of the descriptor) is
 * written.
 */
class ExtensionStoreBinder(db: ExtensionStoreDatabase, extUid: Int) : IExtensionStore.Stub() {

    private val gate = ExtensionStoreGate(db.executor(), extUid, Binder::getCallingUid)
    private val pending = ThreadLocal<SharedMemory>()

    /** After this every method throws `SecurityException`, and every parked result is dropped. */
    fun revoke() = gate.revoke()

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        try {
            return super.onTransact(code, data, reply, flags)
        } finally {
            pending.get()?.close()
            pending.remove()
        }
    }

    override fun schemaVersion(): Int = gate.schemaVersion()

    override fun applySchema(schema: StoreSchema?) = gate.applySchema(schema)

    override fun exec(batch: StorePayload?): LongArray = gate.exec(bytesOf(batch))

    override fun query(statement: StorePayload?): StoreResult = result(gate.query(bytesOf(statement)))

    override fun next(handle: Int): StoreResult = result(gate.next(handle))

    override fun close(handle: Int) = gate.close(handle)

    /** Copy an incoming payload out, closing OUR handle on its region whatever the gate then says. */
    private fun bytesOf(payload: StorePayload?): ByteArray {
        requireNotNull(payload) { "payload is null" }
        return region { payload.readAndClose() }
    }

    /** Wrap an outgoing chunk: inline at or under the cap, else a region parked until the reply is
     *  marshalled. */
    private fun result(chunk: ExtensionStoreGate.Chunk): StoreResult {
        val payload = if (chunk.bytes.size <= ExtensionContract.STORE_MAX_INLINE_BYTES) {
            StorePayload(chunk.bytes, null)
        } else {
            val v = region { SharedBytes.write(chunk.bytes) }
            pending.get()?.close()   // in-process callers (the debug probe) never pass onTransact
            pending.set(v.memory)    // closed in onTransact's finally, after the reply is marshalled
            StorePayload(null, v)
        }
        return StoreResult(payload, chunk.handle, chunk.more)
    }

    /** The ashmem step sits **outside** the gate's `io {}` mapping and needs its own: a mapping or
     *  creation failure is an `ErrnoException` — checked, and not in Binder's marshalable set — so
     *  it would kill the transaction silently and leave the extension reading an empty reply as
     *  success. Same rule as the gate: every failure becomes `IllegalStateException`. */
    private inline fun <T> region(block: () -> T): T =
        try {
            block()
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (e: Exception) {
            throw IllegalStateException("store region: ${e.javaClass.simpleName}: ${e.message}")
        }
}

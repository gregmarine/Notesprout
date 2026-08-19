package com.symmetricalpalmtree.notesprout.extension

import android.os.Parcel
import android.os.Parcelable
import android.os.SharedMemory

/**
 * A large extension-store value (arc 6 / S0 — `IExtensionStore.putLarge` / `getLarge`): the bytes
 * live in [memory]`[0 until byteCount]`. A 4 MiB `byte[]` cannot cross a Binder (~1 MB transaction
 * budget), so values above [ExtensionContract.STORE_MAX_INLINE_BYTES] travel as an ashmem region —
 * the [RenderedTemplate] handshake, in both directions: the **sender** creates the region, writes,
 * `setProtect(PROT_READ)`, hands it over and closes its own handle once the transaction is
 * marshalled; the **receiver** maps read-only, copies out [byteCount] bytes, unmaps and closes (in a
 * `finally`). [SharedBytes] writes the handshake once for both sides.
 *
 * Wire form: `SharedMemory · int byteCount` (a compatible tail may be appended later).
 * `requireValid` at construction (so at unmarshal too): `byteCount` in `1..STORE_MAX_VALUE_BYTES`
 * and `≤ memory.size`.
 */
class LargeValue(
    val memory: SharedMemory,
    val byteCount: Int,
) : Parcelable {

    init {
        requireValid(byteCount, memory.size)
    }

    @Suppress("DEPRECATION")
    private constructor(parcel: Parcel) : this(
        memory = parcel.readParcelable(SharedMemory::class.java.classLoader)!!,
        byteCount = parcel.readInt(),
    )

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeParcelable(memory, flags)
        dest.writeInt(byteCount)
    }

    /** Carries a file descriptor (the ashmem region) — must say so, or `Bundle.hasFileDescriptors()` lies. */
    override fun describeContents(): Int = Parcelable.CONTENTS_FILE_DESCRIPTOR

    companion object {
        /** The constructor's checks, pure so they are JVM-testable. */
        fun requireValid(byteCount: Int, memorySize: Int) {
            require(byteCount in 1..ExtensionContract.STORE_MAX_VALUE_BYTES) {
                "byteCount must be 1..${ExtensionContract.STORE_MAX_VALUE_BYTES} ($byteCount)"
            }
            require(byteCount <= memorySize) { "byteCount $byteCount exceeds the region ($memorySize)" }
        }

        @JvmField
        val CREATOR: Parcelable.Creator<LargeValue> = object : Parcelable.Creator<LargeValue> {
            override fun createFromParcel(parcel: Parcel): LargeValue = LargeValue(parcel)
            override fun newArray(size: Int): Array<LargeValue?> = arrayOfNulls(size)
        }
    }
}

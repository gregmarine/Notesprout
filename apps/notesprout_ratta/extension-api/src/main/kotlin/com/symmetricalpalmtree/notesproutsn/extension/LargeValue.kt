package com.symmetricalpalmtree.notesproutsn.extension

import android.os.Parcel
import android.os.Parcelable
import android.os.SharedMemory

/**
 * A payload too large for a Binder transaction, carried as an ashmem region (arc 11 / J2; since
 * arc 22 / X1 the chunk carrier behind [StorePayload]): the bytes live in [memory]`[0 until byteCount]`.
 *
 * A 4 MiB `byte[]` cannot cross a Binder (~1 MB transaction budget), so a payload above
 * [ExtensionContract.STORE_MAX_INLINE_BYTES] travels as an ashmem region, the same handshake in
 * both directions: the **sender** creates the region, writes, `setProtect(PROT_READ)`, hands it
 * over and closes its own handle once the transaction carrying it is marshalled; the **receiver**
 * maps read-only, copies out [byteCount] bytes, unmaps and closes (in a `finally`). [SharedBytes]
 * writes that handshake once for both sides. The tag manager's `snapshot` still returns one
 * directly until arc 22 / X3 moves it to rows.
 *
 * Wire form: `SharedMemory · int byteCount` (a compatible tail may be appended later).
 * [requireValid] runs at construction, so at unmarshal too: `byteCount` in
 * `0..STORE_MAX_VALUE_BYTES` and `≤ memory.size`. An empty value rides a **1-byte region with
 * `byteCount = 0`** — ashmem refuses a zero-size region.
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
            require(byteCount in 0..ExtensionContract.STORE_MAX_VALUE_BYTES) {
                "byteCount must be 0..${ExtensionContract.STORE_MAX_VALUE_BYTES} ($byteCount)"
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

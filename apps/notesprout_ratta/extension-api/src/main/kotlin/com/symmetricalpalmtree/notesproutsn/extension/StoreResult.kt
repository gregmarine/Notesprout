package com.symmetricalpalmtree.notesproutsn.extension

import android.os.Parcel
import android.os.Parcelable

/**
 * One chunk of a query's rows (arc 22 / X1): [payload] is a [StoreCodec] rows document. When
 * [more] is true the result continues and [handle] names the parked remainder for
 * `IExtensionStore.next`; when it is false the result is complete and [handle] is −1. The host
 * decides the split — a row is never divided between chunks, every chunk repeats the column names,
 * and `StoreReads.all` is the loop that stitches them.
 *
 * Wire form: `StorePayload · int handle · int more`. [requireValid] runs at construction, so at
 * unmarshal too.
 */
class StoreResult(
    val payload: StorePayload,
    val handle: Int,
    val more: Boolean,
) : Parcelable {

    init {
        requireValid(handle, more)
    }

    @Suppress("DEPRECATION")
    private constructor(parcel: Parcel) : this(
        payload = parcel.readParcelable(StorePayload::class.java.classLoader)!!,
        handle = parcel.readInt(),
        more = parcel.readInt() != 0,
    )

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeParcelable(payload, flags)
        dest.writeInt(handle)
        dest.writeInt(if (more) 1 else 0)
    }

    override fun describeContents(): Int = payload.describeContents()

    companion object {
        /** A complete result's handle. */
        const val NO_HANDLE: Int = -1

        /** The constructor's checks, pure so they are JVM-testable: a continuing result names a
         *  handle ≥ 0, a complete one carries [NO_HANDLE]. */
        fun requireValid(handle: Int, more: Boolean) {
            if (more) require(handle >= 0) { "a continuing result needs a handle ($handle)" }
            else require(handle == NO_HANDLE) { "a complete result carries no handle ($handle)" }
        }

        @JvmField
        val CREATOR: Parcelable.Creator<StoreResult> = object : Parcelable.Creator<StoreResult> {
            override fun createFromParcel(parcel: Parcel): StoreResult = StoreResult(parcel)
            override fun newArray(size: Int): Array<StoreResult?> = arrayOfNulls(size)
        }
    }
}

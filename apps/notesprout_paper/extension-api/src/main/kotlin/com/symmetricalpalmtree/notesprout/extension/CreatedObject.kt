package com.symmetricalpalmtree.notesprout.extension

import android.os.Parcel
import android.os.Parcelable

/**
 * What `IObjectProvider.createFromInk` returns (arc 4 / H3): the new object's [typeId] (one of the
 * provider's `describeTypes()`, `[a-z0-9_-]+`, ≤ [ExtensionContract.MAX_TYPE_ID_CHARS]) and its
 * opaque [payload] (≤ [ExtensionContract.MAX_OBJECT_TEXT_CHARS] — the host truncates on the way in
 * and never parses it). Everything here is untrusted on the host side.
 *
 * Hand-written Parcelable (write order fixed forever): `writeString ×2`.
 */
class CreatedObject(
    val typeId: String,
    val payload: String,
) : Parcelable {

    init { requireValid(typeId, payload) }

    private constructor(parcel: Parcel) : this(
        typeId = parcel.readString().orEmpty(),
        payload = parcel.readString().orEmpty(),
    )

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeString(typeId)
        dest.writeString(payload)
    }

    override fun describeContents(): Int = 0

    companion object {
        /** The structural rules the constructor enforces (pure — JVM-testable without a Parcel). */
        fun requireValid(typeId: String, payload: String) {
            require(ExtensionContract.isTypeId(typeId)) { "bad typeId" }
            require(payload.isNotBlank()) { "blank payload" }
        }

        @JvmField
        val CREATOR: Parcelable.Creator<CreatedObject> = object : Parcelable.Creator<CreatedObject> {
            override fun createFromParcel(parcel: Parcel): CreatedObject = CreatedObject(parcel)
            override fun newArray(size: Int): Array<CreatedObject?> = arrayOfNulls(size)
        }
    }
}

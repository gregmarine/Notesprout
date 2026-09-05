package com.symmetricalpalmtree.notesproutsn.extension

import android.os.Parcel
import android.os.Parcelable

/**
 * One folder or file as the provider reports it (arc 25 / V1) — a row of the host-drawn cloud
 * browser, the answer to `ensureFolder`, and the corroboration an `upload` returns.
 *
 * [id] is the provider's opaque id for the entry: the host passes it back to `download` / `delete`
 * and shows it to no one. [name] is the display name and is what the host resolves paths by.
 * [sizeBytes] is what the provider says the file holds (0 for a folder; the host's export
 * verification compares it to what it wrote and treats disagreement as *check the file*, never as
 * delete — the arc-15 rule). [modifiedAt] is epoch millis, 0 when the provider did not say.
 *
 * The constructor `require`s **are** the validation, both directions — unmarshal is validation, the
 * family rule since E1. A name here is the user's own file naming: `toString` prints its length.
 *
 * Wire form: `String id · String name · int isFolder · long sizeBytes · long modifiedAt`. A future
 * field is a compatible tail.
 */
class CloudEntry(
    val id: String,
    val name: String,
    val isFolder: Boolean,
    val sizeBytes: Long,
    val modifiedAt: Long,
) : Parcelable {

    init {
        requireValid(id, name, isFolder, sizeBytes, modifiedAt)
    }

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeString(id)
        dest.writeString(name)
        dest.writeInt(if (isFolder) 1 else 0)
        dest.writeLong(sizeBytes)
        dest.writeLong(modifiedAt)
    }

    override fun describeContents(): Int = 0

    override fun equals(other: Any?): Boolean =
        other is CloudEntry && other.id == id && other.name == name && other.isFolder == isFolder &&
            other.sizeBytes == sizeBytes && other.modifiedAt == modifiedAt

    override fun hashCode(): Int =
        (((id.hashCode() * 31 + name.hashCode()) * 31 + (if (isFolder) 1 else 0)) * 31 +
            sizeBytes.hashCode()) * 31 + modifiedAt.hashCode()

    override fun toString(): String =
        "CloudEntry(${if (isFolder) "folder" else "file"}, name=${name.length} chars, $sizeBytes B)"

    companion object {
        /** The constructor's checks, pure so they are JVM-testable. */
        fun requireValid(id: String, name: String, isFolder: Boolean, sizeBytes: Long, modifiedAt: Long) {
            require(CloudContract.isEntryId(id)) { "entry id is not an id" }
            require(CloudContract.isName(name)) { "entry name is not a name" }
            require(sizeBytes >= 0) { "size is negative ($sizeBytes)" }
            require(!isFolder || sizeBytes == 0L) { "a folder has no size" }
            require(modifiedAt >= 0) { "modifiedAt is negative ($modifiedAt)" }
        }

        @JvmField
        val CREATOR: Parcelable.Creator<CloudEntry> = object : Parcelable.Creator<CloudEntry> {
            override fun createFromParcel(parcel: Parcel): CloudEntry =
                CloudEntry(
                    id = parcel.readString() ?: "",
                    name = parcel.readString() ?: "",
                    isFolder = parcel.readInt() != 0,
                    sizeBytes = parcel.readLong(),
                    modifiedAt = parcel.readLong(),
                )

            override fun newArray(size: Int): Array<CloudEntry?> = arrayOfNulls(size)
        }
    }
}

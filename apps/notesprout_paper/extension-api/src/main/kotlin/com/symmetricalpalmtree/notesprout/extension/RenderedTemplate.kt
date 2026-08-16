package com.symmetricalpalmtree.notesprout.extension

import android.os.Parcel
import android.os.Parcelable
import android.os.SharedMemory

/**
 * A rendered template: a complete WEBP file living in [memory]`[0 until byteCount]`.
 *
 * Binder transactions are capped at ~1 MB, so a full-page bitmap can never travel as a plain
 * `byte[]`; [SharedMemory] is ashmem-backed and Parcelable. The extension creates the region
 * (`SharedMemory.create(null, byteCount)`, maps RW, writes, unmaps, `setProtect(PROT_READ)`); the
 * host maps read-only, copies out [byteCount] bytes, unmaps, and closes.
 */
class RenderedTemplate(
    val memory: SharedMemory,
    val byteCount: Int,
    val mimeType: String,
) : Parcelable {

    @Suppress("DEPRECATION")
    private constructor(parcel: Parcel) : this(
        memory = parcel.readParcelable(SharedMemory::class.java.classLoader)!!,
        byteCount = parcel.readInt(),
        mimeType = parcel.readString().orEmpty(),
    )

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeParcelable(memory, flags)
        dest.writeInt(byteCount)
        dest.writeString(mimeType)
    }

    /** Carries a file descriptor (the ashmem region) — must say so, or `Bundle.hasFileDescriptors()` lies. */
    override fun describeContents(): Int = Parcelable.CONTENTS_FILE_DESCRIPTOR

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<RenderedTemplate> = object : Parcelable.Creator<RenderedTemplate> {
            override fun createFromParcel(parcel: Parcel): RenderedTemplate = RenderedTemplate(parcel)
            override fun newArray(size: Int): Array<RenderedTemplate?> = arrayOfNulls(size)
        }
    }
}

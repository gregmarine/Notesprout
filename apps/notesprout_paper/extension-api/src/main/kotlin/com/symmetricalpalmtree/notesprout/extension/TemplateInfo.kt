package com.symmetricalpalmtree.notesprout.extension

import android.os.Parcel
import android.os.Parcelable

/** One template a provider offers: a stable ASCII [id] and a display [name]. */
class TemplateInfo(
    val id: String,
    val name: String,
) : Parcelable {

    private constructor(parcel: Parcel) : this(
        id = parcel.readString().orEmpty(),
        name = parcel.readString().orEmpty(),
    )

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeString(id)
        dest.writeString(name)
    }

    override fun describeContents(): Int = 0

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<TemplateInfo> = object : Parcelable.Creator<TemplateInfo> {
            override fun createFromParcel(parcel: Parcel): TemplateInfo = TemplateInfo(parcel)
            override fun newArray(size: Int): Array<TemplateInfo?> = arrayOfNulls(size)
        }
    }
}

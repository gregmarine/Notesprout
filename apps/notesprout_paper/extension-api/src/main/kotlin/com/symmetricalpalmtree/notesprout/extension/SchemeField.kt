package com.symmetricalpalmtree.notesprout.extension

import android.os.Parcel
import android.os.Parcelable

/**
 * How the host draws a namer's scheme field: a small [label] above it, the grey [hint] inside it, one
 * [help] line below. All three are untrusted on the host side (truncated before display).
 */
class SchemeField(
    val label: String,
    val hint: String,
    val help: String,
) : Parcelable {

    private constructor(parcel: Parcel) : this(
        label = parcel.readString().orEmpty(),
        hint = parcel.readString().orEmpty(),
        help = parcel.readString().orEmpty(),
    )

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeString(label)
        dest.writeString(hint)
        dest.writeString(help)
    }

    override fun describeContents(): Int = 0

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<SchemeField> = object : Parcelable.Creator<SchemeField> {
            override fun createFromParcel(parcel: Parcel): SchemeField = SchemeField(parcel)
            override fun newArray(size: Int): Array<SchemeField?> = arrayOfNulls(size)
        }
    }
}

package com.symmetricalpalmtree.notesprout.extension

import android.os.Parcel
import android.os.Parcelable

/**
 * How the host draws the edit dialog for one object (`describeEdit`): a [title], one text field
 * prefilled with [text] (for a heading: the words **without** the `#`s), the field's [hint], its
 * [maxChars] (1..[ExtensionContract.MAX_EDIT_TEXT_CHARS]) and whether it is [multiLine]. The host
 * hands the saved text back through `applyEdit`. Everything here is untrusted on the host side
 * (truncated before display).
 *
 * Hand-written Parcelable (write order fixed forever): `writeString ×3; writeInt; writeInt(0/1)`.
 */
class EditSpec(
    val title: String,
    val text: String,
    val hint: String,
    val maxChars: Int,
    val multiLine: Boolean,
) : Parcelable {

    init { requireValid(title, maxChars) }

    private constructor(parcel: Parcel) : this(
        title = parcel.readString().orEmpty(),
        text = parcel.readString().orEmpty(),
        hint = parcel.readString().orEmpty(),
        maxChars = parcel.readInt(),
        multiLine = parcel.readInt() != 0,
    )

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeString(title)
        dest.writeString(text)
        dest.writeString(hint)
        dest.writeInt(maxChars)
        dest.writeInt(if (multiLine) 1 else 0)
    }

    override fun describeContents(): Int = 0

    companion object {
        /** The structural rules the constructor enforces (pure — JVM-testable without a Parcel). */
        fun requireValid(title: String, maxChars: Int) {
            require(title.isNotBlank()) { "blank edit title" }
            require(maxChars in 1..ExtensionContract.MAX_EDIT_TEXT_CHARS) { "bad maxChars" }
        }

        @JvmField
        val CREATOR: Parcelable.Creator<EditSpec> = object : Parcelable.Creator<EditSpec> {
            override fun createFromParcel(parcel: Parcel): EditSpec = EditSpec(parcel)
            override fun newArray(size: Int): Array<EditSpec?> = arrayOfNulls(size)
        }
    }
}

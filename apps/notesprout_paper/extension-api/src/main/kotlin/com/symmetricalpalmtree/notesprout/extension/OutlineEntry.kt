package com.symmetricalpalmtree.notesprout.extension

import android.os.Parcel
import android.os.Parcelable

/**
 * One object's outline (table-of-contents) entry (arc 5 / C0) — what `IObjectProvider.describeOutline`
 * returns, one per payload, same order: a [label] (≤ [ExtensionContract.MAX_OUTLINE_LABEL_CHARS]) at
 * [level] `1..MAX_OUTLINE_LEVEL`, or [level] `0` for "not an outline item" (the label is ignored —
 * [NONE]). The core sorts, nests, pages and draws under its own rules; a provider only *describes*.
 * Everything here is untrusted on the host side (`OutlineCaps`).
 *
 * Hand-written Parcelable (write order fixed forever, tails may be appended): `writeString(label);
 * writeInt(level)`.
 */
class OutlineEntry(
    val label: String,
    val level: Int,
) : Parcelable {

    init { requireValid(label, level) }

    private constructor(parcel: Parcel) : this(
        label = parcel.readString().orEmpty(),
        level = parcel.readInt(),
    )

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeString(label)
        dest.writeInt(level)
    }

    override fun describeContents(): Int = 0

    companion object {
        /** "Not an outline item." */
        @JvmField
        val NONE: OutlineEntry = OutlineEntry("", 0)

        /** The structural rules the constructor enforces (pure — JVM-testable without a Parcel). */
        fun requireValid(label: String, level: Int) {
            require(level in 0..ExtensionContract.MAX_OUTLINE_LEVEL) { "level out of range" }
            require(label.length <= ExtensionContract.MAX_OUTLINE_LABEL_CHARS) { "label too long" }
        }

        @JvmField
        val CREATOR: Parcelable.Creator<OutlineEntry> = object : Parcelable.Creator<OutlineEntry> {
            override fun createFromParcel(parcel: Parcel): OutlineEntry = OutlineEntry(parcel)
            override fun newArray(size: Int): Array<OutlineEntry?> = arrayOfNulls(size)
        }
    }
}

package com.symmetricalpalmtree.notesproutsn.extension

import android.os.Parcel
import android.os.Parcelable

/**
 * One stroke of bare geometry for `IHandwritingRecognizer`: parallel [x] / [y] point arrays in the
 * caller's px space. Nothing else travels — no id, no time, no pressure, no colour, no width. Both
 * arrays must be the same non-zero length.
 *
 * Wire form: `int n · float[] x · float[] y` (a compatible tail — e.g. a time channel — may be
 * appended after `y` in a later version; readers of this version stop after `y`).
 */
class InkStroke(
    val x: FloatArray,
    val y: FloatArray,
) : Parcelable {

    init {
        require(x.size == y.size) { "x/y length mismatch (${x.size} vs ${y.size})" }
        require(x.isNotEmpty()) { "empty stroke" }
    }

    /** Point count. */
    val size: Int get() = x.size

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeInt(x.size)
        dest.writeFloatArray(x)
        dest.writeFloatArray(y)
    }

    override fun describeContents(): Int = 0

    companion object {
        private fun read(parcel: Parcel): InkStroke {
            parcel.readInt()   // declared length; the arrays carry their own headers
            val x = parcel.createFloatArray() ?: FloatArray(0)
            val y = parcel.createFloatArray() ?: FloatArray(0)
            return InkStroke(x, y)   // the `require`s reject a malformed stroke at unmarshal time
        }

        @JvmField
        val CREATOR: Parcelable.Creator<InkStroke> = object : Parcelable.Creator<InkStroke> {
            override fun createFromParcel(parcel: Parcel): InkStroke = read(parcel)
            override fun newArray(size: Int): Array<InkStroke?> = arrayOfNulls(size)
        }
    }
}

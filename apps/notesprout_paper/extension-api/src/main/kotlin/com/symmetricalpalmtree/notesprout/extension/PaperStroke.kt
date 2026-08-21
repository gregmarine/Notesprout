package com.symmetricalpalmtree.notesprout.extension

import android.os.Parcel
import android.os.Parcelable

/**
 * A whole paper stroke minus its id and time (arc 6 / S0 — the scratch-pad transfers): parallel
 * [x] / [y] / [pressure] / [tilt] point arrays in the authoring page's px space, the stroke's
 * [width] (px), [colorArgb] and [style] (the g-paper `StrokeStyle` name — an unknown name reads as
 * PEN on the receiving side). Nothing else travels: no id (both sides mint fresh ones), no time, no
 * page id, no notebook id.
 *
 * Wire form: `int n · float[] x · float[] y · float[] pressure · float[] tilt · float width ·
 * int colorArgb · String style` (a compatible tail may be appended later; readers of this version
 * stop after `style`). [requireValid] runs in the constructor — so at unmarshal too — and a
 * malformed stroke rejects the whole bundle it rides in (the row-21 rule).
 */
class PaperStroke(
    val x: FloatArray,
    val y: FloatArray,
    val pressure: FloatArray,
    val tilt: FloatArray,
    val width: Float,
    val colorArgb: Int,
    val style: String,
) : Parcelable {

    init {
        requireValid(x.size, y.size, pressure.size, tilt.size, width)
    }

    /** Point count. */
    val size: Int get() = x.size

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeInt(x.size)
        dest.writeFloatArray(x)
        dest.writeFloatArray(y)
        dest.writeFloatArray(pressure)
        dest.writeFloatArray(tilt)
        dest.writeFloat(width)
        dest.writeInt(colorArgb)
        dest.writeString(style)
    }

    override fun describeContents(): Int = 0

    companion object {
        /** The constructor's checks, pure so they are JVM-testable: four equal non-zero lengths, finite `width > 0`. */
        fun requireValid(nx: Int, ny: Int, np: Int, nt: Int, width: Float) {
            require(nx >= 1) { "empty stroke" }
            require(nx == ny && nx == np && nx == nt) { "channel length mismatch ($nx/$ny/$np/$nt)" }
            require(width > 0f && width.isFinite()) { "width must be > 0 ($width)" }
        }

        private fun read(parcel: Parcel): PaperStroke {
            parcel.readInt()   // declared length; the arrays carry their own headers
            val x = parcel.createFloatArray() ?: FloatArray(0)
            val y = parcel.createFloatArray() ?: FloatArray(0)
            val p = parcel.createFloatArray() ?: FloatArray(0)
            val t = parcel.createFloatArray() ?: FloatArray(0)
            val width = parcel.readFloat()
            val color = parcel.readInt()
            val style = parcel.readString().orEmpty()
            return PaperStroke(x, y, p, t, width, color, style)   // the `require`s reject a malformed stroke at unmarshal time
        }

        @JvmField
        val CREATOR: Parcelable.Creator<PaperStroke> = object : Parcelable.Creator<PaperStroke> {
            override fun createFromParcel(parcel: Parcel): PaperStroke = read(parcel)
            override fun newArray(size: Int): Array<PaperStroke?> = arrayOfNulls(size)
        }
    }
}

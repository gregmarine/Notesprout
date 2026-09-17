package com.symmetricalpalmtree.notesproutsn.extension

import android.os.Parcel
import android.os.Parcelable

/**
 * What the sketch face's drawing tools were last set to (arc 44 / T2) — the one thing the sketch
 * seam carries that is **not about a page**: which tool was armed, which pencil shade, which pencil
 * size. The face pushes it at every pick ([ISketchHost.putToolSettings]) and asks for it back at
 * `begin` ([ISketchHost.toolSettings]); the host keeps it in device-local prefs — one setting for
 * every notebook, never in the `.soil`, never in a backup (decision 6). An extension writes nothing
 * to disk itself, which is the whole reason this crosses at all.
 *
 * **Indices, never values.** A shade is a level on the face's own ladder and a size is a position
 * in its own list; the greys and the pixel widths live in `:ext-sketch` and nowhere else, so
 * retuning a width — or shortening the ladder — never strands a stored value and the host never
 * learns the palette. That is also why the bound here is a **sanity bound**
 * ([SketchContract.MAX_TOOL_SETTING_INDEX]), not the palette's: the constructor refuses a number
 * that cannot be an index at all, and **the face reads an index past the end of its own list as its
 * default** — a stored 14 against a thirteen-shade build is a legal parcel and an ordinary
 * fallback, never an exception. [tool] is bounded the same way rather than to the two constants
 * that exist today, so a later tool is a new constant and not a wire break.
 *
 * The constructor `require`s are the validation — unmarshal is validation (the family rule). This
 * parcel crosses in **both** directions, so both sides are the untrusted-inward side once.
 *
 * Wire form: `int tool · int shade · int size`. A future field is a compatible tail, read with the
 * exhausted-parcel rule ([SketchPageState.structuralToken]'s).
 *
 * Nothing here is content: three small integers say nothing of what a person drew, so they may be
 * logged.
 */
class SketchToolSettings(
    /** Which drawing tool is armed — [SketchContract.TOOL_PENCIL] / [SketchContract.TOOL_PEN]. The
     *  eraser is never remembered: a face that opened on the eraser would read as a broken pencil. */
    val tool: Int,
    /** The pencil's shade, as a level on the face's own ladder (0 = black). */
    val shade: Int,
    /** The pencil's size, as a position in the face's own list (0 = the finest). */
    val size: Int,
) : Parcelable {

    init {
        require(tool in 0..SketchContract.MAX_TOOL_SETTING_INDEX) {
            "tool $tool outside 0..${SketchContract.MAX_TOOL_SETTING_INDEX}"
        }
        require(shade in 0..SketchContract.MAX_TOOL_SETTING_INDEX) {
            "shade $shade outside 0..${SketchContract.MAX_TOOL_SETTING_INDEX}"
        }
        require(size in 0..SketchContract.MAX_TOOL_SETTING_INDEX) {
            "size $size outside 0..${SketchContract.MAX_TOOL_SETTING_INDEX}"
        }
    }

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeInt(tool)
        dest.writeInt(shade)
        dest.writeInt(size)
    }

    override fun describeContents(): Int = 0

    override fun equals(other: Any?): Boolean =
        other is SketchToolSettings && other.tool == tool && other.shade == shade && other.size == size

    override fun hashCode(): Int = (tool * 31 + shade) * 31 + size

    override fun toString(): String = "SketchToolSettings(tool=$tool, shade=$shade, size=$size)"

    companion object {
        private fun read(parcel: Parcel): SketchToolSettings = SketchToolSettings(
            tool = parcel.readInt(),
            shade = parcel.readInt(),
            size = parcel.readInt(),
        )

        @JvmField
        val CREATOR: Parcelable.Creator<SketchToolSettings> = object : Parcelable.Creator<SketchToolSettings> {
            override fun createFromParcel(parcel: Parcel): SketchToolSettings = read(parcel)
            override fun newArray(size: Int): Array<SketchToolSettings?> = arrayOfNulls(size)
        }
    }
}

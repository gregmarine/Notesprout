package com.symmetricalpalmtree.notesproutsn.extension

import android.os.Parcel
import android.os.Parcelable

/**
 * What the sketch face's drawing tools were last set to (arc 44 / T2, grown by arc 46 / Q1) — the
 * one thing the sketch seam carries that is **not about a page**: which tool was armed, which
 * pencil shade, and — since arc 46 "Palette" — which gel-pen shade. The face pushes it at every pick ([ISketchHost.putToolSettings]) and asks for it back at
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
 * Wire form: `int tool · int shade · int size · int penShade`. [penShade] is arc 46 / Q1's
 * **compatible tail**, read with the exhausted-parcel rule ([SketchPageState.structuralToken]'s):
 * a three-int parcel from an arc-44/45 host reads as pen shade 0, and a four-int parcel handed to
 * such a host leaves one int unread. [size] is a **dead slot since arc 46** — the face offers one
 * pencil width and writes 0 here, never reading it back — kept on the wire because dropping it
 * would be an in-place shape change and move the point's action floor for nothing
 * ([SketchContract.MIN_API_VERSION_FOR_SKETCH_PEN_SHADE] names the tail instead). A future field
 * is another tail after it.
 *
 * Nothing here is content: four small integers say nothing of what a person drew, so they may be
 * logged.
 */
class SketchToolSettings(
    /** Which drawing tool is armed — [SketchContract.TOOL_PENCIL] / [SketchContract.TOOL_PEN]. The
     *  eraser is never remembered: a face that opened on the eraser would read as a broken pencil. */
    val tool: Int,
    /** The pencil's shade, as a level on the face's own ladder (0 = black). */
    val shade: Int,
    /** Arc 44's pencil size, as a position in the face's then list. **Dead since arc 46 / Q1**: the
     *  face writes 0 and never reads it; the slot stays on the wire (class doc). */
    val size: Int,
    /** The gel pen's shade, as a level on the face's own ladder (0 = black) — arc 46 / Q1's tail.
     *  Defaulted so a three-int caller still compiles and means what it always did. */
    val penShade: Int = 0,
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
        require(penShade in 0..SketchContract.MAX_TOOL_SETTING_INDEX) {
            "penShade $penShade outside 0..${SketchContract.MAX_TOOL_SETTING_INDEX}"
        }
    }

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeInt(tool)
        dest.writeInt(shade)
        dest.writeInt(size)
        dest.writeInt(penShade)
    }

    override fun describeContents(): Int = 0

    override fun equals(other: Any?): Boolean =
        other is SketchToolSettings && other.tool == tool && other.shade == shade &&
            other.size == size && other.penShade == penShade

    override fun hashCode(): Int = ((tool * 31 + shade) * 31 + size) * 31 + penShade

    override fun toString(): String =
        "SketchToolSettings(tool=$tool, shade=$shade, size=$size, penShade=$penShade)"

    companion object {
        private fun read(parcel: Parcel): SketchToolSettings = SketchToolSettings(
            tool = parcel.readInt(),
            shade = parcel.readInt(),
            size = parcel.readInt(),
            // The exhausted-parcel rule: an arc-44/45 host writes three ints, and the pen it never
            // knew about reads as black.
            penShade = if (parcel.dataAvail() > 0) parcel.readInt() else 0,
        )

        @JvmField
        val CREATOR: Parcelable.Creator<SketchToolSettings> = object : Parcelable.Creator<SketchToolSettings> {
            override fun createFromParcel(parcel: Parcel): SketchToolSettings = read(parcel)
            override fun newArray(size: Int): Array<SketchToolSettings?> = arrayOfNulls(size)
        }
    }
}

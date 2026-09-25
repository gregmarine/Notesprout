package com.symmetricalpalmtree.notesproutsn.extension

import android.os.Parcel
import android.os.Parcelable

/**
 * A page's **guides** (arc 51 "Guides" / J1, 2026-09-23) — the settings half of what the sketch
 * face lays under the sketch as a tool and never as a mark: a grid of lines or dots, centred on
 * the page, and a reference image for tracing at some opacity. The pixels of the image cross
 * separately, chunked ([ISketchHost.saveGuideImageChunk] / [ISketchHost.readGuideImageChunk]);
 * this parcel is the five small numbers beside them, pushed at every pick
 * ([ISketchHost.putGuides]) and answered inside [SketchGuideState] at every page load
 * ([ISketchHost.guides]). The host keeps them **in the `.soil`, parented to the page** — per page,
 * never a device setting — as two additive rows it never draws, exports, covers or erases.
 *
 * **Indices and a percent, never pixels.** The grid's count is a number of cells across the page's
 * width and its kind a small enum; the line tone, the line and dot sizes, and the ladders of
 * counts and opacities the panel offers live in `:ext-sketch` and nowhere else, so retuning any of
 * them never strands a stored value and the host never learns the ladder. The bounds here are
 * **sanity bounds** ([SketchContract.MAX_TOOL_SETTING_INDEX] for the two indices,
 * [SketchContract.MAX_OPACITY_PERCENT] for the percent): the constructor refuses a number that
 * cannot be one at all, and the face reads a value off its own ladder as that field's default — a
 * legal parcel and an ordinary fallback, never an exception. [gridKind] is bounded the same way
 * rather than to the three constants that exist today, so a later kind is a new constant and not a
 * wire break.
 *
 * **Grid kind 0 means no grid row**: [SketchContract.GRID_OFF] pushed is the host's word to
 * soft-delete the page's grid row, and a page with no grid row answers kind 0. The image's two
 * fields are meaningful only while the page carries an image ([SketchGuideState.hasImage]); the
 * host stores what it is handed and the face ignores them on a page with no image.
 *
 * The constructor `require`s are the validation — unmarshal is validation (the family rule). This
 * parcel crosses in **both** directions, so both sides are the untrusted-inward side once.
 *
 * Wire form: `int gridKind · int gridCount · int gridVisible(0/1) · int imageOpacity ·
 * int imageVisible(0/1)`. A future field is a compatible tail after [imageVisible], read with the
 * exhausted-parcel rule ([SketchToolSettings.penShade]'s).
 *
 * Nothing here is content: five small integers say nothing of what a person drew or traced, so
 * they may be logged.
 */
class SketchGuideSettings(
    /** [SketchContract.GRID_OFF] (no grid row) / [SketchContract.GRID_LINES] / [SketchContract.GRID_DOTS]. */
    val gridKind: Int,
    /** Cells across the page's width — the face's own ladder position is its business; the number
     *  crosses as the count itself. 0 is legal only with the grid off. */
    val gridCount: Int,
    /** Whether the grid is drawn; a hidden grid keeps its kind and count (the user's decision 6). */
    val gridVisible: Boolean,
    /** The reference image's opacity, 0..[SketchContract.MAX_OPACITY_PERCENT]. */
    val imageOpacity: Int,
    /** Whether the reference image is drawn; a hidden image keeps its pixels and opacity. */
    val imageVisible: Boolean,
) : Parcelable {

    init {
        require(gridKind in 0..SketchContract.MAX_TOOL_SETTING_INDEX) {
            "gridKind $gridKind outside 0..${SketchContract.MAX_TOOL_SETTING_INDEX}"
        }
        require(gridCount in 0..SketchContract.MAX_TOOL_SETTING_INDEX) {
            "gridCount $gridCount outside 0..${SketchContract.MAX_TOOL_SETTING_INDEX}"
        }
        require(gridKind == SketchContract.GRID_OFF || gridCount > 0) { "a grid with no cells" }
        require(imageOpacity in 0..SketchContract.MAX_OPACITY_PERCENT) {
            "imageOpacity $imageOpacity outside 0..${SketchContract.MAX_OPACITY_PERCENT}"
        }
    }

    /** Whether a grid row exists for this page at all — kind is not [SketchContract.GRID_OFF]. */
    val hasGrid: Boolean get() = gridKind != SketchContract.GRID_OFF

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeInt(gridKind)
        dest.writeInt(gridCount)
        dest.writeInt(if (gridVisible) 1 else 0)
        dest.writeInt(imageOpacity)
        dest.writeInt(if (imageVisible) 1 else 0)   // LAST — a later field is a tail after it
    }

    override fun describeContents(): Int = 0

    override fun equals(other: Any?): Boolean =
        other is SketchGuideSettings && other.gridKind == gridKind && other.gridCount == gridCount &&
            other.gridVisible == gridVisible && other.imageOpacity == imageOpacity &&
            other.imageVisible == imageVisible

    override fun hashCode(): Int =
        (((gridKind * 31 + gridCount) * 31 + (if (gridVisible) 1 else 0)) * 31 + imageOpacity) * 31 +
            (if (imageVisible) 1 else 0)

    override fun toString(): String =
        "SketchGuideSettings(gridKind=$gridKind, gridCount=$gridCount, gridVisible=$gridVisible, " +
            "imageOpacity=$imageOpacity, imageVisible=$imageVisible)"

    companion object {
        /** The settings of a page with neither row — no grid, and an image that is not there. */
        val NONE: SketchGuideSettings = SketchGuideSettings(
            gridKind = SketchContract.GRID_OFF, gridCount = 0, gridVisible = true,
            imageOpacity = 0, imageVisible = true,
        )

        /** Reads one from [parcel] at its current position — shared with [SketchGuideState], which
         *  carries these five ints inline. */
        internal fun read(parcel: Parcel): SketchGuideSettings = SketchGuideSettings(
            gridKind = parcel.readInt(),
            gridCount = parcel.readInt(),
            gridVisible = parcel.readInt() != 0,
            imageOpacity = parcel.readInt(),
            imageVisible = parcel.readInt() != 0,
        )

        @JvmField
        val CREATOR: Parcelable.Creator<SketchGuideSettings> = object : Parcelable.Creator<SketchGuideSettings> {
            override fun createFromParcel(parcel: Parcel): SketchGuideSettings = read(parcel)
            override fun newArray(size: Int): Array<SketchGuideSettings?> = arrayOfNulls(size)
        }
    }
}

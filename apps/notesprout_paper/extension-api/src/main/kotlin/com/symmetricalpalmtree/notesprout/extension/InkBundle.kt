package com.symmetricalpalmtree.notesprout.extension

import android.os.Parcel
import android.os.Parcelable

/**
 * One chunk of an ink transfer (arc 6 / S0): [strokes] plus the page px geometry they were authored
 * in ([pageWidth] × [pageHeight]; `0f × 0f` = unknown → the reader uses its own page). The host
 * chunks a transfer at [ExtensionContract.TRANSFER_CHUNK_STROKES] / [ExtensionContract.TRANSFER_CHUNK_POINTS]
 * per Binder call; the reader re-checks ([requireValid] in the constructor → at unmarshal). An empty
 * bundle (0 strokes) is legal — it is how `takeOutgoing` says "done".
 *
 * Wire form: `float pageWidth · float pageHeight · typedList strokes` (a compatible tail may be
 * appended later).
 */
class InkBundle(
    val strokes: List<PaperStroke>,
    val pageWidth: Float,
    val pageHeight: Float,
) : Parcelable {

    init {
        requireValid(strokes.size, strokes.sumOf { it.size }, pageWidth, pageHeight)
    }

    /** Points summed over the strokes. */
    val pointCount: Int get() = strokes.sumOf { it.size }

    private constructor(parcel: Parcel) : this(
        pageWidth = parcel.readFloat(),
        pageHeight = parcel.readFloat(),
        strokes = parcel.createTypedArrayList(PaperStroke.CREATOR)?.filterNotNull() ?: emptyList(),
    )

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeFloat(pageWidth)
        dest.writeFloat(pageHeight)
        dest.writeTypedList(strokes)
    }

    override fun describeContents(): Int = 0

    companion object {
        /** The constructor's checks, pure so they are JVM-testable: chunk caps + non-negative finite page sizes. */
        fun requireValid(strokeCount: Int, pointCount: Int, pageWidth: Float, pageHeight: Float) {
            require(strokeCount <= ExtensionContract.TRANSFER_CHUNK_STROKES) {
                "chunk exceeds TRANSFER_CHUNK_STROKES ($strokeCount)"
            }
            // A single stroke over the point chunk cap is its own chunk (allowed — the host's chunker
            // never splits a stroke); it is still bounded by the whole-transfer cap.
            require(pointCount <= ExtensionContract.TRANSFER_CHUNK_POINTS ||
                (strokeCount == 1 && pointCount <= ExtensionContract.MAX_TRANSFER_POINTS)) {
                "chunk exceeds TRANSFER_CHUNK_POINTS ($pointCount)"
            }
            require(pageWidth >= 0f && pageHeight >= 0f && pageWidth.isFinite() && pageHeight.isFinite()) {
                "page size must be finite and >= 0 ($pageWidth x $pageHeight)"
            }
        }

        @JvmField
        val CREATOR: Parcelable.Creator<InkBundle> = object : Parcelable.Creator<InkBundle> {
            override fun createFromParcel(parcel: Parcel): InkBundle = InkBundle(parcel)
            override fun newArray(size: Int): Array<InkBundle?> = arrayOfNulls(size)
        }
    }
}

package com.symmetricalpalmtree.notesproutsn.extension

import android.os.Parcel
import android.os.Parcelable

/**
 * What the host answers [ISketchHost.guides] with (arc 51 "Guides" / J1) — [SketchPageState]'s
 * shape for a page's guides: the page it describes, its [SketchGuideSettings], and the shape of
 * the reference image now parked in the host's **guide read window** for
 * [ISketchHost.readGuideImageChunk] to serve — a lossy WebP with alpha, exactly the page's size,
 * or absent. The window is a third one beside the two raster windows and is loaded atomically
 * with this answer; a later `current()` / `requestPage()` does not touch it.
 *
 * The constructor `require`s are the validation — unmarshal is validation (the family rule). This
 * parcel crosses **into the extension**, which is the untrusted-inward side here.
 *
 * Wire form: `String pageKey · [SketchGuideSettings]'s five ints inline · int imageBytes ·
 * int imageChunks`. A future field is a compatible tail after [imageChunks] (the state is the
 * reply's whole payload, so a reader stops after the fields it knows —
 * [SketchPageState.structuralToken]'s rule). **Blank means absent**: 0 bytes in one chunk is a
 * page with no image row, and [settings]' image fields are then meaningless.
 */
class SketchGuideState(
    /** The page these guides belong to — see [SketchContract.MAX_PAGE_KEY_CHARS]. */
    val pageKey: String,
    /** The page's guide settings; [SketchGuideSettings.NONE] for a page with neither row. */
    val settings: SketchGuideSettings,
    /** Length of the reference image in the guide read window (**0** = no image: an empty window). */
    val imageBytes: Int,
    /** How many [ISketchHost.readGuideImageChunk] calls serve it — always ≥ 1 ([ByteChunks]' rule). */
    val imageChunks: Int,
) : Parcelable {

    init {
        require(pageKey.isNotEmpty()) { "pageKey is empty" }
        require(pageKey.length <= SketchContract.MAX_PAGE_KEY_CHARS) { "pageKey too long" }
        require(imageBytes in 0..SketchContract.MAX_BYTES) {
            "imageBytes $imageBytes outside 0..${SketchContract.MAX_BYTES}"
        }
        require(imageChunks in 1..SketchContract.MAX_CHUNKS) {
            "imageChunks $imageChunks outside 1..${SketchContract.MAX_CHUNKS}"
        }
        require(imageBytes > 0 || imageChunks == 1) { "an empty image in $imageChunks chunks" }
        require(imageChunks == SketchPageState.chunksFor(imageBytes)) {
            "imageChunks $imageChunks does not match $imageBytes bytes"
        }
    }

    /** Whether the page carries a reference image — the image row is live. */
    val hasImage: Boolean get() = imageBytes > 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeString(pageKey)
        settings.writeToParcel(dest, flags)
        dest.writeInt(imageBytes)
        dest.writeInt(imageChunks)   // LAST — the tail, so a later reader's extra fields go after it
    }

    override fun describeContents(): Int = 0

    companion object {
        private fun read(parcel: Parcel): SketchGuideState = SketchGuideState(
            pageKey = parcel.readString() ?: "",
            settings = SketchGuideSettings.read(parcel),
            imageBytes = parcel.readInt(),
            imageChunks = parcel.readInt(),
        )

        @JvmField
        val CREATOR: Parcelable.Creator<SketchGuideState> = object : Parcelable.Creator<SketchGuideState> {
            override fun createFromParcel(parcel: Parcel): SketchGuideState = read(parcel)
            override fun newArray(size: Int): Array<SketchGuideState?> = arrayOfNulls(size)
        }
    }
}

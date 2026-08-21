package com.symmetricalpalmtree.notesprout.extension

import android.os.Parcel
import android.os.Parcelable
import android.os.SharedMemory

/**
 * A rendered image (arc 4 — the `IMarkdownRenderer` result): a complete image file (`mimeType`,
 * today always [ExtensionContract.MIME_WEBP], lossless with alpha) living in
 * [memory]`[0 until byteCount]`, whose decoded size the sender declares as [widthPx] × [heightPx].
 *
 * Same [SharedMemory] handshake as [RenderedTemplate]: the extension creates the region, maps RW,
 * writes, unmaps, `setProtect(PROT_READ)` and closes its handle once the reply is marshalled; the
 * host maps read-only, copies out [byteCount] bytes, unmaps, closes — and **verifies** that the
 * encoded header's size equals the declared one and that both sides are ≤
 * [ExtensionContract.MAX_IMAGE_EDGE_PX].
 *
 * Wire form: `SharedMemory · int byteCount · String mimeType · int widthPx · int heightPx`
 * (a compatible tail may be appended later; readers of this version stop after `heightPx`).
 */
class RenderedImage(
    val memory: SharedMemory,
    val byteCount: Int,
    val mimeType: String,
    val widthPx: Int,
    val heightPx: Int,
) : Parcelable {

    init {
        requireValid(byteCount, widthPx, heightPx)   // runs on the receiving side at unmarshal time too
    }

    @Suppress("DEPRECATION")
    private constructor(parcel: Parcel) : this(
        memory = parcel.readParcelable(SharedMemory::class.java.classLoader)!!,
        byteCount = parcel.readInt(),
        mimeType = parcel.readString().orEmpty(),
        widthPx = parcel.readInt(),
        heightPx = parcel.readInt(),
    )

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeParcelable(memory, flags)
        dest.writeInt(byteCount)
        dest.writeString(mimeType)
        dest.writeInt(widthPx)
        dest.writeInt(heightPx)
    }

    /** Carries a file descriptor (the ashmem region) — must say so, or `Bundle.hasFileDescriptors()` lies. */
    override fun describeContents(): Int = Parcelable.CONTENTS_FILE_DESCRIPTOR

    companion object {
        /** The constructor's checks, pure so they are JVM-testable: `byteCount > 0`, positive size, both edges ≤ [ExtensionContract.MAX_IMAGE_EDGE_PX]. */
        fun requireValid(byteCount: Int, widthPx: Int, heightPx: Int) {
            require(byteCount > 0) { "byteCount must be > 0" }
            require(widthPx > 0 && heightPx > 0) { "image size must be positive ($widthPx x $heightPx)" }
            require(widthPx <= ExtensionContract.MAX_IMAGE_EDGE_PX && heightPx <= ExtensionContract.MAX_IMAGE_EDGE_PX) {
                "image exceeds MAX_IMAGE_EDGE_PX ($widthPx x $heightPx)"
            }
        }

        @JvmField
        val CREATOR: Parcelable.Creator<RenderedImage> = object : Parcelable.Creator<RenderedImage> {
            override fun createFromParcel(parcel: Parcel): RenderedImage = RenderedImage(parcel)
            override fun newArray(size: Int): Array<RenderedImage?> = arrayOfNulls(size)
        }
    }
}

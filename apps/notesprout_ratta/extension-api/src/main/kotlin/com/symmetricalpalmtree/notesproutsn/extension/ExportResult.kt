package com.symmetricalpalmtree.notesproutsn.extension

import android.os.Parcel
import android.os.Parcelable

/**
 * What `INotebookExporter.export()` reports back: the bytes it wrote to the destination fd. The
 * host verifies this against the artifact's size (and the destination's, where the provider can
 * say) before it toasts — an exporter that died mid-stream must never read as success.
 *
 * Wire form: `long bytesWritten` (a compatible tail may be appended in a later version).
 */
class ExportResult(
    val bytesWritten: Long,
) : Parcelable {

    init {
        require(bytesWritten >= 0L) { "negative bytesWritten $bytesWritten" }
    }

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeLong(bytesWritten)
    }

    override fun describeContents(): Int = 0

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<ExportResult> = object : Parcelable.Creator<ExportResult> {
            override fun createFromParcel(parcel: Parcel): ExportResult = ExportResult(parcel.readLong())
            override fun newArray(size: Int): Array<ExportResult?> = arrayOfNulls(size)
        }
    }
}

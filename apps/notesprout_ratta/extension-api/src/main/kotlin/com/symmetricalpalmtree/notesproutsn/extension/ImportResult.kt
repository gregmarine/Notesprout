package com.symmetricalpalmtree.notesproutsn.extension

import android.os.Parcel
import android.os.Parcelable

/**
 * What `INotebookImporter.importDocument()` reports back: the bytes it wrote to the cache fd. The
 * host verifies this against the picked document's size (where the provider will say) before it
 * probes a byte of it — an importer that died mid-stream must never hand the pipeline a truncated
 * file that happens to probe.
 *
 * Wire form: `long bytesWritten` (a compatible tail may be appended in a later version).
 */
class ImportResult(
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
        val CREATOR: Parcelable.Creator<ImportResult> = object : Parcelable.Creator<ImportResult> {
            override fun createFromParcel(parcel: Parcel): ImportResult = ImportResult(parcel.readLong())
            override fun newArray(size: Int): Array<ImportResult?> = arrayOfNulls(size)
        }
    }
}

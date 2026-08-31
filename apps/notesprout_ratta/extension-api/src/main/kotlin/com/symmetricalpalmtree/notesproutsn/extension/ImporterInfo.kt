package com.symmetricalpalmtree.notesproutsn.extension

import android.os.Parcel
import android.os.Parcelable

/**
 * What one importer accepts — returned by `INotebookImporter.describe()`. The format identity
 * (label) plus the bounded file-extension and MIME lists the host matches a picked document
 * against (extension against the document's display name; the MIME list seeds the OPEN_DOCUMENT
 * filter). The constructor `require`s are the validation — unmarshal is validation (the family
 * rule), and a descriptor that fails them drops that importer with a log line, never a crash.
 *
 * Wire form: `String formatLabel · String[] fileExtensions · String[] mimeTypes ·
 * int resultKind` — the result kind is a **compatible tail** (arc 19 / M8, the
 * `ExporterInfo.sourceKind` recipe): the descriptor is `describe()`'s whole reply, so an
 * old-shape parcel simply runs out after the MIME list and the absent tail means
 * [ImporterContract.RESULT_NOTEBOOK]. The tail holds only while the descriptor stays the reply's
 * trailing payload. A further tail may be appended in a later version; readers of this version
 * stop after the result kind.
 */
class ImporterInfo(
    val formatLabel: String,
    val fileExtensions: List<String>,
    val mimeTypes: List<String>,
    val resultKind: Int = ImporterContract.RESULT_NOTEBOOK,
) : Parcelable {

    init {
        require(
            resultKind == ImporterContract.RESULT_NOTEBOOK ||
                resultKind == ImporterContract.RESULT_TEXT_DOCUMENT,
        ) { "unknown result kind $resultKind" }
        OptionDescriptor.requireLabel(formatLabel, "format label")
        require(fileExtensions.size in 1..ImporterContract.MAX_FILE_EXTENSIONS) {
            "${fileExtensions.size} file extensions outside 1..${ImporterContract.MAX_FILE_EXTENSIONS}"
        }
        for (ext in fileExtensions) {
            require(
                ext.isNotEmpty() &&
                    ext.length <= ExporterContract.MAX_FILE_EXTENSION_CHARS &&
                    ext.all { it in 'a'..'z' || it in '0'..'9' },
            ) { "file extension '$ext' is not [a-z0-9]{1..${ExporterContract.MAX_FILE_EXTENSION_CHARS}}" }
        }
        require(fileExtensions.toSet().size == fileExtensions.size) { "duplicate file extensions" }
        require(mimeTypes.size in 1..ImporterContract.MAX_MIME_TYPES) {
            "${mimeTypes.size} MIME types outside 1..${ImporterContract.MAX_MIME_TYPES}"
        }
        for (mime in mimeTypes) {
            require(
                mime.length in 3..ExporterContract.MAX_MIME_CHARS &&
                    mime.count { it == '/' } == 1 &&
                    !mime.startsWith('/') && !mime.endsWith('/'),
            ) { "malformed MIME type '$mime'" }
        }
        require(mimeTypes.toSet().size == mimeTypes.size) { "duplicate MIME types" }
    }

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeString(formatLabel)
        dest.writeStringList(fileExtensions)
        dest.writeStringList(mimeTypes)
        dest.writeInt(resultKind)
    }

    override fun describeContents(): Int = 0

    companion object {
        private fun read(parcel: Parcel): ImporterInfo {
            val formatLabel = parcel.readString() ?: ""
            val fileExtensions = parcel.createStringArrayList() ?: arrayListOf()
            val mimeTypes = parcel.createStringArrayList() ?: arrayListOf()
            // The compatible tail: an old-shape parcel runs out here, and the absent tail
            // means a `.soil` notebook — today's meaning, unchanged.
            val resultKind =
                if (parcel.dataAvail() > 0) parcel.readInt() else ImporterContract.RESULT_NOTEBOOK
            return ImporterInfo(formatLabel, fileExtensions, mimeTypes, resultKind)
        }

        @JvmField
        val CREATOR: Parcelable.Creator<ImporterInfo> = object : Parcelable.Creator<ImporterInfo> {
            override fun createFromParcel(parcel: Parcel): ImporterInfo = read(parcel)
            override fun newArray(size: Int): Array<ImporterInfo?> = arrayOfNulls(size)
        }
    }
}

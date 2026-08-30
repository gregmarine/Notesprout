package com.symmetricalpalmtree.notesproutsn.extension

import android.os.Parcel
import android.os.Parcelable

/**
 * What one exporter offers — returned by `INotebookExporter.describe()`. The format identity
 * (label · file extension · MIME) plus a bounded [options] list the host renders with its own
 * widgets. The constructor `require`s are the validation — unmarshal is validation (the family
 * rule), and a descriptor that fails them drops that exporter with a log line, never a crash.
 *
 * Wire form: `String formatLabel · String fileExtension · String mimeType ·
 * typed OptionDescriptor[] · int sourceKind` — the last is the arc-18 compatible tail: an
 * old-shape descriptor ends after the option list and reads as [ExporterContract.SOURCE_SOIL]
 * (the tail changed nothing for existing exporters), and an old reader stops before it. A further
 * tail may be appended in a later version; readers of this version stop after `sourceKind`.
 */
class ExporterInfo(
    val formatLabel: String,
    val fileExtension: String,
    val mimeType: String,
    val options: List<OptionDescriptor>,
    val sourceKind: Int = ExporterContract.SOURCE_SOIL,
) : Parcelable {

    init {
        require(sourceKind == ExporterContract.SOURCE_SOIL || sourceKind == ExporterContract.SOURCE_PAGES) {
            "unknown source kind $sourceKind"
        }
        OptionDescriptor.requireLabel(formatLabel, "format label")
        require(
            fileExtension.isNotEmpty() &&
                fileExtension.length <= ExporterContract.MAX_FILE_EXTENSION_CHARS &&
                fileExtension.all { it in 'a'..'z' || it in '0'..'9' },
        ) { "file extension '$fileExtension' is not [a-z0-9]{1..${ExporterContract.MAX_FILE_EXTENSION_CHARS}}" }
        require(
            mimeType.length in 3..ExporterContract.MAX_MIME_CHARS &&
                mimeType.count { it == '/' } == 1 &&
                !mimeType.startsWith('/') && !mimeType.endsWith('/'),
        ) { "malformed MIME type '$mimeType'" }
        require(options.size <= ExporterContract.MAX_OPTIONS) {
            "${options.size} options > ${ExporterContract.MAX_OPTIONS}"
        }
        require(options.map { it.id }.toSet().size == options.size) { "duplicate option ids" }
    }

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeString(formatLabel)
        dest.writeString(fileExtension)
        dest.writeString(mimeType)
        dest.writeTypedList(options)
        dest.writeInt(sourceKind)
    }

    override fun describeContents(): Int = 0

    companion object {
        private fun read(parcel: Parcel): ExporterInfo {
            val formatLabel = parcel.readString() ?: ""
            val fileExtension = parcel.readString() ?: ""
            val mimeType = parcel.readString() ?: ""
            val options = parcel.createTypedArrayList(OptionDescriptor.CREATOR) ?: arrayListOf()
            // The compatible tail: the descriptor is the reply's whole payload, so an old-shape
            // parcel simply runs out here and the absent tail means SOURCE_SOIL.
            val sourceKind =
                if (parcel.dataAvail() > 0) parcel.readInt() else ExporterContract.SOURCE_SOIL
            return ExporterInfo(formatLabel, fileExtension, mimeType, options, sourceKind)
        }

        @JvmField
        val CREATOR: Parcelable.Creator<ExporterInfo> = object : Parcelable.Creator<ExporterInfo> {
            override fun createFromParcel(parcel: Parcel): ExporterInfo = read(parcel)
            override fun newArray(size: Int): Array<ExporterInfo?> = arrayOfNulls(size)
        }
    }
}

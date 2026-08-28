package com.symmetricalpalmtree.notesproutsn.extension

import android.os.Parcel
import android.os.Parcelable

/**
 * What the host sends into `INotebookExporter.export()`: the chosen option [values] (option id →
 * value — a choice id or a toggle `"0"`/`"1"`, never free text) and the notebook's **display name**
 * for formats that can carry it (a title field, say). **No id, no path, no secret**: a
 * passphrase-kind option — the reserved keying option included — has **no entry here**; the host
 * consumed the secret itself.
 *
 * The constructor `require`s are the validation (unmarshal is validation — the family rule); the
 * extension re-checks by construction.
 *
 * Wire form: `int n · n × (String key · String value) · String notebookName` (a compatible tail may
 * be appended in a later version; readers of this version stop after `notebookName`).
 */
class ExportSpec(
    val values: Map<String, String>,
    val notebookName: String,
) : Parcelable {

    init {
        require(values.size <= ExporterContract.MAX_OPTIONS) {
            "${values.size} spec entries > ${ExporterContract.MAX_OPTIONS}"
        }
        for ((key, value) in values) {
            OptionDescriptor.requireId(key, "spec key")
            require(value.length <= ExporterContract.MAX_SPEC_VALUE_CHARS) {
                "spec value for '$key' over ${ExporterContract.MAX_SPEC_VALUE_CHARS} chars"
            }
        }
        require(notebookName.length <= ExporterContract.MAX_NAME_CHARS) {
            "notebook name over ${ExporterContract.MAX_NAME_CHARS} chars"
        }
        require('/' !in notebookName && '\u0000' !in notebookName) {
            "notebook name is a display name, never a path"
        }
    }

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeInt(values.size)
        for ((key, value) in values) {
            dest.writeString(key)
            dest.writeString(value)
        }
        dest.writeString(notebookName)
    }

    override fun describeContents(): Int = 0

    companion object {
        private fun read(parcel: Parcel): ExportSpec {
            val n = parcel.readInt()
            require(n in 0..ExporterContract.MAX_OPTIONS) { "$n spec entries outside 0..${ExporterContract.MAX_OPTIONS}" }
            val values = LinkedHashMap<String, String>(n)
            repeat(n) {
                val key = parcel.readString() ?: ""
                val value = parcel.readString() ?: ""
                require(key !in values) { "duplicate spec key '$key'" }
                values[key] = value
            }
            val notebookName = parcel.readString() ?: ""
            return ExportSpec(values, notebookName)
        }

        @JvmField
        val CREATOR: Parcelable.Creator<ExportSpec> = object : Parcelable.Creator<ExportSpec> {
            override fun createFromParcel(parcel: Parcel): ExportSpec = read(parcel)
            override fun newArray(size: Int): Array<ExportSpec?> = arrayOfNulls(size)
        }
    }
}

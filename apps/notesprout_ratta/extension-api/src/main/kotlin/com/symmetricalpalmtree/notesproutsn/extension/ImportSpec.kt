package com.symmetricalpalmtree.notesproutsn.extension

import android.os.Parcel
import android.os.Parcelable

/**
 * What the host sends into `INotebookImporter.importDocument()`: the bounded option [values]
 * (empty this arc — the map crosses now because an AIDL method cannot grow parameters later) and
 * the picked document's **display name** (display only — a name the picker showed the user, never
 * a path, never an id, never a secret).
 *
 * The constructor `require`s are the validation (unmarshal is validation — the family rule); the
 * extension re-checks by construction.
 *
 * Wire form: `int n · n × (String key · String value) · String displayName` (a compatible tail may
 * be appended in a later version; readers of this version stop after `displayName`).
 */
class ImportSpec(
    val values: Map<String, String>,
    val displayName: String,
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
        require(displayName.length <= ExporterContract.MAX_NAME_CHARS) {
            "display name over ${ExporterContract.MAX_NAME_CHARS} chars"
        }
        require('/' !in displayName && '\u0000' !in displayName) {
            "display name is a display name, never a path"
        }
    }

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeInt(values.size)
        for ((key, value) in values) {
            dest.writeString(key)
            dest.writeString(value)
        }
        dest.writeString(displayName)
    }

    override fun describeContents(): Int = 0

    companion object {
        private fun read(parcel: Parcel): ImportSpec {
            val n = parcel.readInt()
            require(n in 0..ExporterContract.MAX_OPTIONS) { "$n spec entries outside 0..${ExporterContract.MAX_OPTIONS}" }
            val values = LinkedHashMap<String, String>(n)
            repeat(n) {
                val key = parcel.readString() ?: ""
                val value = parcel.readString() ?: ""
                require(key !in values) { "duplicate spec key '$key'" }
                values[key] = value
            }
            val displayName = parcel.readString() ?: ""
            return ImportSpec(values, displayName)
        }

        @JvmField
        val CREATOR: Parcelable.Creator<ImportSpec> = object : Parcelable.Creator<ImportSpec> {
            override fun createFromParcel(parcel: Parcel): ImportSpec = read(parcel)
            override fun newArray(size: Int): Array<ImportSpec?> = arrayOfNulls(size)
        }
    }
}

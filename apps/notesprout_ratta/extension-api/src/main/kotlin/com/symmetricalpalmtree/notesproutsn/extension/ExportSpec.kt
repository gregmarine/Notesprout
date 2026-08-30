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
 * **[exportSecret] (arc 18) is the ONE deliberate exception to no-secret** — and it is not an
 * exception to the rule's point. It is a user-typed, **export-scoped** secret that opens no
 * Notesprout data: a password for the *output* file (a PDF password, say), collected by the host's
 * own fields for exactly this export. It is **never** the global Notesprout passphrase, never
 * derived from it, never the device key — and [ExporterContract.KIND_PASSPHRASE] keeps its
 * never-crosses meaning untouched: a keying passphrase still has no entry anywhere here. Rules on
 * both sides: never logged, never saved into instance state, never put in an Intent; the extension
 * holds it only for the protect step and clears its own copy in `finally`. `null` = no secret
 * (every export today — the host starts sending it in arc 18 / D2).
 *
 * Wire form: `int n · n × (String key · String value) · String notebookName · String? exportSecret`
 * — the last is the arc-18 compatible tail: an old-shape spec ends after `notebookName` and reads
 * as no secret, and an old reader stops before it. The spec must stay `export()`'s **trailing**
 * argument for that to hold. A further tail may be appended in a later version; readers of this
 * version stop after `exportSecret`.
 */
class ExportSpec(
    val values: Map<String, String>,
    val notebookName: String,
    val exportSecret: String? = null,
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
        exportSecret?.let {
            require(it.isNotEmpty() && it.length <= ExporterContract.MAX_EXPORT_SECRET_CHARS) {
                // Never the secret itself in a failure message.
                "export secret empty or over ${ExporterContract.MAX_EXPORT_SECRET_CHARS} chars"
            }
        }
    }

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeInt(values.size)
        for ((key, value) in values) {
            dest.writeString(key)
            dest.writeString(value)
        }
        dest.writeString(notebookName)
        dest.writeString(exportSecret)
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
            // The compatible tail: the spec is export()'s trailing argument, so an old-shape
            // parcel simply runs out here and the absent tail means no secret.
            val exportSecret = if (parcel.dataAvail() > 0) parcel.readString() else null
            return ExportSpec(values, notebookName, exportSecret)
        }

        @JvmField
        val CREATOR: Parcelable.Creator<ExportSpec> = object : Parcelable.Creator<ExportSpec> {
            override fun createFromParcel(parcel: Parcel): ExportSpec = read(parcel)
            override fun newArray(size: Int): Array<ExportSpec?> = arrayOfNulls(size)
        }
    }
}

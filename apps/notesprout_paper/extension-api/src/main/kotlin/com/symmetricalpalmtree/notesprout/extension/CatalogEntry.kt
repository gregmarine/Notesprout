package com.symmetricalpalmtree.notesprout.extension

import android.os.Parcel
import android.os.Parcelable

/**
 * One row of the host's library catalog (arc 7 / L0) — what `ILinkCatalog.listFolder` / `listPages`
 * return for the picker to browse: an [id] the host itself issued, a [kind]
 * (`ExtensionContract.CATALOG_*`) and a display [label] (≤ `MAX_CATALOG_LABEL_CHARS`; blank is legal
 * for a page with no name — the picker shows "Page n" from position). Nothing else about a row
 * crosses — never a key, path, cover or blob (rule 29).
 *
 * Hand-written Parcelable (write order fixed forever, tails may be appended):
 * `writeString(id); writeInt(kind); writeString(label)`.
 */
class CatalogEntry(
    val id: String,
    val kind: Int,
    val label: String,
) : Parcelable {

    init { requireValid(id, kind, label) }

    private constructor(parcel: Parcel) : this(
        id = parcel.readString().orEmpty(),
        kind = parcel.readInt(),
        label = parcel.readString().orEmpty(),
    )

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeString(id)
        dest.writeInt(kind)
        dest.writeString(label)
    }

    override fun describeContents(): Int = 0

    companion object {
        /** The structural rules the constructor enforces (pure — JVM-testable without a Parcel). */
        fun requireValid(id: String, kind: Int, label: String) {
            require(id.isNotBlank()) { "id is blank" }
            require(id.length <= ExtensionContract.MAX_LINK_ID_CHARS) { "id too long" }
            require(
                kind == ExtensionContract.CATALOG_FOLDER ||
                    kind == ExtensionContract.CATALOG_NOTEBOOK ||
                    kind == ExtensionContract.CATALOG_PAGE
            ) { "unknown catalog kind $kind" }
            require(label.length <= ExtensionContract.MAX_CATALOG_LABEL_CHARS) { "label too long" }
        }

        @JvmField
        val CREATOR: Parcelable.Creator<CatalogEntry> = object : Parcelable.Creator<CatalogEntry> {
            override fun createFromParcel(parcel: Parcel): CatalogEntry = CatalogEntry(parcel)
            override fun newArray(size: Int): Array<CatalogEntry?> = arrayOfNulls(size)
        }
    }
}

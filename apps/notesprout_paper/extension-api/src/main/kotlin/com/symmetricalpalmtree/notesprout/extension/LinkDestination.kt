package com.symmetricalpalmtree.notesprout.extension

import android.os.Parcel
import android.os.Parcelable

/**
 * A typed link destination (arc 7 / L0) — what `ILinkProvider.resolve` returns for an opaque payload:
 * a *description* the core validates against its own index / `.soil` and navigates to itself (rule
 * 28 — no Intent, component, path or URI ever crosses). [kind] is one of the
 * `ExtensionContract.DEST_*` values and fixes which ids are present:
 * `DEST_PAGE` → [pageId] only (a page of the notebook the link lives in) · `DEST_NOTEBOOK` →
 * [notebookId] only (opens on its last-open page) · `DEST_NOTEBOOK_PAGE` → both. Untrusted on the
 * host side — every id is re-checked against live rows before any navigation.
 *
 * Hand-written Parcelable (write order fixed forever, tails may be appended):
 * `writeInt(kind); writeString(notebookId); writeString(pageId)`.
 */
class LinkDestination(
    val kind: Int,
    val notebookId: String?,
    val pageId: String?,
) : Parcelable {

    init { requireValid(kind, notebookId, pageId) }

    private constructor(parcel: Parcel) : this(
        kind = parcel.readInt(),
        notebookId = parcel.readString(),
        pageId = parcel.readString(),
    )

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeInt(kind)
        dest.writeString(notebookId)
        dest.writeString(pageId)
    }

    override fun describeContents(): Int = 0

    companion object {
        /** The structural rules the constructor enforces (pure — JVM-testable without a Parcel). */
        fun requireValid(kind: Int, notebookId: String?, pageId: String?) {
            when (kind) {
                ExtensionContract.DEST_PAGE -> {
                    require(notebookId == null) { "DEST_PAGE carries no notebookId" }
                    requireId(pageId, "pageId")
                }
                ExtensionContract.DEST_NOTEBOOK -> {
                    requireId(notebookId, "notebookId")
                    require(pageId == null) { "DEST_NOTEBOOK carries no pageId" }
                }
                ExtensionContract.DEST_NOTEBOOK_PAGE -> {
                    requireId(notebookId, "notebookId")
                    requireId(pageId, "pageId")
                }
                else -> throw IllegalArgumentException("unknown destination kind $kind")
            }
        }

        private fun requireId(id: String?, name: String) {
            require(!id.isNullOrBlank()) { "$name is blank" }
            require(id.length <= ExtensionContract.MAX_LINK_ID_CHARS) { "$name too long" }
        }

        @JvmField
        val CREATOR: Parcelable.Creator<LinkDestination> = object : Parcelable.Creator<LinkDestination> {
            override fun createFromParcel(parcel: Parcel): LinkDestination = LinkDestination(parcel)
            override fun newArray(size: Int): Array<LinkDestination?> = arrayOfNulls(size)
        }
    }
}

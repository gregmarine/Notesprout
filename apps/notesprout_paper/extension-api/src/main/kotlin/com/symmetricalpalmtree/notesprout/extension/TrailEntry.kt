package com.symmetricalpalmtree.notesprout.extension

import android.os.Parcel
import android.os.Parcelable

/**
 * One back-trail entry (arc 7 / L0) — where a link follow left from: the origin's [notebookId] +
 * [pageId]. The trail lives in the extension's host-owned store (rule 31) behind
 * `ILinkProvider.pushTrail` / `popTrail` / `clearTrail`, capped at
 * `ExtensionContract.MAX_TRAIL_ENTRIES`; every entry is untrusted on the way back — the core
 * validates both ids against live rows before it navigates anywhere.
 *
 * Hand-written Parcelable (write order fixed forever, tails may be appended):
 * `writeString(notebookId); writeString(pageId)`.
 */
class TrailEntry(
    val notebookId: String,
    val pageId: String,
) : Parcelable {

    init { requireValid(notebookId, pageId) }

    private constructor(parcel: Parcel) : this(
        notebookId = parcel.readString().orEmpty(),
        pageId = parcel.readString().orEmpty(),
    )

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeString(notebookId)
        dest.writeString(pageId)
    }

    override fun describeContents(): Int = 0

    companion object {
        /** The structural rules the constructor enforces (pure — JVM-testable without a Parcel). */
        fun requireValid(notebookId: String, pageId: String) {
            require(notebookId.isNotBlank()) { "notebookId is blank" }
            require(notebookId.length <= ExtensionContract.MAX_LINK_ID_CHARS) { "notebookId too long" }
            require(pageId.isNotBlank()) { "pageId is blank" }
            require(pageId.length <= ExtensionContract.MAX_LINK_ID_CHARS) { "pageId too long" }
        }

        @JvmField
        val CREATOR: Parcelable.Creator<TrailEntry> = object : Parcelable.Creator<TrailEntry> {
            override fun createFromParcel(parcel: Parcel): TrailEntry = TrailEntry(parcel)
            override fun newArray(size: Int): Array<TrailEntry?> = arrayOfNulls(size)
        }
    }
}

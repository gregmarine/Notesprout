package com.symmetricalpalmtree.notesproutsn.extension

import android.os.Parcel
import android.os.Parcelable

/**
 * Where a note row points (arc 42 "Notes") — the reply to `IBible.takeOutgoingNote`, the one
 * value the reader hands back when a row of its Notes panel is tapped: the host opens
 * [notebookId] at [pageId], or its document editor for a [ExtensionContract.BIBLE_NOTE_KIND_DOCUMENT]
 * row. [pageId] is empty only for a document row on the notebook itself (a text-document
 * notebook, or the notebook document); a link row always names its page.
 *
 * Unmarshal is validation: ids are canonical UUIDs, the kind is one of the two, and a link row
 * never arrives page-less. Ids are not user content, but nothing here is logged beyond the kind.
 * Wire form: `String notebookId · String pageId · int kind`. A future field is a compatible tail.
 */
class BibleNoteTarget(
    val notebookId: String,
    val pageId: String,
    val kind: Int,
) : Parcelable {

    init {
        require(TagRules.isId(notebookId)) { "notebookId is not an id" }
        require(pageId.isEmpty() || TagRules.isId(pageId)) { "pageId is not an id" }
        require(kind == ExtensionContract.BIBLE_NOTE_KIND_LINK || kind == ExtensionContract.BIBLE_NOTE_KIND_DOCUMENT) {
            "unknown kind"
        }
        require(kind != ExtensionContract.BIBLE_NOTE_KIND_LINK || pageId.isNotEmpty()) { "a link row names its page" }
    }

    val isDocument: Boolean get() = kind == ExtensionContract.BIBLE_NOTE_KIND_DOCUMENT

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeString(notebookId)
        dest.writeString(pageId)
        dest.writeInt(kind)
    }

    override fun describeContents(): Int = 0

    override fun toString(): String = "BibleNoteTarget(kind=$kind, ${if (pageId.isEmpty()) "notebook" else "page"})"

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<BibleNoteTarget> = object : Parcelable.Creator<BibleNoteTarget> {
            override fun createFromParcel(parcel: Parcel): BibleNoteTarget = BibleNoteTarget(
                notebookId = parcel.readString() ?: "",
                pageId = parcel.readString() ?: "",
                kind = parcel.readInt(),
            )

            override fun newArray(size: Int): Array<BibleNoteTarget?> = arrayOfNulls(size)
        }
    }
}

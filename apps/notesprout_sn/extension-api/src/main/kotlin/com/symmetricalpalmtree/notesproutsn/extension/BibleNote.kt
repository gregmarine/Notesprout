package com.symmetricalpalmtree.notesproutsn.extension

import android.os.Parcel
import android.os.Parcelable

/**
 * One Bible link object on one notebook page, as the host pushes it to the reader's notes index
 * (arc 42 "Notes") — the argument of `IBible.replacePageNotes` / `replaceNotebookNotes`.
 *
 * [noteId] is the link row's own id (a canonical UUID), [pageId] the page it sits on, [pageNumber]
 * that page's 1-based ordinal at the time of the push, [wire] the reference exactly as the link's
 * payload stores it — opaque to the host, decoded to verse keys by the extension on insert
 * (`ReferenceCodec`) — and [at] when the link was created (the note's date in the panel; a
 * rebuild does not re-date a note). Unmarshal is validation, the family rule: every `require`
 * below is the host's whole check, so a note that could not be indexed never crosses.
 *
 * The wire names where the user has read; it is never logged on either side — [toString] says
 * only that there is one. Wire form: `String noteId · String pageId · int pageNumber · String
 * wire · long at`. A future field is a compatible tail.
 */
class BibleNote(
    val noteId: String,
    val pageId: String,
    val pageNumber: Int,
    val wire: String,
    val at: Long,
) : Parcelable {

    init {
        require(TagRules.isId(noteId)) { "noteId is not an id" }
        require(TagRules.isId(pageId)) { "pageId is not an id" }
        require(pageNumber >= 1) { "pageNumber is not an ordinal" }
        require(ResolvedReference.isWire(wire)) { "wire is not a reference" }
        require(at >= 0L) { "at is not a stamp" }
    }

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeString(noteId)
        dest.writeString(pageId)
        dest.writeInt(pageNumber)
        dest.writeString(wire)
        dest.writeLong(at)
    }

    override fun describeContents(): Int = 0

    override fun toString(): String = "BibleNote(page $pageNumber, ${wire.length}-char wire)"

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<BibleNote> = object : Parcelable.Creator<BibleNote> {
            override fun createFromParcel(parcel: Parcel): BibleNote = BibleNote(
                noteId = parcel.readString() ?: "",
                pageId = parcel.readString() ?: "",
                pageNumber = parcel.readInt(),
                wire = parcel.readString() ?: "",
                at = parcel.readLong(),
            )

            override fun newArray(size: Int): Array<BibleNote?> = arrayOfNulls(size)
        }
    }
}

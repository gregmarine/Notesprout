package com.symmetricalpalmtree.notesproutsn.extension

import android.os.Parcel
import android.os.Parcelable

/**
 * One attachment as it crosses the seam (arc 22 / X3) — the `assignment` table's row: a tag on a
 * notebook, or on one page **of** a notebook.
 *
 * **Every assignment names a notebook** (arc 21 / W4, unchanged). A page tag names its page as well,
 * and that is the only difference between the two kinds — [pageId] present *is* what makes it a page
 * tag, so no kind flag is carried. A stored kind would be a second copy of the answer that could
 * disagree with the question. The notebook is not decoration either: the global index holds folders
 * and notebooks, pages live inside each `.soil`, and without the notebook the library has no way on
 * earth to say which file a tagged page is in.
 *
 * **The absent page is `""`, not null**, and that is the store's spelling showing through: the row's
 * primary key is `(tagId, notebookId, pageId)`, and in SQL `NULL` is not equal to `NULL` — a
 * nullable page column would let the same notebook tag be inserted twice. The `String?` ⇄ `""`
 * mapping happens once, at the store adapter's door; [pageIdOrNull] is what the rest of the code
 * reads.
 *
 * The constructor `require`s **are** the validation, both directions. All three ids are canonical
 * UUIDs ([TagRules.isId]) except that [pageId] may be empty, which is what keeps a path character,
 * a tab or a NUL out of every id the seam carries.
 *
 * Wire form: `String tagId · String notebookId · String pageId`. A future field is a compatible tail.
 */
class AssignmentRecord(
    val tagId: String,
    /** The notebook this attachment is in — **always present**, page assignments included. */
    val notebookId: String,
    /** The page within [notebookId], or `""` when the tag hangs on the notebook itself. */
    val pageId: String = "",
) : Parcelable {

    init {
        require(TagRules.isId(tagId)) { "tag id is not a UUID" }
        require(TagRules.isId(notebookId)) { "notebook id is not a UUID" }
        require(pageId.isEmpty() || TagRules.isId(pageId)) { "page id is not a UUID" }
    }

    /** [pageId] as the rest of the code reads it: `null` for a notebook tag. */
    val pageIdOrNull: String? = pageId.ifEmpty { null }

    /** True when this hangs on the notebook itself rather than one of its pages. */
    val isNotebookTag: Boolean = pageId.isEmpty()

    /** True when this attaches to the given target — the one comparison every query makes. */
    fun isOn(notebookId: String, pageId: String?): Boolean =
        this.notebookId == notebookId && this.pageIdOrNull == pageId

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeString(tagId)
        dest.writeString(notebookId)
        dest.writeString(pageId)
    }

    override fun describeContents(): Int = 0

    override fun toString(): String =
        "AssignmentRecord($tagId on $notebookId${if (isNotebookTag) "" else "/$pageId"})"

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<AssignmentRecord> = object : Parcelable.Creator<AssignmentRecord> {
            override fun createFromParcel(parcel: Parcel): AssignmentRecord = AssignmentRecord(
                parcel.readString() ?: "",
                parcel.readString() ?: "",
                parcel.readString() ?: "",
            )

            override fun newArray(size: Int): Array<AssignmentRecord?> = arrayOfNulls(size)
        }
    }
}

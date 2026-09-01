package com.symmetricalpalmtree.notesproutsn.extension

import android.os.Parcel
import android.os.Parcelable

/**
 * One tag as it crosses the seam (arc 22 / X3) — the `tag` table's row, minus the columns only the
 * extension needs (`identityKey` is derived here, `createdAt` is nobody else's business).
 *
 * It replaces arc 21's `TagIndex.Tag` **and** the whole `TagCodec` blob that used to carry the index
 * across in one piece: the host now asks for tags a page at a time (`ITagManager.tags`), and a page
 * is a `List<TagRecord>` in the reply parcel. Nothing is encoded, nothing rides ashmem, and there is
 * no version line that could be unreadable — a row either unmarshals or it does not.
 *
 * The constructor `require`s **are** the validation, both directions — unmarshal is validation, the
 * family rule since E1. A record that fails them is a record the receiving side drops (and counts),
 * never a failed load.
 *
 * **[display] is the stored form.** It is what [TagRules.display] would make of it — trimmed, runs
 * collapsed, case kept — and the check is written as a fixed-point test rather than a normalization,
 * because a record that had to be *fixed* on the way in is a record whose store disagrees with
 * [TagRules], and that is worth failing over rather than papering over.
 *
 * Wire form: `String id · String display`. A future field is a compatible tail.
 */
class TagRecord(
    /** The tag's id: a canonical UUID, minted once when the tag is created (arc 22 / X3 — arc 21's
     *  base-36 counter existed only to make the codec's worst-case arithmetic fit, and both are gone). */
    val id: String,
    /** The casing whoever entered the tag first used — what a person sees, and what is stored. */
    val display: String,
) : Parcelable {

    init {
        require(TagRules.isId(id)) { "tag id is not a UUID" }
        require(TagRules.isValid(display)) { "display is not a tag" }
        require(display == TagRules.display(display)) { "display is not the normalized form" }
    }

    /**
     * The identity of the tag this record names — [display] folded by [TagRules.identityKey].
     *
     * Derived **once, here**, not on each read: the browse order sorts by it, and a `get()` that
     * re-normalized would run the fold twice per comparison. A record is immutable, so there is
     * nothing for the value to fall behind. (The extension also stores it, as a uniquely indexed
     * column — that is the store's uniqueness constraint, not a second definition: one function
     * writes it and this one re-derives it.)
     */
    val identityKey: String = TagRules.identityKey(display)

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeString(id)
        dest.writeString(display)
    }

    override fun describeContents(): Int = 0

    override fun toString(): String = "TagRecord($id, ${display.length} chars)"

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<TagRecord> = object : Parcelable.Creator<TagRecord> {
            override fun createFromParcel(parcel: Parcel): TagRecord =
                TagRecord(parcel.readString() ?: "", parcel.readString() ?: "")

            override fun newArray(size: Int): Array<TagRecord?> = arrayOfNulls(size)
        }
    }
}

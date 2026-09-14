package com.symmetricalpalmtree.notesproutsn.extension

import android.os.Parcel
import android.os.Parcelable

/**
 * A scripture reference the Bible reader has read and checked (arc 38 / R1) — the reply to
 * `IBible.resolve`, and the one value of the reader's that the host stores: [wire] goes into a
 * Bible link's payload, [label] is shown to the user once, in the confirmation toast.
 *
 * **[wire] is opaque to the host.** Its grammar belongs to the extension (`ReferenceCodec`
 * there): ranges `USFM:c:v-c:v` joined by `,`, a whole chapter `USFM:c:0-c:999`. What the host
 * relies on is only what the constructor `require`s — ASCII, no `|` (the link payload's
 * separator), no whitespace, at most [MAX_WIRE_CHARS] — so a wire that could break a payload
 * never unmarshals. Unmarshal is validation, the family rule.
 *
 * **[label] is the canonical display form** ("John 3:16–18; Proverbs 3:5–6"): the user's own
 * words stay on the page (the user's decision 2), so this is read aloud once and not stored.
 *
 * Neither field is ever logged on either side — a reference names where the user has read.
 *
 * Wire form: `String wire · String label`. A future field is a compatible tail.
 */
class ResolvedReference(
    val wire: String,
    val label: String,
) : Parcelable {

    init {
        require(isWire(wire)) { "wire is not a reference" }
        require(label.isNotBlank() && label.length <= MAX_LABEL_CHARS) { "label is not a reference" }
    }

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeString(wire)
        dest.writeString(label)
    }

    override fun describeContents(): Int = 0

    override fun toString(): String = "ResolvedReference(${wire.length} chars)"

    companion object {
        /** The user's decision 1 admits any combination of references; a wire longer than this is
         *  not one anybody wrote by hand. Well under the link payload's 2000. */
        const val MAX_WIRE_CHARS = 512
        const val MAX_LABEL_CHARS = 1_000

        /** What a wire must look like for the host to store it: the characters a range's
         *  `USFM:c:v-c:v,` grammar can produce, and nothing a link payload cannot carry. */
        fun isWire(wire: String): Boolean =
            wire.isNotEmpty() && wire.length <= MAX_WIRE_CHARS &&
                wire.all { it in 'A'..'Z' || it in '0'..'9' || it == ':' || it == '-' || it == ',' }

        @JvmField
        val CREATOR: Parcelable.Creator<ResolvedReference> = object : Parcelable.Creator<ResolvedReference> {
            override fun createFromParcel(parcel: Parcel): ResolvedReference =
                ResolvedReference(parcel.readString() ?: "", parcel.readString() ?: "")

            override fun newArray(size: Int): Array<ResolvedReference?> = arrayOfNulls(size)
        }
    }
}

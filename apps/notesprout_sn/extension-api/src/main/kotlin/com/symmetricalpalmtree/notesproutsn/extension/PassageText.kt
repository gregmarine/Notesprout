package com.symmetricalpalmtree.notesproutsn.extension

import android.os.Parcel
import android.os.Parcelable

/**
 * The verses of a small passage, as Markdown (arc 40 "Verses") — the reply to
 * `IBible.passageText`, and **the first time scripture crosses the Bible seam**: the host lands
 * [text] on the page as an ordinary text object wrapped in the Bible link the wire already names.
 *
 * [status] says why [text] may be empty: [STATUS_OK] carries the Markdown; [STATUS_TOO_LONG] is
 * the reader's refusal — the reference names a whole chapter or more than the verse cap
 * (`PassageMarkdown.MAX_VERSES` in the extension) — and carries nothing, so the host can explain
 * *why* rather than "could not read" (which is a null reply, not a status). The constructor
 * `require`s are the host's whole check: an OK reply is non-blank and at most [MAX_TEXT_CHARS];
 * a refusal is blank. Unmarshal is validation, the family rule.
 *
 * Neither field is ever logged on either side — a length and a status only.
 *
 * Wire form: `int status · String text`. A future field is a compatible tail.
 */
class PassageText(
    val status: Int,
    val text: String,
) : Parcelable {

    init {
        require(status == STATUS_OK || status == STATUS_TOO_LONG) { "unknown status" }
        if (status == STATUS_OK) {
            require(text.isNotBlank() && text.length <= MAX_TEXT_CHARS) { "text is not a passage" }
        } else {
            require(text.isEmpty()) { "a refusal carries no text" }
        }
    }

    val isOk: Boolean get() = status == STATUS_OK

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeInt(status)
        dest.writeString(text)
    }

    override fun describeContents(): Int = 0

    override fun toString(): String = "PassageText(status=$status, ${text.length} chars)"

    companion object {
        const val STATUS_OK = 0

        /** A whole chapter, or more verses than the reader's cap — the user's rule: "text that is
         *  too long for a single page should not be allowed", and a chapter never is. */
        const val STATUS_TOO_LONG = 1

        /** Ten verses of any English Bible are well under a thousand characters; a reply near this
         *  is not one the reader wrote. Well under a page of text at 24 sp. */
        const val MAX_TEXT_CHARS = 4_000

        /** A refusal, for the reader's convenience. */
        fun tooLong(): PassageText = PassageText(STATUS_TOO_LONG, "")

        @JvmField
        val CREATOR: Parcelable.Creator<PassageText> = object : Parcelable.Creator<PassageText> {
            override fun createFromParcel(parcel: Parcel): PassageText =
                PassageText(parcel.readInt(), parcel.readString() ?: "")

            override fun newArray(size: Int): Array<PassageText?> = arrayOfNulls(size)
        }
    }
}

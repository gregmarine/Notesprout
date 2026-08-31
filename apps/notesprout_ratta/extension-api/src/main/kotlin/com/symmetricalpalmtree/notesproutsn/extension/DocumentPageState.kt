package com.symmetricalpalmtree.notesproutsn.extension

import android.os.Parcel
import android.os.Parcelable

/**
 * What the host answers a state question with (arc 19 / M3): the editor's current target — the
 * save key, where it sits in the notebook, the chrome facts, and the shape of the text now
 * parked in the host's **read window** for `IDocumentHost.readChunk` to serve. Every
 * window-loading call (`current`, `requestPage`, `requestScope`, `requestSeed`, `requestMerge`)
 * answers with one of these, atomically with the window it describes.
 *
 * The constructor `require`s are the validation — unmarshal is validation (the family rule).
 * This parcel crosses **into the extension**, and the extension is the untrusted-inward side
 * here just as the host is for a descriptor: a state that fails them rejects the reply.
 *
 * Wire form: `String pageKey · int scope · int pageIndex · int pageCount · String title ·
 * int textDocument (0/1) · int source · int textChars · int textChunks · int seeded (0/1, the
 * M6 tail)`. A future field is a compatible tail — the state is the reply's whole payload, so a
 * reader stops after the fields it knows and an old-shape parcel simply runs out (which is how
 * `seeded` reads: a parcel with no tail left is `false`, exactly what an M3-shape answer meant).
 */
class DocumentPageState(
    /** The save target's opaque host-minted token — see [DocumentContract.MAX_PAGE_KEY_CHARS]. */
    val pageKey: String,
    /** [DocumentContract.SCOPE_PAGE] or [DocumentContract.SCOPE_NOTEBOOK]. */
    val scope: Int,
    /** 0-based position of the target page, or −1 for the notebook scope (not a page). */
    val pageIndex: Int,
    /** How many pages the notebook holds (the `‹ n / m ›` display). */
    val pageCount: Int,
    /** The notebook's display name — display only, never a path. */
    val title: String,
    /** Whether this notebook is a text document (M8 routes the ✓/Close chrome on it). */
    val textDocument: Boolean,
    /** The source strip's state for the target — one of [DocumentContract.SOURCE_NONE] /
     *  [DocumentContract.SOURCE_DRAFTED] / [DocumentContract.SOURCE_STALE]. */
    val source: Int,
    /** Length of the text in the read window (0 = an absent document's empty window). */
    val textChars: Int,
    /** How many `readChunk` calls serve that text — always ≥ 1 ([TextChunks]' empty-chunk rule). */
    val textChunks: Int,
    /**
     * M6's compatible tail: the read window holds a **fresh seed or merge draft** — recognized
     * (or merged) text the host built but has NOT stored. The editor must treat it as unsaved
     * (the host's document is still what it was — blank, for a seed) and push its saves with
     * `drafted = true` until one commits, which is the store that makes the draft real and moves
     * the watermark. `false` is M3's meaning unchanged: the window holds the stored document.
     */
    val seeded: Boolean = false,
) : Parcelable {

    init {
        require(pageKey.isNotEmpty() && pageKey.length <= DocumentContract.MAX_PAGE_KEY_CHARS) {
            "pageKey length ${pageKey.length} outside 1..${DocumentContract.MAX_PAGE_KEY_CHARS}"
        }
        require('\u0000' !in pageKey && '/' !in pageKey) { "pageKey carries a path character" }
        require(scope == DocumentContract.SCOPE_PAGE || scope == DocumentContract.SCOPE_NOTEBOOK) {
            "unknown scope $scope"
        }
        require(pageCount >= 1) { "pageCount $pageCount < 1" }
        if (scope == DocumentContract.SCOPE_PAGE) {
            require(pageIndex in 0 until pageCount) { "pageIndex $pageIndex outside 0..${pageCount - 1}" }
        } else {
            require(pageIndex == -1) { "notebook scope with pageIndex $pageIndex" }
        }
        require(title.length <= DocumentContract.MAX_TITLE_CHARS) {
            "title length ${title.length} > ${DocumentContract.MAX_TITLE_CHARS}"
        }
        require('\u0000' !in title) { "title carries NUL" }
        require(
            source == DocumentContract.SOURCE_NONE ||
                source == DocumentContract.SOURCE_DRAFTED ||
                source == DocumentContract.SOURCE_STALE,
        ) { "unknown source $source" }
        require(textChars in 0..DocumentContract.MAX_DOCUMENT_CHARS) {
            "textChars $textChars outside 0..${DocumentContract.MAX_DOCUMENT_CHARS}"
        }
        require(textChunks in 1..DocumentContract.TEXT_MAX_CHUNKS) {
            "textChunks $textChunks outside 1..${DocumentContract.TEXT_MAX_CHUNKS}"
        }
        require(textChars > 0 || textChunks == 1) { "empty text in $textChunks chunks" }
    }

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeString(pageKey)
        dest.writeInt(scope)
        dest.writeInt(pageIndex)
        dest.writeInt(pageCount)
        dest.writeString(title)
        dest.writeInt(if (textDocument) 1 else 0)
        dest.writeInt(source)
        dest.writeInt(textChars)
        dest.writeInt(textChunks)
        dest.writeInt(if (seeded) 1 else 0)
    }

    override fun describeContents(): Int = 0

    companion object {
        private fun read(parcel: Parcel): DocumentPageState = DocumentPageState(
            pageKey = parcel.readString() ?: "",
            scope = parcel.readInt(),
            pageIndex = parcel.readInt(),
            pageCount = parcel.readInt(),
            title = parcel.readString() ?: "",
            textDocument = parcel.readInt() != 0,
            source = parcel.readInt(),
            textChars = parcel.readInt(),
            textChunks = parcel.readInt(),
            // The tail: an M3-shape parcel ends at textChunks, and running out means false.
            seeded = parcel.dataAvail() > 0 && parcel.readInt() != 0,
        )

        @JvmField
        val CREATOR: Parcelable.Creator<DocumentPageState> = object : Parcelable.Creator<DocumentPageState> {
            override fun createFromParcel(parcel: Parcel): DocumentPageState = read(parcel)
            override fun newArray(size: Int): Array<DocumentPageState?> = arrayOfNulls(size)
        }
    }
}

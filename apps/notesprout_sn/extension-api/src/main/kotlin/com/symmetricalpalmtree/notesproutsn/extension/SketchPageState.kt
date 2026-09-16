package com.symmetricalpalmtree.notesproutsn.extension

import android.os.Parcel
import android.os.Parcelable

/**
 * What the host answers a sketch state question with (arc 43 / K2) — [DocumentPageState]'s shape
 * for pixels: the save key, where the page sits in the notebook, the page's size in px, and the
 * shape of the PNG now parked in the host's **read window** for [ISketchHost.readSketchChunk] to
 * serve. Every window-loading call ([ISketchHost.current], [ISketchHost.requestPage]) answers with
 * one of these, atomically with the window it describes.
 *
 * The constructor `require`s are the validation — unmarshal is validation (the family rule), and
 * because they run in `init` there is no way for `read(parcel)` to build an invalid instance; a
 * state that fails them rejects the reply. This parcel crosses **into the extension**, which is the
 * untrusted-inward side here exactly as the host is for a descriptor.
 *
 * Wire form: `String pageKey · int pageIndex · int pageCount · int width · int height ·
 * int sketchBytes · int sketchChunks · String structuralToken (the K5b tail)`. A future field is a
 * compatible tail — the state is the reply's whole payload, so a reader stops after the fields it
 * knows and an old-shape parcel simply runs out ([DocumentPageState.seeded]'s rule, and exactly how
 * [structuralToken] reads: an exhausted parcel answers `readString()` **null**, which becomes the
 * empty token — precisely what a K5-shape answer meant).
 *
 * **[sketchChunks] is derivable from [sketchBytes] and both are carried anyway.** The count is what
 * the extension loops on and the byte total is what it budgets against, so each is read directly
 * rather than recomputed at the call site — and the `require` that pins them to
 * [ByteChunks.countFor] is what keeps a hand-built state from ever disagreeing with the chunker
 * that will actually serve it.
 */
class SketchPageState(
    /** The save target's opaque host-minted token — see [SketchContract.MAX_PAGE_KEY_CHARS]. */
    val pageKey: String,
    /** 0-based position of the page in the notebook (the `‹ n / m ›` display). */
    val pageIndex: Int,
    /** How many pages the notebook holds. */
    val pageCount: Int,
    /** The page's width in px — the sketch is exactly this wide or it is refused. */
    val width: Int,
    /** The page's height in px. */
    val height: Int,
    /** Length of the PNG in the read window (**0** = the page has no sketch: an empty window). */
    val sketchBytes: Int,
    /** How many [ISketchHost.readSketchChunk] calls serve it — always ≥ 1 ([ByteChunks]' rule). */
    val sketchChunks: Int,
    /**
     * K5b's compatible tail: the host's opaque name for the page insert or delete that produced
     * this answer, or **""** when the answer is not one ([ISketchHost.current],
     * [ISketchHost.requestPage], and the two replays themselves, which name an edit rather than
     * make one).
     *
     * The face keeps it in its history and hands it back at [ISketchHost.undoPage] /
     * [ISketchHost.redoPage] — see [SketchContract.MAX_STRUCTURAL_TOKEN_CHARS] for why a *name*
     * crosses and the snapshot never does.
     */
    val structuralToken: String = "",
) : Parcelable {

    init {
        require(pageKey.isNotEmpty() && pageKey.length <= SketchContract.MAX_PAGE_KEY_CHARS) {
            "pageKey length ${pageKey.length} outside 1..${SketchContract.MAX_PAGE_KEY_CHARS}"
        }
        require('\u0000' !in pageKey && '/' !in pageKey) { "pageKey carries a path character" }
        require(pageCount >= 1) { "pageCount $pageCount < 1" }
        require(pageIndex in 0 until pageCount) { "pageIndex $pageIndex outside 0..${pageCount - 1}" }
        require(width in SketchContract.MIN_PAGE_PX..SketchContract.MAX_PAGE_PX) {
            "width $width outside ${SketchContract.MIN_PAGE_PX}..${SketchContract.MAX_PAGE_PX}"
        }
        require(height in SketchContract.MIN_PAGE_PX..SketchContract.MAX_PAGE_PX) {
            "height $height outside ${SketchContract.MIN_PAGE_PX}..${SketchContract.MAX_PAGE_PX}"
        }
        require(sketchBytes in 0..SketchContract.MAX_BYTES) {
            "sketchBytes $sketchBytes outside 0..${SketchContract.MAX_BYTES}"
        }
        require(sketchChunks in 1..SketchContract.MAX_CHUNKS) {
            "sketchChunks $sketchChunks outside 1..${SketchContract.MAX_CHUNKS}"
        }
        require(sketchBytes > 0 || sketchChunks == 1) { "an empty sketch in $sketchChunks chunks" }
        require(sketchChunks == chunksFor(sketchBytes)) {
            "sketchChunks $sketchChunks does not match $sketchBytes bytes"
        }
        require(structuralToken.length <= SketchContract.MAX_STRUCTURAL_TOKEN_CHARS) {
            "structuralToken length ${structuralToken.length} > ${SketchContract.MAX_STRUCTURAL_TOKEN_CHARS}"
        }
        // A SPACE — the literal ' ', not NUL. A token is one word in a log line and nothing else;
        // it is never a path and never displayed, so this is the whole of what it must not carry.
        require(' ' !in structuralToken) { "structuralToken carries a space" }
    }

    /** Whether the page carries a sketch at all — the screen's "start from blank paper" test. */
    val hasSketch: Boolean get() = sketchBytes > 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeString(pageKey)
        dest.writeInt(pageIndex)
        dest.writeInt(pageCount)
        dest.writeInt(width)
        dest.writeInt(height)
        dest.writeInt(sketchBytes)
        dest.writeInt(sketchChunks)
        dest.writeString(structuralToken)   // LAST — the tail, so an older reader simply stops here
    }

    override fun describeContents(): Int = 0

    companion object {
        /** How many chunks [bytes] bytes of PNG are served in — [ByteChunks.countFor], named here
         *  so a caller building a state never has to know which chunker the host will use. */
        fun chunksFor(bytes: Int): Int = ByteChunks.countFor(bytes)

        private fun read(parcel: Parcel): SketchPageState = SketchPageState(
            pageKey = parcel.readString() ?: "",
            pageIndex = parcel.readInt(),
            pageCount = parcel.readInt(),
            width = parcel.readInt(),
            height = parcel.readInt(),
            sketchBytes = parcel.readInt(),
            sketchChunks = parcel.readInt(),
            // An exhausted parcel answers null here (a K5-shape reply has no tail) — the empty
            // token, which is what "this answer is not a structural edit" has always meant.
            structuralToken = parcel.readString() ?: "",
        )

        @JvmField
        val CREATOR: Parcelable.Creator<SketchPageState> = object : Parcelable.Creator<SketchPageState> {
            override fun createFromParcel(parcel: Parcel): SketchPageState = read(parcel)
            override fun newArray(size: Int): Array<SketchPageState?> = arrayOfNulls(size)
        }
    }
}

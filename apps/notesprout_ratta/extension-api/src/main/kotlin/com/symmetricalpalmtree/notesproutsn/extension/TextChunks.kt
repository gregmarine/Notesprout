package com.symmetricalpalmtree.notesproutsn.extension

/**
 * The contract's chunking rule for document text (arc 19 / M3), written once for both sides —
 * the [InkChunks] recipe applied to text: greedy at [DocumentContract.TEXT_CHUNK_CHARS] per
 * Binder call, with one text-specific wrinkle: **a chunk never ends between the halves of a
 * surrogate pair.** Reassembly is plain concatenation either way, but a lone surrogate in a
 * `String` is at the mercy of whatever encoding a transport layer picks, so the boundary backs
 * off one char instead. A chunk is therefore at most [DocumentContract.TEXT_CHUNK_CHARS] and at
 * least one char short of it — which is why [DocumentContract.TEXT_MAX_CHUNKS] carries a `+ 1`.
 *
 * **Empty text is one empty chunk**, not zero chunks: every document — a cleared one included —
 * crosses as at least one call, so "save blank" and "read an absent document" ride the same
 * shape as everything else (the ashmem empty-value lesson: an empty value is a value, not an
 * absence). Pure; both sides pin it by test.
 */
object TextChunks {

    /** Split [text] into 1..[DocumentContract.TEXT_MAX_CHUNKS] chunks. `join` is `concat`. */
    fun chunk(text: String): List<String> {
        if (text.isEmpty()) return listOf("")
        val out = ArrayList<String>(text.length / DocumentContract.TEXT_CHUNK_CHARS + 1)
        var start = 0
        while (start < text.length) {
            var end = minOf(start + DocumentContract.TEXT_CHUNK_CHARS, text.length)
            // Never split a surrogate pair: if the boundary would separate a high surrogate from
            // its low half, close the chunk one char early. `end > start + 1` keeps progress —
            // a pathological all-high-surrogate text still advances one char per chunk.
            if (end < text.length && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate() &&
                end > start + 1
            ) {
                end--
            }
            out += text.substring(start, end)
            start = end
        }
        return out
    }
}

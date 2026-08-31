package com.symmetricalpalmtree.notesproutsn.ext.document.proofread

/**
 * The incremental-recheck arithmetic behind the editor's proofread pass — which part of the
 * document an edit dirties, and which words in it deserve a flag.
 *
 * Pure Kotlin like the rest of this package: the editor feeds it plain text and change offsets, so
 * every decision here is pinned by JVM tests rather than found on a device.
 *
 * The pass is *regional* for the screen's sake, not the checker's: lookups are microseconds, but
 * every span added or removed is an e-ink repaint. So an edit re-checks only its own lines — a word
 * never crosses a newline, which makes line bounds safe word bounds — while the tokenizer still
 * reads the whole text, so fence and link context stays right no matter how small the region is.
 */
object ProofreadCheck {

    /** A half-open character range `[start, end)` of the text to re-check. */
    data class Region(val start: Int, val end: Int)

    /**
     * Grows the changed range `[changedStart, changedEnd)` to whole-line bounds — the smallest
     * region whose words can all be judged without seeing their neighbours.
     */
    fun lineRegion(text: String, changedStart: Int, changedEnd: Int): Region {
        val s = changedStart.coerceIn(0, text.length)
        val e = changedEnd.coerceIn(s, text.length)
        var start = s
        while (start > 0 && text[start - 1] != '\n') start--
        var end = e
        while (end < text.length && text[end] != '\n') end++
        return Region(start, end)
    }

    /**
     * Whether inserting or removing [changed] can move flags *outside* its own lines. Backticks and
     * tildes open and close code regions whose reach is the rest of the document — typing the
     * closing fence is exactly the moment the "code" below it becomes prose again.
     */
    fun affectsWholeDocument(changed: CharSequence): Boolean =
        changed.any { it == '`' || it == '~' }

    /**
     * The words in [region] that deserve a spelling flag: word-shaped ([SpellEngine.shouldCheck]),
     * not ignored, and unknown to the dictionary. [spans] must come from tokenizing the *whole*
     * text (the caller shares one [ProofreadTokenizer] pass with the grammar rules) so skip
     * context — fences, inline code, link targets — is exact; only the region's words are judged.
     *
     * A word straddling a region edge is included whole — its span came from the full text, so a
     * flag never covers half a word.
     */
    fun misspelled(
        spans: List<WordSpan>,
        region: Region,
        isKnown: (String) -> Boolean,
        isIgnored: (String) -> Boolean,
    ): List<WordSpan> =
        spans.filter { span ->
            span.end > region.start && span.start < region.end &&
                SpellEngine.shouldCheck(span.word) &&
                !isIgnored(span.word) &&
                !isKnown(span.word)
        }
}

/**
 * Accumulates the span of text edited since the last check, tracked in *current-text* offsets: each
 * new edit first shifts what is already tracked, so a range noted before an earlier insertion still
 * points at the same characters afterwards.
 *
 * The shift is deliberately outward-rounding — a range can grow a little past the true edit, never
 * shrink inside it — because the cost of over-covering is a few extra word lookups, while the cost
 * of under-covering is a stale flag sitting on a corrected word.
 */
class ProofreadDirty {

    var start = -1
        private set
    var end = -1
        private set

    val isEmpty: Boolean get() = start < 0

    /** Notes an edit at [changeStart] that replaced [removed] characters with [inserted]. */
    fun note(changeStart: Int, removed: Int, inserted: Int) {
        val changeEnd = changeStart + inserted
        if (isEmpty) {
            start = changeStart
            end = changeEnd
            return
        }
        val delta = inserted - removed
        if (changeStart < start) start = (start + delta).coerceAtLeast(changeStart)
        if (changeStart < end) end = (end + delta).coerceAtLeast(changeEnd)
        start = minOf(start, changeStart)
        end = maxOf(end, changeEnd)
    }

    fun clear() {
        start = -1
        end = -1
    }
}

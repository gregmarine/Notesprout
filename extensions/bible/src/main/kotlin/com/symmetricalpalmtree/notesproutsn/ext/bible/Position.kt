package com.symmetricalpalmtree.notesproutsn.ext.bible

/**
 * Where the reader was left (arc 37 / B2, decision 7): book, chapter, and the verse the page in
 * front of the user opened on. Written on every committed turn, read on the next open — and it is
 * the whole of what the extension stores, one row in the host's `state` table.
 *
 * The wire form is deliberately boring and human-readable: `GEN:1:1`. [decode] is **total** — a
 * value from a store this build did not write (a hand-edited row, an older shape, a truncated
 * write) yields `null`, and the reader falls back to [GENESIS_1] rather than refusing to open. A
 * lost bookmark is never an error the user has to answer.
 *
 * Pure — no Android, no store. It is never logged: the position names where the user has read.
 */
data class Position(val usfm: String, val chapter: Int, val verse: Int) {

    fun encode(): String = "$usfm$SEP$chapter$SEP$verse"

    companion object {
        private const val SEP = ":"

        /** The first ever open, and the answer to anything unreadable (decision 7). */
        val GENESIS_1 = Position("GEN", 1, 1)

        /**
         * [raw] back into a position, or `null` if it is not exactly three fields naming a real
         * book and a 1-based chapter and verse.
         */
        fun decode(raw: String?): Position? {
            val parts = raw?.split(SEP) ?: return null
            if (parts.size != 3) return null
            val book = Canon.tryUsfm(parts[0]) ?: return null
            val chapter = parts[1].toIntOrNull() ?: return null
            val verse = parts[2].toIntOrNull() ?: return null
            if (chapter < 1 || verse < 1) return null
            return Position(book.usfm, chapter, verse)
        }
    }
}

package com.symmetricalpalmtree.notesprout.extension

/**
 * The contract's chunking rule for an ink transfer (arc 6 / S2), written once for both sides: greedy
 * at [ExtensionContract.TRANSFER_CHUNK_STROKES] / [ExtensionContract.TRANSFER_CHUNK_POINTS] per
 * Binder call — a single stroke over the point chunk cap is its own chunk (never split; still bounded
 * by the whole-transfer cap). Every chunk satisfies [InkBundle.requireValid]. Pure.
 */
object InkChunks {

    fun chunk(strokes: List<PaperStroke>): List<List<PaperStroke>> {
        val out = ArrayList<List<PaperStroke>>()
        var cur = ArrayList<PaperStroke>()
        var pts = 0
        for (s in strokes) {
            val fits = cur.size < ExtensionContract.TRANSFER_CHUNK_STROKES &&
                pts + s.size <= ExtensionContract.TRANSFER_CHUNK_POINTS
            if (!fits && cur.isNotEmpty()) {
                out += cur; cur = ArrayList(); pts = 0
            }
            cur += s; pts += s.size
        }
        if (cur.isNotEmpty()) out += cur
        return out
    }
}

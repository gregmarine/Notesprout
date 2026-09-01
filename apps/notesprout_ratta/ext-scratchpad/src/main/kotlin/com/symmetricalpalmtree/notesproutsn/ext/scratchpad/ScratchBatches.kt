package com.symmetricalpalmtree.notesproutsn.ext.scratchpad

import com.symmetricalpalmtree.notesproutsn.extension.ExtensionContract
import com.symmetricalpalmtree.notesproutsn.extension.Statement
import com.symmetricalpalmtree.notesproutsn.extension.StoreCodec

/**
 * Splitting a write into `exec` batches (arc 22 / X2) — pure, JVM-tested.
 *
 * One `exec` payload carries at most `STORE_MAX_VALUE_BYTES` and
 * `STORE_MAX_BATCH_STATEMENTS` statements; a flush after a long writing session, or a placement of
 * a whole notebook's ink, can exceed either. Statements are packed **in order** by the codec's own
 * arithmetic ([StoreCodec.statementBytes]), so what is measured here is exactly what the payload
 * will weigh.
 *
 * **One batch is one transaction, and therefore atomic** — which is the common case: every ordinary
 * flush, page operation and placement under the cap is one batch. Past it the write is several
 * transactions in order, and the caller's retry is what closes the gap, which is why every
 * statement the pad emits is idempotent ([ScratchSql]).
 *
 * A single statement over the byte budget gets a batch to itself: the host will refuse it, and
 * losing that one stroke is a better answer than losing the page it was drawn on.
 */
object ScratchBatches {

    fun split(
        statements: List<Statement>,
        maxBytes: Int = ExtensionContract.STORE_MAX_VALUE_BYTES,
        maxStatements: Int = ExtensionContract.STORE_MAX_BATCH_STATEMENTS,
    ): List<List<Statement>> {
        require(maxStatements >= 1) { "a batch holds at least one statement" }
        if (statements.isEmpty()) return emptyList()
        val batches = ArrayList<List<Statement>>()
        var current = ArrayList<Statement>()
        var bytes = StoreCodec.STATEMENTS_HEADER_BYTES
        for (s in statements) {
            val size = StoreCodec.statementBytes(s)
            if (current.isNotEmpty() && (bytes + size > maxBytes || current.size >= maxStatements)) {
                batches += current
                current = ArrayList()
                bytes = StoreCodec.STATEMENTS_HEADER_BYTES
            }
            current += s
            bytes += size
        }
        if (current.isNotEmpty()) batches += current
        return batches
    }
}

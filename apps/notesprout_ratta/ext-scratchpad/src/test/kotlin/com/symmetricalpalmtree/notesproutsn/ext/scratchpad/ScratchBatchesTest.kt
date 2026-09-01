package com.symmetricalpalmtree.notesproutsn.ext.scratchpad

import com.symmetricalpalmtree.notesproutsn.extension.Cell
import com.symmetricalpalmtree.notesproutsn.extension.ExtensionContract
import com.symmetricalpalmtree.notesproutsn.extension.Statement
import com.symmetricalpalmtree.notesproutsn.extension.StoreCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Packing a long write into `exec` batches (arc 22 / X2) — order preserved, both caps honoured. */
class ScratchBatchesTest {

    private fun statement(bytes: Int) = Statement("INSERT OR REPLACE INTO stroke (blob) VALUES (?)", ByteArray(bytes))

    @Test
    fun emptyIsEmpty() {
        assertEquals(emptyList<List<Statement>>(), ScratchBatches.split(emptyList()))
    }

    @Test
    fun oneBatchWhenItFits() {
        val statements = List(50) { statement(100) }
        val batches = ScratchBatches.split(statements)
        assertEquals(1, batches.size)
        assertEquals(50, batches[0].size)
    }

    @Test
    fun theByteBudgetClosesABatch_andEveryOneEncodesInsideIt() {
        val budget = 4_000
        val statements = List(20) { statement(500) }
        val batches = ScratchBatches.split(statements, maxBytes = budget)
        assertTrue("expected several batches, got ${batches.size}", batches.size > 1)
        for (b in batches) assertTrue(StoreCodec.encodeStatements(b).size <= budget)
        // Order is preserved and nothing is lost or duplicated.
        assertEquals(statements, batches.flatten())
    }

    @Test
    fun theStatementCountClosesABatch() {
        val statements = List(7) { statement(10) }
        val batches = ScratchBatches.split(statements, maxStatements = 3)
        assertEquals(listOf(3, 3, 1), batches.map { it.size })
        assertEquals(statements, batches.flatten())
    }

    @Test
    fun aLoneOversizeStatementGetsItsOwnBatch() {
        val small = statement(10)
        val huge = statement(5_000)
        val batches = ScratchBatches.split(listOf(small, huge, small), maxBytes = 1_000)
        assertEquals(listOf(1, 1, 1), batches.map { it.size })
        assertEquals(huge, batches[1].single())
    }

    /** The arithmetic the packing is measured with has to be the codec's own, exactly. */
    @Test
    fun statementBytesIsTheCodecsOwnArithmetic() {
        val statements = listOf(
            Statement("DELETE FROM stroke WHERE id = ?", "a-stroke-id"),
            Statement("INSERT INTO t VALUES (?, ?, ?)", listOf(Cell.Null, Cell.Real(1.5), Cell.Blob(ByteArray(64)))),
            Statement("SELECT 1"),
        )
        assertEquals(
            StoreCodec.STATEMENTS_HEADER_BYTES + statements.sumOf { StoreCodec.statementBytes(it) },
            StoreCodec.encodeStatements(statements).size,
        )
    }

    @Test
    fun theDefaultsAreTheContractsCaps() {
        val statements = List(ExtensionContract.STORE_MAX_BATCH_STATEMENTS + 1) { Statement("SELECT 1") }
        assertEquals(listOf(ExtensionContract.STORE_MAX_BATCH_STATEMENTS, 1), ScratchBatches.split(statements).map { it.size })
    }
}

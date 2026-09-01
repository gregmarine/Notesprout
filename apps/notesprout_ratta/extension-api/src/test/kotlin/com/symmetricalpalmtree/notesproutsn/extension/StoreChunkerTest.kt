package com.symmetricalpalmtree.notesproutsn.extension

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The host's row chunker (arc 22 / X1): a row is never split, the counts are exact, the caps are typed. */
class StoreChunkerTest {

    private val columns = listOf("id", "blob")
    private val header = StoreCodec.rowsHeaderBytes(columns)

    private fun row(blobSize: Int, id: Long = 0): List<Cell> = listOf(Cell.Integer(id), Cell.Blob(ByteArray(blobSize)))

    @Test
    fun emptyResult_isOneChunkOfZeroRows() {
        val chunks = StoreChunker(columns).finish()
        assertEquals(1, chunks.size)
        val rows = StoreCodec.decodeRows(chunks[0])
        assertEquals(columns, rows.columns)
        assertTrue(rows.isEmpty())
    }

    @Test
    fun rowsFillChunksExactly_andNeverSplit() {
        val rowBytes = StoreCodec.rowBytes(row(100))
        // A chunk that holds exactly three rows.
        val cap = header + 3 * rowBytes
        val chunker = StoreChunker(columns, chunkCap = cap, resultCap = Int.MAX_VALUE)
        repeat(7) { chunker.add(row(100, it.toLong())) }
        val chunks = chunker.finish()
        assertEquals(3, chunks.size)                       // 3 + 3 + 1
        val sizes = chunks.map { StoreCodec.decodeRows(it).size }
        assertEquals(listOf(3, 3, 1), sizes)
        for (c in chunks) assertTrue(c.size <= cap)
        assertEquals(cap, chunks[0].size)                   // the full ones are exactly full
        // Row order survives the split.
        val ids = chunks.flatMap { StoreCodec.decodeRows(it).rows.map { r -> r.long("id") } }
        assertEquals((0L..6L).toList(), ids)
        assertEquals(7, chunker.rowCount)
    }

    @Test
    fun aResultEndingOnABoundary_hasNoEmptyTrailingChunk() {
        val rowBytes = StoreCodec.rowBytes(row(10))
        val cap = header + 2 * rowBytes
        val chunker = StoreChunker(columns, chunkCap = cap, resultCap = Int.MAX_VALUE)
        repeat(4) { chunker.add(row(10)) }
        assertEquals(listOf(2, 2), chunker.finish().map { StoreCodec.decodeRows(it).size })
    }

    @Test
    fun oneRowPerChunk_whenARowNearlyFills() {
        val rowBytes = StoreCodec.rowBytes(row(500))
        val cap = header + rowBytes + 1            // one row fits, two do not
        val chunker = StoreChunker(columns, chunkCap = cap, resultCap = Int.MAX_VALUE)
        repeat(3) { chunker.add(row(500)) }
        assertEquals(3, chunker.finish().size)
    }

    @Test
    fun rowLarge_isTyped_andRaisedAtThatRow() {
        val cap = header + StoreCodec.rowBytes(row(100))
        val chunker = StoreChunker(columns, chunkCap = cap, resultCap = Int.MAX_VALUE)
        chunker.add(row(100))
        val e = assertThrows(IllegalStateException::class.java) { chunker.add(row(101)) }
        assertEquals(ExtensionContract.STORE_ROW_LARGE, e.message)
    }

    @Test
    fun resultLarge_isTyped_andCountsEveryChunk() {
        val rowBytes = StoreCodec.rowBytes(row(100))
        val cap = header + 2 * rowBytes
        // Room for exactly two full chunks and nothing more.
        val chunker = StoreChunker(columns, chunkCap = cap, resultCap = 2 * cap)
        repeat(4) { chunker.add(row(100)) }
        val e = assertThrows(IllegalStateException::class.java) { chunker.add(row(100)) }
        assertEquals(ExtensionContract.STORE_RESULT_LARGE, e.message)
    }

    @Test
    fun defaults_areTheContractCaps() {
        // A row at exactly the chunk cap (with the header) fits; one byte more is STORE_ROW_LARGE.
        val fits = ExtensionContract.STORE_MAX_VALUE_BYTES - header - (1 + 8) - (1 + 4)
        StoreChunker(columns).add(row(fits))
        val e = assertThrows(IllegalStateException::class.java) { StoreChunker(columns).add(row(fits + 1)) }
        assertEquals(ExtensionContract.STORE_ROW_LARGE, e.message)
    }

    @Test
    fun cellCountMustMatchColumns_andFinishIsFinal() {
        val chunker = StoreChunker(columns)
        assertThrows(IllegalArgumentException::class.java) { chunker.add(listOf(Cell.Null)) }
        chunker.finish()
        assertThrows(IllegalStateException::class.java) { chunker.add(row(1)) }
        assertThrows(IllegalStateException::class.java) { chunker.finish() }
        assertThrows(IllegalArgumentException::class.java) { StoreChunker(emptyList()) }
    }
}

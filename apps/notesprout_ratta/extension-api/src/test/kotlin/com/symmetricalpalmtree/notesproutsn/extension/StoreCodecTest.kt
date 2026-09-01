package com.symmetricalpalmtree.notesproutsn.extension

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The two wire documents (arc 22 / X1): every cell kind round-trips, and unreadable is never empty. */
class StoreCodecTest {

    private val everyKind = listOf(
        Cell.Null,
        Cell.Integer(Long.MIN_VALUE),
        Cell.Integer(0),
        Cell.Integer(Long.MAX_VALUE),
        Cell.Real(-0.0),
        Cell.Real(Double.MAX_VALUE),
        Cell.Real(Double.NaN),
        Cell.Text(""),
        Cell.Text("héllo wörld — 日本語 😀"),
        Cell.Blob(ByteArray(0)),
        Cell.Blob(ByteArray(1000) { (it % 251).toByte() }),
    )

    @Test
    fun statements_roundTrip_everyCellKind() {
        val statements = listOf(
            Statement("SELECT 1"),
            Statement("INSERT INTO t VALUES (${everyKind.joinToString { "?" }})", everyKind),
            Statement("DELETE FROM t WHERE id = ?", listOf(Cell.Text("x"))),
        )
        val back = StoreCodec.decodeStatements(StoreCodec.encodeStatements(statements))
        assertEquals(statements.size, back.size)
        for ((a, b) in statements.zip(back)) {
            assertEquals(a.sql, b.sql)
            assertEquals(a.args.size, b.args.size)
            for ((x, y) in a.args.zip(b.args)) assertCell(x, y)
        }
    }

    @Test
    fun rows_roundTrip_everyCellKind() {
        val columns = listOf("a", "b", "cölumn", "\"order\"")
        val rows = listOf(
            listOf(Cell.Null, Cell.Integer(1), Cell.Real(2.5), Cell.Text("x")),
            listOf(Cell.Blob(byteArrayOf(1, 2, 3)), Cell.Text(""), Cell.Null, Cell.Integer(-1)),
        )
        val back = StoreCodec.decodeRows(StoreCodec.encodeRows(columns, rows))
        assertEquals(columns, back.columns)
        assertEquals(2, back.size)
        for ((a, b) in rows.zip(back.cells)) for ((x, y) in a.zip(b)) assertCell(x, y)
        // Typed access by index and by name.
        assertEquals(1L, back[0].long("b"))
        assertEquals(2.5, back[0].real(2), 0.0)
        assertEquals("x", back[0].text("\"order\""))
        assertTrue(back[0].isNull("a"))
        assertNull(back[0].longOrNull(0))
        assertArrayEquals(byteArrayOf(1, 2, 3), back[1].blob("a"))
        assertEquals(-1L, back[1].long(3))
        // An integer reads as a real (SQLite affinity), nothing else crosses kinds.
        assertEquals(1.0, back[0].real("b"), 0.0)
        assertThrows(IllegalArgumentException::class.java) { back[0].long("cölumn") }
        assertThrows(IllegalArgumentException::class.java) { back[0].text("missing") }
        assertThrows(IllegalArgumentException::class.java) { back[1].text("b").let { back[1].blob("b") } }
    }

    @Test
    fun rows_emptyResult_isAValidDocumentWithColumns() {
        val back = StoreCodec.decodeRows(StoreCodec.encodeRows(listOf("id"), emptyList()))
        assertEquals(listOf("id"), back.columns)
        assertTrue(back.isEmpty())
        assertEquals(0, back.rows.size)
    }

    @Test
    fun rows_zeroColumns_refusedBothWays() {
        assertThrows(IllegalArgumentException::class.java) { StoreCodec.encodeRows(emptyList(), emptyList()) }
        // Hand-built NSRW · v1 · columnCount 0 · rowCount 0.
        val doc = "NSRW".toByteArray(Charsets.US_ASCII) + byteArrayOf(1, 0, 0, 0, 0, 0, 0)
        assertThrows(IllegalArgumentException::class.java) { StoreCodec.decodeRows(doc) }
    }

    @Test
    fun rows_cellCountMustMatchColumns() {
        assertThrows(IllegalArgumentException::class.java) {
            StoreCodec.encodeRows(listOf("a", "b"), listOf(listOf(Cell.Null)))
        }
    }

    @Test
    fun unknownMagicOrVersion_isUnreadable_neverEmpty() {
        val rows = StoreCodec.encodeRows(listOf("a"), listOf(listOf(Cell.Integer(1))))
        val statements = StoreCodec.encodeStatements(listOf(Statement("SELECT 1")))
        // Wrong magic.
        assertThrows(IllegalArgumentException::class.java) { StoreCodec.decodeRows(statements) }
        assertThrows(IllegalArgumentException::class.java) { StoreCodec.decodeStatements(rows) }
        // Wrong version.
        val v2 = rows.copyOf().also { it[4] = 2 }
        assertThrows(IllegalArgumentException::class.java) { StoreCodec.decodeRows(v2) }
        // Truncated.
        assertThrows(IllegalArgumentException::class.java) { StoreCodec.decodeRows(rows.copyOf(rows.size - 1)) }
        assertThrows(IllegalArgumentException::class.java) { StoreCodec.decodeStatements(statements.copyOf(6)) }
        // Trailing garbage.
        assertThrows(IllegalArgumentException::class.java) { StoreCodec.decodeRows(rows + byteArrayOf(0)) }
        // Empty.
        assertThrows(IllegalArgumentException::class.java) { StoreCodec.decodeRows(ByteArray(0)) }
        // Bad cell tag.
        val badTag = rows.copyOf().also { it[it.size - 9] = 9 }
        assertThrows(IllegalArgumentException::class.java) { StoreCodec.decodeRows(badTag) }
        // A length past the end.
        val text = StoreCodec.encodeRows(listOf("a"), listOf(listOf(Cell.Text("abc"))))
        val past = text.copyOf().also { it[it.size - 4] = 0x7F }
        assertThrows(IllegalArgumentException::class.java) { StoreCodec.decodeRows(past) }
    }

    @Test
    fun statementCaps() {
        assertThrows(IllegalArgumentException::class.java) { StoreCodec.encodeStatements(emptyList()) }
        val tooMany = List(ExtensionContract.STORE_MAX_BATCH_STATEMENTS + 1) { Statement("SELECT 1") }
        assertThrows(IllegalArgumentException::class.java) { StoreCodec.encodeStatements(tooMany) }
        val tooManyArgs = Statement("SELECT 1", List(ExtensionContract.STORE_MAX_ARGS + 1) { Cell.Null })
        assertThrows(IllegalArgumentException::class.java) { StoreCodec.encodeStatements(listOf(tooManyArgs)) }
        val maxArgs = Statement("SELECT 1", List(ExtensionContract.STORE_MAX_ARGS) { Cell.Null })
        assertEquals(ExtensionContract.STORE_MAX_ARGS, StoreCodec.decodeStatements(StoreCodec.encodeStatements(listOf(maxArgs)))[0].args.size)
    }

    @Test
    fun sizes_matchTheBytesWritten() {
        val columns = listOf("id", "cölumn")
        for (cell in everyKind) {
            val one = StoreCodec.encodeRows(columns, listOf(listOf(cell, cell)))
            assertEquals(cell.toString(), StoreCodec.rowsHeaderBytes(columns) + 2 * StoreCodec.cellBytes(cell), one.size)
        }
        assertEquals(StoreCodec.rowsHeaderBytes(columns), StoreCodec.encodeRows(columns, emptyList()).size)
        assertEquals(everyKind.sumOf { StoreCodec.cellBytes(it) }, StoreCodec.rowBytes(everyKind))
    }

    @Test
    fun cellOf_mapsKotlinValues() {
        assertEquals(Cell.Null, Cell.of(null))
        assertEquals(Cell.Integer(3), Cell.of(3))
        assertEquals(Cell.Integer(3), Cell.of(3L))
        assertEquals(Cell.Integer(1), Cell.of(true))
        assertEquals(Cell.Real(1.5), Cell.of(1.5))
        assertEquals(Cell.Real(1.5), Cell.of(1.5f))
        assertEquals(Cell.Text("x"), Cell.of("x"))
        assertEquals(Cell.Blob(byteArrayOf(1)), Cell.of(byteArrayOf(1)))
        assertEquals(Cell.Text("x"), Cell.of(Cell.Text("x")))
        assertThrows(IllegalArgumentException::class.java) { Cell.of(Any()) }
        val s = Statement("SELECT ?, ?", 1, "a")
        assertEquals(listOf(Cell.Integer(1), Cell.Text("a")), s.args)
    }

    private fun assertCell(expected: Cell, actual: Cell) {
        if (expected is Cell.Real && expected.value.isNaN()) {
            assertTrue((actual as Cell.Real).value.isNaN())
        } else {
            assertEquals(expected, actual)
        }
    }
}

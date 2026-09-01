package com.symmetricalpalmtree.notesproutsn.extension

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException

/**
 * One SQLite value as it crosses the store seam (arc 22 / X1) — SQLite's five storage classes and
 * nothing else. [Blob] compares by content.
 */
sealed class Cell {
    object Null : Cell() {
        override fun toString() = "NULL"
    }

    data class Integer(val value: Long) : Cell()

    data class Real(val value: Double) : Cell()

    data class Text(val value: String) : Cell()

    class Blob(val value: ByteArray) : Cell() {
        override fun equals(other: Any?): Boolean = other is Blob && other.value.contentEquals(value)
        override fun hashCode(): Int = value.contentHashCode()
        override fun toString() = "Blob(${value.size} bytes)"
    }

    companion object {
        /** The `?` binding for a Kotlin value: `null`, `Long`/`Int`, `Double`/`Float`, `String`,
         *  `ByteArray`, `Boolean` (0/1). Anything else is refused. */
        fun of(value: Any?): Cell = when (value) {
            null -> Null
            is Cell -> value
            is Long -> Integer(value)
            is Int -> Integer(value.toLong())
            is Boolean -> Integer(if (value) 1L else 0L)
            is Double -> Real(value)
            is Float -> Real(value.toDouble())
            is String -> Text(value)
            is ByteArray -> Blob(value)
            else -> throw IllegalArgumentException("not a store value: ${value.javaClass.simpleName}")
        }
    }
}

/** One parameterized statement: the SQL with `?` / `?NNN` binds and its arguments, in bind order. */
class Statement(val sql: String, val args: List<Cell> = emptyList()) {
    /** Convenience: `Statement("… ?, ?", 1L, "x")` with [Cell.of] applied to each argument. */
    constructor(sql: String, vararg args: Any?) : this(sql, args.map { Cell.of(it) })

    override fun toString() = "Statement(${sql.length} chars, ${args.size} args)"
}

/**
 * One decoded row. Typed accessors answer the cell's value or throw `IllegalArgumentException`
 * when the column is absent or of another storage class — an extension that treats a malformed row
 * as "drop this row" catches exactly that.
 */
class Row(val columns: List<String>, val cells: List<Cell>) {
    val size: Int get() = cells.size

    operator fun get(index: Int): Cell = cells[index]
    operator fun get(column: String): Cell = cells[indexOf(column)]

    fun indexOf(column: String): Int {
        val i = columns.indexOf(column)
        require(i >= 0) { "no column '$column' in $columns" }
        return i
    }

    fun isNull(index: Int): Boolean = cells[index] is Cell.Null
    fun isNull(column: String): Boolean = isNull(indexOf(column))

    fun long(index: Int): Long = (cells[index] as? Cell.Integer)?.value
        ?: throw IllegalArgumentException("column ${name(index)} is not INTEGER (${cells[index]})")
    fun long(column: String): Long = long(indexOf(column))
    fun longOrNull(index: Int): Long? = if (isNull(index)) null else long(index)
    fun longOrNull(column: String): Long? = longOrNull(indexOf(column))

    fun real(index: Int): Double = when (val c = cells[index]) {
        is Cell.Real -> c.value
        is Cell.Integer -> c.value.toDouble()   // SQLite's own affinity: an integer is a real
        else -> throw IllegalArgumentException("column ${name(index)} is not REAL ($c)")
    }
    fun real(column: String): Double = real(indexOf(column))
    fun realOrNull(index: Int): Double? = if (isNull(index)) null else real(index)
    fun realOrNull(column: String): Double? = realOrNull(indexOf(column))

    fun text(index: Int): String = (cells[index] as? Cell.Text)?.value
        ?: throw IllegalArgumentException("column ${name(index)} is not TEXT (${cells[index]})")
    fun text(column: String): String = text(indexOf(column))
    fun textOrNull(index: Int): String? = if (isNull(index)) null else text(index)
    fun textOrNull(column: String): String? = textOrNull(indexOf(column))

    fun blob(index: Int): ByteArray = (cells[index] as? Cell.Blob)?.value
        ?: throw IllegalArgumentException("column ${name(index)} is not BLOB (${cells[index]})")
    fun blob(column: String): ByteArray = blob(indexOf(column))
    fun blobOrNull(index: Int): ByteArray? = if (isNull(index)) null else blob(index)
    fun blobOrNull(column: String): ByteArray? = blobOrNull(indexOf(column))

    private fun name(index: Int): String = columns.getOrNull(index) ?: "#$index"
}

/** A decoded rows document (one chunk, or a whole result once `StoreReads.all` has stitched it). */
class StoreRows(val columns: List<String>, val cells: List<List<Cell>>) : Iterable<Row> {
    val size: Int get() = cells.size
    fun isEmpty(): Boolean = cells.isEmpty()
    operator fun get(index: Int): Row = Row(columns, cells[index])
    val rows: List<Row> get() = cells.map { Row(columns, it) }
    override fun iterator(): Iterator<Row> = rows.iterator()
}

/**
 * The two wire documents of the store seam (arc 22 / X1), pure and shared by both sides —
 * big-endian `DataOutputStream`, the arc-11 page-codec idiom.
 *
 * **Statements** — magic `NSST` · u8 version 1 · u16 count · per statement u32 sqlLen + UTF-8 sql ·
 * u16 argc · args as cells. **Rows** — magic `NSRW` · u8 version 1 · u16 columnCount · per column
 * u16 nameLen + UTF-8 name · u32 rowCount · per row per column a **cell**: u8 tag (`0 NULL ·
 * 1 INTEGER i64 · 2 REAL f64 · 3 TEXT u32 + UTF-8 · 4 BLOB u32 + bytes`).
 *
 * Unknown magic or version, a truncated document, a bad tag or a length past the end all throw
 * `IllegalArgumentException` — **unreadable is never empty** (the arc-11 rule that keeps a
 * half-read value from being written over). A rows document must have at least one column
 * (a SELECT always does; a zero-column document is a corrupt one).
 */
object StoreCodec {

    const val STATEMENTS_MAGIC = "NSST"
    const val ROWS_MAGIC = "NSRW"
    const val VERSION = 1

    private const val TAG_NULL = 0
    private const val TAG_INTEGER = 1
    private const val TAG_REAL = 2
    private const val TAG_TEXT = 3
    private const val TAG_BLOB = 4

    // ── Statements ──────

    fun encodeStatements(statements: List<Statement>): ByteArray {
        require(statements.size in 1..ExtensionContract.STORE_MAX_BATCH_STATEMENTS) {
            "1..${ExtensionContract.STORE_MAX_BATCH_STATEMENTS} statements per batch (${statements.size})"
        }
        val bytes = ByteArrayOutputStream()
        val out = DataOutputStream(bytes)
        out.writeBytes(STATEMENTS_MAGIC)
        out.writeByte(VERSION)
        out.writeShort(statements.size)
        for (s in statements) {
            require(s.args.size <= ExtensionContract.STORE_MAX_ARGS) {
                "${s.args.size} args — at most ${ExtensionContract.STORE_MAX_ARGS}"
            }
            val sql = s.sql.toByteArray(Charsets.UTF_8)
            out.writeInt(sql.size)
            out.write(sql)
            out.writeShort(s.args.size)
            for (a in s.args) writeCell(out, a)
        }
        out.flush()
        return bytes.toByteArray()
    }

    fun decodeStatements(bytes: ByteArray): List<Statement> = read(bytes) { input ->
        readMagic(input, STATEMENTS_MAGIC)
        val count = input.readUnsignedShort()
        require(count in 1..ExtensionContract.STORE_MAX_BATCH_STATEMENTS) { "statement count $count" }
        val list = ArrayList<Statement>(count)
        repeat(count) {
            val sql = String(readBytes(input, input.readInt()), Charsets.UTF_8)
            val argc = input.readUnsignedShort()
            require(argc <= ExtensionContract.STORE_MAX_ARGS) { "arg count $argc" }
            val args = ArrayList<Cell>(argc)
            repeat(argc) { args += readCell(input) }
            list += Statement(sql, args)
        }
        list
    }

    // ── Rows ──────

    fun encodeRows(columns: List<String>, rows: List<List<Cell>>): ByteArray {
        val bytes = ByteArrayOutputStream()
        val out = DataOutputStream(bytes)
        writeRowsHeader(out, columns)
        out.writeInt(rows.size)
        for (row in rows) {
            require(row.size == columns.size) { "row has ${row.size} cells for ${columns.size} columns" }
            for (c in row) writeCell(out, c)
        }
        out.flush()
        return bytes.toByteArray()
    }

    fun decodeRows(bytes: ByteArray): StoreRows = read(bytes) { input ->
        readMagic(input, ROWS_MAGIC)
        val columnCount = input.readUnsignedShort()
        require(columnCount >= 1) { "a rows document needs at least one column" }
        val columns = ArrayList<String>(columnCount)
        repeat(columnCount) { columns += String(readBytes(input, input.readUnsignedShort()), Charsets.UTF_8) }
        val rowCount = input.readInt()
        require(rowCount >= 0) { "row count $rowCount" }
        val rows = ArrayList<List<Cell>>(minOf(rowCount, 1 shl 16))
        repeat(rowCount) {
            val cells = ArrayList<Cell>(columnCount)
            repeat(columnCount) { cells += readCell(input) }
            rows += cells
        }
        StoreRows(columns, rows)
    }

    // ── Sizes (the chunker's arithmetic — the exact bytes the writers above produce) ──────

    /** The encoded size of one cell. */
    fun cellBytes(cell: Cell): Int = when (cell) {
        is Cell.Null -> 1
        is Cell.Integer -> 1 + 8
        is Cell.Real -> 1 + 8
        is Cell.Text -> 1 + 4 + utf8Length(cell.value)
        is Cell.Blob -> 1 + 4 + cell.value.size
    }

    /** The encoded size of one row (its cells only — the header is per document). */
    fun rowBytes(cells: List<Cell>): Int = cells.sumOf { cellBytes(it) }

    /** The rows document's header — magic, version, column names and the row count — for [columns]. */
    fun rowsHeaderBytes(columns: List<String>): Int =
        4 + 1 + 2 + columns.sumOf { 2 + utf8Length(it) } + 4

    /** The statements document's header — magic, version and the statement count. */
    const val STATEMENTS_HEADER_BYTES: Int = 4 + 1 + 2

    /** The encoded size of one statement inside a batch — `u32 sqlLen + sql · u16 argc + args`, the
     *  exact bytes [encodeStatements] writes for it. A batch is
     *  [STATEMENTS_HEADER_BYTES] + the sum over its statements, which is what an extension splitting
     *  a long write into `STORE_MAX_VALUE_BYTES`-sized batches has to measure against. */
    fun statementBytes(statement: Statement): Int =
        4 + utf8Length(statement.sql) + 2 + statement.args.sumOf { cellBytes(it) }

    // ── Internals ──────

    private fun writeRowsHeader(out: DataOutputStream, columns: List<String>) {
        require(columns.isNotEmpty()) { "a rows document needs at least one column" }
        require(columns.size <= 0xFFFF) { "${columns.size} columns" }
        out.writeBytes(ROWS_MAGIC)
        out.writeByte(VERSION)
        out.writeShort(columns.size)
        for (name in columns) {
            val b = name.toByteArray(Charsets.UTF_8)
            require(b.size <= 0xFFFF) { "column name too long" }
            out.writeShort(b.size)
            out.write(b)
        }
    }

    private fun writeCell(out: DataOutputStream, cell: Cell) {
        when (cell) {
            is Cell.Null -> out.writeByte(TAG_NULL)
            is Cell.Integer -> { out.writeByte(TAG_INTEGER); out.writeLong(cell.value) }
            is Cell.Real -> { out.writeByte(TAG_REAL); out.writeDouble(cell.value) }
            is Cell.Text -> {
                val b = cell.value.toByteArray(Charsets.UTF_8)
                out.writeByte(TAG_TEXT); out.writeInt(b.size); out.write(b)
            }
            is Cell.Blob -> { out.writeByte(TAG_BLOB); out.writeInt(cell.value.size); out.write(cell.value) }
        }
    }

    private fun readCell(input: DataInputStream): Cell = when (val tag = input.readUnsignedByte()) {
        TAG_NULL -> Cell.Null
        TAG_INTEGER -> Cell.Integer(input.readLong())
        TAG_REAL -> Cell.Real(input.readDouble())
        TAG_TEXT -> Cell.Text(String(readBytes(input, input.readInt()), Charsets.UTF_8))
        TAG_BLOB -> Cell.Blob(readBytes(input, input.readInt()))
        else -> throw IllegalArgumentException("unknown cell tag $tag")
    }

    private fun readMagic(input: DataInputStream, magic: String) {
        val m = ByteArray(4)
        input.readFully(m)
        require(String(m, Charsets.US_ASCII) == magic) { "not a store document (magic ${String(m, Charsets.US_ASCII)})" }
        val v = input.readUnsignedByte()
        require(v == VERSION) { "unknown store document version $v" }
    }

    private fun readBytes(input: DataInputStream, length: Int): ByteArray {
        require(length >= 0) { "negative length" }
        require(length <= input.available()) { "length $length past the end of the document" }
        val b = ByteArray(length)
        input.readFully(b)
        return b
    }

    private inline fun <T> read(bytes: ByteArray, block: (DataInputStream) -> T): T {
        val input = DataInputStream(ByteArrayInputStream(bytes))
        return try {
            val result = block(input)
            require(input.available() == 0) { "${input.available()} trailing byte(s)" }
            result
        } catch (e: EOFException) {
            throw IllegalArgumentException("truncated store document", e)
        } catch (e: IOException) {
            throw IllegalArgumentException("unreadable store document", e)
        }
    }

    /** UTF-8 byte length without allocating the encoding. */
    private fun utf8Length(s: String): Int {
        var n = 0
        var i = 0
        while (i < s.length) {
            val c = s[i]
            n += when {
                c.code < 0x80 -> 1
                c.code < 0x800 -> 2
                Character.isHighSurrogate(c) && i + 1 < s.length && Character.isLowSurrogate(s[i + 1]) -> { i++; 4 }
                else -> 3
            }
            i++
        }
        return n
    }
}

/**
 * Splits a query's rows into chunks as the host reads them (arc 22 / X1), each chunk a complete
 * rows document at or under [chunkCap] bytes, **a row never divided between two**. [add] each row
 * in order, then [finish]. An empty result is one chunk of zero rows.
 *
 * Refusals are the contract's typed messages: a single row that will not fit one chunk (with the
 * header) → `IllegalStateException(STORE_ROW_LARGE)`; a result whose chunks would sum past
 * [resultCap] → `IllegalStateException(STORE_RESULT_LARGE)`. Both are raised at the row that
 * crosses, so the host stops reading there rather than materializing the rest.
 */
class StoreChunker(
    private val columns: List<String>,
    private val chunkCap: Int = ExtensionContract.STORE_MAX_VALUE_BYTES,
    private val resultCap: Int = ExtensionContract.STORE_MAX_RESULT_BYTES,
) {
    private val headerBytes = StoreCodec.rowsHeaderBytes(columns)
    private val chunks = ArrayList<ByteArray>()
    private var current = ArrayList<List<Cell>>()
    private var currentBytes = headerBytes
    private var sealedBytes = 0L
    private var finished = false

    init {
        require(columns.isNotEmpty()) { "a rows document needs at least one column" }
        require(headerBytes <= chunkCap) { "the column names alone exceed the chunk cap" }
    }

    /** Rows added so far, across every chunk. */
    var rowCount: Int = 0
        private set

    fun add(cells: List<Cell>) {
        check(!finished) { "finished" }
        require(cells.size == columns.size) { "row has ${cells.size} cells for ${columns.size} columns" }
        val bytes = StoreCodec.rowBytes(cells)
        if (headerBytes + bytes > chunkCap) throw IllegalStateException(ExtensionContract.STORE_ROW_LARGE)
        if (currentBytes + bytes > chunkCap) seal()
        if (sealedBytes + currentBytes + bytes > resultCap) throw IllegalStateException(ExtensionContract.STORE_RESULT_LARGE)
        current += cells
        currentBytes += bytes
        rowCount++
    }

    /** The chunks, in order — at least one. */
    fun finish(): List<ByteArray> {
        check(!finished) { "finished" }
        finished = true
        // A result that ended exactly on a chunk boundary does not get an empty trailing chunk;
        // an empty result gets its one.
        if (chunks.isEmpty() || current.isNotEmpty()) seal()
        return chunks
    }

    private fun seal() {
        chunks += StoreCodec.encodeRows(columns, current)
        sealedBytes += currentBytes
        current = ArrayList()
        currentBytes = headerBytes
    }
}

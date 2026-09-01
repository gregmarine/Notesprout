package com.symmetricalpalmtree.notesproutsn.data.extstore

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import com.symmetricalpalmtree.notesproutsn.extension.Cell

/**
 * What the gate needs from a store's connection (arc 22 / X1), with **no Android types precisely so
 * the gate runs on the JVM** — tests inject a fake; [SupportStoreExecutor] is the device one.
 *
 * Every method is blocking and runs on the host's Binder thread or on IO, never Main. Failures come
 * back as whatever the connection throws; the gate maps every one of them to the three exceptions
 * a Binder can carry.
 */
interface StoreExecutor {
    /** Run [block] in one transaction: committed when it returns, rolled back when it throws. */
    fun <T> transaction(block: () -> T): T

    /** One DDL statement (schema steps and the host's own table). */
    fun ddl(sql: String)

    /** One write with its binds; answers `changes()` — the rows the statement touched. */
    fun exec(sql: String, args: List<Cell>): Long

    /** One read with its binds: [sink] gets the column names once, then every row in order until it
     *  answers false (the chunker's caps) or the rows run out. */
    fun query(sql: String, args: List<Cell>, sink: RowSink)

    interface RowSink {
        fun columns(names: List<String>)

        /** Answer false to stop reading. */
        fun row(cells: List<Cell>): Boolean
    }
}

/** [StoreExecutor] over the store's `SupportSQLiteDatabase` — the only thing here that touches SQLite. */
class SupportStoreExecutor(private val db: SupportSQLiteDatabase) : StoreExecutor {

    override fun <T> transaction(block: () -> T): T {
        db.beginTransaction()
        try {
            val result = block()
            db.setTransactionSuccessful()
            return result
        } finally {
            db.endTransaction()
        }
    }

    override fun ddl(sql: String) = db.execSQL(sql)

    override fun exec(sql: String, args: List<Cell>): Long {
        val statement = db.compileStatement(sql)
        try {
            for ((i, cell) in args.withIndex()) {
                val index = i + 1
                when (cell) {
                    is Cell.Null -> statement.bindNull(index)
                    is Cell.Integer -> statement.bindLong(index, cell.value)
                    is Cell.Real -> statement.bindDouble(index, cell.value)
                    is Cell.Text -> statement.bindString(index, cell.value)
                    is Cell.Blob -> statement.bindBlob(index, cell.value)
                }
            }
            return statement.executeUpdateDelete().toLong()
        } finally {
            statement.close()
        }
    }

    override fun query(sql: String, args: List<Cell>, sink: StoreExecutor.RowSink) {
        val bound = Array<Any?>(args.size) { i ->
            when (val c = args[i]) {
                is Cell.Null -> null
                is Cell.Integer -> c.value
                is Cell.Real -> c.value
                is Cell.Text -> c.value
                is Cell.Blob -> c.value
            }
        }
        db.query(sql, bound).use { cursor ->
            val names = cursor.columnNames.toList()
            sink.columns(names)
            val n = names.size
            while (cursor.moveToNext()) {
                val cells = ArrayList<Cell>(n)
                for (i in 0 until n) {
                    cells += when (cursor.getType(i)) {
                        Cursor.FIELD_TYPE_NULL -> Cell.Null
                        Cursor.FIELD_TYPE_INTEGER -> Cell.Integer(cursor.getLong(i))
                        Cursor.FIELD_TYPE_FLOAT -> Cell.Real(cursor.getDouble(i))
                        Cursor.FIELD_TYPE_STRING -> Cell.Text(cursor.getString(i))
                        Cursor.FIELD_TYPE_BLOB -> Cell.Blob(cursor.getBlob(i))
                        else -> Cell.Null
                    }
                }
                if (!sink.row(cells)) return
            }
        }
    }
}

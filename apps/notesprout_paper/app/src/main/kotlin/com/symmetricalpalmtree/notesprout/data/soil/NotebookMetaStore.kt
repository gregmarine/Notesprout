package com.symmetricalpalmtree.notesprout.data.soil

import androidx.sqlite.db.SupportSQLiteDatabase

/** Raw-SQL access to the single `notebook_meta` row (it is not a Room entity). Callers are on IO. */
object NotebookMetaStore {

    fun write(db: SupportSQLiteDatabase, meta: NotebookMeta) {
        db.execSQL(SoilSchema.CREATE_META)
        db.execSQL(
            "INSERT OR REPLACE INTO notebook_meta (id, json) VALUES (0, ?)",
            arrayOf(meta.toJson()),
        )
    }

    fun read(db: SupportSQLiteDatabase): NotebookMeta? = try {
        db.query("SELECT json FROM notebook_meta WHERE id = 0").use { c ->
            if (!c.moveToFirst()) null else NotebookMeta.fromJson(c.getString(0))
        }
    } catch (_: Exception) {
        null
    }
}

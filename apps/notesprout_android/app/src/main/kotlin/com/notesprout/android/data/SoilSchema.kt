package com.notesprout.android.data

/**
 * Single source of truth for the `.soil` `notebook` table schema.
 *
 * The table is bootstrapped by raw SQL in **two** hand-written sites (`MainActivity.createNotebook`
 * for the template/encrypted path, `NotebookFactory.createBlankNotebook` for the plain path) and
 * upgraded on existing files by `SoilDatabase.MIGRATION_3_4`. All three must produce a schema Room
 * accepts, or on-open validation crashes — so the column list lives here once and every site refers
 * to it.
 *
 * **Data-model-optimization Phase 1:** the opaque `data` JSON is being replaced by typed columns +
 * a binary `blob` (stroke geometry via [com.notesprout.android.core.StrokeCodec]). Every column added
 * in v4 is nullable — the table is intentionally wide and sparse (a NULL costs ~1 byte in SQLite;
 * trailing NULLs are free). The legacy `data` / `boundingBox` columns are kept NOT NULL (written as
 * `""` on new columnar rows) for **lazy coexistence**: format-agnostic readers fall back to them, and
 * a later phase drops them once every row is converted.
 */
object SoilSchema {

    /** Full v4 (widened, columnar) CREATE TABLE for a fresh `.soil`. */
    val CREATE_NOTEBOOK_TABLE = """
        CREATE TABLE IF NOT EXISTS notebook (
            id          TEXT    NOT NULL PRIMARY KEY,
            parentId    TEXT    NOT NULL,
            boundingBox TEXT    NOT NULL,
            "order"     INTEGER NOT NULL DEFAULT 0,
            createdAt   INTEGER NOT NULL,
            updatedAt   INTEGER NOT NULL,
            deletedAt   INTEGER,
            type        TEXT    NOT NULL,
            data        TEXT    NOT NULL,
            x           REAL,
            y           REAL,
            width       REAL,
            height      REAL,
            "text"      TEXT,
            color       TEXT,
            strokeWidth REAL,
            refId       TEXT,
            level       INTEGER,
            lineStyle   TEXT,
            orientation TEXT,
            dotSpacing  REAL,
            shapeType   TEXT,
            centerX     REAL,
            centerY     REAL,
            rotationDeg REAL,
            pointCount  INTEGER,
            contentW    REAL,
            contentH    REAL,
            linkTarget  TEXT,
            chrome      TEXT,
            flags       INTEGER,
            blob        BLOB
        )
    """.trimIndent()

    val CREATE_NOTEBOOK_INDEX = """
        CREATE INDEX IF NOT EXISTS idx_notebook_parent_order
            ON notebook(parentId, "order", deletedAt)
    """.trimIndent()

    /**
     * Columns added to an existing v3 `notebook` table by `MIGRATION_3_4` (name → SQLite type).
     * Must exactly match the v4 columns declared in [CREATE_NOTEBOOK_TABLE] and the widened
     * [NotebookObject] entity.
     */
    val ADDED_COLUMNS_V4: List<Pair<String, String>> = listOf(
        "x" to "REAL", "y" to "REAL", "width" to "REAL", "height" to "REAL",
        "text" to "TEXT", "color" to "TEXT", "strokeWidth" to "REAL", "refId" to "TEXT",
        "level" to "INTEGER", "lineStyle" to "TEXT", "orientation" to "TEXT", "dotSpacing" to "REAL",
        "shapeType" to "TEXT", "centerX" to "REAL", "centerY" to "REAL", "rotationDeg" to "REAL",
        "pointCount" to "INTEGER", "contentW" to "REAL", "contentH" to "REAL",
        "linkTarget" to "TEXT", "chrome" to "TEXT", "flags" to "INTEGER", "blob" to "BLOB",
    )
}

package com.symmetricalpalmtree.notesprout.data.soil

/**
 * The greenfield `.soil` schema — one universal `notebook` table plus the single-row `notebook_meta`.
 *
 * Room owns the `notebook` table (it is generated from [SoilObjectEntity]); the DDL below is the
 * *contract* those entity annotations must produce, kept here so `docs/data.md`, the raw-SQL paths
 * and any future external reader have one authoritative statement. `notebook_meta` is created by
 * raw SQL in the Room open callback (it is not an entity).
 *
 * `"order"` is an SQL keyword — always double-quote it in SQL and backtick it in ContentValues.
 */
object SoilSchema {

    /** `PRAGMA user_version` of a Paper `.soil`. Bump only with a migration. */
    const val SOIL_VERSION = 1

    const val TABLE = "notebook"
    const val META_TABLE = "notebook_meta"

    // Row types
    const val TYPE_NOTEBOOK = "notebook"
    const val TYPE_PAGE = "page"
    const val TYPE_TEMPLATE = "template"
    const val TYPE_STROKE = "stroke"

    /** The notebook meta row's `parentId` (it is the root). */
    const val ROOT_PARENT = ""

    const val CREATE_NOTEBOOK = """
        CREATE TABLE IF NOT EXISTS notebook (
            id          TEXT    NOT NULL PRIMARY KEY,
            parentId    TEXT    NOT NULL,
            type        TEXT    NOT NULL,
            "order"     INTEGER NOT NULL DEFAULT 0,
            createdAt   INTEGER NOT NULL,
            updatedAt   INTEGER NOT NULL,
            deletedAt   INTEGER,
            text        TEXT,
            refId       TEXT,
            width       REAL,
            height      REAL,
            color       TEXT,
            strokeWidth REAL,
            style       TEXT,
            flags       INTEGER,
            blob        BLOB
        )
    """

    const val CREATE_NOTEBOOK_INDEX =
        """CREATE INDEX IF NOT EXISTS idx_notebook_parent_order ON notebook(parentId, "order", deletedAt)"""

    const val CREATE_META =
        "CREATE TABLE IF NOT EXISTS notebook_meta (id INTEGER PRIMARY KEY CHECK (id = 0), json TEXT NOT NULL)"
}

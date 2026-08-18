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

    /**
     * `PRAGMA user_version` of a Paper `.soil`. Bump only with a migration. Arc 4 / H1 added the
     * `x`/`y` columns **without** a bump or a migration (phase-start decision: fresh schema, old test
     * notebooks abandoned) — a pre-H1 file fails Room's identity-hash check on open and the reason
     * surfaces through the notebook screen's open-failed toast; nothing is ever deleted or rewritten.
     */
    const val SOIL_VERSION = 1

    const val TABLE = "notebook"
    const val META_TABLE = "notebook_meta"

    // Row types
    const val TYPE_NOTEBOOK = "notebook"
    const val TYPE_PAGE = "page"
    const val TYPE_TEMPLATE = "template"
    const val TYPE_STROKE = "stroke"
    /** A content object (arc 4): `style` = provider identity `<pkg>:<typeId>`, `text` = opaque payload,
     *  `x`/`y`/`width`/`height` = bounds in page px, `"order"` = z-order among the page's objects. */
    const val TYPE_OBJECT = "object"

    /** The notebook meta row's `parentId` (it is the root). */
    const val ROOT_PARENT = ""

    /**
     * Label written to the index `templateKind` column for a notebook created with no template. A
     * templated notebook carries the extension-namespaced identity `"<extension package>:<template id>"`
     * there and in its `template` row's `text` (v0 notebooks keep `LINED`/`DOTTED`/`GRID`). Informational
     * only — nothing reads these yet.
     */
    const val TEMPLATE_BLANK = "BLANK"

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
            x           REAL,
            y           REAL,
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

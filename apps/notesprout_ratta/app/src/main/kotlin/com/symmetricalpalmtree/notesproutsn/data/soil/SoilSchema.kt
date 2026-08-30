package com.symmetricalpalmtree.notesproutsn.data.soil

/**
 * The `.soil` schema — one universal `notebook` table plus the single-row `notebook_meta`.
 * **Byte-for-byte format-compatible with Paper's** (`apps/notesprout_paper/docs/data.md`): same
 * columns in the same order, same index name, same `user_version` — Room's identity hash must
 * match or a Paper-created file fails validation on open (and vice versa). SN writes only the
 * row types notebook/page/template/stroke; the `x`/`y` columns and the object/link types exist
 * in the shared table shape but SN never creates such rows.
 *
 * Room owns the `notebook` table (generated from [SoilObjectEntity]); the DDL below is the
 * *contract* those entity annotations must produce. `notebook_meta` is created by raw SQL in the
 * Room open callback (it is not an entity).
 *
 * `"order"` is an SQL keyword — always double-quote it in SQL and backtick it in Room.
 */
object SoilSchema {

    /** `PRAGMA user_version` of a `.soil`. Bump only with a migration (family-wide decision). */
    const val SOIL_VERSION = 1

    const val TABLE = "notebook"
    const val META_TABLE = "notebook_meta"

    // Row types SN writes
    const val TYPE_NOTEBOOK = "notebook"
    const val TYPE_PAGE = "page"
    const val TYPE_TEMPLATE = "template"
    const val TYPE_STROKE = "stroke"

    /**
     * Heading object (arc 3) — SN's one additive row type on the family shape, og's model:
     * `parentId` = page id · `text` = hash-prefixed markdown (`"## Title"`), always non-null ·
     * `flags` = level 1–6 (**authoritative** — the prefix is only ever written from it) ·
     * `x`/`y`/`width`/`height` = bounds in page px · `"order"` = z-order among the page's
     * headings. No version bump, no migration; Paper ignores the rows (the proven-safe additive
     * pattern — the mirror of SN ignoring Paper's `object` rows in R6).
     */
    const val TYPE_HEADING = "heading"

    /**
     * Link object (arc 6) — the second additive row type, Paper's L1 shape: `parentId` = page id ·
     * `text` = the v1 payload (`LinkPayload` — Paper's exact grammar, so link rows stay
     * family-compatible) · `x`/`y`/`width`/`height` = union bounds of the wrapped content plus the
     * underline clearance, in page px · `"order"` = z-order among the page's links · `style` and
     * `flags` **null** (Paper wrote its provider id into `style` — read leniently, never required;
     * chrome is parsed from the payload, never cached in `flags`). The wrapped children are the
     * page's former stroke/heading rows with `parentId` flipped to the link id (re-parent, not
     * copy). No version bump, no migration.
     */
    const val TYPE_LINK = "link"

    /**
     * Document object (arc 19) — the third additive row type, og's model with one deviation:
     * the watermark rides `flags` instead of og's `srcUpdatedAt` column (og's table had no spare
     * 64-bit slot; this family's `flags` is a nullable SQLite INTEGER, which is 64-bit — see
     * [SoilObjectEntity.flags]). No version bump, no migration; Paper ignores the rows.
     *
     * `parentId` = the page id (a **page document** — at most one live row per page) or the
     * notebook root row's id (the **notebook document** — the merged final draft, at most one
     * live row per notebook, og's shape) · `text` = the markdown, always non-blank ·
     * `flags` = **the source watermark**: the page's (or notebook's) max content `updatedAt` at
     * the last seed/refresh, epoch millis; NULL = authored by hand, never drafted from the page.
     * The watermark moves in exactly two places — the seed and a "Bring in" refresh — which is
     * what makes "page has changed since this draft" meaningful. Everything else null;
     * `"order"` = 0 (one row per parent, nothing to order).
     *
     * **Blank means absent** (og's rule): a document with no text is never inserted and a save of
     * blank text deletes the row — which is what lets seed-once work with no "has been seeded"
     * flag. `document` rows are **excluded** from every content-staleness whitelist (a document
     * is a product of the page, not content on it) but a page delete / copy / purge cascade
     * carries them like any child row — a document travels with its page.
     */
    const val TYPE_DOCUMENT = "document"

    /** The notebook meta row's `parentId` (it is the root). */
    const val ROOT_PARENT = ""

    /** Index `templateKind` label for a notebook created with no template. Built-in templates use
     *  the legacy family labels `LINED` / `DOTTED` / `GRID`. Informational — nothing reads these. */
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

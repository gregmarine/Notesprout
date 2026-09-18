package com.symmetricalpalmtree.notesproutsn.data.soil

/**
 * The `.soil` schema — one universal `notebook` table plus the single-row `notebook_meta`.
 * **Byte-for-byte format-compatible with Paper's** (`apps/notesprout_paper/docs/data.md`): same
 * columns in the same order, same index name, same `user_version` — Room's identity hash must
 * match or a Paper-created file fails validation on open (and vice versa). SN writes only the
 * row types notebook/page/template/stroke plus its own additive object types (heading, link,
 * document, arc 28's text / shape / sticky_note, and arc 43's sketch — two rows since arc 45 / G2,
 * [TYPE_SKETCH_GRAPHITE] and [TYPE_SKETCH_INK]); Paper's `object` rows are ignored.
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

    /**
     * Text object (arc 28 / H1) — the fourth additive row type, og's on-page Markdown text:
     * `parentId` = page id · `text` = raw Markdown source, **always non-blank** (a blank text never
     * exists — the heading rule) · `x`/`y` = the box's top-left in page px, authored ·
     * `width`/`height` = the laid-out box, **derived** (re-measured on every page load, like a
     * heading's) · `"order"` = z-order among the page's text rows · everything else null. No
     * version bump, no migration; Paper ignores the rows. `OBJECTS_PLAN.md` D1.
     */
    const val TYPE_TEXT = "text"

    /**
     * Shape object (arc 28 / H1) — the fifth additive row type, six hand-placed outlines:
     * `parentId` = page id · `style` = the type name (`RECTANGLE` `ELLIPSE` `TRIANGLE` `ARROW`
     * `LINE` `STAR`; unknown → row dropped) · `x`/`y` = the **centre** in page px (the one row kind
     * whose `x`/`y` is not a top-left) · `width`/`height` = the un-rotated local extents in page px
     * · `strokeWidth` = the outline width in **px** · `flags` = `ShapeFlags.pack(aspectLocked,
     * pointCount, rotationTenths)` (bit 0 · bits 8–15 · bits 16–31) · `"order"` = z-order among the
     * page's shape rows · `text`/`color`/`blob`/`refId` null. Stroke-only, no fill. No version bump,
     * no migration. `OBJECTS_PLAN.md` D3.
     */
    const val TYPE_SHAPE = "shape"

    /**
     * Sticky note (arc 28 / H1) — the sixth additive row type, og's row name verbatim:
     * `parentId` = page id · `x`/`y`/`width`/`height` = the **icon box** in page px (72 dp square
     * at creation) · `flags` = `StickyFlags.pack(contentW, contentH)` (bits 0–19 · bits 20–39, the
     * note's content size in px) · `"order"` = z-order among the page's sticky rows · everything
     * else null. The note's content is **`stroke` rows parented to the sticky id, in the note's
     * LOCAL space** (`(0,0)` = the content's top-left) — a second grandchild branch beside a link's.
     * Sticky content never draws on the page. No version bump, no migration. `OBJECTS_PLAN.md` D2.
     */
    const val TYPE_STICKY = "sticky_note"

    /**
     * Sketch, the **graphite** raster (arc 43 / K3, split in two at arc 45 / G2) — the seventh
     * additive row *kind*, and the first whose payload is a **picture of the page rather than a
     * mark on it**: `parentId` = page id · `blob` = a page-sized **lossless WebP with alpha** (RGBA,
     * transparent where empty, **exactly** the page's `width` × `height`) · `"order"` =
     * [SKETCH_ORDER] · everything else null. No version bump, no migration; Paper ignores the rows
     * (the proven-safe additive pattern). `INK_PLAN.md` § Decisions / Derived.
     *
     * **A sketch is two rows since G2** — this one, which the pencil bakes into and the rubbing
     * eraser rubs, and [TYPE_SKETCH_INK], which the gel pen and "Bring in ink" bake into and
     * nothing ever erases (the user's decision 1). Every place the sketch is *seen* flattens the
     * two with a darken composite, which is order-independent, so the file has no top and bottom to
     * record: the row name is the whole of the layering. The seventh additive row type became two,
     * and the format's version did not move, because both are additive exactly as the one was.
     *
     * **One live row per page per layer**, each **minted on its own first save, never on open**: a
     * page nobody has drawn on has no row at all, and a page with only ink has no graphite row.
     * Later saves rewrite that row in place ([SoilDao.setBlob], `createdAt` kept) — a row per save
     * would make a notebook's size a function of how long the person worked rather than of how
     * much they drew.
     *
     * **Blank means absent, per row** (the `document` row's rule, applied to pixels): an
     * all-transparent raster is not stored, and the wire form for "clear this layer" is an empty
     * byte array, which soft-deletes that layer's live row and leaves the other alone. Both rows
     * are soft-deleted with their page and ride copy / cut / paste / delete / undo like any other
     * child ([SoilDao.liveDescendantIds]) — but **not** Erase page ([SoilDao.liveErasableIds],
     * decision 11 of arc 43: Erase page is ink only), and both are invisible to [SoilDao.childrenOf]
     * (a page-sized blob must never ride an untyped page read).
     */
    const val TYPE_SKETCH_GRAPHITE = "sketch_graphite"

    /**
     * Sketch, the **ink** raster (arc 45 / G2) — [TYPE_SKETCH_GRAPHITE]'s twin in every respect but
     * what writes it: the gel pen and "Bring in ink" bake here, and **nothing ever erases it**
     * (decision 1 — "in the real world, ink is more permanent than pencil"). Same columns, same
     * `"order"`, same blank-means-absent rule, same lossless WebP at exactly the page's size.
     */
    const val TYPE_SKETCH_INK = "sketch_ink"

    /**
     * The **dead** sketch row name — arcs 43–44's single un-layered PNG row, replaced by the two
     * above at arc 45 / G2 under the user's decision 4: **no legacy.** There is no migration, no
     * PNG sniffing and no warning; this name exists so that a leftover row on a device that ran an
     * older build is **excluded from [SoilDao.childrenOf]** and therefore never surfaces as a child
     * of its page (an untyped page read would hand a megabyte of unreadable pixels to a caller that
     * has no idea what they are).
     *
     * Beyond that one exclusion it is ignored entirely: never read, never written, never migrated,
     * never counted, and not carried by [SoilDao.liveDescendantIds] — a row nothing can read is a
     * row nothing should copy. The close purge takes it with its page like any other soft-deleted
     * row; a live one simply sits there, costing the bytes it already cost.
     */
    const val TYPE_SKETCH_DEAD = "sketch"

    /**
     * A sketch row's `"order"` — **-1, outside every z-order space in the file**, and the same
     * number for both rasters. Every other `"order"` in this format is a position among siblings of
     * the same type, dense from 0; a sketch raster is not one of the marks on the page, it is what
     * all of them came to, so it is given a number no ordering ever reaches rather than a place in
     * one. The two rows sharing it says the same thing the flatten does: neither is above the other.
     */
    const val SKETCH_ORDER = -1

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

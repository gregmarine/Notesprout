# The `.soil` File Format — Complete Specification

> **Audience:** an engineer (human or AI) implementing a compatible file container for a *different*
> application in the Sprout family — e.g. **Paintsprout** (paint/art documents). This document is
> self-contained: it assumes no knowledge of Notesprout's codebase.
>
> **Compatibility goal:** not byte-for-byte 1:1. A Paintsprout document will hold different object
> types than a Notesprout notebook. What must match is the **container contract** — the storage
> topology, the universal row schema, the encryption model, the self-describing metadata table, and
> the sidecar/portability discipline. Part XI tells you exactly which parts are invariant and which
> you are expected to replace.
>
> Everything below describes Notesprout as built on the `sprout` branch (schema `.soil` v4, global
> index v8, global encryption phases 0–5). Where the format has legacy shapes still readable in the
> wild, that is called out explicitly — a greenfield app should implement only the current shape.

---

## Table of Contents

- [Part I — The Invariants](#part-i--the-invariants)
- [Part II — Storage Topology](#part-ii--storage-topology)
- [Part III — The Universal Object Model](#part-iii--the-universal-object-model)
- [Part IV — Object Type Catalog](#part-iv--object-type-catalog)
- [Part V — Binary Encodings](#part-v--binary-encodings)
- [Part VI — Schema Versions & Migration](#part-vi--schema-versions--migration)
- [Part VII — Encryption](#part-vii--encryption)
- [Part VIII — The Global Index](#part-viii--the-global-index)
- [Part IX — Portability: Export & Import](#part-ix--portability-export--import)
- [Part X — Durability: WAL, Sidecars, Backup](#part-x--durability-wal-sidecars-backup)
- [Part XI — Adapting This Format for Paintsprout](#part-xi--adapting-this-format-for-paintsprout)
- [Appendix A — Quick Reference Tables](#appendix-a--quick-reference-tables)
- [Appendix B — Known Divergences & Open Questions](#appendix-b--known-divergences--open-questions)

---

# Part I — The Invariants

These twelve rules *are* the format. An application that honors all of them is compatible with the
Sprout container model even if it stores entirely different content.

1. **One document = one SQLite database file** with an app-specific extension (`.soil` for
   Notesprout). No zip, no wrapper, no sidecar manifest, no directory bundle.

2. **The file is self-describing.** A single-row `notebook_meta` table carries the document's
   identity, name, ancestry, and encryption state, so the file can be imported into a fresh install
   with no external context. (Keep this table name across apps — it belongs to the container, not to
   Notesprout.)

3. **Everything is an object.** All content lives as rows in **one** table with a universal row
   shape. There is no per-type table. The `type` column is a plain string discriminator; adding a new
   object type requires **zero schema migration**.

3a. **The object table's *name* declares the content type.** Notesprout's is `notebook`;
   Paintsprout's would be `sketchbook`. A reader identifies a file's contents from `sqlite_master`
   alone. One file may carry several such tables — and **no writer may drop or rewrite a table it
   doesn't own.** See [Appendix B](#content-typing-the-table-name-is-the-discriminator).

4. **The universal row carries:** `id`, `parentId`, `type`, `order`, `createdAt`, `updatedAt`,
   `deletedAt`, plus payload. Nothing else is mandatory.

5. **Stable UUIDs everywhere.** Every object id is a UUIDv4 string, assigned at creation, never
   reassigned. Ids are stable across export/import, copy is the only operation that mints new ones.

6. **Soft deletes only.** Deletion sets `deletedAt` (epoch ms). Hard deletion happens only in a
   deliberate compaction pass.

7. **Payload is columnar + binary, never opaque JSON.** Typed nullable columns hold scalars; a single
   `blob BLOB` column holds binary geometry/image bytes. JSON on the object path is legacy only.

8. **Composites are relational.** An object that contains other objects is a parent row plus child
   rows (`child.parentId = composite.id`), not a nested serialized document.

9. **Filenames are UUIDs in a flat directory.** Structure (folders, hierarchy, ordering) lives
   exclusively in a separate global index database — never derived from the filesystem.

10. **Encryption is whole-file SQLCipher with stock defaults.** The key is the passphrase, UTF-8
    encoded. No custom `kdf_iter`, no custom page size. A stock `sqlcipher` CLI must be able to open
    the file.

11. **No stray files.** A file browser shows only document files. WAL/SHM/journal sidecars are
    checkpointed and removed on clean close.

12. **No content leaks outside the encrypted file.** Nothing derived from document content —
    thumbnails, recognized text, caches — may be written to any unencrypted store.

---

# Part II — Storage Topology

## Layout on disk

```
<app external files dir>/
├── notesprout.db                  ← global index (SQLCipher-encrypted)
├── notesprout.db-wal              ← present while the index is open (normal WAL behavior)
├── notesprout.db-shm
└── Garden/                        ← flat blob directory, no subdirectories, ever
    ├── 3f2a1b8c-....soil          ← one notebook, filename = its UUID
    ├── 8d1e4f77-....soil
    └── …
```

On Android this is `context.getExternalFilesDir(null)` — app-private, no runtime permissions
required, visible to the user in a file manager, and removed on uninstall.

## The two-database split

| | Global index (`notesprout.db`) | Document (`<uuid>.soil`) |
|---|---|---|
| Count | Exactly one | One per document |
| Holds | Folder tree, document rows, names, covers, lists/pins, app-wide tables | All document content |
| Lifetime | Open for the whole app lifetime | Open only while the document is open |
| Encryption | SQLCipher under the **global** key | SQLCipher under global **or** per-document key |
| Rebuildable? | No — it is the only source of hierarchy | Yes, it is the source of truth for content |

**The critical rule:** the `Garden/` directory is *blob storage*. It has no structure. Folder
ancestry, display names, ordering, and pinning are index concerns. This is what makes a document file
portable — it carries no assumptions about where it lives.

## Path derivation

Exactly one function in the entire codebase constructs a document path:

```kotlin
fun soilFile(context: Context, notebookId: String): File {
    val garden = File(context.getExternalFilesDir(null)!!, "Garden")
    garden.mkdirs()
    return File(garden, "$notebookId.soil")
}
```

Enforce this. Scattered path construction is how sidecar leaks and orphaned files start.

## Tables inside one `.soil`

| Table | Rows | Purpose |
|---|---|---|
| `notebook` | many | **The** object table. All content. |
| `notebook_meta` | exactly 1 | Self-describing identity (Part IX) |
| `undo_redo_state` | 0 or 1 | Persisted undo stack for encrypted documents (Part X) |

Single-row tables are enforced structurally:

```sql
CREATE TABLE IF NOT EXISTS notebook_meta
    (id INTEGER PRIMARY KEY CHECK (id = 0), json TEXT NOT NULL);
```

The `CHECK (id = 0)` is deliberate — it makes "there is exactly one" a database invariant rather than
a convention.

---

# Part III — The Universal Object Model

## Hierarchy

```
Notebook (meta row, parentId = "")
└── Page                      (parentId = notebook row id)
    └── Layer                 (parentId = page id)
        ├── Stroke            (parentId = layer id)
        ├── Heading           (parentId = layer id)
        │   └── Stroke        (parentId = heading id)   ← fallback only
        ├── Text
        ├── Line
        ├── Shape
        ├── Link              (parentId = layer id)
        │   ├── Stroke, Heading, Text, Line, Shape      ← children, page-absolute coords
        └── StickyNote        (parentId = layer id)
            └── Stroke, Heading, Text, Line, Shape      ← children, LOCAL coords
```

Depth is bounded in practice at 2 levels below a layer (composite → nested heading/text → strokes).
Nothing in the schema enforces that bound; readers simply don't need to go deeper.

A page also owns a `page_text` row (`parentId = pageId`) holding cached recognized text — an example
of adding an entirely new object type with zero migration.

## The table

```sql
CREATE TABLE IF NOT EXISTS notebook (
    -- ── Universal row (v1 core, stable) ──────────────────────────────
    id          TEXT    NOT NULL PRIMARY KEY,   -- UUIDv4
    parentId    TEXT    NOT NULL,               -- "" for the root meta row
    boundingBox TEXT    NOT NULL,               -- legacy JSON; "" on columnar content rows
    "order"     INTEGER NOT NULL DEFAULT 0,     -- sort order among siblings
    createdAt   INTEGER NOT NULL,               -- epoch ms
    updatedAt   INTEGER NOT NULL,               -- epoch ms
    deletedAt   INTEGER,                        -- NULL = alive; epoch ms = soft-deleted
    type        TEXT    NOT NULL,               -- string discriminator
    data        TEXT    NOT NULL,               -- legacy JSON payload; "" on columnar rows

    -- ── Columnar payload (v4) — all nullable, wide + sparse ──────────
    x           REAL,   y           REAL,
    width       REAL,   height      REAL,
    "text"      TEXT,
    color       TEXT,
    strokeWidth REAL,
    refId       TEXT,
    level       INTEGER,
    lineStyle   TEXT,
    orientation TEXT,
    dotSpacing  REAL,
    shapeType   TEXT,
    centerX     REAL,   centerY     REAL,
    rotationDeg REAL,
    pointCount  INTEGER,
    contentW    REAL,   contentH    REAL,
    linkTarget  TEXT,
    chrome      TEXT,
    flags       INTEGER,
    blob        BLOB
);

CREATE INDEX IF NOT EXISTS idx_notebook_parent_order
    ON notebook(parentId, "order", deletedAt);
```

### Why one wide sparse table

This looks wasteful and is not. In SQLite a NULL column costs ~1 byte in the record header, and
**trailing NULLs cost nothing at all** — the record is simply truncated. A stroke row populates
`color`, `strokeWidth`, and `blob`; every other typed column is absent from the serialized record.

The payoff is that a new object type needs no `ALTER TABLE`, no join, no per-type reader
registration. `type` is just a string. This is the single most important structural decision in the
format and the one most worth copying.

### Column semantics

Columns are *shared across types* by role, not owned by a type. The same `text` column is a heading's
recognized text, a text object's content, a template's name, a layer's label, and the notebook's
title. Read the type first, then interpret.

| Column | Used by | Meaning |
|---|---|---|
| `x`, `y`, `width`, `height` | most content types | Bounding box, page-absolute px. `x`/`y` are the top-left; `width`/`height` are extents (**not** right/bottom). |
| `text` | heading, text, page_text, template, layer, notebook | See above — role varies by type |
| `color` | stroke | `#RRGGBB` or `#AARRGGBB` |
| `strokeWidth` | stroke (px), line (dp), shape (dp) | Note the unit difference — see "Density" below |
| `refId` | page → template id · notebook → lastOpenedPage · link → target id | A foreign reference within the same file |
| `level` | heading | 1–3 |
| `lineStyle`, `orientation`, `dotSpacing` | line | Enum names as strings |
| `shapeType`, `centerX`, `centerY`, `rotationDeg`, `pointCount` | shape | Oriented-box params |
| `contentW`, `contentH` | sticky_note | Content-window pixel size (second coordinate space) |
| `linkTarget`, `chrome` | link | Target JSON + chrome enum name |
| `flags` | layer, shape | Bitfield — see per-type sections |
| `blob` | stroke, template, legacy composites | Binary payload |

### The `boundingBox` and `data` columns

Both are `NOT NULL` legacy columns retained for **lazy coexistence**:

- A **columnar row** writes `data = ""` and puts everything in typed columns.
- A **legacy row** (written by an older build) has JSON in `data` and/or `boundingBox`.
- Every reader is **format-agnostic**: prefer the typed columns, fall back to JSON when they're null.
- Rows convert on their next write; a background compactor sweeps the rest.

Two exceptions where `boundingBox` is still *actively* used on columnar rows: `page` and `template`
keep their dimensions there as `{"x":0,"y":0,"width":W,"height":H}`, because raw-SQL page-size
readers depend on it and moving it bought nothing.

**A greenfield implementation should omit `data` and `boundingBox` entirely.** They exist only to
avoid rewriting a user's 44 MB notebook during an upgrade.

### Density independence

Stroke widths are stored in **px**; line and shape widths are stored in **dp** (density-independent
pixels) and multiplied by the display density at load. This is because strokes are captured from
hardware at physical resolution while lines/shapes are authored at logical sizes. A document authored
on a 300 DPI e-ink tablet must render correctly on a 160 DPI phone.

If your app has any concept of physical vs. logical size, decide this per-type and **document it in
the column comment**, because it is invisible at the schema level and silently wrong when mixed up.

### Ordering

`"order"` is an integer sort key among siblings. It is a **SQLite reserved word** — every hand-written
SQL statement must double-quote it (`"order"`), and `ContentValues` keys must backtick it
(`` `order` ``). This bites once per codebase; quote it everywhere from day one.

---

# Part IV — Object Type Catalog

Type discriminators are lowercase snake_case strings. Notesprout's set:

```
Structural:  "notebook"  "page"  "layer"  "template"
Content:     "stroke"  "heading"  "text"  "line"  "shape"  "link"  "sticky_note"
Derived:     "page_text"
```

---

## `notebook` — the document meta row

Exactly one per file. `parentId = ""`. Its `id` is the parent of every page.

| Field | Column | Notes |
|---|---|---|
| title | `text` | Display name (also mirrored in `notebook_meta`) |
| lastOpenedPage | `refId` | Page id to restore on open; nullable |
| rtrEnabled | `flags` bit 0 | Per-document feature toggle, travels with the file |

Legacy JSON shape (`data`): `{"title":…,"last_opened_page":…,"rtr_enabled":…}`.

**Design note:** per-document feature flags belong here, not in app preferences, so they travel with
the file on export.

---

## `page` — a fixed-size page

`parentId` = notebook row id.

| Field | Column | Notes |
|---|---|---|
| width, height | `boundingBox` JSON `{0,0,w,h}` | Kept in `boundingBox` by design |
| template | `refId` | Id of a `template` row in this file; `""` = blank |

Pages are **fixed screen-size**, never infinite scroll — a core philosophy constraint, not a
technical one. Page order is the `"order"` column.

---

## `layer` — a drawing layer

`parentId` = page id. Every page has at least one content layer. A base layer holds the template and
is locked.

| Field | Column | Notes |
|---|---|---|
| label | `text` | e.g. `"Content"` |
| isLocked | `flags` bit 0 (`LAYER_FLAG_LOCKED = 1`) | |
| isVisible | `flags` bit 1 (`LAYER_FLAG_VISIBLE = 2`) | |

Default for a new content layer: `flags = 2` (visible, unlocked).

Layers are where content rows attach. **All page-level content queries are
`WHERE parentId = <layerId> AND deletedAt IS NULL`** — this is what structurally isolates composite
children from page rendering (a composite's children are parented to the composite, not the layer, so
they never appear in a page query).

---

## `template` — an embedded page background image

`parentId` = notebook row id. A template is **copied into** the document when applied, never
referenced externally. This keeps the file self-contained and portable.

| Field | Column | Notes |
|---|---|---|
| name | `text` | |
| width, height | `boundingBox` JSON | Pixel dimensions |
| image | `blob` | Raw **WEBP q100** bytes |

**Image encoding rule — WEBP q100, lossy.** On transparent-alpha ink this measured ~47% smaller than
PNG and is visually lossless. Android's `WEBP_LOSSLESS` encoder is pathological here — it produced
files 2–6× *larger* than PNG. Decoding is format-agnostic (the decoder reads the header), so legacy
PNG and lossless-WEBP blobs coexist and get re-encoded in place by the compactor.

**Bounded decode rule:** never call a raw `decodeByteArray` on document-sourced image bytes. Route
through a sampled decoder with a target size and a `MAX_DIMENSION = 4096` fallback. A hostile or
merely large embedded image is an OOM on a low-memory e-ink device.

---

## `stroke` — a handwriting/ink stroke

The dominant row type by volume — a real notebook holds ~11,000 of them, historically ~99% of file
bytes. Everything about this type is tuned for that.

| Field | Column | Notes |
|---|---|---|
| points | `blob` | Binary, see Part V. **Geometry only.** |
| color | `color` | `#RRGGBB`/`#AARRGGBB` |
| strokeWidth | `strokeWidth` | px |
| bounding box | *(not stored)* | Recomputed from points at load |

The bounding box is deliberately **not persisted** — it's an O(n) recompute at load that pays for
itself immediately (it's used as a fast AABB rejection test during eraser hit-testing) and would
otherwise be a denormalized value that can drift.

Colour and width are row columns, not part of the blob, so the blob is pure geometry and a colour
change is a scalar update.

**Legacy shape** (`data` JSON, still readable): `{"color":…,"strokeWidth":…,"points":[{"x":…,"y":…,
"pressure":…,"tilt":…}]}`. `pressure`/`tilt` are nullable and omitted when absent. A per-point `ts`
field existed, was never read, cost ~40% of stroke JSON, and is stripped on any re-save.

---

## `heading` — a recognized heading

Two states, and the distinction drives the storage shape:

| State | `text` | Children |
|---|---|---|
| **Recognized** (happy path) | recognized string | none — a single bare row |
| **Fallback** (recognition failed) | `NULL` | stroke child rows — the ink *is* the visual |

| Field | Column |
|---|---|
| recognizedText | `text` (NULL = fallback) |
| level | `level` (1–3) |
| bounding box | `x`,`y`,`width`,`height` |
| fallback strokes | child rows, `parentId = heading.id`, page-absolute coords |

Once recognized, the original strokes are **dropped** — headings are not revertible to ink. The
recognized-path row therefore costs exactly one row, making this behavior- and perf-neutral versus a
non-composite type.

A fallback→recognized transition leaves the old stroke children as harmless orphans (excluded from
reads by the parent's short-circuit; reclaimed by the compactor's orphan sweep).

---

## `text` — a text object

Same two-state structure as `heading`, without `level`. Markdown-capable content.

| Field | Column |
|---|---|
| text | `text` |
| bounding box | `x`,`y`,`width`,`height` |
| fallback strokes | child rows |

---

## `line` — a straight rule

| Field | Column | Values |
|---|---|---|
| bounding box | `x`,`y`,`width`,`height` | |
| style | `lineStyle` | `SOLID` · `DASHED` · `DOTTED` |
| orientation | `orientation` | `HORIZONTAL` · `VERTICAL` |
| strokeWidth | `strokeWidth` | **dp** |
| dotSpacing | `dotSpacing` | **dp** |

Endpoints are *derived*, not stored: a `HORIZONTAL` line runs `(left, centerY) → (right, centerY)`;
a `VERTICAL` line runs `(centerX, top) → (centerX, bottom)`. Storing only the box keeps the line
consistent with lasso/resize operations that manipulate boxes.

---

## `shape` — a geometric shape

The only type with an **oriented** box rather than an AABB.

| Field | Column | Notes |
|---|---|---|
| type | `shapeType` | Enum name |
| centerX, centerY | `centerX`, `centerY` | Absolute page coords |
| width, height | `width`, `height` | **Un-rotated local extents** (the oriented box) |
| rotationDeg | `rotationDeg` | |
| strokeWidth | `strokeWidth` | dp |
| aspectLocked | `flags` bit 0 | |
| pointCount | `pointCount` | STAR only; default 5 |
| AABB | *(not stored)* | Recomputed from oriented box + rotation |

`x`/`y` stay NULL — the axis-aligned bounding box is fully derivable and storing it would be a
drift-prone denormalization.

`ShapeType`: `RECTANGLE` `ELLIPSE` `TRIANGLE` `DIAMOND` `TRAPEZOID` `PENTAGON` `HEXAGON` `STAR`
`ARCH` `LINE` `ARROW`. Square = `RECTANGLE` with `aspectLocked` and `w == h`; circle = `ELLIPSE`
likewise. **Do not add square/circle as separate types** — the constraint is the distinction.

---

## `link` — a navigable region wrapping content

The first true composite. A link captures any mix of content at creation time (as fresh-UUID copies)
and makes the union region tappable.

| Field | Column | Notes |
|---|---|---|
| union bounding box | `x`,`y`,`width`,`height` | |
| target | `linkTarget` | JSON, polymorphic — see below |
| chrome | `chrome` | `NONE` · `UNDERLINE` · `DOTTED_CHEVRON` |
| content | **child rows** | `parentId = link.id`, **page-absolute** coords |

`linkTarget` is a serialized sealed hierarchy:

```json
{"type":"CurrentNotebookPage","pageId":"…"}
{"type":"OtherNotebook","notebookId":"…"}
{"type":"OtherNotebookPage","notebookId":"…","pageId":"…"}
```

> ⚠️ **Landmine.** The discriminator kotlinx.serialization emits for a sealed class is the
> **fully-qualified class name** by default. Notesprout's persisted link targets therefore embed
> `com.notesprout.android.data.LinkTarget.CurrentNotebookPage` in user data — which permanently
> blocks any package rename. **Set an explicit `@SerialName` on every polymorphic subtype from day
> one.** This is the single most expensive mistake in the existing format; do not repeat it.

Because link children are **page-absolute**, moving a link must rewrite every child's coordinates.
Compare sticky notes below.

---

## `sticky_note` — a collapsed content window

The second composite, and the one with **two coordinate spaces**.

| Field | Column | Notes |
|---|---|---|
| icon rect | `x`,`y`,`width`,`height` | The on-page icon — page coords |
| content size | `contentW`, `contentH` | The content window's px size at authoring time |
| content | **child rows** | `parentId = sticky.id`, **LOCAL** coords |

Because children are in the sticky's **own** coordinate space, moving a sticky touches only the
parent row. This is the better design of the two composites: **prefer local coordinate spaces for
container objects.** A Paintsprout group/layer-folder should be local.

Storing `contentW`/`contentH` (the authoring-time canvas size) is what lets the content render
correctly on a device with a differently-sized screen.

---

## `page_text` — cached recognized text

`parentId` = page id, one per page. Payload is JSON in `text`. Included here because it demonstrates
the two patterns worth stealing:

1. **A derived cache lives inside the encrypted document**, so it is encrypted at rest for free and
   travels on export/import with no extra code.
2. **Freshness is a watermark, not an invalidation hook.** The row stores `sourceMaxUpdatedAt` — the
   maximum `updatedAt` of the layer's content at recognition time. Stale is
   `max(content.updatedAt) > sourceMaxUpdatedAt`. No cache-invalidation plumbing anywhere.

```json
{
  "text": "…markdown…",
  "engine": "mlkit",           // producer id — lets you upgrade per-engine
  "recognizedAt": 1750000000000,
  "sourceMaxUpdatedAt": 1749999999000,
  "schema": 2,
  "lines": [ { "text": "…", "strokeIds": ["…"], "top": 120.0, "height": 42.0 } ]
}
```

Note `engine` and `schema` inside the payload — the cache knows what produced it, so a better engine
can invalidate only its predecessor's output.

---

## Composites: the relational rule

An object that contains objects is **a parent row plus child rows**, each child a normal row of its
own type. Not nested JSON, not a serialized sub-document.

```
link (id=L, parentId=layer)
├── stroke  (parentId=L)
├── heading (parentId=L)
│   └── stroke (parentId=heading.id)
└── shape   (parentId=L)
```

Consequences that fall out for free:

- **Isolation.** Page queries filter `parentId = layerId`; composite children are invisible to page
  rendering, lasso, and export without any special-casing.
- **Deep copy is generic.** One recursive `collectDescendants`/`deepCopyChildren` handles every
  composite. No per-type copy code.
- **Delete is cheap.** Soft-delete the parent only. The subtree is reclaimed by the compactor's
  orphan sweep once the parent is purged.
- **Reads batch.** Children fetch in ≤2 queries: `WHERE parentId IN (…)` for level 1, then again for
  nested heading/text. Not N+1.

**Legacy shape:** composites once stored nested content as `zlib(JSON)` in `blob`. Readers detect
this — a row with `data != "" OR blob != NULL` is a legacy composite and decodes through the old
path; a row with `data == "" AND blob == NULL` uses child rows. A greenfield app implements only
child rows.

---

# Part V — Binary Encodings

## Stroke geometry — format "B" (float32 + zlib)

The measured problem: `{"x":257.9762,"y":390.0}` spends ~25 bytes to carry 8 bytes of float, and JSON
parsing was the page-load bottleneck. On a real 44 MB notebook, format B took the payload
**43.3 MB → 8.6 MB (5.0×)** with all 11,003 strokes round-tripping exactly.

**Wire layout** (per stroke, independently decodable so partial/lazy loads work):

```
byte 0   : version : u8   (= 1)                    ← PLAINTEXT, outside the compression
bytes 1+ : zlib{ flags:u8 | (x:f32, y:f32) × N }   ← little-endian
```

```
flags bit 0 (0x01) = per-point pressure present
flags bit 1 (0x02) = per-point tilt present
v1 writes flags = 0 (xy only)
```

The decoder derives its per-point stride from the flags it reads:
`stride = 8 + (pressure ? 4 : 0) + (tilt ? 4 : 0)`, and skips unknown extra channels. **This means
pressure and tilt can be added later with no version bump and no migration.**

Two design points worth carrying over:

- **The version byte is outside the zlib stream.** You can identify the format without decompressing.
- **float32, not quantized int16.** Lossy quantization was evaluated and rejected: for a
  handwriting-first app you never silently alter the user's ink. Binary+zlib was already fast enough
  that lossy bought nothing worth the trade.

Reference implementation (Kotlin, JVM-only so it unit-tests without a device):

```kotlin
fun encode(xy: FloatArray): ByteArray {          // xy = x0,y0,x1,y1,…
    val payload = ByteBuffer.allocate(1 + xy.size * 4).order(LITTLE_ENDIAN)
    payload.put(0)                                // flags
    for (v in xy) payload.putFloat(v)
    val compressed = deflate(payload.array())     // Deflater.BEST_COMPRESSION
    return byteArrayOf(1) + compressed            // version byte, then payload
}
```

An empty stroke encodes to a valid tiny blob that decodes back to an empty array — don't special-case
it.

## Images — WEBP q100

Covered under `template` in Part IV. Summary: WEBP quality-100 lossy, raw bytes in `blob` (base64
only in the index, where the column is TEXT). Decode format-agnostically by header. Never re-encode
an already-lossy image (the compactor walks the RIFF chunk list to positively identify `VP8L`
lossless before touching anything — a substring search would false-match inside an ICC profile).

## Legacy composite blobs

`zlib(UTF-8 JSON)` in `blob`. Read-only in a modern implementation. Documented here only so you
recognize it if you ever ingest a real Notesprout file.

---

# Part VI — Schema Versions & Migration

## `.soil` version history

| Version | Change | Migration |
|---|---|---|
| 1 | Base `notebook` table | — |
| 2 | `+ undo_redo_state` table | `CREATE TABLE IF NOT EXISTS` |
| 3 | `+ notebook_meta` table | `CREATE TABLE IF NOT EXISTS` |
| 4 | `+ 23 typed columns + blob` | 24 × `ALTER TABLE … ADD COLUMN` |

**Every migration to date is additive and non-rewriting.** v4 — the big one — adds 24 nullable
columns and rewrites zero rows. Opening a 44 MB legacy notebook is instant; content converts lazily
on write, and a compactor sweeps the backlog at close.

This is the migration strategy to copy:

1. **Additive DDL only.** Nullable columns, `CREATE TABLE IF NOT EXISTS`. Never rewrite rows in a
   migration — a migration runs on the UI's critical path.
2. **Format-agnostic readers.** Every reader tries the new shape and falls back to the old. Old and
   new rows coexist indefinitely.
3. **Convert on write.** A row touched by the user converts itself.
4. **Sweep in the background.** An idempotent, self-limiting compactor converts the backlog at close
   and `VACUUM`s once if anything changed.

Point 4 has a subtlety: shrinking a TEXT value in place leaves the freed bytes as internal page
fragmentation. `incremental_vacuum` will not return that to the OS — only a full `VACUUM` will. Issue
it **only** when at least one row actually changed, or you pay a full rewrite on every close.

Another subtlety: format conversions must **preserve `updatedAt`**. A format change is not a content
edit; bumping the timestamp would needlessly re-flag every file for backup.

## Schema as a single source of truth

The `CREATE TABLE` statement is written in exactly one place and referenced by every bootstrap site
and the migration. Notesprout has three sites that create the table (two creation paths + the
migration) and all three must produce a byte-identically-validating schema or the ORM's on-open
validation crashes. Centralize this from the start.

---

# Part VII — Encryption

## Model

SQLCipher encrypts **the entire database file** — every page, every table, the WAL, and the SQLite
header itself. A file browser sees opaque bytes with no recognizable magic.

**Encrypt-everything-by-default.** Both the global index and every document are encrypted from first
launch. At first run the app mints a random global key with no user interaction; encryption costs the
user nothing until they choose to care.

## The key is the passphrase

```kotlin
fun keyBytes(passphrase: String): ByteArray = passphrase.toByteArray(Charsets.UTF_8)
```

**Non-negotiable rules:**

- UTF-8 is the *only* correct encoding. Changing it breaks cross-device portability silently.
- **Never customize `kdf_iter` or page size.** Stock SQLCipher 4.x defaults (PBKDF2-HMAC-SHA512,
  256,000 iterations, AES-256, 16-byte salt in the file's plaintext header) mean any stock SQLCipher
  build opens the file with the same passphrase.
- **The platform keystore is never part of the key.** It encrypts the *local cache* of the passphrase
  and nothing more. Making the keystore part of the key would make files device-locked and
  unrecoverable.

Verify portability with the stock CLI — this is the acceptance test:

```sh
sqlcipher /tmp/test.soil
PRAGMA key = 'your-passphrase';
SELECT count(*) FROM sqlite_master;   -- an integer, not an error
```

## Key scopes

| Scope | Prompt behavior | Cached? | Raw key persisted? |
|---|---|---|---|
| `GLOBAL` | Once per device, then never | Yes — keystore-backed encrypted prefs | Yes (keystore) |
| `NOTEBOOK` | **Every** open | Never | RAM only, cleared on close |

The global key is **device-local by design**. A GLOBAL-encrypted document opens on another device —
the user is prompted once there, then it's cached. Same passphrase, different cache.

## The auto-generated recovery key

At first launch, if no global passphrase exists, one is minted:

```
NSPT-4K7P-9WXQ-2M3F-8VBN-5H0T-…      160 bits, Crockford base32, 8 groups of 4
```

Crockford base32 omits `I`, `L`, `O`, `U` to eliminate transcription confusion. The prefix and dashes
are *part of the string* — it is an ordinary passphrase fed to the KDF, not a structured token.

This doubles as the **recovery key**: the one secret that opens the library on another device or
after a reinstall. Onboarding must show it and offer replacement with a memorable passphrase via the
rotation flow. Use your own app prefix (`PSPT-` for Paintsprout).

## Raw-key caching — the performance answer

SQLCipher's KDF costs 300–700 ms **per connection**. On an e-ink device opening a document, that is
the entire perceived launch time.

The KDF output is deterministic for a given (passphrase, file salt) pair. So: derive once, cache the
32-byte result, and reopen via `PRAGMA key = "x'<64 hex chars>'"`, which SQLCipher recognizes as a
raw key and applies directly. **~35 ms including ORM overhead.**

```
Resolution order:  process RAM  →  keystore (GLOBAL only)  →  derive + store
```

The salt is the file's first 16 bytes, readable without any key:

```kotlin
fun deriveKey(file: File, passphrase: String): ByteArray =
    pbkdf2HmacSha512(passphrase.toByteArray(UTF_8), readSalt(file), 256_000, 32)
```

**Portability is preserved** — the file is still passphrase-keyed; the raw key is merely the KDF's
output, so a stock SQLCipher build still opens it with the passphrase.

Two operational rules:

- On a cache **miss**, open with the passphrase for that one connection and derive the raw key in the
  background, so the miss costs one slow open rather than blocking.
- **Invalidate the cache** on rotation, re-key, forget-on-device, decrypt, and delete. A stale raw key
  after rotation looks exactly like corruption.

## The data-loss guard — read this one twice

> An encrypted database opened with the **wrong key** is indistinguishable from a **corrupt** one.
> Room/SQLite's default corruption handler **deletes and recreates the file.**

This shipped as a real, severe data-loss bug in Notesprout: browsing an encrypted notebook in a link
picker opened it as plaintext, the default handler fired, and the notebook was **wiped**.

**Every open helper factory — plaintext, passphrase, and raw-key — must be wrapped in a
non-destructive factory that reports corruption without deleting.** No exceptions, no "this path
can't hit an encrypted file." Build the wrapper before you build the encryption.

## Leak hygiene

Encrypting the document is not sufficient. Every derived artifact is a plaintext side channel:

| Channel | Rule |
|---|---|
| Index cover/thumbnail | **Never** written for an encrypted document. Cleared atomically in the same write that sets the encrypted flag. The setter refuses a non-null value on an encrypted row. |
| Undo/redo sidecar | Not written when encrypted — persisted to the in-file `undo_redo_state` table instead (encrypted at rest for free). Stale sidecars deleted on open. |
| WAL / SHM | SQLCipher encrypts these too. Safe. |
| Original plaintext file | After in-place encryption, the original and all siblings are deleted atomically — the temp is renamed over the original **only after** verification passes. |
| Search index | **No document content is ever written to the global index.** Search queries only `name`. |

That last one is a **structural invariant, not a policy**: because no content can reach the index, a
future "search inside documents" feature *must* be an explicit design decision. Nothing can leak
there by accident.

## Conversion mechanics

**Encrypt / decrypt in place** — via `sqlcipher_export`:

```
1. Open source with the zetetic driver  (plaintext = empty key "")
2. ATTACH DATABASE '<tmp>' AS target KEY '<dest-passphrase>'
3. SELECT sqlcipher_export('target')
4. DETACH DATABASE 'target'
5. verifyPassphrase(<tmp>, dest-passphrase)          ← gate
6. Delete original + WAL/SHM/journal, then tmp.renameTo(original)
7. Delete any sidecars
```

On any failure before step 6 the temp is deleted and the original is untouched.

**Re-key** — `PRAGMA rekey = '<new>'`, in place, no temp file, then verify.

Encrypting an already-open document **requires a close → migrate → reopen cycle**: the live
connection holds an open handle and in-memory WAL, and `sqlcipher_export` must run against a sealed,
checkpointed database. There is no shortcut.

## Global rotation — crash-resumable batch re-key

Rotating the global passphrase re-keys every GLOBAL-scoped file. This can be hundreds of files and
must survive a crash:

- A **marker** (in encrypted prefs) records `pendingIds` + the new passphrase **before the first file
  is touched**.
- Per file: try `verifyPassphrase(file, NEW)` first — if it already opens, a prior run finished it;
  skip and drop from pending. Otherwise re-key, verify, remove from the marker.
- **Cancel stops after the current file completes** — never mid-re-key.
- The cached global passphrase is updated to the new one **only after `pendingIds` is empty.** During
  a partial rotation the cache still holds the old value, so already-rotated files fall through to a
  prompt and re-cache on success.
- **Quarantine rule:** a file marked GLOBAL in the index that does not actually open with the old
  global key gets downgraded to `NOTEBOOK` scope and the sweep **continues**. Without this, one
  mislabeled file stalls the entire rotation forever. (This was a real bug.)

Every step is idempotent. That is what makes resumption trivial rather than a reconciliation problem.

## Rate limiting

Escalating lockout per key bucket (per document id, plus separate `"GLOBAL"` and `"IMPORT"` buckets),
persisted so it survives process death:

| Consecutive failures | Lockout |
|---|---|
| 1–2 | none |
| 3 | 30 s |
| 5 | 5 min |
| ≥ 10 | 1 hr (cap) |

Cancel does not advance the counter. No passphrase material or attempt count is ever logged.

## Probing an unknown file

```
1. Empty/missing                         → Invalid
2. First 16 bytes != "SQLite format 3\0" → Encrypted
3. Opens as plain SQLite + reads
   sqlite_master successfully            → Plaintext
4. Otherwise                             → Encrypted
```

Step 2 matters: SQLCipher encrypts the first page including the header, so the magic is absent. Skip
that check and a plain driver will "successfully" open an encrypted file and read garbage. A
definitive encrypted-vs-garbage distinction requires the passphrase.

---

# Part VIII — The Global Index

`notesprout.db` — one per install, SQLCipher-encrypted under the global key, open for the whole app
lifetime.

## The `objects` table

Deliberately the **same universal row shape** as the document table, so object serializers work
unchanged across both databases.

```sql
CREATE TABLE objects (
    id        TEXT    PRIMARY KEY NOT NULL,
    type      TEXT    NOT NULL,
    name      TEXT    NOT NULL,        -- top-level, unlike .soil where name is in payload
    parentId  TEXT,                    -- NULL = root
    createdAt INTEGER NOT NULL,
    updatedAt INTEGER NOT NULL,
    deletedAt INTEGER,
    data      TEXT    NOT NULL DEFAULT '{}'
    -- plus columnar payload columns + blob (index v7/v8)
);
CREATE INDEX idx_objects_parent_type_deleted ON objects(parentId, type, deletedAt);
```

Index object types: `FOLDER` · `NOTEBOOK` · `LIST` · `TEMPLATE` · `TEMPLATE_FOLDER`.

Note `TEMPLATE` here does **not** collide with the `.soil` `"template"` type — different databases,
different meaning. The index holds the reusable **template library**; applying a library template
**copies** it into the document.

## The notebook row payload

```kotlin
data class NotebookObject(
    val snapshot: String? = null,     // base64 cover — ALWAYS null when encrypted
    val pageCount: Int = 0,
    val encrypted: Boolean = false,
    val keyScope: KeyScope? = null,   // non-null only when encrypted
    val excludeFromBackup: Boolean = false,
    val lastBackedUpLocal: Long? = null,
    val lastBackedUpDrive: Long? = null,
)
```

These fields let every list, picker, and card renderer know a document is encrypted **without opening
the file** — which is the whole point. Opening a file to learn whether it's locked would require the
key you're trying to decide whether to ask for.

**The passphrase is never written to the index.**

## Index rules

- `parentId = NULL` means root.
- Soft deletes only; no hard delete without a deliberate GC pass.
- All writes go through one repository; direct DAO access is read-only.
- The index must be open before any UI touches it. Because opening may now be **async** (a one-time
  plaintext→encrypted migration, or an unlock prompt), this cannot happen synchronously at app
  startup — a bootstrap gate drives it and consumers suspend on a ready latch.
- Membership lists (pins, ordering) are child rows, not JSON arrays.
- `checkpointAndVacuum()` on app background: `PRAGMA incremental_vacuum` +
  `PRAGMA wal_checkpoint(TRUNCATE)`, always via `rawQuery(...).use { it.moveToFirst() }`, never
  `execSQL`.

The index's WAL sidecars legitimately stay on disk (it's open the whole app lifetime) — this is the
one documented exception to the no-stray-files rule.

## Index open state machine

```
probe(notesprout.db):
  Invalid   → fresh install: mint global key, create ENCRYPTED from the start
  Plaintext → existing user upgrading: migrate schema plaintext, THEN encrypt in place, then open
  Encrypted → cached key?  → raw-key open (verify first)
              no key / verify fails → NEEDS_UNLOCK → prompt → verify → cache → open
```

The `Plaintext` branch ordering matters: bring the schema current **while still plaintext**, then
encrypt. Migrating an encrypted file is strictly harder for no benefit.

---

# Part IX — Portability: Export & Import

## Export is a file copy

The exported file is **the raw database**, renamed to `<DocumentName>.soil`. No zip, no wrapper, no
manifest. Because the file is self-describing, there is nothing to bundle with it.

- Filename: the current display name from the index, sanitized `[^a-zA-Z0-9_\-. ]` → stripped,
  trimmed. Empty (or `.`/`..`) falls back to the UUID. Spaces preserved.
- MIME: `application/octet-stream`.
- **Encrypted documents export silently as ciphertext.** No passphrase prompt, no "this file is
  unencrypted" warning — because it isn't. Encrypted status travels with the file.

## `notebook_meta` — the self-describing table

```sql
CREATE TABLE IF NOT EXISTS notebook_meta
    (id INTEGER PRIMARY KEY CHECK (id = 0), json TEXT NOT NULL);
```

| Field | Type | Description |
|---|---|---|
| `formatVersion` | Int (default 1) | Forward compatibility |
| `notebookId` | String | Stable UUID |
| `name` | String | Display name at last refresh |
| `createdAt` / `updatedAt` | Long | Epoch ms |
| `encrypted` | Boolean | |
| `keyScope` | `GLOBAL`/`NOTEBOOK`/null | |
| `cover` | String? | Base64 cover — **plaintext only**, always null when encrypted |
| `folderPath` | List\<FolderRef\> | Full ancestry, **ordered root → immediate parent** |
| `exportedAt` | Long? | Stamped at export |
| `appVersionCode` | Int? | Producer version |

```kotlin
data class FolderRef(val id: String, val name: String, val parentId: String?)
```

**`folderPath` carrying stable folder UUIDs is the key trick.** An importing device walks the list in
order and recreates missing folders **with the same ids and names**. Import the same document on
three devices and they converge on an identical hierarchy — no sync, no server, no merge.

## Continuous upkeep

`notebook_meta` is **not export-only**. It is refreshed:

| Event | Action |
|---|---|
| Creation | Table created + initial row inserted in the bootstrap SQL |
| Open | Refresh off the UI thread; failure tolerated and logged |
| Close | Refresh before checkpoint/vacuum — bakes in the freshest state before the file goes cold |

This upkeep is *exactly* what makes export a prompt-free pure copy: the embedded metadata is already
current, so exporting never needs to open (and therefore never needs to unlock) the file.

**Known lag:** a NOTEBOOK-encrypted document renamed in the index but not reopened before export gets
the new name in the *filename* but the old name in *embedded meta*. Accepted by design — the
alternative is prompting for a passphrase on export. It self-heals on the next open/close.

## Import pipeline

```
1.  Copy incoming URI → cacheDir/imported/incoming.soil   (dir wiped each import)
2.  Probe                          → Plaintext / Encrypted / Invalid (Invalid → abort)
3.  Unlock (encrypted only)        → prompt + verify, "IMPORT" rate-limit bucket
4.  Read manifest                  → notebook_meta + page count
                                     missing meta → fallback to filename, empty folderPath
                                     no object table → reject
5.  ID collision                   → Replace existing / Keep both (fresh UUID) / Cancel
6.  Placement                      → "document's folders" (recreate via folderPath) or choose
7.  Name conflict                  → Replace / Keep both (append " Copy")
8.  Keying chooser (encrypted)     → see table below
9.  Write into Garden              → delete stale sidecars, copy temp → soilFile(resolvedId)
10. Register in index
11. Refresh embedded meta          → new id, new folderPath, resulting keying; checkpoint; close
12. Cleanup temp
```

### Keying chooser

| Choice | Action | Resulting scope |
|---|---|---|
| Keep existing passphrase | no re-key | `GLOBAL` **iff** the entered passphrase equals this device's global; else `NOTEBOOK` |
| Use this device's global | `rekeyInPlace(file, entered, global)` | `GLOBAL` |
| New passphrase | `rekeyInPlace(file, entered, new)` | `NOTEBOOK` |

**The GLOBAL→NOTEBOOK downgrade rule** is subtle and correct: a document that was GLOBAL on the
*source* device, imported with "keep existing", is only GLOBAL here if its passphrase happens to
match this device's global. Otherwise it is recorded `NOTEBOOK` and prompts on every open — because
its passphrase genuinely is not this device's global. Scope is a property of the (file, device) pair,
not of the file.

**Ordering rule:** re-key operates on the **temp file, before the copy into Garden.** Any failure
leaves the real storage directory untouched.

---

# Part X — Durability: WAL, Sidecars, Backup

## The no-stray-files rule

> A file browser must show only document files. Never a `-wal`, `-shm`, or `-journal`.

```sql
PRAGMA journal_mode    = WAL;
PRAGMA wal_autocheckpoint = 100;
PRAGMA auto_vacuum     = INCREMENTAL;
```

On clean close: `PRAGMA incremental_vacuum` → `PRAGMA wal_checkpoint(TRUNCATE)` → close → delete any
`-journal` artifact.

Hard-won rules:

- **`wal_autocheckpoint` is connection-level and not persisted.** Re-apply on **every** open.
- **PRAGMAs that return a result set must use `rawQuery(...).use { it.moveToFirst() }`**, never
  `execSQL`, and never an unconsumed cursor. `execSQL` silently does not run them.
- **Raw non-ORM opens must use `OPEN_READWRITE`, never `OPEN_READONLY`.** A read-only WAL connection
  *recreates* `-shm` and then cannot unlink `-wal`/`-shm` on close — permanently stranding sidecars.
- **A helper must not delete `-wal`/`-shm` while another connection is open** to the same file.
  SQLite removes them when the last connection closes. Delete them yourself and you corrupt the live
  connection's view.
- Close on a **non-cancellable application-scoped coroutine**, not the UI component's scope, so a
  close survives the screen going away. Capture any snapshot on the main thread first, launch the
  seal, finish immediately.

## The seal sequence

```
flush pending content
  → refresh notebook_meta
  → run compactor (lazy format conversions; VACUUM only if something changed)
  → hard-delete rows soft-deleted before this session
  → incremental_vacuum
  → wal_checkpoint(TRUNCATE)
  → close
  → delete stray -journal
```

Soft-deleted rows are only hard-deleted **from prior sessions**, so this session's undo history stays
intact.

## Backup

Manual-trigger, incremental by timestamp, to LOCAL (a user-picked directory tree) and/or cloud.

**Filenames are UUIDs, not display names** — `<uuid>.soil`. This gives stable replace-in-place
identity: renaming a document doesn't orphan its backup. The display name travels *inside* the file
via `notebook_meta`, which is what makes restore work.

**Needs-backup predicate:** `!excluded && (lastBackedUp[dest] == null || updatedAt > lastBackedUp[dest])`.
Per-destination timestamps. A failed copy does **not** stamp — it retries next run.

**Index last.** Documents first, then checkpoint the index, then copy the index. The backed-up index
therefore reflects a *completed* run. Backing it up first would record timestamps for copies that
hadn't happened yet.

**Encrypted documents are copied as ciphertext.** No prompt, no decryption — a byte copy is
sufficient and correct.

**Restore is staging-first, replace-all:** stage everything, validate, then swap. Restart into the
unlock flow afterward, since the restored index may be keyed to a different global secret.

---

# Part XI — Adapting This Format for Paintsprout

## What you MUST keep (the compatibility surface)

These are what make two apps "the same format family." Changing any of them means reinventing the
wheel, which is precisely what this document exists to prevent.

1. **One document = one SQLite file**, app-specific extension (`.paint`? `.canvas`? — pick one and
   never change it).
2. **Flat UUID-named directory**; all hierarchy in a separate global index.
3. **One universal object table**, string `type` discriminator, columnar payload + `blob`.
4. **`id` / `parentId` / `type` / `order` / `createdAt` / `updatedAt` / `deletedAt`** — identical
   names, identical semantics, epoch-ms integers, soft delete via `deletedAt`.
5. **Composites as parent + child rows**, `child.parentId = composite.id`.
6. **A single-row `notebook_meta` table** with `CHECK (id = 0)`, holding the `NotebookMeta` field set
   verbatim — same table name, same field names, in both apps. It is the container's identity record,
   not Notesprout's, and a shared reader must find it at a fixed name. **Keep `folderPath`
   unchanged** — it's what lets a document imported into either app land in the right place.

6a. **Name your object table for your content** (`sketchbook`, not `notebook`) and never touch a
   table you don't own, so a single file can carry both apps' content.
7. **SQLCipher whole-file, stock defaults, UTF-8 passphrase, key ≠ keystore.** A Paintsprout document
   and a Notesprout notebook should both open in the stock `sqlcipher` CLI with the same recipe.
8. **The raw-key derive-once cache** (identical: read 16-byte salt, PBKDF2-HMAC-SHA512 ×256,000 →
   32 bytes, `PRAGMA key = "x'<hex>'"`).
9. **GLOBAL / per-document key scopes**, auto-generated Crockford-base32 recovery key (change the
   prefix to `PSPT-`).
10. **The non-destructive open-helper wrapper.** Non-negotiable.
11. **Export = raw file copy; import = probe → unlock → placement → collision → keying.**
12. **WAL discipline and the no-stray-files rule.**

Ideally the two apps share a genuine `soil-container` module rather than parallel implementations.
The container has no notion of ink or paint — it is objects, rows, keys, and files.

## What you MUST replace (the app-specific surface)

**The object catalog.** Notesprout's `heading` / `text` / `link` / `sticky_note` / `page_text` are
handwriting-app concepts. Paintsprout will have its own. The point of the string discriminator is
that this costs no schema change — pick your types and go.

A plausible Paintsprout catalog, showing how the existing columns map:

| Type | Likely columns | Notes |
|---|---|---|
| `document` | `text`=title, `refId`=lastOpenedCanvas, `flags` | Same role as `notebook` |
| `canvas` | `boundingBox`={0,0,w,h}, `refId`=background | Same role as `page` |
| `layer` | `text`=label, `flags`=locked/visible **+ new** blend mode & opacity | Extend `flags`; add `opacity REAL` |
| `raster` | `blob`=WEBP/PNG tile bytes, `x`/`y`/`width`/`height` | The big new one — see below |
| `brush_stroke` | `blob`=geometry, `color`, `strokeWidth`, **+ brush params** | Extend the stroke format — see below |
| `shape` | unchanged | Take Notesprout's wholesale |
| `text` | `text`, box | Simplify — drop the two-state recognition split |
| `group` | children, **LOCAL coords** | Model on `sticky_note`, **not** `link` |
| `mask` / `adjustment` | `refId` → target layer, params in typed columns | |
| `palette` | `blob` or child rows | Document-scoped, travels on export |

### Extending the stroke codec for brushes

Format B was designed for exactly this. Two clean paths:

- **Per-point channels → use the reserved flag bits.** `flags` bit 0 = pressure, bit 1 = tilt are
  already specified and the decoder already derives its stride from them. Bits 2–7 are free: velocity,
  rotation, barrel pressure. **No version bump, no migration** — a Notesprout-era decoder that reads a
  Paintsprout stroke skips channels it doesn't know.
- **Per-stroke brush parameters → row columns, not the blob.** Brush id, opacity, flow, spacing,
  jitter belong in typed columns exactly as `color` and `strokeWidth` do. Keeps the blob pure geometry
  and makes brush changes scalar updates.

Bump the version byte only if you change the *geometry* encoding (e.g. adding per-stroke float64 for
very large canvases). The version byte is deliberately outside the zlib stream so you can dispatch on
it cheaply.

### Raster data — the one genuinely new problem

Notesprout has no equivalent, so decide these deliberately:

- **Tile, don't monolith.** One row per tile (`x`/`y`/`width`/`height` + `blob`) rather than one row
  per full-canvas image. Undo of a local edit rewrites one tile; the existing `parentId` grouping and
  `order` layering work unchanged.
- **Lossless for artwork.** Notesprout's WEBP-q100 finding is specific to *sparse transparent ink*.
  Paint pixels are dense and user-authored — a lossy round-trip on every save is unacceptable. Use
  lossless (PNG, or a raw+zlib format analogous to the stroke codec) and **re-measure** rather than
  inheriting the q100 conclusion.
- **Keep the bounded-decode rule.** It matters far more with raster.
- **Watch file size.** Documents grow orders of magnitude larger than notebooks. The incremental
  vacuum + compaction story needs re-validating at that scale.

### Coordinate spaces

Copy the **sticky note** (local coordinates — moving the container touches one row), not the **link**
(page-absolute — moving rewrites every child). For a paint app with nested groups and transforms,
local spaces with a transform on the parent is the only sane model. Consider adding
`scaleX`/`scaleY`/`rotationDeg` to container rows so a group transform is a parent-row update.

## Explicit mistakes not to repeat

1. **Never use a default FQCN discriminator for polymorphic serialization.** Put an explicit stable
   `@SerialName` on every subtype. Notesprout's persisted link targets embed
   `com.notesprout.android.data.LinkTarget.CurrentNotebookPage` in user data and can never be renamed.
2. **Build the non-destructive open-helper wrapper before the encryption.** Wrong key looks like
   corruption; the default handler deletes.
3. **Don't persist derivable geometry.** Stroke AABBs and shape AABBs are recomputed. Every
   denormalized geometry field is a future drift bug.
4. **Don't put content in the index.** Once it's there, "search leaks plaintext" is a design problem
   forever. Keep the invariant absolute.
5. **Don't store per-sample timestamps.** Notesprout did; they were never read, every point in a
   stroke carried the same value, and they cost ~40% of stroke payload.
6. **Columnar tables need columnar writers.** A generic "update the JSON payload" helper is a **dead
   write** against a columnar row — the update appears to succeed and silently does nothing. This
   shipped as a "moved objects snap back" bug. If you keep both paths during a migration, make the
   legacy writer *fail loudly* on a columnar row.
7. **Handle the mislabeled-file case in any batch re-key.** One file whose recorded scope doesn't
   match reality must be quarantined and skipped, not allowed to stall the sweep.
8. **Decide dp-vs-px per type and write it down.** It is invisible in the schema and silently wrong
   across devices.

## Suggested build order

1. Container: `soilFile()`, schema constant, universal row entity, WAL PRAGMAs, seal sequence.
2. **Non-destructive open helper wrapper.** Before anything touches a key.
3. Global index: objects table, folder tree, repository, bootstrap gate.
4. Encryption: `Crypto` open helper, key scopes, keystore-backed store, recovery-key generation,
   raw-key cache, rate limiter.
5. Object model: structural types (`document`/`canvas`/`layer`), then leaf content types.
6. Binary codec: adapt format B with your channel flags + brush columns.
7. `<app>_meta` + continuous upkeep at create/open/close.
8. Export (copy) → import (probe/unlock/placement/collision/keying).
9. Backup, then restore.
10. Compactor — last, once you have a legacy shape to sweep.

Steps 1–4 are the container and should be shared code. Everything from 5 on is Paintsprout's own.

---

# Appendix A — Quick Reference Tables

## Universal row columns

| Column | SQL type | Null? | Meaning |
|---|---|---|---|
| `id` | TEXT | no | UUIDv4, primary key |
| `parentId` | TEXT | no | Parent object id; `""` for the root meta row |
| `type` | TEXT | no | String discriminator |
| `"order"` | INTEGER | no, default 0 | Sort among siblings (**reserved word**) |
| `createdAt` | INTEGER | no | Epoch ms |
| `updatedAt` | INTEGER | no | Epoch ms |
| `deletedAt` | INTEGER | yes | NULL = alive |
| `boundingBox` | TEXT | no | Legacy JSON; `""` on columnar content rows |
| `data` | TEXT | no | Legacy JSON; `""` on columnar rows |
| `x` `y` `width` `height` | REAL | yes | Box: top-left + **extents** |
| `text` | TEXT | yes | Role varies by type |
| `color` | TEXT | yes | `#RRGGBB` / `#AARRGGBB` |
| `strokeWidth` | REAL | yes | px (stroke) / dp (line, shape) |
| `refId` | TEXT | yes | Intra-file reference |
| `level` | INTEGER | yes | heading 1–3 |
| `lineStyle` `orientation` | TEXT | yes | Enum names |
| `dotSpacing` | REAL | yes | dp |
| `shapeType` | TEXT | yes | Enum name |
| `centerX` `centerY` `rotationDeg` | REAL | yes | Oriented box |
| `pointCount` | INTEGER | yes | STAR |
| `contentW` `contentH` | REAL | yes | Second coordinate space |
| `linkTarget` | TEXT | yes | Polymorphic JSON |
| `chrome` | TEXT | yes | Enum name |
| `flags` | INTEGER | yes | Bitfield, per-type |
| `blob` | BLOB | yes | Binary payload |

## Bitfields

| Type | Bit | Constant | Meaning |
|---|---|---|---|
| layer | 0 | `LAYER_FLAG_LOCKED = 1` | locked |
| layer | 1 | `LAYER_FLAG_VISIBLE = 2` | visible |
| shape | 0 | — | aspectLocked |
| notebook | 0 | — | rtrEnabled |

Default new content layer: `flags = 2`.

## Crypto constants

| Constant | Value |
|---|---|
| KDF | PBKDF2-HMAC-SHA512 |
| Iterations | 256,000 (stock) |
| Key length | 32 bytes (AES-256) |
| Salt | 16 bytes, file's plaintext header |
| Passphrase encoding | UTF-8 |
| Raw key literal | `x'<64 hex chars>'` |
| Recovery key entropy | 160 bits |
| Recovery key alphabet | Crockford base32 (no `I` `L` `O` `U`) |
| Recovery key format | `<PREFIX>-` + 8 groups of 4 |
| Passphrase open | ~300–700 ms |
| Raw-key open | ~35 ms |

## Stroke codec

| | |
|---|---|
| Version byte | `1` = float32, **outside** the zlib stream |
| Compression | zlib, `BEST_COMPRESSION` |
| Byte order | Little-endian |
| Base stride | 8 bytes (x:f32, y:f32) |
| flags bit 0 | pressure present (+4 bytes/point) |
| flags bit 1 | tilt present (+4 bytes/point) |
| flags bits 2–7 | **free — use for brush channels** |
| Measured | 43.3 MB → 8.6 MB (5.0×), lossless |

---

# Appendix B — Known Divergences & Open Questions

## Documentation drift (resolved)

Six passages across four docs asserted that the global index is never encrypted, which stopped being
true when encrypt-everything-by-default shipped. These have been corrected:
`data-architecture.md`, `clipboard-and-page-transfer.md`, `calendar.md` (×2), `scratchpad.md` (×2).
`encryption.md` gained a new **"The Global Index Is Encrypted"** section as the canonical reference.
Also corrected: `data-architecture.md`'s "strokes are JSON" bullet, `full-notebook-export.md`'s
"import is out of scope" header, and `backup.md`'s "restore is future" stub.

The matching code inaccuracy was also fixed: `NotebookActivity.awaitEncryptionClipboardConfirm()` and
its three call sites were **removed**. That prompt warned about content moving into plaintext, which
no longer happens — every store it could reach is encrypted at rest. This is the general principle:
once encryption is universal, per-operation "this leaves the encrypted zone" prompts stop being
warnings and start being noise. Audit for them after any encrypt-everything migration.

## Content typing: the table name is the discriminator

**A `.soil` file declares what it contains by which object table it has.** Notesprout's is
`notebook`; Paintsprout's would be `sketchbook`. A reader identifies content by table presence:

```sql
SELECT name FROM sqlite_master WHERE type='table';
```

This is better than a `producer` string in the meta record for three reasons:

1. **It cannot disagree with reality.** A metadata field can be wrong or absent; the table either
   exists or it doesn't.
2. **It's readable before parsing anything.** One `sqlite_master` query, no JSON decode, no schema
   assumptions.
3. **It composes.** A single `.soil` can hold **both** a `notebook` and a `sketchbook` table —
   multiple content types in one document. A reader takes the tables it understands and leaves the
   rest intact. A mixed file round-trips through an app that only knows one of its types without
   losing the other, provided writers never `DROP` a table they don't own.

Rules that follow, and that both apps must honor:

- **Never drop or rewrite a table you don't own.** This is what makes coexistence safe.
- Shared infrastructure tables stay shared and generic: `notebook_meta` (identity — keep the name
  even in Paintsprout, it's the container's table, not Notesprout's) and `undo_redo_state`.
- Each app's object table carries the universal row schema, so container-level code (copy, subtree
  walk, soft-delete sweep, compaction) is parameterized on the table name and otherwise identical.
- `formatVersion` in `notebook_meta` stays what it is: the **container's** schema version, not a
  content-type marker. It is currently `1` and nothing branches on it — that's fine, it's a
  forward-compatibility reserve. Bump it only if the container contract itself changes.

## Genuinely undecided

- **Cross-app import semantics.** Table presence tells a reader *what* it has; it doesn't say what to
  *do*. When Notesprout is handed a file with only a `sketchbook` table, should it reject cleanly,
  import the shell and show it as unopenable, or offer to hand off to Paintsprout? And for a mixed
  file, does the notebook side import alone, or is splitting a document across apps incoherent?
  Decide before either app ships an importer that can receive the other's files.
- **Who owns a mixed file's index row?** The global index has one row per document with one `type`.
  A file with both tables needs either a compound type, two rows pointing at one file, or a rule that
  the index records only the content type the running app understands. Unresolved.
- **`data` and `boundingBox` legacy columns.** Notesprout keeps them `NOT NULL` for coexistence. A
  greenfield Paintsprout should omit them — but then a shared container module has to be
  parameterized on their presence. Simplest resolution: the shared module omits them, and Notesprout
  keeps them as an app-local extension until its compactor has swept every device.
- **Raster compression** has not been measured (Notesprout has no raster path). Re-measure; do not
  inherit the WEBP-q100 conclusion.
- **The 2-level composite depth bound** is a convention in the readers, not a schema constraint.
  Deeply nested paint groups will need a genuinely recursive loader with an explicit depth cap.

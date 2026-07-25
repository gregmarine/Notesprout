# The Global Index (`notesprout.db`) — Complete Specification

> **Audience:** an engineer (human or AI) implementing a compatible global index for a *different*
> application in the Sprout family — e.g. **Paintsprout**. This document is self-contained: it assumes
> no knowledge of Notesprout's codebase.
>
> **Companion document:** [`soil-file-format.md`](soil-file-format.md) specifies the *document*
> container (one `.soil` file per notebook). This one specifies the *other* half of the storage
> model: the single per-install database that holds structure, library metadata, app-level content,
> and app configuration. Read that one first — this document reuses its object model, its encryption
> model, and its vocabulary without re-deriving them.
>
> **Compatibility goal:** not byte-for-byte 1:1. Paintsprout's index will hold different object types
> and different app tables. What must match is the **index contract** — the two-database split, the
> universal row shape, the "no content leaks" invariant, the encryption and key lifecycle, and the
> open/unlock state machine. [Part IX](#part-ix--adapting-this-index-for-paintsprout) says exactly
> which parts are invariant and which you are expected to replace.
>
> Everything below describes Notesprout as built on the `main` branch (index schema v8, `.soil` v4,
> global encryption phases 0–5). Legacy shapes still readable in the wild are called out explicitly; a
> greenfield app should implement only the current shape.

---

## Table of Contents

- [Part I — Why There Are Two Databases](#part-i--why-there-are-two-databases)
- [Part II — Storage & Lifecycle](#part-ii--storage--lifecycle)
- [Part III — The `objects` Table](#part-iii--the-objects-table)
- [Part IV — App-Content Tables](#part-iv--app-content-tables)
- [Part V — Schema Versions & Migration](#part-v--schema-versions--migration)
- [Part VI — Encryption](#part-vi--encryption)
- [Part VII — Durability, Backup & Restore](#part-vii--durability-backup--restore)
- [Part VIII — Stores Outside Both Databases](#part-viii--stores-outside-both-databases)
- [Part IX — Adapting This Index for Paintsprout](#part-ix--adapting-this-index-for-paintsprout)
- [Appendix A — Quick Reference Tables](#appendix-a--quick-reference-tables)
- [Appendix B — Known Divergences & Open Questions](#appendix-b--known-divergences--open-questions)

---

# Part I — Why There Are Two Databases

A Sprout install has exactly two kinds of database:

| | Global index (`notesprout.db`) | Document (`<uuid>.soil`) |
|---|---|---|
| Count | Exactly one per install | One per document |
| Holds | Folder tree, document rows, names, covers, template library, lists/pins, app-level canvases, app config | All document content |
| Lifetime | Open for the whole app lifetime | Open only while the document is open |
| Encryption | SQLCipher under the **global** key | SQLCipher under global **or** per-document key |
| Portable? | **No** — device-local, never handed to another install except as a whole-library restore | **Yes** — self-describing, exportable, importable |
| Rebuildable? | **No** — it is the only source of hierarchy | Yes — it is the source of truth for its own content |

## The five index invariants

1. **All structure lives here; none of it lives on the filesystem.** The document directory is flat
   blob storage with UUID filenames. Folder ancestry, display names, ordering, and pinning are index
   concerns. This is precisely what makes a document file portable — it carries no assumption about
   where it lives.

2. **The index is not rebuildable from the documents, and the documents are not rebuildable from the
   index.** Losing the index loses the hierarchy (documents survive, unfiled). Losing a document loses
   its content (the index row survives, pointing at nothing). Both must be backed up, and the backup
   must order them correctly ([Part VII](#part-vii--durability-backup--restore)).

3. **No document content is ever written to the index.** Not text, not search terms, not extracted
   entities. The one deliberate exception is a document *cover image*, under the rules in
   [Part VI](#what-may-be-cached-in-the-index). This is a **structural invariant, not a policy**:
   because content cannot reach the index, a future "search inside documents" feature *must* be an
   explicit design decision. Nothing can leak there by accident.

4. **The index rows describe a document well enough that no list, picker, or card ever has to open
   the file.** Name, page count, encrypted flag, key scope, cover. Opening a file to learn whether
   it's locked would require the key you're trying to decide whether to ask for.

5. **The index is encrypted at rest, under the global key, from first launch.** There is no plaintext
   mode for new installs. See [Part VI](#part-vi--encryption).

## What the index is *not*

- Not a cache. Nothing in it can be regenerated.
- Not a sync surface. It is device-local; two devices converge on structure by importing the same
  self-describing documents, not by exchanging indexes. (Restore is the one exception, and it is
  whole-library replace-all, not merge.)
- Not a place for secrets. No passphrase, no key, no token is ever written to it.

---

# Part II — Storage & Lifecycle

## Layout on disk

```
<app external files dir>/
├── notesprout.db                  ← the global index (SQLCipher-encrypted)
├── notesprout.db-wal              ← present while the index is open (normal WAL behavior)
├── notesprout.db-shm
└── Garden/                        ← flat document directory, no subdirectories, ever
    ├── 3f2a1b8c-….soil
    └── …
```

On Android this is `context.getExternalFilesDir(null)` — app-private, no runtime permissions, visible
to the user in a file manager, removed on uninstall. The index sits **beside** the document directory,
not inside it, so a directory sweep over documents never trips over it.

The index's `-wal`/`-shm` sidecars legitimately stay on disk: the connection is open for the whole app
lifetime, so there is no "clean close" moment at which to remove them. This is the one documented
exception to the no-stray-files rule that governs `.soil` files (`soil-file-format.md` Part X).

## Lifetime and the bootstrap gate

The index is a process-wide singleton, opened once and closed on shutdown. Opening it is **potentially
async and potentially interactive**:

- a fresh install must mint a global key and create the file encrypted;
- an install upgrading from a pre-encryption build must migrate the schema and then encrypt in place;
- a device with no cached key must **prompt the user** before the index can open at all.

That last case is why opening cannot be a synchronous step in application startup. The shape is:

```
Application.onCreate        → kick off, do not block
BootstrapActivity           → drives ensureReady(); shows unlock UI on NEEDS_UNLOCK; shows a
                              retry-able error screen on failure (never a launcher crash-loop)
every index consumer        → suspends on a ready latch (awaitReady) before touching a DAO
deep-link / share entries   → self-guard, because they can start outside the bootstrap path
```

**Do not let any UI read the index before the gate opens.** The failure mode is not a blank screen —
it is a consumer that opens a not-yet-migrated or not-yet-decrypted database and draws conclusions
from it.

## Concurrency rules

- One mutex serializes **every** open, unlock, re-key, and seal. Sealing mid-open (or the reverse)
  interleaves a close with a fresh open and throws on the closed instance.
- `ensureReady` is idempotent and safe to call concurrently — it returns immediately if the instance
  already exists.
- All writes go through one repository; direct DAO access is read-only. This is what keeps the
  updatedAt discipline in [Part III](#the-updatedat-discipline) enforceable in one place.

---

# Part III — The `objects` Table

The index's object table is deliberately **the same universal row shape** as the document object
table, so object serializers, columnar mappings, and subtree walks work unchanged across both
databases.

## Schema (v8)

```sql
CREATE TABLE objects (
    -- ── Universal row (v1 core, stable) ──────────────────────────────────
    id                TEXT    NOT NULL PRIMARY KEY,   -- UUIDv4 (or a sentinel, see below)
    type              TEXT    NOT NULL,               -- string discriminator
    name              TEXT    NOT NULL,               -- top-level, unlike .soil where name is payload
    parentId          TEXT,                           -- NULL = root
    createdAt         INTEGER NOT NULL,               -- epoch ms
    updatedAt         INTEGER NOT NULL,               -- epoch ms
    deletedAt         INTEGER,                        -- NULL = alive; epoch ms = soft-deleted
    data              TEXT    NOT NULL DEFAULT '{}',  -- legacy JSON payload; "" on columnar rows

    -- ── Columnar payload (v7) — all nullable, wide + sparse ──────────────
    pageCount         INTEGER,   -- document
    flags             INTEGER,   -- document bitfield (see below)
    keyScope          TEXT,      -- document: 'GLOBAL' | 'NOTEBOOK'
    lastBackedUpLocal INTEGER,   -- document, epoch ms
    lastBackedUpDrive INTEGER,   -- document, epoch ms
    width             INTEGER,   -- template
    height            INTEGER,   -- template
    blob              BLOB,      -- document cover bytes / template image bytes

    -- ── Relational membership (v8) ───────────────────────────────────────
    refId             TEXT,      -- list_item → the member's id
    sortOrder         INTEGER    -- list_item → position within its list
);

CREATE INDEX index_objects_parentId_type_deletedAt ON objects(parentId, type, deletedAt);
```

The index name is ORM-generated; only the column set is contractual. `(parentId, type, deletedAt)` is
the shape of essentially every read — "the live folders under this parent", "the live documents under
this parent" — so it is the one index that matters.

### Deliberate divergences from the `.soil` row

| | `.soil` object table | index `objects` table |
|---|---|---|
| `parentId` at root | `NOT NULL`, `""` sentinel | **nullable, NULL** |
| `name` | inside the payload (`text` column) | **a top-level column** |
| `"order"` | present — explicit sibling order | **absent** |
| `boundingBox` | present (legacy, `NOT NULL`) | absent |

`name` is promoted because every index read is a listing that needs it, and no index row has a
competing use for a generic `text` column. The missing `"order"` is the interesting one: **index rows
have no intrinsic sibling order.** Folders and documents are sorted by the user's chosen sort (name,
created, updated) at read time. The only explicit ordering in the index is *list membership*, and that
is carried on the `list_item` edge rows via `sortOrder`. If you need user-draggable ordering of the
tree itself, add an `"order"` column — do not overload `sortOrder`.

Everything else about the wide-sparse design — why one table, why NULL columns are nearly free, why a
new type costs no migration — is the same argument made in `soil-file-format.md` Part III and is not
repeated here.

## Type catalog

```
Structure:     "folder"  "notebook"
Library:       "template"  "template_folder"
Membership:    "list"  "list_item"
Singletons:    "clipboard"  "backup_config"
```

`"template"` here does **not** collide with the `.soil` `"template"` type — different databases,
different meaning. The index holds the reusable **template library**; applying a library template
**copies** it into the document as a document-local row. A document never references the library.

Rename `"notebook"` to your content type in Paintsprout (`"sketchbook"`, say). Note that the index's
type string and the document's *object-table name* (the `.soil` content discriminator) are two
independent decisions; keep them consistent for sanity, but only the table name is load-bearing for
file identification.

## Sentinel ids

Six well-known ids are hard-coded rather than random. Each is the all-zero UUID with an ASCII string
spelled out in hex as the last group, which makes them recognizable in a hex dump and impossible to
collide with a real UUIDv4:

| Constant | Id | Lives in | Purpose |
|---|---|---|---|
| `PINNED_LIST_ID` | `00000000-0000-0000-0000-70696e6e6564` (`pinned`) | `objects` | The pinned-documents list |
| `PINNED_TEMPLATES_LIST_ID` | `…-746d706c7069` (`tmplpi`) | `objects` | The pinned-templates list |
| `CLIPBOARD_ID` | `…-636c69706264` (`clipbd`) | `objects` | The persisted clipboard singleton |
| `BACKUP_CONFIG_ID` | `…-6261636b7570` (`backup`) | `objects` | The backup configuration singleton |
| `SCRATCHPAD_ROOT_ID` | `…-736372746368` (`scrtch`) | `scratchpad` | Root row of the scratch-pad tree |
| `CALENDAR_ROOT_ID` | `…-63616c6e6472` (`calndr`) | `calendar` | Root row of the calendar tree |

A sentinel row is created on demand by an idempotent `ensure…Exists()` called at every launch — never
assumed to be present, never created by a migration. A migration that inserts data is a migration that
can fail on a user's device; an idempotent bootstrap cannot.

## Row payloads by type

### `notebook` — one per document

The row is the app's entire knowledge of a document while it is closed.

| Field | Storage | Notes |
|---|---|---|
| display name | `name` column | The file on disk is named by UUID; this is the only display name the library has |
| page count | `pageCount` | For the card subtitle; refreshed on close |
| encrypted | `flags` bit 0 | |
| exclude from backup | `flags` bit 1 | |
| key scope | `keyScope` | `GLOBAL` / `NOTEBOOK`, non-null only when encrypted |
| last backed up (local) | `lastBackedUpLocal` | epoch ms, per destination |
| last backed up (cloud) | `lastBackedUpDrive` | epoch ms, per destination |
| cover snapshot | `blob` | WEBP q100 bytes. **Governed by the caching rule in [Part VI](#what-may-be-cached-in-the-index)** |

Legacy JSON shape in `data` (still readable):
`{"snapshot":"<base64>","pageCount":…,"encrypted":…,"keyScope":…,"excludeFromBackup":…,`
`"lastBackedUpLocal":…,"lastBackedUpDrive":…}`.

These fields exist so that **every list, picker, and card renderer knows a document is encrypted
without opening the file.** That is the whole point of the row.

### `folder` / `template_folder` — pure structure

No payload at all: `data = ""`, every typed column NULL. Identity is `id` + `name` + `parentId`.
Folders nest arbitrarily; `parentId = NULL` is root. A template folder may contain only template
folders and templates — never reuse the document `folder` type for it, or a single tree walk will
happily mix libraries.

Ancestry resolution walks `parentId` upward and is **cycle-guarded with a hop cap** (50). A cycle in a
user's folder tree should be impossible, and an unguarded walk turns "impossible" into a hang.

### `template` — a library template

| Field | Storage |
|---|---|
| name | `name` column (**not** in the payload — unlike the `.soil` template type) |
| width, height | `width`, `height` |
| image | `blob` — full-resolution WEBP q100 bytes |

Legacy rows carry `{"width":…,"height":…,"image":"<base64 PNG>"}` in `data`.

The image-encoding rules, including the "never re-encode an already-lossy image" chunk-inspection
rule and the bounded-decode requirement, are in `soil-file-format.md` Part IV/V and apply identically
here. A template image is user-supplied data being decoded on a low-memory device; it deserves the
same paranoia in the index as in a document.

### `list` + `list_item` — membership as child rows

A list is a named row; its membership is **not** a JSON array on that row but a set of `list_item`
child rows:

```
list      (id = PINNED_LIST_ID, name = "Pinned", parentId = NULL)
├── list_item (parentId = list.id, refId = <document id>, sortOrder = 0)
├── list_item (parentId = list.id, refId = <document id>, sortOrder = 1)
└── …
```

Rules:

- **Membership churn hard-deletes the edge rows.** Soft deletes exist to protect user content; a pin
  toggle is not user content, and tombstoning it would leave every list accumulating dead rows
  forever. This is the one place in either database where a hard delete is routine.
- **Reads are format-agnostic.** A legacy list still holding an inline JSON array in `data` is read
  from there; the first write (or the launch-time `ensure…Exists` bootstrap) converts it to child rows
  and clears `data`. The conversion **preserves `updatedAt`** — a format change is not an edit.
- **Members are resolved and filtered at read.** A membership edge pointing at a missing,
  soft-deleted, or wrong-typed row is silently skipped, so a list can never resurrect a deleted
  document or crash a picker.
- **Deleting a member scrubs its edges everywhere** — one `DELETE … WHERE refId = ?` across all lists,
  before the member itself is soft-deleted. Without this, list rows accumulate dangling references
  that every reader then has to defend against.

### `clipboard` / `backup_config` — JSON singletons, by design

Two rows keyed by their sentinel ids whose payload stays JSON in `data`. This is deliberate, not a
migration backlog: both are small, both are read exactly once per use, both change shape as features
change, and neither is queried by any column. Columnarizing them would buy nothing and cost a
migration every time a field is added.

- **Clipboard** — the persisted cross-document clipboard: a list of typed items with their bounding
  boxes, plus the source document id and a copy timestamp. Cleared by **soft delete** (so the row's
  identity survives), and read as "empty" when soft-deleted or when `data` is the empty object.
- **Backup config** — device id, per-device folder name, per-destination enable flags and roots, the
  connected account identifier, and the last-run timestamp. Created on demand with a default.

Both decode **leniently and per item**: an unknown field from a newer build is ignored, and a single
corrupt clipboard item is dropped rather than allowed to throw. A clipboard row that throws on decode
at launch is a crash-loop with no user-reachable way to clear it — this shipped once.

## Format-agnostic reads, lazy conversion

Identical discipline to the document container:

- A **columnar row** writes `data = ""` and puts everything in typed columns.
- A **legacy row** has JSON in `data`.
- Every reader prefers the typed columns and falls back to JSON. Old and new rows coexist
  indefinitely.
- Rows convert on their next write; a manual compaction sweep converts the backlog (and re-encodes
  any image blob still stored as PNG or lossless WEBP), then `VACUUM`s **once, only if something
  actually changed**.

Because the index is a long-lived singleton with no per-document "close" moment, its compaction runs
from an explicit user-triggered sweep rather than from a seal.

**A greenfield implementation should omit the `data` column entirely.** It exists only so that an
existing user's library upgrades without a rewrite.

## The `updatedAt` discipline

`updatedAt` on a document row is not a general-purpose "row was touched" marker. It is the input to
the backup predicate (`updatedAt > lastBackedUp[dest]`), so bumping it schedules a file copy.

| Operation | Bumps `updatedAt`? |
|---|---|
| Rename, move, cover refresh, page-count refresh | **Yes** — the document's presentation changed |
| Encryption state change | **Yes** — the file on disk changed |
| Stamping a backup timestamp | **No** — that would re-flag the file it just backed up |
| Setting the exclude-from-backup flag | **No** — a policy toggle, not a modification |
| Legacy→columnar format conversion | **No** — a storage-shape change, not an edit |

Get this wrong in the "no" direction and every compaction pass re-uploads the user's entire library.

---

# Part IV — App-Content Tables

Beyond `objects`, the index carries four tables that are **not** about documents at all. They exist
because the app has content that belongs to the *install* rather than to any document — and that
content still deserves to be encrypted at rest, backed up, and modeled like everything else.

This is the part of the index most specific to Notesprout. The *pattern* transfers; the tables do not.

## The pattern: app-level canvases reuse the document row schema

`scratchpad` and `calendar` are drawing surfaces that live in the index. Rather than invent a storage
model for them, both tables are **column-for-column identical to the `.soil` object table** (universal
row + the full columnar payload set + binary `blob`). A row round-trips through the same in-memory
object type via a zero-logic converter, which means every object serializer, every columnar
read/write mapping, every stroke codec path, and every lasso/clipboard operation works on them
unchanged.

```sql
CREATE TABLE scratchpad (          -- and `calendar`, identically
    id          TEXT    NOT NULL PRIMARY KEY,
    parentId    TEXT    NOT NULL,
    boundingBox TEXT    NOT NULL,
    "order"     INTEGER NOT NULL DEFAULT 0,
    createdAt   INTEGER NOT NULL,
    updatedAt   INTEGER NOT NULL,
    deletedAt   INTEGER,
    type        TEXT    NOT NULL,
    data        TEXT    NOT NULL
    -- + the v6 columnar columns: x, y, width, height, text, color, strokeWidth, refId, level,
    --   lineStyle, orientation, dotSpacing, shapeType, centerX, centerY, rotationDeg, pointCount,
    --   contentW, contentH, linkTarget, chrome, flags, blob
);
CREATE INDEX idx_scratchpad_parent_order ON scratchpad(parentId, "order", deletedAt);
```

Note that these tables **do** have `"order"` and `boundingBox` — they are document-shaped, not
index-shaped. `"order"` is a SQLite reserved word and must be quoted in every hand-written statement.

> ⚠️ **Columnar tables need columnar writers.** A generic "update the JSON payload" helper is a **dead
> write** against a columnar row: the update reports success and silently does nothing. This shipped
> twice — once as "moved objects snap back on reload", once as "resize/rotate snaps back" — because
> these two tables were widened to columnar while three call sites still wrote through the legacy JSON
> path. If you keep both paths during a migration, make the legacy writer **fail loudly** on a
> columnar row.

### `scratchpad` — an always-available multi-page surface

Root row `scratchpad_root` (sentinel id, `parentId = ""`), then `page` rows under it, then `layer`,
then content. Page ids are ordinary UUIDs; page order is the `"order"` column.

### `calendar` — deterministic page keys

Same hierarchy, but the page rows are **keyed by what they represent** rather than by a random UUID.
The row id *is* the key:

```
cal-month-YYYY-MM             one page per month
cal-week-<ISO date of Sunday> one page per week
cal-day-<ISO date>-AM         two pages per day
cal-day-<ISO date>-PM
cal-daynote-YYYY-MM-DD        the day-window note canvas
```

This makes "get me the page for March 2027" a primary-key lookup with no lookup table, and makes page
creation idempotent.

> ⚠️ **Format deterministic keys with a fixed locale.** Formatting `%04d-%02d` under the device's
> default locale writes Eastern-Arabic digits on `ar`/`fa`/`bn` locales. The user switches their
> device language and every previously-written month page becomes unreachable — orphaned, not lost,
> which is worse because nothing errors. Any string that is *both* a formatted number and a database
> key must be formatted with the root/invariant locale, and so must any `LIKE` pattern that matches
> such keys.

## `notebook_activity` — an append-only telemetry log

```sql
CREATE TABLE notebook_activity (
    id           TEXT    NOT NULL PRIMARY KEY,
    notebookId   TEXT    NOT NULL,
    activityType TEXT    NOT NULL,   -- 'OPENED' | 'EDITED'
    timestamp    INTEGER NOT NULL
);
CREATE INDEX index_notebook_activity_activityType_timestamp ON notebook_activity(activityType, timestamp);
CREATE INDEX index_notebook_activity_notebookId              ON notebook_activity(notebookId);
```

Powers "which documents did I open/edit on this day". Two design notes worth stealing:

- **Only forward-looking facts are logged.** "Created" is *derived* from the document row's
  `createdAt` and is never written as a log row — so enabling the feature doesn't require
  backfilling history that doesn't exist.
- **It holds ids and verbs, never names or content.** The name is resolved against `objects` at read
  time, which means a renamed document's history renames with it and a deleted one's history
  disappears.

## `events` — a bespoke, query-optimized table

Calendar events (birthdays, anniversaries, vacations, meetings, appointments). This table
**deliberately breaks the universal row pattern**, and the reason is a good rule of thumb:

> Promote a field to a column when the *database* must answer a question about it. Leave it in the
> payload when only the app cares.

Events are queried by date-range overlap across the whole table, every time a month grid is drawn. So
the range fields are columns:

```sql
CREATE TABLE events (
    id            TEXT    NOT NULL PRIMARY KEY,
    type          TEXT    NOT NULL,   -- BIRTHDAY | ANNIVERSARY | VACATION | MEETING | APPOINTMENT | OTHER
    title         TEXT    NOT NULL,
    startEpochDay INTEGER NOT NULL,   -- local epoch-day
    endEpochDay   INTEGER NOT NULL,   -- inclusive; == start for a single-day event
    allDay        INTEGER NOT NULL,
    startMinute   INTEGER,            -- minute-of-day 0–1439, NULL when all-day
    endMinute     INTEGER,
    recurring     INTEGER NOT NULL,   -- mirrors (data.recurrence != null)
    data          TEXT    NOT NULL,   -- the payload JSON
    createdAt     INTEGER NOT NULL,
    updatedAt     INTEGER NOT NULL,
    deletedAt     INTEGER
);
CREATE INDEX index_events_startEpochDay_endEpochDay ON events(startEpochDay, endEpochDay);
CREATE INDEX index_events_recurring                 ON events(recurring);
CREATE INDEX index_events_deletedAt                 ON events(deletedAt);
```

The `recurring` column is pure denormalization with a purpose: a recurring event's occurrences cannot
be found by SQL range overlap (its stored span is only the *anchor* occurrence), so the query is
"non-recurring rows overlapping this range, **plus** every recurring row expanded in memory". That
`plus` needs a cheap way to pull only the rows worth expanding.

The `data` payload carries what SQL never filters on: an RRULE-like recurrence rule (frequency,
interval, weekday set, monthly mode, end condition, and a list of removed occurrence dates), free-text
notes, and look-ahead lead times. Every field added to it since v5 has an **empty default**, so
existing rows deserialize unchanged and no migration was needed — the same forward-compatibility
technique the document container uses.

Three semantics lessons paid for on-device, all of which generalize to any recurrence model:

1. **Editing "all events in a series" must preserve the series anchor.** If the editor is prefilled
   from the *tapped occurrence* and the user changes only the title, writing the prefill back verbatim
   re-anchors the series to that occurrence — silently erasing every occurrence before it (a
   birthday loses its birth year). Only a deliberately changed date may re-anchor.
2. **An override or a split must carry the whole payload forward.** Building a fresh payload object
   for the new row drops every field the user set on the original.
3. **Validate that an event can occur at all.** An "ends on" date before the start date produces a row
   that appears on no day — and is therefore uneditable and undeletable forever.

## `tasks` — bespoke *and* fully columnar

The task manager's store: one-time and recurring to-do items (see [`tasks.md`](tasks.md)). Like
`events` it is query-shaped rather than universal-row, but unlike `events` it carries **no `data`
payload at all** — the recurrence rule lives in typed columns, so nothing in this table is ever JSON.

```sql
CREATE TABLE tasks (
    id               TEXT    NOT NULL PRIMARY KEY,
    parentId         TEXT,              -- routine id; NULL today (reserved)
    type             TEXT    NOT NULL,  -- TASK | ROUTINE
    title            TEXT    NOT NULL,
    state            TEXT    NOT NULL,  -- NOT_DONE | DONE | SKIPPED
    dueEpochDay      INTEGER,           -- local epoch-day; NULL = undated
    "order"          INTEGER NOT NULL DEFAULT 0,
    seriesId         TEXT,              -- shared by every row generated from one rule
    seriesIndex      INTEGER,           -- 0-based position; drives the COUNT end mode
    seriesAnchorDay  INTEGER,           -- the series' ORIGINAL first due day
    recurFreq        TEXT,              -- NULL = one-time
    recurInterval    INTEGER,
    recurWeekdays    INTEGER,           -- ISO weekday bitmask, Mon = bit 0
    recurMonthlyMode TEXT,
    recurEndMode     TEXT,              -- NEVER | UNTIL | COUNT
    recurEndEpochDay INTEGER,
    recurEndCount    INTEGER,
    resolvedAt       INTEGER,           -- ms the row was completed/skipped
    createdAt        INTEGER NOT NULL,
    updatedAt        INTEGER NOT NULL,
    deletedAt        INTEGER
);
```

**Why this one can drop the payload when `events` could not.** An event stores a single anchor row and
expands its occurrences in memory at read time, which forces it to carry an open-ended list of removed
occurrence dates — a genuinely set-shaped field, and the thing a payload is for. A task series instead
**materializes** its occurrences: exactly one row is open at a time, and resolving it inserts the
successor. There is nothing to except out of, so the only remaining set — the weekly weekday
selection — collapses into an integer bitmask and every field becomes a column.

The lesson generalizes past this schema: *expansion forces a payload; materialization does not.*
Which model to pick is a product question (a calendar wants to show every future occurrence at once; a
to-do list wants exactly one), and the storage shape follows from it rather than the reverse.

Two more semantics lessons, both paid for in this table:

1. **A count is a count of rows, not of calendar positions.** Resolving COUNT the way the events
   engine does — enumerate the first *N* valid dates — silently truncates a materialized series the
   moment the user runs late: a daily "3 times" series started Jan 1 but finished Jan 5 finds no
   enumerated start after Jan 5 and ends after one occurrence. Enforce it by series index, and run the
   date walk with the count stripped.
2. **A recurrence look-ahead bound that is too tight does not error — it silently ends the series.**
   Size it per frequency *and* interval, generously: monthly-on-the-31st must clear a 59-day gap
   (Jan 31 → Mar 31), and yearly-on-Feb-29 must reach eight years to clear a skipped century.

`type` + `parentId` are reserved for **routines** (a named set of tasks). Nothing writes them today,
but every query filters `type = 'TASK'` so those rows cannot leak into the task list when they arrive.
A greenfield implementation that does not need routines can drop both columns.

---

# Part V — Schema Versions & Migration

## Index version history

| Version | Change | Migration shape |
|---|---|---|
| 1 | Base `objects` table | — |
| 2 | `+ scratchpad` table | `CREATE TABLE IF NOT EXISTS` |
| 3 | `+ calendar` table | `CREATE TABLE IF NOT EXISTS` |
| 4 | `+ notebook_activity` table | `CREATE TABLE IF NOT EXISTS` |
| 5 | `+ events` table | `CREATE TABLE IF NOT EXISTS` |
| 6 | `scratchpad` + `calendar` gain the 23 columnar columns + `blob` | `ALTER TABLE … ADD COLUMN` ×2 tables |
| 7 | `objects` gains `pageCount`, `flags`, `keyScope`, `lastBackedUpLocal`, `lastBackedUpDrive`, `width`, `height`, `blob` | 8 × `ALTER TABLE … ADD COLUMN` |
| 8 | `objects` gains `refId`, `sortOrder` (list membership as child rows) | 2 × `ALTER TABLE … ADD COLUMN` |
| 9 | `+ tasks` table (fully columnar — no `data` payload) | `CREATE TABLE IF NOT EXISTS` |

**Every migration is additive and rewrites zero rows.** Nullable columns and
`CREATE TABLE IF NOT EXISTS`, nothing else. Data conversion happens lazily on write plus an optional
background/manual sweep — never inside a migration, because a migration runs on the critical path of
the user's launch, and a migration that fails halfway is a support case with no good outcome.

The same four-step strategy the document container uses applies verbatim: additive DDL →
format-agnostic readers → convert on write → sweep in the background. See `soil-file-format.md`
Part VI.

## The `user_version` hazard applies here too

The index is re-keyed via the same `sqlcipher_export` round-trip as a document (global rotation
re-keys the index first). **`sqlcipher_export` does not copy `PRAGMA user_version`.** An exported
database keeps the target's default version of 0, and an ORM that keys off the schema version will
read version 0 as "brand-new database", run its create path, validate the pre-existing older tables
against the current entity definitions, and **reject the file** rather than migrating it. That bricks
an otherwise perfectly intact library.

Carry `user_version` across **every** export round-trip — encrypt, decrypt, and re-key. This is
spelled out with the recovery procedure in `soil-file-format.md` Part VII; it is repeated here only
because the index is the file where the consequence is worst.

---

# Part VI — Encryption

> The cryptographic primitives — KDF, key encoding, raw-key caching, key scopes, recovery-key
> generation, rate limiting, the non-destructive open helper — are specified in
> `soil-file-format.md` **Part VII** and are **identical** for the index. This part covers only what
> is specific to indexing: which key the index uses, how it opens, how it re-keys, and what is and is
> not allowed to be cached inside it.

## The index is encrypted, always, under the global key

Encrypt-everything-by-default. On a fresh install the app mints a random 160-bit recovery key with no
user interaction and creates the index **encrypted from the first byte**. There is no plaintext index
for a new install, and no user-facing setting that turns index encryption off.

The consequence to internalize: **the index is not a "safe" place to put things.** It is exactly as
protected as a global-scope document, no more. Everything in [Part I](#the-five-index-invariants)
about content leakage still holds, because the threat model that matters — a per-document passphrase
the user chose *specifically so that this content is not readable with the global key* — is not
addressed by the index's own encryption.

## Key resolution

The index is treated as one more keyed file, identified by a stable synthetic file id (Notesprout uses
`__notesprout_index__`) in the same derive-once raw-key cache that documents use:

```
process RAM  →  Keystore-backed store (GLOBAL scope only)  →  derive + persist
```

The first open of a newly created index derives and caches the raw key immediately, so the very next
cold launch skips the KDF. That matters more here than for any document: the index open is on the
critical path of *every* launch, and a 300–700 ms KDF there is the entire perceived startup time.

## The open state machine

```
recoverInterruptedMigration(notesprout.db)   ← ALWAYS FIRST. See below.
probe(notesprout.db):
  Invalid    → fresh install (or empty file): mint/read the global key, create ENCRYPTED from the
               start, then derive + cache its raw key
  Plaintext  → existing user upgrading: migrate the schema while STILL PLAINTEXT, close, encrypt in
               place, derive + cache the raw key, reopen encrypted
  Encrypted  → cached global passphrase?
                 yes → derive/fetch raw key → verify → open
                       verify fails (rotated on another device, restored from a foreign backup)
                         → invalidate the cached key → NEEDS_UNLOCK
                 no  → NEEDS_UNLOCK
NEEDS_UNLOCK → prompt → verify passphrase → cache as the global passphrase → derive raw key → open
```

Four ordering rules, each of which was a bug before it was a rule:

1. **Repair before probing.** An in-place migration (the upgrade encrypt, or a rotation re-key) that
   was killed mid-swap can leave the index file *absent* with its data sitting under an aside name. A
   probe of a missing file returns `Invalid`, and `Invalid` means "fresh install" — which would mint a
   brand-new empty index and silently replace the user's entire library structure. Always run the
   mid-swap recovery first. (The same sweep runs over the document directory at launch.)

2. **Migrate the schema while still plaintext, then encrypt.** Migrating an encrypted file is
   strictly harder for no benefit.

3. **Verify the cached key before trusting it.** A cached raw key that no longer opens the file is
   indistinguishable from corruption at the SQLite layer. Verify, and on failure invalidate the cache
   and fall through to the unlock prompt — never let a stale key reach the corruption handler.

4. **Wrap every open in the non-destructive helper factory — including the plaintext one.** The
   default corruption handler **deletes and recreates the database**. For a document that loses a
   notebook; for the index it loses the user's entire library structure, replaced by an empty file
   that looks like a fresh install. The plaintext upgrade path is not exempt: a legacy index that is
   genuinely damaged must surface as an error, not be silently replaced with an empty one.

## Re-keying the index (global rotation)

Rotating the global passphrase re-keys the index and every global-scoped document. For the index
specifically:

```
hold the open/close mutex for the whole operation
  → if the file already opens with the NEW passphrase, skip the round-trip (a prior run finished it)
  → checkpoint the WAL and close the live connection      ← required; the round-trip needs a sealed file
  → rekey in place (export round-trip, NOT `PRAGMA rekey` — unreliable on-device)
      on failure: the file is still under the OLD key → invalidate, reopen under the old key,
                  then surface the error. The index must never be left closed.
  → invalidate the cached raw key (the salt changed) → re-derive against the new salt → reopen
```

The cached *global passphrase* is **not** updated here. The rotation orchestrator updates it only
after every pending target has been re-keyed, so that a partial rotation leaves the cache holding the
old value and already-rotated files fall through to a prompt-and-re-cache rather than silently
failing. Every step is idempotent, which is what makes crash resumption trivial instead of a
reconciliation problem.

## What may be cached in the index

This table is the whole leak-hygiene policy for the index. It changed when the index became
encrypted, and the change is easy to get wrong in both directions.

| Artifact | Rule |
|---|---|
| Cover image of an **unencrypted** document | Stored. |
| Cover image of a **GLOBAL-scope** encrypted document | **Stored.** The index is itself encrypted under that same global key, so the cover is protected by exactly the key that protects the document. Suppressing it would buy no security and cost every library card its thumbnail. |
| Cover image of a **NOTEBOOK-scope** (private-passphrase) document | **Never.** This is the case the rule exists for: the user chose a separate passphrase precisely so this content is *not* readable with the global key. The setter refuses the write, and converting a document to private scope clears any existing cover in the same write. Cards show a lock instead. |
| Any document text, recognized handwriting, or search term | **Never**, at any scope. There is no content search index, by construction. |
| Passphrases, raw keys, recovery keys, OAuth tokens | **Never.** Secrets live only in the platform-keystore-backed stores ([Part VIII](#part-viii--stores-outside-both-databases)). |
| Document display names | Stored, unavoidably — they are the library. See the honest limitation in [Appendix B](#appendix-b--known-divergences--open-questions). |

The general principle: **once encryption is universal, "does this leave the encrypted zone?" is the
wrong question** — nothing does. The right question is "does this cross a *key boundary*?" Only the
private-scope case does. After an encrypt-everything migration, audit for per-operation warnings
that were written under the old model; they have stopped being warnings and started being noise.

---

# Part VII — Durability, Backup & Restore

## WAL configuration

Applied on **every** open — `wal_autocheckpoint` is connection-level and is not persisted in the file:

```sql
PRAGMA journal_mode       = WAL;
PRAGMA wal_autocheckpoint = 100;
PRAGMA auto_vacuum        = INCREMENTAL;   -- one-time; followed by a single VACUUM to take effect
```

PRAGMAs that return a result set must be issued as a query whose cursor is actually stepped, never as
a bare statement execution — the latter silently does not run them. This bites once per codebase.

Because the index never closes during normal use, it is checkpointed opportunistically rather than at
a seal: on the library screen going to the background, run `incremental_vacuum` +
`wal_checkpoint(TRUNCATE)`. Full sidecar cleanup happens only in the shutdown seal.

## Backup

Backup is manual-trigger and incremental-by-timestamp; documents are copied under their UUID
filenames so a rename never orphans a backup. Three rules concern the index:

1. **The index is copied last.** Documents first, then checkpoint the index, then copy the index. The
   backed-up index therefore describes a *completed* run. Copy it first and it records backup
   timestamps for copies that hadn't happened yet — a restore would then believe files were backed up
   that never were.

2. **Snapshot the index locally before streaming it anywhere.** The index stays open and writable
   during the (slow, possibly cloud) transfer, so streaming the live file can capture a torn state.
   Copy it to a local staging path, sanity-probe the copy, then stream the copy. If the snapshot
   fails, streaming the live file is the degraded fallback — but log it.

3. **Write to a temporary name and swap.** A mid-write failure must leave the *previous good* backup
   intact, never a half-written file under the real name.

Documents are backed up as **ciphertext byte copies** — no prompt, no decryption. One subtlety worth
carrying over: a document that could not have its WAL absorbed at backup time (an encrypted file with
no key available) must have its `-wal` sidecar backed up **alongside** the main file, and both must
land before the "backed up" timestamp is stamped. Otherwise a restore pairs a fresh main file with a
stale sidecar, which is corruption. Conversely, when the WAL *was* absorbed, any stale sidecar at the
destination must be deleted.

## Restore

Restore is **staging-first, replace-all** — never a merge:

```
fetch everything to a staging area   (per-file .part + rename; abort the whole run on any single
                                      failure; hard-fail on insufficient free space)
  → probe the staged index AND every staged document before touching the live library
  → commit by aside-rename swap, with the installed index as the commit marker
  → on failure: roll back and reopen the previous index
  → clear cached key state only AFTER the commit succeeds
  → restart into the unlock flow
```

The restart matters: a restored index may be keyed to a **different global secret** than this device
currently caches (it came from another device, or from before a rotation). The unlock flow is the only
correct way back in, and the cached-key invalidation must not happen before the commit — otherwise a
failed restore leaves the user locked out of the library they still have.

A mid-commit process kill is repaired at the next launch by the same aside-name recovery that repairs
an interrupted in-place migration.

---

# Part VIII — Stores Outside Both Databases

A complete picture of an install's state includes what lives in neither database. All of these are
device-local, none are backed up, and none may ever hold document content.

## Keystore-protected stores (secrets)

Built on platform-encrypted preference files with a hardware-backed master key — three separate
files, so that clearing one (a key cache) never disturbs another (the passphrase itself):

| Store | Holds |
|---|---|
| Secure prefs | The **global passphrase**; the in-progress rotation marker (pending file ids + the new passphrase); the bulk-conversion marker; the failed-attempt counters that drive lockout |
| Derived-key store | The 32-byte SQLCipher **raw keys** for global-scope files, keyed by file id |
| Cloud token store | OAuth refresh/access tokens for the cloud backup destination |

Rules, all non-negotiable:

- **A NOTEBOOK-scope (private) key is never persisted here.** It lives in process RAM only and is
  dropped on close. That is the entire difference between the two scopes.
- **Creation of these files must be serialized and cached.** Two threads first-creating the same
  keystore-backed preference file is a known crash/corruption mode, and the crypto stores are hit
  from independent threads (bootstrap, warm-up, unlock prompts). One lock, one cached instance per
  file. Add one retry on top: the platform keystore throws transiently right after device boot,
  before the user has unlocked the device for the first time.
- **Minting the global key must be synchronized too.** Two concurrent first-callers can otherwise
  mint two different global secrets, and the loser's write strands whatever the winner already
  encrypted behind a passphrase the user was never shown.
- **Nothing here is ever logged.** Not the value, not a prefix, not a length.

## Plaintext preference files (settings and pointers)

Ordinary preference files: recents, template recents, toolbar layout, export presets, link back-stack,
onboarding flags, last calendar position, recognition settings.

The rule that keeps these safe is worth stating explicitly, because it is easy to violate by accident:
**these files store ids and settings, never names and never content.** A recents entry is
`(documentId, timestamp)`; the display name and folder breadcrumb are resolved against the encrypted
index at read time, with a self-healing prune for ids that no longer resolve. Storing the name instead
would have been marginally faster and would have leaked the user's document titles into a plaintext
file readable by anything that can read the app's data directory.

## Out of scope: the handwriting-training database

Notesprout has a third SQLCipher database holding handwriting-recognition training data. It is
**deliberately not specified here** — Paintsprout will not implement it, and it will be documented
when another Sprout project needs it. It is mentioned only so that a reader who finds a third
database file is not surprised. It follows the same encryption and key-caching model as the index.

---

# Part IX — Adapting This Index for Paintsprout

## What you MUST keep

1. **The two-database split**, with all structure in the index and none on the filesystem, and the
   document directory flat and UUID-named.
2. **The universal row shape** in the index object table — same names, same semantics, epoch-ms
   integers, soft delete via `deletedAt`, string `type` discriminator — so container-level code is
   genuinely shared with the document container rather than reimplemented.
3. **Enough per-document metadata on the index row to render any list without opening a file** —
   including the encryption flag and key scope. This is a correctness requirement, not an
   optimization: deciding whether to prompt for a key must not require the key.
4. **"No content in the index"** as a structural invariant, with cover images as the single, explicitly
   reasoned exception governed by key scope.
5. **The encryption model, key scopes, and key lifecycle** exactly as in `soil-file-format.md`
   Part VII, with the index as one more keyed file in the same raw-key cache under its own stable
   file id.
6. **The open state machine**, including repair-before-probe, verify-before-trusting-a-cached-key, and
   the non-destructive open helper on *every* path.
7. **The bootstrap gate** — nothing reads the index until it is open, because opening can be async
   and interactive.
8. **Idempotent sentinel bootstrap** rather than data-inserting migrations.
9. **Additive-only migrations** with lazy conversion, and `user_version` carried across every
   `sqlcipher_export` round-trip.
10. **Backup ordering: documents first, index last, index snapshotted before it is streamed.** And
    restore as staging-first replace-all with the index as the commit marker.
11. **The `updatedAt` discipline** — only real modifications bump it, because it drives backup.

## What you MUST replace

- **The index type catalog.** `notebook` becomes your content type. Keep `folder`, the list/membership
  pair, and the singleton config rows; they are container furniture, not Notesprout concepts.
- **The app-content tables.** `scratchpad` / `calendar` / `events` / `notebook_activity` / `tasks` are
  Notesprout's app-level surfaces. Paintsprout will have its own — a palette library, a brush library,
  a reference-image shelf. Keep the *pattern*: an app-level canvas reuses the document row schema
  verbatim so every serializer works unchanged; an app-level record with range queries promotes its
  queryable fields to columns — and leaves the rest in a payload only if some field is genuinely
  open-ended (compare `events` with `tasks`, which needs no payload because it materializes rather
  than expands).
- **The template library shape.** Paintsprout's equivalent (brushes, palettes, canvas presets)
  probably wants more than `width`/`height`/`image`. Add typed columns; the wide-sparse table makes
  that free.
- **Sibling ordering.** If your tree is user-orderable, add an `"order"` column to the index object
  table rather than reusing list-membership `sortOrder`.

## Mistakes not to repeat

1. **Do not let a missing index file mean "fresh install."** Probe only after repairing a possible
   interrupted swap. This one silently destroys the entire library.
2. **Do not use the default corruption handler anywhere.** Wrong key looks exactly like corruption,
   and the default deletes.
3. **Do not widen a table to columnar without converting every writer.** The legacy JSON writer
   becomes a silent no-op, and the symptom ("my edits revert on reload") reads as a UI bug for weeks.
4. **Do not format a database key with the default locale.**
5. **Do not bump `updatedAt` for storage-format or bookkeeping writes.**
6. **Do not put content in the index "just for search."** Once it is there, "search leaks plaintext"
   is a design problem forever.
7. **Do not store display names in plaintext preference files.** Store ids; resolve at read.
8. **Do not create keystore-backed preference files from multiple threads unserialized.**
9. **Do not tombstone membership edges.** Hard-delete them; they are not user history.

## Suggested build order

The index is step 3 in the overall build order in `soil-file-format.md` Part XI. Within it:

1. `objects` table + repository + the folder tree, plaintext, no encryption yet.
2. **The non-destructive open helper wrapper.** Before any key exists.
3. The bootstrap gate + ready latch.
4. Encryption: global key mint, raw-key cache, the open state machine, unlock UI.
5. Sentinel singletons (lists, config) via idempotent bootstrap.
6. App-content tables, reusing the document row schema.
7. Backup (documents → index) then restore (staging-first).
8. Compaction sweep — last, once you have a legacy shape to sweep.

---

# Appendix A — Quick Reference Tables

## `objects` columns

| Column | SQL type | Null? | Meaning |
|---|---|---|---|
| `id` | TEXT | no | UUIDv4 or sentinel; primary key |
| `type` | TEXT | no | String discriminator |
| `name` | TEXT | no | Display name (top-level, unlike `.soil`) |
| `parentId` | TEXT | **yes** | NULL = root |
| `createdAt` | INTEGER | no | Epoch ms |
| `updatedAt` | INTEGER | no | Epoch ms — drives the backup predicate |
| `deletedAt` | INTEGER | yes | NULL = alive |
| `data` | TEXT | no, default `'{}'` | Legacy JSON; `""` on columnar rows; still the payload for the JSON singletons |
| `pageCount` | INTEGER | yes | Document |
| `flags` | INTEGER | yes | Document bitfield |
| `keyScope` | TEXT | yes | `GLOBAL` / `NOTEBOOK` |
| `lastBackedUpLocal` / `lastBackedUpDrive` | INTEGER | yes | Per-destination epoch ms |
| `width` / `height` | INTEGER | yes | Template |
| `blob` | BLOB | yes | Cover / template image bytes (WEBP q100) |
| `refId` | TEXT | yes | `list_item` → member id |
| `sortOrder` | INTEGER | yes | `list_item` → position |

## Bitfields

| Type | Bit | Value | Meaning |
|---|---|---|---|
| document | 0 | 1 | encrypted |
| document | 1 | 2 | excludeFromBackup |

## Tables at a glance

| Table | Since | Row shape | Purpose |
|---|---|---|---|
| `objects` | v1 | Universal (index variant) | Structure, documents, template library, lists, config singletons |
| `scratchpad` | v2 | **Document** row schema | App-level scratch canvas |
| `calendar` | v3 | **Document** row schema | App-level calendar canvases, deterministic page keys |
| `notebook_activity` | v4 | Bespoke, append-only | Open/edit telemetry (ids + verbs only) |
| `events` | v5 | Bespoke, query-optimized | Calendar events + recurrence payload |
| `tasks` | v9 | Bespoke, **fully columnar** | To-do items + materialized recurrence series |

## Sentinel ids

| Purpose | Last UUID group | Decodes to |
|---|---|---|
| Pinned documents list | `70696e6e6564` | `pinned` |
| Pinned templates list | `746d706c7069` | `tmplpi` |
| Clipboard singleton | `636c69706264` | `clipbd` |
| Backup config singleton | `6261636b7570` | `backup` |
| Scratch-pad root | `736372746368` | `scrtch` |
| Calendar root | `63616c6e6472` | `calndr` |

All are `00000000-0000-0000-0000-<group>`.

## Crypto

Identical to the document container — see `soil-file-format.md` Appendix A. Index-specific values:

| | |
|---|---|
| Key | The **global** passphrase, always |
| Raw-key cache id | A fixed synthetic file id (Notesprout: `__notesprout_index__`) |
| Raw key persisted? | Yes (keystore-backed) — the index is always global scope |
| Plaintext index supported? | Only as a one-time upgrade input; never created |

---

# Appendix B — Known Divergences & Open Questions

## Honest limitation: document names are visible at global scope

A NOTEBOOK-scope document's *content* is unreadable without its own passphrase — but its **name, page
count, folder location, and timestamps live in the index**, which opens with the global key. Anyone
who can unlock the library can see that "Therapy Journal" exists, how many pages it has, and when it
was last touched; they simply cannot open it.

This is a deliberate trade: the alternative is a library that cannot render a list of its own
contents without prompting for every private document's passphrase. It is documented here so that no
one later mistakes it for an oversight, and so that a Sprout app with a stronger requirement knows
exactly which field to move (`objects.name`, plus the per-document metadata columns) into the
document file itself.

## Undecided: who owns a mixed file's index row?

The document container permits a single `.soil` to carry more than one app's object table (see
`soil-file-format.md` Appendix B). The index, however, has **one row per document with one `type`**. A
file holding both a `notebook` and a `sketchbook` table needs one of: a compound type, two rows
pointing at one file, or a rule that the index records only the content type the running app
understands. Unresolved — and it must be decided before either app ships an importer that can receive
the other's files.

## Undecided: cross-app index interchange

Restore is whole-library replace-all within one app. There is currently no story for handing
*structure* between Sprout apps — if a user has both Notesprout and Paintsprout, they have two
independent indexes, two folder trees, and two backup destinations, even if the documents sit side by
side. A shared index is possible (the object table is generic enough) but nothing about it is
designed. Decide deliberately rather than by accident.

## Legacy shapes a greenfield app should omit

- The `data` column on `objects`, except as the payload for the JSON config singletons.
- The `boundingBox` column on the app-canvas tables (it survives in the document row schema for the
  same lazy-coexistence reason).
- The inline-JSON list membership shape (`{"notebookIds":[…]}`) — implement child rows only.

## Not specified here

The handwriting-training database ([Part VIII](#out-of-scope-the-handwriting-training-database)) and
everything about recognition models and training bundles. Out of scope for Paintsprout by agreement;
to be specified when a Sprout project needs it.

# Data Architecture — Global Index & `.soil` Files

> Referenced from `CLAUDE.md`. Covers the global index (`notesprout.db`) and the per-notebook
> `.soil` databases.

## Foundational Decisions

- Notebook = a `.soil` file (SQLite database with `.soil` extension)
- Notebook files live in `getExternalFilesDir(null)/Garden/<uuid>.soil` — flat directory, UUID filenames, no permissions required
- Folder/notebook structure is maintained exclusively in the global index (`notesprout.db`) — never derived from the filesystem
- **`soilFile(context, notebookId)` (`data/SoilFile.kt`)** is the single canonical way to derive a `.soil` path. No other code constructs a `.soil` path.
- Hierarchy: Notebook → Pages → Layers → Content Objects
- Layers: base layer (template, locked) and content layers
- Every object carries: id, parentId, boundingBox, order, createdAt, updatedAt, deletedAt, data
- Stroke data: point arrays (x, y; optional pressure/tilt) stored as a **binary blob** — float32 + zlib via `core/StrokeCodec`, ~5× smaller than the legacy JSON. Colour/width live in row columns. (Pre-v4 rows may still carry JSON in `data`; readers are format-agnostic. See **Schema Version 4** below.)
- Soft deletes with cleanup process; stable UUIDs everywhere
- Activities receive notebook identity as `EXTRA_NOTEBOOK_ID` (entity UUID) + `EXTRA_NOTEBOOK_NAME` — never a `File` object

---

## Global Index (`notesprout.db`)

Room/SQLite at `getExternalFilesDir(null)/notesprout.db`. Owns the entire folder/notebook tree — the `Garden/` directory is flat blob storage, not a source of structure.

### Schema (`objects` table)

```sql
CREATE TABLE objects (
    id         TEXT    PRIMARY KEY NOT NULL,
    type       TEXT    NOT NULL,
    name       TEXT    NOT NULL,
    parentId   TEXT,
    createdAt  INTEGER NOT NULL,
    updatedAt  INTEGER NOT NULL,
    deletedAt  INTEGER,
    data       TEXT    NOT NULL DEFAULT '{}'
);

CREATE INDEX idx_objects_parent_type_deleted
    ON objects(parentId, type, deletedAt);
```

### Auxiliary tables (same `notesprout.db`, Room `version = 5`)

Beyond `objects`, the global index DB holds several auxiliary tables. **The index itself is
SQLCipher-encrypted at rest** under the global passphrase (encrypt-everything-by-default) — see
[`docs/encryption.md`](encryption.md#the-global-index-is-encrypted). The two object-canvas tables
share the universal row schema
(`id/parentId/type/boundingBox/order/createdAt/updatedAt/deletedAt/data`) so every `.soil` object
serializer works unchanged.

- **`scratchpad`** — added in `MIGRATION_1_2`. See [`docs/scratchpad.md`](scratchpad.md).
- **`calendar`** — added in `MIGRATION_2_3` (keyed pages: month/week/day-AM/day-PM). See
  [`docs/calendar.md`](calendar.md).
- **`notebook_activity`** — added in `MIGRATION_3_4`; OPENED/EDITED telemetry for the Day-window
  Notebooks/History views. See [`docs/calendar.md`](calendar.md).
- **`events`** — added in `MIGRATION_4_5`; calendar Events (birthdays/anniversaries/appointments) with
  RRULE-like recurrence. Own column schema (not the universal row shape). See
  [`docs/calendar.md`](calendar.md#events--the-events-table).

`NotesproutDatabase` (`@Database entities = [ObjectEntity, ScratchpadEntity, CalendarEntity,
NotebookActivityEntity, EventEntity]`, `version = 5`) registers all migrations in `NotesproutIndex`.

### Key Classes

- `ObjectEntity` (`data/index/ObjectEntity.kt`) — Room entity; universal index row
- `ObjectType` (`data/index/ObjectType.kt`) — `FOLDER`, `NOTEBOOK`, `LIST`, `TEMPLATE`, `TEMPLATE_FOLDER`
- `FolderObject`, `NotebookObject`, `ListObject` — `@Serializable` data classes in `data` column. `NotebookObject` carries `snapshot: String?`, `pageCount: Int`, `encrypted: Boolean` (default `false`), and `keyScope: KeyScope?` (non-null only when `encrypted == true`). `ListObject` carries `notebookIds: List<String>` (array order = display order).
  - **Snapshot suppression:** `snapshot` is **always `null`** for encrypted notebooks. `IndexRepository.updateNotebookSnapshot` is a no-op when the row has `encrypted = true`; `setEncryptionState(..., encrypted = true)` atomically clears `snapshot` in the same write. Lists and card renders show the lock icon instead. See [`docs/encryption.md`](docs/encryption.md).
- `ListIds` (`data/index/ListIds.kt`) — `PINNED_LIST_ID = "00000000-0000-0000-0000-70696e6e6564"`, `PINNED_TEMPLATES_LIST_ID = "00000000-0000-0000-0000-746d706c7069"`
- `TemplateListObject` (`data/index/TemplateListObject.kt`) — `@Serializable data class TemplateListObject(templateIds: List<String>)`; the `data` payload of the pinned-templates `LIST` object. A parallel to `ListObject` so notebook list code is untouched.
- `ObjectDao` (`data/index/ObjectDao.kt`) — Room DAO for all index queries and mutations
- `IndexRepository` (`data/index/IndexRepository.kt`) — higher-level API: create/rename/softDelete/move for folders and notebooks; list ops: `ensurePinnedListExists`, `getPinnedList`, `addNotebookToList`, `removeNotebookFromList`, `reorderList`, `getNotebooksInList`, `scrubNotebookFromAllLists`; pin helpers: `isNotebookPinned(notebookId)`, `togglePin(notebookId)`
- `NotesproutIndex` (`data/index/NotesproutIndex.kt`) — singleton managing `notesprout.db`; `open(context)` in `Application.onCreate`, `seal()` on shutdown

### Rules

- `parentId = null` means root
- Soft-deletes only — set `deletedAt`; never hard-delete without deliberate GC
- All writes go through `IndexRepository`; direct DAO use limited to reads in `MainActivity` load paths
- `NotesproutIndex` must be opened before any Activity accesses it; `NotesproutApplication.onCreate` is the correct place
- **List bootstrap:** `NotesproutApplication.onCreate` calls `repository.ensurePinnedListExists()` on `appScope` after `NotesproutIndex.open()` — idempotent, safe every launch
- **Scrub-on-delete:** `deleteNotebook()` and `deleteFolderRecursively()` call `repository.scrubNotebookFromAllLists(notebookId)` before soft-deleting, so list rows never contain dangling references
- **G10 ADB pull:** `adb -s 34E517F9 pull /sdcard/Android/data/com.notesprout.android.dev/files/notesprout.db /tmp/notesprout.db`

### Templates (library)

The reusable **template library** lives in the global index — not the filesystem, not any `.soil`.

- **Types:** `TEMPLATE` (`"template"`) and `TEMPLATE_FOLDER` (`"template_folder"`). These do **not**
  collide with the `.soil` `type="template"` rows — those live in each notebook's `notebook` table, a
  different database.
- **Payload** — `TemplateObject` (`data/index/TemplateObject.kt`), `@Serializable`, in the `data` column:
  ```kotlin
  data class TemplateObject(val width: Int = 0, val height: Int = 0, val image: String = "")
  ```
  `image` is the full-resolution image as base64 (`NO_WRAP`), stored in `ObjectEntity.data` — same pattern
  as `NotebookObject.snapshot`. The template **name lives in `ObjectEntity.name`** (the top-level
  column), like notebooks/folders — *not* inside the JSON. (Contrast the `.soil` `TemplateData`, which
  keeps name in JSON; that class is unchanged and still used inside `.soil`.)
- **Template folders** behave like notebook folders: nestable, `parentId = null` at root, movable,
  copyable, renamable, recursively soft-deleted. A separate type — never reuse `FOLDER`. A template
  folder contains only template folders + templates.
- **Repository:** `IndexRepository` `// region Template operations` — `createTemplate`,
  `createTemplateFolder`, `renameTemplate`/`renameTemplateFolder`, `softDeleteTemplate`,
  `deleteTemplateFolderRecursively`, `getTemplate(s)`, `getTemplateFolders`, `getAllTemplates`,
  `copyTemplate`, `copyTemplateFolderRecursively`; moves reuse the generic `moveObject`. All
  index-only — **no `.soil`/file cleanup** on delete (templates are index-only).
- **Apply / seed → `.soil`:** selecting a library template **copies** it into the target `.soil` as a
  new `type="template"` row (`TemplateData` shape). See the Template System section of
  [`drawing-engine.md`](drawing-engine.md).

#### Pinned templates (index)

- **Dedicated list, never the notebook pinned list.** The `PINNED_TEMPLATES_LIST_ID` row is a
  `type = LIST` `ObjectEntity` (name `"Pinned Templates"`, `parentId = null`) whose `data` is a
  `TemplateListObject` JSON. Pin order = `templateIds` list order (newest pin appended last).
- **Only templates are pinnable — never template folders** (mirrors notebooks: folders aren't pinned).
- **Repository:** `ensurePinnedTemplatesListExists` (bootstrapped from `NotesproutApplication.onCreate`
  alongside `ensurePinnedListExists`), `isTemplatePinned`, `toggleTemplatePin` (returns new state),
  `getPinnedTemplates` (resolves ids → entities, skips null/deleted/non-`TEMPLATE`),
  `scrubTemplateFromPinned`. `softDeleteTemplate` scrubs-then-deletes, so deleting a pinned template
  also unpins it.

#### Template recents (device-local)

- **Separate from notebook recents, library-template-only.** Lives in `data/recents/` —
  `TemplateRecentEntry(templateId, timestamp)`, `ResolvedTemplateRecent(templateId, templateName,
  folderPath, timestamp)`, and `TemplateRecentsManager` (`object`, prefs `notesprout_template_recents`,
  `MAX_ENTRIES = 20`). Exact mirror of the notebook `RecentsManager`, but resolves against
  `ObjectType.TEMPLATE` via `IndexRepository.getTemplate` + `getAllTemplateFolders` (breadcrumb root
  `"Templates"`), with a self-healing prune in `resolve` (missing/deleted ids never surface).
- **A "use" is recorded only when a *library* template is actually applied to a page** — exactly two
  sites: `NotebookActivity.onTemplatePicked` (after a successful library-browse apply) and
  `MainActivity.createNotebook` (when seeding a new notebook from a library template). Blank, in-notebook
  re-apply (`.soil` row ids), importing, and "Save as Template" do **not** record (creation ≠ use). The
  repo gets no `Context` — cleanup relies on `resolve`'s prune.

### WAL Maintenance

- `NotesproutDatabase.openCallback()` sets `journal_mode = WAL`, `wal_autocheckpoint = 100`, and runs a one-time `auto_vacuum = INCREMENTAL` + `VACUUM` migration on first open (skipped when already `INCREMENTAL`).
- `NotesproutIndex.checkpointAndVacuum()` — `suspend fun` on `Dispatchers.IO`: `PRAGMA incremental_vacuum` + `PRAGMA wal_checkpoint(TRUNCATE)` via `rawQuery(...).use { it.moveToFirst() }`, never `execSQL`. Called from `MainActivity.onStop()` on `appScope`.
- `notesprout.db` stays open the full app lifetime — its `-wal`/`-shm` sidecars remain on disk (normal WAL behaviour; checkpoint keeps them near-empty). Full cleanup only on `NotesproutIndex.seal()`. This is distinct from the "no stray files" rule for `.soil` files.

---

## Data Layer — `.soil` Files

### Core Rules — Never Violate These

- **One file per notebook.** Each `.soil` file is a self-contained SQLite database.
- **Single table.** Everything — pages, layers, strokes, images, text, metadata — is a row in one `notebook` table.
- **Everything is an object.** No type special-casing at the schema level — type behavior lives in Kotlin.
- **Payload is columnar, not JSON.** As of schema **v4** (the data-model-optimization work) every
  object's payload lives in typed columns + a binary `blob`, not the opaque `data` TEXT column. Stroke
  geometry is a binary blob (float32 + zlib, ~5× smaller than the old JSON); template images are a
  binary WEBP blob (not base64). See **Schema Version 4** below. The legacy `data` column is retained
  (readers are format-agnostic) but new writes leave it `""`; the `NotebookCompactor` converts legacy
  rows lazily on seal, so a fully-swept notebook has **zero JSON** on the object path.
- **Images are WEBP q100.** Template images (and the library-grid cover snapshot in the global index)
  are **WEBP q100** via `core/ImageCodec` — a binary `blob` on a columnar `template` row, or base64 in
  the index. q100 lossy is deliberate: on transparent-alpha ink it measured ~47% smaller than PNG and
  visually lossless, whereas Android's `WEBP_LOSSLESS` bloats to 2–6× PNG. Decode is format-agnostic
  (`BitmapFactory` reads the header) so legacy PNG/lossless-WEBP coexist; `NotebookCompactor` re-encodes
  old ones in place.
- **Decode embedded images bounded.** Route all embedded-asset decodes through `core/BitmapDecode.decodeSampled(bytes, reqW, reqH)` — never `BitmapFactory.decodeByteArray` directly on `.soil`-sourced bytes (OOM risk on e-ink). `MAX_DIMENSION=4096` fallback when there's no natural target.
- **A `.soil` that won't open must never take the app down.** Two layers cooperate here, and both are
  load-bearing:
  - `NonDestructiveOpenHelperFactory` (installed by `SoilDatabase.builder`) refuses to delete on a
    reported corruption. The framework default is delete-and-recreate — that is precisely how a notebook
    was destroyed in the link-picker incident, and a mis-keyed open of an encrypted `.soil` looks exactly
    like corruption. It throws instead, leaving the file byte-intact.
  - `NotebookActivity` then **catches that throw broadly** and reports it via `state/NotebookOpenFailure`,
    so the notebook steps back to the library with an explanation instead of killing the process. This
    matters beyond tidiness: cold launch rebuilds the previous surface stack, so an uncaught open failure
    reopened the same notebook on the next launch and crashed again — an unrecoverable loop whose only
    exit was clearing app data. Failing back to the library is the fix, because `MainActivity.onResume`
    resets the surface stack.
  - Room opens the file **lazily**, so the failure usually surfaces on the *first query*, not at
    `build()`. Any new `.soil` open path needs its guard where the first DAO call happens.
- **SQLite must stay clean.** A file browser should show only `.soil` files — no WAL/SHM/journal sidecars.
  - `PRAGMA journal_mode = WAL`; `PRAGMA wal_autocheckpoint = 100`; `PRAGMA auto_vacuum = INCREMENTAL`
  - Run `PRAGMA incremental_vacuum` + `PRAGMA wal_checkpoint(TRUNCATE)` on clean close

### Object Schema

The v1 shape below is the stable core; **v4** adds the columnar payload columns (see next section).

```sql
CREATE TABLE IF NOT EXISTS notebook (
    id          TEXT    PRIMARY KEY NOT NULL,
    parentId    TEXT    NOT NULL,
    type        TEXT    NOT NULL,
    boundingBox TEXT    NOT NULL,   -- v4: "" for content rows (geometry moved to x/y/width/height)
    "order"     INTEGER NOT NULL DEFAULT 0,
    createdAt   INTEGER NOT NULL,
    updatedAt   INTEGER NOT NULL,
    deletedAt   INTEGER,
    data        TEXT    NOT NULL    -- v4: "" on columnar rows; legacy JSON only on un-swept rows
);

CREATE INDEX IF NOT EXISTS idx_notebook_parent_order
    ON notebook(parentId, "order", deletedAt);
```

### `.soil` Schema Version 4 — columnar payload, binary strokes, relational composites

`SoilDatabase.MIGRATION_3_4` adds 23 nullable typed columns + a `blob BLOB`
(`SoilSchema.ADDED_COLUMNS_V4`) so an object's payload no longer lives in the opaque `data` JSON:

- **Typed columns** — `x/y/width/height`, `text`, `color`, `strokeWidth`, `refId`, `level`,
  `lineStyle`, `orientation`, `dotSpacing`, `shapeType`, `centerX/centerY`, `rotationDeg`,
  `pointCount`, `contentW/contentH`, `linkTarget`, `chrome`, `flags`. Each type uses the subset it
  needs (e.g. a `page` puts its template in `refId` and keeps size in `boundingBox`; a `layer` puts
  its label in `text` and lock/visible bits in `flags`).
- **`blob`** — binary stroke geometry (float32 x,y + zlib via `core/StrokeCodec`, ~5× smaller than
  JSON), and the binary WEBP image on a `template` row.
- **`ObjectColumns.kt`** is the single boundary between the render/domain models and the columnar row:
  per-type `to<Type>()` readers, `<Render>.toRow()` builders, and the generic `updateColumns`.

**Format-agnostic reading (lazy coexistence).** A columnar row has `data == ""`; readers use the
typed columns/blob when present and fall back to the legacy `data` JSON otherwise, so pre-v4 rows keep
working and convert on their next write. `NotebookCompactor` (run on every seal) sweeps the rest:
legacy JSON strokes → binary blob, then **composites → child rows**, then legacy **structural rows →
columnar**, then a VACUUM. A fully-swept notebook is 100% JSON-free on the object path.

**Composites are relational child rows (Phase 2c).** A composite — a **sticky note**, a **link**, or a
fallback (unrecognized) **heading**/**text** — no longer holds its nested content as `zlib(JSON)` in
the blob. Instead it is a **parent row plus child rows**, each `child.parentId = composite.id`, and
each child a normal columnar row of its own type (strokes, headings, text, lines, shapes; nested
heading/text own their own stroke children). This is the pure "everything is an object — relational,
compositional" model. Coordinate space: **sticky** children are LOCAL (the sticky's content window),
so moving a sticky touches only the parent; **link** and fallback heading/text children are
PAGE-ABSOLUTE, so a move rewrites them. The parentId hierarchy self-isolates composite content from
the page — every page-level reader queries `parentId = layerId`, and a composite's children are
parented to the composite, so they never leak into page rendering, lasso, or export. Recognized
heading/text carry no strokes, so they remain a single bare parent row (behaviour- and perf-neutral).
`ObjectColumns.kt` provides the subtree boundary (`loadXSubtree` / `insertXSubtree` /
`replaceXSubtree` / `assembleX`); `PageCopier` deep-copies whole subtrees (`collectDescendants` /
`deepCopyChildren`); deleting a composite soft-deletes only its parent, and a compactor orphan-sweep
reclaims the child subtree once the parent is purged.

**Materialized child rows always get fresh ids (`replaceXSubtree` → `remapDescendantIds`).** A
composite's children are private content whose ids are never referenced from outside the subtree, so
`replaceXSubtree` (delete-descendants-then-insert) reassigns every descendant a fresh UUID before
insert, rewiring intra-subtree `parentId` through the map (top-level children keep `parentId =
composite.id`). This is not optional: legacy `zlib(JSON)` composites were duplicated **keeping
identical embedded child ids**, so two composites can carry the same child ids. A blob tolerates that
(it is opaque); real child rows cannot (`id` is the PK). Once one side is compacted to child rows,
materializing the other with its original ids hits `UNIQUE constraint failed: notebook.id` — the
crash seen moving a selection containing a legacy-blob link whose children already lived under another
link. Fresh ids make the insert collision-proof and never touch the other composite's rows.
`hardDeleteDescendants` deletes by `parentId` (not child id) and readers reassemble from rows, so
child-id stability is never required.

**Calendar/scratchpad** index tables mirror the same columnar+binary model (`CalendarEntity` /
`ScratchpadEntity`, `NotesproutDatabase` v6 / `MIGRATION_5_6`), but keep composites as blob (their
own DBs; cross-DB transfer bridges via render models through the clipboard).

### `.soil` Schema Version 2 — `undo_redo_state`

`SoilDatabase.MIGRATION_1_2` (Room version 1 → 2) adds a single-row meta table used to persist
undo/redo history for encrypted notebooks (P2.S3):

```sql
CREATE TABLE IF NOT EXISTS undo_redo_state
    (id INTEGER PRIMARY KEY CHECK (id = 0), json TEXT NOT NULL)
```

The `CHECK (id = 0)` constraint limits this to one row. Encrypted notebooks write
`undoRedoManager.toJson()` here on `onStop`; plaintext notebooks continue to use the
`*.soil.undoredo` sidecar and never write to this table. See
[`docs/encryption.md`](encryption.md) for the full undo-persistence design.

### `.soil` Schema Version 3 — `notebook_meta`

`SoilDatabase.MIGRATION_2_3` (Room version 2 → 3) adds the export/import identity table:

```sql
CREATE TABLE IF NOT EXISTS notebook_meta
    (id INTEGER PRIMARY KEY CHECK (id = 0), json TEXT NOT NULL)
```

`CHECK (id = 0)` enforces a single row. The JSON is a `NotebookMeta` object
(`data/NotebookMeta.kt`, `@Serializable`) containing the notebook's id, name, `createdAt`,
`updatedAt`, `encrypted`/`keyScope`, cover snapshot (plaintext only), full folder ancestry
(`folderPath: List<FolderRef>` ordered root→parent), and export provenance (`exportedAt`,
`appVersionCode`). For encrypted notebooks the row is encrypted at rest along with the rest of the
file.

**Continuous upkeep:** the row is written at notebook creation and refreshed on every open/close via
`NotebookMetaStore` (`data/NotebookMetaStore.kt`). Full-notebook export uses this embedded metadata
to be a prompt-free pure file copy. See [`docs/full-notebook-export.md`](full-notebook-export.md).

**Migration-set rule:** all `SoilDatabase` Room builder sites must register
`MIGRATION_1_2` **and** `MIGRATION_2_3`. A shared factory `SoilDatabase.builder(context, path)`
applies both migrations (plus `openCallback()`) — route all builders through it; add per-site
`.openHelperFactory(SoilCrypto.roomFactory(key))` where a keyed open is needed.

---

### Room Setup Rules

- Open `.soil` files by absolute path: `Room.databaseBuilder(context, SoilDatabase::class.java, absolutePath)`
- Each open notebook gets its own Room instance; close and release when the notebook is closed
- `wal_autocheckpoint` is connection-level — re-apply in `SoilDatabase.openCallback()` via `SupportSQLiteDatabase.query(...).use { it.moveToFirst() }`
- PRAGMAs returning a result set: always `rawQuery("PRAGMA ...", null).use { it.moveToFirst() }` — never `execSQL`, never unconsumed cursor
- Any raw SQL touching `order` must double-quote it: `"order"` — it is a SQLite reserved word. Room-generated DAO handles this; only hand-written SQL is at risk. `ContentValues` keys use backtick quoting: `` "`order`" ``.
- `closeNotebook()` runs incremental_vacuum + wal_checkpoint(TRUNCATE), then `db.close()`, then deletes any `-journal` artifact. Lives in `suspend sealNotebook()` (`withContext(Dispatchers.IO)`). User-initiated close: capture snapshot on main thread → launch `sealNotebook()` on `NotesproutApplication.appScope` (a never-cancelled `SupervisorJob + Dispatchers.IO` scope that outlives the Activity) → `finish()` immediately. `onDestroy()` safety net calls `closeNotebook(blocking = true)` for abnormal teardown only (normal path already nulled `soilDatabase`, so it no-ops).
- **Raw `SQLiteDatabase` on `.soil` outside Room must use `OPEN_READWRITE`, not `OPEN_READONLY`.** A read-only WAL connection re-creates `-shm` and cannot unlink `-wal`/`-shm` on close — permanently stranding sidecars. Close via `SQLiteDatabase.checkpointTruncateAndClose(tag, file)` (`data/CoverLoader.kt`): checkpoint → close → delete empty `-journal`.
- Raw read-write helpers (`data/PageCopier.kt`) run `checkpointAndVacuum()` before `db.close()`, then `cleanStrayJournal()`. They must NOT delete `-wal`/`-shm` — NotebookActivity's Room connection is still open to the same file; SQLite removes those when that last connection closes. Multi-step writes must use transactions. The page-index batch ops are: `copyPagesRelativeRaw` / `movePagesRelativeRaw` (deep-copy / reorder a contiguous block before/after a target, in original document order), `deletePageRaw` (soft delete), `setPagesTemplateRaw` + `insertSoilTemplateRaw` + `readNotebookRowId` (point selected pages at one shared `.soil` template row). See the Multi-Page Selection section of [`mainactivity-and-recents.md`](mainactivity-and-recents.md).
- Never silently swallow exceptions over raw DB ops — `Log.e` at minimum; surface a Toast for write ops.

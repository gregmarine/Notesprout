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
- Stroke data: proprietary point arrays (x, y, pressure, tilt, timestamp), stored as JSON in the `data` TEXT column
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
  `image` is the full-resolution PNG as base64 (`NO_WRAP`), stored in `ObjectEntity.data` — same pattern
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
- **Assets are base64 strings.** No external files. Images stored inline in the `data` TEXT column.
- **Decode embedded images bounded.** Route all embedded-asset decodes through `core/BitmapDecode.decodeSampled(bytes, reqW, reqH)` — never `BitmapFactory.decodeByteArray` directly on `.soil`-sourced bytes (OOM risk on e-ink). `MAX_DIMENSION=4096` fallback when there's no natural target.
- **SQLite must stay clean.** A file browser should show only `.soil` files — no WAL/SHM/journal sidecars.
  - `PRAGMA journal_mode = WAL`; `PRAGMA wal_autocheckpoint = 100`; `PRAGMA auto_vacuum = INCREMENTAL`
  - Run `PRAGMA incremental_vacuum` + `PRAGMA wal_checkpoint(TRUNCATE)` on clean close

### Object Schema

```sql
CREATE TABLE IF NOT EXISTS notebook (
    id          TEXT    PRIMARY KEY NOT NULL,
    parentId    TEXT    NOT NULL,
    type        TEXT    NOT NULL,
    boundingBox TEXT    NOT NULL,
    "order"     INTEGER NOT NULL DEFAULT 0,
    createdAt   INTEGER NOT NULL,
    updatedAt   INTEGER NOT NULL,
    deletedAt   INTEGER,
    data        TEXT    NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_notebook_parent_order
    ON notebook(parentId, "order", deletedAt);
```

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

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
- `ObjectType` (`data/index/ObjectType.kt`) — `FOLDER`, `NOTEBOOK`, `LIST`
- `FolderObject`, `NotebookObject`, `ListObject` — `@Serializable` data classes in `data` column. `NotebookObject` carries `snapshot: String?` + `pageCount: Int`. `ListObject` carries `notebookIds: List<String>` (array order = display order).
- `ListIds` (`data/index/ListIds.kt`) — `PINNED_LIST_ID = "00000000-0000-0000-0000-70696e6e6564"`
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

### Room Setup Rules

- Open `.soil` files by absolute path: `Room.databaseBuilder(context, SoilDatabase::class.java, absolutePath)`
- Each open notebook gets its own Room instance; close and release when the notebook is closed
- `wal_autocheckpoint` is connection-level — re-apply in `SoilDatabase.openCallback()` via `SupportSQLiteDatabase.query(...).use { it.moveToFirst() }`
- PRAGMAs returning a result set: always `rawQuery("PRAGMA ...", null).use { it.moveToFirst() }` — never `execSQL`, never unconsumed cursor
- Any raw SQL touching `order` must double-quote it: `"order"` — it is a SQLite reserved word. Room-generated DAO handles this; only hand-written SQL is at risk. `ContentValues` keys use backtick quoting: `` "`order`" ``.
- `closeNotebook()` runs incremental_vacuum + wal_checkpoint(TRUNCATE), then `db.close()`, then deletes any `-journal` artifact. Lives in `suspend sealNotebook()` (`withContext(Dispatchers.IO)`). User-initiated close: capture snapshot on main thread → launch `sealNotebook()` on `NotesproutApplication.appScope` (a never-cancelled `SupervisorJob + Dispatchers.IO` scope that outlives the Activity) → `finish()` immediately. `onDestroy()` safety net calls `closeNotebook(blocking = true)` for abnormal teardown only (normal path already nulled `soilDatabase`, so it no-ops).
- **Raw `SQLiteDatabase` on `.soil` outside Room must use `OPEN_READWRITE`, not `OPEN_READONLY`.** A read-only WAL connection re-creates `-shm` and cannot unlink `-wal`/`-shm` on close — permanently stranding sidecars. Close via `SQLiteDatabase.checkpointTruncateAndClose(tag, file)` (`data/CoverLoader.kt`): checkpoint → close → delete empty `-journal`.
- Raw read-write helpers (`data/PageCopier.kt`) run `checkpointAndVacuum()` before `db.close()`, then `cleanStrayJournal()`. They must NOT delete `-wal`/`-shm` — NotebookActivity's Room connection is still open to the same file; SQLite removes those when that last connection closes. Multi-step writes must use transactions.
- Never silently swallow exceptions over raw DB ops — `Log.e` at minimum; surface a Toast for write ops.

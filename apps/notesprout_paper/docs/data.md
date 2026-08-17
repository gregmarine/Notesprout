# Paper — Data (as built, Phase 1)

Two encrypted SQLite databases, both SQLCipher 4 stock defaults, both Room + KSP, both opened only
through `crypto/SoilCrypto` factories wrapped in `data/NonDestructiveOpenHelperFactory`.

## Paths (`data/SoilFile.kt` — the only path constructors)

| Function | Path |
|---|---|
| `indexFile(ctx)` | `getExternalFilesDir(null)/notesprout.db` |
| `gardenDir(ctx)` | `getExternalFilesDir(null)/Garden/` |
| `soilFile(ctx, id)` | `Garden/<uuid>.soil` |
| `extensionStoreFile(ctx, pkg)` | `Garden/<ext package>.db` — an extension's host-owned store (arc 2, N0); `pkg` must pass `isValidExtensionPackage` (`[a-zA-Z0-9_.]+`) or it throws. Nothing enumerates `Garden/`, so `.db` and `.soil` never mix. |
| `sidecarsOf(file)` | `-wal`, `-shm`, `-journal` next to a db file |

## Global index — `notesprout.db` (`data/index/`)

Owner: `PaperIndex` (object). Opened once per process by `BootstrapActivity`, never closed.
`IndexDatabase` (Room, `user_version` = 1, WAL, `wal_autocheckpoint=100`, `busy_timeout=5000`).

```sql
CREATE TABLE objects (
    id TEXT NOT NULL PRIMARY KEY, type TEXT NOT NULL, name TEXT NOT NULL, parentId TEXT,
    createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, deletedAt INTEGER,
    pageCount INTEGER, flags INTEGER, keyScope TEXT, templateKind TEXT, blob BLOB,
    refId TEXT, sortOrder INTEGER);
CREATE INDEX index_objects_parentId_type_deletedAt ON objects(parentId, type, deletedAt);
```

- Types (`ObjectType`): `folder` · `notebook` (`pageCount`, `flags` bit0 = encrypted, `keyScope`
  `GLOBAL`, `templateKind`, `blob` = cover WEBP q100) · `list` · `list_item` (`parentId` = list,
  `refId` = member, `sortOrder`).
- Sentinel `ListIds.PINNED_LIST_ID = 00000000-0000-0000-0000-70696e6e6564` ("pinned" in hex),
  created by `IndexRepository.ensurePinnedListExists()` on library launch — never by a migration.
- `ObjectSummary` = the blob-free projection every listing/card read uses. `cover(id)` is a
  separate per-card read.
- `IndexRepository`: listing (`folders`/`notebooks`, alive only), `alive(id)`, `nameTaken`,
  `createFolder`/`createNotebook`, `rename`/`move`/`touch` (the `updatedAt` discipline — bumped
  only by real edits), `setPageCount`/`setCover`, `deleteNotebook` (scrubs edges → soft-delete),
  `deleteFolderRecursive` (cycle-guarded; returns the notebook ids inside for file removal),
  `ancestry(folderId)` (root-first, ≤ 50 hops, cycle-guarded), `isSelfOrDescendant`, pin/unpin/
  `pinnedNotebookIds`. Soft deletes only; membership edges are the one routine hard delete.

## Notebook — `<uuid>.soil` (`data/soil/`)

`SoilDatabase` (Room, `user_version` = `SoilSchema.SOIL_VERSION` = 1, WAL, `auto_vacuum=INCREMENTAL`
set in `onCreate`). One instance per open notebook; opened by the notebook session (Phase 3).

- `SoilDatabase.open(ctx, id, file, passphrase)` — file **must exist** (`SoilLockedException`
  otherwise); raw-key path via `KeyOpener` when cached.
- `SoilDatabase.create(ctx, id, file, passphrase)` — new-notebook flow only; refuses an existing
  non-empty file; warms the raw key.
- `db.seal(file)` — `wal_checkpoint(TRUNCATE)` → close → delete an empty stray `-journal`. Meta
  refresh is the caller's step before seal. No compactor in v0.

Schema (`SoilSchema` holds the DDL contract Room's entity must match):

```sql
CREATE TABLE notebook (
    id TEXT NOT NULL PRIMARY KEY, parentId TEXT NOT NULL, type TEXT NOT NULL,
    "order" INTEGER NOT NULL DEFAULT 0, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL,
    deletedAt INTEGER, text TEXT, refId TEXT, width REAL, height REAL, color TEXT,
    strokeWidth REAL, style TEXT, flags INTEGER, blob BLOB);
CREATE INDEX idx_notebook_parent_order ON notebook(parentId, "order", deletedAt);
CREATE TABLE notebook_meta (id INTEGER PRIMARY KEY CHECK (id = 0), json TEXT NOT NULL);
```

Hierarchy: `notebook` row (`parentId = ''`, `text` = title, `refId` = last-open page) → `page` rows
(`refId` = template row id, `width`/`height` px, ordered by `"order"`) → `stroke` rows (`color`,
`strokeWidth`, `style`, `blob` = format B). One `template` row under the notebook row (`text` = template
identity, `blob` = WEBP q100). **No layers.** `"order"` is always double-quoted in SQL / backticked in Room.

**Template identity values** (index `objects.templateKind` and the template row's `text` — informational
labels, nothing reads them yet): `BLANK` (`SoilSchema.TEMPLATE_BLANK`, no template row) ·
`<extension package>:<template id>` (e.g. `com.symmetricalpalmtree.notesprout.ext.templates:lined`,
`ExtensionContract.templateIdentity`) · legacy v0 `LINED` / `DOTTED` / `GRID` (untouched, no migration).

`NotebookMeta` (kotlinx.serialization, `ignoreUnknownKeys`, `explicitNulls=false`) — the same
field set as Notesprout's so the file stays in the family: `formatVersion=1, notebookId, name,
createdAt, updatedAt, encrypted=true, keyScope="GLOBAL", cover=null, folderPath, exportedAt=null,
appVersionCode, textDocument=false`. `NotebookMetaStore.write/read` are raw SQL on the Room
connection.

## Stroke codec — `core/StrokeCodec.kt` (format B, `docs/soil-file-format.md` Part V)

`byte0 = 1` (plaintext version) · `zlib{ flags:u8 | (x:f32, y:f32[, pressure:f32][, tilt:f32]) × N }`
little-endian; `flags` bit0 = pressure, bit1 = tilt. Paper writes both channels; reads any
combination; derives the stride from the known bits; drops a partial trailing point; bails the
inflate loop on a zero-progress round. `encode(x, y, pressure?, tilt?)` / `decode(blob): Points`
(parallel arrays — no Android / g-paper dependency). `core/InkColorCodec` maps `#RRGGBB` /
`#AARRGGBB` ⇄ ARGB Int (garbage → black). JVM tests: `StrokeCodecTest`, `InkColorCodecTest`.

## Extension stores — `Garden/<pkg>.db` (`data/extstore/`)

One encrypted key/value database per extension package, **owned by the core** and opened only by
`ExtensionStores.open(ctx, pkg)` (open-or-create; process-lifetime cache; `closeAll()` for
tests/debug). Encrypted under the **global** passphrase from `KeySession`; raw-key cache file id
**`ext:<pkg>`** (namespaced so it never collides with a notebook UUID or `KeyMaterial.INDEX_FILE_ID`).
`ExtensionStoreDatabase` (Room, `user_version` = 1, WAL, `wal_autocheckpoint=100`, `busy_timeout=5000`).

```sql
CREATE TABLE kv (`key` TEXT NOT NULL PRIMARY KEY, `value` BLOB NOT NULL, updatedAt INTEGER NOT NULL);
```

The extension reaches it only through `IExtensionStore` (an `ExtensionStoreBinder` the host mints
per bind, uid-bound and revocable); it never sees the path or a key. The file **survives** the
extension's uninstall / disable. Full model, caps and rules: `docs/extensions.md` §"The extension
store". "Forget cached key" (debug) clears `ext:*` raw keys with everything else.

## Prefs (ids and enum names only — never names)

- `paper_view_state` (`data/prefs/BrowseState`): `folderId`, `mode` (NORMAL/PINNED/RECENTS),
  `lastOpenNotebookId`.
- `paper_sort`, `paper_recents` — Phases 2 / 3.

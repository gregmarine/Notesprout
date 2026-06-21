# Full Notebook Export

> Referenced from `CLAUDE.md`. Covers the export format, `notebook_meta` schema, continuous
> upkeep, the copy engine, and the encrypted trade-off. Import is **out of scope** for the current
> implementation (see stub below).

---

## Overview

Full-notebook export copies the notebook's `.soil` file — the SQLite database itself — to a
user-chosen destination via **Save to device** (`CreateDocument`) or **Share** (platform share
sheet via FileProvider). The file is **self-describing**: an embedded `notebook_meta` table inside
the `.soil` carries the import metadata, so no external manifest or wrapper is needed.

Entry points:
- **MainActivity** — long-press context menu → Export → "Export Notebook (.soil)" (format chooser)
- **NotebookActivity** — toolbar Export button → "Export Notebook (.soil)" (format chooser)

Both flow through the same Save/Share AlertDialog after packaging.

---

## Container Format

The exported file is the raw `.soil` (SQLite) file renamed to `<NotebookName>.soil`.

- No zip, no wrapper, no separate manifest.
- Filename = the notebook's **current name from the global index**, sanitized with
  `[^a-zA-Z0-9_\-. ]` stripped, trimmed. A name that sanitizes to empty (or is `.`/`..`) falls
  back to the notebook UUID. Spaces are preserved (valid on all target filesystems).
- MIME type for all `.soil` transfers: `application/octet-stream`.

The FileProvider entry for the export cache dir is `<cache-path name="exported_notebooks"
path="exported_notebooks/" />` in `res/xml/file_paths.xml`.

---

## `notebook_meta` Table — Schema v3

Added by `SoilDatabase.MIGRATION_2_3` (Room version 2 → 3):

```sql
CREATE TABLE IF NOT EXISTS notebook_meta
    (id INTEGER PRIMARY KEY CHECK (id = 0), json TEXT NOT NULL)
```

The `CHECK (id = 0)` constraint enforces a single row. For encrypted notebooks the JSON is
encrypted at rest along with all other data in the file (SQLCipher encrypts the whole file).

### `NotebookMeta` Fields

| Field | Type | Description |
|---|---|---|
| `formatVersion` | `Int` (default 1) | Schema version for future compatibility |
| `notebookId` | `String` | Stable UUID from the global index |
| `name` | `String` | Display name at time of last meta refresh |
| `createdAt` | `Long` | Epoch ms from the global index |
| `updatedAt` | `Long` | Epoch ms from the global index |
| `encrypted` | `Boolean` | Whether the file is SQLCipher-encrypted |
| `keyScope` | `KeyScope?` | `GLOBAL`, `NOTEBOOK`, or `null` (if not encrypted) |
| `cover` | `String?` | Base64 PNG cover snapshot — **plaintext notebooks only**; always `null` for encrypted notebooks |
| `folderPath` | `List<FolderRef>` | Full ancestor chain root→immediate-parent; empty for root-level notebooks |
| `exportedAt` | `Long?` | Epoch ms stamped at export time; `null` on in-DB rows that haven't been exported |
| `appVersionCode` | `Int?` | `BuildConfig.VERSION_CODE` at time of last refresh |

### `FolderRef` Fields

| Field | Type | Description |
|---|---|---|
| `id` | `String` | Stable folder UUID from the global index |
| `name` | `String` | Folder display name at time of last meta refresh |
| `parentId` | `String?` | Parent folder UUID; `null` if root |

`folderPath` is ordered root → immediate parent. An importing device that lacks those folders can
recreate the hierarchy with the **same IDs and names** by walking the list in order.

---

## Continuous Upkeep

`notebook_meta` is not export-only — it is kept current throughout the notebook lifecycle:

| Event | Action |
|---|---|
| **Notebook creation** (`createNotebook` in `MainActivity`) | `CREATE TABLE IF NOT EXISTS notebook_meta (...)` in the raw bootstrap SQL; initial row inserted |
| **Notebook open** (`NotebookActivity` after Room build) | `NotebookMetaStore.refresh(db, repo, notebookId)` off-UI; failure is tolerated and logged |
| **Notebook close** (`sealNotebook` before checkpoint/vacuum) | `NotebookMetaStore.refresh(db, repo, notebookId)` — bakes in freshest name/cover/folderPath before the file goes cold |

This upkeep is what makes full-notebook export a **pure file copy** — no passphrase prompt is ever
needed, because the embedded metadata is always current from the last open/close cycle.

Key classes:
- `NotebookMeta` / `FolderRef` (`data/NotebookMeta.kt`) — `@Serializable`; `toJson()`/`fromJson()`
- `NotebookMetaStore` (`data/NotebookMetaStore.kt`) — `write`, `read`, `buildFromIndex`, `refresh`
- `SoilDatabase.MIGRATION_2_3` — creates the table on any pre-v3 `.soil` opened by Room

---

## Export Copy Engine (`NotebookPackager`)

`object NotebookPackager` (`NotebookPackager.kt`) provides two variants:

### Cold-file variant — `packageForExport(context, repo, notebookId, openableKey)`

Used from **MainActivity** (notebook is not currently open):

- `openableKey`: `""` = plaintext; non-empty String = GLOBAL passphrase (open via SoilCrypto);
  `null` = encrypted-NOTEBOOK or key not cached — skip meta refresh, copy as-is.
- **Best-effort meta refresh:** if `openableKey != null`, opens a transient keyed/plain Room
  connection, refreshes `notebook_meta` with `exportedAt = now`, runs
  `PRAGMA wal_checkpoint(TRUNCATE)`, then closes the transient connection. On any error, logs and
  skips the refresh; the copy always proceeds.
- **Copy:** wipes `cacheDir/exported_notebooks/`, then copies the main `.soil` file only (no
  sidecars — after TRUNCATE checkpoint, the WAL is empty and the main file is self-contained).
- Returns the `File` in the export cache dir.

### Open-DB variant — `packageOpenForExport(context, db, repo, notebookId)`

Used from **NotebookActivity** (notebook is currently open, `db` is the live keyed connection):

- Caller must flush strokes to `db` before invoking (NotebookActivity calls `saveStrokes(db)` first).
- Refreshes `notebook_meta` with `exportedAt = now` **through the live keyed connection** — no
  passphrase prompt; the key is already held by `db`. Works identically for plaintext and encrypted.
- Checkpoints the live connection (`PRAGMA wal_checkpoint(TRUNCATE)`) so the main `.soil` holds all
  committed content.
- **Copies the main file only.** The live Room connection stays open; its `-wal`/`-shm` are NOT
  deleted (SQLite manages them; same rule as `data/PageCopier.kt`). Because the TRUNCATE checkpoint
  ran and the user isn't drawing during the "Exporting…" modal, the copied main file is complete.
- Returns the `File` in the export cache dir.

### Sidecar / cache hygiene

- `exported_notebooks/` is **wiped and recreated** at the start of every export (
  `deleteRecursively()` + `mkdirs()`). No stale `.soil` files accumulate.
- The copy touches only the main `.soil` file — never `-wal`, `-shm`, or `-journal`.
- The cold-file path closes the transient DB after checkpoint; Room removes the (empty) WAL and SHM
  on close.
- The open-DB path leaves Garden sidecars intact (the live connection owns them).

---

## Encrypted Notebooks — No Prompt, No Warning

Full-notebook export of an encrypted notebook is a **silent pure copy**:

- No passphrase is requested at export time (contrast: PDF export, which decrypts content for
  rendering and therefore warns that the exported PDF is unencrypted).
- No "exported file is unencrypted" warning is shown, because the export *is* encrypted — SQLCipher
  encrypts the entire file including `notebook_meta`. A file browser sees opaque ciphertext.
- **Encrypted status travels with the file.** When the `.soil` is imported on another device, the
  importing app opens it via `SoilCrypto` and prompts for the passphrase normally.

### Encrypted-NOTEBOOK meta freshness on cold rename

If a NOTEBOOK-scoped encrypted notebook is **renamed in the global index** but **not reopened**
before export via the MainActivity context menu, the export *filename* uses the new index name (from
`repo.getNotebook()`), but the **embedded `notebook_meta.name`** reflects the prior open/close cycle
(the last time the file was sealed). This lag is by design — the export is passphrase-free, so we
cannot open the file to refresh embedded meta. The embedded name self-heals on the next open/close.
This is documented but acceptable; the export filename is always current.

---

## Save / Share

After `NotebookPackager` returns the cache `File`, both entry points present the same
Save/Share `AlertDialog` (`shape_bordered`):

- **Save to device** — `saveSoilLauncher` (`CreateDocument("application/octet-stream")`) lets the
  user pick a location via the system file picker; the cache file is copied to that URI.
- **Share** — `ACTION_SEND`, `type = "application/octet-stream"`, `EXTRA_STREAM` = FileProvider URI,
  **with `clipData = ClipData.newRawUri("", uri)` + `FLAG_GRANT_READ_URI_PERMISSION`** (required on
  Android 12+ for chooser intermediaries such as Drive — same pattern as PDF share).

Cancelling Save or Share leaves only the (harmless) cache file, which is wiped at the next export.

---

## Import (Future — Stub)

Import is explicitly out of scope for this implementation. This stub records the intended design so
a future implementer can build on it.

**Input:** a `.soil` file provided by the user (e.g. via `ACTION_VIEW` / `ACTION_SEND` intent or a
file picker). The file may be encrypted.

**Steps:**

1. Read `notebook_meta` from the file (for plaintext: open via standard SQLite; for encrypted:
   prompt for passphrase via `KeyResolver`, open via `SoilCrypto`).
2. From `folderPath`, recreate any missing folders in the global index **using the same UUIDs and
   names** so cross-device links resolve correctly. Walk the list root → immediate parent; for each
   folder, `getOrCreate` by id (check index first; insert only if absent).
3. Determine the target notebook id. If `notebookMeta.notebookId` does not yet exist in the index,
   use it as-is (no collision). If it exists and refers to a different notebook, generate a fresh UUID
   for the imported copy.
4. Copy the `.soil` file to `Garden/<resolvedId>.soil`.
5. Insert a `type="notebook"` row in the global index with the resolved id, the name from meta
   (editable by the user post-import), parent from the reconstructed folder chain, and
   `encrypted`/`keyScope` from meta.
6. For GLOBAL-scoped encrypted notebooks: prompt for the passphrase once (verify against the file);
   if the device already has a global passphrase cached and they differ, the user must either supply
   the notebook's original passphrase (and re-key to the device's global) or keep it as NOTEBOOK
   scope.
7. Page count is derivable on import by counting `type="page"` rows in the `.soil` — no embedded
   value needed.

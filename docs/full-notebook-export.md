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

## Import

Full-notebook import is the reverse of export: a `.soil` file is accepted from the file picker or an
open-with / share-to intent, probed, optionally unlocked, placed in the folder hierarchy, and
registered in the global index. The embedded `notebook_meta` drives the entire process.

### Entry Points

- **Overflow "Import Notebook (.soil)"** — `importSoilLauncher` (`OpenDocument`, MIME
  `application/octet-stream` + `*/*`) in `MainActivity`.
- **Open-with / share-to** — `AndroidManifest.xml` registers `ACTION_VIEW` (content:// and file://)
  and `ACTION_SEND` filters on `MainActivity` (`launchMode="singleTop"`). Cold launch triggers in
  `onCreate` (`savedInstanceState == null`); already-open app triggers in `onNewIntent`.

Both paths call `startImportFromUri(uri)`.

### Pipeline

1. **Copy to temp.** The incoming `content://` URI is copied to `cacheDir/imported_notebooks/incoming.soil`
   (dir wiped+recreated each import so no stale files accumulate).
2. **Probe.** `SoilCrypto.probe(temp)` → `Plaintext` / `Encrypted` / `Invalid`. Invalid → toast + abort.
3. **Unlock (encrypted only).** `KeyResolver.resolveForImportRead(activity, temp)` — prompts for the
   passphrase, verifies with `SoilCrypto.verifyPassphrase`, loops on wrong (using the `"IMPORT"`
   `AttemptLimiter` bucket, independent of any notebook id). Cancel → abort + wipe temp.
4. **Read manifest.** `NotebookImporter.readManifest(file, fallbackName, passphrase?)` opens the file via
   `SoilCrypto.openRaw`, reads `notebook_meta` + page count. Missing `notebook_meta` → `meta = null`,
   fallback name = file's display name minus `.soil`, empty `folderPath` (lands at root or chosen folder).
   No `notebook` table → `ImportException` → rejected.
5. **ID collision.** If `meta.notebookId` already exists in the index (live row): **Replace existing** /
   **Keep both** (fresh UUID) / **Cancel**. Replace keeps the existing row's placement and skips the
   placement dialog. Keep both proceeds to placement.
6. **Placement dialog.** "Notebook's folders" (default — recreates missing folders with same UUIDs/names
   via `ensureFolderWithId`, walking `folderPath` root→parent) or "Choose folder…" (enters
   `DestinationPickerState.ImportNotebook` — existing picker, no folders created).
7. **Name conflict.** If a notebook of the same name already exists in the target folder: **Replace**
   (soft-deletes the conflict, imports with same name) or **Keep both** (appends " Copy").
8. **Keying chooser (encrypted only).** `ActionSheetDialog` after placement is resolved, before writing
   to Garden — "Keep existing passphrase" / "Use this device's global" / "New notebook passphrase".
   See [`docs/encryption.md`](encryption.md) for the scope rule (including GLOBAL→NOTEBOOK downgrade).
   Re-key happens on the temp file (`SoilMigrator.rekeyInPlace`) before the copy, keeping Garden clean
   on any failure.
9. **Write to Garden.** Delete stale sidecars, copy temp to `soilFile(context, resolvedId)`.
10. **Register / update index.** `importNotebookRow` (new) or `renameNotebook` + `updateNotebookPageCount`
    + `setEncryptionState` (Replace). Encrypted imports always set `snapshot = null` (leak hygiene).
11. **Refresh embedded meta.** Opens the Garden file (keyed or plaintext), writes a fresh `NotebookMeta`
    (resolved id, new `folderPath`, resulting `encrypted`/`keyScope`, cover null for encrypted),
    `PRAGMA wal_checkpoint(TRUNCATE)`, closes. Best-effort — failure is logged, import proceeds.
12. **Cleanup.** Delete `incoming.soil`; dir is wiped at the start of the next import regardless.

### Key Classes

- `NotebookImporter.kt` — engine: `readManifest`, `importPlaintext`, `replacePlaintext`,
  `importEncrypted`, `replaceEncrypted`, `refreshPlaintextMeta`, `refreshEncryptedMeta`.
- `MainActivity.kt` — all import dialogs and coroutine wiring: `startImportFromUri`,
  `handleIncomingIntent`, `showImportCollisionDialog`, `showImportPlacementDialog`,
  `showImportNameConflictDialog`, `showKeyingChooserForImport`, `showKeyingChooserForReplace`,
  `executeImport`, `executeReplace`, `doImportEncrypted`, `doReplaceEncrypted`.
- `IndexRepository` — `ensureFolderWithId`, `importNotebookRow`, `setEncryptionState`.

### Import Cache Dir

`cacheDir/imported_notebooks/` — wiped+recreated at the start of every import. The FileProvider does
not need to expose this directory (it is not shared with other apps).

### Edge Cases

- **Folder id collision** — if a `folderPath` id already exists as a notebook (not a folder), that
  level is skipped and the notebook lands one level up; the hierarchy still forms.
- **Soft-deleted folder** — `ensureFolderWithId` un-deletes the folder (same id, same name) rather
  than creating a duplicate.
- **Default placement when folders already exist** — `ensureFolderWithId` is a no-op for folders
  that are already live; no duplicates are created.
- **Pre-S1 `.soil` (no `notebook_meta`)** — imports with the file's display name at the chosen folder
  (or root if default); the index snapshot is null; opens and draws correctly.
- **Corrupt / non-`.soil`** — rejected at the probe step; no partial index row is written.

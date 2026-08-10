# Full Notebook Export

> Referenced from `CLAUDE.md`. Covers the export format, `notebook_meta` schema, continuous
> upkeep, the copy engine, and the encrypted trade-off — plus the **full import pipeline**
> (probe → unlock → placement → collision → keying), which is implemented; see [Import](#import).

---

## Overview

Full-notebook export copies the notebook's `.soil` file — the SQLite database itself — to a
user-chosen destination via **Save to device** (`CreateDocument`), **Share** (platform share
sheet via FileProvider), or **Google Drive** (direct upload through the backup subsystem's Drive
client — no share sheet involved, which is what makes it work on Supernote, where the system
chooser is suppressed by the firmware). The file is **self-describing**: an embedded `notebook_meta` table inside
the `.soil` carries the import metadata, so no external manifest or wrapper is needed.

Every export in the app — `.soil` included — runs through the single **`ExportActivity`** screen.
Entry points:

- **MainActivity** — long-press context menu → Export
- **NotebookActivity** — canvas long-press "Page" menu → Export (bottom item; flushes ink first)
- **PageIndexActivity** — select pages → Export (seeds the "Selected (n)" scope)

The screen presents Pages / Format / Options / Encryption / Destination at once; the top-right
Export button runs the job and hands the result straight to the chosen destination. `.soil` is
offered only when the scope is **All pages** — it is inherently whole-notebook. See
[The Export Screen](#the-export-screen).

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

## The Export Screen

`ExportActivity` (`ExportActivity.kt`, `res/layout/activity_export.xml`) is the only export UI.
Supporting types live in `export/`:

| File | Role |
|---|---|
| `export/ExportSpec.kt` | `ExportFormat` (PDF/PNG/MARKDOWN/TEXT/SOIL), `PageScope`, `ExportDestination`, `SoilKeying`, and the immutable `ExportSpec` describing one job |
| `export/ExportEngine.kt` | Runs a spec — dispatches to `NotebookExporter` / `NotebookTextExporter` / `NotebookPackager`, and applies the `.soil` keying transform |

**MARKDOWN / TEXT prefer the page's document.** `NotebookTextExporter` exports a page's `document` row
when it has one and falls back to recognized handwriting otherwise — the document is the finished
version of the same words, and those pages skip recognition entirely (see [`documents.md`](documents.md)).
Documents live inside the `.soil`, so `.soil` export/import carries them with no changes at all.

| `export/ExportDelivery.kt` | SAF `CreateDocument` launchers (one per mime), the `OpenDocumentTree` folder write for multi-file PNG, share intents, the Google Drive upload (`uploadToDrive`), and the PNG→template import |
| `export/DriveFolderPickerDialog.kt` | Folder picker for the Google Drive destination — browses the app-owned "Notesprout Exports" tree |
| `export/ExportNaming.kt` | Filename/template-name whitelisting and de-duplication |
| `data/export/ExportPreset.kt` | One saved set of export choices |
| `data/export/ExportPresetsManager.kt` | SharedPreferences + kotlinx JSON store for the preset list |

### Presets

The Presets section at the top of the screen saves the current choices for reuse. Rows apply a
preset on tap and delete it on long-press (with confirmation); `+ Save current settings…` prompts
for a name (empty field — the user names every preset).

- **A preset never holds a secret.** `usePdfPassword` and `soilKeying` record what was *chosen*;
  the PDF password and any new `.soil` passphrase are not stored. Applying a preset that needs one
  re-opens its prompt immediately, so the secret is typed fresh each time. This is the same rule as
  everywhere else in the app — see [`encryption.md`](encryption.md).
- **Page scope is not captured.** It belongs to how the screen was opened (a Page Index selection,
  the current page), not to a reusable preference. Changing scope therefore does *not* clear the
  active preset, while changing anything a preset does capture does.
- Applying a `.soil` preset widens the scope to All pages rather than falling back to another
  format — `.soil` is inherently whole-notebook.
- `ExportActivity.applyingPreset` guards the widget writes in `applyPreset`: setting a checkbox in
  code fires its listener, and those listeners clear the active preset, so without the guard a
  preset would instantly deselect itself.
- Storage mirrors `ToolbarPreferencesManager` — device-local SharedPreferences, tolerant load, not
  in `notesprout.db` and not in any `.soil`.

Contract notes:

- The screen receives **notebook identity only** (`EXTRA_NOTEBOOK_ID` / `_NAME`, optional
  `EXTRA_CURRENT_PAGE_ID`, optional `EXTRA_SELECTED_PAGE_IDS`) — never a `File` or a live DB handle.
  It reads the page list itself via `data/PageList.kt`'s `loadPageRefs(path, passphrase)`.
- **Callers must flush unsaved ink before launching.** The screen renders from the `.soil` on disk;
  `NotebookActivity.openExportScreen` calls `saveStrokes(db)` first for exactly this reason.
- The key is resolved **once** in `onCreate`: `KeySession` → `PassphraseStore.getGlobalPassphrase`
  (GLOBAL) → `KeyResolver.resolveForOpen` (NOTEBOOK). An unresolvable key shows a "locked" notice
  and hides the Export button.
- Encryption choices are **inline, not prompts**: the unencrypted-export warning is visible text,
  and the `.soil` keying picker (Keep / Remove / New passphrase) replaces the old
  `SoilExportKeying` action sheet. Only the two options that need typed input — the PDF password
  and a new `.soil` passphrase — still open a `PassphrasePrompt`, and they fire from their row
  rather than after Export.
- Unavailable options are `GONE`, never disabled — a disabled control is visually silent on e-ink.
- Progress is inline (`Exporting page 7 of 24…`); Back cancels the running job.

---

## Export Copy Engine (`NotebookPackager`)

### `packageForExport(context, repo, notebookId, openableKey)`

The single packaging path. Because `ExportActivity` always works from the cold file, the former
open-DB variant (`packageOpenForExport`) is gone — callers flush and let this open its own
transient connection:

- `openableKey`: `""` = plaintext; non-empty String = GLOBAL passphrase (open via SoilCrypto);
  `null` = encrypted-NOTEBOOK or key not cached — skip meta refresh, copy as-is.
- **Best-effort meta refresh:** if `openableKey != null`, opens a transient keyed/plain Room
  connection, refreshes `notebook_meta` with `exportedAt = now`, runs
  `PRAGMA wal_checkpoint(TRUNCATE)`, then closes the transient connection. On any error, logs and
  skips the refresh; the copy always proceeds.
- **Copy:** wipes `cacheDir/exported_notebooks/`, then copies the main `.soil` file only (no
  sidecars — after TRUNCATE checkpoint, the WAL is empty and the main file is self-contained).
- Returns the `File` in the export cache dir.

### Sidecar / cache hygiene

- `exported_notebooks/` is **wiped and recreated** at the start of every export (
  `deleteRecursively()` + `mkdirs()`). No stale `.soil` files accumulate.
- The copy touches only the main `.soil` file — never `-wal`, `-shm`, or `-journal`.
- The transient DB is closed after checkpoint; Room removes the (empty) WAL and SHM on close.

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

## Google Drive Destination

The fourth destination row uploads the finished file(s) directly to the connected Google Drive —
no system chooser, so it works on every device including Supernote (whose firmware suppresses the
share sheet). It reuses the backup subsystem's OAuth connection and REST client wholesale
(`DriveAuth` / `DriveTokenStore` / `DriveApiClient` — see [`backup.md`](backup.md)); the one new
piece is the folder picker.

- **App-owned tree.** The OAuth scope is `drive.file` — the app can only see folders it created.
  All exports live under an app-created **"Notesprout Exports"** root (`ROOT_EXPORT_FOLDER` in
  `DriveApiClient.kt`), separate from "Notesprout Backups". The picker browses and creates folders
  inside that tree only; to move an export elsewhere the user does it in Drive itself.
- **Default folder mirrors the library.** `ExportActivity` seeds the path with the notebook's
  folder ancestry from the global index (`getFolderAncestry`), so `Library/Work/Meetings` exports
  to `Notesprout Exports / Work / Meetings` unless changed.
- **Folder picker** (`export/DriveFolderPickerDialog.kt`) — browse subfolders / Up / "New
  folder…", "Use this folder" confirms. The chosen path is **names, not Drive ids**, and *nothing
  is created while browsing*: a new folder just descends into a not-yet-existing name (empty
  listing), and the whole chain is find-or-created (`ensureFolder`) at upload time by
  `ExportDelivery.uploadToDrive`. Cancelling an export therefore never leaves empty folders on
  Drive.
- **Connection.** Tapping the row (or Export, if a preset armed the destination) with no stored
  refresh token opens a Connect dialog → `DriveAuthActivity` (same WebView OAuth as backups). A
  successful connect writes `driveAccountEmail` into the backup config so Backup Settings shows
  "Connected", but does **not** enable Drive backups.
- **Upload semantics.** `uploadOrReplace` — re-exporting the same name into the same folder
  replaces the file rather than accumulating copies. Multi-file PNG exports upload every page file
  into the chosen folder (no SAF tree picker needed). Failure keeps the export screen open with a
  toast so the user can retry without re-rendering.
- **Presets** capture `drivePath` (names only — no ids, no secrets); an empty stored path keeps
  the seeded library-mirror default.

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
   No `notebook` table → `ImportException` → rejected. **The manifest is untrusted input:** every
   `notebookId` / folder id read from it is validated (`isSafeImportId` — UUID alphabet only) before it
   is used as a `soilFile()` path or index key, closing path-traversal; a non-UUID id falls back to a
   fresh UUID. `SoilMigrator`'s ATTACH statements single-quote-escape the file path.
5. **ID collision.** If a (validated) `meta.notebookId` already exists in the index (live row):
   **Replace existing** / **Keep both** (fresh UUID) / **Cancel**. Replace keeps the existing row's
   placement and skips the placement dialog. Keep both proceeds to placement.
6. **Placement dialog.** "Notebook's folders" (default) or "Choose folder…". Ancestry recreation is
   **strictly create-only** (`importFolderCreateOnly`): a folder path segment whose id is missing is
   created with that UUID/name; an id that already exists — as a live folder, a soft-deleted folder,
   or a non-folder — is **never mutated**, and the descent stops there (the notebook lands one level
   up). Imported ancestry can't resurrect, rename, or move the user's own folders.
7. **Name conflict.** If a notebook of the same name already exists in the target folder: **Replace**
   or **Keep both** (appends " Copy"). **Replace retires the existing notebook only *after* the import
   fully commits** (soft-delete → normal trash), so cancelling at a later step leaves it intact; it is
   refused outright if that notebook is currently open in a `NotebookActivity` (`OpenNotebooks`).
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

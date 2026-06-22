# Notesprout — Backup (Phase 1) Implementation Plan

> **Scope:** Back up `.soil` notebooks + the global index (`notesprout.db`) to a local device
> folder and/or a Google Drive folder, via the Storage Access Framework (SAF). **Backup only —
> restore is a separate future effort.** This document is the source of truth for the work; keep the
> per-session **Status** blocks current as you go.

---

## Decisions (locked — do not re-litigate)

| # | Decision | Rationale |
|---|---|---|
| D1 | **SAF for both destinations.** Local and Drive both use `ACTION_OPEN_DOCUMENT_TREE` + `takePersistableUriPermission` + `DocumentFile`. No new Gradle dependencies. Google Drive folders are pickable when the Drive app's DocumentsProvider is present. | Honors the "no new deps" rule; unified code path. |
| D2 | **Two fixed destination slots: `LOCAL` and `DRIVE`** — not an arbitrary list. Each is independently configurable + enable-toggleable. A backup run writes to **every enabled** destination. | Matches the spec exactly (local→root, Drive→device subfolder). |
| D3 | **Local layout:** files written to the **root** of the chosen tree. **Drive layout:** files written to a **per-device subfolder** inside the chosen tree. | Multiple devices share one Drive folder without collision. |
| D4 | **Device folder name = `Build.MODEL` + short stable UUID, user-editable** (e.g. `BOOX-Go-103-a1b2c3d4`). **Hardware serial is NOT used** — `Build.getSerial()` requires privileged `READ_PHONE_STATE` and returns `"UNKNOWN"` for a normal sideloaded app on Android 10+. | Unique, human-readable, no permissions. |
| D5 | **Backup filename = `<notebookUuid>.soil`** (not the display name). | Replace-in-place semantics require a stable name; renaming a notebook must not orphan its backup. The display name still travels inside the file via `notebook_meta` for future restore. |
| D6 | **Full file copy each run, replace-in-place.** Not a sync. No diffing, no deletion of backups for deleted notebooks (Phase 1). | Spec: "Full backup each time… replace the file." |
| D7 | **Manual trigger only** ("Back Up Now" button). No background scheduling, no WorkManager. | Spec + no new deps. |
| D8 | **A notebook needs backup to destination _X_ when:** `excludeFromBackup == false` AND (`lastBackedUp[X] == null` OR `updatedAt > lastBackedUp[X]`). Per-destination timestamp. | "Register as backed up" = stamp the timestamp **after** a successful copy, per destination. |
| D9 | **`notesprout.db` is copied LAST**, after every notebook's status has been written, so the backed-up index reflects the completed run. | Spec. |
| D10 | **Encrypted `.soil` files are copied as-is (ciphertext).** No passphrase prompt — same as full-notebook export. | SQLCipher encrypts the whole file; backup is a pure byte copy. |
| D11 | **All backup state lives in `notesprout.db`.** Per-notebook timestamps live in `NotebookObject` JSON (no migration). Global config lives in a singleton `BACKUP_CONFIG` row (mirrors `CLIPBOARD`). | Spec: "tracked in notesprout.db"; consistent with existing patterns. |

### Known Phase-1 limitations (document, don't fix)
- Renaming the device folder orphans the old Drive subfolder (no migration of prior backups).
- A notebook that is **currently open** while a backup runs is copied from its last cold/sealed
  state (backup is launched from MainActivity, where notebooks are closed). The live-edit window is
  not flushed mid-edit. Acceptable for v1; note in docs.
- Deleting a notebook does not remove its backup file. Restore/GC is future work.
- SAF writes to Drive can be slow; the progress UI must remain responsive (work on `Dispatchers.IO`).
- **Google Drive does not appear in the SAF folder picker on BOOX devices** (confirmed on G102). The
  Drive app is present but its `DocumentsProvider` is not registered by the Play Services layer on
  BOOX/ONYX. Users can still pick a local or SD card folder for the "Drive" slot as a workaround.
  Document in `docs/backup.md` (S4); do not change the code.

---

## Architecture Summary

```
notesprout.db (global index)
 ├─ objects table
 │   ├─ NOTEBOOK rows  → NotebookObject JSON gains:
 │   │       excludeFromBackup: Boolean = false
 │   │       lastBackedUpLocal: Long?   = null   (epoch ms)
 │   │       lastBackedUpDrive: Long?   = null   (epoch ms)
 │   └─ BACKUP_CONFIG row (singleton, fixed UUID) → BackupConfig JSON:
 │           deviceId: String            (random UUID, generated once)
 │           deviceFolderName: String    (Model + short id, user-editable)
 │           localTreeUri: String?       (persisted SAF tree URI)
 │           localEnabled: Boolean = false
 │           driveTreeUri: String?       (persisted SAF tree URI)
 │           driveEnabled: Boolean = false
 │           lastRunAt: Long?            (device-local bookkeeping)
 │
Destinations (DocumentFile trees):
   LOCAL:  <localTreeUri>/<uuid>.soil ,  <localTreeUri>/notesprout.db
   DRIVE:  <driveTreeUri>/<deviceFolderName>/<uuid>.soil ,  .../notesprout.db
```

New package: `com.notesprout.android.data.backup` (the existing empty `sync/` dir is left alone).

---

## Session 1 — Data model & backup-state foundations

**Goal:** All persistence plumbing for backup state, no UI, no actual file copying. App behaves
exactly as before to the user; existing notebooks load unchanged (JSON back-compat).

**Status:** ☑ Complete

### Files to create
1. `data/backup/BackupConfig.kt` — `@Serializable data class BackupConfig` with the fields in the
   architecture summary. `toJson()`/`fromJson()` via `kotlinx.serialization` (follow
   `ClipboardPayload.kt` for the companion pattern). Use
   `@OptIn(ExperimentalSerializationApi::class)` + `@EncodeDefault(ALWAYS)` on `localEnabled`,
   `driveEnabled` (mirror `NotebookObject`). Add a companion `fun newDefault(deviceFolderName: String): BackupConfig`
   that generates `deviceId = UUID.randomUUID().toString()`.
2. `data/backup/BackupConfigStore.kt` — `object BackupConfigStore` (mirror `ClipboardStore.kt`):
   - `suspend fun read(dao: ObjectDao): BackupConfig?`
   - `suspend fun write(dao: ObjectDao, config: BackupConfig)`
   - `suspend fun ensure(dao: ObjectDao, defaultDeviceFolderName: String): BackupConfig` — read, or
     create+persist a default row if absent. Uses `BACKUP_CONFIG_ID` + `ObjectType.BACKUP_CONFIG`.
3. `data/backup/DeviceIdentity.kt` — `object DeviceIdentity`:
   - `fun defaultDeviceFolderName(): String` → sanitize `Build.MODEL` (replace `[^a-zA-Z0-9_-]` with
     `-`, collapse repeats) + `-` + first 8 chars of a `UUID.randomUUID()`. Pure helper; the
     persisted name lives in `BackupConfig.deviceFolderName`.

### Files to edit
4. `data/index/ObjectType.kt` — add `const val BACKUP_CONFIG = "backup_config"`.
5. `data/index/ListIds.kt` — add
   `const val BACKUP_CONFIG_ID = "00000000-0000-0000-0000-6261636b7570"` (comment: `"backup"` →
   hex `62 61 63 6b 75 70`).
6. `data/index/NotebookObject.kt` — add three fields with defaults (no migration needed; absent JSON
   keys decode to defaults):
   ```kotlin
   @EncodeDefault(EncodeDefault.Mode.ALWAYS) val excludeFromBackup: Boolean = false,
   val lastBackedUpLocal: Long? = null,
   val lastBackedUpDrive: Long? = null,
   ```
7. `data/index/IndexRepository.kt` — add a `// region Backup` block:
   - `suspend fun getBackupConfig(): BackupConfig?` → `BackupConfigStore.read(dao)`
   - `suspend fun ensureBackupConfig(defaultName: String): BackupConfig`
   - `suspend fun saveBackupConfig(config: BackupConfig)`
   - `suspend fun setNotebookExcludedFromBackup(notebookId: String, excluded: Boolean)` — read row,
     decode `NotebookObject`, copy with new flag, re-encode, `dao.update(...)`. **Do NOT bump
     `updatedAt`** (toggling exclusion is not a content modification). Follow the
     decode→copy→encode→update pattern already in `IndexRepository` (see the `data =` update sites).
   - `suspend fun markNotebookBackedUp(notebookId: String, kind: BackupKind, timestamp: Long)` — same
     pattern; sets `lastBackedUpLocal` or `lastBackedUpDrive`. **Do NOT bump `updatedAt`.**
   - `suspend fun notebooksNeedingBackup(kind: BackupKind): List<ObjectEntity>` — `getAllNotebooks()`
     filtered by the D8 rule (decode each `NotebookObject`, compare `entity.updatedAt` to the
     per-kind timestamp; skip `excludeFromBackup`).
8. `data/backup/BackupKind.kt` — `enum class BackupKind { LOCAL, DRIVE }`.

### Tests (`app/src/test`)
9. `BackupConfigTest.kt` — JSON round-trip; `newDefault` generates a non-blank `deviceId`.
10. `NotebookObjectBackupTest.kt` — decoding a **legacy** `NotebookObject` JSON string (without the
    three new keys) yields the correct defaults (`excludeFromBackup=false`, both timestamps `null`).
11. `NeedsBackupLogicTest.kt` — pure-function test of the D8 predicate (extract the predicate into a
    small testable function, e.g. `BackupKind` + a helper `fun needsBackup(updatedAt, lastBackedUp, excluded): Boolean`
    in `data/backup/`, and have `notebooksNeedingBackup` call it).

### Status maintenance
Update this block to ☑ as each numbered item lands. Add a one-line note for any deviation.

### Testing steps (you perform; report back)
1. Clean build + install debug on **G102**:
   ```sh
   cd apps/notesprout_android && ./gradlew clean assembleDebug
   adb -s b7a46e13 install -r app/build/outputs/apk/debug/app-debug.apk
   ```
2. Run unit tests: `./gradlew testDebugUnitTest` — all green.
3. Launch the app on G102. Existing notebooks and folders must load exactly as before (verifies the
   `NotebookObject` JSON change is backward compatible). Create a notebook, draw, close, reopen —
   no errors.
4. Report any crashes / load failures.

> On pass: update Status → ☑, commit all Session 1 changes (no push). Commit msg seed style, e.g.
> `🌱 Backup S1: backup state model (config row + per-notebook timestamps)`.

---

## Session 2 — Backup Settings screen & destination configuration

**Goal:** A "Backup" entry in MainActivity's overflow toolbar opens a Backup Settings screen where
the user picks a local folder and/or a Drive folder (SAF), toggles each on/off, and edits the device
folder name. Everything persists across restart. **No actual backup runs yet** — "Back Up Now" is
present but disabled (wired in Session 3).

**Status:** ☑ Complete

### Files to create
1. `res/drawable/ic_backup.xml` — monochrome vector (e-ink: single path, `android:tint`/fill
   `@color/inkBlack`, no color, no gradient). A simple cloud-with-up-arrow or folder-with-up-arrow,
   24dp viewport, matching the weight of `ic_import.xml` / `ic_export.xml`. Copy one of those as a
   starting structure.
2. `res/layout/activity_backup_settings.xml` — mirror `activity_backup_settings` on
   `activity_encryption_settings.xml`'s structure/styling exactly (back button header,
   `@color/paperWhite` background, `shape_bordered` sections, `Widget.Notesprout.*` styles, 1dp
   inkBlack dividers). Sections:
   - **Header row:** `btnBack` (ic_back) + title "Backup".
   - **Device section:** label "This device's backup folder name", an editable field
     `etDeviceFolderName` (AppCompatEditText, BOOX IME pattern per `docs/design-system.md`) + a
     `btnSaveDeviceName` (or save on focus-loss). Helper text: "Used as the subfolder name inside
     your Google Drive backup folder."
   - **Local destination section:** status `tvLocalStatus` ("Not configured" / the tree's display
     path), `btnChooseLocal` ("Choose folder…"), a toggle `switchLocalEnabled` (use an
     AppCompatCheckBox or a styled toggle consistent with the app — **no Material switch**; check how
     existing toggles are done, else a labeled checkbox).
   - **Drive destination section:** mirror local — `tvDriveStatus`, `btnChooseDrive`,
     `switchDriveEnabled`. Helper text noting the per-device subfolder behavior.
   - **Action section:** `btnBackUpNow` ("Back Up Now"), disabled in this session; `tvLastRun`
     ("Last backup: never").
3. `BackupSettingsActivity.kt` — `AppCompatActivity` modeled on `EncryptionSettingsActivity.kt`:
   - `private val repository by lazy { IndexRepository(NotesproutIndex.dao()) }`.
   - In `onCreate`: inflate `ActivityBackupSettingsBinding`, wire back button, register two
     `ActivityResultContracts.OpenDocumentTree` launchers (`pickLocalTreeLauncher`,
     `pickDriveTreeLauncher`).
   - On a tree picked: call `contentResolver.takePersistableUriPermission(uri,
     FLAG_GRANT_READ_URI_PERMISSION or FLAG_GRANT_WRITE_URI_PERMISSION)`, store the URI string in the
     config (`localTreeUri` / `driveTreeUri`), persist via `repository.saveBackupConfig`, refresh UI.
   - `onResume` → `refreshUi()` (load config via `ensureBackupConfig(DeviceIdentity.defaultDeviceFolderName())`,
     populate fields, set toggle states, render destination status from
     `DocumentFile.fromTreeUri(...).name` or the URI's last path segment).
   - Toggle listeners persist `localEnabled` / `driveEnabled`. A destination cannot be enabled if its
     URI is null (toggle disabled until a folder is chosen).
   - Device-name save: validate non-blank, sanitize the same way as `DeviceIdentity` (no path
     separators), persist `deviceFolderName`.
   - `btnBackUpNow.isEnabled = false` this session (TODO comment referencing Session 3).

### Files to edit
4. `res/layout/activity_main.xml` — add `btnBackup` `AppCompatImageButton` to the `overflowToolbar`
   LinearLayout (after `btnEncryption`, line ~289), `style="@style/Widget.Notesprout.ToolbarButton"`,
   `android:layout_marginStart="4dp"`, `android:src="@drawable/ic_backup"`,
   `android:contentDescription="Backup"`.
5. `MainActivity.kt` — in `setupBottomBar()` (near line 713, the `btnEncryption` block) add:
   ```kotlin
   binding.btnBackup.setOnClickListener {
       closeOverflowToolbar()
       startActivity(Intent(this, BackupSettingsActivity::class.java))
   }
   ```
6. `AndroidManifest.xml` — register `BackupSettingsActivity` (`exported="false"`, theme
   `@style/Theme.Notesprout`, no special launch mode) next to `EncryptionSettingsActivity`.

### Status maintenance
Update ☑ per item; note any styling deviations from the encryption screen.

### Testing steps (you perform; report back)
1. Clean build + install on G102 (commands as Session 1).
2. Open the app → tap **More (…)** → tap the new **Backup** button → Backup Settings opens.
3. Tap **Choose folder…** under Local, pick a folder (e.g. internal storage `Documents/NSBackup`).
   Status shows the folder. Enable the Local toggle.
4. Tap **Choose folder…** under Drive. If the Google Drive app is installed, navigate into Drive and
   pick a folder; if not, pick any tree to confirm the picker works. Enable the Drive toggle.
   **Report whether Google Drive appears as a location in the picker on G102.**
5. Edit the device folder name; save.
6. Force-stop and relaunch the app, reopen Backup Settings → all selections, toggles, and the device
   name persist. "Back Up Now" is visibly disabled.
7. Report any issues, especially around the Drive picker availability and the IME for the name field.

> On pass: Status → ☑, commit (no push). e.g. `🌱 Backup S2: settings screen + SAF destination config`.

---

## Session 3 — Backup engine (copy + status tracking)

**Goal:** "Back Up Now" runs the full process: copy every notebook needing backup to each enabled
destination, stamp per-destination timestamps after each success, then copy `notesprout.db` last.
Per-notebook **Exclude from backup** toggle added to the MainActivity context menu.

**Status:** ☐ Not started

### Files to create
1. `data/backup/SafBackupWriter.kt` — `object SafBackupWriter`, SAF/`DocumentFile` helpers (all on
   `Dispatchers.IO`, never UI thread):
   - `fun rootDir(context, treeUri: Uri): DocumentFile?` → `DocumentFile.fromTreeUri(context, treeUri)`,
     null/`!canWrite()` guarded.
   - `fun ensureChildDir(parent: DocumentFile, name: String): DocumentFile?` →
     `parent.findFile(name)?.takeIf { it.isDirectory } ?: parent.createDirectory(name)`.
   - `fun replaceFile(context, dir: DocumentFile, fileName: String, source: File, mime: String = "application/octet-stream"): Boolean`
     — `dir.findFile(fileName)?.delete()`, then `dir.createFile(mime, fileName)`, then stream
     `source` → `contentResolver.openOutputStream(target.uri)` with buffered copy. (SAF has no atomic
     replace; delete-then-create is the standard approach.) Returns success; logs `Log.e` on failure,
     never throws to the caller's loop.
   - SAF `createFile` may append an extension or de-dupe the name; after delete-then-create with the
     exact name this is safe, but verify the created `DocumentFile.name` matches and log a warning if
     not.
2. `data/backup/BackupResult.kt` — `data class BackupResult(val perDestination: Map<BackupKind, DestResult>)`
   and `data class DestResult(val attempted: Int, val succeeded: Int, val failed: Int, val skipped: Int, val indexCopied: Boolean, val errors: List<String>)`.
3. `data/backup/BackupEngine.kt` — `object BackupEngine`:
   ```kotlin
   suspend fun run(
       context: Context,
       repo: IndexRepository,
       config: BackupConfig,
       onProgress: (current: Int, total: Int, label: String) -> Unit,
   ): BackupResult
   ```
   Algorithm (on `Dispatchers.IO`):
   1. Build the list of enabled destinations from `config` (LOCAL if `localEnabled && localTreeUri != null`;
      DRIVE likewise). If none enabled, return an empty result.
   2. Resolve each destination's **target directory** once:
      - LOCAL → `rootDir(localTreeUri)`.
      - DRIVE → `ensureChildDir(rootDir(driveTreeUri), config.deviceFolderName)`.
      A destination whose dir can't be resolved/written is recorded as a hard error and skipped.
   3. For each enabled destination, compute `notebooksNeedingBackup(kind)` (per-destination; D8).
   4. Iterate notebooks (union for progress counting). For each (notebook, destination) pair needing
      backup: resolve `soilFile(context, notebookId)`; if the `.soil` file is missing, count as
      skipped + log. Copy via `replaceFile(dir, "<uuid>.soil", soilFile)`. On success call
      `repo.markNotebookBackedUp(notebookId, kind, runStart)`; on failure leave the timestamp
      untouched (auto-retry next run) and append to `errors`. Call `onProgress` each step.
   5. **Last step:** checkpoint the index (`NotesproutIndex.checkpointAndVacuum()`) so the main
      `notesprout.db` file is self-contained, then copy it to each enabled destination's dir as
      `notesprout.db`. (Per D9, this happens after all per-notebook statuses are written, so the
      backed-up index reflects the completed run.) Mark `indexCopied` per destination.
   6. Set `config.lastRunAt = runStart`, `repo.saveBackupConfig(config)` (device-local bookkeeping;
      it is fine that this value is not in the just-copied index).
   7. Return `BackupResult`.
   - Notes: the `notesprout.db` File is `File(context.getExternalFilesDir(null), "notesprout.db")`
     (same derivation as `NotesproutIndex.open`). Copy **only** the main db file — after
     `wal_checkpoint(TRUNCATE)` the `-wal` is empty; do **not** copy `-wal`/`-shm`.
   - Encrypted notebooks: no special handling — the byte copy carries ciphertext (D10).

### Files to edit
4. `BackupSettingsActivity.kt`:
   - Enable `btnBackUpNow` when at least one destination is enabled+configured (compute in `refreshUi`).
   - On click: guard against concurrent runs (an `AtomicBoolean running` like
     `EncryptionSettingsActivity`'s rotation guard, or disable the button for the duration). Show a
     determinate/indeterminate progress dialog (reuse the app's `shape_bordered` AlertDialog pattern;
     `onProgress` updates "Backing up X of N…"). Launch on `lifecycleScope` + `withContext(Dispatchers.IO)`
     for the engine call.
   - On completion: dismiss progress, show a summary AlertDialog/Toast (e.g. "Local: 4 backed up, 0
     failed · Drive: 4 backed up, index copied"). Surface errors plainly. Refresh `tvLastRun`.
   - If a destination's persisted URI permission has been revoked (caught as `SecurityException` /
     null `rootDir`), show a clear message telling the user to re-choose that folder; clear the stale
     URI from config so the toggle disables.
5. `MainActivity.kt` — notebook context menu (`ActionSheetDialog`, near line 1646–1672): add an
   action **"Exclude from Backup"** / **"Include in Backup"** (label reflects current state; read the
   decoded `NotebookObject.excludeFromBackup`). On tap: `repo.setNotebookExcludedFromBackup(id, !current)`,
   refresh the grid. Use an appropriate existing drawable (e.g. reuse `ic_backup` or a generic icon —
   do not add a new one unless trivial).

### Status maintenance
Track ☑ per item. Record the actual progress-dialog approach chosen.

### Testing steps (you perform; report back)
1. Clean build + install on G102.
2. Configure **both** Local and Drive (enable both). Ensure you have ~3–4 notebooks, at least one
   encrypted, plus a few folders.
3. Tap **Back Up Now**. Watch progress. On completion read the summary.
4. **Verify Local:** browse the chosen local folder (file manager / `adb`) → it contains
   `<uuid>.soil` files (one per non-excluded notebook) **at the root** + `notesprout.db`.
5. **Verify Drive:** the chosen Drive folder contains a subfolder named your device folder name, and
   inside it the `<uuid>.soil` files + `notesprout.db`.
6. **Encrypted notebook:** its backup file exists and is ciphertext (opening it raw shows no readable
   strings).
7. **Incremental behavior:** without modifying anything, tap Back Up Now again → summary shows 0
   notebooks copied (all skipped as up-to-date), index still re-copied.
8. **Modify one notebook** (draw + close so `updatedAt` bumps), Back Up Now → only that notebook is
   re-copied; its backup file's timestamp updates.
9. **Exclude:** long-press a notebook → "Exclude from Backup". Back Up Now → it is skipped even
   though modified. Re-include → it backs up again.
10. **No destinations:** disable both toggles → "Back Up Now" disables.
11. Report timings (especially Drive), any failures, and the summary text.

> On pass: Status → ☑, commit (no push). e.g. `🌱 Backup S3: backup engine + per-notebook exclude`.

---

## Session 4 — Docs, edge cases & polish

**Goal:** Documentation, hardening of edge cases, and the final cross-device sanity pass.

**Status:** ☐ Not started

### Files to create
1. `docs/backup.md` — full subsystem doc: destinations & layout (D2/D3), device identity (D4),
   filename scheme (D5), the needs-backup rule (D8), run ordering / index-last (D9), encrypted
   behavior (D10), state storage (D11), key classes (`BackupConfig`, `BackupConfigStore`,
   `BackupEngine`, `SafBackupWriter`, `DeviceIdentity`, `BackupKind`), the Backup Settings screen,
   and the **Known limitations** list. Note "Restore — out of scope (future)" stub at the end,
   mirroring the import stub style in `docs/full-notebook-export.md`.

### Files to edit
2. `CLAUDE.md` — add a row to the `docs/` table:
   `| Backup: local + Google Drive via SAF, per-device subfolder, incremental-by-timestamp, index-last | docs/backup.md |`.
3. Memory: add `project_backup.md` under the memory dir + a one-line entry in `MEMORY.md`.

### Edge cases to verify / harden (code only where a gap is found)
- **Empty Garden / zero notebooks:** run completes, only `notesprout.db` is copied. No crash.
- **Revoked SAF permission** between runs: handled in S3 (re-prompt + clear stale URI) — confirm.
- **Drive folder picked but offline / Drive app signed out:** copy fails gracefully, summary reports
  the failure, timestamps not stamped, app does not crash.
- **Very large notebook** (big strokes/images): copy streams (buffered), no OOM, progress advances.
- **Concurrent-run guard:** double-tapping "Back Up Now" cannot start two runs.
- **A notebook currently open** in another Activity while backing up from settings is not expected
  (settings is reached from MainActivity) — document the cold-copy caveat.
- **Name sanitation:** a device folder name with slashes/illegal chars is sanitized before use as a
  SAF directory name.

### Testing steps (you perform; report back)
1. Clean build + install on G102.
2. Walk the edge-case list above on G102; report each.
3. Optional spot-check on a second Tier-1 device (e.g. Go 7 `17845014`) to confirm two devices write
   to **different** subfolders in the same Drive root without collision.
4. Read `docs/backup.md` for accuracy against the shipped behavior.

> On pass: Status → ☑, commit (no push). e.g. `🌱 Backup S4: docs, edge-case hardening, test matrix`.

---

## Global status

| Session | Title | Status |
|---|---|---|
| 1 | Data model & backup-state foundations | ☑ Complete |
| 2 | Backup Settings screen & destination config | ☑ Complete |
| 3 | Backup engine + per-notebook exclude | ☐ Not started |
| 4 | Docs, edge cases & polish | ☐ Not started |

**Branch:** `seed` · **Test device:** G102 (`b7a46e13`) · **Commit after each session only once the
user confirms tests pass. Never push.**

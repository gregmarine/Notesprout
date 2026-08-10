# Backup

> Referenced from `CLAUDE.md`. Covers destinations & layout, device identity, the needs-backup
> rule, run ordering, encrypted behavior, state storage, the Google Drive REST/OAuth path, key
> classes, the Backup Settings screen, and known limitations.
>
> **Restore is implemented** (staging-first, replace-all) — see [Restore](#restore).

---

## Overview

Backup copies every non-excluded `.soil` notebook file plus the global index (`notesprout.db`) to
one or both configured destinations. It is **manual-trigger only** ("Back Up Now" button in Backup
Settings) and **incremental by timestamp**: a notebook is only re-copied when its `updatedAt` is
newer than the last successful backup timestamp for that destination.

Entry point: **MainActivity overflow toolbar → Backup icon → BackupSettingsActivity**.

---

## Destinations & Layout (D2, D3)

Two fixed slots — not an arbitrary list:

| Slot | Mechanism | Root layout |
|---|---|---|
| **LOCAL** | Storage Access Framework (`ACTION_OPEN_DOCUMENT_TREE` + `DocumentFile`) | Files written to the **root** of the chosen SAF tree |
| **DRIVE** | Google Drive REST API v3 (hand-rolled, no GMS dependency) | Files written to `My Drive / Notesprout Backups / <deviceFolderName> /` |

A backup run writes to **every enabled** destination. Either slot can be enabled or disabled
independently; both can be active simultaneously.

**LOCAL** can target internal storage, SD card, or any SAF-accessible tree (e.g. a USB drive on
supported hardware). Google Drive does **not** appear in the SAF folder picker on BOOX devices
(confirmed on G102), which is why the DRIVE slot uses the Drive REST API instead of SAF.

---

## Device Identity (D4)

The Drive layout uses a **per-device subfolder** so multiple devices can share one Drive root
without collision. The subfolder name is:

```
<sanitized Build.MODEL>-<8-char random hex>
```

Generated once by `DeviceIdentity.defaultDeviceFolderName()` (each run of any character outside
`[a-zA-Z0-9_-]` collapses to `-`, then the ends are trimmed), persisted in
`BackupConfig.deviceFolderName`, user-editable in Backup Settings. The Settings-screen editor applies
a **looser** filter — only `/ \ : * ? " < > |` runs become `-` — so a hand-typed name may keep spaces
and punctuation the generated default would have stripped. Both filters exist for the same reason:
the name doubles as the Drive subfolder name, so it must not contain path separators.

**Hardware serial is NOT used** — `Build.getSerial()` requires `READ_PHONE_STATE` (privileged) and
returns `"UNKNOWN"` on normal sideloaded builds.

---

## Filename Scheme (D5)

Backup files are named by **notebook UUID**, not display name:

- `<notebookUuid>.soil` — one per non-excluded notebook
- `notesprout.db` — the global index

UUID filenames give stable replace-in-place identity: renaming a notebook in the app does not
orphan its backup. Display names and folder ancestry come back from the restored `notesprout.db`, not
from the filenames; each `.soil` also carries its own copy in `notebook_meta`, which is what makes a
single backed-up file importable on its own via the full-notebook import flow.

---

## Needs-Backup Rule (D8)

A notebook needs backup to destination *X* when **all** of:
- `excludeFromBackup == false`
- `lastBackedUp[X] == null` **OR** `notebook.updatedAt > lastBackedUp[X]`

The predicate lives in `BackupPredicates.kt`
(`fun needsBackup(updatedAt, lastBackedUp, excludeFromBackup): Boolean`); `IndexRepository.notebooksNeedingBackup(kind)`
applies it to every non-deleted notebook row. Timestamps are per-destination (`lastBackedUpLocal` /
`lastBackedUpDrive`). A failed copy does **not** stamp the timestamp — the notebook is automatically
retried on the next run.

**Where exclusion is set:** the notebook context menu in MainActivity (long-press a notebook →
"Exclude from Backup" / "Include in Backup"). Neither the exclusion toggle nor a backup stamp bumps
`updatedAt` — they are policy/bookkeeping, not content edits, and bumping it would immediately
re-flag the file that was just backed up.

---

## Run Ordering / Index Last (D9)

The engine processes notebooks first, then copies `notesprout.db` **last** — after all per-notebook
timestamps have been written. This ensures the backed-up index reflects the completed run. The
sequence:

1. Resolve destination directories (fail-fast per destination, not global abort). A destination that
   can't be resolved records its error and is dropped from the run; the other still goes.
2. Pre-flight DRIVE token fetch (`DriveAuth.getAccessTokenSilent`), then find-or-create the device
   folder.
3. Build the work list — one `(notebook, destination)` pair per notebook needing that destination.
4. **Compact pass.** Each notebook in the work list (unique across destinations) is opened and closed
   once, running the same seal-time `NotebookCompactor.compact` used on close, so the bytes about to
   be copied are the leanest form. Compaction preserves `updatedAt`, so a notebook stays flagged and
   is copied in its now-smaller form below. Opening also absorbs the WAL — so the main file alone is a
   complete copy. Notebooks that **can't** be opened unattended (NOTEBOOK-scope encrypted, or GLOBAL
   without a cached passphrase) are skipped; failure here is swallowed, never a reason to skip the
   copy that follows.
5. For each pair: copy the `.soil`, stamp the timestamp on success. A notebook that couldn't be
   checkpointed in step 4 has its non-empty `-wal` **sidecar copied alongside** the `.soil`, and both
   must land before the notebook is stamped backed-up; when the WAL *was* absorbed, any stale
   destination sidecar is deleted (a fresh `.soil` paired with an old `-wal` would corrupt on
   restore). A `.soil` missing from disk is counted *skipped*, not failed.
6. `NotesproutIndex.checkpointAndVacuum()`, then snapshot the index to a local temp and **probe it**
   before streaming to destinations (guards against a torn copy of the live, still-open index). If
   the snapshot or probe fails, the run falls back to streaming the live file.
7. Copy `notesprout.db` to each enabled destination. LOCAL writes stream to a `.part` sibling, move
   the previous file to `.old`, then rename in — a torn write never replaces a good backup. DRIVE
   PATCHes the existing file id via resumable upload (see [Replace-in-place](#replace-in-place-d16)).
8. Persist `config.lastRunAt` **only if at least one destination succeeded** (a fully-failed run no
   longer reports "backed up just now").

---

## Encrypted Notebook Behavior (D10)

Encrypted `.soil` files are copied **as ciphertext** — no passphrase prompt, no decryption.
SQLCipher encrypts the entire file; a byte-level copy is sufficient. Under encrypt-everything the
index itself is encrypted too, so a backup is opaque without the source device's passphrase. Restore
never asks for it inline: it stages and commits the ciphertext as-is, then the post-restart unlock
gate collects that library's recovery key (see [Restore](#restore)).

The one thing encryption costs the backup run is **compaction**: a NOTEBOOK-scope notebook — or a
GLOBAL one on a device with no cached passphrase — can't be opened unattended, so it is copied
un-compacted and with its `-wal` sidecar (step 4 above).

---

## State Storage (D11)

All backup state lives in `notesprout.db` (the global index):

- **Per-notebook state** — typed **columns** on the notebook's `objects` row: `excludeFromBackup` is
  bit 1 of `flags`, alongside the `lastBackedUpLocal` / `lastBackedUpDrive` INTEGER columns. Notebook
  rows went columnar in DB **v7** (`data = ""`); the accessors in `IndexObjectColumns.kt`
  (`notebookMeta()` / `withNotebookMeta()`) are format-agnostic, so a pre-v7 row still reads its
  values out of the legacy `NotebookObject` JSON and converts on next write. No other code touches
  these fields directly. See [`global-index-format.md`](global-index-format.md).
- **Global config** — a singleton `BACKUP_CONFIG` row (`id = BACKUP_CONFIG_ID`) holding
  `BackupConfig` JSON (mirrors the `CLIPBOARD` row pattern). Read/written by `BackupConfigStore`.
  This row **keeps its JSON by design** — it is a single low-traffic blob, not a queried shape.

`NotebookObject` remains the in-memory carrier for the per-notebook values regardless of which
storage form the row is in.

`BackupConfig` fields:

| Field | Type | Purpose |
|---|---|---|
| `deviceId` | `String` | Stable random UUID generated once per device |
| `deviceFolderName` | `String` | Drive subfolder name (user-editable) |
| `localTreeUri` | `String?` | Persisted SAF tree URI for LOCAL |
| `localEnabled` | `Boolean` | LOCAL backup active |
| `driveTreeUri` | `String?` | **Legacy** — unused after S2.1, kept for back-compat |
| `driveEnabled` | `Boolean` | DRIVE backup active |
| `driveAccountEmail` | `String?` | Display-only; non-secret; null = not connected |
| `lastRunAt` | `Long?` | Device-local epoch-ms timestamp of the last run |

---

## Google Drive REST/OAuth Path (D12–D17)

### Why not SAF for Drive?

The Android `DocumentsProvider` for Google Drive is not registered in the SAF folder picker on
BOOX devices (confirmed on G102). The DRIVE slot therefore uses the Drive REST API v3 directly
(hand-rolled with `HttpURLConnection`; no Google API client libraries).

### OAuth: WebView PKCE flow (S2.2)

OAuth uses a **WebView-based OAuth 2.0 + PKCE** flow — no Google Play Services dependency.

- **Client type:** Desktop app (created in Google Cloud Console).
- **Redirect URI:** `http://localhost/oauth2callback` — intercepted in `DriveAuthActivity`'s `WebViewClient.shouldOverrideUrlLoading`.
- **User agent:** Chrome UA is spoofed before `loadUrl()` — Google blocks OAuth in WebViews that identify as Android WebView (`disallowed_useragent`).
- **PKCE:** 32-byte random verifier → SHA-256 → base64url challenge (RFC 7636).
- **Credentials:** `DRIVE_CLIENT_ID` and `DRIVE_CLIENT_SECRET` are injected at build time via
  `System.getenv()` into `BuildConfig`. Set them in `~/.zshenv` (or your shell profile); they are
  **never committed to git**.

### Token hygiene (D15)

- **Access tokens** live in memory only — never persisted, never logged, never put in Intent extras.
- **Refresh token** stored in `DriveTokenStore` (`EncryptedSharedPreferences`, AES-256-GCM, master
  key in Android Keystore). Treated with the same hygiene as encryption passphrases.
- Each backup run calls `DriveAuth.getAccessTokenSilent(context)` to silently fetch a fresh access
  token via a POST to the Google token endpoint. No UI required after first consent.

### `drive.file` scope (D14)

OAuth scope = `https://www.googleapis.com/auth/drive.file` (per-file: the app sees/manages **only**
files it creates). The app creates its own visible **"Notesprout Backups"** folder in the user's
Drive. There is no Drive folder picker.

`drive.file` is *sensitive* but **not** *restricted* — no annual third-party security assessment
required (the full `drive` scope would require it).

### Replace-in-place (D16)

Drive allows multiple files with the same name in a folder. Each run:
1. **Searches** for an existing `<uuid>.soil` / `notesprout.db` by name within the device folder.
2. **PATCHes** its content if found (stable file ID, preserves revision history), or **POSTs** to
   create it if absent.

Folders are resolved with **find-or-create every run** (no cached folder IDs) to handle the case
where the user deletes the backup folder in Drive — it is transparently re-created.

Upload uses the **resumable upload** protocol (`X-Upload-Content-Type` initiate → `Location` session
URI → streaming PUT). `setFixedLengthStreamingMode` avoids buffering large `.soil` files in memory.
Chunked upload with `Content-Range` + `308 Resume Incomplete` is a future enhancement for
interrupted uploads over flaky Wi-Fi.

---

## Google Cloud Console Setup (One-Time)

This is required before the Drive OAuth flow will succeed.

1. **Create / pick a Google Cloud project** at https://console.cloud.google.com.
2. **Enable the Google Drive API:** *APIs & Services → Library → Google Drive API → Enable*.
3. **Configure the OAuth consent screen** (*APIs & Services → OAuth consent screen*):
   - User type: **External**.
   - App name (e.g. "Notesprout"), user support email, developer contact email.
   - **Add scope:** `https://www.googleapis.com/auth/drive.file` (listed as *sensitive*, not
     *restricted*).
   - **Publishing status:** for personal / multi-device use, leave in **Testing** and add your
     Google account under **Test users**. For a public release, publish the app — standard
     verification applies for the sensitive scope.
4. **Create an OAuth 2.0 Client ID of type _Desktop app_** (*APIs & Services → Credentials →
   Create credentials → OAuth client ID → Desktop app*). Name it e.g. "Notesprout Desktop".
   - **Authorized redirect URI:** `http://localhost/oauth2callback`.
   - Download / copy the **Client ID** and **Client secret**.
5. **Set credentials in your shell profile** (`~/.zshenv` or `~/.zprofile`):
   ```sh
   export DRIVE_CLIENT_ID="<your-client-id>.apps.googleusercontent.com"
   export DRIVE_CLIENT_SECRET="<your-client-secret>"
   ```
   Source the file, then rebuild. They are injected into `BuildConfig.DRIVE_CLIENT_ID` /
   `BuildConfig.DRIVE_CLIENT_SECRET` via `System.getenv()` in `app/build.gradle.kts`. **Never
   commit these values to git.**

> **Note:** The Android OAuth client type (from S2.1) is no longer used — the Desktop app client
> replaced it in S2.2. It can be left in place or deleted.

---

## Key Classes

| Class / Object | Location | Role |
|---|---|---|
| `BackupConfig` | `data/backup/BackupConfig.kt` | `@Serializable` config data class; `toJson()`/`fromJson()` |
| `BackupConfigStore` | `data/backup/BackupConfigStore.kt` | Read/write singleton BACKUP_CONFIG row in `notesprout.db` |
| `BackupKind` | `data/backup/BackupKind.kt` | `enum { LOCAL, DRIVE }` |
| `BackupPredicates` | `data/backup/BackupPredicates.kt` | `needsBackup(updatedAt, lastBackedUp, excludeFromBackup)` |
| `BackupResult` / `DestResult` | `data/backup/BackupResult.kt` | Run summary: per-destination counts + errors |
| `BackupEngine` | `data/backup/BackupEngine.kt` | Orchestrates a full backup run on `Dispatchers.IO` |
| `SafBackupWriter` | `data/backup/SafBackupWriter.kt` | SAF/`DocumentFile` helpers for LOCAL writes (`.part` → rename) |
| `SafBackupReader` | `data/backup/SafBackupReader.kt` | Read side of a SAF destination — enumerate device folders, copy files out |
| `DriveAuth` | `data/backup/DriveAuth.kt` | PKCE helpers, auth URL builder, token exchange, silent refresh |
| `DriveTokenStore` | `data/backup/DriveTokenStore.kt` | `EncryptedSharedPreferences`-backed refresh token storage |
| `DriveAuthActivity` | `DriveAuthActivity.kt` | WebView OAuth activity; intercepts the redirect URI |
| `DriveApiClient` | `data/backup/DriveApiClient.kt` | Hand-rolled Drive REST v3 (`findChild`, `listChildren`, `ensureFolder`, `uploadOrReplace`, `downloadTo`, `delete`) |
| `DriveBackupWriter` | `data/backup/DriveBackupWriter.kt` | Engine-facing facade over `DriveApiClient` |
| `DeviceIdentity` | `data/backup/DeviceIdentity.kt` | `defaultDeviceFolderName()` — sanitized model + random suffix |
| `RestoreSource` | `data/backup/RestoreSource.kt` | Restore-side interface + `SafRestoreSource` / `DriveRestoreSource`; `listDevices()` / `fetchInto()` |
| `RestoreEngine` | `data/backup/RestoreEngine.kt` | Staging-first, aside-swap restore + `recoverInterrupted()` launch repair |
| `BackupSettingsActivity` | `BackupSettingsActivity.kt` | The Backup Settings screen (destinations, Back Up Now, Restore) |

> **Shared with the export screen.** `DriveAuth`/`DriveTokenStore`/`DriveApiClient` also power the
> export screen's **Google Drive destination** (see
> [`full-notebook-export.md`](full-notebook-export.md)): same OAuth connection and token, uploads to
> a separate app-owned root **"Notesprout Exports"** (`ROOT_EXPORT_FOLDER`), never "Notesprout
> Backups". Connecting Drive from the export screen writes `driveAccountEmail` into the backup
> config so this screen shows "Connected", but deliberately does **not** flip `driveEnabled` — an
> export connection must not silently opt the device into backups.

---

## Backup Settings Screen

`BackupSettingsActivity` — reachable from the MainActivity overflow toolbar (Backup icon).

Sections:
- **Device folder name** — editable field (`etDeviceFolderName`) + "Save" (`btnSaveDeviceName`); used
  as the Drive subfolder.
- **Local backup** — status, "Choose folder…" button (`btnChooseLocal`), enable toggle. Picking a
  folder takes a persistable read+write URI permission and enables the slot in one step.
- **Google Drive backup** — status, "Connect Google Drive" / "Disconnect" buttons, enable toggle.
  "Disconnect" clears the stored refresh token as well as the config fields.
- **Actions** — "Back Up Now" (`btnBackUpNow`), enabled when at least one destination is ready;
  "Last backup: …" timestamp.
- **Restore** — "Restore from Backup…" (`btnRestore`); see [Restore](#restore).

"Back Up Now" is guarded by an `AtomicBoolean` to prevent concurrent runs. Progress is shown in an
`AlertDialog` updated by `onProgress`. On completion a summary dialog shows per-destination counts
and any errors. The whole run is wrapped in a `try/catch`: the engine guards each file copy, but its
own repo/index calls can still throw (index sealed underneath, SQLite error), and that must surface
as a failed-backup message rather than a crash mid-run.

**Debug builds** write into a `dev/` subfolder inside each destination root (LOCAL: `<tree>/dev/`;
DRIVE: `My Drive / Notesprout Backups / <deviceFolderName> / dev /`). Release builds write directly
to the destination root.

---

## Known Limitations

- Renaming the device folder orphans the old Drive subfolder — prior backups in the old folder are
  not migrated.
- Deleting a notebook does not remove its backup file (the needs-backup sweep skips deleted rows, it
  never reaps). Harmless for restore — the restored index simply doesn't reference the orphan — but
  the bytes accumulate. GC is future work.
- **Debug builds cannot restore from Drive.** Debug backups land in `<deviceFolderName>/dev/`, but
  `DriveRestoreSource` only treats a direct child of "Notesprout Backups" that *itself* holds a
  `notesprout.db` as a device folder, so a debug Drive backup is never listed. LOCAL restore is
  unaffected: the picker scans the chosen tree **and** one level of subfolders, so `dev/` is offered
  as a device. Release builds write to the destination root and restore normally on both.
- A notebook currently open in another Activity is backed up from its last cold/sealed state.
  Backup is launched from MainActivity (where notebooks are closed), so this is not expected in
  normal usage, but live-edit data is not flushed.
- **Drive backup requires a Google Cloud project** with the Drive API enabled and a Desktop-app
  OAuth client configured (see setup runbook above). This is a one-time manual step.
- Drive backups go to an app-created **"Notesprout Backups"** folder — the user cannot choose an
  arbitrary pre-existing Drive folder (that would require the full `drive` scope + Google's
  restricted-scope security assessment).
- SAF writes can be slow on large notebooks; progress updates keep the UI responsive.

---

## Restore

In-app restore is implemented. It **replaces the entire current library** — it is not a merge and not
a per-notebook import (that is what full-notebook import is for).

**Key classes:** `RestoreSource` (LOCAL/SAF or DRIVE, with device-folder selection) and
`RestoreEngine` (`data/backup/RestoreEngine.kt`).

**Choosing a backup.** "Restore from Backup…" asks for the source (Local folder / Google Drive), then
lists the device folders found there with their notebook counts, then requires an explicit
"Replace your library?" confirmation that names the backup and warns the recovery key will be needed.
Device enumeration is one level deep: SAF treats the picked tree as a device folder if it directly
holds a `notesprout.db`, and also scans its immediate subfolders; Drive scans the children of
"Notesprout Backups". Only `*.soil` names are taken as notebooks, so a leftover `.part` / `.old` from
a killed backup run is never mistaken for one.

**Staging-first, aside-swap commit** — the live library is never in a state with no intact copy:

1. Wipe + recreate `cacheDir/restore_staging`.
2. Fetch the backup's `notesprout.db` and every `.soil` (plus any `-wal` sidecar) into staging.
   **Every per-file result is checked — a single failure aborts the whole restore**, and each file
   streams to a `.part` name then renames, so a dropped connection never stages a truncated file.
   Drive listing is equally strict: a paging failure *after* the first page throws rather than
   returning a short list, because a truncated set would commit as the entire library. The live
   library is untouched if any of this fails.
3. **Validate + free-space gate:** probe the staged index and every staged `.soil` (reject a
   non-database; encrypted files pass, they can't be read deeper without the backup's key). Then
   require the staged payload's size **plus 64 MB of headroom** free on the library volume — the
   commit copies the staged set in while the old library still exists aside — else hard-fail with a
   message naming the shortfall.
4. Seal the index, then **move (rename) the live index + `Garden/` aside** into `restore_replaced/`,
   copy the staged Garden in, and install the staged index **last** as the commit marker. A failure
   inside this step rolls the aside copy back and reopens the index, so the app keeps working without
   a restart.
5. Only after the index is in place: clear the cached global passphrase **and all cached raw keys**
   (`KeyMaterial` / `KeySession` / `PassphraseStore`), then delete the aside copy.

**Crash recovery.** A kill mid-commit is repaired at launch by `RestoreEngine.recoverInterrupted`
(called from `BootstrapActivity`): aside present + no live index ⇒ roll the old library back; aside
present + live index present ⇒ the commit finished, discard the aside. Either way a leftover
`notesprout.db.part` from the interrupted install is deleted — it is stale on both branches. WAL
sidecars staged in step 2 travel with their `.soil` (see the backup side's un-checkpointable-notebook
handling).

**Restart into unlock.** The restored index is encrypted under the **backup device's** global
passphrase, which is not this device's. So the next launch necessarily lands in
`NotesproutIndex.PrepareOutcome.NEEDS_UNLOCK`. The success dialog therefore has a single
non-cancelable "Restart" action: it relaunches the app and hard-exits the process, landing on the
bootstrap gate to prompt for that library's recovery key. Clearing the cached keys in step 5 is what
makes this deterministic — a stale cached key would otherwise fail verification and look like
corruption.

**Entry point:** the Restore section of Backup Settings.

**Status:** the LOCAL/SAF backup **and** restore round-trip are validated end-to-end on G102
(2026-07-21), including the WAL-sidecar copy/delete branches and the mid-commit recovery. The DRIVE
path is built and shares the same engine/writer logic but has not been exercised on-device.

# Backup

> Referenced from `CLAUDE.md`. Covers destinations & layout, device identity, the needs-backup
> rule, run ordering, encrypted behavior, state storage, the Google Drive REST/OAuth path, key
> classes, the Backup Settings screen, and known limitations.
>
> **Restore — out of scope for Phase 1.** See the "Restore" stub at the end.

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

Generated once by `DeviceIdentity.defaultDeviceFolderName()`, persisted in `BackupConfig.deviceFolderName`,
user-editable in Backup Settings. Sanitization strips everything except `[a-zA-Z0-9_-]`. The same
name is used as the Drive subfolder name, so it must not contain path separators.

**Hardware serial is NOT used** — `Build.getSerial()` requires `READ_PHONE_STATE` (privileged) and
returns `"UNKNOWN"` on normal sideloaded builds.

---

## Filename Scheme (D5)

Backup files are named by **notebook UUID**, not display name:

- `<notebookUuid>.soil` — one per non-excluded notebook
- `notesprout.db` — the global index

UUID filenames give stable replace-in-place identity: renaming a notebook in the app does not
orphan its backup. The display name travels inside the `.soil` via `notebook_meta` for future
restore.

---

## Needs-Backup Rule (D8)

A notebook needs backup to destination *X* when **all** of:
- `excludeFromBackup == false`
- `lastBackedUp[X] == null` **OR** `notebook.updatedAt > lastBackedUp[X]`

The predicate lives in `BackupPredicates.kt` (`fun needsBackup(updatedAt, lastBackedUp, excluded): Boolean`).
Timestamps are per-destination (`lastBackedUpLocal` / `lastBackedUpDrive` on `NotebookObject`).
A failed copy does **not** stamp the timestamp — the notebook is automatically retried on the next
run.

---

## Run Ordering / Index Last (D9)

The engine processes notebooks first, then copies `notesprout.db` **last** — after all per-notebook
timestamps have been written. This ensures the backed-up index reflects the completed run. The
sequence:

1. Resolve destination directories (fail-fast per destination, not global abort).
2. Pre-flight DRIVE token fetch (`DriveAuth.getAccessTokenSilent`).
3. For each (notebook, destination) pair needing backup: copy `.soil`, stamp timestamp on success.
4. `NotesproutIndex.checkpointAndVacuum()` — flushes WAL, makes `notesprout.db` self-contained.
5. Copy `notesprout.db` to each enabled destination.
6. Persist `config.lastRunAt`.

---

## Encrypted Notebook Behavior (D10)

Encrypted `.soil` files are copied **as ciphertext** — no passphrase prompt, no decryption.
SQLCipher encrypts the entire file; a byte-level copy is sufficient. The restore path (future) will
prompt for the passphrase.

---

## State Storage (D11)

All backup state lives in `notesprout.db` (the global index):

- **Per-notebook timestamps** — `excludeFromBackup`, `lastBackedUpLocal`, `lastBackedUpDrive` fields
  on `NotebookObject` JSON. No migration needed; absent keys decode to defaults.
- **Global config** — a singleton `BACKUP_CONFIG` row (`id = BACKUP_CONFIG_ID`) holding
  `BackupConfig` JSON (mirrors the `CLIPBOARD` row pattern). Read/written by `BackupConfigStore`.

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
| `BackupPredicates` | `data/backup/BackupPredicates.kt` | `needsBackup(updatedAt, lastBackedUp, excluded)` |
| `BackupResult` / `DestResult` | `data/backup/BackupResult.kt` | Run summary: per-destination counts + errors |
| `BackupEngine` | `data/backup/BackupEngine.kt` | Orchestrates a full backup run on `Dispatchers.IO` |
| `SafBackupWriter` | `data/backup/SafBackupWriter.kt` | SAF/`DocumentFile` helpers for LOCAL writes |
| `DriveAuth` | `data/backup/DriveAuth.kt` | PKCE helpers, auth URL builder, token exchange, silent refresh |
| `DriveTokenStore` | `data/backup/DriveTokenStore.kt` | `EncryptedSharedPreferences`-backed refresh token storage |
| `DriveAuthActivity` | `ui/DriveAuthActivity.kt` | WebView OAuth activity; intercepts the redirect URI |
| `DriveApiClient` | `data/backup/DriveApiClient.kt` | Hand-rolled Drive REST v3 (`findChild`, `ensureFolder`, `uploadOrReplace`) |
| `DriveBackupWriter` | `data/backup/DriveBackupWriter.kt` | Engine-facing facade over `DriveApiClient` |
| `DeviceIdentity` | `data/backup/DeviceIdentity.kt` | `defaultDeviceFolderName()` — sanitized model + random suffix |

---

## Backup Settings Screen

`BackupSettingsActivity` — reachable from the MainActivity overflow toolbar (Backup icon).

Sections:
- **Device folder name** — editable field (`etDeviceFolderName`); used as the Drive subfolder.
- **Local backup** — status, "Choose folder…" button (`btnChooseLocal`), enable toggle.
- **Google Drive backup** — status, "Connect Google Drive" / "Disconnect" buttons, enable toggle.
- **Actions** — "Back Up Now" (`btnBackUpNow`), enabled when at least one destination is ready;
  "Last backup: …" timestamp.

"Back Up Now" is guarded by an `AtomicBoolean` to prevent concurrent runs. Progress is shown in an
`AlertDialog` updated by `onProgress`. On completion a summary dialog shows per-destination counts
and any errors.

**Debug builds** write into a `dev/` subfolder inside each destination root (LOCAL: `<tree>/dev/`;
DRIVE: `My Drive / Notesprout Backups / <deviceFolderName> / dev /`). Release builds write directly
to the destination root.

---

## Known Limitations (Phase 1)

- **Restore is not implemented.** The backed-up `.soil` files can be imported manually via the
  full-notebook import flow (share / open `.soil`), but there is no automated restore. Future work.
- Renaming the device folder orphans the old Drive subfolder — prior backups in the old folder are
  not migrated.
- Deleting a notebook does not remove its backup file. Restore / GC is future work.
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

## Restore (Future)

Restore is out of scope for Phase 1. The backed-up files are standard `.soil` and `notesprout.db`
files. A future restore flow would download/copy them to the device and run them through the
existing full-notebook import pipeline for `.soil` files, and a separate migration path for the
global index.

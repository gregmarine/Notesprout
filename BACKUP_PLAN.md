# Notesprout — Backup (Phase 1) Implementation Plan

> **Scope:** Back up `.soil` notebooks + the global index (`notesprout.db`) to a local device
> folder (via the Storage Access Framework, SAF) and/or to Google Drive (via the **Google Drive REST
> API v3 + Google Identity Services OAuth** — see Session 2.1; SAF is *not* used for Drive because the
> Drive `DocumentsProvider` is unavailable in the SAF picker on BOOX). **Backup only — restore is a
> separate future effort.** This document is the source of truth for the work; keep the per-session
> **Status** blocks current as you go.

---

## Decisions (locked — do not re-litigate)

| # | Decision | Rationale |
|---|---|---|
| D1 | ~~**SAF for both destinations.**~~ **SUPERSEDED by D12 for Drive.** LOCAL still uses SAF (`ACTION_OPEN_DOCUMENT_TREE` + `takePersistableUriPermission` + `DocumentFile`). Drive does **not** — its `DocumentsProvider` is not registered in the SAF picker on BOOX (confirmed G102), so Drive now uses the Google Drive REST API (Session 2.1). | SAF unified path was the original goal; reality on BOOX forced a native Drive API path for the DRIVE slot. |
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
| D12 | **The DRIVE slot uses the Google Drive REST API v3 + Google Identity Services (GIS) authorization** — *not* SAF. The S2 SAF "Choose folder…" UI for Drive is replaced (Session 2.1). LOCAL remains SAF. `BackupKind.LOCAL` → `SafBackupWriter`; `BackupKind.DRIVE` → Drive REST path. | SAF Drive picker is missing on BOOX; the REST API is the only reliable Drive path. |
| D13 | ~~**Exactly one new Gradle dependency: `com.google.android.gms:play-services-auth` (21.x).**~~ **SUPERSEDED by S2.2:** `play-services-auth` is removed. Drive auth is now a WebView OAuth 2.0 + PKCE flow with zero new Gradle dependencies (uses `HttpURLConnection` + existing `security-crypto` for refresh-token storage). Drive REST remains hand-rolled. | G102 reports `SERVICE_INVALID` for GMS; the GIS Identity API is blocked. WebView OAuth works on any device with a WebView engine. `security-crypto` was already a dependency for the global-passphrase Keystore cache. |
| D14 | **OAuth scope = `https://www.googleapis.com/auth/drive.file`** (per-file: the app sees/manages **only** files it creates). The app creates its own visible **"Notesprout Backups"** folder in the user's Drive; **there is no Drive folder picker.** | `drive.file` is *sensitive* but **not** *restricted* → no costly annual third-party security assessment (full `drive` scope would require it). Sufficient for backup since the app owns everything it writes. |
| D15 | ~~**No backend, no offline access, no refresh tokens.**~~ **UPDATED by S2.2:** No backend, no server-side component. The **refresh token is stored on-device** in `EncryptedSharedPreferences` (AES-256-GCM, master key in Android Keystore) via `DriveTokenStore`. Each backup run uses the refresh token to silently fetch a fresh access token via a POST to the Google token endpoint — no UI required. Access tokens still live in memory only and are never persisted, never logged, never put in Intent extras. | Without GMS's `AuthorizationClient`, there is no framework-managed silent re-auth. Storing the refresh token locally is the standard native-app pattern (RFC 6749 §10.3). `EncryptedSharedPreferences` is already a project dependency; the refresh token is treated with the same hygiene as encryption passphrases. |
| D16 | **Drive replace-in-place = search-then-update.** Drive permits multiple files with the same name in a folder, so each run **finds** the existing `<uuid>.soil` / `notesprout.db` by name within the device folder and **`PATCH`es its content** (stable file ID); only creates when absent. Folders are resolved find-or-create **every run** (no cached folder IDs) to avoid staleness. | Blind `create` would accumulate duplicate-named backups. Re-resolving folders each run is one cheap list call and eliminates a class of stale-ID bugs. |
| D17 | ~~**Drive is Google-Play-Services-gated.**~~ **SUPERSEDED by S2.2:** Drive works on **any device with a WebView engine** — no GMS required. The OAuth flow is a standard WebView that opens Google's consent page; no Play Services framework is involved. Drive section is now unconditionally available in the UI. | G102 has GMS installed but as `SERVICE_INVALID` (uncertified OEM build); the GIS API is blocked even though Play Services appears present. WebView OAuth is GMS-independent and works universally on Android. |

### Known Phase-1 limitations (document, don't fix)
- Renaming the device folder orphans the old Drive subfolder (no migration of prior backups).
- A notebook that is **currently open** while a backup runs is copied from its last cold/sealed
  state (backup is launched from MainActivity, where notebooks are closed). The live-edit window is
  not flushed mid-edit. Acceptable for v1; note in docs.
- Deleting a notebook does not remove its backup file. Restore/GC is future work.
- SAF writes to Drive can be slow; the progress UI must remain responsive (work on `Dispatchers.IO`).
- **Google Drive does not appear in the SAF folder picker on BOOX devices** (confirmed on G102) — the
  reason the DRIVE slot moved to the Drive REST API (Session 2.1, D12). The LOCAL/SAF slot can still
  target an SD card or any other SAF tree.
- **Drive backup goes to an app-created "Notesprout Backups" folder, not a folder the user picks**
  (D14, `drive.file` scope). Choosing an arbitrary pre-existing Drive folder would need the full
  `drive` scope + Google's restricted-scope security assessment — out of scope.
- **Drive requires Google Play Services** (D17). BOOX builds without GMS get LOCAL backup only.
- **Drive backup requires the user to have set up an OAuth client in Google Cloud Console** keyed to
  the app's package name + signing-cert SHA-1 (Session 2.1 Prerequisite). This is a one-time manual
  setup, documented in `docs/backup.md` (S4).

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
 │           driveTreeUri: String?       (LEGACY — unused after S2.1/D12; kept for back-compat)
 │           driveEnabled: Boolean = false  (now = "Drive API backup enabled")
 │           driveAccountEmail: String?  (display only; non-secret; added S2.1)
 │           lastRunAt: Long?            (device-local bookkeeping)
 │
LOCAL destination (SAF / DocumentFile tree):
   <localTreeUri>/<uuid>.soil ,  <localTreeUri>/notesprout.db

DRIVE destination (Google Drive REST API v3, resolved find-or-create each run):
   My Drive / "Notesprout Backups" / <deviceFolderName> / <uuid>.soil
                                                         / notesprout.db
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

## Session 2.1 — Google Drive via REST API + OAuth (replaces SAF for the DRIVE slot)

> **Implementer profile:** intended for Sonnet / medium effort. Follow this section literally; every
> class, endpoint, header, and config field is specified. Do **not** add the Google API client
> libraries (D13) — hand-roll the REST calls. Do **not** persist or log access tokens (D15).

**Goal:** The Drive section of Backup Settings stops using SAF and instead **connects a Google
account** via Google Identity Services (GIS), then reads/writes through the **Google Drive REST API
v3**. After this session the user can: tap **Connect Google Drive**, grant `drive.file` consent once,
see "Connected as <email>", toggle Drive on, and — wired in Session 3 — back up into
`My Drive / Notesprout Backups / <deviceFolderName> /`. **"Back Up Now" stays disabled here**; this
session delivers the auth + REST plumbing and the reworked Drive UI, not the run loop (that is S3,
which dispatches `BackupKind.DRIVE` to this code).

**Status:** ☑ Complete — `play-services-auth:21.6.0` added; `DriveAuth`, `DriveApiClient`, `DriveBackupWriter` created; `BackupSettingsActivity` Drive section reworked (Connect/Disconnect); `BackupConfig.driveAccountEmail` added; tests updated/added. Awaiting on-device integration test (user performs prerequisite GCP setup + Testing steps).

---

### Prerequisite — Google Cloud Console setup (one-time, performed by the user/Gardener)

This is **manual** and must be done before the OAuth flow will succeed. Document it verbatim in
`docs/backup.md` (S4). The implementer cannot do this from code; surface it in the plan and in the
"Connect" failure messaging.

1. **Create / pick a Google Cloud project** at <https://console.cloud.google.com>.
2. **Enable the Google Drive API**: *APIs & Services → Library → Google Drive API → Enable*.
3. **Configure the OAuth consent screen** (*APIs & Services → OAuth consent screen*):
   - User type **External**.
   - App name (e.g. "Notesprout"), user support email, developer contact email.
   - **Add scope** `.../auth/drive.file` (listed as *sensitive*, **not** *restricted* → no third-party
     security assessment required).
   - **Publishing status:** for personal multi-device use, leave it in **Testing** and add the
     Gardener's own Google account under **Test users**. (Testing mode caps refresh tokens at 7 days —
     irrelevant here because we fetch a fresh **access token** each run via silent `authorize()`, D15.
     For a public release, *Publish app* → standard verification for the sensitive scope.)
4. **Create OAuth 2.0 Client IDs of type _Android_** (*APIs & Services → Credentials → Create
   credentials → OAuth client ID → Android*). The Android client type matches the caller by
   **package name + signing-cert SHA-1**; no client-ID string is embedded in the app (we use the
   access-token-only path — no `requestOfflineAccess`, so no Web client ID is needed). Create **two**
   clients (the app ships two package names, both currently signed with the debug keystore per
   CLAUDE.md):
   | Package name | Build |
   |---|---|
   | `com.notesprout.android.dev` | debug (default dev install) |
   | `com.notesprout.android` | release |
   - **SHA-1 of the (debug) signing key** — both packages use `~/.android/debug.keystore`:
     ```sh
     keytool -list -v -keystore ~/.android/debug.keystore \
       -alias androiddebugkey -storepass android -keypass android
     ```
     Copy the `SHA1:` value into **both** Android OAuth clients. (When a real release keystore is
     adopted later, add a third client with that key's SHA-1 + `com.notesprout.android`.)
5. No `google-services.json` is required for this flow (Android OAuth client + access-token path).

> If consent fails with `ApiException` status `10` (DEVELOPER_ERROR), the SHA-1/package pair is not
> registered correctly — the single most common setup mistake. Surface a hint to that effect.

---

### Config & manifest changes

1. `AndroidManifest.xml` — add network permissions **above** `<application>` (none are declared
   today; the REST calls need INTERNET):
   ```xml
   <uses-permission android:name="android.permission.INTERNET" />
   <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
   ```
2. `app/build.gradle.kts` — add the **one** authorized dependency (D13), grouped with a comment in the
   spirit of the existing dependency comments:
   ```kotlin
   // Google Identity Services — OAuth authorization (drive.file access token) for Drive backup.
   // The ONLY Google dependency: Drive REST v3 is hand-rolled (HttpURLConnection + kotlinx.serialization),
   // no google-api-services-drive / GSON / Guava. See BACKUP_PLAN D13.
   implementation("com.google.android.gms:play-services-auth:21.6.0")
   ```
   (`21.6.0` is current as of this writing; use the latest stable `21.x` resolvable from `google()`. `play-services-auth` provides
   `com.google.android.gms.auth.api.identity.*` and `com.google.android.gms.common.api.Scope`. It is
   compatible with `minSdk 29` / `compileSdk 35`. Build arm64-only is unaffected — this is a Java/AAR
   dependency.)
3. `data/backup/BackupConfig.kt` — add **one** field (keep `driveTreeUri` for back-compat, now unused):
   ```kotlin
   val driveAccountEmail: String? = null,   // display only; non-secret. null = not connected.
   ```
   Place it after `driveEnabled`. No `@EncodeDefault` needed (nullable default decodes fine; existing
   rows omit it → `null`). Update `BackupConfigTest` round-trip to cover the new field.

> **`driveEnabled` semantics change (D12):** it now means "Drive **API** backup enabled" and may only
> be `true` when `driveAccountEmail != null`. The old `driveTreeUri` SAF value is ignored everywhere.

---

### Files to create

#### 4. `data/backup/DriveAuth.kt` — GIS authorization (access token acquisition)

`object DriveAuth`. Responsibilities: build the authorization request, gate on GMS, fetch access
tokens (silent for the engine; interactive resolution is launched from the Activity, item 7).

```kotlin
package com.notesprout.android.data.backup

import android.content.Context
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object DriveAuth {
    /** drive.file: per-file access to files THIS app creates/opens (D14). Sensitive, not restricted. */
    const val SCOPE_DRIVE_FILE = "https://www.googleapis.com/auth/drive.file"

    /** Result of a token attempt. Never log the token (D15). */
    sealed interface TokenResult {
        data class Token(val accessToken: String) : TokenResult
        /** Consent needed → must be resolved interactively from an Activity (item 7). */
        data class NeedsConsent(val pendingIntent: android.app.PendingIntent) : TokenResult
        data class Error(val message: String) : TokenResult
    }

    fun isPlayServicesAvailable(context: Context): Boolean =
        GoogleApiAvailability.getInstance()
            .isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS

    fun request(): AuthorizationRequest =
        AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(SCOPE_DRIVE_FILE)))
            .build()
    // NOTE: no .requestOfflineAccess(...) — access-token-only path, no server (D15).

    /**
     * Blocking-on-IO token fetch for the backup engine (S3). If consent was already granted this
     * returns a fresh token with NO UI; otherwise returns NeedsConsent so the caller can tell the
     * user to (re)connect in Backup Settings. Engine MUST call this on Dispatchers.IO.
     */
    suspend fun getAccessTokenSilent(context: Context): TokenResult = withContext(Dispatchers.IO) {
        try {
            val result: AuthorizationResult = Tasks.await(
                Identity.getAuthorizationClient(context).authorize(request())
            )
            if (result.hasResolution()) {
                TokenResult.NeedsConsent(result.pendingIntent!!)
            } else {
                val token = result.accessToken
                if (token.isNullOrBlank()) TokenResult.Error("No access token returned.")
                else TokenResult.Token(token)
            }
        } catch (e: Exception) {
            // Do NOT include any token material. ApiException status 10 == DEVELOPER_ERROR (SHA-1/pkg).
            TokenResult.Error(e.message ?: "Authorization failed.")
        }
    }
}
```

> `Tasks.await` is synchronous → only ever call `getAccessTokenSilent` from a coroutine on
> `Dispatchers.IO` (the `withContext` above guarantees it). The **interactive** first-time consent is
> launched from the Activity with `StartIntentSenderForResult` (item 7) — `Tasks.await` cannot drive a
> UI resolution.

#### 5. `data/backup/DriveApiClient.kt` — hand-rolled Drive REST v3 (D13/D16)

A class constructed with a valid access token. All methods run on the caller's IO context, use
`HttpURLConnection`, set `Authorization: Bearer <token>`, and parse responses with
`kotlinx.serialization`. **Never log the token or full request/response bodies.**

DTOs (top of file):
```kotlin
@Serializable private data class DriveFile(val id: String, val name: String? = null)
@Serializable private data class DriveFileList(val files: List<DriveFile> = emptyList())
@Serializable private data class CreateFolderBody(
    val name: String, val mimeType: String, val parents: List<String>,
)
@Serializable private data class UploadMeta(
    val name: String? = null, val parents: List<String>? = null,
)
@Serializable private data class DriveUser(val emailAddress: String? = null, val displayName: String? = null)
@Serializable private data class DriveAbout(val user: DriveUser? = null)
```

Constants:
```kotlin
private const val FOLDER_MIME = "application/vnd.google-apps.folder"
private const val FILES = "https://www.googleapis.com/drive/v3/files"
private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3/files"
private const val ABOUT = "https://www.googleapis.com/drive/v3/about?fields=user(emailAddress,displayName)"
const val ROOT_BACKUP_FOLDER = "Notesprout Backups"   // app-created, visible in My Drive (D14)
```

Public API (all `fun`/`suspend fun` as you prefer; engine calls them on IO):
- `fun accountEmail(): String?` — `GET ABOUT`; return `user.emailAddress`. Used to display the
  connected account (works under `drive.file`, no extra scope). Returns null on any failure.
- `fun findChild(name: String, parentId: String, foldersOnly: Boolean): String?` —
  `GET FILES?q=<q>&spaces=drive&fields=files(id,name)&pageSize=10`, where
  `q = "name = '<esc(name)>' and '<parentId>' in parents and trashed = false"` and, if
  `foldersOnly`, append `" and mimeType = '$FOLDER_MIME'"`. Return the first file's id or null.
  **Escape** single quotes in `name`: `name.replace("\\", "\\\\").replace("'", "\\'")`. URL-encode
  the whole `q`. (Under `drive.file` the listing only ever returns app-created items — exactly what we
  want.)
- `fun ensureFolder(name: String, parentId: String): String?` —
  `findChild(name, parentId, foldersOnly = true) ?: createFolder(name, parentId)`.
- `private fun createFolder(name: String, parentId: String): String?` — `POST FILES?fields=id` with
  `Content-Type: application/json; charset=UTF-8`, body =
  `Json.encodeToString(CreateFolderBody(name, FOLDER_MIME, listOf(parentId)))`. Parse `{ "id": ... }`,
  return id.
- `fun uploadOrReplace(name: String, parentId: String, source: File): Boolean` — the D16 core:
  1. `val existing = findChild(name, parentId, foldersOnly = false)`.
  2. **Resumable initiate:**
     - new file → `POST  UPLOAD?uploadType=resumable&fields=id`
     - existing → `PATCH UPLOAD/<existing>?uploadType=resumable&fields=id`
     Headers: `Authorization`, `Content-Type: application/json; charset=UTF-8`,
     `X-Upload-Content-Type: application/octet-stream`,
     `X-Upload-Content-Length: <source.length()>`.
     Body: for **new** → `UploadMeta(name = name, parents = listOf(parentId))`; for **existing** →
     `UploadMeta()` (empty `{}`; do **not** resend `parents` on PATCH — that would try to move it).
     On `HTTP 200`, read the **`Location`** response header = the **session URI**.
  3. **Upload bytes:** `PUT <sessionUri>` with `Content-Length: <source.length()>`; stream
     `source.inputStream()` → `connection.outputStream` with an 8 KB buffer (see `SafBackupWriter`'s
     copy for the buffer idiom). Use `setFixedLengthStreamingMode(source.length())` to avoid buffering
     the whole file in memory (large `.soil`). Accept `HTTP 200` or `201` as success.
  4. Return `true` on success; on any non-2xx or exception, `Log.e` (no token, no body) and return
     `false` — never throw into the engine's loop.
  - **Single-PUT** upload is fine for v1 (BOOX notebooks are modest). Chunked upload with
    `Content-Range` + `308 Resume Incomplete` is a documented future enhancement; note it in
    `docs/backup.md`.
- Small private helpers: `open(method, urlString): HttpURLConnection` (sets method, `Authorization`
  header, `connectTimeout`/`readTimeout` ~30 s, `doInput`), and `readBody(conn): String`. Treat
  `responseCode` 401/403 specially in `uploadOrReplace`/`findChild` by returning a failure the engine
  can map to "reconnect Drive" (e.g. a `DriveAuthExpired` signal — simplest: return false and let the
  engine's pre-flight token check (item 6) catch expiry first).

> **Why resumable, not multipart:** resumable handles large files and flaky e-ink Wi-Fi without
> re-sending, and gives a clean metadata-then-bytes split. `PATCH` on an existing file ID preserves
> the file's identity and revision history in Drive (true replace-in-place, D6/D16).

#### 6. `data/backup/DriveBackupWriter.kt` — engine-facing facade (mirrors `SafBackupWriter`)

`object DriveBackupWriter`. Gives the S3 engine a LOCAL-parallel surface so it can branch by
`BackupKind` cleanly. Holds **no** state; takes a `DriveApiClient` it is handed.

```kotlin
object DriveBackupWriter {
    /** Resolve (find-or-create, every run — D16) My Drive/Notesprout Backups/<deviceFolderName>. */
    fun resolveDeviceFolderId(client: DriveApiClient, deviceFolderName: String): String? {
        val root = client.ensureFolder(DriveApiClient.ROOT_BACKUP_FOLDER, "root") ?: return null
        return client.ensureFolder(deviceFolderName, root)
    }

    /** Replace-in-place one file into the device folder. fileName e.g. "<uuid>.soil" / "notesprout.db". */
    fun replaceFile(client: DriveApiClient, deviceFolderId: String, fileName: String, source: File): Boolean =
        client.uploadOrReplace(fileName, deviceFolderId, source)
}
```
(`"root"` is the Drive alias for My Drive; the top folder is created there, user-visible per D14.)

### Files to edit

#### 7. `BackupSettingsActivity.kt` — rework the Drive section from SAF to GIS connect

- **Remove** the Drive SAF launcher (`pickDriveTreeLauncher`) and its `onTreePicked(isLocal=false)`
  branch. Keep the LOCAL launcher untouched.
- **Add** a `StartIntentSenderForResult` launcher for consent resolution:
  ```kotlin
  private val driveConsentLauncher = registerForActivityResult(
      ActivityResultContracts.StartIntentSenderForResult()
  ) { result ->
      // After the consent UI, re-run the silent fetch to confirm + grab email.
      onDriveConsentReturned()
  }
  ```
- **`btnConnectDrive` click** (renamed from `btnChooseDrive`, item 8):
  ```kotlin
  if (!DriveAuth.isPlayServicesAvailable(this)) {
      toast("Google Drive backup requires Google Play Services, which isn't available on this device.")
      return
  }
  lifecycleScope.launch {
      when (val r = DriveAuth.getAccessTokenSilent(this@BackupSettingsActivity)) {
          is DriveAuth.TokenResult.NeedsConsent ->
              driveConsentLauncher.launch(
                  IntentSenderRequest.Builder(r.pendingIntent.intentSender).build())
          is DriveAuth.TokenResult.Token -> finishConnect(r.accessToken)   // already granted
          is DriveAuth.TokenResult.Error -> toast("Couldn't connect Drive: ${r.message}")
      }
  }
  ```
- **`onDriveConsentReturned()` / `finishConnect(token)`** — fetch the account email and persist it:
  ```kotlin
  // onDriveConsentReturned: re-call getAccessTokenSilent (now Token), then finishConnect.
  private fun finishConnect(token: String) = lifecycleScope.launch {
      val email = withContext(Dispatchers.IO) { DriveApiClient(token).accountEmail() }
      val config = withContext(Dispatchers.IO) {
          repository.ensureBackupConfig(DeviceIdentity.defaultDeviceFolderName())
      }
      val updated = config.copy(driveAccountEmail = email, driveEnabled = true)
      withContext(Dispatchers.IO) { repository.saveBackupConfig(updated) }
      applyConfigToUi(updated)
  }
  ```
  **Never store `token`** — only `email` (D15).
- **`btnDisconnectDrive` click** (new, item 8): clear `driveAccountEmail = null, driveEnabled = false`,
  persist, refresh. (Optional best-effort: `POST https://oauth2.googleapis.com/revoke?token=<token>`
  on IO if a token is handy — not required; clearing local state is the contract.)
- **`applyConfigToUi`** Drive branch — replace the SAF status logic:
  ```kotlin
  val connected = config.driveAccountEmail != null
  val gms = DriveAuth.isPlayServicesAvailable(this)
  binding.tvDriveStatus.text = when {
      !gms       -> "Google Play Services unavailable on this device"
      connected  -> "Connected as ${config.driveAccountEmail}"
      else       -> "Not connected"
  }
  binding.btnConnectDrive.isEnabled = gms && !connected
  binding.btnDisconnectDrive.isVisible = connected
  binding.switchDriveEnabled.isEnabled = gms && connected
  // re-bind the listener exactly as the existing local toggle does (clear → set isChecked → set listener)
  binding.switchDriveEnabled.isChecked = config.driveEnabled && connected
  ```
- The toggle persist path (`persistToggle(isLocal=false, ...)`) is unchanged — it flips
  `driveEnabled`. Guard: cannot enable when `driveAccountEmail == null` (UI already disables it).
- Keep `btnBackUpNow.isEnabled = false` this session (S3 enables it).

#### 8. `res/layout/activity_backup_settings.xml` — rework the Drive section

In the "Google Drive Backup" section (currently lines ~179–237):
- **Replace** `btnChooseDrive` ("Choose folder…") with `btnConnectDrive` (text **"Connect Google
  Drive"**, same `shape_bordered`/`stateListAnimator=@null`/inkBlack styling as the other buttons).
- **Add** `btnDisconnectDrive` (text **"Disconnect"**, `android:visibility="gone"`, same styling),
  placed after `tvDriveStatus` or beside Connect.
- Keep `tvDriveStatus` and `switchDriveEnabled` (same IDs).
- **Update the helper text** to: *"Backs up to a 'Notesprout Backups' folder in your Google Drive, in
  a per-device subfolder. Requires Google Play Services."* (Remove the SAF "Choose folder" framing.)
- No new colors/styles — reuse existing (`shape_bordered`, `@color/inkBlack`, `@color/inkLight`). To
  use `isVisible` from code, the binding gives the view; `androidx.core.view.isVisible` is already
  available via `core-ktx`.

#### 9. `data/backup/DeviceIdentity.kt` — (no change expected)

The device folder name from D4 doubles as the Drive subfolder name; sanitation already strips path
separators (`saveDeviceName` in the Activity). Confirm a Drive folder name can't contain `/` — the
existing sanitize regex covers it.

### Tests (`app/src/test`)

> The REST + GIS calls hit the network/GMS and **cannot** run as JVM unit tests. Keep tests to pure
> logic; verify the network path on-device (Testing steps).

10. `BackupConfigTest.kt` — extend the round-trip to include `driveAccountEmail` (set + null); confirm
    a legacy JSON string **without** `driveAccountEmail` decodes to `null` and that a row with
    `driveTreeUri` set still decodes (back-compat).
11. `DriveQueryTest.kt` (new) — extract the Drive `q` builder and the single-quote escaper into small
    pure functions in `DriveApiClient` (e.g. `internal fun buildChildQuery(name, parentId, foldersOnly): String`
    and `internal fun escapeDriveString(s): String`) and unit-test them: correct escaping of names
    containing `'` and `\`, folder-only clause present/absent, parent clause present. This is the only
    meaningfully testable Drive logic without a network.

### Status maintenance

Update ☑ per numbered item. Record the exact `play-services-auth` version resolved, and note on-device
whether silent re-auth returned a token without UI on the 2nd+ connect.

### Integration with Session 3 (read before S3)

S3's `BackupEngine` must **dispatch by `BackupKind`** (the S3 text's "SafBackupWriter for both" is
superseded by D12):
- **LOCAL** → existing `SafBackupWriter` path (S3 item 1) into `<localTreeUri>` root.
- **DRIVE** → **pre-flight once per run:** `DriveAuth.getAccessTokenSilent(context)`.
  - `Error` / `NeedsConsent` → record the DRIVE destination as a hard error
    ("Reconnect Google Drive in Backup Settings"), skip all DRIVE copies, **do not** stamp
    `lastBackedUpDrive`, and do not crash. (LOCAL still runs.)
  - `Token(t)` → build `DriveApiClient(t)`; `val devFolder = DriveBackupWriter.resolveDeviceFolderId(client, config.deviceFolderName)`
    (null → hard error, skip DRIVE). Then per notebook needing DRIVE backup:
    `DriveBackupWriter.replaceFile(client, devFolder, "<uuid>.soil", soilFile)` → on success
    `repo.markNotebookBackedUp(id, BackupKind.DRIVE, runStart)`. Finally upload `notesprout.db` last
    (D9) the same way. One access token (valid ~1 h) comfortably covers an entire run; do not refetch
    per file.
- The "Back Up Now" enable rule (S3 item 4) becomes: enabled when
  (`localEnabled && localTreeUri != null`) **or** (`driveEnabled && driveAccountEmail != null`).

### Testing steps (you perform; report back)

1. Complete the **Prerequisite** Cloud Console setup (both Android OAuth clients with the debug-keystore
   SHA-1; add the test Google account; Drive API enabled).
2. Clean build + install debug on **G102**:
   ```sh
   cd apps/notesprout_android && ./gradlew clean assembleDebug
   adb -s b7a46e13 install -r app/build/outputs/apk/debug/app-debug.apk
   ```
   Confirm the build succeeds with the new `play-services-auth` dependency (arm64-only APK unaffected).
3. Run unit tests: `./gradlew testDebugUnitTest` — all green (config round-trip + Drive query/escape).
4. Open **Backup Settings** → Drive section shows **"Not connected"** and **Connect Google Drive**.
   - On a device **without** GMS, it shows the "Google Play Services unavailable" message and Connect
     is disabled (test this on a non-GMS BOOX if available; otherwise note as untested).
5. Tap **Connect Google Drive** → Google account picker + `drive.file` consent appears → grant.
   Status becomes **"Connected as <email>"**, the Drive toggle enables, **Disconnect** appears.
6. Force-stop + relaunch → still "Connected as <email>" (email persisted; token is **not** persisted).
   Tap Connect-equivalent path again is unnecessary; verify the engine's silent token path later in S3.
7. **Tap Disconnect** → returns to "Not connected", toggle disables. Re-connect → no full re-consent
   if still granted (returns silently/fast) — note the behavior observed.
8. Confirm **"Back Up Now" is still disabled** (S3 wires it).
9. In `~/.android/debug.keystore`-misconfigured scenarios, verify the DEVELOPER_ERROR (status 10) hint
   shows (optional negative test).
10. Report: resolved `play-services-auth` version, consent UX on BOOX (the GIS bottom-sheet renders
    fine on e-ink?), whether the email displays, and any failures.

> On pass: Status → ☑, commit (no push). e.g.
> `🌱 Backup S2.1: Google Drive via REST API + GIS OAuth (drive.file)`.

---

## Session 2.2 — WebView OAuth replaces GIS (BOOX SERVICE_INVALID fix)

> **Trigger:** G102 (BOOX Go 10.3 Gen 2) reports GMS status `SERVICE_INVALID` (9). The GIS
> `Auth.Api.Identity.Authorization.API` is unavailable even though GMS is installed — the BOOX OEM
> build is not certified. Session 2.1's GIS flow is replaced end-to-end with standard OAuth 2.0 +
> PKCE via a WebView.

**Status:** ☑ Complete

### Prerequisite — Google Cloud Console changes (user action, replaces S2.1 prerequisite)

The Android OAuth client from S2.1 is no longer used. Create a new **Desktop app** client:

1. Go to *APIs & Services → Credentials → Create credentials → OAuth client ID*.
2. **Application type: Desktop app**. Name it e.g. "Notesprout Desktop".
3. **Redirect URI:** `http://localhost/oauth2callback` (add it under "Authorized redirect URIs").
4. Download / copy the **Client ID** and **Client secret**.
5. Add them as environment variables in `~/.zshenv` (see step-by-step in the session notes below).
   They are injected into `BuildConfig.DRIVE_CLIENT_ID` / `BuildConfig.DRIVE_CLIENT_SECRET` at build time via `System.getenv()`. **Never written to disk or committed to git.**
7. Keep the Drive API enabled and the OAuth consent screen configured from S2.1 (scope `drive.file`,
   user type External, your account as a test user).

> The Android OAuth client from S2.1 can be left in place or deleted — it is no longer used.

### Files changed

- `DriveAuth.kt` — complete rewrite: GIS removed; PKCE helpers, `buildAuthUrl`, `exchangeCodeForTokens`, `getAccessTokenSilent` (refresh-token path).
- `DriveTokenStore.kt` (new) — `EncryptedSharedPreferences`-backed refresh token storage.
- `DriveAuthActivity.kt` (new) — WebView OAuth activity; intercepts `http://localhost/oauth2callback`.
- `res/layout/activity_drive_auth.xml` (new) — back-button header + full-screen WebView.
- `BackupSettingsActivity.kt` — `driveConsentLauncher` → `driveAuthLauncher`; `connectDrive()` now just launches `DriveAuthActivity`; `disconnectDrive()` clears `DriveTokenStore`.
- `AndroidManifest.xml` — registers `DriveAuthActivity`.
- `app/build.gradle.kts` — removes `play-services-auth`; injects `DRIVE_CLIENT_ID`/`DRIVE_CLIENT_SECRET` from `local.properties` into BuildConfig.
- `BACKUP_PLAN.md` — decisions D13, D15, D17 updated.

### Testing steps

1. Complete the prerequisite above (Desktop app client + local.properties).
2. Build + install debug on G102.
3. Open Backup Settings → Drive section shows "Not connected", "Connect Google Drive" button.
4. Tap **Connect Google Drive** → Google sign-in WebView opens. Sign in + grant `drive.file` consent.
5. WebView closes → status shows "Connected as \<email>", Disconnect appears, toggle enables.
6. Force-stop + relaunch → still connected (email + refresh token persisted).
7. Tap **Disconnect** → status returns to "Not connected", token cleared.
8. Reconnect → no full re-consent (Google skips it if still granted); if re-consent shown, that's fine.
9. **"Back Up Now" still disabled** (S3).

> On pass: commit. e.g. `🌱 Backup S2.2: WebView OAuth replaces GIS (BOOX SERVICE_INVALID fix)`.

---

## Session 3 — Backup engine (copy + status tracking)

**Goal:** "Back Up Now" runs the full process: copy every notebook needing backup to each enabled
destination, stamp per-destination timestamps after each success, then copy `notesprout.db` last.
Per-notebook **Exclude from backup** toggle added to the MainActivity context menu.

**Status:** ☑ Complete — `SafBackupWriter`, `BackupResult`, `BackupEngine` created; `BackupSettingsActivity` wired (`btnBackUpNow` enabled by destination readiness, progress dialog, summary AlertDialog, `isBackupRunning` guard); "Exclude/Include from Backup" added to notebook context menu in `MainActivity`. One new dependency added: `androidx.documentfile:documentfile:1.0.1` (required for SAF tree navigation; not in transitive appcompat deps in `appcompat:1.7.0`).

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
   behavior (D10), state storage (D11), **the Google Drive REST/OAuth path (D12–D17): `drive.file`
   scope, app-created "Notesprout Backups" folder, GIS access-token flow, GMS gating, token
   hygiene**, key classes (`BackupConfig`, `BackupConfigStore`, `BackupEngine`, `SafBackupWriter`,
   `DriveAuth`, `DriveApiClient`, `DriveBackupWriter`, `DeviceIdentity`, `BackupKind`), the Backup
   Settings screen, and the **Known limitations** list. **Include the full Google Cloud Console
   setup runbook** (Session 2.1 Prerequisite: enable Drive API, consent screen + `drive.file` scope,
   two Android OAuth clients with the debug-keystore SHA-1, `keytool` command, Testing-vs-Published
   note, DEVELOPER_ERROR/status-10 troubleshooting). Note "Restore — out of scope (future)" stub at
   the end, mirroring the import stub style in `docs/full-notebook-export.md`.

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
  SAF **and** Drive directory name (the same sanitized name is the Drive subfolder).
- **Drive token expired / consent revoked** between runs: engine pre-flight `getAccessTokenSilent`
  returns `Error`/`NeedsConsent` → DRIVE recorded as a hard error ("Reconnect Google Drive"),
  timestamps not stamped, LOCAL still completes, no crash.
- **Drive offline / no network:** REST calls fail gracefully → DRIVE failures reported in the summary,
  `lastBackedUpDrive` untouched (auto-retry next run).
- **"Notesprout Backups" or the device subfolder deleted in Drive between runs:** `ensureFolder`
  re-creates it (find-or-create each run, D16); backups repopulate. No duplicate folders accumulate
  (search-then-create).
- **Duplicate-name safety:** re-running backup `PATCH`es the existing `<uuid>.soil`/`notesprout.db`
  rather than creating a second copy (D16) — verify exactly one file per name in the device folder
  after two runs.
- **GMS absent:** Drive section disabled with the explanatory message; LOCAL backup unaffected (D17).

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
| 2.1 | Google Drive via REST API + GIS OAuth (replaces SAF for DRIVE) | ☑ Complete |
| 2.2 | WebView OAuth replaces GIS (BOOX SERVICE_INVALID fix) | ☑ Complete |
| 3 | Backup engine + per-notebook exclude | ☑ Complete |
| 4 | Docs, edge cases & polish | ☐ Not started |

**Branch:** `seed` · **Test device:** G102 (`b7a46e13`) · **Commit after each session only once the
user confirms tests pass. Never push.**

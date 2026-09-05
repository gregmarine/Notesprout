# DRIVE_PLAN.md — Arc 25 "Drive" (Notesprout SN, branch `ratta`)

**Standalone plan for the Google Drive extension.** This file is the cross-session memory for the
arc — read it whole at every phase start, together with the root `CLAUDE.md` and
`apps/notesprout_ratta/CLAUDE.md`. **Do not load `RATTA_PLAN.md` for this arc** unless a standing
trap needs checking; its protocol and traps are summarized below so this file is enough.

**Status:** planned 2026-09-04 · V1 ⬜ · V2 ⬜ · V3 ⬜ · V4 ⬜ · V5 ⬜ · V6 ⬜

---

## What this arc is

og Notesprout (`apps/notesprout_android` — reading reference, **no code copied**) backs up to and
exports to Google Drive through a hand-rolled REST v3 client and a WebView OAuth 2.0 + PKCE flow
(`docs/backup.md` § "Google Drive REST/OAuth Path", `data/backup/Drive*.kt`,
`export/DriveFolderPickerDialog.kt`, `export/ExportDelivery.kt`, `DriveAuthActivity.kt`). SN gets
the same ability **as an extension**: a new module `:ext-drive` (**NSE · Google Drive**) serving
SN's **EIGHTH extension point**, a *generic* cloud-storage point the host consumes for three
existing features — **export destination, backup destination, import source**.

The host stays what it is today: **no INTERNET permission**, no OAuth, no client secret. The
extension is the only networked process in the app. Exporters, importers and every other
extension are untouched — they never learn a cloud exists (see "Why no extension is aware").

## Decisions (wizard 2026-09-04 — all binding)

| # | Decision | Answer |
|---|---|---|
| 1 | Eighth point | **Granted.** Arc 25 "Drive". Module `:ext-drive`, label `NSE · Google Drive`, package `com.symmetricalpalmtree.notesproutsn.drive`. |
| 2 | Point shape | **Generic cloud storage**: `ACTION_CLOUD_STORAGE` + `ACTION_CLOUD_STORAGE_SCREEN`. Contract speaks folders/files/bytes, never Drive terms. Drive is the first provider; a future provider is a new extension, not a new point. |
| 3 | OAuth owner | **The extension owns all of it.** Connect is a tier-2 screen in `:ext-drive` (WebView PKCE, Chrome UA spoof, `http://localhost/oauth2callback` redirect intercepted). `DRIVE_CLIENT_ID` / `DRIVE_CLIENT_SECRET` from the same shell env vars og uses, compiled **only** into the extension APK (`BuildConfig`). Refresh token lives in the **extension store** (host-encrypted `Garden/<pkg>.db`). Host sees status (connected + account email) and file ops only. |
| 4 | Consumers this arc | **Export destination · Backup destination · Import source.** No whole-library restore (SN has no restore of any kind — its own future arc). |
| 5 | Drive tree | **Own root**: `My Drive / Notesprout SN / Exports/…` and `/ Backups/<device folder>/`. Never og's "Notesprout Backups" / "Notesprout Exports". Note: `drive.file` visibility is per OAuth client, and SN shares og's client, so SN *can* see og's trees — it writes only under its own root by name and never lists outside it. |
| 6 | Extension awareness | **None — host-only.** No presence extra, no host-side cloud stub. See "Why no extension is aware". |
| 7 | Cloud picker | **Host-drawn** folder/file browser (e-ink list dialog, og's `DriveFolderPickerDialog` shape): the host asks the extension to `list` a path and draws the rows itself. Export: pick/create a subfolder under `Exports/`, filename from `ExportNaming`. Import: browse `Exports/` and `Backups/`, tap a file. |
| 8 | Connect UI | **Backup screen Cloud section** (status line with account email · Connect / Disconnect · enable toggle) **+ inline offer**: Export and Import show the cloud choice whenever a provider is installed; if not connected, tapping it offers Connect right there. |
| 9 | Debug tree | **Separate root `Notesprout SN Dev`** for debug builds (the extension's own `BuildConfig.DEBUG` picks the root name). Release: `Notesprout SN`. |
| 10 | API version | `ExtensionContract.API_VERSION` **7 → 8**; `ACTION_CLOUD_STORAGE` floored at **8** (`MIN_API_VERSION_FOR_CLOUD`, the calendar's per-action precedent). No other floor moves, so no existing door vanishes. |
| 11 | App version | Stays `0.1.0-ratta`. |
| 12 | Code review | **None in this arc** — no `/code-review` on any phase (the user's call). |
| 13 | Device | **Nomad only** (SNN `SN078D10012852`). **Sonnet** drives the walks, not Haiku. |
| 14 | Scope | OAuth scope `https://www.googleapis.com/auth/drive.file` — sensitive, not restricted; the app sees only what it created. |

## Why no extension is aware

An export is host-driven end to end: the host draws the chooser from `describe()`, prepares the
source, picks the destination, opens **two fds** and hands them to the exporter in one call; the
exporter only streams source → destination. Import is the mirror. Backup is host-only code. So a
cloud destination changes only *where the host's write fd points* — `NSE · Soil Export`,
`NSE · PDF Export` and `NSE · Document` need zero changes and cannot tell a Drive upload from a SAF
file. **Intended future shape, if an extension ever needs cloud for itself** (recorded, not
built): a host-side `ICloudHost` stub minted per showing and revoked with the unbind
(`IDocumentHost`'s recipe), plus a presence boolean extra on the tier-2 launch
(`EXTRA_CALENDAR_SCRATCH_PAD_AVAILABLE`'s precedent). Whoever needs it builds it, under a fresh
user decision.

## The seam (designed at V1 — the contract this arc builds against)

**Point:** `ACTION_CLOUD_STORAGE` (service) + `ACTION_CLOUD_STORAGE_SCREEN` (the connect screen;
`HostCallerCheck.enforceActivity`, launched only by an `ActivityResultLauncher`). Both actions go in
the host's `<queries>` block (arc 21 / W1 trap — missing one reads as a signature mismatch).

**Store-taking, bind-per-call** (the tag manager's second call shape; the store rides every call,
minted per bind, uid-bound). No held bind: every operation is one Binder call sized by
on-device measurement, because a Binder call cannot be cancelled.

```
interface ICloudStorage {
    CloudStatus status(IExtensionStore store);               // connected? accountLabel; never blocks on network
    void disconnect(IExtensionStore store);                  // revoke best-effort + forget token
    CloudEntry[] list(IExtensionStore store, in String[] path);          // folders+files at path (under the root)
    CloudEntry ensureFolder(IExtensionStore store, in String[] path);    // find-or-create each segment
    CloudEntry upload(IExtensionStore store, in String[] path, String name, String mime,
                      in ParcelFileDescriptor source, long expectedBytes);   // replace-by-name; returns the entry as Drive reports it
    long download(IExtensionStore store, String entryId, in ParcelFileDescriptor destination);  // bytes written, fsynced
    void delete(IExtensionStore store, String entryId);
}
```

Wire types (`:extension-api`, `requireValid` in constructors, both directions):
`CloudStatus(connected: Boolean, accountLabel: String, providerName: String)` ·
`CloudEntry(id, name, isFolder, sizeBytes, modifiedAt)` · `path` = folder **names** under the
provider's root, bounded (`CloudContract.MAX_PATH_DEPTH` 8, `MAX_NAME_CHARS` 255, no `/`, no
control chars). **No secret, no device path, no URL ever crosses the seam.** Only
`SecurityException` / `IllegalArgumentException` / `IllegalStateException` cross a stub;
`IllegalStateException("not connected")` and `IllegalStateException("network")` are the two the
host distinguishes for its dialogs. Account email is user content: never logged on either side.

Extension store: `DriveSchema.V1` = `account(key TEXT PRIMARY KEY, value TEXT)` — refresh token,
account label, cached root folder ids. Every SQL string in `DriveSql`. Pre-open the store on IO
before the first bind (cold KDF ≈ 3 s).

Host side: `ExtensionRegistry.cloud()` (single provider — with more than one installed, the first
in discovery order and a logged warning; a chooser is a future decision), `CloudClient` (bind per
call, timeouts as a table in `CloudTimeouts` with the on-device measurement recorded beside each),
`CloudBrowserDialog` (the host-drawn list), `CloudPrefs` (index prefs row: cloud backup enabled,
device folder name, cloud stamp map — a second map, never the SAF one).

## Phases

Recipe for every phase: **Fable** plans and writes the seam/crypto/engine seams · **Opus** the
feature code · **Sonnet** scaffold, layouts, resources, docs, **and the Nomad walks** · ≤ 5
background agents. Gate: JVM tests for all pure logic, a Sonnet walk for what adb can see, a
**short numbered user checklist** for what it cannot (every OAuth sign-in, every SAF pick, live
ink). Then docs / memory / `CLAUDE.md`, **commit + push**, user runs `/clear`. **No code review.**

### V1 ⬜ — Seam + scaffold
- `:extension-api`: `CloudContract`, `ICloudStorage.aidl`, `CloudStatus` / `CloudEntry`
  (parcelables + tests), `API_VERSION` 8, `MIN_API_VERSION_FOR_CLOUD` 8 in the floor map, the
  ledger line in `ExtensionContract`'s KDoc.
- Host: `<queries>` for both actions, `ExtensionRegistry.cloud()`, `CloudClient` skeleton with
  `status()` only, `CloudTimeouts` table (placeholders marked UNMEASURED).
- `:ext-drive` module: manifest (INTERNET, service exported + signature-checked, screen
  `exported="true"` behind `HostCallerCheck`), `DriveService` answering `status()` = not connected,
  `DriveSchema.V1` + `DriveSql`, `BuildConfig` fields from the env vars (blank → status reports
  `providerName` with a "not configured" flag the host dialogs on), root name by build type.
- Debug menu: "Cloud status" line (the one on-device proof of discovery + bind).
- Tests: contract/wire tests, floor map test, `StoreSql` acceptance of `DriveSql` strings.
- Walk: both APKs installed, debug menu shows the provider discovered and `not connected`.
- **Questions at phase start:** none pending (wizard complete).

### V2 ⬜ — Connect
- `:ext-drive`: `DriveAuth` (PKCE verifier/challenge, auth URL, code exchange, silent refresh —
  pure parts JVM-tested), `ConnectActivity` (WebView, Chrome UA, redirect intercept, result codes;
  no extras on the Intent), token + label persisted through the store, `DriveApi` REST core
  (`GET about`, find/ensure folder, list, multipart upload ≤ 5 MiB + resumable above, download,
  delete, replace-by-name), `status()` real, `disconnect()`.
- Host: Backup screen **Cloud section** (status line · Connect / Disconnect · enable toggle;
  GONE when no provider installed — never disabled), launch via `ActivityResultLauncher`.
- User checklist: sign in on the Nomad (agents cannot type into the WebView, and it is your
  account); confirm the email shows; Disconnect; reconnect.
- Measure on device: `status`, `list`, `upload` 1 MiB / 20 MiB, `download` — fill `CloudTimeouts`.

### V3 ⬜ — Export to cloud
- Export screen: **Destination** row (Local file / <providerName>), GONE without a provider;
  cloud + not connected → inline Connect offer. Cloud path: `CloudBrowserDialog` over
  `Exports/` (browse, New folder…), then the flow writes the exporter's output to
  `cacheDir/export/out.<ext>` through the **same two-fd call** (destination fd on the cache file),
  verifies the count, then `upload(...)`. Verification per `ExportVerification`: the returned
  `CloudEntry.sizeBytes` corroborates; disagreement = *check the file*, never delete (the arc-15
  rule). `lastExporter` still written from OK only. Failure table grows cloud rows.
- Walk: Sonnet drives everything up to the upload (browser dialog, folder create) and verifies via
  shell that the cache is wiped; the actual sign-in stays a checklist item, the upload result is
  checked by the user in Drive.

### V4 ⬜ — Backup to cloud
- `BackupEngine` grows a second destination (`CloudDestination` beside the SAF one): same
  incremental stamp rule, its **own** stamp map, index last, WAL-alongside rule (seal first; a
  `-wal` still holding frames refuses that file), extension stores included (W5), per-device
  subfolder `Backups/<device folder>/` with the folder name editable on the Backup screen
  (`NameRules` charset, no separators). "Back up now" runs local then cloud when both are
  enabled; the status line reports both. Cloud upload replaces by name (never duplicates —
  og's find-then-update rule).
- Walk: enable cloud backup, run, verify stamps and status line; user confirms files in Drive.

### V5 ⬜ — Import from cloud
- Library Import: source choice (Local file / <providerName>) when a provider is installed;
  `CloudBrowserDialog` over `Exports/` + `Backups/` (files only tappable); `download` into
  `cacheDir/import/` then the **unchanged** import pipeline (probe → unlock → re-key → placement →
  remap → staged Garden write) with the importer matched by extension. Cache wiped in `finally`.
- Walk: Sonnet drives the browser; user checklist covers the pick of a real file.

### V6 ⬜ — Docs + freeze
- `docs/cloud.md` (the feature: seam, tree, connect, three consumers, timeouts table, failure
  table), `docs/extensions.md` (the eighth point section + boundary audit rows),
  `docs/export.md` / `docs/backup.md` / `docs/import.md` (cloud paragraphs), app `CLAUDE.md`
  (THIRTEEN modules, EIGHT points, API 8), root `CLAUDE.md` branch line, `RATTA_PLAN.md` gets a
  one-line pointer to this file, memory. No code review (decision 12).

## Protocol summary (from RATTA_PLAN.md — so that file need not be loaded)

- One phase per session. Flip `⬜ → 🔄` at start, record an **Outcome** at close.
- Commit + push only when tests pass or the user gives the all-clear, after docs/memory are in.
- **File tools can land a raw NUL byte** — byte-scan changed files for `\x00` before calling a
  phase done.
- Extension seam laws: only three exception types cross a stub; caller check **inside** the
  `try` whose `finally` closes fds; Binder calls cannot be cancelled — timeouts by measurement;
  pre-open the store on IO; an extension writes nothing to disk itself, ever.
- Device: Supernote swallows `adb shell input text`; a SAF pick and a WebView sign-in cannot be
  driven by adb — user checklist. Verify `mResumedActivity` before any screencap conclusion;
  the Nomad sleeps behind a PIN (black screencap = ask the user). Walk the **`.dev`** package.
- Chrome: action buttons on the top bar after Cancel; bottom bars pager-only; GONE never disabled;
  toast only confirms, a dialog explains; a screen that explains then leaves does so on the
  dialog's dismiss; TopGuard 0 on Ratta; Tabler icons only; no colour.
- `adb push` into `Android/data` deletes the target — push to `/data/local/tmp` then `shell cp`.

## Standing traps specific to this arc (from og's Drive work)

- Google refuses OAuth in a WebView identifying as Android WebView (`disallowed_useragent`) —
  spoof a Chrome UA **before** `loadUrl()`.
- The OAuth client is the **Desktop app** type; the secret is not actually secret for that type
  (Google's stated model) but still never crosses the seam and never lands in a log.
- Drive allows same-named siblings — every upload is find-then-update, never blind create.
- A cloud provider's metadata can lag its own write — corroborate, never delete on disagreement.
- The Supernote suppresses the share sheet; direct upload is the only Drive path there.

## Ledger

(Outcomes appended per phase.)

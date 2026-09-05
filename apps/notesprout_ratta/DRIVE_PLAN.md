# DRIVE_PLAN.md — Arc 25 "Drive" (Notesprout SN, branch `ratta`)

**Standalone plan for the Google Drive extension.** This file is the cross-session memory for the
arc — read it whole at every phase start, together with the root `CLAUDE.md` and
`apps/notesprout_ratta/CLAUDE.md`. **Do not load `RATTA_PLAN.md` for this arc** unless a standing
trap needs checking; its protocol and traps are summarized below so this file is enough.

**Status:** planned 2026-09-04 · V1 ✅ (2026-09-04) · V2 ✅ (2026-09-04) · V3 ✅ (2026-09-04) · V4 ⬜ · V5 ⬜ · V6 ⬜

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

### V1 ✅ — Seam + scaffold (2026-09-04)
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

### V2 ✅ — Connect (2026-09-04)
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

### V3 ✅ — Export to cloud (2026-09-04)

**Design (Fable, 2026-09-04 — binding for the phase).**

- **Destination row** on the Export screen, host-drawn in `render()` after the exporter's options
  and before the passphrase block: caption *Destination*, two radios — *File on this device* /
  `<providerName>` (the provider's own name from `status()`, else the extension label). The row is
  **GONE without a provider** (`ExtensionRegistry.cloud()` re-asked in `loadCandidates`, so every
  resume re-asks, as the exporters are); a standing *cloud* answer is forced back to *local* whenever
  the row is not on screen (the `documentSource` rule). The answer is host state: saved/restored,
  `KEY_DESTINATION`. Pure rules in `export/ExportDestination.kt` (JVM-tested): row visible? · the
  forced-back rule · which tap does what (below).
- **`status()` at discovery**, remembered like `hasDocument` is NOT — it is re-asked on every
  discovery because the connect door changes it. A provider that does not answer keeps the row (GONE
  is for *not installed* only); its label is the extension's.
- **Tapping the cloud radio**: connected → select. `!configured` → the Backup screen's *not set up*
  problem dialog. Otherwise (not connected, or status unanswered) → the **inline Connect offer**: a
  two-button dialog *Connect to <provider>?* [Connect] [Cancel]; Connect opens `CloudConnectEntry`
  (registered in `onCreate` — a launcher may not register later); its `onChanged` re-runs discovery
  and, when the result was `RESULT_OK` and the fresh status says connected, selects the cloud radio.
  `CloudConnectEntry.close()` in `onDestroy` (the Backup screen's backstop).
- **Export tap, cloud**: the secret checks run exactly as today, then instead of the SAF picker the
  **`CloudBrowserDialog`** opens over `Exports/` (busy latched from the tap; a cancel unlatches and
  drops the typed secrets, the picker-cancel rule). The dialog answers a folder **path** (names under
  the root, `["Exports", …]`) plus the listing it last drew. Filename = `ExportNaming.suggestedFileName`
  — fixed, not editable (decision 7). If that listing already holds a **file of that name**, a
  *Replace <name>?* [Replace] [Cancel] dialog stands in for SAF's overwrite confirmation — upload is
  replace-by-name, and a silent replace is not the family's way.
- **`CloudBrowserDialog`** (`cloud/CloudBrowserDialog.kt` + `dialog_cloud_browser.xml`, pure rules in
  `cloud/CloudBrowserRules.kt`): the host-drawn list, **shared with V5** — `Mode.PICK_FOLDER` (V3:
  folders enter on tap, files are drawn but inert, the action is *Save here*) and `Mode.PICK_FILE`
  (V5 builds it: files tappable, no action button). Full-screen e-ink dialog in the Contents dialog's
  shape: top bar = Up arrow · breadcrumb `<provider> › Exports › …` · Cancel · **Save here** (action
  after Cancel, top bar); 1 dp rule; rows paged like the Contents dialog (bottom bar pager-only, no
  scroll); first row of every folder is **New folder…** (`NameDialog`, `CloudArgs.requireName` says
  why a name will not go; a name already listed as a folder just enters it; otherwise
  `ensureFolder(path + name)` then enter). **Browsing creates nothing** — the browser only ever
  `list`s; `Exports/` itself is made by the upload on the way. Each navigation is one `list` under
  a *Loading…* body; `NOT_CONNECTED` closes the browser into the Connect offer; `NETWORK` and a
  no-answer are problem dialogs over the browser, which stays where it was. Up at `Exports/` does
  nothing (Cancel is the way out). Folder rows take Tabler `folder`, files `file-text` (present);
  a `folder-plus` for the New row (download if absent — outline, 24, stroke 2).
- **The cloud flow** (`runExport` grows a `Destination` sealed type: `Saf(uri)` / `Cloud(path, name,
  mime)`; the prepare → key/render/assemble → two-fd `export()` sequence stays ONE sequence in
  `ExportActivity` — the file's over-800 reason is extended, not split): the destination fd is
  `cacheDir/export/out.<ext>` opened `rwt` **after** prepare (prepare wipes the directory); the
  exporter never learns it. Verification per `ExportVerification` with the cache file's own length
  as the one destination account. Then stage *Uploading to <provider>…* and
  `CloudClient.upload(path, name, mime, pfd(out), out.length())` under `uploadBudgetMs`. The returned
  `sizeBytes` corroborates: agree → *Exported* (body names the provider) and `lastExporter` written;
  disagree → the arc-15 *check the file* dialog, **never** a delete, `lastExporter` NOT written
  (the SAF UNCONFIRMED rule). Pure verdict in `ExportVerification.cloudVerdict(reported, uploaded)`.
- **Failure rows (cloud)**: before the upload, every existing failure message stands and nothing is
  in the cloud — the note is *Nothing was uploaded.* · `CloudNotConnectedException` → *no account is
  connected; connect and export again* (offer Connect from the dialog) · `CloudNetworkException` →
  *<provider> could not be reached; nothing was uploaded; try again* · plain
  `ExtensionCallException` (no answer, timeout) → *the provider did not answer; the file may or may
  not have arrived — check <provider> before exporting again* (no delete: the upload is
  replace-by-name, so a retry is safe). No remote delete anywhere in this phase.
- **Cache hygiene**: `ExportArtifact.clean` in the existing `finally` takes `out.<ext>` with the
  artifact; `CloudClient` closes the upload fd. Nothing else new touches disk.
- **Tests**: `ExportDestination` rules · `CloudBrowserRules` (crumb text, paging, the
  same-named-file and same-named-folder lookups, the New-folder outcome) · `cloudVerdict`. No code
  review (decision 12).
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

### V1 — Seam + scaffold (2026-09-04) ✅

**Outcome.** The eighth point exists and is discoverable on the Nomad. `:extension-api`:
`CloudContract` (actions, `MIN_API_VERSION_FOR_CLOUD` 8, path/name/id/MIME checks, the two verbatim
refusals `NOT_CONNECTED` / `NETWORK`), `CloudStatus` (`connected · configured · accountLabel ·
providerName` — **`configured` is a fourth field**, added so a blank-credentials build reports itself
rather than offering a Connect that cannot work), `CloudEntry`, `ICloudStorage.aidl` (seven methods
as designed), `API_VERSION` 7 → 8, the cloud row in the floor map. Host: `<queries>` for both
actions, `ExtensionRegistry.cloud()` (first-wins + `Log.w` on a second provider), `CloudClient`
(`status()` only — store pre-opened on IO, one bind, verbatim refusal mapping to
`CloudNotConnectedException` / `CloudNetworkException`), `CloudTimeouts` (eight rows incl.
`DELETE_MS`, all UNMEASURED with the V2 measurement named beside each), debug menu **"Cloud status"**
dialog. `:ext-drive` (`NSE · Google Drive`, package `com.symmetricalpalmtree.notesproutsn.ext.drive`
— the family's `.ext.<name>` spelling, not the wizard table's `.drive`; same meaning): INTERNET, the
service at API 8, `ConnectActivity` behind `HostCallerCheck.enforceActivity` (cancels — V2's
WebView lands there), `DriveSchema.V1` = `account(key, value)`, `DriveSql` (four statements),
`DriveStore`, `DriveService` (`status()` real from the store; `disconnect()` clears; the five file
ops validate, close their fds in `finally`, then refuse `NOT_CONNECTED`), Tabler "cloud" icon,
`DRIVE_CLIENT_ID` / `DRIVE_CLIENT_SECRET` compiled from the env, `ROOT_FOLDER_NAME` per build type.

**Tests.** 2087 → **2119 JVM tests/variant** (+14 contract, +6 `DriveSql`, +6 `DriveStore`, the
floor test grown). No code review (decision 12).

**Walk (Nomad, `.dev`).** Both APKs installed; Debug tools → Cloud status → `Provider: NSE · Google
Drive Dev (…ext.drive.dev, api 8) · Name: Google Drive · Configured: yes · Connected: no ·
Account: —`. Logcat: `ExtensionRegistry … CLOUD_STORAGE: 1 provider(s) of 1 candidate(s)`,
`DriveService: status: configured=true connected=false`, `CloudClient: status … in 717 ms` (cold
store open included host-side — the first `STATUS_MS` data point). `Garden/…ext.drive.dev.db`
minted by the pre-open. No user checklist this phase (nothing adb cannot see).

**Traps met.** The Write tool landed a raw BEL byte inside a Kotlin `'\u0007'` char literal and a raw
control char inside a `' '` — the byte-scan caught both (the standing trap, now seen on a *char
literal*: spell control characters as `'\uXXXX'` and scan). `am start` of the bootstrap on a device
whose foreground was Ratta Settings needed a second `am start` — the first only raised the task.

### V2 — Connect (2026-09-04) ✅

**Outcome.** An account connects, disconnects and reconnects on the Nomad, and every file op on the
seam is real. **The seam grew two tail methods** — `beginConnect(store)` / `endConnect()` — because
the sign-in screen must write the token it wins into a store only the host can lend: Connect is the
tier-2 recipe (the tag manager's held bracket, `CloudConnectClient.open/finish` host-side,
`ConnectSession` extension-side); file ops stay bind-per-call. No API bump (the point was born this
arc, nothing shipped). `:ext-drive` takes **kotlinx.serialization** (already on the graph via `:app`,
no new library) — V1's "hand-rolled JSON" note is superseded. `DriveAuth` (pure PKCE/OAuth core, the
RFC 7636 vector in its tests) · `DriveHttp` (`HttpTransport` seam, the one production impl over
`HttpURLConnection`; every transport failure is the verbatim `NETWORK`) · `DriveTokens` (access token
**in memory only**, refresh from the store; `invalid_grant` forgets the account → `NOT_CONNECTED`) ·
`DriveApi` (REST v3: about, find/create/ensure, paged+sorted+truncated list, multipart ≤ 5 MiB /
resumable above, replace-by-name, download with fsync, delete; root id cached, re-resolved once on
404) · `DriveOps` (the testable body the service delegates to) · `ConnectActivity` (WebView, Chrome UA
before `loadUrl`, redirect intercepted, `RESULT_OK` only after both store writes; consent declined =
plain cancel; every other failure a dialog that leaves on dismiss). Host: `CloudClient` whole
(`CloudArgs` refuses before any bind), `CloudConnectEntry`, `CloudWording`, the Backup screen's
**Cloud section** (GONE without a provider; status line · "Back up to <provider>" tick persisted as
`BackupConfig.cloudEnabled` — **the plan's `CloudPrefs` row is superseded by growing this row**; V4
adds the device folder + second stamp map there · Connect/Disconnect), debug menu **"Cloud probe"**
(the measurement tool; `Exports/probe/`, deletes what it wrote).

**Measured (Nomad, wifi) → `CloudTimeouts`.** status 772 ms cold / 51 warm → 4 s · ensureFolder
(2 segments + root, all created) 3 981 ms → 30 s · list 530/805/1 056 ms at depth 0/1/2 → 20 s ·
upload 1 MiB 2 901 ms → 60 s · upload 20 MiB 6 435 ms (≈ 4.3 MB/s) → **120 s per 20 MiB** (was
180 s) · download 20 MiB 4 343 ms → 120 s flat · delete 729 ms → 15 s · disconnect ≈ 160 ms → 15 s.
Size corroboration agreed on both uploads and the download.

**Tests.** 2119 → **2281 JVM tests/variant** (+17 `DriveAuth`, +114 `:ext-drive` REST/ops/tokens,
+31 `:app`). No code review (decision 12).

**Walk (Sonnet, Nomad `.dev`) + user checklist.** Cloud section rendered; Connect opened Google's
sign-in (no `disallowed_useragent`); cancel closed the bracket (`beginConnect` → `endConnect` →
unbind + revoke); shell launch of the screen refused (behavioural — `HostCallerCheck` in the
stdlib-only contract module logs nothing); no URL/token/email in any log line. User: signed in, the
email showed on the Backup line and in Cloud status, the probe ran clean, `Notesprout SN Dev/Exports/
probe` seen empty in Drive, Disconnect (revoke http 200) then reconnect — all passed.

**Design calls not in the wizard (recorded, all binding unless the user says otherwise).** Held bind
for the connect showing · serialization in the extension · `cloudEnabled` on `BackupConfig` · host
validators throw `ExtensionCallException` (a bad name must be sayable) · `CloudClient` owns and closes
the fds it is handed · upload size is corroborated by the caller, never refused by the client · a 401
on a streaming leg never retries (an fd cannot be rewound) · uploading over a same-named folder is
refused, never created beside · a listing row the seam cannot describe is skipped (Drive allows `/`)
· revoke before forget, revoke failure swallowed · the resumable PUT carries no bearer (the session
URI is the credential).

**Traps met.** The Write tool landed a raw BEL in a test string literal again — caught by the
byte-scan. `am start` of the bootstrap needed the second call (standing).

### V3 — Export to cloud (2026-09-04) ✅

**Outcome.** A notebook exports straight into `Notesprout SN Dev/Exports/<folder>/` from the Export
screen. Host only — no seam change, `:ext-drive` untouched. `export/ExportDestination` (pure: row
visible · forced-back · the cloud-radio tap → SELECT / NOT_CONFIGURED / OFFER_CONNECT), the
Destination row after the exporter's options (`ExtensionRegistry.cloud()` + `status()` re-asked at
every discovery; GONE without a provider), the inline Connect offer through `CloudConnectEntry`
(registered in `onCreate`, closed in `onDestroy`; a `RESULT_OK` selects the cloud radio after a
fresh status), `cloud/CloudBrowserDialog` + `CloudBrowserRules` (Contents-dialog shape: Up ·
breadcrumb · Cancel · **Save here**; paged rows, pager-only bottom bar, *New folder…* first row;
browsing only `list`s, `ensureFolder` only from New folder; NOT_CONNECTED closes into the Connect
offer, NETWORK / no-answer stay put; `Mode.PICK_FILE` + `Pick.File` declared for V5, files inert),
the *Replace <name>?* confirmation when the chosen listing holds the name, `runExport` over a
`Destination` sealed type (`Saf(uri)` / `Cloud(path, name, mime)`) with the cache file
`cacheDir/export/out.<ext>` as the exporter's destination fd, local verification against that file's
length, then `upload` under `uploadBudgetMs` and `ExportVerification.cloudVerdict` (agree → done
naming the provider + `lastExporter`; disagree → check-the-file, no delete, no `lastExporter`). The
cloud failure rows as designed; no remote delete anywhere. `ExportActivity` 944 → 1428 lines, its
over-800 reason extended. New drawable `ic_arrow_up` (Tabler).

**Tests.** 2281 → **2302 JVM tests/variant** (+8 `ExportDestination`, +11 `CloudBrowserRules`, +2
`cloudVerdict`). No code review (decision 12).

**Walk (Sonnet, Nomad `.dev`) — all nine steps passed.** Section order Format → Source/options →
Destination; the connected radio selects at once (`status` 109 ms warm); the browser over `Exports/`
listed V2's `probe`; New folder `Walk` (`ensureFolder` depth 2, 1 790 ms) entered it; Up/enter/Cancel
left the screen unlatched and a second Export reopened the browser fresh; Save here ran *Rendering
page 1 of 1…* → *Uploading to Google Drive…* → **"Exported — Your notebook was exported to Google
Drive."** and closed to the library; `cache/export` gone afterwards; the second export to `Walk`
raised **"Replace Events Ideas.pdf?"** and the replace agreed (`upload: 283443 B → 283443 B
reported, agrees=true in 2 775 ms`); the local destination still opens the SAF picker. No `Log.w`,
nothing grey or clipped.

**Design calls not in the wizard (recorded, binding unless the user says otherwise).** Name matches
are exact · a New folder past `MAX_PATH_DEPTH` is refused in the rules, before any bind · a
same-named *file* does not block a New folder · the Up button is `INVISIBLE` at the base folder and
its no-op is silent · `Pick.Folder` carries the listing it was drawn from (the replace question costs
no second `list`) · `PICK_FILE` hides the action button and the New-folder row · New-folder failures
share the browser's list-failure table · the upload's NOT_CONNECTED dialog offers Connect as its
positive button and re-reads `status()` first · a provider gone between tap and upload is
`export_cloud_gone_body` · the destination answer is NOT remembered across screens (a fresh Export
screen defaults to the local file — the walk noted it; a remembered destination is a future call).

**Traps met.** Opus wrote `"out.${'$'}{…}"` for the cache filename — a literal `${…}` in the name,
harmless to the upload (the cloud name is `ExportNaming`'s) but wrong; caught on the orchestrator's
read-through, fixed before the install. No control bytes this phase.

**User checklist.** 1. In Drive, `Notesprout SN Dev/Exports/Walk/Events Ideas.pdf` opens and shows
the page. 2. `Exports/probe` is still empty. (Delete `Walk` whenever you like — nothing reads it.)


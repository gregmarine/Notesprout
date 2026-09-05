# Cloud (arc 25 "Drive")

Arc 25 "Drive" (V1–V5, 2026-09-04 → 09-05) is SN's **eighth extension point**,
`CloudContract.ACTION_CLOUD_STORAGE` — the first point that is *generic over a provider*: the
contract speaks folders, files and bytes, never a provider's own terms. `NSE · Cloud Storage`
(`:ext-cloud`) is the one extension on the point, and Google Drive is the first (and so far only)
provider inside it: **a second provider is baked into this same extension, not shipped as a second
one** (decision 15). The point stays singular either way — the host's `ExtensionRegistry.cloud()`
is first-wins. The full plan and phase ledger — every design call, every
on-device measurement, every trap — lives in `DRIVE_PLAN.md`; this document is the settled
reference for the feature it left behind. **Do not load `RATTA_PLAN.md` for this arc.**

## Decisions (binding, `DRIVE_PLAN.md` wizard 2026-09-04)

| # | Decision |
|---|---|
| 1 | Eighth point granted. Module `:ext-drive`, label `NSE · Google Drive` — **amended by decision 15**: the module is `:ext-cloud` and the label `NSE · Cloud Storage`. |
| 2 | Generic cloud storage — `ACTION_CLOUD_STORAGE` + `ACTION_CLOUD_STORAGE_SCREEN`. |
| 3 | **The extension owns all of OAuth** — client id/secret compiled only into the extension APK, refresh token in the extension store. The host sees status and file ops only. |
| 4 | Consumers this arc: export destination, backup destination, import source. **No whole-library restore.** |
| 5 | Drive tree is SN's **own root** (`Notesprout SN` / `Notesprout SN Dev`), never og's trees. |
| 6 | **No other extension is aware of the cloud** — no presence extra, no host-side cloud stub. |
| 7 | The cloud picker is **host-drawn** (`CloudBrowserDialog`), not the provider's own UI. |
| 8 | Connect lives in the Backup screen's Cloud section **and** as an inline offer from Export/Import. |
| 9 | Debug builds get a **separate root** `Notesprout SN Dev`. |
| 10 | `API_VERSION` 7 → 8; `ACTION_CLOUD_STORAGE` floored at 8. No other floor moved. |
| 11 | App version stays `0.1.0-ratta`. |
| 12 | **No code review** on any phase of this arc (the user's call). |
| 13 | Device: Nomad only; Sonnet drives the walks, not Haiku. |
| 14 | OAuth scope is `drive.file` — the app sees only what it created. |
| 15 | **The extension is generic, 2026-09-05 (post-freeze, the user's call).** `:ext-drive` → `:ext-cloud`, `NSE · Google Drive` → **`NSE · Cloud Storage`**, package/applicationId `…ext.drive` → `…ext.cloud`, `DriveService` → `CloudService`. **A second cloud provider is baked in HERE, beside Google Drive, never as a second extension** — which is what makes the generic name the honest one. The `Drive*` implementation classes keep their names (they *are* Google Drive's OAuth flow and REST v3 client); a second provider arrives as `Dropbox*.kt` beside them, `CloudService.PROVIDER_NAME` + `opsFor` being the fork. Nothing on the seam moved. The applicationId change makes it a new package: uninstall the old one, and re-connect once (the refresh token lives in the host's `Garden/<pkg>.db`, which is keyed by package). |
| 16 | **The whole extension family wears one icon, 2026-09-05 (the same call).** The Tabler "puzzle" glyph, byte-identical, with no exception — reversing the three per-subject icons granted along the way (`:ext-tags` `tag`, `:ext-calendar` `calendar`, the cloud's `cloud`). The **label** is what a person reads in Settings → Apps; the glyph only says which family a package belongs to. |

## Why no other extension is aware of the cloud

An export is host-driven end to end: the host draws the chooser, prepares the source, picks the
destination, opens two fds and hands them to the exporter in one call — the exporter only streams
source → destination. Import is the mirror, and backup is host-only code. A cloud destination
therefore changes only *where the host's fd points*: `NSE · Soil Export`, `NSE · PDF Export` and
`NSE · Document` need zero changes and cannot tell a Drive upload from a SAF file. The intended
future shape, if an extension ever needs the cloud for itself (recorded, not built): a host-side
`ICloudHost` stub minted per showing and revoked with the unbind, the `IDocumentHost` recipe, plus
a presence boolean extra on the tier-2 launch (`EXTRA_CALENDAR_SCRATCH_PAD_AVAILABLE`'s
precedent) — under a fresh user decision.

## The seam

### The two actions

- `CloudContract.ACTION_CLOUD_STORAGE` — the `<service>` intent-filter action, store-taking,
  bind-per-call.
- `CloudContract.ACTION_CLOUD_STORAGE_SCREEN` — the connect screen's action. Resolved with
  `setPackage(<the discovered service's package>)` and launched **for a result**;
  `HostCallerCheck.enforceActivity` refuses a plain `startActivity` (a `callingPackage` of null).
  Nothing rides its Intent — no extras at all.

Both actions are in the host's `<queries>` block (the arc 21 / W1 trap — a missing one reads as a
signature mismatch, not a "not found").

### `ICloudStorage.aidl`, verbatim

```
interface ICloudStorage {
    CloudStatus status(IExtensionStore store);
    void disconnect(IExtensionStore store);
    void beginConnect(IExtensionStore store);
    void endConnect();
    CloudEntry[] list(IExtensionStore store, in String[] path);
    CloudEntry ensureFolder(IExtensionStore store, in String[] path);
    CloudEntry upload(IExtensionStore store, in String[] path, String name, String mime,
                      in ParcelFileDescriptor source, long expectedBytes);
    long download(IExtensionStore store, String entryId, in ParcelFileDescriptor destination);
    void delete(IExtensionStore store, String entryId);
}
```

`beginConnect`/`endConnect` were **added at V2**, after the seam was designed at V1 — the sign-in
screen has to write the token it wins into a store only the host can lend, so Connect needed a
tier-2 bracket even though every other method is bind-per-call. No API bump came with them: the
point was born this arc and nothing had shipped against the five-method shape yet.

### Wire types

- `CloudStatus(connected: Boolean, configured: Boolean, accountLabel: String, providerName: String)`
  — `configured` is the field the wizard didn't originally ask for: it exists so a build with blank
  OAuth credentials reports itself rather than offering a Connect that cannot work. The constructor
  requires `!connected || configured` and `connected || accountLabel.isEmpty()`.
- `CloudEntry(id, name, isFolder, sizeBytes, modifiedAt)` — one folder or file, folder-and-size
  cross-checked in the constructor (`!isFolder || sizeBytes == 0L`).
- A `path` is folder **names** under the provider's root, never the root itself and never above it:
  `CloudContract.MAX_PATH_DEPTH` = 8, `MAX_NAME_CHARS` = 255, `MAX_ENTRY_ID_CHARS` = 256,
  `MAX_MIME_CHARS` = 128, `MAX_ACCOUNT_LABEL_CHARS` = 254, `MAX_PROVIDER_NAME_CHARS` = 64,
  `MAX_LIST_ENTRIES` = 1 000 (a longer listing is truncated, never failed).
- Every parcelable's constructor `require`s its own fields — unmarshal is validation, the family
  rule since arc 1's E1.
- **No secret, no device path, no URL ever crosses this seam**, in either direction. The account
  label is user content and is never logged on either side, on both sides of the seam.

### The three exceptions and the two verbatim refusals

Only `SecurityException`, `IllegalArgumentException` and `IllegalStateException` may leave a stub
(`DriveFailures.marshalable` on the extension side funnels everything else into one of these — the
arc-2 trap: a non-marshalable exception leaving a stub kills the transaction silently and the host
waits out its whole timeout for nothing).

Two `IllegalStateException` messages are compared **verbatim** (never as a substring) by the host:

- `CloudContract.NOT_CONNECTED = "not connected"` — no account, ever was, `disconnect()` ran, or
  the provider's token was revoked out from under it. The host offers Connect.
- `CloudContract.NETWORK = "network"` — the provider could not reach its own service (offline,
  DNS, TLS, a 5xx, its own timeout). Nothing changed; the host offers to try again.

Everything else — a bind refused, a timeout, an `RemoteException`, a reply that failed validation
— is a plain `ExtensionCallException` and reads on the host as "the provider didn't answer".
`CloudClient.mapRefusals` is where the verbatim comparison happens, mapping to
`CloudNotConnectedException` / `CloudNetworkException`.

### Store-taking, bind-per-call

The tag manager's second call shape: the store rides every call, minted per bind, uid-bound,
revoked with the unbind. **There is no held bind for a file operation** — every call is one Binder
call sized by a measured `CloudTimeouts` row, because a Binder call cannot be cancelled. The one
exception is the **connect showing** (below), which is a genuine held bind — the tag manager's
bracket, not the bind-per-call shape.

`CloudClient`'s pattern for every operation: pre-open the store on IO (before the bind — a cold
SQLCipher KDF is seconds on the Nomad and must never sit inside a call timeout), one bind, one
call under the row's timeout with refusals mapped, `store.revoke()` in `finally` whatever
happened. Arguments are checked host-side (`CloudArgs`) **before** the bind, because a refusal
must never cost a bind — binding a service starts a process. `upload`/`download` each take an fd
the client owns from the moment it is handed one: closed in `finally` on every path, including a
refusal that never reached a bind.

### API version 8, floored per action

`ExtensionContract.API_VERSION` moved 7 → 8 for this arc; `CloudContract.MIN_API_VERSION_FOR_CLOUD
= 8` is a per-action floor (the calendar's arc-23 precedent — `minApiVersion` is a map, not a
single set). The cloud point was born at 8, so there is no older cloud shape a host could accept —
a service declaring less is simply not a provider this host knows. No other point's floor moved,
so no existing door vanished with this bump.

## The extension: `:ext-cloud`

Renamed from `:ext-drive` / `NSE · Google Drive` on 2026-09-05 (decision 15). The module, package,
applicationId, label and the point's service (`DriveService` → `CloudService`) are generic; the
implementation stays honestly provider-named — `DriveApi`, `DriveAuth`, `DriveHttp`, `DriveTokens`,
`DriveOps`, `DriveStore`, `DriveSql`, `DriveSchema`, `DriveFailures`, `DriveJson`, `DriveMultipart`,
`DriveRest` are Google Drive's own OAuth flow and REST v3 client. A second provider is a
`Dropbox*.kt` set beside them behind the same `CloudService`, never a second APK.

Module facts: `com.symmetricalpalmtree.notesproutsn.ext.cloud` (applicationId
`…ext.cloud`, `.dev` suffix in debug), depends on `:extension-api` + `:sn-screen` only, **never**
`:app` — the host keeps zero INTERNET permission and zero OAuth of its own; this is the only
networked process in the app. `INTERNET` is its one manifest permission beyond the usual. No
Application class (no drawing engine to register). Takes `kotlinx.serialization` (already on the
graph through `:app`, so no new library) for the token endpoint's and Drive REST's JSON — the V1
plan's "hand-rolled JSON" note was superseded at V2. `abiFilters += "arm64-v8a"`. `versionName`
tracks `:app` in lockstep, `0.1.0-ratta`.

### OAuth: PKCE in a WebView

`DriveAuth` is the pure PKCE/OAuth core (RFC 7636 vector JVM-tested; no network, no Android type).
The flow, as og Notesprout runs it and Google documents for a **Desktop-app** client type:

1. a random 32-byte verifier (`DriveAuth.codeVerifier`) and its SHA-256 S256 challenge
   (`codeChallenge`);
2. `ConnectActivity` opens Google's consent page in a `WebView` whose `userAgentString` is set to
   `DriveAuth.CHROME_UA` **before** `loadUrl()` — Google refuses OAuth from anything identifying as
   Android WebView (`disallowed_useragent`);
3. the URL carries `access_type=offline&prompt=consent` — the only way Google is guaranteed to
   issue a refresh token on every connect, not only the first;
4. the consent page is steered to `http://localhost/oauth2callback`, which no server answers — the
   navigation is **intercepted** in `shouldOverrideUrlLoading` (and, belt-and-braces, in
   `onPageStarted` for WebView builds that start loading before asking) and never actually loaded;
5. the code + verifier + client secret are POSTed to the token endpoint on IO
   (`DriveAuth.exchangeBody`);
6. the refresh token and the account's label are written **through the store the host lent**
   (`ConnectSession.store`, parked by `beginConnect`) — the extension writes nothing to disk
   itself, ever;
7. only then `RESULT_OK`. Any other ending — consent declined (`access_denied`, a plain cancel), a
   malformed redirect, no refresh token in the grant, a store that could not be reached — is
   `RESULT_CANCELED`, after a `Dialogs.confirm` the screen leaves on the dismiss of, never beside
   it.

The client id/secret are `BuildConfig.DRIVE_CLIENT_ID` / `DRIVE_CLIENT_SECRET`, populated from the
same shell env vars og Notesprout reads (`buildConfigField` reading `System.getenv(...)`), compiled
**only** into this APK. Blank credentials make `CloudService.configured()` false; the host dialogs
on `configured = false` rather than offering a Connect that cannot work, and `ConnectActivity`
itself refuses to even inflate when it is not configured.

`ConnectSession` is process-wide state shared by `CloudService` (the host's held bind) and
`ConnectActivity` (the sign-in screen), the `TagSession` shape — it holds only the store binder the
host lent for this one showing; `endConnect` clears it and the host revokes the binder right after.

The access token lives **in memory only** (`DriveTokens.cache`, a `TokenCache`) — a 60-minute
credential that dies with the process; only the durable refresh token is in the store.
`TokenSource.access()` returns the cached token if `DriveAuth.isFresh` (skewed by
`DriveAuth.EXPIRY_SKEW_MS` = 60 000 ms) or silently refreshes. A refresh answering Google's
`invalid_grant` (revoked, expired after months unused, consent withdrawn) **forgets the refresh
token and the label** and the account reads `not connected` from there — leaving a dead token in
the store would make `status()` lie forever.

### Token lifecycle in the store

`DriveSchema.V1` = one table: `account(key TEXT PRIMARY KEY, value TEXT NOT NULL)` — the document
editor's `prefs` shape, reused for the same reason: a handful of rows is a key/value table, not a
wider one. `DriveSql` holds every statement (`selectValue`, `upsertValue`, `deleteValue`,
`deleteAll`); `account` has no children and no cascade to protect, so `INSERT OR REPLACE` is safe
here (unlike the tag manager's tables). Three keys: `refreshToken`, `accountLabel`,
`rootFolderId`. `DriveStore` applies the schema on every public call (idempotent, one `SELECT`
host-side) and turns every non-typed exception into `StoreUnavailable`, which `DriveFailures`
folds into the seam's `STORE_UNAVAILABLE` message.

### The REST core

`DriveApi` is Drive REST v3 re-derived from og Notesprout's `DriveApiClient` (not copied — the
shapes differ because this seam speaks paths-of-names under a provider-owned root and answers
`CloudEntry`, not bare ids).

- **Root resolution** — `rootId()` find-or-creates a folder named `BuildConfig.ROOT_FOLDER_NAME`
  directly under My Drive, caches its id in the store, and **re-resolves once** if the cached id no
  longer exists (deleted or trashed from another device — the difference between the feature
  healing itself and every call failing forever after a web-UI tidy-up).
- **Find-or-ensure folder** — `ensureFolder` always **finds first**: nothing is ever created beside
  an existing name. Drive allows same-named siblings, so `findChild` takes the **first** match in
  Drive's own order.
- **Listing** — `listChildren` pages through Drive's own paging, sorted folders-then-files by name,
  and stops asking once the seam's `MAX_LIST_ENTRIES` ceiling is already exceeded (the extra pages
  could only be thrown away).
- **Multipart vs. resumable** — `DriveMultipart.useMultipart(expectedBytes)` decides at 5 MiB
  (`CloudTimeouts.UPLOAD_SMALL_LIMIT_BYTES`): at or below, one multipart request carries metadata +
  bytes; above, a resumable session (`resumableUpload`) opens with one POST/PATCH, then one PUT of
  the whole body — the session URI is itself the credential, so it carries no bearer.
- **Replace-by-name** — `upload` looks up a same-named file first; a same-named **folder** is
  refused outright (`"name is a folder"`) rather than creating a file beside it. `ExactCopy.copy`
  streams **exactly** `expectedBytes` from the host's fd, refusing a short read
  (`IllegalStateException("short read")`) or a long one (`"long read"`) — an fd cannot be rewound,
  so a stream upload is never retried after a 401.
- **Download** — streams into the host's fd, which the `CloudService` stub truncates first and
  `fsync`s after (`out.fd.sync()`), then answers the byte count.
- **Delete** — one DELETE; 204/200/404 all count as done (idempotent on an id already gone).
- **Retry discipline** — a call whose body can be replayed (`call`) retries once after a 401 (token
  refresh, retry; a second 401 is `not connected`); a call that streams the host's fd (`callOnce`)
  never retries and reads a 401 as `not connected` at once. `DriveFailures.forHttp` maps 5xx/429 to
  `network()`, everything else to a named `"http <code>"`.

### `ExactCopy`

The exact-byte-count copy used by every upload path — see above. Its two messages
(`SHORT_READ`/`LONG_READ`) are informative, not contractual: the host reads any message other than
the two verbatim refusals as "the provider didn't answer".

### `DriveFailures` — the failure table

| what happened | what crosses |
|---|---|
| offline, DNS, TLS, a socket timeout, any `IOException`/`GeneralSecurityException` | `IllegalStateException("network")` |
| http 5xx, or 429 (rate limited) | `IllegalStateException("network")` |
| no refresh token in the store, or Google says the token is dead | `IllegalStateException("not connected")` |
| any other 4xx (400/403/404 on a specific call) | `IllegalStateException("http <code>")` |
| anything else (a serialization failure, an NPE) | `IllegalStateException("provider failure (<class>)")` |
| a store exception (`StoreUnavailable`) | `IllegalStateException("store unavailable")` |

No message ever carries user content — no file name, no email, no URL, no token.

### The Drive tree

Under `BuildConfig.ROOT_FOLDER_NAME` — `Notesprout SN` (release) / `Notesprout SN Dev` (debug, its
own root, never a `dev/` subfolder under the release tree): `Exports/<folder>/…` (export
destination), `Backups/<device folder>/` (backup leg), `probe/` under `Exports/` (the debug Cloud
probe's scratch folder, deleted after every run). Because SN shares og Notesprout's OAuth client,
SN *can* see og's own Drive trees under `drive.file` visibility — it never lists outside its own
root, by construction: nothing in `DriveApi` ever names a parent it did not resolve starting from
`rootId()`.

## Host side

### `ExtensionRegistry.cloud()`

`ExtensionRegistry.cloud(context)` is the single-provider rule: discovers every trusted
`ACTION_CLOUD_STORAGE` service, and with more than one installed takes the **first** by discovery
order and logs a warning (`Log.w`) about the rest — a chooser between providers is a future
decision, not built.

### `CloudClient` + `CloudArgs`

`CloudClient` is the host's bind-per-call client (`status`, `disconnect`, `list`, `ensureFolder`,
`upload`, `download`, `delete`) — see "Store-taking, bind-per-call" above for its shared shape.
`CloudArgs` is the host-side pre-bind validation (`requirePath`, `requireName`, `requireMime`,
`requireEntryId`, `requireExpectedBytes`, plus reply-shape checks `checkList`/`checkFolder`/
`checkUploaded`/`checkDownloaded`) — pure, JVM-tested, and deliberately does **not** compare an
upload's reported size against what was sent (that comparison belongs to the caller's own
verification, where it can be worded as "check the file", never a refusal here).

### `CloudTimeouts` — measured, not guessed

Every row carries the Nomad measurement it was sized from (`DRIVE_PLAN.md` § V2 ledger, home
wifi, 2026-09-04) and a budget 5–30× that measurement — never tight, because a Binder call cannot
be cancelled and a timeout undoes nothing: the transaction keeps running in the provider's process
after the host has already told the person nothing happened.

| Method | Measured (Nomad) | Budget |
|---|---|---|
| `status` | 772 ms cold / 51 ms warm | `STATUS_MS` = 4 000 ms |
| `disconnect` | ≈ 160 ms (revoke POST 137 ms + store forget) | `DISCONNECT_MS` = 15 000 ms |
| `list` | 530 / 805 / 1 056 ms at depth 0 / 1 / 2 | `LIST_MS` = 20 000 ms |
| `ensureFolder` | 3 981 ms (2 segments + root, all created) | `ENSURE_FOLDER_MS` = 30 000 ms |
| `upload` ≤ 5 MiB (multipart) | 2 901 ms for 1 MiB | `UPLOAD_SMALL_MS` = 60 000 ms |
| `upload` > 5 MiB (resumable) | 6 435 ms for 20 MiB (≈ 4.3 MB/s) | `UPLOAD_LARGE_MS` = 120 000 ms **per 20 MiB slice** (`uploadBudgetMs` rounds up) |
| `download` | 4 343 ms for 20 MiB (≈ 5.4 MB/s) | `DOWNLOAD_MS` = 120 000 ms, **flat** (not yet a rate — see "Not built" below) |
| `delete` | 729 ms (one DELETE, http 204, 677 ms) | `DELETE_MS` = 15 000 ms |

`UPLOAD_SMALL_LIMIT_BYTES` = 5 MiB is the provider's own multipart/resumable boundary.
`CloudTimeouts.BIND_MS` is not a cloud-specific number — it is `ExtensionBinder.BIND_TIMEOUT_MS`,
the seam's own constant for every point.

### `CloudConnectClient` + `CloudConnectEntry` — the connect showing

The **one held bind** on this whole point, and the only place the seam looks like the tag
manager's bracket rather than `CloudClient`'s bind-per-call shape (added at V2, after the seam was
first drawn at V1):

1. `CloudConnectClient.open()` pre-opens the store on IO, mints a uid-bound
   `ExtensionStoreBinder`, holds the bind (`ExtensionBinder.hold` — the signature re-checked at the
   bind, not only at discovery), calls `beginConnect(store)` to park it, and returns the screen's
   `Intent` for the caller to launch through an `ActivityResultLauncher`.
2. On the result, `finish()`: `endConnect()` best-effort, then unbind + revoke in one `finally` on
   every path — the revoke is what makes the store dead to anything the screen still holds.
3. The screen answers `RESULT_OK` only after the token is **in** the store, so the bracket itself
   teaches the host nothing; the caller's next `status()` is the truth either way.

`CloudConnectEntry` is the host's UI-facing wrapper (the `TagManagerEntry` shape): it owns
discovery (`discover()`, re-run every time the door is about to be offered — the caller makes the
whole Cloud section **GONE**, never disabled, when the answer is null), the busy latch at the tap
(`OpeningOverlay.showThen` — a cold store open is seconds on the Nomad and a silent tap reads as a
miss), and the bind's life (`close()` is the backstop for a caller destroyed mid-showing).

**The posted-result-after-`onResume` trap (found on the V5 walk).** `CloudConnectEntry`'s launcher
callback posts its work on `MainScope().launch`, which runs **after** the caller's `onResume` — a
"stranded connect" safety net that cleared a latch in `onResume` would race ahead of a genuinely
successful sign-in and the caller would never learn it connected. The fix, and the standing rule:
**a result always arrives.** `CloudConnectEntry.open()` calls `onChanged(false)` even on the
sign-in-could-not-open path (the intent came back null), so a caller holding a latch across the
showing — the import flow's source question — is never left waiting for a callback that never
comes; not connected reads exactly like a cancel.

### `CloudWording`

The Backup screen's Cloud-line status text, as a pure function over four ordered questions (`
`CloudWording.detail`): **not configured** (first — it makes every other answer moot) → **not
connected** → **the account label** (only branch that prints user content, and only on the
person's own screen) → **connected** with no label. `unavailableLine` is the fifth case — not a
`CloudStatus` at all, the provider is installed but did not answer — and leaves the button on
**Connect**. `CloudWording.showsDisconnect(status)` is true only for a live connection.

### `CloudBrowserDialog` + `CloudBrowserRules`

The **host-drawn** folder/file browser (`DRIVE_PLAN.md` decision 7), shared verbatim by all three
consumers — the extension is asked one thing, `list`, and every decision about what the rows mean
is made here. A full-screen `Dialog` in the Contents dialog's shape: top bar = Up · breadcrumb ·
Cancel · the action (F2 — action button after Cancel, top bar), a 1 dp inkBlack rule, rows that
**paginate and never scroll** with a pager-only bottom bar, the same one-finger `ListSwipe` every
paginated list in the app gets.

- **Modes.** `Mode.PICK_FOLDER` (V3): folders enter on tap, a file row is drawn and **inert**
  (information, not an offer), the action is **Save here** answering `Pick.Folder(path, listing)`
  — the listing travels with the answer so the caller's replace question costs no second `list`.
  `Mode.PICK_FILE` (V5): every file row **is** the answer (`Pick.File(entry, path)`), the action
  button and the *New folder…* row are both `View.GONE`, and **nothing is filtered by extension** —
  which importer can read a tapped file is decided afterwards, by name, the family's rule that the
  browser never hides the file the person came for.
- **Crumb** — `<provider> › Exports › …`, always headed by the provider's own name (the string
  resource's separator is quoted `" › "` in XML — an unquoted `›` was AAPT-trimmed away and caught
  on the V5 walk).
- **Up** — `CloudBrowserRules.canGoUp` never climbs above the folder the browser was opened on
  (`basePath`); Cancel is the way out of that one. The arrow is `INVISIBLE`, not `GONE`, at the
  floor so the crumb never steps sideways.
- **New folder…** — the first row of every folder listing a folder can be picked into, judged by
  `CloudBrowserRules.newFolderOutcome` before any bind: `REFUSED` (an illegal name, or past
  `MAX_PATH_DEPTH`), `ENTER_EXISTING` (a folder of that name is already listed — just enter it),
  `CREATE` (`ensureFolder(path + name)` then enter). **Browsing creates nothing** — `ensureFolder`
  is reached only from this row, and `Exports/` itself is made by an upload passing through, not by
  the browser.
- **Paging** — `rows`/`page`/`itemsPerPage` are pure; a folder's row height matches the Contents
  row's, so the two lists read as one family; `itemsPerPage` is measured from the real body height
  after first layout, never estimated.
- **Failures** — three outcomes, because they mean different things: `CloudNotConnectedException`
  closes the browser into the caller's own Connect offer (there is nothing to browse and the door
  belongs to the caller); `CloudNetworkException` and a plain no-answer are problem dialogs **over**
  the browser, which stays exactly where it was.
- No log line in the browser ever carries a folder name, a file name, or the account — counts,
  depths, durations only.

## The three consumers

### Export destination (V3)

`export/ExportDestination` is the pure core (`rowVisible`, `settled`, `onCloudTap`). The
Destination row appears on the Export screen after the exporter's own options, host-drawn only
when `ExtensionRegistry.cloud()` finds a provider — **GONE without one, never disabled** — and
`status()` is re-asked at every discovery rather than remembered, because the connect door changes
it between screens. A standing *cloud* answer is forced back to *local* the instant the row leaves
the screen (`settled`).

Tapping the cloud radio (`onCloudTap`, shared verbatim by Import — see below): connected →
`SELECT`; `!configured` → the *not set up* problem dialog; anything else (no account, or the
provider did not answer at all) → `OFFER_CONNECT`, the **inline Connect offer** — a two-button
*Connect to \<provider>?* dialog through `CloudConnectEntry`, registered in `onCreate`, closed in
`onDestroy`. A `RESULT_OK` re-runs discovery and selects the cloud radio once the fresh status
confirms connection.

Export tap on the cloud destination: the secret checks run exactly as they do for a local export,
then `CloudBrowserDialog` in `Mode.PICK_FOLDER` opens over `Exports/` instead of the SAF picker.
Filename is `ExportNaming.suggestedFileName` — fixed, not editable. If the drawn listing already
holds a file of that name, a *Replace \<name>?* dialog stands in for SAF's overwrite confirmation
(upload is replace-by-name). `runExport`'s `Destination` sealed type gained a `Cloud(path, name,
mime)` case beside `Saf(uri)`; the exporter's destination fd is `cacheDir/export/out.<ext>` opened
**after** prepare (prepare wipes the directory) — the exporter never learns it isn't SAF.
Verification runs against that cache file's own length first, then `CloudClient.upload(...)` under
`CloudTimeouts.uploadBudgetMs(bytes)`, and `ExportVerification.cloudVerdict(reported, uploaded)`
corroborates: agree → *Exported* naming the provider, `lastExporter` written; disagree → the
arc-15 *check the file* dialog, **never a delete**, `lastExporter` **not** written. No remote
delete anywhere in this consumer.

### Backup leg (V4)

Two legs, one run — `BackupEngine.run` decides which legs exist (`CloudBackupRules.legs`): the
local leg when a SAF tree is chosen (untouched code path), the cloud leg when
`BackupConfig.cloudEnabled` **and** `ExtensionRegistry.cloud()` finds a provider, **re-asked at run
start**. Neither leg exists → `Problem.NO_DESTINATION`, the same pre-check on the screen so nobody
watches an empty progress dialog. The result type is `BackupEngine.Outcome(local: Result?, cloud:
Result?, problem: Problem?)` — a leg that did not run is `null`, never a zero result;
`BackupEngine.Progress` carries which `Leg` it is currently in.

`BackupConfig` grew additively (`VERSION` stays 1): `cloudEnabled`, `cloudDeviceFolder`,
`cloudStamps` (the cloud leg's **own** stamp map — never shares a field with the local leg),
`cloudLastRunAt` / `cloudLastCopied` / `cloudLastSkipped`.

**Device folder.** `data/backup/DeviceFolder` — og's D4 shape: sanitized `Build.MODEL` (capped at
48 chars, charset `[a-zA-Z0-9_-]`, no dot or space — narrower than `NameRules` on purpose, because
this name is minted by the app and becomes a folder in someone else's tree) plus `-` and 8 random
hex chars (`randomSuffix`, never the hardware serial — og's rule: a serial is a durable identifier
that would sit in the person's cloud forever, and the folder needs to be *distinct*, not
*identifying*). Minted lazily — the Backup screen's Cloud section mints one on first render if none
exists; `CloudBackupLeg.deviceFolder` mints if a run starts before the screen ever showed it. The
Backup screen offers **Rename…** (`NameDialog` → `NameRules.validate` → `CloudArgs.requireName`,
the library's problem dialog then the seam's bounds); a *different* name resets `cloudStamps`
(`adoptFolder` reasoning — a stamp is a statement about one destination, and a fresh folder has
never seen a file), re-entering the same name keeps them.

**`SelfContainedSnapshot` — the cloud never holds a sidecar.** The local leg can land a `.soil` and
its `-wal` as a near-atomic pair because the SAF `.part`/`.old` swap makes each write atomic and
both land in one run; the cloud has no swap, so two uploads can tear and a fresh main paired with a
stale `-wal` is a corrupt file that looks fine until it's needed. Before every upload,
`SelfContainedSnapshot.of` copies the live file **and its WAL** into `cacheDir/backup/cloud/`
(wiped before every file), opens the copy with the file's **cached raw key**
(`KeyMaterial.peekOrLoad` — notebook id / `KeyMaterial.INDEX_FILE_ID` / `ExtensionStores.fileIdFor`
— falling back to the session passphrase only when nothing is cached), runs `PRAGMA
wal_checkpoint(TRUNCATE)`, and answers the file **only if** the copy's `-wal` is now absent or
empty **and** the copy still probes `SoilFileKind.Encrypted`. Anything else is `null` — that file
is **refused this run**, counted failed, retried next run, nothing uploaded and nothing deleted.

**One listing, not one per file.** `CloudBackupLeg.run` calls `ensureFolder(["Backups", device])`
once (fail-fast: any failure here means nowhere to write, so the leg ends before a byte moves) then
`list`s that folder **once** and keeps the listing current itself as it uploads
(`entry` replaced, `entry` removed) — the local leg's one-listing-per-write rule scaled to the
seam's per-call cost (~800 ms). That listing serves the leg's **one remote delete in the whole
arc**: a stale `<name>-wal` left by something else is deleted **before the stamp**
(`CloudBackupRules.staleSidecar`, exact name match, a file never a folder) — a swallowed failure
here would pair a fresh main file with an old sidecar forever.

Per notebook: `compactPass` runs unless the **other** leg already compacted this id this run
(`compactedThisRun` set handed across — VACUUM twice is a wasted minute); snapshot; `upload`
(`application/octet-stream`, budget `uploadBudgetMs(length)`); `ExportVerification.cloudVerdict`
corroborates — agree → stale-sidecar check → **stamp immediately**; disagree → failed, nothing
deleted, retried next run (replace-by-name makes the retry safe). Every extension store and the
index follow the same send/corroborate/stamp shape, with no stamps for the stores.

**Mid-leg failure.** `CloudNotConnectedException` / `CloudNetworkException` / a plain no-answer on
any single upload **ends the leg** with that `Problem` and the counts earned so far
(`CloudBackupRules.endsLeg`) — piling 60–120 s upload budgets on a dead link would turn a failed
backup into a frozen screen. A corroboration miss is per-file only and never ends the leg.

**The screen.** The Cloud section (`BackupActivity`, GONE without a provider, re-asked every
`onResume`): status line, Connect/Disconnect button, the enable toggle, the Device folder line +
Rename…, a second status line *Last cloud backup: \<date> — N copied, M skipped* /
*Never backed up to \<provider>*. `onRunTap`'s pre-check is `CloudBackupRules.legs` — neither leg
gives the *nowhere to back up* problem dialog naming whichever way (or ways) out apply. One report
dialog with a block per leg that ran (`CloudBackupRules.legClean` / `showsBlock` /
`clean`) — a leg that did not run has no block, because a zero-copied sentence about a destination
nobody chose is noise.

### Import source (V5)

The library's Import button asks **Import from** — *This device* / *\<providerName>* — only when a
trusted provider is installed (`ImportSource.asksSource`); without one, nothing changed, straight
to the SAF picker. `ImportSource` is the pure decision core: `choices`, `asksSource`, `sourceAt`.

The cloud answer reuses `ExportDestination.onCloudTap` verbatim (not copied — a second copy would
be a second place for "connected?" to answer differently), worded for import
(`import_cloud_connect_offer_body`). `ImportFlow` owns its own `CloudConnectEntry` (constructed
with the flow in the library's `onCreate`, closed from `LibraryActivity.onDestroy`) — a sign-in
that succeeds **continues the same beat** straight into the browser rather than stopping and
waiting for a second tap.

`CloudBrowserDialog` opens in `Mode.PICK_FILE` on the provider's **root** (`basePath = []`, legal
on the seam) rather than on `Exports/` and `Backups/` as two separate doors — a root listing shows
both folders plus anything else under the app's own root (e.g. `probe/`), and the host still never
lists outside it. Every file row is tappable and answers `Pick.File`; the action button and *New
folder…* stay absent.

**The importer is matched before any bytes move.** `ExtensionRegistry.importers` (existing arc-16
matching) runs against the picked entry's **name** first — a file no importer can read costs no
download and gets the existing *Can't import that file* dialog. Only a matched file is downloaded,
into `cacheDir/import/cloud/download.bin`, a sibling of the SAF path's incoming copy.
`CloudImportRules.downloadVerdict(reported, landed, listed)` corroborates three separate accounts:

- `reported != landed` → `SHORT` (the provider's own count disagrees with the file on disk — a
  truncated stream, stop the import).
- `listed > landed` → `SHORT` (the listing claims more than arrived — can't be a lag, it names
  bytes that never showed up).
- `listed >= 0 && listed != landed` (and not caught above) → `DISAGREE` — logged, import goes on
  (the standing trap: a provider's listing metadata can lag its own write; the probe and keying
  acceptance downstream answer for what the bytes actually are).
- otherwise → `OK`.

The matched importer then streams the **downloaded file** into `incoming.soil` exactly as it
streams a SAF document — `ImportFlow`'s `Delivery` sealed type gained `Cached(file)` beside
`Document(uri)` — so probe, unlock, keying, manifest, the three questions and both writes are
completely untouched; "every import goes through an importer" stays literally true, at the cost of
one extra local copy (~0.5 s per 100 MB on the Nomad's flash). One latched `try/finally` covers both
origins, and the cache wipe takes the download with it. `CloudImportFailure` (its own type, not a
`NotebookImport.Problem` value, because `NOT_CONNECTED` needs a **Connect** button the `Problem`
table's one-body-per-value shape has nowhere for): `GONE` / `NOT_CONNECTED` / `NETWORK` /
`UNANSWERED`. **Nothing remote is ever deleted** by import.

## Failure table

| Failure | Where it surfaces | What the host does |
|---|---|---|
| `CloudNotConnectedException` (`"not connected"`) | Export upload, backup leg, import download, any `CloudClient` call | Offer **Connect**; nothing was changed |
| `CloudNetworkException` (`"network"`) | Same | "\<provider> could not be reached; nothing was uploaded/downloaded; try again" |
| Plain `ExtensionCallException` (no answer / timeout / bad reply) | Same | "The provider did not answer; the file may or may not have arrived — check \<provider>" (never a delete: replace-by-name makes a retry safe) |
| `!status.configured` | Export/Import cloud-radio tap, Backup Connect | *Not set up* problem dialog — no Connect offered, since it cannot work |
| Provider gone between tap and call (`ExtensionRegistry.cloud()` re-asked) | Backup leg, browser navigation, import download | `Problem.CLOUD_GONE` / `CloudImportFailure.Kind.GONE`; browser closes into the caller's Connect offer for `NOT_CONNECTED` only |
| Export/upload size disagreement (`cloudVerdict` != OK) | Export finish, backup leg per file | "Check the file" (export) / failed + retried next run (backup) — **never delete** |
| Import download size disagreement (`SHORT`) | Import cloud download | Import stops, nothing imported, cache wiped |
| Import download size disagreement (`DISAGREE`) | Import cloud download | Logged, import continues |
| A stale `<name>-wal` found in a backup listing | Backup leg, before the stamp | **Deleted** — the arc's one remote delete |
| Corroboration miss mid backup leg | One notebook/store/index upload | That file counted failed, retried next run; the leg continues |
| `CloudNotConnectedException`/`CloudNetworkException`/no-answer mid backup leg | Backup leg | Leg **ends**, keeping every stamp already earned |
| A file/folder the seam cannot describe (past `MAX_PATH_DEPTH`, an illegal name) | New-folder row, any `CloudArgs` check | Refused **before any bind** — a problem dialog, no network round trip spent |

## What the Nomad walks proved (measured numbers)

See the `CloudTimeouts` table above for the per-method numbers used to size every budget. Beyond
those: a cold host process's first `status()` call was 772 ms (includes the host-side extension
store's cold open, which the pre-open rule puts *before* the bind so no call budget has to cover
it) against 51 ms warm; `ensureFolder` for two fresh segments plus the root was 3 981 ms; a 20 MiB
upload sustained ≈ 4.3 MB/s and a 20 MiB download ≈ 5.4 MB/s on the Nomad's home wifi; a real
backup run of 42 `.soil` files, 7 extension stores and the index all corroborated `agrees=true`
with exactly one `list` call for the whole leg; a second run against the same device folder found
0 copied / 43 skipped, touching only the 7 `.db` files (stores + index), confirming stamps prevent
re-upload; renaming the device folder reset the stamp map and forced a full 42-file re-copy into
the new folder.

## Design calls recorded outside the wizard

**V2.** Held bind for the connect showing (not bind-per-call, because the store must outlive one
call) · serialization lives in the extension, never `:app` gains a JSON dependency for this ·
`cloudEnabled` lives on `BackupConfig`, not a separate `CloudPrefs` row (the plan's `CloudPrefs` is
superseded) · host validators throw `ExtensionCallException`, not `IllegalArgumentException` — a
bad name must be sayable, not a crash · `CloudClient` owns and closes every fd it is handed · an
upload's size is corroborated by the *caller*, never refused by the client itself · a 401 on a
streaming leg never retries (an fd cannot be rewound) · uploading over a same-named **folder** is
refused, never created beside it · a listing row the seam cannot describe is silently skipped
(Drive allows characters the seam's `isName` refuses) · revoke happens before forget, and a failed
revoke is swallowed · the resumable PUT carries no bearer token (the session URI is the
credential).

**V3.** Name matches in the browser are **exact** · a New-folder past `MAX_PATH_DEPTH` is refused
in the rules, before any bind · a same-named **file** never blocks a New folder · the Up button is
`INVISIBLE`, not `GONE`, at the base folder, and its no-op is silent · `Pick.Folder` carries the
listing it was drawn from, so the replace question costs no second `list` · `Mode.PICK_FILE` hides
the action button and the New-folder row entirely · New-folder failures share the browser's own
list-failure table · the upload's `NOT_CONNECTED` dialog offers Connect as its positive button and
re-reads `status()` first · a provider gone between tap and upload gets its own wording
(`export_cloud_gone_body`) · the destination answer is **not remembered across screens** — a fresh
Export screen always defaults back to the local file.

**V4.** `Problem.NO_FOLDER` is gone, replaced by `Problem.NO_DESTINATION` (covers both ways out
with one wording) · a local `FOLDER_GONE` is a leg result and does not stop the cloud leg ·
`CLOUD_GONE` is decided by re-asking discovery after a plain `ExtensionCallException` · a store
whose package cannot be derived from its filename counts against `storesFailed` on the cloud leg ·
the minted device-folder charset is narrower than `NameRules` (no dot, no space; model capped at
48 chars) so a minted name is always legal at the seam, pinned by a test · Rename… judges with
`NameRules` first, then `CloudArgs.requireName` · the cloud leg re-runs the index purge + checkpoint
even right after the local leg's own (a near no-op, kept for correctness) · the cloud index copy,
like the local one, is taken **before** `cloudLastRunAt` is written (index-last: every stamp is
already in it before the run timestamp lands).

**V5.** The browser opens on the provider's **root**, not on two separate doors for `Exports/` and
`Backups/` — a root listing shows both plus anything else under the app's own root, and the host
still never lists outside it · **the importer is matched before the download**, and streams the
already-downloaded file — "every import goes through an importer" stays literally true at the cost
of one local copy · no extension filtering in the browser at all — every file is tappable and a
non-importable one gets the existing dialog (og's never-hide-the-file rule) · a backup `.soil`
picked from `Backups/` runs the ordinary notebook-import pipeline (id collision offers Replace/Keep
both) — the arc's one "restore a notebook" path, and deliberately not a library restore ·
`DOWNLOAD_MS` stays flat 120 s rather than becoming a rate; a very large file over a slow link would
read as `UNANSWERED` with nothing imported (cache wiped) — make it a rate like upload if a measured
import ever needs it · the source answer is not remembered across screens either.

## Standing traps

- **Chrome UA before `loadUrl()`.** Google refuses OAuth in anything identifying as Android
  WebView (`disallowed_useragent`) — the UA must be set before the first load, not after.
- **The OAuth client is the Desktop-app type.** Its "secret" is not actually secret for that client
  type (Google's own model), but it still never crosses the seam and never lands in a log.
- **Drive allows same-named siblings.** Every write is find-then-update, never a blind create.
- **A provider's metadata can lag its own write.** Corroborate a size disagreement; never delete on
  one.
- **The Supernote suppresses the share sheet.** Direct upload through this seam is the only Drive
  path there — there is no fallback to "share to Drive".
- **`${'$'}` in a filename.** Opus once wrote `"out.${'$'}{…}"` for a cache filename — a literal
  `${…}` landed in the name (harmless here, since the cloud's own name is `ExportNaming`'s, but
  wrong) — caught on read-through before install.
- **AAPT trims an unquoted string resource.** V3's crumb separator `" › "` was unquoted XML; AAPT
  trimmed it to `›`, seen on-device as `Google Drive›Exports` — fixed by quoting it, verified as
  `Google Drive › Exports`.
- **A cached raw key can be stale.** `KeyMaterial.peekOrLoad` hands back a key cached for a file
  this process has not itself opened via `KeyOpener`; a store wiped and re-minted since the key was
  derived opens as *"file is not a database."* `SelfContainedSnapshot.absorbWal` now verifies the
  cached key against the copy, invalidates a stale one, and falls back to the passphrase — found and
  fixed on the V4 Nomad walk.
- **A `CloudConnectEntry` result is posted after `onResume`.** Any latch held across a connect
  showing must be released by the result callback itself, never by a resume-time sweep — see "the
  posted-result-after-`onResume` trap" above.
- **File tools can land raw control bytes.** A raw BEL character landed inside a Kotlin char
  literal and inside a test string literal more than once this arc — byte-scan every changed file
  (`LC_ALL=C grep -nP '[\x00-\x08\x0B\x0C\x0E-\x1F]'`) before calling a phase done.

## Debug tooling

- **Cloud status** (debug menu) — the one on-device proof of discovery + bind: provider label,
  package, API version, configured/connected, account (label only shown, never logged).
- **Cloud probe** (`app/src/debug/.../library/CloudProbe.kt`, debug builds only) — walks the whole
  `ICloudStorage` surface once in order (`status` cold/warm, `ensureFolder`, `list` at several
  depths, upload 1 MiB and 20 MiB with corroboration, download 20 MiB with corroboration, delete
  both), timing every step and printing three ways: on the glass, as `Log.i` lines a walk can grep
  (`probe: <op> <n> ms`), and as a copyable summary. Writes real files under `Exports/probe/` and
  deletes them at the end; the local cache files it uses are removed in `finally` regardless of
  outcome. A failed step never stops the run — later steps that need its output are skipped and say
  so, so half a table of measurements is still worth having. This is exactly the tool that produced
  the `CloudTimeouts` numbers above.

## Not built / future (recorded, no user decision to build them)

- **No whole-library restore** through the cloud point — decision 4. The one "restore a notebook"
  path this arc has is picking a backed-up `.soil` from `Backups/` through the import source, which
  runs the ordinary import pipeline (id-collision Replace/Keep both), not a library restore.
- **No provider chooser.** `ExtensionRegistry.cloud()` takes the first discovered provider and logs
  a warning about any others; a second real provider needs this decision made.
- **No remembered destination/source.** Export's Destination row and Import's source question both
  default back to local every time the screen opens fresh — a remembered choice is a future call
  (noted explicitly on both the V3 and V5 walks).
- **`DOWNLOAD_MS` is flat, not rate-based.** Unlike upload, download stays a flat 120 s ceiling;
  make it a rate like `UPLOAD_LARGE_MS` if a measured import ever needs more.
- **`ICloudHost` stub shape**, for the day an extension needs the cloud for itself: a host-side
  stub minted per showing and revoked with the unbind (the `IDocumentHost` recipe), plus a presence
  boolean extra on the tier-2 launch (`EXTRA_CALENDAR_SCRATCH_PAD_AVAILABLE`'s precedent). Recorded
  in `DRIVE_PLAN.md`, not built, and needs its own user decision before it is.
- **No second provider.** When there is one, it is baked into `:ext-cloud` beside Google Drive
  (decision 15) — a `Dropbox*.kt` set behind the same `CloudService`, never a second APK and never
  a ninth extension point. That fork also needs the two things this arc did not build: a provider
  chooser (`ExtensionRegistry.cloud()` is first-wins over packages, not over providers inside one)
  and a per-provider `account` namespace in `DriveSchema`'s one table.

## Tests

2340 JVM tests/variant as of V5 (2026-09-05) — the arc's running total: 2087 → 2119 (V1, +14
contract/+6 `DriveSql`/+6 `DriveStore`) → 2281 (V2, +17 `DriveAuth`/+114 `:ext-cloud`
REST-ops-tokens/+31 `:app`) → 2302 (V3, +8 `ExportDestination`/+11 `CloudBrowserRules`/+2
`cloudVerdict`) → 2329 (V4, +9 `DeviceFolder`/+18 `CloudBackupRules`/+2 `BackupConfig`) → 2340 (V5,
+4 `ImportSource`/+6 `CloudImportRules`/+1 `CloudBrowserRules.fileTappable`). **No code review any
phase** (decision 12) — the JVM test suite plus a Sonnet-driven Nomad walk plus a short numbered
user checklist for anything adb cannot see (every OAuth sign-in, every SAF pick) is the whole gate.

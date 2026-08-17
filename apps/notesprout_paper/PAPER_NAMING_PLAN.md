# Paper — Extensions arc 2: the extension store + the Naming extension

> **This file is the project's memory across sessions for arc 2.** Context is cleared between
> phases. Everything a fresh session needs — decisions, non-goals, the contract, per-phase tasks,
> tests, status — is here or in the files this document points at. If it isn't written down here (or
> in the repo / project memory), it doesn't exist. **Read this file top to bottom at the start of
> every session**, after `PAPER_PLAN.md` (v0 — architecture), `PAPER_EXTENSIONS_PLAN.md` (arc 1 —
> the extension API v1 + Templates extension, frozen; this arc extends it) and both `CLAUDE.md`
> files. `docs/extensions.md` is the subsystem reference both arcs write into.

## Why

Arc 1 proved the extension shape (separate APK, AIDL, same-signature trust, host draws the UI). Arc 2
continues the evaluation of Notesprout Paper *and* of the extension API with a second, deliberately
different extension — one that **holds data**. The **Naming** extension enhances the default name a
new notebook gets, per folder: a folder can be given a *naming scheme* (literal text + `{date}` /
`{time}` / `{n}` tokens); a notebook created in that folder is pre-named by the scheme. Folders with
no scheme, the library root, and every device without the extension keep today's core default
(`yyyyMMdd_HHmmss`) — the core's behaviour without the extension does not change.

That forces the new concept this arc exists to settle: **where does an extension keep its own data?**
Answer (locked below): the **core owns an encrypted store per extension** — one `.db` per extension
package, SQLCipher under the *global* key, in the core's files dir, reached by the extension only
through a small `IExtensionStore` binder the core hands it per call. The key never crosses the
boundary; `notesprout.db` is not muddied; the data lives in the global encryption space (so an
extension may keep sensitive data); no JSON pref files.

There is still no Extensions UI; extensions are installed and removed by hand (`adb`, Settings → Apps).

---

## Working protocol

Identical to `PAPER_EXTENSIONS_PLAN.md` §"Working protocol" — each phase in a **fresh session**:

1. **Phase start (no-assumption QA):** read this file (all of it), `PAPER_EXTENSIONS_PLAN.md`
   (Locked decisions + Architecture + Appendix A/B), `docs/extensions.md`, the root `CLAUDE.md`, and
   `apps/notesprout_paper/CLAUDE.md`. Confirm the next `⬜` phase with the user, flip it to `🔄`, then
   **ask the phase's "Questions to resolve at phase start"** one at a time in the wizard
   (option-select) format before writing code — recommended default first, plus "Other". Do not
   assume answers. If a new ambiguity surfaces mid-phase that would materially change the work, stop
   and ask.
2. **Code** — auto mode; inline; frugal with agents; no Gradle dependency beyond Appendix B without
   asking; deliverables **exactly as written** — no added scope, no "improving" adjacent code, no
   scaffolding for later phases.
3. **Test** — `./gradlew testDebugUnitTest` (all modules), then build + install the debug APKs on the
   requested **test devices** and hand the user the phase's numbered on-device checklist (copy it,
   don't invent). EPD overlays are invisible to screencap — the user verifies by eye and reports.
4. **Fix → test again** until every test passes (JVM + user-reported device checklist).
5. **Docs / memory / CLAUDE.md** — `apps/notesprout_paper/CLAUDE.md` (standing rules + build facts
   only), `docs/extensions.md` (+ `docs/library.md`, `docs/data.md`, `docs/crypto.md` where named),
   this file's status marker + **Outcome** note, and the project memory
   (`~/.claude/projects/-Users-gregmarine-git-Notesprout/memory/project_paper_naming.md` + its
   `MEMORY.md` index line).
6. **Commit & push** on `paper`. Then the user runs `/clear`.

**Status markers:** `⬜ Not started` · `🔄 In progress` · `🧪 Awaiting device verification` ·
`✅ Complete (commit <hash>)`. Update the marker **the moment the state changes**.

**Test devices** (user verifies by eye; always `-s <serial>`; never install on a device the user didn't
ask for; offline → say so and wait):

| Nickname | Device | Serial | Engine |
|---|---|---|---|
| SNN | Supernote Nomad | `SN078D10012852` | `gpaper-ratta` |
| NA5C | BOOX NoteAir5C | `92c16533` | `gpaper-onyx` |
| MIP11 | Wacom Movink Pad 11 | `5HL21V5007384` | `gpaper-core` |

**Model note:** Fable 5 recommended for **N0** (the store touches the crypto spine — creation entry
point, key handling, binder-side uid checks) and **N1** (bidirectional IPC + three UI entry points);
Opus is fine for N2. Either model follows the phase-start question ritual.

---

## Locked decisions (planning Q&A, 2026-08-16)

| Area | Decision |
|---|---|
| Where extension data lives | **Host-owned store, one `.db` per extension package**: `<app files>/Garden/<ext package>.db` (N0 Q2 — inside `Garden/`, beside the `.soil` files, not a separate `Extensions/` dir), SQLCipher under the **global key** (same passphrase / raw-key machinery as the index and every `.soil`). The extension never sees a key or a path; unlock, lockout, key caching, and deletion stay in the core. Rejected: extension-owned DB with a host-derived sub-key (a key would cross the boundary; SQLCipher dep + recovery burden on every extension); extension-owned DB with the actual passphrase (rejected outright); JSON/prefs (not encrypted). |
| Store shape | **Key/value**: `kv(key TEXT PRIMARY KEY, value BLOB NOT NULL, updatedAt INTEGER NOT NULL)`. Extension serialises whatever it wants. Caps enforced by the host: key ≤ 512 chars, value ≤ 256 KiB, ≤ 50 000 keys per extension (N0 Q1, 2026-08-16 — loosened from the planned 256 / 64 KiB / 10 000). No namespaces, no extension-defined SQL. |
| Store handoff | **Passed as an in-parameter on each AIDL call** that may need it (`IExtensionStore store`). The host mints one store binder per bind (bound to that extension's uid), and **revokes it on unbind** — a call on a revoked binder throws `SecurityException`. Stateless extension side; no reverse discovery, no exported host service. |
| Store lifetime vs. the extension | The `.db` **survives extension uninstall/disable**. Removing an extension's data is Extensions-UI territory. Store DBs are keyed by the *installed* package name, so the `.dev` and release builds of an extension get separate stores (consistent with arc 1's template-identity note). |
| Scheme editor UI | **The core draws one text field from extension data** — the extension describes the field (label, hint, one help line); the core renders a plain `EditText` in the New-folder dialog and in a "Default notebook name…" dialog from the folder long-press. On OK the core asks the extension to validate then save; invalid → the extension's error string as a toast, dialog stays. Extensions still place no UI in the core's flow. |
| Scheme language (v1) | Literal text + `{date}` (`yyyyMMdd`) + `{time}` (`HHmmss`) + `{n}` / `{n:K}` (next number in this folder, zero-padded to K digits; `{n}` at most once). Empty scheme = no scheme. The expanded name must satisfy the core's name rule (`[a-zA-Z0-9_\-. ]`, not `.`/`..`, non-empty) — literal text is validated against it at save time. |
| Outward payload (explicit widening of audit row 3) | This point sends the **folder UUID** (a random id, no content — it is the store key) and, for `defaultName`, the **names of the folder's existing notebooks** (needed only for `{n}`). Never: passphrase, key, paths, index rows, other folders, page/stroke data. Recorded in the boundary audit as the widening this point requires. |
| Inheritance | **None.** Only the folder itself is consulted; no scheme → core default. |
| Root | **No scheme for the library root** — root notebooks always get the core default; no new root chrome. |
| Prefill timing | **Resolved before the New-notebook screen opens**: the library's +Notebook tap calls the namer (≤ 2 s) with the current folder's notebook names, then launches `NewNotebookActivity` with `EXTRA_DEFAULT_NAME`. Failure / timeout / no extension / no scheme → core default, silently. `NewNotebookActivity` stays extension-agnostic (uses the extra if present and valid, else its own default). |
| Folder delete | **Orphan rows tolerated in v1** (rows are tiny; UUIDs never come back). No delete-path call, no contract method. |
| Several namers installed | **First by `ExtensionRegistry` order (label, then package) is used; the rest are ignored + `Slog.d`.** Choosing among providers is Extensions-UI territory. |
| Names / ids | Label **`NSE · Naming`** (debug `NSE · Naming Dev`); `applicationId com.symmetricalpalmtree.notesprout.ext.naming` (debug `.dev`); module `:ext-naming`; extension point **`NOTEBOOK_NAMER`** (`INotebookNamer.aidl`); store interface `IExtensionStore.aidl`. Icon = Tabler `puzzle` (arc-1 convention). |
| Plan / phases | This file, **3 phases**: N0 store · N1 contract + extension + host wiring · N2 hardening/docs freeze. `PAPER_EXTENSIONS_PLAN.md` stays frozen and gains a pointer. |
| Trust / artifacts / version | Unchanged from arc 1: same-signature only, debug-only APKs, no version bump, no `.soil`/index schema change. `ExtensionContract.API_VERSION` stays **1** (a new point = new action + new AIDL + same meta-data key — arc-1 versioning rule; `IExtensionStore` is a new interface, nothing existing is reordered). |

## Deferred (recorded 2026-08-16, not built in this arc)

- **Store pre-warm for the Templates extension** — revisit when Templates gains image templates kept in
  *its* store (a later arc): its first call per process will then pay the same cold store open Naming
  pays, and the same fix applies — at library resume, `ExtensionStores.open` on IO for **every**
  discovered extension, not only the namer (`LibraryActivity.refreshNamer` is the template). Still no
  binding held across screens; the extension *process* start stays the platform's business.

## Non-goals for this arc (do not build, do not scaffold "for later")

No Extensions UI (list / enable / disable / consent / "remove data") · no third-party trust · no
publishing of `:extension-api` · no live preview of the expanded name · no token picker / chips · no
scheme inheritance · no root scheme · no renaming of existing notebooks · no page naming · no
counters beyond `{n}` · no store namespaces, quotas UI, or export/backup of store DBs · no
extension-defined SQL · no changes to the `.soil` schema, the index schema, `NotebookSession`,
g-paper, or the Templates extension's behaviour (the one permitted touch is sharing `CallerCheck` via
`:extension-api` if N1 Q1 says so) · no release signing · no version bump.

---

## Architecture

### Module layout (after this arc)

```
apps/notesprout_paper/
├── settings.gradle.kts            include(":app", ":extension-api", ":ext-templates", ":ext-naming")
├── PAPER_NAMING_PLAN.md           this file
├── docs/extensions.md             gains: "The extension store", "NotebookNamer", audit rows 10–13
├── app/…/notesprout/
│   ├── data/SoilFile.kt           + extensionStoreFile(ctx, pkg)  (Garden/<pkg>.db)
│   ├── data/extstore/             ExtensionStoreDatabase (Room: KvEntity + KvDao), ExtensionStores
│   │                              (open-or-create per package, process cache), ExtensionStoreGate
│   │                              (uid check, caps, revoke — no Android types, JVM-tested),
│   │                              ExtensionStoreBinder (IExtensionStore.Stub delegating to the gate)
│   ├── extension/                 + NamerClient, ExtensionRegistry.notebookNamer(), NamerField
│   └── library/                   LibraryActivity (New-folder field, long-press item, prefill),
│                                  NewNotebookActivity (EXTRA_DEFAULT_NAME)
├── extension-api/src/main/
│   ├── aidl/…/extension/{IExtensionStore,INotebookNamer,SchemeField}.aidl
│   └── kotlin/…/extension/{ExtensionContract (+ constants), SchemeField}.kt (+ HostCallerCheck if N1 Q1)
└── ext-naming/                    Android APPLICATION, com.symmetricalpalmtree.notesprout.ext.naming (.dev)
    └── src/main/
        ├── AndroidManifest.xml    NO launcher activity; one exported <service> (NOTEBOOK_NAMER + API_VERSION meta)
        ├── kotlin/…/ext/naming/{NotebookNamerService,SchemeEngine,CallerCheck}.kt
        └── res/                   strings (label, field label/hint/help, error messages), puzzle icon
```

Dependency direction unchanged: `:app → :extension-api`, `:ext-templates → :extension-api`,
`:ext-naming → :extension-api`. `:extension-api` depends on nothing in the project.

### The extension store (host side, `:app` `data/extstore/`)

- **File:** `extensionStoreFile(ctx, pkg) = File(gardenDir(ctx), "$pkg.db")` — inside `Garden/`
  beside the `.soil` files (N0 Q2) — added to `data/SoilFile.kt` (the only path constructors). `pkg` is the extension's *installed* package name as returned by
  discovery; it is validated `[a-zA-Z0-9_.]+` before use (a `ProviderRef` package name — never user
  input — but the guard costs nothing).
- **Encryption:** the global passphrase from `KeySession.get()` (process RAM; set once the index is
  open — every caller is behind `IndexGuard`). File id for `KeyMaterial`/`KeyOpener` =
  `"ext:<pkg>"` (namespaced so it can never collide with a notebook UUID or `INDEX_FILE_ID`).
- **Open-or-create — `ExtensionStores.open(ctx, pkg): ExtensionStoreDatabase`** (IO; `synchronized`;
  process-lifetime cache `Map<pkg, db>`, never closed except `closeAll()` for tests/debug):
  - file missing or 0 bytes → **create**: `Room.databaseBuilder(...).openHelperFactory(SoilCrypto.roomFactory(pass))`
    then `KeyOpener.warm(ctx, "ext:<pkg>", file, pass)` — **byte-for-byte the `SoilDatabase.create`
    pattern** (this is the **third named create entry point**; `docs/crypto.md` audit item 2 is
    updated to list it; `require(!file.exists() || file.length() == 0L)`).
  - otherwise → `SoilCrypto.requireExisting(file)` then `KeyOpener.roomFactoryFor(ctx, "ext:<pkg>", file, pass)`
    (cached raw key verified against the file, passphrase fallback + warm) — the `SoilDatabase.open`
    pattern. Every factory is `NonDestructiveOpenHelperFactory`-wrapped by `SoilCrypto`, as always.
  - Room DB: `@Database(entities=[KvEntity], version=1)`, `KvEntity(key PK, value BLOB, updatedAt)`,
    `KvDao`: `get(key): ByteArray?`, `upsert(KvEntity)`, `delete(key)`, `keys(prefix): List<String>`
    (`WHERE key LIKE :prefix || '%' ORDER BY key`, prefix `LIKE`-escaped), `count(): Int`.
- **Pre-open rule:** the host calls `ExtensionStores.open(ctx, pkg)` on IO **before** binding the
  extension for any call that carries a store — so a cold open (SQLCipher KDF ≈ 0.5–1.5 s on e-ink
  when the raw key isn't cached) is never inside the extension call's 2 s timeout window.
- **`ExtensionStoreBinder(db, extUid): IExtensionStore.Stub`** — minted by the client per bind:
  - every method first: `check(Binder.getCallingUid() == extUid && !revoked)` else
    `SecurityException` (the binder was handed to exactly one process; nothing else may use it, and
    not after unbind). `revoke()` is called from the client's `finally`.
  - caps: key `1..512` chars; value `≤ 256 KiB`; `put` of a *new* key when `count() ≥ 50 000` →
    `IllegalStateException` (surfaces to the extension as a `RemoteException`… AIDL maps runtime
    exceptions of the standard set — the extension treats any exception as "store unavailable").
  - methods run synchronously on the host's Binder thread (Room blocking DAO — never Main).
- **Backup/restore/compaction:** none in Paper (no backup subsystem exists) — nothing to do.
  Delete of the extension: nothing (data survives — locked). Debug "Forget cached key" already
  clears `KeyMaterial` for every file id, including `ext:*`.

### Contract additions (`:extension-api`) — exact

`ExtensionContract` gains:

| Constant | Value |
|---|---|
| `ACTION_NOTEBOOK_NAMER` | `"com.symmetricalpalmtree.notesprout.extension.NOTEBOOK_NAMER"` |
| `STORE_MAX_KEY_CHARS` | `512` |
| `STORE_MAX_VALUE_BYTES` | `256 * 1024` |
| `STORE_MAX_KEYS` | `50_000` |
| `MAX_NAME_CHARS` | `100` (host-side cap on a returned default name / scheme text) |

AIDL (`extension-api/src/main/aidl/com/symmetricalpalmtree/notesprout/extension/`):

```aidl
// IExtensionStore.aidl — host-owned encrypted key/value store, scoped to the calling extension.
package com.symmetricalpalmtree.notesprout.extension;
interface IExtensionStore {
    /** Value for [key], or null if absent. */
    byte[] get(String key);
    /** Insert or replace. key 1..512 chars, value ≤ 256 KiB, ≤ 50 000 keys per extension. */
    void put(String key, in byte[] value);
    /** Remove [key] (no-op if absent). */
    void delete(String key);
    /** Keys starting with [prefix] ("" = all), ascending. */
    List<String> keys(String prefix);
}

// SchemeField.aidl
package com.symmetricalpalmtree.notesprout.extension;
parcelable SchemeField;

// INotebookNamer.aidl
package com.symmetricalpalmtree.notesprout.extension;
import com.symmetricalpalmtree.notesprout.extension.IExtensionStore;
import com.symmetricalpalmtree.notesprout.extension.SchemeField;
interface INotebookNamer {
    /** How the host should draw the scheme field (label, hint, one help line). No store needed. */
    SchemeField describeField();
    /** The scheme stored for [folderId], or null if none. */
    String currentScheme(IExtensionStore store, String folderId);
    /** null if [scheme] is acceptable, else a short user-facing error. Pure — no store. */
    String validateScheme(String scheme);
    /** Store [scheme] for [folderId]; "" (or blank) clears it. Throws IllegalArgumentException if invalid. */
    void saveScheme(IExtensionStore store, String folderId, String scheme);
    /** The default name for a new notebook in [folderId] given the folder's existing notebook names,
     *  or null if the folder has no scheme (host then uses its own default). */
    String defaultName(IExtensionStore store, String folderId, in List<String> siblingNames);
}
```

- `SchemeField(val label: String, val hint: String, val help: String)` — hand-written Parcelable
  (`writeString ×3`, `@JvmField CREATOR`, `describeContents = 0`).
- Everything the host receives is untrusted: `label`/`hint`/`help` are truncated to sane lengths
  (`≤ 40 / 60 / 200` chars) before display; a returned `defaultName` is accepted only if
  `NewNotebookActivity.validateName(name) == null && name.length ≤ MAX_NAME_CHARS`, else the core
  default is used (log, no toast).

### Host side (`:app` `extension/`)

- Manifest `<queries>` gains `<intent><action android:name="…NOTEBOOK_NAMER"/></intent>`.
- `ExtensionRegistry.notebookNamer(ctx): ProviderRef?` — same discovery + trust filter as
  `templateProviders`, action `ACTION_NOTEBOOK_NAMER`, **first** by (label, package); if more than one
  candidate survives the filter the others are logged (`Slog.d`) and dropped.
- `NamerClient(ctx, ref)` — the `TemplateProviderClient` shape (explicit component, `checkSignatures`
  re-run before bind, `BIND_AUTO_CREATE` on the app context, bind ≤ 3 s, call ≤ **2 s**, IO,
  supervisor scope, unbind in `finally`, every failure → `ExtensionCallException`). Additions:
  - `private suspend fun <T> callWithStore(block: (INotebookNamer, IExtensionStore) -> T): T` —
    `ExtensionStores.open(ctx, ref.packageName)` **before** bind (pre-open rule); mints
    `ExtensionStoreBinder(db, extUid)` (`extUid` = `packageManager.getPackageUid(ref.packageName, 0)`,
    fetched at bind time); `revoke()` in the same `finally` as the unbind.
  - `describeField(): SchemeField` · `currentScheme(folderId): String?` · `validate(scheme): String?`
    · `save(folderId, scheme)` · `defaultName(folderId, siblings): String?`.
- **`LibraryActivity`:**
  - `namerRef: ProviderRef?` refreshed in `onResume` on IO (`ExtensionRegistry.notebookNamer`) — a
    few ms; chrome doesn't depend on it, so no layout jump. All three entry points below are **absent
    when `namerRef == null`** — nothing hints at naming schemes without the extension.
  - **+Notebook** (`btnNewNotebook`): if `namerRef != null && folderId != null` →
    `NamerClient.defaultName(folderId, currentNotebookNames)` (`currentNotebookNames` = names of the
    `NOTEBOOK` summaries in the current listing, already in memory) → launch
    `NewNotebookActivity.intent(ctx, folderId, defaultName)`; null / exception → launch without.
    Root (`folderId == null`) never calls. Feedback while resolving = N1 Q3.
  - **New-folder dialog:** if `namerRef != null`, `describeField()` runs **before** the dialog shows
    (same before-not-during rule as prefill; failure → dialog without the field). Layout: name field,
    then a second `EditText` (label = `field.label` as a small caption above it, hint = `field.hint`),
    then `field.help` in `BodyMedium`-secondary style. **CREATE:** validate the name (as today) → if
    the scheme text is non-blank: `validate(scheme)` → error → toast + stay; then `createFolder` →
    if non-blank: `save(newFolder.id, scheme)`; a save failure → toast
    `R.string.naming_save_failed` ("Folder created — naming scheme not saved") and dismiss anyway
    (the folder exists; the user can retry from long-press).
  - **Folder long-press** (`onCardLongPress`, `CardItem.Folder`): a new action `R.string.action_naming`
    ("Default notebook name…", icon = N1 Q2) inserted **before Rename**, only when `namerRef != null`.
    Tapping: `describeField()` + `currentScheme(id)` (before the dialog shows) → `AlertDialog` titled
    with the folder name: caption + `EditText` prefilled with the current scheme (select-all) + help
    line; OK → blank ⇒ `save(id, "")` (clears); else `validate` → toast + stay / `save`. Failure on
    the way in → toast `R.string.naming_unavailable` ("Naming extension didn't respond") and no dialog.
- **`NewNotebookActivity`:** `EXTRA_DEFAULT_NAME` (nullable); `intent(ctx, parentFolderId, defaultName:
  String? = null)`; `onCreate` pre-fills `extra?.takeIf { validateName(it) == null && it.length ≤ 100 }
  ?: defaultName()`. Nothing else changes (template flow untouched).

### Extension side (`:ext-naming`)

- Gradle mirrors `:ext-templates` exactly (application, `minSdk 29`, `compileSdk`/`targetSdk 35`,
  `versionCode 1`/`0.1.0`, `buildConfig` + `HOST_PACKAGE` per build type, `applicationIdSuffix ".dev"`,
  deps `:extension-api` + `androidx.core:core-ktx:1.13.1`, `allowBackup="false"`, no Activity, label
  `NSE · Naming` (debug ` Dev`), puzzle icon copied from `:ext-templates`).
- Manifest: one exported `<service android:name=".NotebookNamerService">` with intent-filter
  `…NOTEBOOK_NAMER` + `<meta-data …API_VERSION android:value="1"/>`.
- `NotebookNamerService`: `onBind` → `INotebookNamer.Stub`; **every** method first
  `CallerCheck.enforce(context)` (host package + `SIGNATURE_MATCH`, as arc 1). Holds no mutable
  state. Store key: `"folder:<folderId>"` → UTF-8 bytes of the scheme; `saveScheme("")` deletes the key.
- **`SchemeEngine`** (pure Kotlin, JVM-tested):
  - `parse(scheme): List<Part>` where `Part = Literal(text) | Date | Time | Counter(width)`; unknown
    `{…}` → error; `{n}` more than once → error; literal text failing the core name rule → error;
    result empty → error; scheme > 100 chars → error. Errors are user-facing strings from `strings.xml`.
  - `expand(scheme, now: Long, siblingNames: List<String>): String` — `Date`/`Time` from `now`
    (`SimpleDateFormat`, `Locale.ROOT`); `Counter` = (highest number among sibling names that match
    the scheme's skeleton — see N1 Q4 for whether date/time positions match as wildcards or as
    today's values) + 1, zero-padded to `width` (min 1; a number wider than `width` is not truncated).
  - `validate(scheme): String?` = parse errors or null.

### Rules for adding a future extension point (from arc 1 — followed here)

1. Action + AIDL + parcelables in `:extension-api`; keep the dependency direction. ✔ (`NOTEBOOK_NAMER`, `IExtensionStore`, `INotebookNamer`, `SchemeField`)
2. Discovery in `ExtensionRegistry` (same trust filter) + a client with explicit timeouts,
   bind-per-operation, unbind-in-finally, untrusted-payload caps. ✔ (`notebookNamer`, `NamerClient`)
3. The core decides what the user sees on failure; extensions never show UI in the core's flow. ✔
4. Document in `docs/extensions.md` + boundary-audit rows. ✔ (N2)
5. Nothing crosses that the call doesn't need. ✔ — with the **explicit, recorded widening**: folder
   UUID + sibling notebook names for this point only.

---

## Phases

### Phase N0 — The extension store (contract + host implementation, no UI)
**Status:** ✅ Complete (commit 04c5894)

**Goal:** `IExtensionStore` exists in the contract; the core can open-or-create an encrypted
per-extension store under the global key, mint a uid-bound revocable binder over it, and enforce the
caps. **No user-visible change** — nothing calls it yet except tests and a debug probe.

**Questions to resolve at phase start** (one at a time; recommended default first):
1. Caps — key 256 chars / value 64 KiB / 10 000 keys (rec.) / other values? **→ Answered
   2026-08-16: looser — 512 chars / 256 KiB / 50 000 keys.**
2. Store file location — `<app files>/Extensions/<pkg>.db` (rec.) / inside `Garden/` / other?
   **→ Answered: inside `Garden/` (`Garden/<pkg>.db`).**
3. Debug probe — a debug-menu item "Extension store self-test" that round-trips a value in a store
   for a fake package `probe.test` and toasts OK/FAIL (rec.: yes — Room+SQLCipher can't run on the
   JVM, so this is the only pre-N1 on-device check) / skip and rely on N1's device checklist?
   **→ Answered: yes.**
4. Confirm the third create entry point is acceptable (`ExtensionStores.open` creates a missing
   store exactly the way `SoilDatabase.create` creates a `.soil`) — rec.: yes. **→ Answered: yes.**

**Deliverables**
1. `:extension-api`: `IExtensionStore.aidl`; `ExtensionContract` gains `STORE_MAX_KEY_CHARS`,
   `STORE_MAX_VALUE_BYTES`, `STORE_MAX_KEYS`, `MAX_NAME_CHARS`.
2. `:app` `data/SoilFile.kt`: `extensionStoreFile` (`Garden/<pkg>.db`, + package-name guard).
3. `:app` `data/extstore/`: `KvEntity`, `KvDao`, `ExtensionStoreDatabase`, `ExtensionStores`
   (open-or-create, process cache, `closeAll()`), `ExtensionStoreBinder` (uid check, revoke, caps) —
   exactly as in "The extension store".
4. Debug probe per Q3 (in `src/debug/…/library/DebugMenu.kt`, no-op twin untouched).
5. JVM tests (`:app`): `ExtensionStoreBinderTest` with a fake `KvDao` (uid mismatch → `SecurityException`;
   revoked → `SecurityException`; key too long / empty, value too big, keys cap → rejected; `keys("")`
   ordering; `LIKE` escaping of `%`/`_` in prefix); `SoilFileTest` for the package-name guard.
6. Docs: `docs/extensions.md` §"The extension store" (model, file, key, caps, handoff/revoke rules,
   pre-open rule, survives-uninstall); `docs/data.md` (`Garden/<pkg>.db` store files, `ext:<pkg>` file ids);
   `docs/crypto.md` audit item 2 lists the third create entry point.

**Tests**
- JVM: `./gradlew testDebugUnitTest` green; `assembleDebug` builds all four modules (`:ext-naming`
  is **not** created in N0 — three modules).
- On device (Claude runs; user watches): install `app-debug.apk`; run the debug probe (Q3) → "OK";
  `adb shell ls /sdcard/Android/data/com.symmetricalpalmtree.notesprout.dev/files/Garden/` shows
  `probe.test.db`; `xxd -l 16` of the file is **not** `SQLite format 3` (encrypted header); Debug ⋯ →
  Forget cached key → relaunch → unlock → probe again → "OK" (raw-key re-derivation path).
- **User device checklist:** 1. Library, notebooks, templates all behave as before (regression only).

**Close-out:** status ✅ + Outcome; docs; memory; commit + push `paper`.

**Outcome (N0):** JVM half built 2026-08-16 (base commit for N2's review range: **4fe2ed6**).
Answers: caps 512 / 256 KiB / 50 000; store file `Garden/<pkg>.db`; probe yes; third create entry
point accepted. One structural addition beyond the plan's file list: `ExtensionStoreGate` — the
checks/caps with no Android types, because `IExtensionStore.Stub` extends `android.os.Binder` and
cannot be constructed on the JVM (`ExtensionStoreBinderTest` drives the gate with a fake `KvDao`; 9
tests + 2 `SoilFileTest`). `KvDao.keysLike` takes a ready `LIKE … ESCAPE '\\'` pattern built by
`ExtensionStoreGate.likePattern`. Device probe: OK on SNN + NA5C + MIP11 (encrypted `Garden/probe.test.db`); forget-key → unlock → probe OK on MIP11 (cold open 668 ms, warm 0 ms). User regression check passed on all three 2026-08-16.

---

### Phase N1 — NotebookNamer contract + `:ext-naming` + host wiring
**Status:** ✅ Complete (commit — see below)

**Goal:** the Naming extension installs; folders can be given a scheme from the New-folder dialog and
from long-press; +Notebook in a folder with a scheme opens New-notebook pre-named by it; without the
extension nothing in the core changes.

**Questions to resolve at phase start** (one at a time; recommended default first):
1. Share `CallerCheck` — move the host-package + signature check into `:extension-api` as
   `HostCallerCheck.enforce(context, hostPackage)` and have **both** extensions call it (rec.: yes —
   the one permitted touch to `:ext-templates`; third parties get it for free) / duplicate the file
   into `:ext-naming` and leave Templates untouched? **→ Answered 2026-08-16: share via `:extension-api`.**
2. Long-press item icon — Tabler `cursor-text` (rec.) / `abc` / `signature` / other (new drawable
   `ic_<name>.xml`, 24dp, stroke 2, round caps)? Wording "Default notebook name…" (rec.) / other?
   **→ Answered: `cursor-text` + "Default notebook name…".**
3. +Notebook feedback while the name resolves (≤ 2 s worst case, ~0.1–0.5 s typical) — none (rec.;
   the tap simply takes a beat) / reuse the "Opening…" popup pattern / other? **→ Answered: none.**
4. `{n}` skeleton matching — date/time positions match as **wildcards** so the counter continues
   across days (rec.: `Meeting {date} {n:2}` → 01, 02 today, 03 tomorrow) / match today's values so
   the counter restarts each day? **→ Answered: wildcards.**
5. Field wording (from the extension's `strings.xml`) — label "Default notebook name", hint
   "e.g. Meeting {date} {n:2}", help "Tokens: {date} {time} {n} {n:3}. Leave empty for the standard
   name." (rec.) / other? **→ Answered: recommended set.**
6. Save-failure wording — "Folder created — naming scheme not saved" and "Naming extension didn't
   respond" (rec.) / other? **→ Answered: recommended.**

**Deliverables**
1. `:extension-api`: `INotebookNamer.aidl`, `SchemeField.aidl` + Kotlin Parcelable,
   `ExtensionContract.ACTION_NOTEBOOK_NAMER` (+ `HostCallerCheck` per Q1).
2. `settings.gradle.kts` `include(":ext-naming")`; `:ext-naming` exactly as in "Extension side"
   (manifest, service, `CallerCheck`/`HostCallerCheck`, `SchemeEngine`, strings, icon, `HOST_PACKAGE`,
   debug label override).
3. `:app`: manifest `<queries>` action; `ExtensionRegistry.notebookNamer`; `NamerClient`;
   `LibraryActivity` (three entry points) + `NewNotebookActivity` (`EXTRA_DEFAULT_NAME`) exactly as
   in "Host side"; strings `action_naming`, `naming_save_failed`, `naming_unavailable`; new icon per Q2;
   the New-folder dialog and the scheme dialog share one small builder (`library/SchemeDialogs.kt` or
   inline — keep `LibraryActivity` under ~800 lines; extract if it would cross).
4. JVM tests: `:ext-naming` `SchemeEngineTest` (parse each token; unknown token / double `{n}` /
   illegal literal / empty result → the right error; expand date+time with a fixed clock; counter with
   0, gapped, and padded siblings; width not truncating; skeleton matching per Q4); `:app`
   `NewNotebookActivity`-side pure check of the extra acceptance rule if it is factored as a function.
5. Docs: `docs/extensions.md` §"NotebookNamer" (contract, host behaviour, failure surface, the
   explicit outward-payload widening); `docs/library.md` (New folder, long-press, +Notebook prefill);
   `README.md` three-APK install line; `CLAUDE.md` build lines for `:ext-naming`.

**Tests**
- JVM: `./gradlew testDebugUnitTest` (all modules) green; `assembleDebug` builds four modules.
- Shell sanity per device (Claude runs) after installing `ext-naming-debug.apk`: `pm list packages |
  grep ext.naming`; `dumpsys package … | grep -A6 NOTEBOOK_NAMER`; `pm resolve-activity --brief -c
  android.intent.category.LAUNCHER <pkg>` → "No activity found"; BOOX: `pm enable`, wait, confirm
  `pm list packages -d` (re-disable trap).
- **User device checklist** — install `app-debug.apk` + `ext-naming-debug.apk` (+ keep
  `ext-templates-debug.apk` installed):
  1. Library root → **+Notebook**: name is the standard `yyyyMMdd_HHmmss` (root never gets a scheme).
  2. **+Folder**: the dialog shows the name field **and** a "Default notebook name" field with the
     help line. Create folder `Work` with scheme `Meeting {date} {n:2}`.
  3. Enter `Work` → **+Notebook**: name field pre-filled `Meeting <today> 01`, select-all'd; CREATE →
     opens; back → **+Notebook** again → `Meeting <today> 02`.
  4. Rename that notebook to `Meeting <today> 07`; **+Notebook** → `Meeting <today> 08` (highest + 1).
  5. **+Folder** `Misc` with the scheme left empty → enter → **+Notebook** → standard name.
  6. Long-press `Misc` → "Default notebook name…" → dialog (field empty) → type `Note {time}` → OK →
     **+Notebook** → `Note HHmmss`. Long-press again → field shows `Note {time}`; clear it → OK →
     **+Notebook** → standard name.
  7. Validation: long-press `Work` → enter `Bad {foo}` → OK → toast with the error, dialog stays;
     enter `A/B` → error (illegal character); enter `{n} {n}` → error.
  8. Move `Work` into another folder; its scheme still applies. Rename `Work` → still applies.
  9. Templates still work: New-notebook in `Work` with Lined selected creates a lined notebook named by
     the scheme.
  10. `pm disable-user --user 0 …ext.naming.dev` (Claude): +Folder dialog has **no** scheme field;
      long-press folder has **no** naming item; +Notebook in `Work` → standard name. `pm enable` →
      everything back **and `Work`'s scheme is remembered** (store survived).
  11. `uninstall` the extension → same as 10; reinstall → scheme remembered.
  12. `am force-stop …ext.naming.dev` then +Notebook in `Work` → scheme name (auto-create binding).
  13. Debug ⋯ → Forget cached key → relaunch → unlock → +Notebook in `Work` → scheme name.
  14. Timing by eye on SNN: +Notebook in a scheme folder opens New-notebook well under a second
      after the first (cold) call.
- Claude-side log check on one device: `logcat -s ExtensionRegistry NamerClient ExtensionStores`
  → one bind/unbind per call, store opened once per process, no `leaked ServiceConnection`.

**Close-out:** status ✅ + Outcome (timings, any e-ink observation); docs; memory; commit + push.

**Outcome (N1):** built 2026-08-16. Answers: Q1 shared `HostCallerCheck` in `:extension-api` (Templates
switched, its private `CallerCheck` deleted) · Q2 `cursor-text` + "Default notebook name…" · Q3 no
feedback (a `resolvingName` guard drops a second tap during the beat) · Q4 wildcards · Q5/Q6 the
recommended wording. Structure: `SchemeEngine` returns error **codes** (enum) and the service maps
them to `strings.xml`, so the engine stays Android-free (18 JVM tests); the service rethrows any store
failure as `IllegalStateException` (Binder-safe) rather than letting a `RemoteException` kill the
extension process; the two dialogs share `library/SchemeDialogs.kt` (`LibraryActivity` 735 lines).
`:ext-naming` mirrors `:ext-templates` exactly (no core-ktx — Templates doesn't use it either).
JVM: 97 tests green (app 68 · ext-naming 18 · ext-templates 8 · extension-api 3). Installed SNN +
NA5C + MIP11; shell sanity OK on all three. Claude smoke on MIP11 (LCD): New-folder field drawn,
`Work` + `Meeting {date} {n:2}` → +Notebook prefills `Meeting 20260816 01`, then `02`; long-press item +
dialog show the stored scheme; `Bad {foo}` → extension's toast, dialog stays. SNN log: one bind/unbind
per call, store created once, warm `defaultName` ≈ 50 ms, cold `describeField` ≈ 350 ms, no leaked
`ServiceConnection`. **Item 14 follow-up (user saw ~1–1.5 s on a cold tap):** measured on SNN with adb
`input` overhead (~500 ms!) subtracted — warm ≈ 0.26 s total (= no-extension baseline), extension
process killed +≈ 380 ms, app-cold ≈ 0.85 s, after key wipe + KDF ≈ 1.5–2 s once. Added (user-approved)
a **store pre-warm at library resume** (`refreshNamer` → `ExtensionStores.open` on IO, silent on
failure): app-cold tap→bind 125 → 22 ms; total ≈ 0.75 s. Process start is the remaining cold cost and
is by design (no bindings held across screens). **User checklist 1–14 passed on SNN + NA5C + MIP11 2026-08-16.**

---

### Phase N2 — Hardening, review, boundary audit, docs freeze
**Status:** ⬜ Not started

**Goal:** the store + NotebookNamer are trustworthy enough to be the pattern every data-holding
extension follows.

**Questions to resolve at phase start** (one at a time):
1. Anything observed in N1 the user wants changed before freezing (wording, timing, presentation)?
   (rec.: no — freeze as built)
2. Confirm scope freeze: fixes only.

**Deliverables**
1. `/code-review high` over the arc's diff (`git diff <N0 base>...HEAD` — the range, not a bare ref;
   the base is the commit before N0's first commit, record it in N0's Outcome); fix confirmed findings.
2. **Boundary audit** rows added to `docs/extensions.md` and walked:
   - **10 — Outward payload of NotebookNamer** is exactly folder UUID + sibling notebook names (+
     the scheme text the user typed); no other argument exists in the interface. Recorded as the
     explicit widening of row 3.
   - **11 — The store binder is uid-bound, per-bind, revocable, capped.** Minted only inside
     `NamerClient.callWithStore`, checks `getCallingUid() == extUid` and `!revoked` on every method,
     revoked in the same `finally` as the unbind; key/value/count caps enforced host-side; the DB is
     opened only through `SoilCrypto` factories under the global key; no key, path, or `File` ever
     crosses (`IExtensionStore` has no such method).
   - **12 — Inward payload is validated.** `SchemeField` strings truncated; `defaultName` accepted only
     if it passes the core's name rule and length cap, else the core default (never a toast, never a
     crash); `currentScheme` shown verbatim only inside a text field.
   - **13 — Failure never changes what the user chose.** Namer failure → core default name / dialog
     without the field / toast + stay; a folder is created before its scheme is saved and a save
     failure says so; the extension being absent removes every entry point and nothing else.
3. `docs/extensions.md` final: "Writing an extension" gains "Using the store" (get/put/delete/keys,
   caps, treat any exception as unavailable, key naming advice) and "Adding a data-holding point";
   `README.md`; `CLAUDE.md` standing rules (store facts + namer facts).
4. This file frozen; memory updated (arc complete).

**Tests:** full N1 device checklist again on all three devices + arc-1 E1 checklist items 1–3 + v0
regression subset (create/open/write/flip, library create/rename/move/delete, cold-launch reopen).

**Close-out:** status ✅ + Outcome; commit + push `paper`.

**Outcome (N2):** —

---

## Appendix A — Constants (this arc)

| Name | Value |
|---|---|
| `ACTION_NOTEBOOK_NAMER` | `com.symmetricalpalmtree.notesprout.extension.NOTEBOOK_NAMER` |
| `META_API_VERSION` / `API_VERSION` | unchanged (`…extension.API_VERSION` / `1`) |
| Store caps | key 1..512 chars · value ≤ 256 KiB · ≤ 50 000 keys per extension |
| `MAX_NAME_CHARS` | 100 |
| Store file | `<app files>/Garden/<ext package>.db`; key-cache file id `ext:<pkg>` |
| Timeouts | bind 3 000 ms · every namer call 2 000 ms |
| Naming extension package | `com.symmetricalpalmtree.notesprout.ext.naming` (debug `.dev`) |
| Store key used by the Naming extension | `folder:<folder UUID>` → UTF-8 scheme |
| Tokens | `{date}`=`yyyyMMdd` · `{time}`=`HHmmss` · `{n}` / `{n:K}` (once) · literal `[a-zA-Z0-9_\-. ]` |

## Appendix B — Allowed dependencies (in addition to arc 1's Appendix B)

```
:extension-api   — unchanged (none)
:ext-naming      — project(":extension-api"), androidx.core:core-ktx:1.13.1, testImplementation junit:junit:4.13.2
:app             — unchanged (Room + SQLCipher already present; the store reuses them)
```
No `kotlin-parcelize`, no new plugins, no serialization lib in `:ext-naming` (the scheme is stored as
UTF-8 bytes).

## Appendix C — Build & install (this arc)

```sh
cd ~/git/Notesprout/apps/notesprout_paper
./gradlew assembleDebug && ./gradlew testDebugUnitTest
adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <serial> install -r ext-naming/build/outputs/apk/debug/ext-naming-debug.apk
adb -s <serial> shell pm enable com.symmetricalpalmtree.notesprout.ext.naming.dev      # BOOX: re-run after a few seconds, confirm `pm list packages -d`
adb -s <serial> shell pm disable-user --user 0 com.symmetricalpalmtree.notesprout.ext.naming.dev
adb -s <serial> uninstall com.symmetricalpalmtree.notesprout.ext.naming.dev
adb -s <serial> shell ls /sdcard/Android/data/com.symmetricalpalmtree.notesprout.dev/files/Garden/   # <pkg>.db store files beside the .soil files
```

## Appendix D — Reference map

| Concern | Where |
|---|---|
| Arc-1 client/registry pattern to copy | `app/.../extension/{ExtensionRegistry,TemplateProviderClient,TemplateChoice}.kt` |
| Create/open patterns the store mirrors | `app/.../data/soil/SoilDatabase.kt` (`create` / `open`), `crypto/{SoilCrypto,KeyOpener,KeyMaterial,KeySession}.kt`, `docs/crypto.md` |
| Path constructors | `app/.../data/SoilFile.kt` |
| Library entry points | `library/LibraryActivity.kt` (`showNewFolderDialog`, `onCardLongPress`, `wireBars` +Notebook), `library/NewNotebookActivity.kt` (`defaultName`, `validateName`, `intent`), `core/ActionSheetDialog.kt`, `core/Dialogs.kt` |
| Extension reference implementation | `ext-templates/` (`TemplateProviderService`, `CallerCheck`, manifest, Gradle, icon) |
| Debug menu (probe) | `app/src/debug/.../library/DebugMenu.kt` (+ release no-op twin) |
| Android references | AIDL in-parameter interfaces (`IExtensionStore` as an argument), `Binder.getCallingUid`, `PackageManager.getPackageUid`, Room `@Query` `LIKE` escaping |

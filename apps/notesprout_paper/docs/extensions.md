# Extensions — the extension model & contract v1

> Arc: `PAPER_EXTENSIONS_PLAN.md` (the cross-session memory for the extensions work). This doc is the
> subsystem reference. **Phase E0** established the contract library and the first extension APK;
> **Phase E1** wired the host (`:app`) to discover and call them and removed the core's renderer;
> **Phase E2** reviewed and hardened both sides and froze this doc as the pattern every later extension
> point follows (§"Rules for adding a future extension point", §"Boundary audit").
> **Arc 2** (`PAPER_NAMING_PLAN.md`): **N0** added the host-owned encrypted extension store; **N1**
> added the second point, **NotebookNamer**, and the Naming extension (§"The Naming extension",
> §"NotebookNamer — host behaviour").
> **Arc 3** (`PAPER_RECOGNITION_PLAN.md`): **M0** added the third point — the engine-neutral
> **HandwritingRecognizer** *capability* point — and the ML Kit extension (§"HandwritingRecognizer
> (contract)", §"The ML Kit extension"); the host client + debug test surface come in M1.

Notesprout's original design baked too many features into the core. Paper's core is **paper with
strokes** — a library of notebooks, each a stack of pages you write on. Everything else is added by
**extensions**: opt-in, removable, each a **separate APK** the core calls over Android IPC (AIDL).

The first extension is **Templates**: it offers the generated base templates (Lined / Dotted / Grid)
on the New-notebook screen and renders the chosen one into the WEBP the `.soil` already stores.
**Templates remain a core concept of the `.soil`** — a notebook shared with someone who lacks the
extension still shows its template, because the core reads and draws the stored WEBP exactly as before.
Only *how templates are offered and generated* lives in the extension.

There is **no Extensions UI yet**: extensions are installed and removed by hand (`adb install` /
`adb uninstall`, or Settings → Apps → Uninstall). An Extensions UI is a later arc.

---

## The extension model (concepts)

- **Extension** — an installed Android package. Its user-visible name is its application label
  (`ApplicationInfo.loadLabel`).
- **Extension point** — a named capability the core knows how to call. Each point has: an **intent
  action** (declared on a `<service>` intent-filter), an **AIDL interface**, and a **`<meta-data>` API
  version** on that `<service>`. One `<service>` per point per extension. **v1 has exactly one point:
  `TemplateProvider`.**
- **No launcher** — an extension declares **no launcher Activity**; it shows no icon in any app drawer.
  It is visible only under Settings → Apps (which is also how a user removes one until the Extensions UI
  exists). **Supernote is the exception:** Ratta's sidebar "Apps" grid and Settings → Apps → My Apps
  enumerate every user-installed *package*, launcher Activity or not, so an extension shows up there
  beside the app (tapping it does nothing). Accepted 2026-08-16 as the platform cost of separate APKs;
  the mitigation is the naming + icon convention below, so it at least reads as an add-on, not an app.
- **Naming + icon convention** — an extension's label is **`NSE · <Name>`** ("Notesprout Extension";
  debug builds append ` Dev`) and its icon is the **Tabler `puzzle`** outline in ink-black on white — the
  same visual vocabulary as the app's own launcher icon (the Tabler `seedling`, bare: "Paper" is a
  codename, the sprout is the brand). The prefix groups extensions in any alphabetical list and survives
  Ratta's ~18-character label truncation with the extension's own name intact; the puzzle says "not
  runnable" where the word can't. (`NPE` was rejected — it reads as NullPointerException.) Adaptive-icon
  vectors keep Tabler's stroke ratio (2 units per 24) with the glyph scaled ×3.1–3.4 and centred — sized
  like Ratta's own icons, inside the rounded-square mask, only brushing a circular one; a sprout inside
  the puzzle piece was tried and dropped — at launcher size it is a smudge.
- **Discovery** — the core runs
  `PackageManager.queryIntentServices(Intent(action), GET_META_DATA)`. The core manifest must declare
  the action in `<queries>` (mandatory on API 30+, or the query silently returns nothing). Disabled
  packages/components are not returned, so `pm disable` == uninstalled from the core's point of view.
- **Trust (v1: same-signature only)** — a discovered service is used only if the service is exported,
  its meta-data API version **equals** `ExtensionContract.API_VERSION`, and
  `PackageManager.checkSignatures(corePackage, extPackage) == SIGNATURE_MATCH`. The extension,
  symmetrically, refuses any caller whose uid is not the host package with a matching signature.
  Lifting to third parties later = one condition + the Extensions UI's consent step.
- **Calling** — the core binds (`bindService`, `BIND_AUTO_CREATE`), waits for `onServiceConnected` with
  a timeout, runs the AIDL call(s) on a background dispatcher under a timeout, and **unbinds in
  `finally`**. The core never holds a binding across screens; it binds per operation.
- **Payload rules** — the core hands an extension only what the call needs (for templates: page
  geometry + dpi). **Nothing about keys, files, the index, or notebook contents ever crosses.** Data
  coming back is untrusted: size-capped and bounded-decoded.

---

## Contract v1 (`:extension-api`)

`:extension-api` is an Android **library** (`namespace com.symmetricalpalmtree.notesprout.extension`,
`minSdk 29`, AIDL enabled) that **depends on nothing in `:app` and on no library beyond the Kotlin
stdlib**. Third parties will consume it as a published Maven artifact later; the module boundary keeps
that true. Dependency direction (Gradle-enforced, never violated): `:app → :extension-api` and
`:ext-templates → :extension-api`, `:ext-naming → :extension-api`, `:ext-mlkit → :extension-api`; `:app`
and the extension modules never depend on each other.

### `ExtensionContract`

| Constant | Value |
|---|---|
| `API_VERSION` | `1` |
| `ACTION_TEMPLATE_PROVIDER` | `"com.symmetricalpalmtree.notesprout.extension.TEMPLATE_PROVIDER"` |
| `ACTION_NOTEBOOK_NAMER` | `"com.symmetricalpalmtree.notesprout.extension.NOTEBOOK_NAMER"` (arc 2 / N1) |
| `ACTION_HANDWRITING_RECOGNIZER` | `"com.symmetricalpalmtree.notesprout.extension.HANDWRITING_RECOGNIZER"` (arc 3 / M0) |
| `META_API_VERSION` | `"com.symmetricalpalmtree.notesprout.extension.API_VERSION"` |
| `MIME_WEBP` | `"image/webp"` |
| `MAX_RENDER_BYTES` | `16 * 1024 * 1024` (16 MiB — hard cap the host enforces on a render result) |
| `STORE_MAX_KEY_CHARS` | `512` — longest `IExtensionStore` key (the empty key is rejected) |
| `STORE_MAX_VALUE_BYTES` | `256 * 1024` — largest `IExtensionStore` value |
| `STORE_MAX_KEYS` | `50_000` — most keys one extension's store may hold |
| `MAX_NAME_CHARS` | `100` — host-side cap on a notebook name / scheme text an extension returns |
| `MAX_INK_STROKES` | `2_000` — most strokes in one `recognizeInk` / `recognizePage` call (host-enforced **before** the call, re-checked by the extension) |
| `MAX_INK_POINTS` | `60_000` — most points summed over the strokes of one call (≈ 480 KB of floats, under the ~1 MB Binder buffer) |
| `MAX_PRECONTEXT_CHARS` | `20` — the host truncates `preContext` to its tail before the call |
| `MAX_RECOGNIZED_CHARS` | `20_000` — host-side cap on the text a recognize call returns (the rest is dropped) |
| `templateIdentity(pkg, id)` | `"$pkg:$id"` |
| `parseIdentity(s)` | `Pair<pkg, id>?` — splits at the **first** `:`; null if `:` absent or either side empty. The Blank sentinel is the host's, not the contract's. |

### AIDL

`extension-api/src/main/aidl/com/symmetricalpalmtree/notesprout/extension/`:

```aidl
// TemplateInfo.aidl
package com.symmetricalpalmtree.notesprout.extension;
parcelable TemplateInfo;

// RenderedTemplate.aidl
package com.symmetricalpalmtree.notesprout.extension;
parcelable RenderedTemplate;

// IExtensionStore.aidl — host-owned encrypted key/value store, scoped to the calling extension.
// Not an extension point: the host hands one in as a parameter of the calls that may need it.
interface IExtensionStore {
    /** Value for [key], or null if absent. */
    byte[] get(String key);
    /** Insert or replace. key 1..512 chars, value <= 256 KiB, <= 50 000 keys per extension. */
    void put(String key, in byte[] value);
    /** Remove [key] (no-op if absent). */
    void delete(String key);
    /** Keys starting with [prefix] ("" = all), ascending. */
    List<String> keys(String prefix);
}

// SchemeField.aidl
package com.symmetricalpalmtree.notesprout.extension;
parcelable SchemeField;

// INotebookNamer.aidl — the NOTEBOOK_NAMER point (arc 2 / N1)
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

// ITemplateProvider.aidl
interface ITemplateProvider {
    /** Templates this provider offers, in display order. Ids are stable, ASCII, unique per provider. */
    List<TemplateInfo> listTemplates();
    /** Render [templateId] at exactly widthPx x heightPx for a panel of [dpi] as a lossless WEBP.
     *  Returns null if the id is unknown. Called on a Binder thread; may take seconds on e-ink CPUs. */
    RenderedTemplate render(String templateId, int widthPx, int heightPx, float dpi);
}

// InkStroke.aidl
package com.symmetricalpalmtree.notesprout.extension;
parcelable InkStroke;

// IHandwritingRecognizer.aidl — the HANDWRITING_RECOGNIZER point (arc 3 / M0).
// Engine-neutral. Every argument is bare geometry; every result is plain text. Stateless.
interface IHandwritingRecognizer {
    /** One of RecognizerStatus.* — READY / NEEDS_DOWNLOAD / DOWNLOADING / UNAVAILABLE. Fast. */
    int status();
    /** Start acquiring what the engine needs (model download). Returns at once; poll status().
     *  A no-op while READY or already DOWNLOADING. */
    void prepare();
    /** Recognize one writing area (no layout analysis). [strokes] in the area's px space,
     *  [areaWidth]/[areaHeight] > 0, [preContext] = the text just before this ink ("" if none).
     *  Returns the top candidate ("" if none). Throws IllegalStateException if status() != READY,
     *  IllegalArgumentException over the MAX_INK_* caps. */
    String recognizeInk(in List<InkStroke> strokes, float areaWidth, float areaHeight, String preContext);
    /** Recognize a whole page: the engine finds lines / paragraphs itself and chains context.
     *  [strokes] in page px; [pageWidth]/[pageHeight] the page size. Returns lines joined by '\n',
     *  paragraphs separated by a blank line ("" if nothing recognizable). Same exceptions. */
    String recognizePage(in List<InkStroke> strokes, float pageWidth, float pageHeight);
}
```

`RecognizerStatus` is a Kotlin `object` of `Int` constants (AIDL carries `int` — no parcelable, no
enum): `READY 0` (model on device, engine constructed) · `NEEDS_DOWNLOAD 1` (call `prepare()`) ·
`DOWNLOADING 2` (a `prepare()` is in flight) · `UNAVAILABLE 3` (the engine cannot run here). The host
treats any other value as `UNAVAILABLE`.

### Parcelables (hand-written — no `kotlin-parcelize`)

- `TemplateInfo(id: String, name: String)` — `writeString(id); writeString(name)`.
- `RenderedTemplate(memory: SharedMemory, byteCount: Int, mimeType: String)` —
  `writeParcelable(memory, flags); writeInt(byteCount); writeString(mimeType)`. The bytes are a complete
  WEBP file in `memory[0 until byteCount]`. Binder transactions are capped at ~1 MB, so a page bitmap
  can never travel as a plain `byte[]`; `SharedMemory` is ashmem-backed and Parcelable. **Handshake:**
  the **extension** creates the region (`SharedMemory.create(null, byteCount)`, maps RW, writes, unmaps,
  `setProtect(PROT_READ)`); the **host** maps read-only, copies out `byteCount` bytes, unmaps, closes.
  Writing the parcelable **dups** the region's file descriptor into the reply, so the extension closes
  its own handle once the transaction is marshalled (the Templates extension does this from
  `onTransact`'s `finally` via a per-Binder-thread `ThreadLocal`); leaving it to GC leaks one
  descriptor per render until a collection.

- `SchemeField(label: String, hint: String, help: String)` — `writeString ×3` (N1). How the host draws
  a namer's one text field: caption above, grey hint inside, one help line below. Untrusted on the host
  side — truncated to 40 / 60 / 200 chars before display.

- `InkStroke(x: FloatArray, y: FloatArray)` — `writeInt(n); writeFloatArray(x); writeFloatArray(y)`
  (M0). One stroke of **bare geometry** in the caller's px space; `require(x.size == y.size &&
  x.isNotEmpty())` runs in the constructor, so a malformed stroke is rejected at unmarshal time on the
  receiving side too. Nothing else is in it — no id, no time, no pressure, no colour, no width. A
  compatible tail (e.g. a time channel) may be appended after `y` later; readers of this version stop
  after `y`.

All parcelables carry `@JvmField val CREATOR` and match their `.aidl` declarations.
`RenderedTemplate.describeContents()` returns `CONTENTS_FILE_DESCRIPTOR` (it carries the region's fd —
`Bundle.hasFileDescriptors()` relies on this); `TemplateInfo`, `SchemeField` and `InkStroke` return 0.

### `HostCallerCheck` (N1 — shared extension-side trust gate)

`HostCallerCheck.enforce(context, hostPackage)` lives in `:extension-api` so every extension (first- or
third-party) gets the belt-and-braces caller check for free: `Binder.getCallingUid()` →
`getPackagesForUid(uid)` must contain `hostPackage` **and** `checkSignatures(uid, Process.myUid()) ==
SIGNATURE_MATCH`, else `SecurityException`. Every first-party extension calls it first in **every** stub
method with `BuildConfig.HOST_PACKAGE` (the per-build-type host id). It replaced the Templates
extension's private `CallerCheck` (the one permitted N1 touch to `:ext-templates`); it uses only
`android.*` — the library still depends on nothing.

### Versioning rules (for the next point / next version)

- A **new extension point** = a new action string + a new AIDL interface + the same `META_API_VERSION`
  key on its own `<service>`.
- A **compatible** change (a method appended at the end of an interface, an optional field appended to a
  parcelable's write order) keeps `API_VERSION`; the host must tolerate old extensions (catch the
  `RemoteException` from an unimplemented transaction).
- An **incompatible** change bumps `API_VERSION`; the host then accepts a *range*
  (`MIN_API_VERSION..API_VERSION`) instead of exact equality.
- **Never** reorder or remove AIDL methods or parcel fields.

---

## The Templates extension (`:ext-templates`)

An Android **application** APK — `applicationId com.symmetricalpalmtree.notesprout.ext.templates`
(debug `.dev`), `versionName 0.1.0`, no NDK/native libs. `BuildConfig.HOST_PACKAGE` is
`com.symmetricalpalmtree.notesprout.dev` in debug and `com.symmetricalpalmtree.notesprout` in release
(the dev extension serves the dev core; release serves release). Dependencies: `:extension-api` +
`androidx.core:core-ktx`.

- **Label:** "NSE · Templates" (debug: "NSE · Templates Dev"). **Icon:** the Tabler `puzzle` outline,
  black on white (`drawable/ic_launcher_foreground.xml`; adaptive icon, white background) — per the
  convention above.
- **Manifest:** `android:allowBackup="false"`, **no Activity**, one exported `<service>`:
  ```xml
  <service android:name=".TemplateProviderService" android:exported="true">
      <intent-filter>
          <action android:name="com.symmetricalpalmtree.notesprout.extension.TEMPLATE_PROVIDER" />
      </intent-filter>
      <meta-data android:name="com.symmetricalpalmtree.notesprout.extension.API_VERSION" android:value="1" />
  </service>
  ```
- **`TemplateProviderService`** returns an `ITemplateProvider.Stub`. **Every** stub method first calls
  `HostCallerCheck.enforce(context, BuildConfig.HOST_PACKAGE)` (§`HostCallerCheck`): the caller uid must
  map to the host package **and** share this extension's signature, else `SecurityException`. `render` renders into a `SharedMemory` per the handshake above and parks the
  region in a `ThreadLocal`; the stub's `onTransact` override closes it in `finally`, after the reply
  (holding a dup of the descriptor) has been written. Binder threads call in — the stub holds no other
  mutable state.
- **`TemplateRenderer`** — the v0 core `BuiltInTemplates` moved **verbatim** (same geometry: 8 mm
  spacing at device dpi, mdpi-authored 1 px rule / 2 px dot radius scaled by dpi, LINED top margin
  2×spacing, symmetric GRID origin at 1×spacing) + the WEBP encode (`WEBP_LOSSLESS`, quality 100 —
  `WEBP_LOSSLESS` exists from API 30, so on API 29 the legacy `WEBP` at quality 100 is used, which is
  lossless too). The catalogue is one `enum Kind(id, nameRes)`: `lined` "Lined", `dotted` "Dotted",
  `grid` "Grid" (ids ASCII lower-case; names from `strings.xml`) — id list, lookup, and names all derive
  from `Kind.entries`, so a template cannot be half-registered. **Blank is not a template** — it is the host's "no template" option.

---

## The extension store (arc 2 / N0 — `:app` `data/extstore/`)

**Where an extension keeps its own data.** The core owns **one encrypted key/value database per
extension package** and lends the extension a small binder over it; the extension never sees a key,
a passphrase, a path, or a `File`, and cannot open anything itself.

- **File:** `extensionStoreFile(ctx, pkg)` = `Garden/<ext package>.db` (`data/SoilFile.kt`, the only
  path constructor; `pkg` is the *installed* package name from discovery, guarded
  `[a-zA-Z0-9_.]+`). So the `.dev` and release builds of an extension get separate stores.
- **Encryption:** SQLCipher under the **global** passphrase (`KeySession.get()` — process RAM, set once
  the index is open; every caller is behind `IndexGuard`). Raw-key cache file id `ext:<pkg>`. Same
  passphrase / raw-key / lockout / "forget key" machinery as the index and every `.soil`.
- **Open-or-create — `ExtensionStores.open(ctx, pkg)`** (IO, `@Synchronized`, process-lifetime cache
  keyed by package, closed only by `closeAll()`). Missing / empty file → **create** exactly the
  `SoilDatabase.create` way (`SoilCrypto.roomFactory(pass)` → force-open → `KeyOpener.warm`) — the
  **third named create entry point** (`docs/crypto.md` audit item 2). Existing file →
  `SoilCrypto.requireExisting` → `KeyOpener.roomFactoryFor` (cached raw key verified against the file,
  passphrase fallback + warm) — the `SoilDatabase.open` way. Every factory is
  `NonDestructiveOpenHelperFactory`-wrapped by `SoilCrypto`. No global key in session →
  `SoilLockedException`.
- **Schema:** `ExtensionStoreDatabase` (Room v1, WAL, `busy_timeout=5000`):
  `kv(key TEXT PRIMARY KEY, value BLOB NOT NULL, updatedAt INTEGER NOT NULL)`. The extension
  serialises whatever it wants into `value`. No namespaces, no extension-defined SQL.
- **Handoff:** `IExtensionStore` is passed as an **in-parameter** on each AIDL call that may need it.
  The host mints one `ExtensionStoreBinder(db, extUid)` per bind (`extUid` =
  `PackageManager.getPackageUid(pkg)` at bind time) and **`revoke()`s it in the same `finally` as the
  unbind**. Every method first checks `Binder.getCallingUid() == extUid && !revoked`, else
  `SecurityException` — the binder was handed to exactly one process and is dead after the bind.
  Stateless extension side; no reverse discovery, no exported host service.
- **Caps (host-enforced, `ExtensionContract.STORE_*`):** key `1..512` chars, value `≤ 256 KiB`, a
  `put` of a *new* key when the store already holds `50 000` → `IllegalStateException`; bad
  arguments → `IllegalArgumentException`; a DAO failure (SQLite full / locked / I/O) is rethrown as
  `IllegalStateException` (N2 — an exception Binder cannot marshal would fail the transaction
  *silently*: the extension would read an empty reply and believe its `put` succeeded). All are in
  the set Binder carries intact; the extension treats **any** exception as "store unavailable".
  `keys(prefix)` is an exact, case-sensitive "starts with" (`substr(key,1,length(prefix)) = prefix`
  — not `LIKE`, which is ASCII-case-insensitive; N2) returning ascending. Methods run synchronously
  on the host's Binder thread over the blocking DAO — never Main. (`ExtensionStoreGate` holds the
  checks with no Android types so they are JVM-tested; the `Stub` delegates to it.)
- **Pre-open rule:** the host opens the store on IO **before** binding the extension for any call that
  carries one, so a cold open (KDF ≈ 0.5–1.5 s on e-ink when the raw key isn't cached) is never
  inside the extension call's 2 s timeout window.
- **Lifetime:** the `.db` **survives** the extension's uninstall / disable — removing an extension's
  data is Extensions-UI territory. Backup / restore / compaction: none in Paper (no backup subsystem).
  Debug "Forget cached key" clears `ext:*` raw keys with everything else.
- **Debug probe:** library ⋯ → "Extension store self-test" (`DebugMenu`, debug builds only) —
  open-or-create `probe.test`, round-trip through a real `ExtensionStoreBinder`, verify the encrypted
  header, wrong-uid and revoked refusal; toast OK / FAIL.

## The Naming extension (`:ext-naming` — arc 2 / N1)

The second first-party extension and the first that **holds data**. It gives a folder a *naming
scheme*; a notebook created in that folder is pre-named by it. Folders without a scheme, the library
root, and every device without the extension keep the core default (`yyyyMMdd_HHmmss`) — the core's
behaviour without the extension is unchanged.

- **APK:** `applicationId com.symmetricalpalmtree.notesprout.ext.naming` (debug `.dev`), `versionName
  0.1.0`, Gradle/manifest shape identical to `:ext-templates` (no Activity, `allowBackup="false"`,
  `BuildConfig.HOST_PACKAGE` per build type, deps `:extension-api` only). Label **"NSE · Naming"**
  (debug "NSE · Naming Dev"), puzzle icon. One exported `<service android:name=".NotebookNamerService">`
  with the `NOTEBOOK_NAMER` action + `API_VERSION` meta-data `1`.
- **`NotebookNamerService`** returns an `INotebookNamer.Stub`; every method first calls
  `HostCallerCheck.enforce`. Holds no state. **Store key:** `folder:<folder UUID>` → UTF-8 scheme text;
  `saveScheme` with a blank scheme deletes the key. Any store failure is rethrown as
  `IllegalStateException` (an exception Binder carries intact) so the host sees a clean
  `ExtensionCallException` instead of a dead extension process. A stored scheme this version can't parse
  makes `defaultName` return null (host default) rather than throw.
- **Scheme language v1 (`SchemeEngine`, pure Kotlin, 18 JVM tests):** literal text + `{date}`
  (`yyyyMMdd`) + `{time}` (`HHmmss`) + `{n}` / `{n:K}` (K = 1–9; at most once). Literal text must satisfy
  the core's name rule (`[a-zA-Z0-9_\-. ]`); a literal-only scheme may not be `.`/`..`/blank; the whole
  scheme ≤ 100 chars; unknown `{…}`, a stray `}`, or an unclosed `{` are errors. Errors are enum codes
  the service maps to `strings.xml` (`err_*`), returned to the host verbatim for its toast.
  **`{n}` = 1 + the highest number among sibling names that match the scheme's skeleton** — an
  anchored regex where literals are quoted, `{date}`/`{time}` are **wildcards** (`\d{8}` / `\d{6}` —
  the counter runs across days: `Meeting {date} {n:2}` → 01, 02 today, 03 tomorrow) and the counter is
  `(\d{1,9})`; zero-padded to K, never truncated (`{n:2}` after 99 → `100`).
- **Field wording** (`SchemeField` from `strings.xml`): label "Default notebook name" · hint
  "e.g. Meeting {date} {n:2}" · help "Tokens: {date} {time} {n} {n:3}. Leave empty for the standard name."

## HandwritingRecognizer (contract — arc 3 / M0)

The third point and the first **capability point**: an extension point whose implementation the core
binds itself and — in a later arc — *lends* to other extensions through a per-bind, uid-bound,
revocable proxy implementing the same `IHandwritingRecognizer` interface (the `IExtensionStore`
pattern; see `PAPER_RECOGNITION_PLAN.md` §"The capability pattern"). Extensions never bind each other.
Paper's core is "paper with strokes" and never learns what handwriting *says*: recognition results
are not stored anywhere by the core (no `page_text`, no `.soil` / index change).

- **Action** `ACTION_HANDWRITING_RECOGNIZER`; interface `IHandwritingRecognizer`; parcelable
  `InkStroke`; status constants `RecognizerStatus`. Engine-neutral — a TrOCR or Onyx-firmware
  extension can implement it later.
- **Outward payload — the recorded widening of boundary-audit row 3 for this point only:** per stroke
  the x/y point arrays (px), plus the writing-area or page size, plus ≤ `MAX_PRECONTEXT_CHARS` of
  pre-context. **Never** stroke ids, notebook/page ids, names, colour, width, style, pressure, tilt,
  timestamps, keys or paths. This is the first point that sends ink to an extension.
- **Protocol (as amended in M1):** `status()` is fast and **never waits on the engine**
  (`DOWNLOADING` covers everything in flight — checking, downloading, loading); `NEEDS_DOWNLOAD` →
  `prepare()` (returns at once; idempotent, a no-op while `READY` / `DOWNLOADING`). `recognize*` may be
  called as soon as the extension exists: **if not READY it waits for readiness within the caller's
  timeout** and throws `IllegalStateException` only if it cannot become ready in time or the engine
  fails (so a real failure is never mistaken for a blank page). `UNAVAILABLE` means "don't bother".
  *Why the amendment:* M0 had `status()` block ≤ 1.5 s on ML Kit's `isModelDownloaded` and report a
  timeout as `UNAVAILABLE`; on the Nomad the first such check in a fresh process took ~75 s, so the
  first taps said "didn't respond" / "unavailable" for a model that was one 6 s download away. The
  original app avoids the whole ladder by running check → download → build once at startup, async;
  the extension now does the same on its first bind, and the recognize calls join that chain.
- **Two calls:** `recognizePage(strokes, pageW, pageH)` — the extension segments the page into lines
  and paragraphs itself and chains pre-context line to line (the core gains no HWR/layout knowledge);
  `recognizeInk(strokes, areaW, areaH, preContext)` — one writing area, no segmentation (the primitive a
  future consumer wants for a lasso'd selection or a heading). Both return plain text (`""` allowed).
- **Caps** (`MAX_INK_STROKES` 2 000 · `MAX_INK_POINTS` 60 000): a plain-parcel transport (`List<InkStroke>`
  in the transaction) — enforced **host-side before the call** (no bind over the cap) and **re-checked
  extension-side** (`IllegalArgumentException`). A `SharedMemory` transport is a later compatible
  change if a real page ever hits it.
- **Everything inward is untrusted:** status outside `0..3` → `UNAVAILABLE`; text `?: ""` and truncated
  to `MAX_RECOGNIZED_CHARS`.
- **Timeouts (host, M1):** bind ≤ 3 s · `status`/`prepare` ≤ 2 s · `recognizeInk` ≤ 10 s ·
  `recognizePage` ≤ 30 s.
- **No store:** the point is stateless (`en-US` only in v1; language selection is a later arc).
- **Logging rule (both sides):** recognized text is never logged — counts and durations only.

## The ML Kit extension (`:ext-mlkit` — arc 3 / M0)

The first implementation of `HANDWRITING_RECOGNIZER`, using **Google ML Kit Digital Ink Recognition**
(`com.google.mlkit:digital-ink-recognition:19.0.0` — the same artifact + version the original Notesprout
ships; on-device, no Play Services required). **The dependency lives in `:ext-mlkit` only** — never
`:app`, never `:extension-api`.

- **APK:** `applicationId com.symmetricalpalmtree.notesprout.ext.mlkit` (debug `.dev`), `versionName
  0.1.0`, Gradle/manifest shape identical to `:ext-naming` (no Activity, `allowBackup="false"`,
  `BuildConfig.HOST_PACKAGE` per build type) plus the ML Kit dependency. Label **"NSE · ML Kit"** (debug
  "NSE · ML Kit Dev"), puzzle icon. One exported `<service android:name=".HandwritingRecognizerService">`
  with the `HANDWRITING_RECOGNIZER` action + `API_VERSION` meta-data `1`.
- **`HandwritingRecognizerService`** returns an `IHandwritingRecognizer.Stub`; every method first calls
  `HostCallerCheck.enforce`. `status()`/`prepare()` delegate to `ModelManager`; `recognizeInk` /
  `recognizePage` re-check the `MAX_INK_*` caps + positive sizes (`IllegalArgumentException`), then
  **wait for readiness** (`ModelManager.awaitReady` — 6 s for ink, 22 s for page, sized under the
  host's 10 s / 30 s; not ready by then → `IllegalStateException("recognizer not ready")`), then run
  `MlKitEngine` **synchronously on the Binder thread**
  (`Tasks.await`; the host's timeout is the ceiling, ML Kit's executor does the work; each ML Kit call
  is additionally bounded to 10 s so a wedged engine can't pin a Binder thread). Any engine failure →
  `IllegalStateException` — only Binder-marshalable exceptions leave the stub (arc-2 lesson).
- **`ModelManager`** (process-lifetime `object`) owns the `en-US` `DigitalInkRecognitionModel` and the
  client, and **one async ensure-ready chain**: `isModelDownloaded` → `download(model,
  DownloadConditions())` if needed (**any network**, as the original) → build the client. The chain
  is started by the service's `onCreate` (the host's first bind — the startup analogue of the
  original's `initModel()`) and by `prepare()`; it is idempotent while in flight and restartable after
  a failure (logged by class + duration). `status()` **never blocks**: `READY` (client built) ·
  `DOWNLOADING` (chain in flight) · `NEEDS_DOWNLOAD` (no chain / last chain failed) · `UNAVAILABLE`
  (no model identifier). `awaitReady(timeoutMs)` starts the chain if needed and `Tasks.await`s it —
  what the recognize calls use. **Model-present memory:** once the chain has seen the model on disk
  (present, or just downloaded) a flag is kept in the extension's own `SharedPreferences` (engine
  state — the same sandbox exception as the model); a fresh process with the flag builds the client
  at once and is READY without ML Kit's cold `isModelDownloaded` (28 s on the Nomad the first time,
  ~4.6 s later; without the flag the host would see `DOWNLOADING` and offer a download for a model
  that is already there). An engine failure on a client built from the flag alone clears the flag
  and drops the client, so the next `start()` runs the full chain (re-download if needed).
- **Where the model lives — the recorded exception to "extensions keep data in the host store":** the
  ~20 MB model is downloaded and managed by ML Kit **in the extension's own app storage**. Engine assets
  are not user data (and exceed the 256 KiB store value cap); user data still goes to the host store
  only. Uninstalling the extension removes the model; disable/enable keeps it.
- **`MlKitEngine`:** `recognizeInk` builds one `Ink` from the x/y arrays (`Ink.Point.create(x, y)` — no
  time channel), `WritingArea(max(w,1), max(h,1))`, `RecognitionContext(preContext = tail ≤ 20 chars)`
  → top candidate's text or `""`. `recognizePage` = `StrokeSegmenter.segment(strokes)` → for each
  paragraph, for each line: `recognizeInk(line.strokes, line.bounds.width, layout.medianLineHeight
  (fallback: the line's own height), preContext = the previous recognized line)` → trimmed; a line that
  recognizes to `""` contributes nothing (no "unrecognized" placeholder); lines joined by `\n`,
  paragraphs by a blank line (`PageText.join`, pure + tested).
- **`StrokeSegmenter`** (pure Kotlin, 8 JVM tests) — a **verbatim port** of the original
  `recognition/StrokeSegmenter` (`RectF` → the tiny pure `Box`, `LiveStroke` → `InkStroke`): vertical
  projection profile → writing bands; each stroke to its (nearest) band; lines ordered left→right;
  a ≤ 3-stroke fragment with > 0.4 vertical overlap folds into its neighbour; a blank gap > 0.9 × the
  median line height starts a new paragraph. Constants unchanged: `PARA_GAP_FRAC 0.9` ·
  `BAND_COVERAGE_FRAC 0.15` · `FRAGMENT_MAX_STROKES 3` · `MERGE_OVERLAP_FRAC 0.4`. The only
  differences from the original file: no logging (pure), and the AABB is computed once per stroke
  (`Box.of`) since `InkStroke` carries no precomputed box.

## Host behaviour (`:app`, package `extension/`)

- **Manifest:** `<queries><intent><action android:name="…TEMPLATE_PROVIDER"/></intent></queries>` as a
  child of `<manifest>` — without it the discovery query is empty on API 30+.
- **`ExtensionRegistry.templateProviders(context)`** (IO): `queryIntentServices(Intent(action),
  GET_META_DATA)`; keeps a candidate only if `serviceInfo.exported`, `metaData[META_API_VERSION] ==
  API_VERSION`, and `checkSignatures(core, ext) == SIGNATURE_MATCH`; each rejection is a `Slog.d`
  (tag `ExtensionRegistry`). Callers treat discovery itself as fallible (a `PackageManager` failure is
  logged and means "no providers", never a crash). Returns `ProviderRef(component, packageName, label, apiVersion)` sorted by
  label then package. Disabled packages are not returned by the query.
- **`ExtensionBinder.call(appContext, ref, action, tag, asInterface, callTimeoutMs, bindTimeoutMs = 3 s, block)`**
  (M1 — **the one bind path**, extracted verbatim from the two verified client bodies; all three
  clients use it and none keeps a private copy): explicit intent (`action` + `component`),
  **`checkSignatures` re-run immediately before the bind**, `bindService(BIND_AUTO_CREATE)` on the
  **application** context, await `onServiceConnected` ≤ `bindTimeoutMs`, `asInterface(binder)` (null →
  failure), run `block` on IO under `withTimeout(callTimeoutMs)` in a supervisor scope, **unbind in
  `finally`**; every failure → **one** `ExtensionCallException`, `CancellationException` re-thrown.
  Bind/unbind `Slog.d` lines go under the **caller's** `tag`, so per-client log tags are unchanged.
  Payload rules stay in the clients; the namer's store pre-open + per-bind `ExtensionStoreBinder` +
  revoke wrap *around* the shared call (revoke in the client's own `finally`, right after the unbind).
- **`TemplateProviderClient(context, ref)`** — bind-per-operation over `ExtensionBinder`. `call(timeoutMs, block)`: explicit
  intent (`action` + `component`), **`checkSignatures` re-run immediately before the bind** (the
  package could have been replaced under a different key while the screen holding the `ProviderRef`
  was open — trust is not a discovery-time-only property), `bindService(BIND_AUTO_CREATE)` on the
  **application** context,
  await `onServiceConnected` ≤ **3 s**, run `block` on IO under `withTimeout`, **unbind in `finally`**.
  Because a Binder transaction can't be interrupted, the call runs in a supervisor scope: on timeout the
  caller resumes with an exception while the orphaned call finishes on its own IO thread and is
  discarded. `onServiceDisconnected` / `onBindingDied` / `onNullBinding` / `bindService == false` /
  `SecurityException` / `RemoteException` / timeout / bad payload → **one** `ExtensionCallException`
  (genuine coroutine cancellation is re-thrown, not wrapped). `list()` = 2 s; `render()` = **15 s**
  (e-ink CPUs; the lossless WEBP encode is the slow part) — copies out of the `SharedMemory` after
  checking `mimeType == MIME_WEBP` and `0 < byteCount ≤ min(MAX_RENDER_BYTES, memory.size)`, unmaps and
  closes the region on every path, then requires the bytes to decode (`Bitmaps.imageSize`, header-only
  probe) to an image of **exactly** the requested `widthPx × heightPx` — an undecodable or wrong-size
  payload is a failed render, never stored (a wrong-size template would be stretched onto every page
  forever); returns null only when the extension returned null. `list()` drops null elements (AIDL
  lists may carry them). Every bind /
  unbind is a `Slog.d` (tag `TemplateProviderClient`) — one pair per list and per render, no
  `leaked ServiceConnection` (verified SNN + NA5C).
- **`TemplateChoice(provider, id, name)`** with `identity` = `templateIdentity(provider.packageName, id)`.
  **Accepted:** the identity uses the *installed* package name, so the debug extension writes
  `…ext.templates.dev:lined` and a release one will write `…ext.templates:lined` — two labels for the
  same first-party template. Nothing reads these labels yet; the future template-switch consumer of
  `parseIdentity` must treat the `.dev` package as an alias of the release one (or the Extensions-UI arc
  namespaces by a stable provider id). Recorded here so it is a decision, not a surprise.
- **New-notebook screen** (`docs/library.md`): the section is `GONE` until a provider answers with at
  least one template; no extension → no section, blank notebook. Render happens **before** any file
  is created; failure = toast + stay on the screen, never a silent Blank. Payload outward: template id,
  page width/height px, dpi — nothing else.
- **Failure surface:** the core decides what the user sees; the only user-visible failure is the
  render toast on CREATE. Discovery/list failures are silent (log only).

### NotebookNamer — host behaviour (N1)

- **Manifest `<queries>`** gains the `NOTEBOOK_NAMER` action.
- **`ExtensionRegistry.notebookNamer(context): ProviderRef?`** — same discovery + trust filter as
  `templateProviders`; **the first by (label, package) is used**, any others are dropped with a `Slog.d`
  (choosing among providers is Extensions-UI territory).
- **`NamerClient(context, ref)`** — the `TemplateProviderClient` shape (explicit component, signature
  re-checked before every bind, `BIND_AUTO_CREATE` on the app context, bind ≤ 3 s, **every call ≤ 2 s**,
  supervisor scope, unbind in `finally`, every failure → `ExtensionCallException`) plus the store:
  `call(store = true)` runs `ExtensionStores.open(ctx, ref.packageName)` on IO **before** binding
  (pre-open rule), mints one `ExtensionStoreBinder(db, extUid)` (`extUid` from
  `PackageManager.getPackageUid` at bind time) and **revokes it in the same `finally` as the unbind** —
  a late call on a timed-out transaction fails closed. Methods: `describeField()` (strings truncated to
  40/60/200), `currentScheme(folderId)` (≤ `MAX_NAME_CHARS`), `validate(scheme)` (error text ≤ 200),
  `save(folderId, scheme)`, `defaultName(folderId, siblingNames)`. **Outward payload — the recorded
  widening of audit row 3:** the folder UUID (a random id, the store key) and, for `defaultName`, the
  names of the folder's existing notebooks; for `save`/`validate` the scheme text the user typed.
  Nothing else exists in the interface.
- **`LibraryActivity`** — `namerRef` is refreshed on every `onResume` (IO, a few ms; chrome doesn't
  depend on it) and, when a namer is found, **its store is pre-warmed** right there
  (`ExtensionStores.open` on IO, failure logged only) so the first +Notebook tap never pays the cold
  raw-key open (~125 ms on the Nomad) or the one-time KDF after a key wipe. All three entry points are
  **absent while `namerRef == null`**:
  - **+Notebook** in a folder: `NamerClient.defaultName(folderId, names of the listing's notebooks)`
    is resolved **before** `NewNotebookActivity` opens (no feedback — the tap takes a beat; a second
    tap during that beat is dropped); result → `EXTRA_DEFAULT_NAME`. Null / failure / root → the screen
    opens without the extra.
  - **New folder**: `describeField()` runs before the dialog shows (failure → the plain v0 dialog); the
    dialog gains caption + `EditText` + help (`SchemeDialogs.buildField`). CREATE: name validated as
    before → if the scheme is non-blank, `validate` (error, or "Naming extension didn't respond" if
    unreachable → toast + stay) → `createFolder` → `save(folder.id, scheme)`; a save failure toasts
    "Folder created — naming scheme not saved" and dismisses (the folder exists; retry from long-press).
  - **Folder long-press** → **"Default notebook name…"** (Tabler `cursor-text`, before Rename):
    `describeField()` + `currentScheme(id)` are fetched first (failure → toast, no dialog); the dialog
    (`SchemeDialogs.showSchemeDialog`, titled with the folder name) prefilled with the current scheme;
    OK → blank clears via `save(id, "")`, else `validate` (toast + stay) then `save`; unreachable →
    toast, dialog stays.
- **`NewNotebookActivity`** — `EXTRA_DEFAULT_NAME`; `acceptDefaultName(candidate)` (JVM-tested) admits
  it only if `validateName == null && length ≤ MAX_NAME_CHARS`, else the screen's own default. The
  screen stays extension-agnostic (it never binds the namer).
- **Verified (N1, SNN log, adb `input` overhead subtracted):** one bind/unbind per call, store opened
  once per process then cached; +Notebook in a scheme folder — warm: tap→bind 13 ms, call 33 ms,
  New-notebook displayed ≈ 0.26 s after the tap (same as with no extension); extension process killed:
  +≈ 380 ms (process start — the one cost that isn't ours without holding a binding across screens,
  which the design forbids); app fully cold: ≈ 0.75 s with the resume pre-warm (was 0.85 s). No
  `leaked ServiceConnection`.

### HandwritingRecognizer — host behaviour (M1)

- **Manifest `<queries>`** gains the `HANDWRITING_RECOGNIZER` action.
- **`ExtensionRegistry.handwritingRecognizer(context): ProviderRef?`** — same discovery + trust filter;
  **the first by (label, package)** is used, others dropped with a `Slog.d` (choosing an engine is
  Extensions-UI territory).
- **`RecognizerClient(context, ref)`** — over `ExtensionBinder`, stateless (no store). Timeouts:
  `status()` / `prepare()` **2 s**, `recognizeInk` **10 s**, `recognizePage` **30 s** (one ML Kit call
  per line; the first call after the extension's process start also loads the model). **Outward caps
  run before the bind** — `InkCaps.check(strokes, w, h)` (pure, JVM-tested): `strokes.size ≤
  MAX_INK_STROKES`, `Σ points ≤ MAX_INK_POINTS`, every stroke non-empty with equal x/y lengths, `w`/`h`
  `> 0` (NaN fails); violations throw **`InkTooLargeException`** (an `ExtensionCallException` subclass)
  **without binding**; `preContext` is cut to its last `MAX_PRECONTEXT_CHARS`. **Inward is untrusted**
  (`InkCaps.status` / `InkCaps.text`): a status outside `0..3` → `UNAVAILABLE`; text `?: ""` and
  truncated to `MAX_RECOGNIZED_CHARS`; the extension's `IllegalStateException` → typed
  `RecognizerNotReadyException` when its message says "not ready" (could not become ready within the
  call), else a generic `ExtensionCallException` (engine failure). Log tag `RecognizerClient`: bind/unbind,
  stroke/char counts, durations — **never text**.
- **`InkPayload.fromStrokes(List<Stroke>): List<InkStroke>`** (`notebook/`, pure, JVM-tested) — the
  **one** place page ink is reduced to bare geometry (audit row 14): x/y arrays per stroke; id, colour,
  width, style, pressure, tilt and time never leave; point-less strokes are skipped.
  `RecognizeContext(strokes, pageWidth, pageHeight)` is what the notebook screen exposes to its debug
  menu — the paper's `getStrokes()` (any thread) and the session's current page px size (the same
  values passed to `setPageSize`); no ids, no names, no session.
- **The debug surface** (`src/debug/…/notebook/NotebookDebugMenu.kt`, no-op twin in `src/release`;
  release builds have no user-visible change): `NotebookActivity` installs a ⋯ (dimen-driven,
  `bg_toolbar_button`, tooltip = "Debug tools") at the **end** of the top bar row (`topBarRow`, a
  weight-1 spacer before it) — inside `topBar`, so the existing exclusion rect covers it and
  `dispatchTouchEvent`'s chrome `releaseRender()` applies. Tap → `ExtensionRegistry.handwritingRecognizer`
  (IO, refreshed on every open) → `ActionSheetDialog` "Debug tools" with **"Recognize page (ML Kit)"
  only while a recognizer is installed** (none → the sheet opens with its title only). Flow (as
  settled with the user in M1 — **dialogs, not toasts**, for anything the user must notice; a toast
  only for a confirmation of something that already happened):
  - strokes empty → dialog *Nothing to recognize* · `InkPayload` (Default) → `status()`:
  - `READY` → **"Recognizing…"** popup (Opening-style: bordered message, no buttons, non-cancelable)
    → `recognizePage(ink, pageW, pageH)` → result `AlertDialog` titled **"Recognized text (ML Kit ·
    N strokes · T ms)"**, message = the text, selectable (`""` → *(nothing recognized)*), **Copy**
    (clip label "Recognized text", toast "copied") + **OK**.
  - `UNAVAILABLE` → dialog *Recognizer unavailable on this device*.
  - otherwise (`NEEDS_DOWNLOAD` / `DOWNLOADING`) → the **one-time model flow**: `Connectivity.isOnline`
    pre-flight (ML Kit's downloader *hangs* rather than fails offline — M1: no error after a minute) —
    offline → dialog "Recognition model needed" saying the device is offline, OK only; online → the
    same dialog offering the ~20 MB en-US download (Wi-Fi recommended) with **Download** / **Cancel**.
    Download → `prepare()` → progress dialog **"Downloading recognition model"** whose message carries
    an **elapsed-time counter** (the e-ink-safe indeterminate indicator — no spinner; refreshed every
    2 s) and **Cancel** (hides the dialog only; the download keeps running in the extension). The
    dialog polls `status()` every 2 s (a bind per poll, ~30 ms): `READY` → dismiss → the READY path
    above, no further tap · network gone → message *Waiting for a network connection…*, gives up after
    30 s offline · `NEEDS_DOWNLOAD` after `prepare()` (chain failed) / `UNAVAILABLE` / 5-min cap →
    dialog **"Download failed"**.
  - `InkTooLargeException` → dialog *Page too dense to recognize*; `RecognizerNotReadyException` (READY
    reported, then lost — extension restarted mid-flow) → dialog *Model still downloading — try again
    in a minute*; any other `ExtensionCallException` → dialog *ML Kit extension didn't respond*.
  - One `recognizeBusy` guard drops a second tap for the whole flow (dialogs included). Nothing
    recognized is stored or logged — the result dialog is the only sink.
- **Failure surface:** every dialog/toast above is the core's; the extension shows nothing. Absent
  extension → no sheet item; not ready / timeout / too dense / offline → a dialog; the page, the ink
  and the other extensions are unaffected.
- **Timings (M1):** warm `recognizePage` of one line ≈ 0.5 s (Nomad) — cold extension process:
  process start + client build ≈ 1.9 s, then the **first inference loads the model** — 4.5 s on the
  Nomad, 1.8 s on the NoteAir5C — so ≈ 6 s / 4 s tap-to-dialog cold. A future consumer that wants
  it faster must bind early (and the extension would need a warm-up inference — deferred).

**BOOX sideload trap (NA5C):** the launcher/firmware flips a freshly installed sideloaded package to
DISABLED_USER shortly *after* `install`, so a `pm enable` issued immediately can be overwritten. Enable,
wait a few seconds, then confirm with `pm list packages -d` (must not list the extension). Discovery
correctly reports 0 candidates while it is disabled.

---

## Boundary audit (rows 1–9 E2, rows 10–13 N2 — walked 2026-08-16, all ✅)

What crosses the process boundary, in which direction, and what guards it. Re-walk this table
whenever an extension point is added or a contract field changes.

| # | Invariant | Where it holds |
|---|---|---|
| 1 | **Host-side signature check on every discovery — and again at every bind.** No candidate is used unless exported, `META_API_VERSION == API_VERSION`, and `checkSignatures(core, ext) == SIGNATURE_MATCH`. Discovery is the only way a `ProviderRef` is made; every bind uses an explicit `ComponentName` from a `ProviderRef` and re-runs `checkSignatures` first (no TOCTOU window across the screen's lifetime). | `ExtensionRegistry.discover` (each rejection a `Slog.d`); `TemplateProviderClient.call` |
| 2 | **Extension-side caller check in every stub method.** `listTemplates` and `render` both call `CallerCheck.enforce` first: caller uid → packages must contain `BuildConfig.HOST_PACKAGE` **and** `checkSignatures(uid, myUid) == SIGNATURE_MATCH`, else `SecurityException`. The service has no other entry point (`onBind` only returns the stub). | `TemplateProviderService`, `CallerCheck` |
| 3 | **Nothing but geometry crosses outward.** `listTemplates()` carries no arguments; `render` carries a template id the extension itself issued, `widthPx`, `heightPx`, `dpi`. No passphrase, key, file path, index row, notebook id, page id, name, or stroke ever reaches the extension — the client API has no parameter that could carry one. | `ITemplateProvider.aidl`, `TemplateProviderClient.list/render`, `NewNotebookActivity.attemptCreate` (`pageWidthPx/pageHeightPx/dpi` only) |
| 4 | **Nothing but WEBP bytes (and id/name strings) crosses inward.** `TemplateInfo(id, name)` is used only to build radios and the `<pkg>:<id>` label; `RenderedTemplate` is reduced to a `ByteArray` in `copyOut` — the `SharedMemory` never leaves the client. | `TemplateProviderClient.copyOut`, `TemplateChoice` |
| 5 | **Byte cap + bounded decode on the way in.** `mimeType == MIME_WEBP`, `0 < byteCount ≤ min(MAX_RENDER_BYTES, memory.size)`, then `Bitmaps.imageSize` (header probe) must equal the requested `widthPx × heightPx` before the bytes are handed to the caller. The stored blob is later decoded only through `Bitmaps.decodeBounded(…, MAX_TEMPLATE_EDGE)` at open. | `TemplateProviderClient.copyOut`, `NotebookSession.loadTemplateFor` |
| 6 | **Every bind has an unbind in `finally`; the region is always closed.** `call()` unbinds in `finally` on every path (bind refused, bind timeout, call timeout, exception, success); `copyOut` unmaps and closes in nested `finally`. Extension side: the region is closed in `onTransact`'s `finally` after the reply is written. Verified on device: one bind/unbind pair per list and per render, no `leaked ServiceConnection`. | `TemplateProviderClient.call/copyOut`, `TemplateProviderService.onTransact` |
| 7 | **Every call has a timeout.** Bind ≤ 3 s, list ≤ 2 s, render ≤ 15 s; an un-interruptible Binder call that outlives its timeout finishes on its own supervisor-scope IO thread and is discarded — the caller never hangs. | `TemplateProviderClient` constants + `call()` |
| 8 | **Failure never creates a notebook silently different from what the user chose.** Render runs **before** any file exists; a failed / null / empty / undecodable / wrong-size render → toast + stay on the screen; Blank is only ever the user's own selection. No extension → no Template section, and the notebook created is the Blank the user saw. A recreated screen (keyboard attach on Ratta, locale) saves the chosen identity and re-checks that radio once discovery rebuilds the list — Blank is re-checked only if nothing was chosen or the template is no longer offered. | `NewNotebookActivity.attemptCreate` / `onSaveInstanceState`, `docs/library.md` §New notebook |
| 9 | **The core has no renderer and no dependency on the extension.** `:app` depends on `:extension-api` only; the template WEBP is drawn from the `.soil` blob exactly as v0 drew it, so a notebook opens with its template whether or not the extension is installed. | `app/build.gradle.kts`, `NotebookSession.loadTemplateFor` |
| 10 | **Outward payload of NotebookNamer is exactly folder UUID + sibling notebook names (+ the scheme text the user typed).** `describeField()` and `validateScheme(scheme)` carry nothing else; `currentScheme` / `saveScheme` / `defaultName` carry the folder UUID (a random id — the store key, no content) and `defaultName` alone adds the names of the folder's own notebooks (needed only for `{n}`). No other argument exists in `INotebookNamer` — no passphrase, key, path, index row, other folder, page or stroke can be carried. This is the **recorded widening of row 3** for this point only. | `INotebookNamer.aidl`, `NamerClient` (five methods), `LibraryActivity.launchNewNotebook` (`siblings` = the current listing's `CardItem.Notebook` names) |
| 11 | **The store binder is uid-bound, per-bind, revocable, capped.** Minted only inside `NamerClient.call(store = true)` — after `ExtensionStores.open` on IO (pre-open rule) and with `extUid = getPackageUid(ref.packageName)` fetched at bind time; `ExtensionStoreGate.check()` requires `getCallingUid() == extUid && !revoked` on **every** method; `revoke()` runs in the same `finally` as the unbind, so a late call from an orphaned (timed-out) transaction fails closed. Caps host-side: key `1..512` chars, value `≤ 256 KiB`, new key at `≥ 50 000` → `IllegalStateException`. The DB is opened only through `SoilCrypto` factories under the global key (`ExtensionStores.open`, the third named create entry point); `IExtensionStore` has no method that could return a key, path, or `File`. | `NamerClient.call`, `ExtensionStoreBinder`, `ExtensionStoreGate` (JVM-tested: uid mismatch, revoked, caps, literal case-sensitive prefix, DAO failure → `IllegalStateException`), `ExtensionStores.open`, `IExtensionStore.aidl` |
| 12 | **Inward payload is validated.** `SchemeField` strings are truncated (`40 / 60 / 200`) and drawn only as a caption, an `EditText` hint and a help line; a `currentScheme` is capped at `MAX_NAME_CHARS` and shown verbatim only **inside** a text field; a validation error is truncated (`200`) and shown only as a toast; a `defaultName` is accepted only if `NewNotebookActivity.acceptDefaultName` says so (core name rule **and** `≤ MAX_NAME_CHARS`) — else the core default, silently (`Slog.d`, never a toast, never a crash). Any exception, timeout, or null on the way in becomes `ExtensionCallException` at the client and a core-owned outcome at the entry point. | `NamerClient` (`MAX_LABEL/HINT/HELP/ERROR`, `take(MAX_NAME_CHARS)`), `SchemeDialogs.buildField`, `NewNotebookActivity.acceptDefaultName` |
| 13 | **Failure never changes what the user chose.** +Notebook: namer failure / null / timeout / root → `NewNotebookActivity` opens with the core default (the tap just takes a beat; a second tap during it is dropped). New folder: `describeField` failure → the dialog without the field; the scheme is validated **before** the folder exists (error → toast + stay); the folder is created **before** its scheme is saved and a save failure says so (`naming_save_failed`) while the folder stands. Long-press: fetch failure → `naming_unavailable` toast and no dialog; save/validate failure inside → toast, dialog stays with the text. The extension absent / disabled → all three entry points vanish (`namerRef == null`) and nothing else changes; its store `.db` survives so the schemes return with it. | `LibraryActivity.launchNewNotebook` / `showNewFolderDialog` (both overloads) / `openSchemeDialog`, `SchemeDialogs.showSchemeDialog`, `LibraryActivity.refreshNamer` |

## Rules for adding a future extension point (write-once, follow later)

1. Add the action + AIDL + parcelables to `:extension-api`; keep the dependency direction.
2. Add discovery to `ExtensionRegistry` (same trust filter) and a client class with explicit timeouts,
   bind-per-operation, unbind-in-finally, and untrusted-payload caps.
3. The core decides what the user sees on failure; extensions never show UI in the core's flow.
4. Document the point here (contract + host behaviour + failure behaviour) and add its rows to the
   boundary audit.
5. Nothing crosses the boundary that the call doesn't need — never keys, files, index rows.

Followed by NotebookNamer (N1): `NOTEBOOK_NAMER` + `INotebookNamer` + `SchemeField` in
`:extension-api`; `ExtensionRegistry.notebookNamer` + `NamerClient`; the core owns every toast; the
one recorded widening of rule 5 is folder UUID + sibling notebook names; audit rows 10–13 (N2).

### Adding a data-holding point (arc 2 pattern)

A point whose extension must remember something between calls follows the five rules above **plus**:

6. **The extension keeps its data in the host-owned store, never in its own files.** Every AIDL method
   that may need it takes `IExtensionStore store` as an **in-parameter** — there is no reverse
   discovery, no exported host service, no store handle kept across calls.
7. **The client opens the store on IO before it binds** (`ExtensionStores.open(ctx, ref.packageName)`
   — a cold KDF must never sit inside the call timeout), mints **one** `ExtensionStoreBinder(db,
   extUid)` for that bind (`extUid` from `PackageManager.getPackageUid` at bind time), hands it to the
   call, and **revokes it in the same `finally` as the unbind**. Copy `NamerClient.call`.
8. **Only host-fixed data crosses outward** — the identity the store row hangs off (a UUID) and the
   minimum the call needs; record any widening of rule 5 in the audit, as row 10 does.
9. **Everything the extension returns from its store is untrusted on the way back** — cap, validate,
   and fall back to the core's own behaviour silently (row 12).
10. **A failure must leave the user's own choice intact** — create the core object first, save the
    extension's data second, and say so when the second step fails (row 13).
11. **Pre-warm at library resume** if the point is on a hot path (`LibraryActivity.refreshNamer` opens
    the namer's store on IO once the ref is known) — deferred: generalise to every discovered
    extension when Templates gains a store (`PAPER_NAMING_PLAN.md` §Deferred).

## Writing an extension

What a third party will do once `:extension-api` is published (today: the same steps, with the module
consumed in-project — see `:ext-templates` for the reference implementation).

1. **Depend on the contract:** `implementation("com.symmetricalpalmtree.notesprout:extension-api:<v>")`
   (in-project: `implementation(project(":extension-api"))`). Nothing else from Paper.
2. **Be an app with no launcher Activity.** `com.android.application`, your own `applicationId`, an
   `<application>` with a label (`NSE · <Name>` — that label is what a future Extensions UI shows) and
   the puzzle icon (see the naming + icon convention), **no Activity**, `allowBackup="false"`.
3. **Declare one exported `<service>` per extension point** with the point's action in its
   intent-filter and the API version as meta-data:
   ```xml
   <service android:name=".TemplateProviderService" android:exported="true">
       <intent-filter>
           <action android:name="com.symmetricalpalmtree.notesprout.extension.TEMPLATE_PROVIDER" />
       </intent-filter>
       <meta-data android:name="com.symmetricalpalmtree.notesprout.extension.API_VERSION" android:value="1" />
   </service>
   ```
4. **Implement the stub.** `onBind` returns an `ITemplateProvider.Stub`. Methods run on Binder threads
   — hold no mutable state, or synchronise. `listTemplates()` returns stable ASCII ids (unique within
   your package) with display names; `render(id, w, h, dpi)` returns a complete lossless WEBP of exactly
   `w × h` in a `SharedMemory` (`create` → map RW → write → unmap → `setProtect(PROT_READ)`), or null
   for an unknown id. The host rejects a payload whose decoded size differs from the request. Close
   your handle after the reply is written (`onTransact` `finally`, as `:ext-templates` does). Keep a
   render under 15 s on an e-ink CPU. Note `WEBP_LOSSLESS` is API 30+ — on API 29 use `WEBP` at
   quality 100 (lossless).
5. **Check the caller in every method** — call `HostCallerCheck.enforce(context, hostPackage)` from
   `:extension-api` first thing in every stub method (host package = `com.symmetricalpalmtree.notesprout`,
   or `.dev` for the debug host): the caller uid must map to the host **and** share your signature,
   else it throws `SecurityException`. In API v1 the host only binds same-signature extensions, so a third-party
   extension is not yet reachable — the trust rule lifts with the Extensions-UI arc's consent step.
6. **Never** reorder or remove AIDL methods or parcel fields; follow the versioning rules above.
7. **Using the store** (points whose AIDL passes an `IExtensionStore`, e.g. `INotebookNamer`):
   - The host hands you a **fresh binder per bind**, scoped to your uid and revoked the moment the
     host unbinds — never cache it in a field, never use it from another thread after the call
     returns; do your reads/writes inside the method that received it.
   - It is a key/value store: `get(key): ByteArray?`, `put(key, value)`, `delete(key)` (no-op if
     absent), `keys(prefix): List<String>` (`""` = all, ascending). Serialise however you like — the
     Naming extension stores UTF-8 text; there is no schema, no SQL, no namespace.
   - **Caps** (`ExtensionContract.STORE_*`): key `1..512` chars, value `≤ 256 KiB`, at most `50 000`
     keys per extension. Over a cap → `IllegalArgumentException` / `IllegalStateException` from the host.
   - **Treat any exception as "store unavailable"** — `SecurityException` (revoked / wrong uid),
     `IllegalArgumentException`, `IllegalStateException`, `RemoteException`, and anything else. Catch it
     and rethrow one of the exceptions Binder carries intact (`IllegalStateException` is what
     `NotebookNamerService.storeCall` uses) so the host sees a clean failure rather than a dead
     process; never let it escape uncaught.
   - **Key naming:** prefix keys by kind (`folder:<uuid>`, `pref:<name>`, …) so `keys("folder:")`
     stays cheap and a later "remove data for X" is a prefix walk. Keys are opaque to the host.
   - Your data is encrypted at rest under the user's global key, lives in the **host's** files dir,
     and **survives your uninstall** — the user (via a future Extensions UI), not you, decides when it
     is removed. Debug (`.dev`) and release builds of your extension get separate stores.

---

## Build & install

```sh
cd ~/git/Notesprout/apps/notesprout_paper
./gradlew assembleDebug                       # all modules
./gradlew testDebugUnitTest                   # all modules
adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <serial> install -r ext-templates/build/outputs/apk/debug/ext-templates-debug.apk
adb -s <serial> install -r ext-naming/build/outputs/apk/debug/ext-naming-debug.apk
adb -s <serial> install -r ext-mlkit/build/outputs/apk/debug/ext-mlkit-debug.apk        # ML Kit model downloads on first prepare() (Wi-Fi once per device)
adb -s <serial> shell pm enable com.symmetricalpalmtree.notesprout.ext.templates.dev          # BOOX sideload trap
adb -s <serial> shell pm enable com.symmetricalpalmtree.notesprout.ext.naming.dev
adb -s <serial> shell pm enable com.symmetricalpalmtree.notesprout.ext.mlkit.dev
adb -s <serial> shell pm disable-user --user 0 com.symmetricalpalmtree.notesprout.ext.templates.dev  # simulate "not installed"
adb -s <serial> uninstall com.symmetricalpalmtree.notesprout.ext.templates.dev
```

All four APKs are signed by the same debug keystore (`~/.android/debug.keystore`) — that is what satisfies
the same-signature trust rule in dev. An extension built on another machine will **not** be trusted by
this Mac's core build (different debug key) — expected, not a bug.

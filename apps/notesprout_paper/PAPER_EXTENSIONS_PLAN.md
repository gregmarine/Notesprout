# Paper — Extensions (arc 1: the extension API + the Templates extension)

> **This file is the project's memory across sessions for the extensions arc.** Context is cleared
> between phases. Everything a fresh session needs — decisions, non-goals, the contract, per-phase
> tasks, tests, status — is here or in the files this document points at. If it isn't written down
> here (or in the repo / project memory), it doesn't exist. **Read this file top to bottom at the
> start of every session**, after `PAPER_PLAN.md` (v0, complete — it holds the architecture this arc
> builds on) and both `CLAUDE.md` files.

## Why

Notesprout's original design baked too many features into the core. Paper's core is **paper with
strokes** — a library of notebooks, each a stack of pages you write on. Everything else will be added
by **extensions**, opt-in and removable. This arc builds the first extension and, with it, the first
version of the extension API. The first extension is the **Templates** extension: it offers a set of
generated base templates (Lined / Dotted / Grid) on the New-notebook screen and renders the chosen one
into the WEBP image the `.soil` already stores. **Templates remain a core concept of the `.soil`**
(a notebook shared with someone who lacks the extension still shows its template — the core reads and
draws the stored WEBP exactly as it does today). Only *how templates are offered and generated* moves
out of the core.

There is no Extensions UI yet: extensions are installed and removed by hand (`adb install` /
`adb uninstall`, or Settings → Apps → Uninstall). An Extensions UI is a later arc.

---

## Working protocol

Each phase runs in a **fresh session** and follows the same ritual as `PAPER_PLAN.md`:

1. **Phase start (no-assumption QA):** read this file (all of it), `PAPER_PLAN.md` (Architecture +
   Locked decisions + Appendices), the root `CLAUDE.md`, and `apps/notesprout_paper/CLAUDE.md`. Confirm
   the next `⬜` phase with the user, flip it to `🔄`, then **ask the phase's "Questions to resolve at
   phase start"** one at a time in the wizard (option-select) format before writing code — the user
   prefers questions presented one at a time with selectable options plus "Other". Every question
   carries a recommended default so the user can answer "defaults". Do not assume answers. If a new
   ambiguity surfaces mid-phase that would materially change the work, stop and ask.
2. **Code** — coding runs in auto mode. Work inline; be frugal with background agents. No new Gradle
   dependency beyond `PAPER_PLAN.md` Appendix B + this file's Appendix B without asking. Follow the
   deliverables **exactly as written**; do not add scope, "improve" adjacent code, or scaffold for later
   phases.
3. **Test** — JVM unit tests (`./gradlew testDebugUnitTest` at the project root runs every module),
   then build + install the debug APKs on the **test devices** and hand the user a short, numbered
   on-device checklist (this file gives each phase's checklist — copy it, don't invent). EPD pen overlays
   are invisible to screencap — the user verifies by eye and reports.
4. **Fix → test again** as needed. Only when every test passes (JVM + user-reported device checklist)
   move on.
5. **Docs / memory / CLAUDE.md** — update `apps/notesprout_paper/CLAUDE.md` (standing rules + build
   facts only), `apps/notesprout_paper/docs/*.md` (`extensions.md` is this arc's subsystem doc), this
   file's status marker + **Outcome** note, and the project memory
   (`~/.claude/projects/-Users-gregmarine-git-Notesprout/memory/project_paper_extensions.md` + its
   `MEMORY.md` index line) with the current phase status.
6. **Commit & push** on `paper`. Then the user runs `/clear`.

**Status markers:** `⬜ Not started` · `🔄 In progress` · `🧪 Awaiting device verification` ·
`✅ Complete (commit <hash>)`. Update the marker in this file **the moment the state changes** — the
file is what survives `/clear`.

**Test devices** (user verifies by eye; always pass `-s <serial>`; never install on a device the user
didn't ask for; if a device is offline, say so and wait):

| Nickname | Device | Serial | Engine |
|---|---|---|---|
| SNN | Supernote Nomad | `SN078D10012852` | `gpaper-ratta` |
| NA5C | BOOX NoteAir5C | `92c16533` | `gpaper-onyx` |
| MIP11 | Wacom Movink Pad 11 | `5HL21V5007384` | `gpaper-core` |

**Model note:** written so Opus 4.8 can execute a phase without inventing decisions. Recommendation:
Fable 5 for **Phase E1** (IPC lifecycle + trust checks + the create-flow rewrite; the most hidden
failure modes), Opus for E0 and E2. Either model must follow the phase-start question ritual.

---

## Locked decisions (planning Q&A, 2026-08-16)

| Area | Decision |
|---|---|
| Container | **An extension is a separate APK** (its own package) exposing a bound `Service`, called by the core over Android IPC (AIDL). Discovery via `PackageManager`. Rejected: in-process compiled code (runs with the core's identity → sees keys / decrypted files; unacceptable for third parties), JavaScript (new engine dependency, bridge to maintain, WebView weight on e-ink). |
| Launcher | Extensions declare **no launcher Activity** — no icon in any app drawer; they are visible only under Settings → Apps (which is also how a user removes one until the Extensions UI exists). Phase E0 verifies this on all three launchers (BOOX, Supernote, stock). |
| Location | **Modules in the Paper Gradle project:** `:extension-api` (the contract library — depends on nothing in `:app`, ever) and `:ext-templates` (the first-party extension APK), beside `:app`. Third parties will consume `:extension-api` as a published Maven artifact later — publishing is the *only* step needed then; the module boundary keeps that true. |
| Picker UI | **The core draws the picker** from data the extension returns (`listTemplates()` → id + name). Extensions never place UI in the core's flow. |
| No extension installed | The New-notebook screen **hides the Template section entirely**; notebooks are created blank. Nothing hints at templates. Core is paper. |
| Several template extensions | **Merged**: every discovered provider's templates are offered. The final presentation is **one provider per tab / "folder"** — decided and built **only when a second provider actually exists** (deferred; see Non-goals). v1 shows the (single) provider's templates as radios; if more than one provider is discovered, each provider's radios are preceded by a small heading with the extension's label. |
| Trust (API v1) | **Same-signature only**: the core uses a discovered service only if `checkSignatures(core, extension) == SIGNATURE_MATCH`. The extension, symmetrically, refuses calls from any uid that is not the host package with a matching signature. Lifting to third parties later = one condition + the consent step of the Extensions UI. |
| Template identity | The two informational label columns (index `objects.templateKind`, `.soil` template row `text`) get the **extension-namespaced id** `"<extension package>:<template id>"` (e.g. `com.symmetricalpalmtree.notesprout.ext.templates:lined`). Blank stays `"BLANK"`. No schema change; v0 notebooks keep `LINED`/`DOTTED`/`GRID`/`BLANK` untouched. Nothing reads these yet; a future template-switch feature (in the Templates extension) will. |
| API scope | **Discovery/handshake + one extension point (`TemplateProvider`)** — nothing else designed now. The rules for adding the next point are written down (below) so the next arc needs no redesign. |
| Artifacts | **Debug-only** for now (both APKs installed via adb, debug-keystore signed — the same-signature rule holds because both are signed by `~/.android/debug.keystore` on this Mac). Release signing arrives with the Extensions UI arc. |
| Version | **No version bump.** Paper is an experiment; versioning/shippability is decided later. The "v0.1 carry-over list" from `PAPER_PLAN.md` Phase 6 deliverable 5 is **dropped**. |
| Plan file | This file. `PAPER_PLAN.md` is v0's record and gains a pointer only. |

## Non-goals for this arc (do not build, do not scaffold "for later")

No Extensions UI (list / enable / disable / consent / install) · no third-party trust (no consent
flow, no allow-list, no signing infra) · no publishing of `:extension-api` to Maven · no template
switching on existing notebooks · no template previews / thumbnails in the picker · no tabs/folders
per provider · no per-page templates · no extension settings screens · no second extension point
(export, backup, objects, …) · no changes to the `.soil` schema, the index schema, `NotebookSession`,
g-paper, or how a stored template is drawn · no release signing · no version bump.

---

## Architecture

### Module layout (after this arc)

```
apps/notesprout_paper/
├── settings.gradle.kts            include(":app", ":extension-api", ":ext-templates")
├── PAPER_PLAN.md                  v0 (complete)
├── PAPER_EXTENSIONS_PLAN.md       this file
├── docs/extensions.md             the extension model + contract v1 (third-party-facing later)
├── app/                           the core (host)
│   └── src/main/kotlin/com/symmetricalpalmtree/notesprout/
│       └── extension/             ExtensionRegistry (discover + trust), TemplateProviderClient
│                                  (bind / call / unbind with timeouts), TemplateChoice (+ identity)
├── extension-api/                 Android LIBRARY, namespace com.symmetricalpalmtree.notesprout.extension
│   └── src/main/
│       ├── aidl/com/symmetricalpalmtree/notesprout/extension/{ITemplateProvider,TemplateInfo,RenderedTemplate}.aidl
│       └── kotlin/com/symmetricalpalmtree/notesprout/extension/{ExtensionContract,TemplateInfo,RenderedTemplate}.kt
└── ext-templates/                 Android APPLICATION (the extension APK), namespace/applicationId
    └── src/main/                  com.symmetricalpalmtree.notesprout.ext.templates (debug: ".dev")
        ├── AndroidManifest.xml    NO launcher activity; one exported <service> with the intent-filter + meta-data
        ├── kotlin/.../ext/templates/{TemplateProviderService,TemplateRenderer,CallerCheck}.kt
        └── res/                   strings (label, template names), mipmap (Paper's icon, copied)
```

**Dependency direction (enforced by Gradle, never violated):** `:app → :extension-api` and
`:ext-templates → :extension-api`. `:extension-api` depends on **nothing** in the project and on no
library beyond the Kotlin stdlib (+ AndroidX `annotation` if needed). `:app` never depends on
`:ext-templates` and vice-versa.

### The extension model (concepts)

- **Extension** — an installed Android package. Its user-visible name is its application label
  (`ApplicationInfo.loadLabel`) — that is what a future tab / Extensions UI shows.
- **Extension point** — a named capability the core knows how to call. Each point has: an **intent
  action** (`<service>` intent-filter), an **AIDL interface**, and a **`<meta-data>` API version** on
  the `<service>`. One `<service>` per point per extension. v1 has exactly one point:
  `TemplateProvider`.
- **Discovery** — `PackageManager.queryIntentServices(Intent(action), GET_META_DATA)` (the core
  manifest declares the action in `<queries>` — mandatory on API 30+, or the query silently returns
  nothing). Candidates are kept only if: the service is exported, its meta-data API version equals
  `ExtensionContract.API_VERSION`, and `checkSignatures(corePackage, extPackage) == SIGNATURE_MATCH`.
  Everything else is skipped with a `Slog.d`. Disabled packages/components are not returned by the
  query, so `pm disable` = uninstalled from the core's point of view.
- **Calling** — the core binds (`bindService`, `BIND_AUTO_CREATE`), waits for `onServiceConnected`
  with a timeout, runs the AIDL call(s) on `Dispatchers.IO` under a timeout, and **unbinds in
  `finally`**. The core never holds a binding across screens; it binds per operation. Failures
  (no connection, timeout, `RemoteException`, `SecurityException`, bad payload) all surface as one
  `ExtensionCallException` to the caller — the caller decides what the user sees.
- **Payload rules** — the core hands an extension only what the call needs (for templates: page
  geometry). Nothing about keys, files, the index, or notebook contents ever crosses. Data coming back
  is treated as untrusted: size-capped and bounded-decoded.
- **Threading (extension side)** — AIDL methods run on Binder threads; a service must be re-entrant
  or synchronize itself. Rendering runs on the calling Binder thread (one render per call).

### Contract v1 (`:extension-api`) — exact

`ExtensionContract` (Kotlin `object`):

| Constant | Value |
|---|---|
| `API_VERSION` | `1` |
| `ACTION_TEMPLATE_PROVIDER` | `"com.symmetricalpalmtree.notesprout.extension.TEMPLATE_PROVIDER"` |
| `META_API_VERSION` | `"com.symmetricalpalmtree.notesprout.extension.API_VERSION"` |
| `MIME_WEBP` | `"image/webp"` |
| `MAX_RENDER_BYTES` | `16 * 1024 * 1024` (16 MiB — hard cap the host enforces on a render result) |
| `templateIdentity(pkg, id)` | `"$pkg:$id"`; `parseIdentity(s)` → `Pair<pkg, id>?` (splits at the **first** `:`; null if absent/empty either side). Blank sentinel is the host's, not the contract's. |

AIDL (files under `extension-api/src/main/aidl/com/symmetricalpalmtree/notesprout/extension/`):

```aidl
// TemplateInfo.aidl
package com.symmetricalpalmtree.notesprout.extension;
parcelable TemplateInfo;

// RenderedTemplate.aidl
package com.symmetricalpalmtree.notesprout.extension;
parcelable RenderedTemplate;

// ITemplateProvider.aidl
package com.symmetricalpalmtree.notesprout.extension;
import com.symmetricalpalmtree.notesprout.extension.TemplateInfo;
import com.symmetricalpalmtree.notesprout.extension.RenderedTemplate;
interface ITemplateProvider {
    /** Templates this provider offers, in display order. Ids are stable, ASCII, unique per provider. */
    List<TemplateInfo> listTemplates();
    /** Render [templateId] at exactly widthPx × heightPx for a panel of [dpi] as a lossless WEBP.
     *  Returns null if the id is unknown. Called on a Binder thread; may take seconds on e-ink CPUs. */
    RenderedTemplate render(String templateId, int widthPx, int heightPx, float dpi);
}
```

Parcelables are **hand-written** `Parcelable` classes (no `kotlin-parcelize` plugin — keeps
`:extension-api` free of build-plugin requirements for third parties):

- `TemplateInfo(val id: String, val name: String)` — `writeString(id); writeString(name)`.
- `RenderedTemplate(val memory: SharedMemory, val byteCount: Int, val mimeType: String)` —
  `writeParcelable(memory, flags); writeInt(byteCount); writeString(mimeType)`. The bytes are a
  complete WEBP file in `memory[0 until byteCount]`. The **extension** creates the `SharedMemory`
  (`SharedMemory.create(null, byteCount)`, map RW, write, unmap, `setProtect(PROT_READ)`), the
  **host** maps read-only, copies out `byteCount` bytes, unmaps, and closes. Rationale: Binder
  transactions are capped at ~1 MB, so a page bitmap can never travel as a plain `byte[]`;
  `SharedMemory` is ashmem-backed and Parcelable.
- Both AIDL parcelables need the `.aidl` declaration files above and Kotlin classes with a
  `CREATOR` (`@JvmField val CREATOR: Parcelable.Creator<…>` in the companion).

**Versioning rules (for the next point / next version — do not implement now):** a new extension
point = a new action string + a new AIDL interface + the same `META_API_VERSION` key on its own
`<service>`. A **compatible** change to an existing interface (new method appended at the end,
new optional field appended to a parcelable's write order) keeps `API_VERSION`; the host must tolerate
old extensions (catch the `RemoteException` from an unimplemented transaction). An **incompatible**
change bumps `API_VERSION`; the host then accepts a *range* (`MIN_API_VERSION..API_VERSION`) instead of
exact equality. Never reorder or remove AIDL methods or parcel fields.

### Extension side (`:ext-templates`)

- `com.android.application`; `namespace`/`applicationId` = `com.symmetricalpalmtree.notesprout.ext.templates`,
  debug `applicationIdSuffix ".dev"`; `minSdk 29`, `compileSdk`/`targetSdk 35`; `versionCode 1`,
  `versionName "0.1.0"`; no NDK / no native libs; `buildFeatures { buildConfig = true }`;
  `buildConfigField("String", "HOST_PACKAGE", …)` = `"com.symmetricalpalmtree.notesprout"` in release
  and `"com.symmetricalpalmtree.notesprout.dev"` in debug (dev extension serves dev core, release serves
  release). Dependencies: `:extension-api`, `androidx.core:core-ktx:1.13.1` only.
- Manifest: `<application android:label="@string/ext_label" android:icon="@mipmap/ic_launcher"
  android:roundIcon="@mipmap/ic_launcher_round" android:allowBackup="false">`, **no Activity**, one
  service:
  ```xml
  <service android:name=".TemplateProviderService" android:exported="true">
      <intent-filter>
          <action android:name="com.symmetricalpalmtree.notesprout.extension.TEMPLATE_PROVIDER" />
      </intent-filter>
      <meta-data android:name="com.symmetricalpalmtree.notesprout.extension.API_VERSION" android:value="1" />
  </service>
  ```
  Label: `Notesprout Paper · Templates` (debug: `Notesprout Paper · Templates Dev`, via a debug
  `res/values/strings.xml` override). Icon: Paper's `mipmap-*/ic_launcher*` copied verbatim.
- `TemplateProviderService : Service` — `onBind` returns an `ITemplateProvider.Stub`. **Every** stub
  method first calls `CallerCheck.enforce(context)`: `Binder.getCallingUid()` → `packageManager
  .getPackagesForUid(uid)` must contain `BuildConfig.HOST_PACKAGE` **and**
  `packageManager.checkSignatures(uid, Process.myUid()) == SIGNATURE_MATCH`, else `throw
  SecurityException("caller is not the host")`. (Belt and braces with the host-side check; both are
  cheap.)
- `TemplateRenderer` — `BuiltInTemplates` moved here **verbatim** (same geometry: 8 mm spacing at
  device dpi, mdpi-authored 1 px rule / 2 px dot radius scaled by dpi, `linePositions` 2×spacing top
  margin for LINED, symmetric `gridPositionsX/Y` for GRID, `dotPositions`) plus the WEBP encode
  (`Bitmap.CompressFormat.WEBP_LOSSLESS`, quality 100 — identical to today's `bitmapToWebp`). Templates:
  `lined` "Lined", `dotted` "Dotted", `grid` "Grid" (ids ASCII lower-case; names from `strings.xml`).
  **Blank is not a template** — it is the host's "no template" option.
- `TemplateGeometryTest` moves here verbatim (`ext-templates/src/test/kotlin/...`).

### Host side (`:app`)

- Manifest gains, as a child of `<manifest>` (sibling of `<application>`):
  ```xml
  <queries>
      <intent>
          <action android:name="com.symmetricalpalmtree.notesprout.extension.TEMPLATE_PROVIDER" />
      </intent>
  </queries>
  ```
- New package `extension/`:
  - `ExtensionRegistry` (`object`): `suspend fun templateProviders(context): List<ProviderRef>` on IO —
    the discovery + trust filter above. `ProviderRef(component: ComponentName, packageName: String,
    label: CharSequence, apiVersion: Int)`. Order: by `label` then `packageName` (deterministic).
  - `TemplateProviderClient(context, ref)`: `suspend fun <T> call(timeoutMs: Long, block:
    (ITemplateProvider) -> T): T` — bind (`BIND_AUTO_CREATE`), await connection ≤ `BIND_TIMEOUT_MS`,
    run `block` on IO under `withTimeout(timeoutMs)`, unbind in `finally`; maps every failure to
    `ExtensionCallException(message, cause)`. Convenience: `suspend fun list(): List<TemplateInfo>`
    (`LIST_TIMEOUT_MS`) and `suspend fun render(id, w, h, dpi): ByteArray?` (`RENDER_TIMEOUT_MS`;
    copies out of `SharedMemory`, enforces `mimeType == MIME_WEBP` and `0 < byteCount ≤
    MAX_RENDER_BYTES`, always closes the `SharedMemory`; returns null only when the extension returned
    null). Constants: `BIND_TIMEOUT_MS = 3_000`, `LIST_TIMEOUT_MS = 2_000`, `RENDER_TIMEOUT_MS = 15_000`
    (e-ink CPUs; a full-page lossless WEBP encode is the slow part).
  - `TemplateChoice(provider: ProviderRef, id: String, name: String)` +
    `val identity get() = ExtensionContract.templateIdentity(provider.packageName, id)`.
- `NewNotebookActivity` after the change:
  - Layout: the label + `RadioGroup` are wrapped in a `LinearLayout` `@+id/templateSection`, `GONE` by
    default; the `RadioGroup` keeps **only** `radioBlank` (checked) in XML; the Lined/Dotted/Grid radios
    and their strings are **deleted**. Provider radios are inflated from a new
    `layout/item_template_radio.xml` (a single `RadioButton` with `style="@style/Widget.Notesprout.RadioButton"`,
    `match_parent × wrap_content`) so they look identical to `radioBlank`; each carries its
    `TemplateChoice` in `tag`. If more than one provider is discovered, a heading `TextView` (14sp,
    inkBlack, extension label) is inserted before that provider's radios.
  - `onCreate` launches discovery on `lifecycleScope`: `ExtensionRegistry.templateProviders` → for each
    provider `TemplateProviderClient(ref).list()` (a provider that throws is skipped with `Slog.d`,
    never a toast) → if at least one template exists, populate + `templateSection.isVisible = true`.
    Otherwise the section stays `GONE`. Selection default = Blank.
  - `attemptCreate`: selected radio's tag → `TemplateChoice?` (null = Blank). Creation order becomes:
    (1) if a choice: `client.render(choice.id, pageW, pageH, dpi)` **before** any file is created —
    on `ExtensionCallException` or a null/empty result: reset the button, `Toast`
    `R.string.new_notebook_template_failed` ("Template unavailable — try again or choose Blank"), and
    **stay on the screen** (never silently downgrade to Blank; the user chose a template). (2) then the
    existing `createNotebook` path with the WEBP bytes: template row `text = choice.identity`, page
    `refId = templateId`; index `templateKind = choice.identity`. Blank: no template row, `refId = ""`,
    `templateKind = SoilSchema.TEMPLATE_BLANK` (`"BLANK"`, new constant; same literal v0 wrote).
  - `data/template/BuiltInTemplates.kt`, `TemplateKind`, `TemplateGeometryTest`, and `bitmapToWebp`
    are **deleted from `:app`** (they now live in `:ext-templates`). No other core file changes:
    `NotebookSession`, `SoilSchema`, `SoilObjectEntity`, `IndexRepository.createNotebook` signature
    (`templateKind: String`) all stay as they are.
- `IndexGuard`, `TopGuard`, design system, portrait lock — unchanged; the screen keeps its layout,
  only the radio list is dynamic.

### Rules for adding a future extension point (write-once, follow later)

1. Add the action + AIDL + parcelables to `:extension-api`; keep the dependency direction.
2. Add discovery to `ExtensionRegistry` (same trust filter) and a client class with explicit timeouts,
   bind-per-operation, unbind-in-finally, and untrusted-payload caps.
3. The core decides what the user sees on failure; extensions never show UI in the core's flow.
4. Document the point in `docs/extensions.md` (contract + host behaviour + failure behaviour).
5. Nothing crosses the boundary that the call doesn't need — never keys, files, index rows.

---

## Phases

### Phase E0 — Contract + the Templates extension APK
**Status:** ✅ Complete (commit ebfc31b)

**Goal:** `:extension-api` and `:ext-templates` exist, build, and install; the extension is invisible
in every launcher and answers the AIDL contract. **The core is not touched in this phase** (except
`settings.gradle.kts` `include`) — the v0 create flow keeps working exactly as before.

**Questions to resolve at phase start** (ask one at a time; recommended default first):
1. Extension label — "Notesprout Paper · Templates" (rec.) / "Paper Templates" / other?
2. Extension `applicationId` — `com.symmetricalpalmtree.notesprout.ext.templates` (rec.) / other?
3. Reuse Paper's launcher icon for the extension (rec.: yes) / a distinct icon (which)?
4. Confirm the renderer moves **verbatim** (same geometry, same WEBP settings) — rec.: yes, no tuning
   in this arc.

**Deliverables**
1. `settings.gradle.kts`: `include(":app", ":extension-api", ":ext-templates")`.
2. `:extension-api` (`com.android.library`, `namespace com.symmetricalpalmtree.notesprout.extension`,
   `minSdk 29`, `compileSdk 35`, `buildFeatures { aidl = true }`, Kotlin, Java 17, no dependencies):
   `ExtensionContract`, `TemplateInfo`, `RenderedTemplate` (hand-written Parcelables + `.aidl`
   declarations), `ITemplateProvider.aidl` — exactly as in "Contract v1".
3. `:ext-templates` (`com.android.application`) exactly as in "Extension side": manifest (no Activity,
   the one service + meta-data), `TemplateProviderService`, `CallerCheck`, `TemplateRenderer` (moved
   verbatim from `app/.../data/template/BuiltInTemplates.kt` + `bitmapToWebp`), strings, icon copied,
   `BuildConfig.HOST_PACKAGE` per build type, debug label override.
4. `TemplateGeometryTest` copied to `ext-templates/src/test/kotlin/...` (the `:app` copy is deleted in
   E1, not now — E0 leaves the core untouched).
5. `docs/extensions.md` — first version: the extension model, contract v1 (verbatim tables/AIDL from
   this file), how to build/install the extension, the no-launcher rule, the trust rule.
6. `apps/notesprout_paper/CLAUDE.md`: add the two modules to Build & install and the standing rule
   "`:extension-api` depends on nothing in `:app`".

**Tests**
- JVM: `./gradlew :ext-templates:testDebugUnitTest` green (geometry tests); `./gradlew assembleDebug`
  builds all three modules; `:app` tests still green.
- Sanity from the shell (Claude runs, no user needed), per device after `adb -s <serial> install -r
  ext-templates/build/outputs/apk/debug/ext-templates-debug.apk`:
  - `adb -s <serial> shell pm list packages | grep ext.templates` → present.
  - `adb -s <serial> shell dumpsys package com.symmetricalpalmtree.notesprout.ext.templates.dev | grep -B2 -A6 TEMPLATE_PROVIDER`
    → the service and its intent-filter are registered.
  - `adb -s <serial> shell pm resolve-activity --brief -c android.intent.category.LAUNCHER com.symmetricalpalmtree.notesprout.ext.templates.dev`
    → "No activity found" (no launcher entry).
  - BOOX trap: if `pm list packages -d` shows it disabled → `pm enable <pkg>`.
- **User device checklist** (SNN, NA5C, MIP11 — the user says which):
  1. Open the device's app drawer / launcher (BOOX Apps tab; Supernote's sideloaded-apps list; MIP11
     stock launcher). **No** "Templates" icon appears.
  2. Settings → Apps: "Notesprout Paper · Templates Dev" is listed; its "Open" control is absent or
     inert; "Uninstall" is offered. (Don't uninstall.)
  3. Existing Paper (v0 build still installed) creates a Lined notebook exactly as before (core
     untouched — regression only).

**Close-out:** status ✅ + Outcome (record: label, ids, whether Supernote's launcher hid it as
expected); docs; memory; commit + push `paper`.

**Outcome (E0):** Label `Notesprout Paper · Templates` (debug `… Dev`); package
`com.symmetricalpalmtree.notesprout.ext.templates` (debug `.dev`); ids `lined`/`dotted`/`grid`; icon =
puzzle piece with a green sprout (Paper greens `#4CAF50`/`#66BB6A`). Three modules build; JVM tests
green (geometry test moved to `:ext-templates`, refs `TemplateRenderer`). Verified SNN + NA5C
2026-08-16. **Launcher visibility differs by device:** the extension declares no launcher Activity
(`pm resolve-activity LAUNCHER` → "No activity found" on both), and BOOX hides it from the Apps tab as
intended — reach it via Settings → Apps → "See all apps" to uninstall. **Supernote's launcher lists
every sideloaded _package_ regardless of a `LAUNCHER` intent, so an icon shows there — not preventable
via manifest;** revisit in the Extensions-UI arc if it matters. BOOX sideload trap seen (landed
disabled → `pm enable`). Regression: existing Paper still creates a Lined notebook (core untouched).

---

### Phase E1 — Host integration: discovery, client, New-notebook wiring, core renderer removed
**Status:** ✅ Complete (commit fdeeb91)

**Goal:** the core offers templates only via discovered, trusted providers; the built-in renderer is
gone from the core; with no extension installed the New-notebook screen has no Template section and
creates blank notebooks; with it installed, Lined/Dotted/Grid produce notebooks indistinguishable from
v0's.

**Questions to resolve at phase start** (one at a time; recommended default first):
1. Timeouts — bind 3 s / list 2 s / render 15 s (rec.) / other values?
2. Render failure — stay on the screen with a toast (rec., as specified) / silently create Blank?
3. On-screen behaviour while discovery runs — section simply appears when ready (rec.; ~100–300 ms) /
   reserve its space with a placeholder to avoid a layout jump on e-ink?
4. Confirm the failure toast wording: "Template unavailable — try again or choose Blank" (rec.) / other.

**Deliverables**
1. `:app` depends on `project(":extension-api")`; manifest `<queries>` block.
2. `extension/ExtensionRegistry.kt`, `extension/TemplateProviderClient.kt` (+ `ExtensionCallException`),
   `extension/TemplateChoice.kt` — exactly as in "Host side".
3. `NewNotebookActivity` + `layout/activity_new_notebook.xml` + new `layout/item_template_radio.xml`
   + strings (`new_notebook_template_failed`; delete `template_lined/dotted/grid`) — exactly as in
   "Host side". `SoilSchema.TEMPLATE_BLANK = "BLANK"`.
4. Delete `app/.../data/template/` and `app/src/test/.../data/TemplateGeometryTest.kt`.
5. JVM tests: `extension/TemplateIdentityTest` (`templateIdentity`/`parseIdentity` round-trip, first-colon
   split, malformed → null) — lives in `:extension-api`'s test source set (`extension-api/src/test/kotlin`;
   add `testImplementation junit` there). Existing `:app` tests still green.
6. Docs: `docs/library.md` (New-notebook section rewritten: dynamic radios, blank-when-absent, identity
   strings, failure behaviour); `docs/extensions.md` gains the "Host behaviour" section (discovery,
   trust, timeouts, failure surface); `docs/data.md` note on `templateKind` / template-row `text` values
   (`BLANK` | `<pkg>:<id>` | legacy `LINED`/`DOTTED`/`GRID`).
7. `CLAUDE.md`: standing rule for the notebook-creation path ("templates come only from
   `ExtensionRegistry` providers; the core has no renderer").

**Tests**
- JVM: `./gradlew testDebugUnitTest` (all modules) green; `./gradlew assembleDebug` builds.
- **User device checklist** — install **both** APKs (`app-debug.apk`, `ext-templates-debug.apk`) on
  each requested device. Keep one v0-created Lined notebook on the device from before this build for
  comparison.
  1. Library → **+** → New notebook: the **Template** section is present with Blank / Lined / Dotted /
     Grid (Blank checked).
  2. Create **Lined**; open it; write. Flip to a v0-created Lined notebook: rule spacing and weight
     look identical. Repeat for **Dotted** and **Grid** (grid rows uniform, no double-height top band).
  3. Create **Blank**: no lines; cover on the library card is white.
  4. `adb -s <serial> shell pm disable-user --user 0 com.symmetricalpalmtree.notesprout.ext.templates.dev`
     (Claude runs). Reopen New notebook: **no Template section at all**. Create → a blank notebook,
     opens and writes fine.
  5. `pm enable` (Claude runs). Reopen New notebook: section is back.
  6. `adb -s <serial> uninstall com.symmetricalpalmtree.notesprout.ext.templates.dev` (Claude runs):
     same as step 4. Reinstall the extension: same as step 5. **All previously created templated
     notebooks still open with their templates** while the extension is absent (core draws the stored
     WEBP).
  7. `adb -s <serial> shell am force-stop com.symmetricalpalmtree.notesprout.ext.templates.dev`
     (Claude runs, while the New-notebook screen is open with Lined selected) → tap CREATE → the
     notebook is created with lines (auto-create binding).
  8. Old v0 notebooks (Blank/Lined/Dotted/Grid) open, flip, and write unchanged.
  9. Timing by eye on SNN: the Template section appears well under a second after the screen opens;
     CREATE with Grid completes in about the time it did in v0.
- Claude-side log check on one device: `logcat -s ExtensionRegistry TemplateProviderClient` shows one
  bind/unbind pair per list and per render, no leaked `ServiceConnection` warnings
  (`"has leaked ServiceConnection"` must not appear).

**Close-out:** status ✅ + Outcome (record any timing numbers and any e-ink layout-jump observation);
docs; memory; commit + push `paper`.

**Outcome (E1):** Built as specified; all four phase-start answers = defaults except the toast, which
reads **"Template extension didn't respond — try again or choose Blank"**. Full checklist 1–9 passed on
**SNN + NA5C** 2026-08-16. Logs: one bind/unbind pair per list and per render, 0 `leaked
ServiceConnection`. Timings (logcat): SNN list ≈540 ms cold / ≈110 ms warm, render 280–590 ms
(≈0.8 s after a force-stop); NA5C list ≈400 ms cold / ≈50 ms warm, render 130–260 ms (≈0.5 s after a
force-stop). No layout-jump complaint on either e-ink device (section appears with the first paint or
one refresh after). One trap: **on NA5C, BOOX re-disabled the extension a few seconds *after*
`install -r`, overwriting the immediate `pm enable`** — discovery correctly saw 0 candidates until it was
enabled again (recorded in `docs/extensions.md` + `CLAUDE.md`). Provider headings are shown only when
more than one provider *contributed templates* (not merely was discovered) — a provider that failed to
list would otherwise leave a lone heading. Deleted from `:app`: `data/template/`, `TemplateGeometryTest`,
`bitmapToWebp`, `template_lined/dotted/grid` strings.

---

### Phase E2 — Hardening, review, docs freeze
**Status:** ✅ Complete (commit c215aaa)

**Goal:** the API v1 + Templates extension are trustworthy enough to be the pattern every later
extension follows.

**Questions to resolve at phase start** (one at a time):
1. Anything observed in E1 the user wants changed before freezing (wording, timing, presentation)?
   (rec.: no — freeze as built)
2. Confirm scope freeze: no new behaviour in E2, fixes only.

**Deliverables**
1. `/code-review high` over the arc's diff (`git diff 611de61...HEAD` — the range, not a bare ref);
   fix confirmed findings.
2. **Boundary audit** walked and recorded in `docs/extensions.md` §"Boundary audit": host-side
   signature check on every discovery; extension-side caller check in every stub method; nothing but
   geometry crosses outward, nothing but WEBP bytes crosses inward; byte cap + `Bitmaps.decodeBounded`
   on the way in; every bind has an unbind in `finally`; every call has a timeout; no passphrase, key,
   path, or notebook id reaches the extension; failure never creates a notebook silently different from
   what the user chose.
3. `docs/extensions.md` final: add "Writing an extension" (what a third party will do once the API is
   published: depend on `extension-api`, declare the service + meta-data, implement the stub, no
   launcher activity, caller check) and the versioning rules from this file. `README.md`: extensions
   paragraph + the two-APK install. `CLAUDE.md`: final standing rules.
4. `PAPER_EXTENSIONS_PLAN.md` frozen; memory updated (arc complete).

**Tests:** full E1 device checklist again on all three devices + v0 regression subset (create/open/
write/flip/insert/delete page, library create/rename/move/delete, cold-launch reopen).

**Close-out:** status ✅ + Outcome; commit + push `paper`.

**Outcome (E2):** Full checklist (E1 items 1–9 + E2 popup items + v0 regression subset) passed on
**SNN + NA5C + MIP11** 2026-08-16 — the first phase verified on all three test devices. Logs: one
bind/unbind pair per render, 0 `leaked ServiceConnection`. `/code-review high 611de61...HEAD` → 10
findings, 9 fixed, 1 accepted+documented: (fixed) `WEBP_LOSSLESS` is API 30+ vs minSdk 29 — guarded
(inherited from v0); New-notebook lost the chosen template on Activity recreation — identity saved /
restored; host rejects a render whose decoded size ≠ requested w×h (+ header probe); signature
re-checked before every bind (TOCTOU); `RenderedTemplate.describeContents` = `CONTENTS_FILE_DESCRIPTOR`;
extension closes its `SharedMemory` in `onTransact` finally; `list()` filters nulls + discovery
failure caught; catalogue = one `enum Kind(id, nameRes)`; `:ext-templates` depends on `:extension-api`
only; heading uses `BodyMedium`. (Accepted) identity uses the installed package, so `.dev` and release
extensions write different labels for the same template — recorded in `docs/extensions.md`. **Added on
the user's request (Q1):** the notebook screen's **"Opening…" popup** — visible from the first frame,
whole-paper exclusion rect until `opened` (no pen input while it is up), hidden + chrome rects restored
the moment the page is loaded (not pen-idle gated: hover counts as active). Docs frozen: Boundary
audit (9 rows), Rules for a future point, Writing an extension; README extensions paragraph + two-APK
install; CLAUDE.md standing rules. **Arc complete.**

**Phase-start answers (2026-08-16):** Q1 — one change requested from E1 testing: on SNN the template
takes a beat to show after the notebook screen opens, and ink written before then is dropped
(`onStrokeCommitted` ignores strokes while `!opened`); the user asked for an e-ink-friendly
**"Opening…" popup (75 % width)** on the notebook screen until it is ready for writing. Q2 — scope:
**overlay only** (no change to the early-stroke behaviour); otherwise fixes only.

**Post-arc retrospective (2026-08-16, after E2):** the user weighed alternative hostings (in-package
first-party extensions toggled via component enable/disable, split APKs, DexClassLoader, data packs) and
**kept separate installable APKs**. Supernote's sidebar Apps grid + My Apps list every user-installed
package regardless of a `LAUNCHER` intent — accepted as a Ratta quirk (every other target hides it).
Mitigation shipped: extension label convention **`NSE · <Name>`** (Templates → `NSE · Templates`,
debug `… Dev`; `NPE` rejected — reads as NullPointerException), extension icon = **Tabler `puzzle`**
black outline, app icon = bare **Tabler `seedling`** black outline (green sprout + page dropped; "Paper"
is a codename, the sprout is the brand), both scaled ×3.1–3.4 to sit at Ratta's own icon size.
Sprout-inside-puzzle tried and dropped (mush at launcher size). Reference: `docs/extensions.md`
§"Naming + icon convention". Deferred: the host's discovery is variant-blind (a same-key release
extension is discovered by the dev host and fails `CallerCheck` per call rather than being hidden) — a
`HOST_PACKAGE` `<meta-data>` on the `<service>` would fix it; cross that bridge with the Extensions UI.

---

## Appendix A — Constants (this arc)

| Name | Value |
|---|---|
| `ExtensionContract.API_VERSION` | 1 |
| `ACTION_TEMPLATE_PROVIDER` | `com.symmetricalpalmtree.notesprout.extension.TEMPLATE_PROVIDER` |
| `META_API_VERSION` | `com.symmetricalpalmtree.notesprout.extension.API_VERSION` |
| `MAX_RENDER_BYTES` | 16 MiB |
| Bind / list / render timeouts | 3 000 / 2 000 / 15 000 ms |
| Template identity | `<extension package>:<template id>` · Blank = `BLANK` (`SoilSchema.TEMPLATE_BLANK`) |
| Templates extension package | `com.symmetricalpalmtree.notesprout.ext.templates` (debug `.dev`) |
| Template ids / names | `lined` Lined · `dotted` Dotted · `grid` Grid |
| Renderer geometry | unchanged from v0: 8 mm spacing at device dpi; 1 px rule / 2 px dot radius at mdpi, scaled by dpi, floor 1 px; LINED top margin 2×spacing; GRID/DOTTED origin 1×spacing; WEBP lossless q100 |

## Appendix B — Allowed dependencies (in addition to `PAPER_PLAN.md` Appendix B)

```
:extension-api   — none (Kotlin stdlib only; testImplementation junit:junit:4.13.2)
:ext-templates   — project(":extension-api"), androidx.core:core-ktx:1.13.1, testImplementation junit:junit:4.13.2
:app             — + project(":extension-api")
```
No `kotlin-parcelize`. No new plugins. AIDL is enabled via `buildFeatures { aidl = true }` in
`:extension-api` only.

## Appendix C — Build & install (this arc)

```sh
cd ~/git/Notesprout/apps/notesprout_paper
./gradlew assembleDebug                      # all modules
./gradlew testDebugUnitTest                  # all modules
adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <serial> install -r ext-templates/build/outputs/apk/debug/ext-templates-debug.apk
adb -s <serial> shell pm enable com.symmetricalpalmtree.notesprout.ext.templates.dev   # BOOX sideload trap
adb -s <serial> shell pm disable-user --user 0 com.symmetricalpalmtree.notesprout.ext.templates.dev  # simulate "not installed"
adb -s <serial> uninstall com.symmetricalpalmtree.notesprout.ext.templates.dev
```
Both APKs are signed by the same debug keystore (`~/.android/debug.keystore`) — that is what satisfies
the same-signature rule in dev. An extension built on another machine will **not** be trusted by this
Mac's core build (different debug key) — expected, not a bug.

## Appendix D — Reference map

| Concern | Where |
|---|---|
| v0 create flow being rewritten | `app/.../library/NewNotebookActivity.kt` (`selectedTemplate`, `attemptCreate`, `createNotebook`, `bitmapToWebp`), `res/layout/activity_new_notebook.xml` (Template block), `res/values/strings.xml` (`template_*`) |
| Renderer being moved | `app/.../data/template/BuiltInTemplates.kt`, `app/src/test/.../data/TemplateGeometryTest.kt` |
| How the core draws a stored template (unchanged) | `app/.../notebook/NotebookSession.kt` `loadTemplateFor` → `Bitmaps.decodeBounded` → `paper.setTemplate` in `NotebookActivity` |
| Index / `.soil` label columns | `data/index/ObjectEntity.kt` `templateKind`; `data/soil/SoilObjectEntity.kt` template row `text`; `docs/data.md` |
| Design system for the dynamic radios | `res/values/styles.xml` `Widget.Notesprout.RadioButton`; root `docs/design-system.md` |
| Android references | Bound services + AIDL (`android.developer` "Bound services", "AIDL"); package visibility `<queries>` (API 30+); `android.os.SharedMemory` (API 27+); `PackageManager.checkSignatures` |

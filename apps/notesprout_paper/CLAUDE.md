# Notesprout Paper — Project Intelligence

Paper is an experimental from-scratch rebuild of Notesprout. It keeps the `.soil` container family,
global-index model, global encryption model, and e-ink design philosophy — and drops everything else.

- **Branch:** `paper`
- **Location:** `apps/notesprout_paper/`
- **Plans:** `PAPER_PLAN.md` (v0, complete — architecture + locked decisions), then
  `PAPER_EXTENSIONS_PLAN.md` (arc 1, complete + frozen: extension API v1 + Templates extension), then
  `PAPER_NAMING_PLAN.md` (arc 2, complete + frozen: the host-owned encrypted extension store + the
  Naming extension), then `PAPER_RECOGNITION_PLAN.md` (arc 3, complete + frozen 2026-08-17: the
  engine-neutral `HANDWRITING_RECOGNIZER` capability point + the ML Kit extension `NSE · ML Kit`,
  debug-only "Recognize page" test surface), then `PAPER_OBJECTS_PLAN.md` (arc 4, **complete + frozen 2026-08-18** —
  H0 8c5361f · H1 62771f3 · H2 bf17417 · H3 0de688e · H4 f995354 · H5 6c5d5c2: content objects in the `.soil` + the selection toolbar with its
  extension-contribution API + the `MARKDOWN_RENDERER` capability point / `NSE · Markdown` + the
  generic `OBJECT_PROVIDER` point / `NSE · Heading`, the two proxies, g-paper 0.1.1), then
  `PAPER_CONTENTS_PLAN.md` (arc 5, **complete + frozen 2026-08-18 — C0 ✅ cc0558d · C1 ✅ c9733c5 ·
  C2 ✅ 54b9bf2**, all user-verified SNN + NA5C + MIP11: the
  Contents — a table of contents from the Heading extension via `IObjectProvider.describeOutline`
  (appended, compatible, `API_VERSION` stays 1) + a core-drawn `ContentsDialog`, top-bar `list`
  button + one-finger swipe-down), then **`PAPER_SCRATCHPAD_PLAN.md` (arc 6, PLANNED 2026-08-19 —
  the ACTIVE arc: `NSE · Scratch Pad`, an extension-owned off-paper screen (UI-rule tier 2, first
  exercise) + the shared `:paper-screen` module + the `SCRATCH_PAD` point + `IExtensionStore.putLarge /
  getLarge` (4 MiB values over `SharedMemory`) + the two ink transfers; S0 ⬜ · S1 ⬜ · S2 ⬜ · S3 ⬜)**
  — read all seven top-to-bottom at the start of every session; **next = S0 (fresh session, the
  phase-start wizard first).**
- **Package / applicationId:** `com.symmetricalpalmtree.notesprout` (debug: `.dev` suffix)
- **Launcher label:** "Notesprout Paper" (debug: "Notesprout Paper Dev")

---

## Standing rules

All rules from the root `CLAUDE.md` apply (language, serialization, no new deps, no Material, no
runBlocking on UI, IndexGuard, Slog, encryption hygiene, design system). In addition:

- **g-paper** is the drawing surface, consumed from **mavenLocal**
  (`com.symmetricalpalmtree.gpaper:gpaper-{core,onyx,ratta}:0.1.1`). Read g-paper docs before touching
  the notebook screen: `~/git/g-paper/docs/api.md`, `host-responsibilities.md`, `integration-guide.md`,
  and `~/git/g-paper/CLAUDE.md`.
- **No file over ~800 lines** without a written reason.
- **Portrait-locked** on every screen.
- **No colour in chrome** — ink itself is black in v0.
- **One layout per screen** — no width-variant XML files unless the narrowest device (MIP11) can't fit.
- **Every SQLCipher open goes through `crypto/SoilCrypto`** (non-destructive factories; opens are
  exists-guarded; creation only via the named create entry points). Read `docs/crypto.md` and
  `docs/data.md` before touching `crypto/` or `data/`.
- **Notebook screen** (`notebook/`): read `docs/notebook.md` first. The paper is full-bleed and chrome
  overlays it — every chrome rect goes to `setExclusionRects`; no app frame while `paper.isPenActive`
  (route chrome text changes through `whenPenIdle`); all `.soil` writes go through the session's one
  serial `SoilWriter` (`StrokeStore` **and** `ObjectStore` enqueue there — arc 4); the file is opened by `NotebookSession.open()` only (exists-guarded — never created
  there) and closed only by the `close()` sequence (cover → lastOpened → meta → seal). `GPaper` is in
  `com.symmetricalpalmtree.gpaper.core.engine`.
- **mavenLocal can lag the g-paper checkout** — if a g-paper symbol from `docs/api.md` is unresolved,
  `cd ~/git/g-paper && ./gradlew publishToMavenLocal` before suspecting anything else.
- **Extensions** (`PAPER_EXTENSIONS_PLAN.md`, `docs/extensions.md`): the project has seven modules —
  `:app` (the core/host), `:extension-api` (the shared contract library), `:ext-templates` (the
  first-party Templates extension APK), `:ext-naming` (the Naming extension APK, arc 2),
  `:ext-mlkit` (the ML Kit handwriting-recognizer extension APK, arc 3), `:ext-markdown` (the
  Markdown renderer extension APK, arc 4 / H0) and `:ext-heading` (the Heading object-provider
  extension APK, arc 4 / H3).
  **`:extension-api` depends on nothing in `:app`, ever** (Gradle enforces `:app → :extension-api`,
  `:ext-templates → :extension-api`, `:ext-naming → :extension-api`, `:ext-mlkit → :extension-api`,
  `:ext-markdown → :extension-api`, `:ext-heading → :extension-api`; `:app` and the extension modules
  never depend on each other — `:ext-heading` reaches the recognizer and the renderer only through the
  proxies the core hands it). Extension-side caller check = `HostCallerCheck.enforce(ctx,
  BuildConfig.HOST_PACKAGE)` from `:extension-api`, first thing in every stub method. An extension is a separate APK with **no launcher Activity**, bound over
  AIDL; the core trusts it only if `checkSignatures == SIGNATURE_MATCH` (in dev, the shared
  `~/.android/debug.keystore` satisfies this). **Naming + icon convention:** label `NSE · <Name>`
  (debug appends ` Dev`), icon = Tabler `puzzle` black outline; the app's own icon is the bare Tabler
  `seedling` (both sized like Ratta's icons — Supernote lists every installed package, launcher or not;
  accepted). Details: `docs/extensions.md` §"Naming + icon convention".
- **Notebook creation:** templates come **only** from `ExtensionRegistry` providers via
  `TemplateProviderClient` (bind-per-operation, signature re-checked at bind, timeouts, unbind in
  `finally`, payload = mime + byte cap + exact requested size); **the core has no renderer**. No
  extension → no Template section, blank notebook. A render failure stays on the screen with a toast —
  never a silent Blank. Identity labels: `BLANK` | `<pkg>:<id>` | legacy `LINED`/… .
- **Extension store** (arc 2 / N0, `data/extstore/`, `docs/extensions.md` §"The extension store"): an
  extension's data lives in a **core-owned** SQLCipher key/value DB `Garden/<pkg>.db` under the global
  key (raw-key id `ext:<pkg>`), opened only by `ExtensionStores.open` (open-or-create — the **third
  named create entry point**, `docs/crypto.md` audit item 2) and reached by the extension only through
  an `ExtensionStoreBinder` the host mints **per bind, uid-bound, revoked in the unbind `finally`**;
  caps `ExtensionContract.STORE_*` (512 chars / 256 KiB / 50 000 keys). Never a key, path, or `File`
  across the boundary. Open the store on IO **before** binding (cold KDF must not sit inside the call
  timeout). The `.db` survives the extension's uninstall.
- **NotebookNamer** (arc 2 / N1, `docs/extensions.md` §"NotebookNamer — host behaviour"): the second
  point (`ACTION_NOTEBOOK_NAMER`, `INotebookNamer`, `SchemeField`). `ExtensionRegistry.notebookNamer`
  returns **the first** trusted namer; `NamerClient` = the Templates client shape + store pre-open,
  per-bind `ExtensionStoreBinder`, revoke in the unbind `finally`, every call ≤ 2 s. `LibraryActivity`
  owns the three entry points (New-folder scheme field · folder long-press "Default notebook name…" ·
  +Notebook prefill resolved **before** `NewNotebookActivity` opens via `EXTRA_DEFAULT_NAME`) and all
  are **absent when no namer is installed**; `NewNotebookActivity` stays extension-agnostic
  (`acceptDefaultName` = name rule + `MAX_NAME_CHARS`). Outward payload = folder UUID + sibling
  notebook names (+ the typed scheme) — the one recorded widening of the boundary rule; the core never
  interprets a scheme.
- **HandwritingRecognizer** (arc 3 / M0, `docs/extensions.md` §"HandwritingRecognizer (contract)" +
  §"The ML Kit extension"): the third point (`ACTION_HANDWRITING_RECOGNIZER`, `IHandwritingRecognizer`,
  `InkStroke`, `RecognizerStatus`) is a **capability point** — engine-neutral, stateless, `en-US` only;
  the core binds it (M1) and later lends it to consumer extensions through a proxy, never
  extension-to-extension. Outward = bare x/y geometry + area/page size + ≤ 20 chars pre-context (the
  recorded widening of the boundary rule for this point); caps `MAX_INK_STROKES 2 000` /
  `MAX_INK_POINTS 60 000` host-enforced before the call and re-checked by the extension.
  **`com.google.mlkit:digital-ink-recognition` lives in `:ext-mlkit` only** — never `:app` or
  `:extension-api`. The model lives in the extension's own sandbox (the recorded exception to
  "extension data goes to the host store" — engine assets are not user data). `status()` →
  `prepare()` → `recognize*` (M1: `status()` never blocks; `recognize*` waits for an acquisition
  already in flight inside the host's timeout and throws `IllegalStateException` only if it can't —
  M2: with the exact message `ExtensionContract.RECOGNIZER_NOT_READY`; **only `prepare()` may start a
  download** — the host asks first; the extension stops at its own budget just under the host's
  timeout). Host side (M1): **one shared
  bind path `ExtensionBinder.call`** for all three clients; `RecognizerClient` + `InkCaps` (caps before
  the bind); the model-download dialog flow (offline pre-check via `core/Connectivity`) is
  `extension/RecognizerReadiness` in main source since H3 — the notebook debug ⋯ calls it. **Recognized text is never logged on either side** — counts + durations only.
  M2 froze it: boundary-audit rows 14–17 (`docs/extensions.md`), the capability-point rules 12–17 +
  §"The capability pattern" (the proxy recipe — `RecognizerProxyBinder`, built only with the first
  consumer point, never before), and the "Writing an extension" recognizer paragraph. **Dots** (M2
  addendum): the ML Kit extension rounds tiny strokes and forces a trailing baseline dot to `.` — see
  `docs/extensions.md` §"The ML Kit extension"; it is the extension's heuristic, the core knows
  nothing of it. **Logging in
  extension modules:** `Slog` lives in `:app` and is unreachable from `:ext-*`; the recorded
  equivalent there is `if (BuildConfig.DEBUG) Log.d(...)` (never text — counts + durations).
- **MarkdownRenderer** (arc 4 / H0, `docs/extensions.md` §"MarkdownRenderer (contract)" + §"The
  Markdown extension"): the fourth point (`ACTION_MARKDOWN_RENDERER`, `IMarkdownRenderer`,
  `RenderedImage`) is the second **capability point** — markdown in, a transparent lossless-WEBP
  image out (`RenderedTemplate` handshake + declared `widthPx × heightPx`, verified by the host);
  blank source → `null`. Caps `MAX_MARKDOWN_CHARS 20 000` / `MAX_IMAGE_EDGE_PX 4 096` /
  `RENDER_PADDING_MAX_PX 64`. **All typography lives in the extension** (`MarkdownSpans` /
  `MarkdownBitmap`: base 24 sp, bold headings ×2.0/1.75/1.5/1.25/1.1/1.0) — the core never learns a
  font size. The core binds it through `MarkdownClient` (H3; `RenderCaps` before the bind,
  `RenderedImages.copyOut` inward — header size == declared, edge cap) and lends it to object
  providers through `MarkdownProxyBinder`. The markdown text is never logged on either side.
- **Content objects** (arc 4 / H1, `docs/notebook.md` §"Content objects", `docs/data.md` §"Object
  rows"): `object` rows are core-owned (identity, provider identity in `style`, **opaque** payload in
  `text` — never parsed, never logged, capped at `MAX_OBJECT_TEXT_CHARS` — bounds `x y width height` in
  page px, z-order); the `.soil` gained `x`/`y` **without a version bump or migration** (pre-H1 test
  notebooks fail to open and are deleted by hand). `ObjectRenderer` is the one g-paper
  `ContentRenderer`: cached bitmap or dashed placeholder, live-drag pair, `hitTargets` = object bounds;
  `ObjectRenderCache` is session-only (no stored bitmap, ever). Page delete / undo carry objects with
  strokes (`liveChildIds`); every object action is one undoable step. (The H1 debug ⋯ "Insert test
  object" item was removed in H5.)
- **Selection toolbar + contributed UI** (arc 4 / H2, `docs/notebook.md` §"Selection toolbar" +
  §"Edit dialog", `docs/extensions.md` §"Selection-toolbar contributions"): the floating toolbar over a
  lasso selection is **core-drawn from descriptions** — `SelectionAction` (id, ≤ 6-char label, catalog
  icon name, `ActionApplies` / `Requires` bits, one level of sub-actions) and `EditSpec` — through
  `ActionCaps` / `EditCaps` (untrusted inward), `IconCatalog` (`IconNames` → Tabler drawables; unknown →
  the label as text) and `SelectionActions.merge` (Delete first, provider order, INK / one-OBJECT /
  mixed → Delete only). **The UI rule is tiered:** description → core-drawn is the only UI over the
  paper; extension-owned screens *off* the paper are the recorded future escape hatch; remote UI /
  in-process extension code over the paper never. Toolbar shows via `whenPenIdle`, hides during a drag,
  anchors 8 dp off the drawn selection box (flip / clamp — `ToolbarAnchor`), and its rects join the
  exclusion rects. `ObjectEditDialog` follows the design-system IME pattern (hide via the field's window
  token on Save/Cancel; never earlier — Ratta hardware keyboards). **The toolbar and the edit dialog
  show at once — not `whenPenIdle`** (H5: the pen hovers after a lasso / tap, and `isPenActive` counts
  hover; g-paper has already presented the selection frame, so no frame-silence break). The H2
  `FakeContributions` twin was deleted in H5.
- **ObjectProvider + the built proxies** (arc 4 / H3, `docs/extensions.md` §"ObjectProvider
  (contract)", §"The Heading extension", §"MarkdownRenderer / ObjectProvider — host behaviour"): the
  fifth point (`ACTION_OBJECT_PROVIDER`, `IObjectProvider`, `CreatedObject`) is the **generic
  content-object point** — describe types/actions · `createFromInk` · `applyAction` · `describeEdit` /
  `applyEdit` · `render`; the core stores the rows, the provider keeps nothing (rule 18), the payload is
  opaque (rule 19). **Capabilities reach a provider only as in-parameters** (rule 23): `ObjectProviderClient`
  mints `RecognizerProxyBinder` (for `createFromInk`) / `MarkdownProxyBinder` (for `render`) **per bind,
  uid-gated by `ProxyGate`, revoked in the client's own `finally` right after the unbind**, or hands
  **null** when nothing is installed — the core never fakes a capability. Proxies forward through the
  core's own clients (own bind / timeout / signature check) via **`runBlocking` on the Binder thread**
  (never Main), re-apply the caps inward, and map failures to the marshalable set; the Markdown proxy
  re-wraps the reply into a region it owns (closed in `onTransact`'s `finally`). Two-hop budgets:
  `createFromInk` 15 s, `render` 10 s (H5: was 8 — zero margin over the inner bind 3 s + call 5 s); pure calls 2 s. A provider's `IllegalStateException(RECOGNIZER_REQUIRED
  / MARKDOWN_REQUIRED)` is typed on the host as `CapabilityRequiredException`. `NSE · Heading`
  (`:ext-heading`, `HeadingText` / `HeadingActions` / `ObjectProviderService`) is the reference
  provider: `heading` type, parent **H** with `h1`…`h6`, edit `maxChars` 500, single line + 8 dp padding
  asked of the Markdown proxy, the proxy's reply returned as-is. **`RecognizerReadiness`** (main source,
  `extension/`) is the M1/M2 model-consent flow lifted out of the debug menu (which now only calls it);
  the H action uses it. (The debug ⋯ "Probe object providers" H3 test surface was removed in H5.)
- **Objects end to end** (arc 4 / H4, `docs/notebook.md` §"Objects — actions, edit, render pass"): the
  screen's three H4 collaborators are `ObjectProviders` (loaded once per open **after** `opened` — its
  binds never hold the Opening popup; resume compares the discovery signature and reloads on a change),
  `ObjectActions` (guards recognizer → Markdown → page cap, `RecognizerReadiness`, no popup since H5,
  the provider calls, **every failure dialog** — core `objects_*` strings) and `ObjectRenderPass` (objects
  grouped by provider → **`ObjectProviderClient.renderAll` = one bind + one Markdown proxy per provider**
  → decode → the screen caches, sizes the object to its image, one pen-idle frame). `NotebookActivity`
  keeps only the page mutations (`objectListener`: create + erase as one `ObjectCreated`, `ObjectEdited`
  recorded with the *rendered* bounds, host-initiated `paper.setSelection` sets the selection state
  itself — no `onSelectionCreated` echo). Rules that bit: the cache entry survives moves that don't hit
  the right edge (`ObjectRenderCache.get` fit rule — "unconstrained" = stopped > 64 dp short of its
  width, else an ellipsized line is mistaken for a natural one); the cache is bounded to the current
  page (`retain` on load); a failed render is not retried until the next page load / provider reload /
  edit of that object; the background pass never holds `pageOps`, the inline one (create / apply / edit)
  is awaited under it — **so a slow provider queues flips / undo behind it** (bounded: bind 3 s +
  render 10 s; accepted in H5, not redesigned). OBJECT actions guard `Requires.MARKDOWN` only; edits
  guard nothing. **Frame at once on the inline render** (`applyRenderResults(atOnce = true)` from
  `renderNow`; the background pass stays pen-idle) and **recognizer warm-up at notebook open**
  (`ObjectActions.warmAtOpen` after providers load → one `status()` bind ≤ 1 / 20 s; the H tap
  `onParentOpened` → `warm` re-warms; the ML Kit ext primes its engine after `buildClient` — cold =
  ~1.6 s process + ~1.9 s model load on the BOOX, measured) — both H5, user-designed. **Undo shapes:** `ObjectCreated` is recorded after the inline render (rendered
  bounds), `ObjectEdited`'s `before` is re-anchored at the final x/y (a drag during the round-trip is
  its own `Moved`); `StrokeStore.restore` un-deletes rows **in place** (writing order survives undo;
  `maxOrder` counts soft-deleted rows so it never ties); `NotebookSession`'s structural ops and
  `navigateTo` `writer.drain()` first. **H5 froze the arc:** boundary-audit rows 18–24 + the re-walk of 1/6/7 for the two
  new clients and the proxies' inner calls, the object-point rules 18–23 (§"Adding an object point"),
  and the "Writing an extension" Markdown-renderer + object-provider paragraphs — all in
  `docs/extensions.md`; a provider that returns the Markdown proxy's `RenderedImage` as-is must close
  its own handle in `onTransact`'s `finally` (the H5 fix in `ObjectProviderService`). The next object
  extension (Text / Shape / Link) implements the same AIDL and follows those rules; nothing else
  is planned.
- **Contents** (arc 5, `docs/notebook.md` §"Contents (arc 5)", `docs/extensions.md` rule 24 + audit
  rows 25–27): **`describeOutline` is appended LAST to `IObjectProvider`** — the first exercised
  compatible AIDL change (`API_VERSION` stays 1; never reorder; a further appended method follows the
  same recipe): the core **probes** each provider at load (`ObjectProviderClient.supportsOutline` — one
  blank payload, capable ⇔ a one-entry reply; an old provider yields an *empty reply*, not an exception,
  so the test is the reply's shape) and records `Contribution.outline` outside the resume signature;
  every real reply goes through `OutlineCaps.sanitize` (exact length or null). **An outline is a
  description, not a parse** (rule 24): the provider returns (label, level) per payload and the table of
  contents is core-drawn from those (`ContentsSource` → `OutlineTree` → `ContentsDialog`, behind
  `ContentsFlow`) — the core still never reads inside a payload. The top-bar `list` button exists **only
  while `ContentsFlow.available`** — an outline-capable provider loaded **and** an object of one in the
  notebook (`ContentsSource.available`, refreshed after provider load / page change / object mutation)
  — and the one-finger swipe-down is silent otherwise; **while the Contents shows the whole paper is
  one exclusion rect** (`NotebookChrome.blockAll`, like the "Opening…" popup); a failing capable
  provider = an honest dialog, nothing opens. `NotebookActivity`'s chrome geometry lives in
  `NotebookChrome` (the file sits at the 800-line cap — new notebook-screen logic goes in a
  collaborator, not there). The C0 debug ⋯ "Probe contents" item was removed in C2 (the screen itself
  proves the path).
- **Toast vs. dialog:** a toast only confirms something that already happened ("copied"); anything
  the user must notice or act on — why a tap did nothing, a failure, a one-time download — is an
  `AlertDialog` (e-ink: a toast is easy to miss and reads as "broken"). The one helper is
  `Dialogs.problem(activity, title, message)` (`core/Dialogs.kt`) — use it, don't hand-roll; the
  library, New-notebook, folder-picker and notebook-debug screens all go through it (M2 swept the
  arc-2 namer / name / move toasts).
- **Extension boundary (frozen in E2):** nothing but what a call needs crosses outward (templates: id +
  page geometry + dpi — never keys, paths, ids, names, strokes); everything inward is untrusted. Adding
  an extension point follows `docs/extensions.md` §"Rules for adding a future extension point" and adds
  its rows to §"Boundary audit". `:ext-templates` depends on `:extension-api` only — it is the
  third-party reference implementation; keep it that clean.
- **Notebook screen "Opening…" popup** (`openingOverlay`) is visible from the first frame and hidden
  only when `opened` is set — do not hide it earlier, and do not gate the hide on the pen (hover counts
  as active). While it is up the whole paper is an exclusion rect (no pen input); `pushExclusions()`
  restores the chrome rects at the same moment.

## Build & install

```sh
cd ~/git/Notesprout/apps/notesprout_paper
./gradlew assembleDebug                  # all modules → app + ext-templates + ext-naming + ext-mlkit + ext-markdown + ext-heading debug APKs
./gradlew testDebugUnitTest              # all modules
adb -s SN078D10012852 install -r app/build/outputs/apk/debug/app-debug.apk   # SNN (Nomad)
adb -s 92c16533       install -r app/build/outputs/apk/debug/app-debug.apk   # NA5C
adb -s 5HL21V5007384  install -r app/build/outputs/apk/debug/app-debug.apk   # MIP11
# The extensions (install alongside the app on the same device):
adb -s <serial> install -r ext-templates/build/outputs/apk/debug/ext-templates-debug.apk
adb -s <serial> install -r ext-naming/build/outputs/apk/debug/ext-naming-debug.apk
adb -s <serial> install -r ext-mlkit/build/outputs/apk/debug/ext-mlkit-debug.apk      # ~40 MB; the en-US model (~20 MB) downloads on first prepare() — Wi-Fi once per device
adb -s <serial> install -r ext-markdown/build/outputs/apk/debug/ext-markdown-debug.apk # ~2.5 MB; the Markdown renderer (arc 4)
adb -s <serial> install -r ext-heading/build/outputs/apk/debug/ext-heading-debug.apk   # ~2.5 MB; the Heading object provider (arc 4 / H3)
adb -s <serial> shell pm enable com.symmetricalpalmtree.notesprout.ext.templates.dev  # BOOX sideload trap — BOOX may
adb -s <serial> shell pm enable com.symmetricalpalmtree.notesprout.ext.naming.dev     #   re-disable a few seconds AFTER
adb -s <serial> shell pm enable com.symmetricalpalmtree.notesprout.ext.mlkit.dev      #   install; re-run enable and confirm with `pm list packages -d`
adb -s <serial> shell pm enable com.symmetricalpalmtree.notesprout.ext.markdown.dev
adb -s <serial> shell pm enable com.symmetricalpalmtree.notesprout.ext.heading.dev
```

Debug launch: `adb -s <serial> shell am start -n com.symmetricalpalmtree.notesprout.dev/com.symmetricalpalmtree.notesprout.bootstrap.BootstrapActivity`
(BootstrapActivity is the launcher and the only thing that opens the index; every other screen
bounces there via `IndexGuard`.) The debug build's library ⋯ menu has "Show recovery key" and
"Forget cached key" (kills the process → next launch is the Unlock screen); the notebook screen's
debug ⋯ has "Recognize page (ML Kit)" (present only with `NSE · ML Kit` installed). The app's files are
readable from `adb shell` at `/sdcard/Android/data/<appId>/files/` (index + `Garden/`).

BOOX trap: `install -r` can leave the package disabled → `pm enable com.symmetricalpalmtree.notesprout.dev`.

## Test devices

| Nickname | Device | Serial | Engine |
|---|---|---|---|
| SNN | Supernote Nomad | `SN078D10012852` | `gpaper-ratta` |
| NA5C | BOOX NoteAir5C | `92c16533` | `gpaper-onyx` |
| MIP11 | Wacom Movink Pad 11 | `5HL21V5007384` | `gpaper-core` |

## g-paper version

Currently pinned: **0.1.1** (arc 4 / H1: `PaperListener.onSelectionTapped(x, y)` — a sub-threshold
stylus or single-finger tap inside the active selection box; g-paper commit e76e305). If a phase bumps
g-paper, update the version in `app/build.gradle.kts` and record it in the phase outcome.

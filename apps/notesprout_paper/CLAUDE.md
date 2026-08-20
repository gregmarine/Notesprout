# Notesprout Paper — Project Intelligence

Paper is an experimental from-scratch rebuild of Notesprout. It keeps the `.soil` container family,
global-index model, global encryption model, and e-ink design philosophy — and drops everything else.

- **Branch:** `paper`
- **Location:** `apps/notesprout_paper/`
- **Plans:** `PAPER_PLAN.md` (v0 — architecture + locked decisions), then the six arc plans:
  `PAPER_EXTENSIONS_PLAN.md` (arc 1 — extension API v1 + the Templates extension),
  `PAPER_NAMING_PLAN.md` (arc 2 — the host-owned encrypted extension store + the Naming extension),
  `PAPER_RECOGNITION_PLAN.md` (arc 3 — the engine-neutral `HANDWRITING_RECOGNIZER` capability point +
  `NSE · ML Kit`), `PAPER_OBJECTS_PLAN.md` (arc 4 — content objects + the selection toolbar with its
  extension-contribution API + the `MARKDOWN_RENDERER` / `OBJECT_PROVIDER` points, the two proxies),
  `PAPER_CONTENTS_PLAN.md` (arc 5 — the Contents via `IObjectProvider.describeOutline` + the
  core-drawn `ContentsDialog`), `PAPER_SCRATCHPAD_PLAN.md` (arc 6 — `NSE · Scratch Pad`, the shared
  `:paper-screen` module, the `SCRATCH_PAD` point, `putLarge`/`getLarge`). **All seven are complete +
  frozen** (phase commit hashes and per-device verification are recorded in each plan) — read all
  seven top-to-bottom at the start of every session.
  **Active arc: `PAPER_LINKS_PLAN.md` (arc 7 — `NSE · Links`: core-owned link rows in the `.soil`
  wrapping a selection, extension-owned semantics — opaque payload + `resolve`/`chromeOf`, the
  tier-2 picker screen with the `ILinkCatalog` host callback, trail in the extension store,
  finger-tap follow + swipe-up back). Planned 2026-08-19; phases L0–L5 all ⬜ — L0 next, in a
  fresh session, starting with the plan's L0 phase-start wizard.**
- **Package / applicationId:** `com.symmetricalpalmtree.notesprout` (debug: `.dev` suffix)
- **Launcher label:** "Notesprout Paper" (debug: "Notesprout Paper Dev")

---

## Standing rules

All rules from the root `CLAUDE.md` apply (language, serialization, no new deps, no Material, no
runBlocking on UI, IndexGuard, Slog, encryption hygiene, design system). In addition:

- **g-paper** is the drawing surface, consumed from **mavenLocal**
  (`com.symmetricalpalmtree.gpaper:gpaper-{core,onyx,ratta}:0.1.3`). Read g-paper docs before touching
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
- **Extensions** (`PAPER_EXTENSIONS_PLAN.md`, `docs/extensions.md`): the project has ten modules —
  `:app` (the core/host), `:extension-api` (the shared contract library), **`:paper-screen`** (the
  shared screen library, arc 6 / S0 — below), `:ext-templates` (the
  first-party Templates extension APK), `:ext-naming` (the Naming extension APK, arc 2),
  `:ext-mlkit` (the ML Kit handwriting-recognizer extension APK, arc 3), `:ext-markdown` (the
  Markdown renderer extension APK, arc 4 / H0), `:ext-heading` (the Heading object-provider
  extension APK, arc 4 / H3), `:ext-scratchpad` (the Scratch Pad extension APK, arc 6 — the
  first extension that owns a screen) and `:ext-links` (the Links extension APK, arc 7 — link
  *meaning* over core-owned link structure; its picker is the second tier-2 screen).
  **`:extension-api` depends on nothing in `:app`, ever** (Gradle enforces `:app → :extension-api`,
  `:ext-templates → :extension-api`, `:ext-naming → :extension-api`, `:ext-mlkit → :extension-api`,
  `:ext-markdown → :extension-api`, `:ext-heading → :extension-api`, `:ext-scratchpad → :extension-api`,
  `:ext-links → :extension-api`;
  `:app` and the extension modules never depend on each other — `:ext-heading` reaches the recognizer
  and the renderer only through the proxies the core hands it). Extension-side caller check = `HostCallerCheck.enforce(ctx,
  BuildConfig.HOST_PACKAGE)` from `:extension-api`, first thing in every stub method. An extension is a separate APK with **no launcher Activity**, bound over
  AIDL; the core trusts it only if `checkSignatures == SIGNATURE_MATCH` (in dev, the shared
  `~/.android/debug.keystore` satisfies this). **Naming + icon convention:** label `NSE · <Name>`
  (debug appends ` Dev`), icon = Tabler `puzzle` black outline; the app's own icon is the bare Tabler
  `seedling` (both sized like Ratta's icons — Supernote lists every installed package, launcher or not;
  accepted). Details: `docs/extensions.md` §"Naming + icon convention".
- **`:paper-screen`** (arc 6 / S0, `docs/extensions.md` §"Contract v1" paragraph): the e-ink
  design-system resources (colors, both dimens tiers, styles, `Theme.Notesprout`, the toolbar / dialog
  drawables, every Tabler `ic_*`) and the paper-screen helpers (`core/` `Slog` · `Device` · `TopGuard` ·
  `Dialogs` · `ActionSheetDialog` · `StrokeCodec` · `InkColorCodec` · `Bitmaps`; `notebook/` `PageGestures`
  · `PageMath` · the generic `UndoRedoStack<A>` · `PaperToolbar` · `PaperChrome` · `ToolbarAnchor`) live
  there, **packages unchanged** (the app's `R` sees the moved resources via
  `android.nonTransitiveRClass=false` in `gradle.properties`; the notebook's own undo action set is
  `notebook/NotebookUndo.kt` in `:app`). It depends on **g-paper (`api`) + androidx only** — never
  `:app`, `:extension-api`, Room, SQLCipher, serialization; `:app → :paper-screen`,
  `:ext-scratchpad → :paper-screen` and `:ext-links → :paper-screen` (arc 7 / L0 — the picker takes
  the design system; the five earlier extensions stay `:extension-api`-only). **A fix to
  shared screen logic goes there, never in a consumer.** `Slog` gates on the library's own
  `BuildConfig.DEBUG` and is usable from `:ext-scratchpad` / `:ext-links`; the five earlier extensions keep
  `if (BuildConfig.DEBUG) Log.d`.
- **Scratch Pad** (arc 6 — `PAPER_SCRATCHPAD_PLAN.md`, `docs/extensions.md` §"ScratchPad (contract)"):
  the sixth point `SCRATCH_PAD` (`IScratchPad` — `begin` / `receiveInk` / `takeOutgoing` / `end`,
  `PaperStroke` / `InkBundle`) is the first **screen-owning** point (UI-rule tier 2): the extension owns
  `ScratchPadActivity`, the core **holds one bind for the showing** (`ExtensionBinder.hold` →
  `ScratchPadClient.open` = store pre-open → hold → `begin(store)` → launch with an
  `ActivityResultLauncher` only; `finish` = `end` → unbind → revoke in `finally`, from the result
  callback **and** the caller's `onDestroy`), the Activity checks its caller first thing in `onCreate`
  (`HostCallerCheck.enforceActivity` — `callingPackage` + signature; `am start` is refused), and **ink
  crosses only through the held service, never the Intent**. The store gained `putLarge` / `getLarge`
  (appended; `STORE_MAX_VALUE_BYTES` 4 MiB, `STORE_MAX_INLINE_BYTES` 512 KiB, `LargeValue` over
  `SharedMemory` via `SharedBytes`; `get` of a large stored value throws `STORE_VALUE_LARGE`). S0 ✅ 9a96c7a
  (contract, module, skeleton, debug "Probe scratch pad" — removed in S3). **S1 ✅ 98f58f6 — the screen +
  the two entry buttons** (`docs/scratchpad.md`): `ScratchPadActivity` = the notebook's shape from
  `:paper-screen` in the extension's own process (top bar Back · "Scratch Pad" · [Send]; bottom
  bar Pen · Eraser · Lasso … `<` n / N `>`; pages, saves and the 4 MiB full rule through `ScratchDocument`
  over `ScratchStore`; debounced 800 ms save + flush on leave / pause / **Back awaits the flush before
  finishing**, because the host's `end()` revokes the store right after the result); the notebook's
  top-bar Scratch Pad button (Tabler **`sketching`**, `IconNames.SKETCHING`; far right, immediately
  before the debug ⋯ since S3 — `notebook/ScratchPadFlow`, **`paper.releaseForHandoff()` immediately before the launch** — and
  the pad **`releaseForHandoff()` before every `finish()` back** (`finishWithHandoff`): the caller's
  `onResume` reclaim runs before the pad window's visibility close, and a late close from the other process
  tears the caller's live session down on BOOX)
  and the library's bottom-bar button after Recents (`library/ScratchPadLaunch`, no send target) — **both
  `GONE` unless a trusted `SCRATCH_PAD` extension is installed, re-discovered on every resume and after
  a failed open** (BOOX re-disables a sideloaded extension: "didn't respond" → check `pm list packages
  -d` first). The notebook's undo replay lives in `NotebookUndo` (`undo` / `redo`). **S2 ✅ — the two
  transfers** (`docs/scratchpad.md` §Transfers, `docs/notebook.md` §"Scratch Pad (arc 6)"): the core
  `scratch` ("Pad", `appliesTo = INK`, `SelectionActions.CORE_SCRATCH_ID`) selection action exists only
  while the extension is installed (`ScratchPadFlow.toolbarAction()`; `SelectionActions.merge` filters
  core actions by `appliesTo`) → placement sheet (New page / Current page) → caps (`MAX_TRANSFER_STROKES`
  10 000 / `MAX_TRANSFER_POINTS` 400 000) **before any bind** → `ScratchPadClient.open` → `send` (one
  `receiveInk` per `InkChunks` chunk on the held bind; `SCRATCH_PAGE_FULL` → `ScratchPageFullException`)
  → launch `openReceived` (the pad opens on the page, strokes selected + one `Pasted` on its stack);
  the pad's Send (page / selection) → `RESULT_SCRATCH_SEND` → `drainOutgoing` (`TransferCaps.Drain`) →
  `pasteStrokes` (fresh ids, **coordinates kept 1:1**, one `NotebookUndo.Action.Pasted`, left selected).
  **Both directions are copies; ink never rides the Intent; no id crosses.** The extension maps with its
  own `ScratchInk` (`:paper-screen` never sees `:extension-api`). **S3 ✅ froze the arc:** the
  `/code-review` of the whole range fixed, boundary-audit rows 28–32 + the re-walk of 1/6/7 for the held
  bind, rules 25–27 (§"Adding a screen-owning point (arc 6 pattern)") and the tier-2 recipe
  (§"Extension-owned screens (tier 2)") in `docs/extensions.md`, `docs/scratchpad.md` frozen; the
  notebook's Scratch Pad button moved to the **far right, immediately before the debug ⋯** (layout weight
  gap — the debug menu adds no spacer of its own); the pad's Send glyphs are Tabler **`pencil-down`**
  (`ic_send` gone); both debug probes ("Probe scratch pad", the pad's whole `ScratchDebugMenu` +
  `sizeSummary`) removed — the pad has no debug ⋯. The intermittent "sluggish drag of a
  just-transferred selection" (SNN) was **g-paper Ratta: the ownership guard is a process-local static, so
  the pad's late focus-loss / `release()` teardowns re-sent `enableFullUiAuto(false)` after the notebook's
  reclaim → slow-waveform drags** — fixed in g-paper 0.1.2 (the handoff drops the token). **On NA5C a second mechanism with the same
  signature: the Onyx app-scope fast-mode pin was PEN-only, and the send flow leaves the notebook on the
  lasso across the handoff → the reopen never re-pinned → unpinned drag frames until the next PEN arming** —
  g-paper 0.1.3 pins for any drawing tool (Paper pins 0.1.3). **Generic: no mechanism found; `tools/drag_capture.sh
  <serial>` within ~10 s of a sluggish drag on the MIP11 is the next step.** **Trap: both engines' ownership
  guards are per-process — a cross-process handoff must be a full teardown on the departing side; a fix
  goes to g-paper, never a host workaround.**
- **Links** (arc 7 — `PAPER_LINKS_PLAN.md`, `docs/extensions.md` §"LinkProvider (contract)"): the
  seventh point `LINK_PROVIDER` (`ILinkProvider` — the pick showing `beginPick` / `takeResult` /
  `endPick` + the one-shots `resolve` / `chromeOf` / `pushTrail` / `popTrail` / `clearTrail`;
  parcelables `LinkChoice` / `LinkDestination` / `CatalogEntry` / `TrailEntry`). **The core owns link
  structure** (`.soil` `TYPE_LINK` rows wrapping re-parented children, render, gestures, navigation,
  undo — L1/L4); **`NSE · Links` owns link meaning**: the row's `text` payload is opaque to the core
  (grammar `"L1|chrome|kind|notebookId|pageId"`, the extension's own `LinkPayload`), resolved into a
  typed `LinkDestination` at follow time and described by `chromeOf` at load (session cache, nothing
  extension-derived persisted — the heading precedent, L0 wizard Q4). The picker
  (`ACTION_LINK_PICKER_SCREEN`) is the second tier-2 screen (held bind: `LinkClient.openPick` =
  store pre-open → `ExtensionStoreBinder` + **`LinkCatalogBinder`** — the first host-implemented
  multi-method callback, a per-showing uid-gated lens (`LinkCatalogGate`) with `listFolder` /
  `listPages` real (current notebook via the live-session `LinkCatalogSource`, others by a read-only
  `SoilDatabase.open` sealed in `finally`) and the create half `UnsupportedOperationException` until
  L3 — → hold → `beginPick` → launch by `ActivityResultLauncher` only → `takeChoice` = drain +
  `endPick` + unbind + **revoke both binders** in one `finally`). The back-trail lives in the
  extension's host store (key `trail`, cap 50) behind the trail one-shots (store as in-parameter,
  pre-opened on IO before the bind). **L0 built** contract + skeleton + client + catalog binder + the
  debug ⋯ "Probe links" (removed in L5); the picker UI, link rows, follow + trail wiring are L1–L4.
  `TemplateProviderClient` (bind-per-operation, signature re-checked at bind, timeouts, unbind in
  `finally`, payload = mime + byte cap + exact requested size); **the core has no renderer**. No
  extension → no Template section, blank notebook. A render failure stays on the screen with a toast —
  never a silent Blank. Identity labels: `BLANK` | `<pkg>:<id>` | legacy `LINED`/… .
- **Extension store** (arc 2 / N0, `data/extstore/`, `docs/extensions.md` §"The extension store"): an
  extension's data lives in a **core-owned** SQLCipher key/value DB `Garden/<pkg>.db` under the global
  key (raw-key id `ext:<pkg>`), opened only by `ExtensionStores.open` (open-or-create — the **third
  named create entry point**, `docs/crypto.md` audit item 2) and reached by the extension only through
  an `ExtensionStoreBinder` the host mints **per bind, uid-bound, revoked in the unbind `finally`**;
  caps `ExtensionContract.STORE_*` (512 chars / 4 MiB per value since arc 6 — inline `put` / `get` ≤ 512 KiB, above that `putLarge` / `getLarge` / 50 000 keys). Never a key, path, or `File`
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
  extension modules:** `Slog` lives in `:paper-screen` (since arc 6 / S0 — before that in `:app`) and
  is reachable only from its consumers (`:app`, `:ext-scratchpad`); the five `:extension-api`-only
  extensions keep the recorded equivalent `if (BuildConfig.DEBUG) Log.d(...)` (never text — counts +
  durations).
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
  one exclusion rect** (`PaperChrome.blockAll`, like the "Opening…" popup); a failing capable
  provider = an honest dialog, nothing opens. `NotebookActivity`'s chrome geometry lives in
  `PaperChrome` (since arc 6 / S0 in `:paper-screen`; the file sits at the 800-line cap — new notebook-screen logic goes in a
  collaborator, not there). The C0 debug ⋯ "Probe contents" item was removed in C2 (the screen itself
  proves the path).
- **Toast vs. dialog:** a toast only confirms something that already happened ("copied"); anything
  the user must notice or act on — why a tap did nothing, a failure, a one-time download — is an
  `AlertDialog` (e-ink: a toast is easy to miss and reads as "broken"). The one helper is
  `Dialogs.problem(activity, title, message)` (`core/Dialogs.kt`, in `:paper-screen` since arc 6 / S0) — use it, don't hand-roll; the
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

Via the `device-build-install` skill (its **Paper** section): build/test commands, the
per-extension APK install lines, the BOOX freeze / `pm enable` sideload traps, and the three Paper
test-device serials (SNN `gpaper-ratta` · NA5C `gpaper-onyx` · MIP11 `gpaper-core`) all live there.

Debug launch: `adb -s <serial> shell am start -n com.symmetricalpalmtree.notesprout.dev/com.symmetricalpalmtree.notesprout.bootstrap.BootstrapActivity`
(BootstrapActivity is the launcher and the only thing that opens the index; every other screen
bounces there via `IndexGuard`.) The debug build's library ⋯ menu has "Show recovery key" and
"Forget cached key" (kills the process → next launch is the Unlock screen); the notebook screen's
debug ⋯ has "Recognize page (ML Kit)" (present only with `NSE · ML Kit` installed). The app's files are
readable from `adb shell` at `/sdcard/Android/data/<appId>/files/` (index + `Garden/`).

## g-paper version

Currently pinned: **0.1.3** (arc 6 / S3 — two "sluggish drag after a transfer" fixes: 0.1.2 Ratta
`releaseForHandoff()` drops the process-local ownership token + full teardown (345c2a8); 0.1.3 Onyx
applies the app-scope fast-mode pin for any drawing tool at the open / tool boundary, not PEN only —
a reopen on the LASSO after a handoff ran drags unpinned (3ac5404). 0.1.1 = arc 4 / H1:
`PaperListener.onSelectionTapped(x, y)`, e76e305). If a phase bumps
g-paper, update the version in `app/build.gradle.kts` and record it in the phase outcome.

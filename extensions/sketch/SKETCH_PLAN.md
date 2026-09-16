# SKETCH_PLAN.md — arc 43 "Sketch": `NSE · Sketch` (branch `sketch`)

The plan **and the ledger** for the raster-sketch extension, in the shape of the SN arc plans
(`apps/notesprout_sn/<ARC>_PLAN.md`) and `extensions/bible/BIBLE_PLAN.md`. Phases are appended to
the ledger at the bottom as they land; the reference doc once frozen is
`extensions/sketch/docs/sketch.md` beside this file.

**Status: Arc 43 — IN PROGRESS, K0 ✅ K1 ✅ K2 ✅ K3 ✅ K4 ✅.** Phases are lettered **K** (arc 37 "Bible" used B, arc 38
"Reference" used R, arc 39 "Lookup" used no letter of its own, arc 40 "Verses" used V, arc 41
"Cross references" used no letter of its own, arc 42 "Notes" used N — arc 43 "Sketch" uses **K**).

## Context

Notesprout SN pages are firmware-ink stroke pages. Paintsprout Onyx's raster experiment (BOOX,
g-paper 0.1.25–0.1.31) proved that a **pixel page with a graphite pencil and a rubbing eraser**
feels truer to paper for sketching ("raster rocks"). The user wants that surface in Notesprout SN
on Supernote: each notebook page may carry **one raster sketch beside its ink**, the way a page
carries a document — chosen at notebook creation, toggled per page, stored **inside the `.soil`**,
carried by page copy/cut/paste, exported and covered like everything else. Nothing on Ratta has
ever run g-paper's raster mode; that engine work lands in g-paper first. Paintsprout's raster
design (PNG on a child row, copy-and-submit saves, 64 px undo tiles under a byte budget, cover over
paper white) is host-side and device-neutral and is ported, not reinvented.

## Decisions (the user's, 2026-09-15 — binding; phase-start questions may not reopen them)

| # | Question | Decision |
|---|---|---|
| 1 | Creation | **Third radio: Handwritten / Text / Sketch.** A Sketch notebook opens into the sketch face; every page keeps its ink canvas and optional document; each page may have one sketch. The radio is exclusive (never Text + Sketch; if a foreign file carries both bits, TEXT wins, logged). |
| 2 | Where | **Extension APK `NSE · Sketch`** at `extensions/sketch` (`:ext-sketch`, the Bible include pattern) — the **tenth extension point** `ACTION_SKETCH` + `ACTION_SKETCH_SCREEN`, granted. Screen-owning tier-2 (the Scratch Pad's shape); **the host owns every `.soil` write**. |
| 3 | Pencil | **Paintsprout's one pencil, as is**: `StrokeStyle.PENCIL`, 1.2 px, `#505050`, pressure → darkness, no tilt, no width choice, no colour. |
| 4 | Eraser | **The rubbing eraser** (g-paper 0.1.30/31 `RasterRubbing` defaults, 12 px). Live lifting on Supernote is an engine measurement; end-of-sweep reveal is the fallback. |
| 5 | Export | **The sketch is its own page after the ink page** in PDF/PNG export. Document-source export unchanged. |
| 6 | Cover | **The sketch of the last-shown page** over paper white; the ink bake when that page has no sketch. |
| 7 | Page turns | **The sketch screen turns pages itself**; the notebook catches up to the page it ended on when the screen closes. No page insert/delete from the sketch face. |
| 8 | Ink bake | **A "Bring in ink" door on the sketch screen**: the page's bare strokes cross the bind (the pad's `InkChunks`) and are composited **as drawn (black pen)**, one undo entry. Link-wrapped and sticky ink excluded. |
| 9 | Toggle door | **`btnSketch` on the notebook's bottom strip, left of Bible** — a further granted exception to "bottom bars are pager-only"; Sketch notebooks only; `GONE` never disabled; mirrored in the collapsed overflow. The sketch screen's top bar carries **Show pages** back. |
| 10 | Paper | **Plain white always** under the sketch; no template crosses the seam. |
| 11 | Erase page | **Ink only** — Erase page never touches the sketch row. |
| 12 | Undo chrome | **Gestures only** (two-/three-finger taps via `PageGestures`); no arrows. |
| 13 | Engine owner | **Opus writes the g-paper Ratta phases on a Fable brief; Fable reviews each diff + the Nomad numbers before `publishToMavenLocal` and the SN re-pin.** |
| 14 | Recipe | Fable plans + orchestrates; **Opus** features; **Sonnet** scaffolding, XML, strings, docs, adb walks on the **Nomad** (`SN078D10012852`, `.dev` builds); **no Haiku; no code review**. Wizard-style phase-start questions, one at a time; explain, then ask. |

### Derived (reconciled from the three design passes — not re-asked)

- **Storage:** `SoilSchema.TYPE_SKETCH = "sketch"`, `SKETCH_ORDER = -1`, parent = page id, `blob` = PNG
  (ARGB_8888, transparent where empty, exactly page `width`×`height`), one live row per page, first save
  `upsert` with a fresh id, later saves `setBlob` in place (`createdAt` kept), **minted on first save never
  on open**, soft-deleted with its page. No `SOIL_VERSION` bump, no entity change (Room identity hash =
  the Paper format contract) — a second DAO like `DocumentDao`. Blank (all transparent) = **absent row**;
  the wire form for "clear" is an empty byte array. IHDR exact-size guard **before any decode**; a refused
  row is soft-deleted, never overwritten. `WATCH_BYTES` 4 MB (log only); **`MAX_BYTES` 6 MiB is a hard
  refusal** (the SQLCipher cursor window — a PNG written above it can never be read back; the one
  deliberate deviation from "never refuse", written down).
- **Index/meta:** `NotebookFlags.SKETCH = 8` + `notebook_meta.sketch` (additive), sourced from the index
  bit at every meta rebuild (the `TEXT_DOCUMENT` wipe-trap rule). `NotebookKind { HANDWRITTEN, TEXT, SKETCH }`.
- **Seam:** `ExtensionContract.API_VERSION` 16 → **17**; `SketchContract.MIN_API_VERSION_FOR_SKETCH = 17`
  as a birth floor in `MIN_API_VERSIONS` (no existing floor moves). `ISketch { begin(ISketchHost host); end() }`
  — **no store** (nothing to persist: the bookmark is the notebook's `refId`, undo is in-session, one tool).
  `ISketchHost` = the second host-side stub (`IDocumentHost`'s recipe: minted per showing, uid-gated in every
  method, revoked on unbind): `current()`, `requestPage(direction)`, `readSketchChunk(i)`,
  `saveSketchChunk(pageKey, i, bytes, last)`, `requestInk(pageKey): chunkCount`, `readInkChunk(i)`.
  **PNG crosses chunked both ways** at `SKETCH_CHUNK_BYTES` = 512 KiB (`ByteChunks`, pure, the `TextChunks`
  recipe; empty image = one empty chunk = blank). A save is accepted for **any live page** of the open
  notebook (identity by key, no mode routing). Show-pages is a **result code** `RESULT_SKETCH_SHOW_PAGES`,
  not a stub method; Back is `RESULT_CANCELED`. Nothing rides the Intent but `EXTRA_CHROME_HIDDEN`.
- **Reads that must not see the sketch:** `SoilDao.childrenOf` (untyped, blob-inclusive; feeds export
  bake / previews / labels / link picker) gains `AND type != 'sketch'`. `liveDescendantIds` gains `'sketch'`
  at the page level (copy/cut/paste/delete/undo carry it). Erase page uses a new `liveErasableIds` =
  descendants minus `'sketch'` (decision 11). Not in `liveContentIds`, not in the document staleness
  whitelists. `ORDERED_TYPES` unchanged (never in an objects paste).
- **Clipboard cap:** keep `ClipEnvelope.MAX_BYTES` 6 MB; when a refused envelope carries a sketch row the
  dialog says so (`clip_too_large_sketch`). Cross-notebook paste into a non-Sketch notebook: the row travels
  and is never shown.
- **Screen base:** lift the store-agnostic chrome/handoff half of `:ext-ink`'s `InkScreenActivity` into
  `PaperScreenActivity` (stays in `:ext-ink` — it reads `EXTRA_CHROME_HIDDEN`); `InkScreenActivity` extends
  it unchanged in behaviour. `SketchActivity : PaperScreenActivity`. `:ext-sketch` depends on
  `:extension-api` + `:sn-screen` + `:ext-ink` (`InkWire.toStrokes`).
- **`:sn-screen` additive changes:** `UndoRedoStack<A>(cost: (A)->Long = {0}, budgetBytes = MAX)` evicting
  the oldest costed entry never the newest + `pushUndoBeneath`; `CollapsedChrome(tools = ORDER)`;
  `PaperToolbar(btnLasso: ImageButton? = null)`; `suspend PenIdle.awaitIdle` lives in `:ext-ink` (coroutines).
- **Sketch screen tools/chrome:** `pageMode = RASTER` once before the first page; pencil · eraser only;
  `scribbleEraseEnabled = false`, `smartLassoEnabled = false` (hatching is not a gesture); top bar Back ·
  Pencil · Eraser · Bring in ink · Show pages; bottom bar ‹ n / m ›; double-tap chrome + collapsed corner
  via `:sn-screen`; frame-silence ledger in `docs/sketch.md`.
- **Saves:** `rasterDirty` from `onRasterChanged` (guarded by `loadingRaster` until g-paper makes
  `loadPageRaster` silent); 3 s debounce through the pen-idle gate; flush on page turn, `onPause`, Back,
  Show pages; each save = main-thread `getPageRaster()` copy → PNG on IO → chunked push under one lock;
  dirty clears when the copy is taken; a failed push parks by key (`PendingPngPark`; key mismatch = drop)
  and `end()` re-pushes the park; **Back/Show-pages await the flush**; a failed final flush shows a
  two-button dialog (Try again / Leave anyway) — pixels have no other copy.
- **Undo:** `SketchEdit.RasterChanged(pageKey, pageIndex, tiles)` on a 64 px grid, each cell read once per
  contact (`RasterEditBuilder`, port), opened at the first `onRasterWillChange`, closed at `onPenLifted`
  (the bake door closes its own); budget 48 MB; replay = turn page first if needed → `awaitIdle` →
  generation re-check (put back beneath if a mark landed) → `swapPageRaster` → dirty → **never reload**.
- **Export:** bundle = ink page, then its sketch page if any, …, endnotes; `bundlePositions` feeds endnote
  `fromPage` and progress; `pageTitles` one per bundle page (`"<title> sketch"` — wording is the user's);
  `SketchRaster.toWebp` composites the guarded PNG over white into RGB_565 with one page bitmap alive.
- **Cover:** `SketchCover.render` from the **row** (after `end()`'s save landed on the writer) for
  `lastFaceEndedOn ?: hooks.targetPageId ?: displayedPageId`; ink bake only when the canvas is loaded.
- **Compaction:** no change — a soft-deleted sketch row purges at close; a purged page cascades.
- **g-paper versions:** Phase 19 → **0.1.32**, Phase 20 → **0.1.33** (Paintsprout's abandoned arcs 3/4
  reserved those numbers on paper only; note it in `ONYX_PLAN.md`). Paintsprout (0.1.31, Onyx) must
  compile and its 203 JVM tests stay green at every core publish.

## Phases (letter **K**; one per session; ⬜ 🔄 🧪 ✅; each ends commit + push; ledger below)

| Phase | Owner | What lands | Gate |
|---|---|---|---|
| **K0 — Pin + scaffold** ✅ (`c3fc1847`, `29fc48dc`) | Sonnet | Branch `sketch` from `main`; `extensions/sketch/SKETCH_PLAN.md` (this plan + ledger); SN pin `sn-screen/build.gradle.kts` 0.1.28 → **0.1.31** (zero `gpaper-ratta` lines change; carries `RasterPatch`/`swapPageRaster`/`RasterRubbing`); g-paper `PLAN.md` Phase 16 🧪 → ✅ with the arc-29 Nomad record (Onyx half still untested); stale `docs/integration-guide.md`/`README.md` version lines; `apps/notesprout_sn/CLAUDE.md` pin sentence. | Build + all JVM tests green; Sonnet adb smoke of notebook/pad/calendar; user hand: ink, point eraser, lasso, lasso eraser, pad Send both ways; `gfxinfo` writing-minute baseline. |
| **K1 — g-paper Phase 19 → 0.1.32 "Ratta and the raster page"** ✅ (g-paper `8bb2aa1`) | Opus (Fable reviews diff + numbers before publish) | Core: `protected open val rasterEraseRedrawIntervalMs` read by `throttledEraseRedraw` (const stays 16). Ratta: override (initial 100 ms; candidates 16/60/100/250/end-only via a demo-only system property); `RattaEmr.penSize(style, widthPx)` pure with `EMR_MIN_HAIRLINE` (start 120) for `PENCIL`, others keep 200; note at the "needs no override" comment naming `loadPageRaster`/`swapPageRaster` (the overlay is already released by `clearForContentSwap` under the swap law — verified, not a gap). **The g-paper demo gains a Raster toggle** (pencil + rubbing eraser) as the measurement vehicle. Tests `RattaEmrTest` ×4. Docs `api.md`/`CLAUDE.md`/`PLAN.md`. | Nomad (demo): M1 cadence per candidate (`gfxinfo` + the hand: lifts live? flicker?); M2 pencil preview width at EMR 120/150/200 vs the baked hairline (photo + screencap); M3 eraser-end pressure real (temporary log); M4 undo-swap flash (accept, record); M5 page-wide swap ms. Phase-start Qs: cadence candidates; whether to try `DARK_GRAY` preview. |
| **K2 — The seam** ✅ | Opus (Sonnet: `ExtensionContractTest` pin, docs rows) | `:extension-api`: `SketchContract`, `ISketch.aidl`, `ISketchHost.aidl`, `SketchPageState` (+`requireValid`), `ByteChunks`, `PngHeader` (pure, both sides), `API_VERSION` 17 + floor; host manifest `<queries>` both actions. `:sn-screen`: `UndoRedoStack` budget + `pushUndoBeneath`, `CollapsedChrome(tools)`, `PaperToolbar(btnLasso?)`. `:ext-ink`: `PaperScreenActivity` extraction + `PenIdle.awaitIdle`. Tests: `ExtensionContractTest`, `SketchContractTest`, `ByteChunksTest`, `PngHeaderTest`, `SketchPageStateTest`, `UndoRedoStackTest` (+6). | `:extension-api:testDebugUnitTest` green (the pin trap); all modules compile; Sonnet adb regression of pad + calendar (the base-class move); user hand: pad/calendar ink + handoff. |
| **K3 — Host data, creation, routing** ✅ | Opus (Sonnet: radio XML/strings) | `TYPE_SKETCH`/`SKETCH_ORDER`; `SketchDao` (`sketchDigest`, `sketchFor`, `pagesWithSketch`) + `sketchDao()`; `SketchRows` (port of `RasterRows`); `SketchRepository` (`has/get/save/clear`, guard, watch, cap, unchanged-bytes skip); `NotebookSession.kind/isSketch`, `writeSketch`, `readSketch`; `NotebookKind`; `NotebookFlags.SKETCH`, `NotebookMeta.sketch` + **every mirror site** (`NewNotebookActivity`, `TextDocumentCreate`, `IndexRepository.createNotebook/importNotebookRow`, `NotebookImport.refreshMeta`, `ImportFlow`, `ExportArtifact.stampExportedAt`, `NotebookSession.refreshMeta`); third radio + `KEY_KIND`; `FaceRouting` generic table + `TextDocRouting` facade (its tests untouched) + `SketchRouting`; `SoilDao.childrenOf` exclusion, `liveDescendantIds` + `'sketch'`, `liveErasableIds` for Erase page; `FakeSoilDao` mirrors. Tests: `SketchRowsTest`, `SketchRepositoryTest`, `NotebookKindTest`, `FaceRoutingTest`/`SketchRoutingTest`, `SoilDaoKindListsTest` (+3), `DocumentWhitelistTest` (+1), `PageClipTest` (+1), `SoilCompactorTest` (+2). | JVM green; Sonnet adb: create a Sketch notebook (radio), `.soil` meta shows `sketch:true`, index flag 8, `.soil` export/re-import keeps it. (No face yet — a Sketch notebook loads the canvas.) |
| **K4 — Host entry, hooks, door, cover** ✅ | Opus (Sonnet: `ic_brush` Tabler, strings, layout) | `ExtensionRegistry.sketch`; `SketchHostSession` (pure: read window, ink window, save accumulator); `SketchHostBinder` (gate first, funnel); `SketchClient` (hold, `begin(host)`, `end()` ≤ 15 s, revoke in `finally`); `SketchHostHooks` (`pages`, `loadPage`, `commit` → `writeSketch`, `requestInk` → drain + `TransferCaps` + `InkChunks`, bare strokes only); `SketchEntry` (= `DocumentEditorEntry` + `ExtensionScreenEntry`'s paper pieces: **drain → open → chrome extra → `dismissFloatingChrome` → `endTransformIfRunning` → `releaseForHandoff` → launch**; result: pop → chrome → `resumeDrawing` → finish job → `onClosed`); `NotebookActivity`: `openIntoSketch`, `sketchShowingEnded` (CATCH_UP / LOAD_CANVAS / SEAL_TO_LIBRARY), `replayAbove` `Surface.SKETCH`, reconnect keys, `close()` joins, watchdog; `btnSketch` on the bottom strip left of Bible + collapsed overflow mirror; `SketchCover` + `captureCover` branch + `saveLastOpened(lastFaceEndedOn)`. Tests: `SketchHostSessionTest` ×10, `ReplayPlanTest` (+1). | JVM green; button GONE for non-Sketch notebooks and with no extension (walk after K5). |
| **K5 — NSE · Sketch, the screen** ⬜ | Opus (Sonnet: module scaffold, manifest, gradle, icon copy, layout, strings) | `extensions/sketch` module per the layout in the design notes: `SketchApplication` (`RattaEngine.register()`), `SketchService` (`begin/end`, `HostCallerCheck`, park re-push in `end()`), `SketchSession`, `SketchActivity` (lifecycle table, `swapTo`, listener ignoring `onStrokeCommitted`, `PageTurn` pure), `SketchSaver` + `SketchSaveGovernor` + `PendingPngPark`, `RasterTiles`/`RasterEditBuilder` (port), `SketchEdit`, `RasterImage`, `InkBake`, dialogs, a **debug-only "fill test pattern" door** so adb can produce a non-blank save. `settings.gradle.kts` include. Tests: `RasterTilesTest` (port), `SketchSaveGovernorTest`, `PendingPngParkTest`, `PageTurnTest`, `InkBakeTest`, `CollapsedToolsTest` (+1). | **First Nomad walk.** Sonnet adb: Sketch notebook opens into the face; `am start` refused; chrome/collapsed; page turns + bounds; Bring in ink (screencap shows strokes) / "No ink"; save log ≤ 1 s; Back → catch-up; Show pages → canvas; reopen pixel-identical; `am kill` host behind the face → reconnect + save lands; sleep/wake; `meminfo`. User hand checklist: pencil feel + preview, rubbing, undo/redo gestures incl. across a turn and the put-back case, bake then undo twice, Back mid-hover. Phase-start Qs: save-failed dialog wording; pencil glyph (`ic_pen` vs new `ic_pencil`). |
| **K6 — g-paper Phase 20 → 0.1.33 "A mark says where it landed"** ⬜ | Opus (Fable reviews) | Core only: `RasterDirty.along(points, width, pageW, pageH, maxSpanPx = 256, maxRects = 64)` — per-segment will/changed rects for `commitCapturedStroke` and `addStrokes` in RASTER; `loadPageRaster` silent (drop the extension's `loadingRaster`, note Paintsprout's dead guard in its watch list); `PaperListener` KDoc. Tests `RasterDirtyTest` (+8). Paintsprout `./gradlew test` 203 green before publish; SN re-pin. | Nomad: corner-to-corner hairline — cells read / entry bytes / pen-up main-thread ms before vs after; undo still an involution (screencap diff zero). |
| **K7 — Clipboard, erase, export** ⬜ | Opus | `clip_too_large_sketch` wording in `doCopy`; Erase page via `liveErasableIds` (undo/redo replay by ids unchanged); `ExportRender`: `PageBake.hasSketch`, interleaved bundle, `bundlePositions`, endnote `fromPage`, `pageTitles` per bundle page (`ExportNaming` sketch stem), `SketchRaster.toWebp`, page-scope export carries its sketch; compaction KDoc. Tests: `ExportRenderPlanTest` (+5), `ExportRenderEndnotesTest`, `ExportNamingTest`, `ClipEnvelopeTest` (+1). | Sonnet adb: copy/paste page → face shows the sketch; Erase page leaves it; Delete → Undo; PDF page count = pages + sketches; page-scope export = 2 pages; PNG names carry the suffix; close after erase shrinks the file; library card shows the sketch. Phase-start Q: the sketch page's name suffix. |
| **K8 — Docs + freeze** ⬜ | Sonnet (Fable reads) | `extensions/sketch/docs/sketch.md` (as-built: data model, seam, screen, saves, undo, failure table, frame-silence ledger, traps, Nomad numbers); `docs/extensions.md` (tenth point, API 17 ledger, boundary-audit rows 64–67, the store-less held bind); `docs/notebook.md` (the door, the second bottom-bar exception, `liveErasableIds`), `docs/clipboard.md`, `docs/export.md`, `docs/library.md` (third radio), `docs/sn-screen.md`, `docs/document.md` (FaceRouting note); both `CLAUDE.md` files (tenth point granted, module count 16, pin 0.1.33, bottom-bar exceptions, the 6 MiB refusal); g-paper `PLAN.md`/`CLAUDE.md`; `ONYX_PLAN.md` version-number note; memory file; `versionName` lockstep check; `SKETCH_PLAN.md` ledger closed. | User's final Nomad hand walk; merge `sketch` → `main` `--no-ff`, delete branch, push. |

Order: K0 → K1 → K2 → K3 → K4 → K5 → K6 → K7 → K8. K2 may start against 0.1.31 while K1's walk is pending;
K6 must land before any Manta walk (budget arithmetic at 19.7 MB pages), not before the first Nomad feel walk.

## Critical files

- g-paper: `gpaper-core/.../canvas/CanvasPaperView.kt` (cadence seam ~L1572–1598; per-segment rects ~L1222; silent load ~L525), `gpaper-core/.../geometry/RasterDirty.kt`, `gpaper-ratta/.../RattaPaperView.kt` (~L201–213 EMR/pen code, ~L315 redraw guard, ~L603 erase contact), `demo/` (raster toggle), `PLAN.md`, `gradle.properties`.
- SN seam: `extension-api/.../ExtensionContract.kt` (+ `SketchContract.kt`, `ISketch.aidl`, `ISketchHost.aidl`, `ByteChunks.kt`, `PngHeader.kt`), `extension-api/src/test/.../ExtensionContractTest.kt`.
- SN host: `data/soil/SoilSchema.kt`, `SoilDao.kt` (`childrenOf`, `liveDescendantIds`, new `liveErasableIds`), new `SketchDao.kt`/`SketchRepository.kt`, `SoilDatabase.kt`, `data/index/ObjectEntity.kt`, `data/soil/NotebookMeta.kt`, `library/NewNotebookActivity.kt` + `activity_new_notebook.xml`, `notebook/NotebookSession.kt`, `notebook/NotebookActivity.kt`, `notebook/TextDocRouting.kt` (→ `FaceRouting`), `extension/DocumentEditorEntry.kt` / `DocumentHostBinder.kt` / `DocumentHostSession.kt` / `DocumentEditorClient.kt` (templates), `notebook/DocumentHostHooks.kt` (template), `export/ExportRender.kt`, `notebook/CoverSnapshot.kt`, `res/layout/activity_notebook.xml`, `AndroidManifest.xml` `<queries>`, `data/prefs/Surface.kt`.
- Shared: `sn-screen/.../notebook/UndoRedoStack.kt`, `CollapsedChrome.kt`, `PaperToolbar.kt`; `ext-ink/.../InkScreenActivity.kt` (→ `PaperScreenActivity`).
- Extension: `extensions/sketch/**` (new; copy `ext-soil`'s launcher icon byte-identical; `build.gradle.kts` from `extensions/bible` minus `noCompress`, plus `:ext-ink`), `apps/notesprout_sn/settings.gradle.kts`.
- Ports from Paintsprout: `apps/paintsprout_onyx/.../sketchbook/{RasterTiles,RasterRows,RasterImage,UndoRedoStack,Edit,SketchbookActivity(showPage/flushRasterSave/applyEdit)}.kt`.

## Verification

- **JVM at every phase:** `./gradlew test` in `apps/notesprout_sn` (1662 `:app` / 3097 total at the start) and
  `~/git/g-paper` (207 core); `:extension-api:testDebugUnitTest` at the API bump; Paintsprout Onyx
  `./gradlew test` (203) before every core publish.
- **Nomad only** (`SN078D10012852`), `.dev` builds, `pm disable-user` the release packages for the walk and
  re-enable after; `am force-stop` the host first then the extensions in one shell command. adb cannot
  draw: Sonnet drives chrome, turns, Bring-in, saves, kills, exports, screencaps (committed raster is
  capturable); the user's hand judges pencil, rubber, undo gestures. Re-drive every agent FAIL by hand.
- **Measurements recorded in the ledger:** cadence `gfxinfo`, preview width, eraser-end pressure, save
  encode+push ms, page-turn ms, `meminfo` (expect < Paintsprout's 255 MB PSS at the Nomad's 10.5 MB page),
  per-entry undo bytes before/after K6, PDF page counts, `.soil` growth per sketched page.

## Open items parked as futures (each needs a fresh decision — not this arc)

Page append from the sketch face · a `DARK_GRAY` pencil preview on Ratta · regional refresh on Supernote
(no firmware transaction exists) · a Sketch-notebook card glyph · the Manta walk.

## Working protocol

Summarized from `apps/notesprout_sn/CLAUDE.md`'s maintenance protocol and this plan's recipe
(decision 14):

1. **One phase per session.** A session opens a phase, lands it, gates it, and appends its Outcome
   to the Ledger below before ending — never straddle two phases in one session.
2. **Read three files at the start of every phase:** this file (the plan + ledger — read the whole
   Ledger so far, not just the phase table), `apps/notesprout_sn/CLAUDE.md` (standing rules, the
   extension-point recipe, the module roster), and `~/git/g-paper/CLAUDE.md` (host-responsibilities
   rules) when the phase touches the engine.
3. **Wizard-style phase-start questions, one at a time.** Where a phase table row lists
   "Phase-start Qs," ask them before writing code — explain the trade-off, wait for the answer, then
   ask the next. The 14 locked decisions above are never reopened.
4. **Fable reviews every g-paper engine diff** (K1, K6) — the Nomad numbers included — before
   `publishToMavenLocal` and the SN re-pin. Opus writes those phases on a Fable brief; Fable never
   writes engine code itself in this arc.
5. **Sub-agents never run git.** No branch, add, commit, or push from inside a delegated task —
   only the orchestrating session commits, and only after a phase gate is green.
6. **Walks happen on the Nomad only** (`SN078D10012852`, `.dev` builds) — never the Manta before
   K6 lands (the budget arithmetic at 19.7 MB pages needs the raster-dirty work first). adb drives
   everything it can (chrome, turns, saves, kills, exports, screencaps of committed raster); the
   user's own hand judges pencil feel, the rubbing eraser, and undo/redo gestures — every agent
   "pass" on a hand-only item gets re-driven by the user before the phase is called done.
7. **Commit + push only when the phase's gate is green** — JVM tests green, the module(s) that
   changed assemble, and (from K5 on) the Nomad walk for that phase has landed. A red gate blocks
   the commit; fix forward, never "adjust the test to match."

## Ledger

Each phase appends an Outcome here when it closes: what landed, what was measured, what the hand
said, and the commit.

### K0 — Outcome (2026-09-15)

**Landed** (`c3fc1847`; g-paper `5d14b6f`). Branch `sketch` from `main`; this file; `:sn-screen`'s
g-paper pin 0.1.28 → **0.1.31** (raster undo swap, rubbing eraser, alpha-3 fix — zero `gpaper-ratta`
lines changed between the two); g-paper `PLAN.md` Phase 16 🧪 → ✅ on arc 29's Nomad record, Phase 19
pencilled in at 0.1.32 (Paintsprout's paper reservations of 0.1.32–0.1.34 noted free), stale 0.1.22
coordinates in `README.md` / `docs/integration-guide.md` fixed. Build green; **1717 `:app` / 3174 JVM
tests** (the plan's 1662 / 3097 predated arcs 41–42).

**Measured.** Sonnet's adb walk on the Nomad (`.dev` host + all ten `.dev` extensions, no release
packages present): 10/10 — engine line `Creating PaperView with engine 'ratta'`, eraser sub-bar / lasso
/ pen toggling, finger page flip both ways, Scratch Pad / Calendar / Document open-and-back with no
`SecurityException` and no `FATAL`; idle `gfxinfo` 0 frames / 60 s (the writing-minute baseline is
owed by K1's hand walk).

**The hand.** Waived on the user's call ("Please close K0") — the engine diff carries no Ratta line,
and K1's walk needs the hand regardless.

**Next.** K1 — g-paper Phase 19 → 0.1.32, Opus on a Fable brief, Fable reviews before publish.

### K1 — Outcome (2026-09-15)

**Landed** (g-paper `8bb2aa1`, Phase 19 → **0.1.32**, published to mavenLocal by the user; SN
re-pinned in `sn-screen/build.gradle.kts` + the two `CLAUDE.md` pin sentences). Opus wrote the
engine on Fable's brief, Fable reviewed every diff before the commit. Core: `rasterEraseRedrawIntervalMs`
(`protected open val`, `RASTER_ERASE_REDRAW_END_ONLY` sentinel, `finalizeEraseRedraw` still presents
once) and **`bakePressure(style, pressure)`** (`protected open`, applied only where a raster page is
written — `compositeIntoRaster` — so stroke mode and every host's `Stroke` keep the measured
pressure; identity default copies nothing). Ratta: `RattaEmr.penSize` (pure, `RattaEmrTest` ×5) with
`EMR_MIN_HAIRLINE` 120 for `PENCIL`; the pencil **bakes at a constant pressure 0.5 under a
`DARK_GRAY` needle preview**; cadence **16 ms**; `livePenCode` back to NEEDLE for PENCIL. `RattaTuning`
(public, "a measurement door, not host API") holds the four measured values as its defaults until K8
removes it. The "needs no override" comment now names `loadPageRaster`/`swapPageRaster` — and the
reason for the swap is **not** the planning note's: an undo swap has no `clearForContentSwap` in
front of it; `redrawCommitted`'s own `pendingBake` guard (record → `clearAll` → invalidate → ladder)
is what covers it. Demo: Raster toggle (PENCIL 1.2 px `#505050`, rubber 12 px, Pen/Eraser only,
gestures off), host-owned 64 px tile undo/redo, "Swap pg" door, four `debug.gpaper.*` props read via
`getprop` at `onCreate`. Onyx untouched. g-paper 207 core / 12 ratta; **Paintsprout Onyx 203 green
against 0.1.32** (temporary pin, reverted — its own move is its own decision); SN 1717 / 3174.

**Measured** (Nomad, demo `.debug`, Sonnet-free — Fable drove adb, the user's hand judged):

| M | Result |
|---|---|
| M1 cadence | 100 ms: 113 frames / 20 % janky / p50 13 p99 30 — "fine". 60 ms: 162 / 22 % / same — "even better". **16 ms: 756 / 82 % / p50 20 p90 28 p99 40, ~2800 batches — "I really like the 16 ms… this eraser works better on Ratta hardware than it does on Onyx."** 250 / end-only not walked. The frame count is far worse and the hand is right: an erase contact releases the overlay at `ACTION_DOWN`, so there is no accumulating masking cost — only the panel's own update, and the Nomad keeps up. |
| M2 pencil width | EMR 120 renders and matches the baked hairline ("line widths seem good"). 150/200 not needed. |
| Pencil tone | Black needle "way off" from the bake (a solid line vs pale grain). DARK_GRAY "an improvement". GRAY tried. INK pressure code "not really seeing a difference — a lightly drawn line is live rendered too dark". **The firmware paints one tone per pen, so no preview can track a soft touch; the user revised decision 3 for Ratta only: the bake ignores pressure** (constant 0.5). With DARK_GRAY: "spot on… really good", confirmed off-device in a Mac screencap at 3×. |
| M3 eraser pressure | **Real**: 2852 batches, 0.06–0.49, median 0.24. |
| M4 undo swap | 10–14 tiles, 14–20 ms; the bake-handoff flash noticed, accepted. |
| M5 page swap | 1404×1685 page = 9.46 MB; whole-page swap 79–126 ms, read 1–53 ms; demo PSS 78 MB. |

**Traps found.** (1) `monkey -p … LAUNCHER` does not bring the demo forward when SN's `.dev`
notebook is resumed — use `am start -n …/.MainActivity`. (2) The Nomad's demo page is
1404×1685, not the 1860×2480 the g-paper docs quote for 18 MB — budget arithmetic for K5/K6 should
use 9.5 MB here (the Manta is the 19.7 MB case). (3) `publishToMavenLocal` is refused by the
session's permission classifier — the user runs it by hand.

**Decisions taken this phase (the user's, binding):** cadence ladder as planned; DARK_GRAY tried and
kept; INK pressure preview tried and dropped; **Ratta bakes PENCIL at a constant pressure (0.5)**
— decision 3's "pressure → darkness" stands on BOOX/Paintsprout only; cadence frozen at 16 ms.

**Next.** K2 — the seam (`:extension-api` 17, `ISketch`/`ISketchHost`, `ByteChunks`, `PngHeader`,
`UndoRedoStack` budget, `PaperScreenActivity`). K5's screen must set nothing in `RattaTuning`
(the defaults are the measurements).

### K2 — Outcome (2026-09-15)

**Landed.** `:extension-api` **17**: `SketchContract` (both actions, `MIN_API_VERSION_FOR_SKETCH`
17 as a birth floor in `MIN_API_VERSIONS`, `SKETCH_CHUNK_BYTES` 512 KiB, `MAX_BYTES` 6 MiB — the
one written-down hard refusal — `WATCH_BYTES` 4 MB, `MAX_CHUNKS` 13, `RESULT_SKETCH_SHOW_PAGES` 1,
`SKETCH_TOO_LARGE` / `SKETCH_BAD_PNG` typed refusals; page key and PAGE_PREV/NEXT alias
`DocumentContract`'s), `ISketch` (`begin(host)` / `end()`, **no store**), `ISketchHost` (`current`,
`requestPage`, `readSketchChunk`, `saveSketchChunk(pageKey, i, bytes, last)`, `requestInk` →
chunk count with **0 = no bare ink, no exception**, `readInkChunk`), `SketchPageState` (7 fields —
key, index, count, width, height, bytes, chunks — validated in `init`, so the plan's `requireValid`
is unnecessary and dropped; `chunksFor` pins chunks to bytes), `ByteChunks` (`chunk` / `countFor` /
`join`; empty → one empty chunk), `PngHeader` (pure big-endian IHDR parse, `size` / `matches`,
no Android classes). Host manifest `<queries>` both actions. `:sn-screen`: `UndoRedoStack(cost,
budgetBytes)` with `undoBytes`, Paintsprout's `evictForBudget` ported exactly (oldest costed entry,
never the newest), `pushUndoBeneath(action, sinceGeneration)`; `CollapsedChrome(tools = ORDER)`
last with a default; `PaperToolbar(btnLasso: ImageButton?)`. `:ext-ink`: `PaperScreenActivity`
lifted out of `InkScreenActivity` (855 → 428 lines; chrome, collapsed bar, eraser sub-bar,
touch dispatch, chrome-hidden state, lifecycle, `finishWithHandoff` moved down; store, strokes,
undo, selection, send stayed up; hooks `extraFloatingRects` / `extraFloatingContains` /
`onScreenPaused` / `onScreenDestroyed` / `collapsedTools`), **zero edits to the pad or calendar
subclasses**; `PenIdleAwait.kt` (`suspend PaperView.awaitPenIdle()`; `:sn-screen` has no
coroutines, so it lives in `:ext-ink` as planned). Opus wrote it on Fable's brief; Fable read
every seam file and found one defect — a literal NUL byte in `SketchPageState`'s key check and
its test instead of the `'\u0000'` escape — fixed before the commit.

**Tests.** `:extension-api` 238 → **281** (+43: `SketchContractTest` 10, `ByteChunksTest` 10,
`PngHeaderTest` 12, `SketchPageStateTest` 11, the pin), `:sn-screen` 101 → **109** (+8),
`:ext-ink` 52; whole tree green, **1717 `:app` / 3225** in the SN root (K1's 3174 + 51) — 3380
counting `extensions/bible`'s 155. `assembleDebug` all 11 APKs.

**Measured** (Nomad, Sonnet's adb walk, all eleven `.dev` packages, no release packages present):
notebook / Scratch Pad / Calendar / Document editor each open on `engine 'ratta'` and close on
`firmware ink released for handoff … result=0`; pad chrome + eraser tap, calendar Next ×2 (adb
`input swipe` does not page-turn on Ratta — known); every point discovered at API 17 (`1 provider(s)
of 1 candidate(s)`), no refused / older-host / mismatch wording; logcat sweep zero `FATAL` /
`SecurityException` / `IllegalStateException`; idle `gfxinfo` 0 frames / 30 s; PSS host 77.5 MB,
pad 41.8 MB, calendar 50.2 MB. The kill-host-behind-the-pad step was not in K0's recipe and was
skipped, not guessed.

**Traps found.** (1) A stale incremental-dex snapshot still pointing at the pre-rename
`apps/notesprout_ratta/…` path broke `:ext-calendar:mergeLibDexDebug` the first time a class was
deleted from `:ext-ink`; `./gradlew :ext-ink:clean` clears it — expect it again on the next class
deletion in a long-lived module. (2) A NUL inside a Kotlin char literal compiles and passes; only
`cat -v` shows it — check new `require` lines against `DocumentPageState`'s escapes.

**The hand.** Nomad, the user, 2026-09-15: pad write + point-erase + Back, calendar write + finger
week flip + Back, chrome hide + collapsed knob in both — **"all pass"**. K2 closed (`82ff9207`).

**Next.** K3 — host data, creation, routing (`TYPE_SKETCH`, `SketchDao` / `SketchRepository`,
`NotebookFlags.SKETCH` + every meta mirror site, the third radio, `FaceRouting`, `childrenOf`
exclusion + `liveDescendantIds` + `liveErasableIds`).


### K3 — Outcome (2026-09-15)

**Landed.** `SoilSchema.TYPE_SKETCH` (`"sketch"`, the seventh additive row type) + `SKETCH_ORDER`
−1; `SketchDao` (`sketchDigest` blob-free, `sketchFor`, `pagesWithSketch`) + `sketchDao()`;
`SketchRows` (the `RasterRows` port — `toRow` / `pngBytes` / `fitsPage` over K2's `PngHeader`;
`pngSize` / `isRaster` / `flagsFor` deliberately not ported); `SketchRepository` (`has` / `get` /
`save` / `clear`: IHDR guard both ways, a refused **stored** row soft-deleted never overwritten,
`SKETCH_TOO_LARGE` / `SKETCH_BAD_PNG` thrown with the contract's exact strings and nothing
written, `WATCH_BYTES` logged, digest-first unchanged-bytes skip, pixels never logged);
`NotebookFlags.SKETCH = 8` + `NotebookKind { HANDWRITTEN, TEXT, SKETCH }` (`of` — **TEXT wins
when both bits are set**, `conflicting`, `flagBits`, `fromMeta`, `metaConflicting`; pure, so the
two reading sites that can meet a foreign row — `NotebookSession.open` and `ImportFlow` — log
the conflict); `notebook_meta.sketch` (last, after `textDocument`) sourced from the index bit at
**every** mirror site (`NewNotebookActivity`, `TextDocumentCreate`, `IndexRepository.createNotebook`
/ `importNotebookRow` — both now take a `kind`, `NotebookImport.refreshMeta`, `ImportFlow`,
`ExportArtifact.stampExportedAt`, `NotebookSession.refreshMeta`); the third radio `typeSketch`
(exclusive, `KEY_KIND` saved by name); `NotebookSession.kind` / derived `isTextDocument` +
`isSketch`, `sketches`, `writeSketch` (writer-queued, awaited, throws; page size from `pages`)
and `readSketch`; `FaceRouting` (the generic table, `opensIntoFace` + `showPagesMode`) with
`TextDocRouting` a facade over it (its test untouched) and `SketchRouting` (Show pages =
`RESULT_SKETCH_SHOW_PAGES` → LOAD_CANVAS; Back / null → SEAL_TO_LIBRARY when the canvas was
never shown); `SoilDao.childrenOf` excludes `'sketch'`, `liveDescendantIds` carries it at the
page level, new `liveErasableIds` = descendants minus `'sketch'` and `eraseCurrent` uses it
(decision 11); `clip_too_large_sketch` wording when a refused envelope carries a sketch row (cap
unchanged). `NotebookActivity` is otherwise untouched — a Sketch notebook loads the canvas, as
this phase's gate requires. Opus wrote it on Fable's brief, Sonnet the radio XML + string; Fable
read the repository, kind, routing, DAO SQL, fake-DAO mirrors and session diff before the build.

**Tests.** `:app` 1717 → **1783** (+66: `SketchRowsTest`, `SketchRepositoryTest` over a
`FakeSketchDao` + hand-built `TestPng` headers, `NotebookKindTest`, `FaceRoutingTest`,
`SketchRoutingTest`; `SoilDaoKindListsTest` +3, `DocumentWhitelistTest` +1 — a newer sketch row
raises neither staleness sweep, `PageClipTest` +1, `SoilCompactorTest` +2, `NotebookMetaTest`
re-pinned with `"sketch":false`, `FamilyConstantsTest` bit 8 / type / order). **3291 in the SN
root** (K2's 3225 + 66), 3446 with `extensions/bible`'s 155; `:extension-api` 281 unchanged.
`assembleDebug` green.

**Measured** (Nomad, Sonnet's adb walk, the `.dev` host with K3): three radios present by id and
by eye; a Sketch notebook created from the radio opened on the ordinary canvas (`Creating
PaperView with engine 'ratta'`, no `FATAL` / `SecurityException` / `IllegalStateException` /
`IllegalArgumentException` in the host's log, `logcat -b crash` empty); the pulled index row reads
`flags = 9` (ENCRYPTED | **SKETCH**, TEXT_DOCUMENT clear) and the pulled `.soil`'s
`notebook_meta.json` ends `"textDocument":false,"sketch":true`; the `.soil` **export** landed on
the device and its meta reads the same with `exportedAt` stamped (Fable pulled it and read it
with the sqlcipher CLI). Regression: a Handwritten notebook opened and closed, a Text notebook
opened straight into the editor. **Re-import: NOT WALKED by adb** — the Supernote DocumentsUI
*file* picker (`ACTION_OPEN_DOCUMENT`) ignores synthetic taps on rows exactly as the folder
picker does (a new instance of the recorded trap); walked by the user's hand instead — see
"The hand".

**Traps found.** (1) `TextDocRouting`'s nested `Open` / `Close` enums had to survive for its test
to stay untouched, and Kotlin has no nested typealias — the enums live in `FaceRouting` and each
facade re-exposes them as nested objects of `val`s, which costs `when`-exhaustiveness, so
`NotebookActivity`'s two `when`s name `FaceRouting.Open` / `.Close` directly (six label edits).
(2) `PageClipTest`'s counts are load-bearing: adding the sketch row to the shared fixture moved
three unrelated assertions. (3) The DocumentsUI file picker is as adb-proof as the folder picker.
(4) `NotebookSession.kt` is 1014 lines (was 918, already over the ~800 guidance) — flagged, not
split, in this phase.

**The hand.** Nomad, the user, 2026-09-15: the exported Sketch `.soil` imported from the library's
Import door (the adb-driven export had landed it in a folder named with one space — moved to
`Download/` first). Log: `placed 24576 bytes in the Garden`; the imported row
`20260915_195418 Copy` reads `flags = 9` and its `notebook_meta.json` ends
`"textDocument":false,"sketch":true` (Fable pulled both and read them with sqlcipher). **Trap:**
the user's second tap on Import reopened the DocumentsUI picker, which Supernote gives no way to
back out of by hand — `am force-stop com.android.documentsui` returned the library cleanly
(`RESULT_CANCELED`, nothing imported twice). K3 closed.

**Next.** K4 — host entry, hooks, door, cover (`ExtensionRegistry.sketch`, `SketchHostSession` /
`SketchHostBinder` / `SketchClient` / `SketchHostHooks` / `SketchEntry`, `NotebookActivity`'s
`openIntoSketch` + `sketchShowingEnded` over `SketchRouting`, `btnSketch` on the bottom strip left
of Bible, `SketchCover`).


### K4 — Outcome (2026-09-15)

**Landed.** `ExtensionRegistry.sketch()` (trust + the API-17 floor through the existing
`accepts` / `minApiVersion`; "nine → ten points"); `SketchHostSession` (pure: the read window
PNG → `ByteChunks`, the ink window of staged `WireStroke` chunks with **0 legal**, the save
accumulator — key taken from chunk 0 and every later chunk must repeat it, in order, per-chunk
and running-total caps, `SKETCH_TOO_LARGE` verbatim as an `IllegalStateException`, empty join =
clear; **a window swap mid-save leaves the accumulation where it was**, so a flush a moment after
a page turn still lands on the page it was drawn on); `SketchHostBinder` (`ISketchHost.Stub`,
uid `gate()` the first statement of all six methods, the marshalable-only `hook {}` funnel,
`revoke()` clears the session); `SketchClient` (`DocumentEditorClient` minus the store: mint
binder → `hold` → `begin(host)` ≤ 2 s → the package-pinned screen Intent; `finish()` = `end()`
≤ 15 s then unbind + revoke in `finally`); `SketchEntry` (`DocumentEditorEntry` +
`ExtensionScreenEntry`'s paper pieces — open: drain → bind+begin → `EXTRA_CHROME_HIDDEN` →
`beforeLaunch` (floating chrome down, transform ended, `releaseForHandoff`) → `ScreenLaunch.attempt`
→ stack attach; result: pop → chrome flag → `reclaimPipeline` → lazy finish job → `onClosed(code)`;
`reconnect()`, `close(): Job?`, `finishJob`; the `sketch_failed_*` alert only on a deliberate
tap); `SketchHostHooks` (`loadCurrent` / `requestPage` — **an edge answers the same page** /
`commit` → `NotebookSession.writeSketch` / `requestInk` — writer drained, then `store.loadPage`
→ `TransferCaps` + `InkChunks`; `BoundedWait` `openSession()` at the document hooks' 8 s / 200 ms;
the face's target is `@Volatile`, restored from saved state before a reconnect's `begin`);
`SketchCover` (sampled decode behind the `PngHeader` guard, drawn over white into `RGB_565`,
`CoverSnapshot.encode`; pure `sampleFor`). `Surface.SKETCH` appended (`SurfaceStack`,
`LibraryActivity` drops it like `DOCUMENT_EDITOR`, `ReplayPlan` doc). `NotebookActivity`:
`sketchHooks` / `sketchEntry` wiring, `btnSketch` click + tooltip, the collapsed-overflow mirror
(`ic_brush`, left of Bible), the open route forking on `session.isSketch` into `SketchRouting`,
`openIntoSketch(launch)` (a missing / untrusted extension loads the canvas silently),
`sketchShowingEnded(resultCode)` (CATCH_UP → `refreshToPage`; LOAD_CANVAS on Show pages;
SEAL_TO_LIBRARY on Back with the canvas never shown), `replayAbove` `Surface.SKETCH`,
`KEY_SKETCH_SHOWING` / `KEY_SKETCH_TARGET` reconnect keys, `captureCover`'s sketch branch +
`coverPageId` / `lastOpenedPageId` → `saveLastOpened(…)` (the notebook reopens on the page the
face ended on), `close()` / `onDestroy` join and close the sketch bind. Sonnet: `ic_brush`
(Tabler, in `:sn-screen` beside `ic_bible`), `cd_sketch` / `sketch_failed_title` /
`sketch_failed_body`, the `btnSketch` slot before `btnBible` on the bottom strip (decision 9).
Opus wrote it on Fable's brief; Fable read the session, binder, hooks, client and the
`NotebookActivity` diff before the gate.

**Deviations from the row (Opus's, each read and kept).** (1) `watchForAnEditorThatNeverOpens`
generalized into `watchForAFaceThatNeverOpens(reconnect, isShowing, face)` for both faces — a
second hand copy is the sibling-copy trap. (2) **The ink window's cap cuts rather than refuses**:
a page past `MAX_TRANSFER_STROKES` (10 000) / `MAX_TRANSFER_POINTS` (400 000) crosses as the
prefix that fits, in writing order, logged — the pad's Send refuses because a selection can be
made smaller; here the alternative to a partial bake is no bake, and the seam has no typed
refusal for it. (3) The PNG header guard is not in the session — the page size lives in the
`.soil`, so `SketchRepository` throws `SKETCH_BAD_PNG` with nothing written. (4) The sketch
hooks reuse `documentWritesClosed` as their `alive` gate — one screen, one session, one fact.
(5) `captureCover`'s ink-bake fallback is gated on `canvasShown`, not `opened` — a Sketch
notebook is `opened` with nothing on the paper (TextCover's lesson). (6) `SketchEntry.discovered()`
carries both conditions (Sketch notebook AND a trusted extension) and `openIntoSketch` **awaits**
it — reading `isAvailable` would lose the RS2 race about half the time.

**Tests.** `:app` 1783 → **1805** (+22: `SketchHostSessionTest` 15, `SketchCoverTest` 5,
`ReplayPlanTest` +1, and one more pin); **3313 in the SN root** (K3's 3291 + 22), 3468 with
`extensions/bible`'s 155. `assembleDebug` green. Fable re-ran the whole root's `test` +
`:app:assembleDebug` after the review — zero failures.

**Measured.** Nothing on the device this phase — the row's gate is "JVM green; button GONE for
non-Sketch notebooks and with no extension (walk after K5)": with no `NSE · Sketch` installed a
Sketch notebook loads its canvas and the door stays GONE, which K5's walk covers together with
the face itself.

**For K5 (the screen).** `requestPage` never returns null and never throws at an edge — compare
`pageKey`. `requestInk` answers **0** for a page with no bare ink, never a refusal; "bare" is
structural (link ink is re-parented to the link row, sticky ink lives in the sticky), and a dense
page may arrive **cut** at the transfer caps. A save is accepted for any live page; a key naming a
deleted page comes back as `IllegalArgumentException("Unknown page")`. `end()` has the 15 s host
budget and is where a parked re-push must land; `begin()` has 2 s — do no work in it. The host
sets `EXTRA_CHROME_HIDDEN` on the launch Intent and reads it back off the result (`ChromeResult`)
— echo it. `RESULT_SKETCH_SHOW_PAGES` (1) is the only non-to-library result; `RESULT_CANCELED`
seals to the library when the canvas was never shown.

**Next.** K5 — `extensions/sketch`, the screen (`SketchApplication`, `SketchService`,
`SketchActivity : PaperScreenActivity`, saver / governor / park, tiles + edit builder, `InkBake`,
the debug-only fill door), the first Nomad walk. Phase-start Qs: the save-failed dialog wording;
the pencil glyph (`ic_pen` vs a new `ic_pencil`).

# SKETCH_PLAN.md — arc 43 "Sketch": `NSE · Sketch` (branch `sketch`)

The plan **and the ledger** for the raster-sketch extension, in the shape of the SN arc plans
(`apps/notesprout_sn/<ARC>_PLAN.md`) and `extensions/bible/BIBLE_PLAN.md`. Phases are appended to
the ledger at the bottom as they land; the reference doc once frozen is
`extensions/sketch/docs/sketch.md` beside this file.

**Status: Arc 43 — IN PROGRESS, K0 🔄.** Phases are lettered **K** (arc 37 "Bible" used B, arc 38
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
| **K0 — Pin + scaffold** 🔄 | Sonnet | Branch `sketch` from `main`; `extensions/sketch/SKETCH_PLAN.md` (this plan + ledger); SN pin `sn-screen/build.gradle.kts` 0.1.28 → **0.1.31** (zero `gpaper-ratta` lines change; carries `RasterPatch`/`swapPageRaster`/`RasterRubbing`); g-paper `PLAN.md` Phase 16 🧪 → ✅ with the arc-29 Nomad record (Onyx half still untested); stale `docs/integration-guide.md`/`README.md` version lines; `apps/notesprout_sn/CLAUDE.md` pin sentence. | Build + all JVM tests green; Sonnet adb smoke of notebook/pad/calendar; user hand: ink, point eraser, lasso, lasso eraser, pad Send both ways; `gfxinfo` writing-minute baseline. |
| **K1 — g-paper Phase 19 → 0.1.32 "Ratta and the raster page"** ⬜ | Opus (Fable reviews diff + numbers before publish) | Core: `protected open val rasterEraseRedrawIntervalMs` read by `throttledEraseRedraw` (const stays 16). Ratta: override (initial 100 ms; candidates 16/60/100/250/end-only via a demo-only system property); `RattaEmr.penSize(style, widthPx)` pure with `EMR_MIN_HAIRLINE` (start 120) for `PENCIL`, others keep 200; note at the "needs no override" comment naming `loadPageRaster`/`swapPageRaster` (the overlay is already released by `clearForContentSwap` under the swap law — verified, not a gap). **The g-paper demo gains a Raster toggle** (pencil + rubbing eraser) as the measurement vehicle. Tests `RattaEmrTest` ×4. Docs `api.md`/`CLAUDE.md`/`PLAN.md`. | Nomad (demo): M1 cadence per candidate (`gfxinfo` + the hand: lifts live? flicker?); M2 pencil preview width at EMR 120/150/200 vs the baked hairline (photo + screencap); M3 eraser-end pressure real (temporary log); M4 undo-swap flash (accept, record); M5 page-wide swap ms. Phase-start Qs: cadence candidates; whether to try `DARK_GRAY` preview. |
| **K2 — The seam** ⬜ | Opus (Sonnet: `ExtensionContractTest` pin, docs rows) | `:extension-api`: `SketchContract`, `ISketch.aidl`, `ISketchHost.aidl`, `SketchPageState` (+`requireValid`), `ByteChunks`, `PngHeader` (pure, both sides), `API_VERSION` 17 + floor; host manifest `<queries>` both actions. `:sn-screen`: `UndoRedoStack` budget + `pushUndoBeneath`, `CollapsedChrome(tools)`, `PaperToolbar(btnLasso?)`. `:ext-ink`: `PaperScreenActivity` extraction + `PenIdle.awaitIdle`. Tests: `ExtensionContractTest`, `SketchContractTest`, `ByteChunksTest`, `PngHeaderTest`, `SketchPageStateTest`, `UndoRedoStackTest` (+6). | `:extension-api:testDebugUnitTest` green (the pin trap); all modules compile; Sonnet adb regression of pad + calendar (the base-class move); user hand: pad/calendar ink + handoff. |
| **K3 — Host data, creation, routing** ⬜ | Opus (Sonnet: radio XML/strings) | `TYPE_SKETCH`/`SKETCH_ORDER`; `SketchDao` (`sketchDigest`, `sketchFor`, `pagesWithSketch`) + `sketchDao()`; `SketchRows` (port of `RasterRows`); `SketchRepository` (`has/get/save/clear`, guard, watch, cap, unchanged-bytes skip); `NotebookSession.kind/isSketch`, `writeSketch`, `readSketch`; `NotebookKind`; `NotebookFlags.SKETCH`, `NotebookMeta.sketch` + **every mirror site** (`NewNotebookActivity`, `TextDocumentCreate`, `IndexRepository.createNotebook/importNotebookRow`, `NotebookImport.refreshMeta`, `ImportFlow`, `ExportArtifact.stampExportedAt`, `NotebookSession.refreshMeta`); third radio + `KEY_KIND`; `FaceRouting` generic table + `TextDocRouting` facade (its tests untouched) + `SketchRouting`; `SoilDao.childrenOf` exclusion, `liveDescendantIds` + `'sketch'`, `liveErasableIds` for Erase page; `FakeSoilDao` mirrors. Tests: `SketchRowsTest`, `SketchRepositoryTest`, `NotebookKindTest`, `FaceRoutingTest`/`SketchRoutingTest`, `SoilDaoKindListsTest` (+3), `DocumentWhitelistTest` (+1), `PageClipTest` (+1), `SoilCompactorTest` (+2). | JVM green; Sonnet adb: create a Sketch notebook (radio), `.soil` meta shows `sketch:true`, index flag 8, `.soil` export/re-import keeps it. (No face yet — a Sketch notebook loads the canvas.) |
| **K4 — Host entry, hooks, door, cover** ⬜ | Opus (Sonnet: `ic_brush` Tabler, strings, layout) | `ExtensionRegistry.sketch`; `SketchHostSession` (pure: read window, ink window, save accumulator); `SketchHostBinder` (gate first, funnel); `SketchClient` (hold, `begin(host)`, `end()` ≤ 15 s, revoke in `finally`); `SketchHostHooks` (`pages`, `loadPage`, `commit` → `writeSketch`, `requestInk` → drain + `TransferCaps` + `InkChunks`, bare strokes only); `SketchEntry` (= `DocumentEditorEntry` + `ExtensionScreenEntry`'s paper pieces: **drain → open → chrome extra → `dismissFloatingChrome` → `endTransformIfRunning` → `releaseForHandoff` → launch**; result: pop → chrome → `resumeDrawing` → finish job → `onClosed`); `NotebookActivity`: `openIntoSketch`, `sketchShowingEnded` (CATCH_UP / LOAD_CANVAS / SEAL_TO_LIBRARY), `replayAbove` `Surface.SKETCH`, reconnect keys, `close()` joins, watchdog; `btnSketch` on the bottom strip left of Bible + collapsed overflow mirror; `SketchCover` + `captureCover` branch + `saveLastOpened(lastFaceEndedOn)`. Tests: `SketchHostSessionTest` ×10, `ReplayPlanTest` (+1). | JVM green; button GONE for non-Sketch notebooks and with no extension (walk after K5). |
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

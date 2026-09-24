# Arc 51 "Guides" — a grid and a reference image under the sketch (branch `tools`, g-paper `sheet`)

**Status:** ✅ **COMPLETE + FROZEN 2026-09-24** on the user's Nomad hand walks — *"the grid (lines
and dots), and the reference image and settings all work well"* — *"Freeze please!"*. g-paper
`sheet` (Phase 46 → 0.1.61) **merged to g-paper `main` 2026-09-24 (`--no-ff`) and deleted** — g-paper
has only `main`; SN `tools` stays open as the working branch. Letter **J**. `docs/sketch.md`
§ "Guides (arc 51)" is the reference; this file is the plan + ledger (history).

## Context

The user's request (2026-09-23): two per-page **guides** for NSE · Sketch, tools for the artist that
are never part of the sketch — (1) a **grid** of lines or dots, centred on the page, with a count
setting, for laying out a scene (urban sketching: the same grid over a photo); (2) a **reference
image** for tracing — centred, fit to the page proportionally, one setting (opacity). Both can be
hidden or removed per page; neither is exported, covered, erased, or touched by any tool; the
pencil, pen and eraser draw *over* them (through the rasters' alpha). Stored as rows parented to
the page. Work is done by **Opus 5.5 under Fable's orchestration** (arc 43–45's recipe): Fable
writes the AIDL seam and the g-paper brief, Opus 5.5 codes engine/face/host, Fable reviews every
diff, JVM tests gate each phase, the user walks the Nomad by hand, docs + memory + CLAUDE.md
updated and committed + pushed per phase. SN work on `tools`; g-paper on a new branch `sheet`,
merged in the last phase.

**The one engine fact that shapes the arc:** on a `PageMode.RASTER` page the Supernote direct
panel path flattens graphite + ink over **hard-coded white** (`DitherFlatten.luma/band` start at
255; `toneAndPost`, `ditherBand`, `postRectFromDither` write `LEVEL_WHITE` wherever no raster
pixel is) and never reads `templateBitmap`. A sibling View under the paper is never seen (the
window fills opaque white and the panel is written directly). `setTemplate` is the wrong seam
twice over: it is in `renderToBitmap()` and it is absent from the raster panel flatten. So the
underlay is a **new engine input, display-only** — g-paper Phase 46.

## The user's decisions (2026-09-23 — never re-ask)

| # | Question | Decision |
|---|---|---|
| 1 | Recipe | **Fable orchestrates, Opus 5.5 codes** (`model: "opus"` subagents), Fable reviews every diff; JVM tests, the user walks; no code review (the standing waiver). |
| 2 | Naming | **Arc 51 "Guides"** · SN branch `tools` · g-paper branch **`sheet`** (Phase 46 → **0.1.61**) · letter **J** (V is arc 25's) · `extensions/sketch/GUIDES_PLAN.md`. |
| 3 | Grid geometry | **Square cells, one count across the page width**; the grid is laid out symmetric about the page centre (equal partial cells at opposite edges). Dots at the same cell corners. |
| 4 | Reference storage | **Page-fit lossy WebP, RGB(A)** — decoded, fit once (centred, `min(pw/sw, ph/sh)`), encoded at page size with transparent margins, `WEBP_LOSSY` q 90; opacity a setting beside it, applied at draw time. No zoom, no orientation. |
| 5 | Chrome | **One Guides button + one anchored panel** (Tabler `grid-dots` → `ic_grid_dots`, hint "Guides", after Smudge, mirrored in the collapsed overflow). Panel rows: Grid — Off · Lines · Dots + count latches; Reference — Pick… · Remove · opacity latches · Show/Hide. **Latches, never steppers/sliders.** |
| 6 | Hide / remove | **Hidden persists per page** (a stored flag; count/opacity kept while hidden); **Remove** deletes the row. **Grid and reference may show together**, grid drawn over the image. **Not on the undo stack.** |
| 7 | Values (walk knobs) | Counts **2 · 4 · 6 · 8 · 12** over **16 · 20 · 24 · 28 · 32**, two rows of five (grown on the J4 walk — "up to 24", then "up to 32", "2 rows to fit better", "drop the 3"; default Lines, 4); opacity **10 · 25 · 50 · 75 %** (default 25); grid tone Atelier level 9 `#aaaaaa`, line ~2 px, dot radius ~4 px. |

**Amends arc 43 decision 10** ("plain white, always; no template crosses the seam"): the paper is
still white and no *notebook template* crosses — but the face now lays its own guide sheet under
the rasters. **Not a reopening of `docs/templates.md`'s dropped "adjustable generators"**: a
sketch-only guide with a count is a fresh user decision, recorded here.

## Derived shape (reconciled from the exploration, not re-asked)

### Engine — g-paper Phase 46 "The sheet under the raster page" (branch `sheet`, 0.1.61)

- **`PaperView.setSheet(bitmap: Bitmap?)`** — a page-sized ARGB image with alpha, drawn **over
  white and under graphite** (then ink over both), **display only**: never in `renderToBitmap()`
  (`forDisplay = false`), never in `getPageRaster`, never read by `eraseRasterAlong` /
  `smudgeChunk` (both name `graphiteRaster` only — holds by construction). Null clears. Only
  `PageMode.RASTER`; a no-op on a stroke page (documented). Generic/Onyx: drawn in
  `drawCommittedContent` between the template and `drawRasterLayers` when `forDisplay`.
- **`DitherFlatten` grows a sheet input**: `luma(sheet, graphite, ink)` / `coverage` / `band` start
  from the sheet pixel composited over white instead of 255 (`srcOver` exact, unchanged operator
  order: white → sheet → graphite → ink; a white gel pen covers the sheet). Fast paths: the "no
  alpha on either raster → blank" band and the `OPAQUE_WHITE` shortcut become "…and no sheet
  alpha". Ratta reads it wherever it reads the graphite band: `toneAndPost`, `ditherBand` /
  `flattenDither` / `regenDither`, `postRectFromDither` (its `LEVEL_WHITE` fills *outside the page*
  stay). A sheet change fires the whole-page `onRasterPixelsChanged(null)` rebuild through the
  existing `DitherCoalescer` and presents once. **The window shows the sheet dithered into
  `ditherDisplay`** (an ALPHA_8 black/white coverage image), never true-grey sheet under black dots
  — the panel and the window must agree, the template's documented mismatch (`docs/api.md:279`)
  not repeated.
- Cost: one more page-sized band read per flatten (measure the Nomad whole-page rebuild, ~500 ms
  today); +1 page-sized bitmap (~10 MB Nomad) only while a sheet is set.
- JVM tests (`DitherFlattenTest` + new): white/absent sheet ≡ no sheet; opaque black sheet → all
  black; 50 % grey sheet at (x,y) == `Dither.black(luma)`; graphite over sheet; white ink over a
  black sheet → white; `band` pinned against per-pixel `coverage` with a sheet.
- Docs: `docs/api.md` (the sheet), `PLAN.md` Phase 46 entry, `CLAUDE.md` standing-rules bullet,
  `README.md` version, `gradle.properties` `GPAPER_VERSION=0.1.61`; `./gradlew publishToMavenLocal`.
- **J0 brief, line level** (validated against the source): core — `sheetBitmap` beside
  `templateBitmap` (L197), `sheetFor()` beside `rasterFor` (L795, null outside RASTER);
  `setSheet` beside `setTemplate` (L1045): no-op unless RASTER, then `sheetBitmap = bitmap;
  onRasterPixelsChanged(null); redrawCommitted()` — `loadPageRaster`'s order (L701); drawn 1:1
  from the origin (never stretched) inside `drawRasterLayers` (L1569) only when `forDisplay`;
  nulled in the `pageMode` setter (L531) and `release()` (L1156), kept across
  `clearForContentSwap`. Onyx: `epdRepaintHandoff { super.setSheet(bitmap) }`. Ratta:
  `DitherFlatten.luma` gains `sheet` (2-arg overload forwards 0); `luma`/`black`/`coverage`
  trailing `sheet: Int = 0`; `band` trailing `sheet: IntArray, hasSheet` — blank fill becomes
  `!g && !k && !s`, `OPAQUE_WHITE` shortcut stays valid, the per-pixel gate becomes
  `(gp or kp or sp) ushr 24 != 0`, graphite step switched to the general blend (bit-identical
  when no sheet); the sheet never counts as *coverage*. `RattaPaperView`: `readSheet` /
  `readSheetBand` mirroring `readRaster` (L1426) / `readRasterBand` (L1451) but sourcing
  `sheetFor()` (never `flattenBase`, so a direct stroke page never reads it); `toneAndPost`
  (L1350) and `ditherBand` (L2110) pass it; `postRectFromDither` untouched (it reads
  `ditherBytes`, which now carry the sheet); the "page gone" test in `onRasterPixelsChanged(null)`
  (L1754) becomes `graphite == null && ink == null && sheetFor() == null`; `setSheet` override
  beside `setTemplate` (L3007) releases the firmware overlay first. Window: the existing
  `drawRasterLayers` override (L1723) draws only `ditherDisplay`, so the sheet shows dithered
  for free. Cost: a full-page photo puts every pixel on the long path (≈2× a blank page's
  rebuild) — read `dither: whole page WxH in N ms` (L1997) on the Nomad.

### Data — two additive row types in the `.soil`, parented to the page (host only)

- `SoilSchema.TYPE_GUIDE_GRID = "guide_grid"` — no blob; `text` = `GuideGrid` JSON via
  kotlinx.serialization (`NotebookMeta`'s codec pattern): `{kind: LINES|DOTS, count, visible}`.
- `SoilSchema.TYPE_GUIDE_IMAGE = "guide_image"` — `blob` = the page-sized lossy WebP with alpha
  (`RIFF`/`WEBP`/`VP8X` — `ImageHeader.matches(bytes, pageW, pageH)` already parses `VP8X`
  regardless of the lossy sub-chunk, so **the same exact-page-size guard applies, unchanged**);
  `text` = `GuideImage` JSON `{opacityPercent, visible}`. One live row of each kind per page,
  `order = SoilSchema.SKETCH_ORDER`. Minted on first save, rewritten in place (`setBlob` /
  `setText`), soft-deleted on Remove. Caps: the existing `MAX_BYTES` (6 MiB) hard refusal
  `SKETCH_TOO_LARGE` and `WATCH_BYTES` log apply to the image row.
- `GuideRows` (`data/soil/GuideRows.kt`, pure: `toGridRow`/`toImageRow`/`gridOf`/`imageOf`/
  `imageBytes`) + `GuideDao` (`gridFor(pageId)`, `imageDigest(pageId)`, `imageFor(pageId)`) +
  `GuideRepository` (the only writer: `putGrid`, `putImageSettings`, `saveImage`, `clearGrid`,
  `clearImage`, `get`), `SketchRepository`'s shape, `suspend`, no Android types.
- **Reads that must not see the rows** — the one list to touch: `SoilDao.childrenOf` (add both
  names to the `NOT IN`); `liveDescendantIds` (**add both** — page copy/cut/paste/delete/undo
  carry the guides with the page); `liveErasableIds` **leave out** (Erase page never touches
  them; `pageContent`'s ink bit must not light); `liveContentIds`, `hasLiveSketch`,
  `pagesWithSketch`, the document staleness whitelists, `ORDERED_TYPES` untouched — export, cover
  and the bundle read the two named raster types only, so guides are excluded by construction.
  `ClipMessages.tooLarge` names the sketch for a guide image too. `FakeSoilDao` mirrors the SQL.
- **Never routed through `SketchRows.typeFor` / `SketchContract.LAYERS` / `SketchLayers.all`** —
  those drive flatten, export, cover and the raster saves.

### Seam — `API_VERSION` 21 → 22, a method-floored tail (action floor stays 20)

`SketchContract.MIN_API_VERSION_FOR_SKETCH_GUIDES = 22`; `:ext-sketch`'s manifest declares 22.
Four `ISketchHost` tails after `putToolSettings` (transaction codes 14–17), `gate()` first in each:

```
SketchGuideState guides(String pageKey);                        // 14: parks the image window
byte[]           readGuideImageChunk(int chunkIndex);           // 15: the parked window
void             putGuides(String pageKey, in SketchGuideSettings s); // 16: grid + image settings (no pixels)
void             saveGuideImageChunk(String pageKey, int chunkIndex, in byte[] chunk, boolean last); // 17: one empty chunk = Remove
```

- `SketchGuideSettings` (parcel): `gridKind` (0 off · 1 lines · 2 dots) · `gridCount` ·
  `gridVisible` · `imageOpacity` (0–100) · `imageVisible` — small ints, sanity-bounded at
  unmarshal (`MAX_TOOL_SETTING_INDEX`'s rule), **indices/percent never pixels**. The host stores
  what it is handed and never clamps against the face's ladder.
- `SketchGuideState` (parcel): `pageKey · settings · imageBytes · imageChunks` (chunks pinned to
  `ByteChunks.countFor`), the exhausted-parcel tail rule kept for the next field.
- `SketchHostSession` grows a **third read window and a third accumulator** (the guide image),
  independent of the two raster ones; `saveGuideImageChunk` follows `saveSketchChunk`'s rules
  (chunk 0 carries the key, running total vs `MAX_BYTES`, the empty join clears the row, the
  page-size guard before write, `SKETCH_BAD_IMAGE` / `SKETCH_TOO_LARGE` verbatim).
- Pin tests: `ExtensionContractTest` (22), `SketchContractTest` (floor 22 **above** the action
  floor — the correct sense), new `SketchGuideSettingsTest` / `SketchGuideStateTest`;
  `docs/extensions.md` boundary-audit **row 71** + a "J1's tails" section.

### Face — `:ext-sketch` (+ `:sn-screen` for shared chrome)

- **`GuideSheet`** (`:ext-sketch`, pure geometry + one Android painter): `GridLayout.plan(kind,
  count, pageW, pageH)` — cell = `pageW / count`; columns at `pageW/2 ± k·cell`, rows at
  `pageH/2 ± k·cell` (symmetric about the centre — count even puts a line on the centre, odd puts
  the centre mid-cell; **compute positions from the centre outward, never `extent/count`
  accumulated** — `templates.md`'s float-accumulation trap); `render(plan, image?, opacity)` →
  one page-sized ARGB bitmap: image at its alpha × opacity, grid lines/dots in `#aaaaaa` over it.
  `ImageFit.plan(srcW, srcH, pageW, pageH)` = `TemplateFit.FIT`'s arithmetic, **copied as a pure
  object into `:sn-screen`'s `core/`** (`:app` is off-limits to the face; `TemplateFit` stays).
- **`SketchActivity.loadPage`**: after `setTemplate(null)`, ask `host.guides(pageKey)`, read the
  image chunks, decode (bounded, `RasterImage.decode`'s header-guard first), build the sheet
  (`GuideSheet.render`) on IO, `paper.setSheet(bitmap)` before the rasters load (one rebuild). A
  page with neither row → `setSheet(null)`. Hidden guides are simply not drawn.
- **`GuidesBar`** (`:ext-sketch`, over `:sn-screen`'s `AnchoredBar`, `PaletteBar`'s recipe):
  rows built with `addRow`; latch buttons use `Widget.Notesprout.LatchButton` / `isSelected`;
  re-reads state at every `show`; stays open after a pick; dismissed exactly as `PaletteBar` is
  (outside contact, tool change, page swap, finger gesture, chrome flip, exit); its rects in
  `extraFloatingRects`. Every pick → `paper.setSheet(rebuilt)` + `putGuides(pageKey, settings)`
  fire-and-forget on IO under a fair mutex (`toolPushes`' rule, pick order kept).
- **Pick…**: `ACTION_OPEN_DOCUMENT` + `CATEGORY_OPENABLE` + `image/*` + `EXTRA_MIME_TYPES`
  png/jpeg/webp via `registerForActivityResult(StartActivityForResult())` from the face (the first
  system picker any extension opens — `TemplateTransfer.startImport`'s Intent, `:app`). The result
  grant is read once with `contentResolver.openInputStream` (bounds pass → `inSampleSize` →
  exact fit scale, `TemplateTransfer.decodeAndEncode`'s recipe), no persistable grant, nothing
  written to disk by the extension. Encode `WEBP_LOSSY` q 90 at page size with transparent
  margins → `saveGuideImageChunk` chunks under the one push lock (`SketchSaver`'s lock, a new
  `pushGuideImage` beside the raster pushes; not debounced — one push per pick). Refusals
  (`SKETCH_TOO_LARGE`, no picker, decode failure) are `Dialogs.problem` alerts, never toasts.
  Before launching the picker: the face stays live (a non-drawing child needs no
  `releaseForHandoff` — the calendar's precedent) but `HostCallerCheck` must survive a
  DocumentsUI-pressure recreate — verify `callingPackage` on the walk.
- **Remove**: `saveGuideImageChunk(pageKey, 0, empty, true)`; grid Off = `putGuides` with kind 0
  (the host soft-deletes the grid row on kind 0).
- **Show/Hide**: two latches (grid, image) → `putGuides` + `setSheet`.
- New `:sn-screen` drawables: `ic_grid_dots` (Tabler `grid-dots`), `ic_grid` (`grid-4x4`),
  `ic_photo` + `ic_eye` / `ic_eye_off` moved from `:ext-document` into `:sn-screen`. Strings in
  `:ext-sketch`.
- Collapsed chrome: `collapsedOverflow()` gains `Entry.mirroring(R.drawable.ic_grid_dots,
  binding.btnGuides)`; `keepCollapsedUnder` / `onCollapsedClosing` cover the bar as they cover
  `PaletteBar`.
- Not remembered on the device (per page in the `.soil` is the memory); no `SketchToolSettings`
  change.

## Phases (each: build → JVM tests green → Nomad walk where it applies → docs/memory/CLAUDE.md → commit + push)

| Phase | Owner | What lands | Gate |
|---|---|---|---|
| **J0** | Fable brief → Opus 5.5 in `~/git/g-paper` on branch `sheet` | Phase 46: `PaperView.setSheet`, `CanvasPaperView` sheet draw (`forDisplay` only), `DitherFlatten` sheet input, `RattaPaperView` reads (toneAndPost / ditherBand / postRectFromDither / coalesced rebuild / window dither), demo door (a Sheet toggle in the demo's raster page: grid + a bundled test photo), tests, docs, `0.1.61` published to mavenLocal | `./gradlew test` green (core + ratta, 809 → +N); demo on the Nomad: a grid and a photo visible under pencil, pen, rubber, smudge; `renderToBitmap` dump without the sheet |
| **J1** | Fable (seam) | `SketchGuideSettings` / `SketchGuideState` parcels + AIDL, four `ISketchHost` tails, `SketchContract.MIN_API_VERSION_FOR_SKETCH_GUIDES` = 22, `API_VERSION` 22, `:ext-sketch` manifest 22, pin tests, `docs/extensions.md` row 71 + "J1's tails" | `:extension-api:testDebugUnitTest` (**the pin test — must be run**) |
| **J2** | Opus 5.5 (host) | `SoilSchema` types, `GuideRows` / `GuideDao` / `GuideRepository`, `SoilDao.childrenOf` + `liveDescendantIds`, `FakeSoilDao`, `ClipMessages`, `SketchHostSession` third window/accumulator, `SketchHostBinder` + `SketchHostHooks` + `NotebookSession.readGuides/writeGuideImage/putGuides` | `:app:testDebugUnitTest` green (repository, rows, kind lists, session, PageClip carry) |
| **J3** | Opus 5.5 (face) | `:sn-screen` `ImageFit` + icons; `:ext-sketch` `GuideSheet` (pure `GridLayout` + painter), `GuidesBar`, `btnGuides`, `loadPage` sheet load, the picker + encode + push, Remove / Show / Hide, collapsed overflow; `:sn-screen` pinned 0.1.61 | `:ext-sketch:testDebugUnitTest` + `:sn-screen:test` green (`GridLayoutTest`: symmetric positions for even/odd counts, no accumulation drift, cell = width/count; `ImageFitTest`; `GuideStateTest`); `.dev` host + ext on the Nomad; adb walk: grid latches, hide/show, page turn/reopen persistence, page copy carries rows, PNG/PDF export shows no guide, cover shows no guide, Erase page leaves guides |
| **J4** | the user | Nomad hand walk: pick a photo (DocumentsUI is adb-proof), trace over it at each opacity, grid over photo, pencil/pen/rubber/smudge over both, undo/redo untouched, `SKETCH_DUMP` and a real export clean. Knobs: `GuideSheet.GRID_TONE`, `LINE_PX`, `DOT_RADIUS_PX`, `COUNTS`, `OPACITIES`, `IMAGE_QUALITY`; g-paper `DITHER_GAMMA` untouched | the user's word |
| **J5** | Fable | Docs + freeze: `docs/sketch.md` § "Guides (arc 51)" + decisions + failure-table rows + traps, `GUIDES_PLAN.md` ledger, SN `CLAUDE.md`, root `CLAUDE.md`, memory (`project_guides_arc.md` + MEMORY.md index, trimmed under the size limit); **merge g-paper `sheet` → `main` (`--no-ff`) and delete**; SN `tools` stays open | on the user's word |

## Critical files

- **g-paper**: `gpaper-core/.../core/PaperView.kt` (`setSheet`, KDoc beside `setTemplate` L405),
  `core/canvas/CanvasPaperView.kt` (`drawCommittedContent` L1522, `drawRasterLayers` L1569,
  `renderToBitmap` L1163), `gpaper-ratta/.../ratta/DitherFlatten.kt` (`luma` L80, `coverage` L152,
  `srcOver` L191, `band` L264), `ratta/RattaPaperView.kt` (`toneAndPost` ~L1350, `flattenBase`
  L1659, `drawRasterLayers` override ~L1723, `postRectFromDither` ~L1922, `ditherBand` ~L2110),
  `PLAN.md`, `docs/api.md`, `CLAUDE.md`, `gradle.properties`.
- **Seam**: `extension-api/src/main/aidl/.../ISketchHost.aidl`, new `SketchGuideSettings.aidl` /
  `SketchGuideState.aidl` + `.kt`, `SketchContract.kt` (L65 floor, L104 pen-shade precedent),
  `ExtensionContract.kt` L187, `extensions/sketch/src/main/AndroidManifest.xml` L39,
  `ExtensionContractTest.kt` L47/L62, `SketchContractTest.kt`, `apps/notesprout_sn/docs/extensions.md` § Boundary audit (row 70 at L2851).
- **Host**: `data/soil/SoilSchema.kt` (L139–171), `SoilDao.kt` (`childrenOf` L44, `liveDescendantIds`
  L127, `liveErasableIds` L154), `SketchRows.kt` / `SketchDao.kt` / `SketchRepository.kt` (the
  recipe), `data/clip/ClipMessages.kt` L31, `extension/SketchHostSession.kt` (L76 windows, L164
  accept), `extension/SketchHostBinder.kt`, `notebook/SketchHostHooks.kt` (`state()` L542),
  `NotebookSession.kt` (`writeSketch` L805 / `readSketch` L838), `app/src/test/.../FakeSoilDao.kt`.
- **Face**: `extensions/sketch/.../SketchActivity.kt` (`loadPage` L443, `setTemplate(null)` L454,
  `togglePaletteBar` L628, `collapsedOverflow` L262, `extraFloatingRects` L246), `SketchToolbar.kt`,
  `res/layout/activity_sketch.xml` (top bar L60–125, `paletteBar` L194), `SketchSaver.kt` (the push
  lock), `RasterImage.kt`; `:sn-screen` `notebook/AnchoredBar.kt`, `PaletteBar.kt` (the recipe),
  `CollapsedChrome.kt`; `:app` `data/template/TemplateFit.kt` (copy its FIT arithmetic),
  `templates/TemplateTransfer.kt` L118–218 (the picker + decode recipe, read only).
- **Plan + docs**: `extensions/sketch/GUIDES_PLAN.md` (INK_PLAN's richer shape: decisions table,
  phases table, ledger), `extensions/sketch/docs/sketch.md`, both `CLAUDE.md`s, memory.

## Verification

- JVM: `cd ~/git/g-paper && ./gradlew test`; `cd apps/notesprout_sn && ./gradlew
  :extension-api:testDebugUnitTest :app:testDebugUnitTest :sn-screen:test :ext-sketch:testDebugUnitTest`.
- Device (Nomad `SN078D10012852`, `.dev` builds via the `device-build-install` skill): g-paper demo
  sheet door (J0); the face's adb walk (J3: latches, persistence across turn/reopen/process death,
  page copy, export/cover exclusion, Erase page); the user's hand walk (J4: the picker, tracing at
  each opacity, every tool over both guides, undo/redo).
- `sqlcipher` on the `.soil`: `guide_grid` (text JSON, no blob) + `guide_image` (RIFF/WEBP/VP8X)
  rows parented to the page; page copy → the same two rows on the new page; PNG export bytes
  identical with and without a shown guide.
- Log lines name counts/bytes/ms only; no image bytes or Uris logged.

## Open items parked as futures (each a fresh user decision)

Zoom/pan/rotate of the reference; a per-notebook default guide; the grid tone as a setting; the
sheet on a stroke page (`directInk`) for the notebook's own paper; Manta walk.

## Ledger

### J4 — Walk ledger (2026-09-23, the user's hand on the Nomad)

- **Walk 1:** every test passed (the picker, tracing at 75 %, the grid over the photo). Ask:
  counts above 12 → **16 · 20 · 24** added to `GuideSheet.COUNTS`; nine latches fit one row on
  the Nomad (the bar re-anchors left). Then **28 · 32** and **two rows** (`COUNT_ROW_BREAK` 6,
  `countRows()`): `2 3 4 6 8 12` over `16 20 24 28 32`, the panel narrower and centred under the
  button again. Then **"drop the 3"** → `2 4 6 8 12` over `16 20 24 28 32` (`COUNT_ROW_BREAK` 5).
- **Walk 2 (2026-09-24): "the grid (lines and dots), and the reference image and settings all
  work well."** Export proof over adb: whole-notebook PNG export from the library sheet to
  `Document/` (the SAF folder picker driven by breadcrumb nudge + USE THIS FOLDER + ALLOW);
  `ExportRender: rendered 29 page(s) + 29 sketch(es)`; `Sketch Tools - page 29 sketch.png`
  (the page with a 24-line grid and the ice-cream photo at 75 %) is 1404×1872 RGB with **0 colour
  pixels** and no grid — the rasters alone; opened in Preview for the user. The photo was pushed to `Download/ice-cream.jpg` by adb
  because the Supernote file picker hides where a shared image lands — a Drive source through our
  own browser (the export destination's shape) is noted as a future, the user's call.

### J0 — Outcome (2026-09-23, built; walk pending)

- **Landed (Opus 5.5, Fable-reviewed):** `PaperView.setSheet`; `CanvasPaperView.sheetBitmap` /
  `sheetFor()` / the `forDisplay`-only draw in `drawRasterLayers`, nulled on a mode flip and at
  `release()`, kept across `clearForContentSwap`; Onyx through `epdRepaintHandoff`; `DitherFlatten`
  `luma(sheet, graphite, ink)` + trailing `sheet` on `luma`/`black`/`coverage` + `band(sheet,
  hasSheet)` — white → sheet → graphite (general blend) → ink, bit-identical without a sheet;
  Ratta `readSheet`/`readSheetBand` through `sheetFor()` (never `flattenBase`), `toneAndPost` +
  `ditherBand` pass it, `setSheet` releases the overlay first; demo Sheet cycler (grid → ramp
  photo at 25 %). g-paper 0.1.61 published; `README`/`PLAN.md` (Phase 46 🧪)/`CLAUDE.md`/`api.md`.
- **Tests:** core 317 + ratta 105 = 422 distinct (was 417), green.
- **Deviations (accepted):** (1) inside `clearForContentSwap` the page counts as gone whatever the
  sheet (`swappingContent`), so the old pixels hold on the panel until the new page lands;
  (2) `band`'s settled tone now keys on the two page images only (`coverage`'s gate) — identical
  without a sheet; (3) the demo's photo is generated, not bundled; (4) the sheet is held by
  reference like the template.

### J3 — Outcome (2026-09-23)

- **Landed (Opus 5.5, Fable-reviewed):** `:sn-screen` pinned g-paper **0.1.61**; `ImageFit` +
  `ic_grid_dots`/`ic_grid`, `ic_photo`/`ic_eye`/`ic_eye_off` moved in from `:ext-document`;
  `:ext-sketch` `GridLayout` (pure: `centre ± (offset + k·cell)` in Double, half-cell offset for
  odd counts, rows take the columns' parity, only positions strictly inside the page), `GuideState`
  (off-ladder → default, unknown kind → Lines), `GuideSheet` (the knobs `GRID_TONE #aaaaaa`,
  `LINE_PX` 2, `DOT_RADIUS_PX` 4, `COUNTS` 2·3·4·6·8·12, `OPACITIES` 10·25·50·75, `DEFAULT_COUNT` 4,
  `DEFAULT_OPACITY` 25, `IMAGE_QUALITY` 90; `render`, `fitToPage`, `encode`, `sampleSize`),
  `GuidePush` (the chunk stream, testable), `GuidesBar` (Grid header · Off/Lines/Dots + grid eye ·
  six counts · Reference header · Pick/Remove + image eye · four opacities; controls that make no
  sense are `GONE`), `SketchGuides` (load → `setSheet` before the rasters; picks rebuild on IO and
  `putGuides` in pick order; the picker via `ActivityResultContracts.StartActivityForResult` +
  `ACTION_OPEN_DOCUMENT`, decoded with `ImageDecoder` (EXIF-rotated, software allocator), fit,
  encoded lossy WebP q 90 at page size, header-checked, pushed under the one push lock via
  `SketchSaver.pushGuideImage`, then `putGuides`; Remove = one empty chunk; every refusal a
  problem dialog); `SketchActivity` keeps the hooks + `btnGuides` after Smudge; `SketchToolbar`
  `onGuides`; strings. **Fable's one fix:** the title is centred in the band the button groups
  leave free (`centreTitleInTheFreeBand`, `START_BUTTONS` 6 / `END_BUTTONS` 2) — with six start
  buttons the screen's centre lies under Guides and the title read "tch".
- **Tests:** `:ext-sketch` 131 (`GridLayoutTest` 10, `GuideStateTest` 10, `GuidePushTest` 3);
  `:sn-screen` 129 (`ImageFitTest` 5).
- **adb walk (Nomad, `.dev`):** Guides → Lines → 4: the grid live under the smudge sketch,
  centred, dithered (the #aaaaaa line reads as a dotted rule on the panel); the host wrote
  `guide_grid` (`new grid for …`, `putGuides` 36 ms); turn away (no grid) and back (`grid 1/4`
  from the row); force-stop + reopen the notebook: `guides loaded in 355 ms: grid 1/4`; the
  library cover shows no grid.
- **Deviations (accepted):** the overflow entry has its own `onTap` (the top-bar button is hidden
  while collapsed, so the plain mirror could not open the bar); `ImageDecoder` over the two-pass
  `BitmapFactory` recipe (EXIF); pixel (0,0)'s alpha is 254 so libwebp always writes a `VP8X` head
  (an aspect-exact picture would otherwise write a bare `VP8 ` the guard refuses); refusals decided
  before any chunk is sent; the decoded reference kept in memory (~10.5 MB + the sheet's ~10.5 MB
  on the Nomad while set); a picker result is tied to the page it was opened for; two small text
  headers in the panel.

### J2 — Outcome (2026-09-23)

- **Landed (Opus 5.5, Fable-reviewed):** `SoilSchema.TYPE_GUIDE_GRID/TYPE_GUIDE_IMAGE`;
  `GuideRows` (`GuideGrid`/`GuideImage` `@Serializable` in the row's `text`, `toGridRow`/
  `toImageRow`, `gridOf`/`imageOf`/`imageBytes` null-never-throw, `fitsPage` = `ImageHeader.matches`,
  `toSettings`/`gridFrom`/`imageFrom`); `GuideDao` (`gridFor`, blob-free `imageDigest` carrying the
  text, `imageFor`) on `SoilDatabase` (no schema move); `GuideRepository` the only writer (mint on
  first use, rewrite in place, identical bytes write nothing, `GRID_OFF` soft-deletes, a bad stored
  image soft-deleted on read, `SKETCH_TOO_LARGE`/`SKETCH_BAD_IMAGE` write nothing, a new image row
  starts at opacity 0 and the face's `putGuides` right after fixes it); `SoilDao.childrenOf` excludes
  both, `liveDescendantIds` carries both, `liveErasableIds` untouched; `ClipMessages` names the
  sketch for a guide image; `NotebookSession.readGuides`/`putGuides`/`writeGuideImage`;
  `SketchHostSession` third window + accumulator (`setGuideWindow`, `readGuideChunk`,
  `acceptGuideChunk` → `GuideCommit`); `SketchHostBinder`'s four stubs replaced; `SketchHostHooks`
  `guides`/`putGuides`/`commitGuideImage` on the existing Binder-thread `runBlocking` pattern.
- **Tests:** `:app` 1852 → 1899 (`GuideRowsTest` 13, `GuideRepositoryTest` 17 + `FakeGuideDao`,
  `SketchHostSessionTest` +12, `SoilDaoKindListsTest` +4, `PageClipTest` +1; `FakeSoilDao` mirrors
  the SQL).
- **Deviations:** the JSON field is `opacity` (not `opacityPercent`); a grid on → off → on mints a
  new row (the old stays soft-deleted); the AIDL `putGuides` note reworded to "ignored".

### J1 — Outcome (2026-09-23)

- **Landed:** `SketchGuideSettings` (five ints, `NONE`, `hasGrid`) + `SketchGuideState` (pageKey ·
  settings inline · imageBytes · imageChunks, pinned to `ByteChunks.countFor`) with their `.aidl`
  declarations; `SketchContract.MIN_API_VERSION_FOR_SKETCH_GUIDES` = 22, `GRID_OFF/LINES/DOTS`,
  `MAX_OPACITY_PERCENT`; four `ISketchHost` tails (codes 14–17); `API_VERSION` 21 → 22 with its
  ledger paragraph; `:ext-sketch` manifest 22 (comment moved with it); `docs/extensions.md` row 71
  + "J1's four tails". `SketchHostBinder` gained the four overrides as gated
  `UnsupportedOperationException` stubs so `:app` compiles at this commit — J2 replaces them.
- **Tests:** `:extension-api` 313 (was 300): `ExtensionContractTest` re-pinned 22, two new
  `SketchContractTest`s, `SketchGuideSettingsTest` (6), `SketchGuideStateTest` (4).
- **Deviation:** none from the plan; the binder stubs are the one addition, for a green commit.


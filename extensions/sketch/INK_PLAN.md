# INK_PLAN.md — arc 45 "Ink": two rasters, permanent ink (branch `ink`)

The plan **and the ledger** for the third arc of `NSE · Sketch`, in the shape of `PENCILS_PLAN.md`
beside this file. Phases are appended to the ledger at the bottom as they land; the reference doc
stays `extensions/sketch/docs/sketch.md`, grown at G5.

**Status: Arc 45 — IN PROGRESS, G0 ✅ (2026-09-17).** Phases are lettered **G** ("Graphite" — the
letters B C D F H K L M N P R T U V W X Z are in use across every `*_PLAN.md`; I was declined for
its I/l ambiguity in a terminal). The g-paper half is g-paper's own **Phase 26 → 0.1.39** on its
branch `two-rasters`.

## Context

The sketch face has a pencil, a gel pen and a rubbing eraser over **one** page-sized ARGB bitmap.
The rubber (`RasterRub.rubBatch`) scales every covered pixel's alpha and nothing in a pixel says
which tool laid it down, so the gel pen erases exactly as the pencil does. The user's decision of
2026-09-17 opens this arc: **ink is permanent** — "in the real world, ink is more permanent than
pencil."

Three shapes were weighed in the planning session (all in this session's transcript; only the
outcome is binding):

- **A colour key** ("pure black means ink", one bitmap, `inkLift` on the rubber, `DARKEN` for
  pencil flecks over ink) — one engine phase and one SN line, but it rests on the pencil never
  baking `#000000`, breaks the day the pen gets a second colour, and scuffs the 1 px antialias
  fringe where ink crosses graphite. **Declined** as the interim it would have been.
- **User-facing layers** (a layer UI, z-order, visibility) — **declined**; the app never grows a
  layer panel.
- **Two rasters, one picture** — the page's *data* is a graphite raster and an ink raster; every
  place the sketch is seen (the canvas, export, cover, clipboard) flattens the two with a
  **darken** composite, which is order-independent, so there is no top and bottom to explain.
  Tools route by raster, not by colour; the rubber rubs graphite and never reads ink. **Chosen.**

What the exploration found (2026-09-17), which shapes every phase:

- **The engine holds one `pageRaster`** (`CanvasPaperView` ~L192); `compositeIntoRaster` bakes
  every style into it through `StrokeRenderer`; `eraseRasterAlong` reads and writes it;
  `swapPageRaster` / `copyPageRaster` / `loadPageRaster` / `getPageRaster` are its whole API, and
  `PaperListener.onRasterWillChange` / `onRasterChanged` carry a rect with no layer.
- **The `.soil` row is one PNG** (`SoilSchema.TYPE_SKETCH = "sketch"`, `SketchRows`, the pure
  `PngHeader` guard in `:extension-api`), crossing the seam as one chunked stream both ways
  (`SketchPageState.sketchBytes/sketchChunks`, `readSketchChunk` / `saveSketchChunk`).
- **Every reader composites the one row over white**: `SketchRaster` (export), `SketchCover`,
  and the clipboard carries the row by type name.
- **The face's undo is per contact** (`RasterEditBuilder` over 64 px `RasterTiles`,
  `SketchEdit.RasterChanged`), its save governor is one dirty flag, its park one slot.
- **This is still a brand-new extension**: no shipped library depends on the PNG row. The user
  waived legacy outright (decision 4) — the Nomad's dev library and the Manta's release library
  both lose their existing sketches, by the user's word.

## Decisions (the user's, 2026-09-17 — binding; phase-start questions may not reopen them)

| # | Question | Decision |
|---|---|---|
| 1 | How the rubber treats ink | **Never erases.** The eraser rubs the graphite raster only; the ink raster is never read, never rubbed, never in an eraser's undo tiles. (A future "resists" would be an `inkLift` fraction on `RasterRubbing` — a fresh decision, not this arc.) |
| 2 | Naming | Arc 45 **"Ink"** · Notesprout branch **`ink`** · g-paper branch **`two-rasters`** (Phase 26 → 0.1.39) · letter **G** · this file. |
| 3 | Storage | **Two rows per page, `sketch_graphite` + `sketch_ink`**, symmetric names (`TYPE_SKETCH` is renamed, every site with it); **each a page-sized lossless WebP with alpha** (RGBA — colour-ready for other platforms; grey+alpha PNG was declined for that reason). The 6 MiB cursor-window refusal applies **per row**. |
| 4 | Legacy | **None.** No migration, no PNG sniffing, no warning. Existing `sketch` PNG rows on the Nomad dev library and the Manta release library are lost — "nothing there worth keeping." The Nomad's dev sketches may be removed by the walk agent. |
| 5 | Models | **Unchanged from arcs 43–44**: Fable orchestrates, writes the AIDL seam and the g-paper briefs, reviews every g-paper diff and every doc draft; Opus codes; Sonnet scaffolds, runs JVM tests and walks the Nomad; no Haiku; no code review. |

### Derived (reconciled from the exploration — not re-asked; say so at a phase start if one is wrong)

- **Flatten = darken.** Wherever the two rasters become one picture — the engine's committed
  layer, `SketchRaster` (export), `SketchCover`, any clipboard preview — graphite is drawn and ink
  is drawn over it with `PorterDuff.Mode.DARKEN`: each pixel is the darker of the two. Order-
  independent (so "not layered" is literally true), and right for a coloured ink later (min per
  channel). The Ratta **live preview is untouched** — the firmware draws it.
- **Routing by style, in the engine.** `StrokeStyle.PENCIL` bakes into graphite; every other
  style (`PEN` today — the gel pen and "Bring in ink") bakes into ink. The debug fill door
  (`PENCIL` strokes) lands in graphite. A `RasterLayer { GRAPHITE, INK }` enum in `gpaper-core`
  names them; the exact API shape (layer-qualified `getPageRaster` / `loadPageRaster` /
  `copyPageRaster` / `swapPageRaster` and a layer on `onRasterWillChange` / `onRasterChanged`, with
  the **un-layered methods kept meaning graphite** so Paintsprout compiles unchanged against a
  re-pin) is Fable's G1 brief.
- **The rubber never allocates the ink raster.** With decision 1, `eraseRasterAlong` targets
  graphite and `RasterRubbing` gains no field. A page with ink and no graphite rubs nothing.
- **One contact touches one raster.** A pencil stroke, a pen stroke, an eraser sweep and the ink
  bake each announce will-change / changed for exactly one layer, so the face's undo entry
  (`SketchEdit.RasterChanged`) carries a layer and reads tiles from that raster only — per-contact
  undo bytes do **not** double. `swapPageRaster(layer, patches)` is still its own inverse.
- **Two dirty flags, two saves, two parks.** `SketchSaveGovernor` becomes per-layer (a pencil
  scribble never re-encodes the ink); a save pushes only the dirty raster(s); `PendingPngPark`
  (renamed for the format) keys by page **and** layer. The one push lock and "newest wins, after"
  stand.
- **Seam: the chunk calls take a layer.** `SketchPageState` carries `graphiteBytes/Chunks` and
  `inkBytes/Chunks`; `readSketchChunk(layer, i)` and `saveSketchChunk(pageKey, layer, i, chunk,
  last)` **replace** the un-layered pair (no legacy to keep them for); `SketchHostSession` holds
  two read windows and two accumulators. `API_VERSION` 19 → **20**, and because the point's own
  calls change shape the **action floor `MIN_API_VERSION_FOR_SKETCH` moves 17 → 20** — a host or
  screen below 20 never binds the point at all. `MIN_API_VERSION_FOR_SKETCH_PAGES` (18) and
  `_TOOLS` (19) stay as history in the ledger; both are ≤ 20 and therefore inert.
- **The image guard becomes a WebP guard.** `PngHeader` → `ImageHeader` (`:extension-api`, pure,
  hand-parsed): `RIFF` + `WEBP`, then a `VP8L` chunk (signature `0x2F`, 14-bit width−1 /
  height−1) **or** a `VP8X` chunk (24-bit width−1 / height−1) — libwebp writes either for lossless
  with alpha. Exactly the page's size or refused, both ways, as today. A PNG is simply not a WebP
  and fails the guard.
- **Encoding**: `Bitmap.CompressFormat.WEBP_LOSSLESS` on API 30+, `WEBP` at quality 100 on 29
  (documented lossless there); `minSdk` is 29 in SN, `:ext-sketch` and g-paper. The `quality` of
  `WEBP_LOSSLESS` is an **effort** dial — its value is a Nomad measurement at G3 (encode ms vs.
  bytes on a fully shaded page), written down as a constant with the number beside it. Decode is
  `BitmapFactory` as today, sampled for the cover.
- **Blank means absent, per row.** An empty byte array for a layer clears that layer's row.
  `pagesWithSketch` / `hasLiveSketch` answer "either row live". A page with only ink has no
  graphite row and vice versa.
- **Reads that must not see the rows**: `childrenOf` excludes **both new names and the dead name
  `sketch`** (a leftover PNG row on a device must never surface as a child); `liveDescendantIds`
  carries both; `liveErasableIds` excludes both (Erase page stays ink-only, decision 11 of arc 43);
  page copy/cut/paste carries both; `clip_too_large_sketch` names either.
- **Export stays one sketch page per sketched page** — the flatten happens in `SketchRaster`;
  `bundlePositions`, naming (`<page> sketch`) and `ExportDelivery.perPage` are unchanged.
- **Cover** flattens both rows of the last-shown page over white; ink bake fallback unchanged.
- **Memory**: one more page-sized bitmap alive in the extension (9.5 MB Nomad / 18.4 MB Manta),
  allocated lazily like `pageRaster` is today — a page nobody has inked never allocates the ink
  raster. Measured at G1 (demo PSS) and G3 (face PSS), against K5's 64 MB.
- **Carried unchanged from arcs 43–44:** the six shades / twelve leads / 5 px gel pen; cadence
  16 ms, EMR floor 120, bake pressure 0.5, bake tilt 0; undo by gestures only; no lasso on the
  face; plain white paper; `WATCH_BYTES` 4 MB per row; tool memory in host prefs.

## Phases (letter G; one per session; ⬜ 🔄 🧪 ✅; each ends commit + push; ledger below)

| Phase | Owner | What lands | Gate |
|---|---|---|---|
| **G0 — Plan lands** ✅ | Fable | Branches `ink` (Notesprout) and `two-rasters` (g-paper); this file; g-paper `PLAN.md` Phase 26 ⬜; memory `project_ink_arc.md`. | Both branches pushed; `file` says this is text (the NUL trap). |
| **G1 — g-paper Phase 26 → 0.1.39: two rasters** ⬜ | Opus on Fable's brief; Fable reviews the diff; Sonnet runs tests + installs the demo | `RasterLayer`; `CanvasPaperView` holds `graphiteRaster` + `inkRaster` (both lazy); `compositeIntoRaster` routes by style; the committed-layer draw flattens with `DARKEN`; `eraseRasterAlong` rubs graphite only; layer-qualified `get/load/copy/swapPageRaster` + layered listener callbacks, un-layered forms = graphite; `RasterDirty` unchanged; `clear()` drops both. Demo: the raster toggle draws pencil + pen + rubs, and a "flatten to PNG" render before a panel sees it (g-paper `CLAUDE.md`'s rule). `docs/api.md` + `CLAUDE.md` in the same commit. | g-paper `./gradlew test` green (core + ratta + onyx); **Paintsprout Onyx green on its pin**; Nomad demo: pen over pencil then rub — graphite lifts, ink stays; redraw ms with the flatten vs. 0.1.38; demo PSS; 0.1.39 published to mavenLocal (by the user's hand if the classifier refuses). |
| **G2 — Seam + host data (API 20)** ⬜ | **Fable: the seam**; Opus: host side; Sonnet: test runs + doc rows | `ImageHeader` (WebP) replaces `PngHeader`; `SketchPageState` two streams; layered `readSketchChunk` / `saveSketchChunk`; `API_VERSION` 19 → 20, `MIN_API_VERSION_FOR_SKETCH` 17 → 20. Host: `TYPE_SKETCH_GRAPHITE` / `TYPE_SKETCH_INK` (+ the dead `sketch` in every exclusion), `SketchDao` / `SketchRepository` / `SketchRows` per layer, `SoilDao` lists, `SketchHostSession` two windows + two accumulators, `SketchHostBinder`, `SketchRaster` + `SketchCover` flatten, clip message. SN `docs/objects.md` (or wherever the row is specified) + root `docs/soil-file-format.md` if it names the row. | `:extension-api:testDebugUnitTest` (**the pin test — must be run**) + `:app:test` green; `PageClipTest` fixtures grown for the second row type. |
| **G3 — The face** ⬜ | Opus; Sonnet: the adb walk (**clear the dev library's sketches first**, then a blank page) | Re-pin g-paper 0.1.39 (`sn-screen/build.gradle.kts` + the pin sentences). `:ext-sketch` declares API 20. `RasterImage` encodes WebP lossless (effort constant from the measurement), decodes via `BitmapFactory`; `SketchSaveGovernor` / `SketchSaver` / the park per layer; `RasterEditBuilder` + `SketchEdit.RasterChanged` carry a layer; `openPage` loads both rasters; Bring in ink → ink raster; the delete/undo/redo replay paths re-index both. **Phase-start question:** the WebP effort value, after Sonnet's on-device encode table. | `:ext-sketch:test` + root `./gradlew test` green; Sonnet's Nomad adb walk (two rows in the `.soil`, bytes per row vs. the old PNG, encode ms, reopen 0-pixel diff of the flatten, PSS, `logcat -b crash` empty); then **the hand**: pencil over pen, pen over pencil, rub each way, undo/redo across both, Bring in ink then rub, page turns, export PDF/PNG, cover, copy/paste page. |
| **G4 — Walk adjustments** ⬜ | Opus (small); Fable if engine | Whatever the hand found (engine → another g-paper patch + Fable's review). Skipped if G3's walk is clean. | The user's word. |
| **G5 — Docs + freeze** ⬜ | Sonnet drafts; Fable reviews every draft | `docs/sketch.md` (the two rasters, the format, the seam, traps, numbers), SN `docs/extensions.md` (API 20, the floor move), SN `CLAUDE.md` (API 20, pin 0.1.39, counts, arc row), root `CLAUDE.md`, g-paper `PLAN.md` close, memory. **Phase-start question:** a `versionName` bump (T5's answer was no — the version moves at a release). Merge `--no-ff` in both repos **only on the user's word**; the Manta only if asked. | `file` on every touched doc = text; the final hand walk. |

**Order:** G0 → G1 → G2 → G3 → (G4) → G5. G2 does not depend on G1 and may go first if the Nomad
is not at hand; G3 needs both.

## Critical files

- **g-paper:** `gpaper-core/…/canvas/CanvasPaperView.kt` (`pageRaster` ~L192, `swapPageRaster`
  ~L608, `ensurePageRaster` ~L648, `compositeIntoRaster` ~L664, `eraseRasterAlong` ~L1640),
  `PaperView.kt` (`loadPageRaster` ~L256, `copyPageRaster` ~L273, `swapPageRaster` ~L298),
  `PaperListener.kt` (~L140/154), `RasterPatch.kt`, `RasterRubbing.kt` (unchanged),
  `geometry/RasterRub.kt` (unchanged), `gpaper-ratta/…/RattaPaperView.kt`,
  `gpaper-onyx/…/OnyxPaperView.kt`, `demo/…/MainActivity.kt`, `gradle.properties`
  (`GPAPER_VERSION`), `docs/api.md`, `CLAUDE.md`, `PLAN.md`.
- **Seam:** `apps/notesprout_sn/extension-api/…/SketchContract.kt`, `SketchPageState.kt` +
  `.aidl`, `ISketchHost.aidl`, `PngHeader.kt` → `ImageHeader.kt`, `ByteChunks.kt`,
  `ExtensionContract.kt`, `ExtensionContractTest`.
- **Host:** `data/soil/SoilSchema.kt` (`TYPE_SKETCH` ~L131), `SketchDao.kt`, `SketchRepository.kt`,
  `SketchRows.kt`, `SoilDao.kt`, `data/clip/ClipMessages.kt`, `extension/SketchHostBinder.kt`,
  `SketchEntry.kt`, `notebook/SketchHostHooks.kt`, `SketchRaster.kt`, `SketchCover.kt`,
  `NotebookSession.kt`, `NotebookActivity.kt`, `export/ExportRender.kt`, `ExportActivity.kt`.
- **Face:** `extensions/sketch/…/RasterImage.kt`, `RasterTiles.kt`, `SketchEdit.kt`,
  `SketchPush.kt`, `SketchSaveGovernor.kt`, `SketchSaver.kt`, `SketchActivity.kt` (~1230 lines —
  already past the guide; G3 may split the raster-load / save wiring out rather than grow it).

## Verification

- **JVM:** g-paper `./gradlew test`; SN root `./gradlew test` from `apps/notesprout_sn` (baseline
  3592 root at arc 44's freeze — the `test-results` XML counts every test twice, halve it);
  `:extension-api:testDebugUnitTest` at the bump. JVM cannot run Android's WebP encoder: the guard
  is tested on hand-built headers; the encode round-trip is a device item.
- **Device:** the Nomad `SN078D10012852` only; release extension packages `pm disable-user`'d for
  a `.dev` walk. adb cannot draw ink or double-tap — every feel judgment is the user's hand, given
  as a short numbered checklist. **The Nomad's dev-library sketches are removed before G3's
  walk** (decision 4).
- **Measured:** G1 — flatten redraw ms and demo PSS vs. 0.1.38; G3 — WebP bytes and encode ms per
  layer at each effort tried, against K5/T3's PNG numbers, face PSS vs. K5's 64 MB, undo entry
  bytes for a pen stroke vs. a pencil stroke.

## Open items parked as futures (each needs a fresh decision — not this arc)

An `inkLift` fraction (ink that resists rather than refuses) · a coloured or grey gel pen (the
RGBA choice is what makes it cheap) · quantizing graphite alpha to the panel's 16-grey ladder
before encoding (a large size win, lossy on non-e-ink export) · everything in `PENCILS_PLAN.md`'s
futures.

## Working protocol

1. One phase per session; the user clears context between phases.
2. Every phase starts by reading this file, `docs/sketch.md`, and the SN `CLAUDE.md` (G1: g-paper's
   `CLAUDE.md` + `PLAN.md` too).
3. Phase-start questions are wizard-style, one at a time, the explanation first.
4. Fable reviews every g-paper diff before publish and every Sonnet doc draft before commit.
5. Sub-agents never run git.
6. Walks on the Nomad only; the Manta only on the user's word.
7. Commit + push only on a green gate; one ledger entry per phase (Phase-start answers · Landed ·
   Deviations · Tests · Measured · Traps found · The hand · Next).

## Ledger

### G0 — Outcome (2026-09-17)

**Landed.** Branch `ink` here and `two-rasters` in `~/git/g-paper`, both from a clean `main`; this
file; g-paper `PLAN.md` Phase 26 ⬜ (scope = Fable's brief, gate named); memory
`project_ink_arc.md`. The five decisions above were taken wizard-style in the planning session,
after the exploration of the engine (`RasterRub`, `CanvasPaperView`'s one `pageRaster`,
`StrokeRenderer`'s bakes) and the face; three shapes were weighed before the two-raster model was
chosen (§ Context).

**Deviations.** None.

**Traps found.** None yet. Letter G verified unused across every `*_PLAN.md`.

**Next.** G1 — g-paper Phase 26. Needs the Nomad and the user's hand for the gate; G2 may go first
if it is not at hand.

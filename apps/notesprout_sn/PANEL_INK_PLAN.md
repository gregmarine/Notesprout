# Panel ink — the writing faces on the Supernote panel

**Status (2026-09-22): arc 49 "Panel" — P0 BUILT (g-paper Phase 42 → 0.1.56 on branch `panel-ink`, published to mavenLocal, demo installed on the Nomad), awaiting the user's Nomad walk of the demo's stroke page; decisions locked (§ 2), phases in § 3, ledger below.** The user's ask: the
sketch extension (arcs 43–48) opened new ways of capturing stylus input and showing strokes on the
Supernote; find where the ordinary vector-ink *writing* faces (notebook, scratch pad, calendar,
the event note, the sticky editor) can use them. Scope the user set in the wizard of 2026-09-22:
**Notesprout SN only**; every vector-ink face; the two techniques weighed are **app-painted live
ink with the firmware daemon off** and **true-tone greys for the pen** — dithered on the glass,
true tone in every export; **no pencil, no pressure width, no tilt**. Branch `tools`. The icon
swap that came with the ask landed first (bfabaf83: the ballpen is `ic_pen` everywhere a pen is
meant; the pencil glyph is `ic_pencil`, the sketch face's alone).

Sibling of `RATTA_PLAN.md` / `extensions/sketch/*_PLAN.md`: the survey is § 1, the user's
decisions go in § 2 as they are made, the phases in § 3. Read `extensions/sketch/docs/sketch.md`
§ "The Ratta preview" / "Always dithered" and g-paper `PLAN.md` § Phases 28–29 / 41 for the
path this builds on.

---

## 1. Survey

### 1.1 What the sketch face has, and how it gets it

On a **raster** page g-paper's `RattaPaperView` paints the live stroke itself: the vendor policy
lets any app open `/dev/ebc`, so the engine writes the dirty rect's pixels straight into the
panel's frame and issues one `DISPAREA` (mode 7 "grey16", flag 1 — Atelier's own call) per
drain. The panel never shows a grey: every displayed pixel is a **blue-noise dither** of the true
grey page image (black or white), live and after pen-up alike, and nothing settles (Phase 41).
The firmware ink daemon is **full-screen-disabled for every tool** on such a page, so there is no
overlay, no bake handoff, no clear ladder and no flash at pen-up — the preview *is* the final
picture. Exports, covers and the stored rasters stay true grey.

The host's whole opt-in is **one line**: `paper.pageMode = PageMode.RASTER` before content
(`extensions/sketch/.../SketchActivity.kt:288`). The gate is engine-internal:

```
directRaster = firmware && panel.isOpen && pageMode == PageMode.RASTER      // RattaPaperView.kt:500
ditherDisplayed = panel.isOpen && pageMode == PageMode.RASTER               // :1405
```

**Why stroke-mode pages were left out** (the only stated reason, in code and in the Phase 28 / 29
plans — never latency): *"everything this path draws is flattened against the page images and
there is nothing to flatten against in stroke mode"* (`RattaPaperView.kt:484-499`). The pencil
was the user's Phase 28 decision 3 ("pencil only — pen, rubber and stroke-mode pages keep the
firmware path"); Phase 29 added the pen and the rubber on raster pages and repeated "stroke-mode
pages keep the firmware path untouched."

Measured on the direct path (g-paper `PLAN.md`, `EbcPanel.kt:38-41`, `RattaPaperView.kt:892-900,
1650-1690`):

| What | Number |
|---|---|
| `DISPAREA` ioctl | 0.4–0.9 ms avg; **blocks up to ~65 ms** while a previous update is in flight |
| Toning + posting a live batch (pen) | under 1 ms an event |
| Pen-up whole-page dither rebuild | 494–551 ms → 160–200 ms after the coalescer |
| Pen-up rect dither on a dense pencil scribble | 651–848 ms (the pencil's grain, not the pen's line) |
| One more page-sized raster on the Nomad | ~9.6 MB PSS each |

### 1.2 How the writing faces draw today

One view class, `PaperView` via `GPaper.create(this)`; the Ratta engine is picked by the registry.
Five hosts, three wirings:

| Face | Module | Screen base | Notes |
|---|---|---|---|
| `NotebookActivity` | `:app` | plain `AppCompatActivity`, its own chrome/exclusion copy (`:4794-4837`) | BLOCK_ALL while Contents/Recents panels are up "because the daemon draws beneath any Android window" |
| `StickyEditorActivity` | `:app` | plain `AppCompatActivity` | smaller minted page + off-page fence bands (`:626-652`) |
| `ScratchPadActivity` | `:ext-scratchpad` | `InkScreenActivity` → `PaperScreenActivity` | the sketch face's own base |
| `CalendarActivity` | `:ext-calendar` | `InkScreenActivity` → `PaperScreenActivity` | template-as-page, double-tap hit-test |
| `EventEditorActivity` / `NoteSurface` | `:ext-calendar` | none — a bounded paper *view* in a form | arms nothing; ink held in memory until Save |

**Live ink** on every one of them is the firmware daemon's overlay, black, through the four-tone
ladder `RattaInkMap.firmwareColorFor` (BLACK ≤85 · DARK_GRAY ≤187 · GRAY ≤222 · LIGHT_GRAY). The
base engine draws **no** live stroke at all while the daemon is present (`rendersLiveStrokes =
!firmware`). **Committed ink** is the stroke's true ARGB through the window and the compositor —
no dither, no quantisation. **Exports, covers, thumbnails** are true grey — but every bake bitmap
is `RGB_565` (`PageRaster.kt:75`, `PagePreview.kt:82`, `TextCover.kt:104`, `PdfAssembly.kt:145`),
so a neutral grey lands slightly non-neutral and 5/6-bit.

**There is no pen panel and no grey on any writing face.** All four toolbars hard-arm opaque
black once (`NotebookToolbar.kt:58-63`, `ScratchToolbar.kt:66-71`, `CalendarToolbar.kt:103-108`,
`StickyEditorActivity.kt:211-216`); `NotebookToolbar.kt:20-23` records the R3 decision that
removed the pen panels — "five widths, five styles and sixteen greys" — while every stored stroke
keeps its authored width / style / colour and renders as authored. The only sixteen-tone palette
in the repo is the sketch face's (`SketchPalette.TONES`, `PaletteBar`).

**Nothing in SN pins Ratta ink**: no test names the firmware ladder, the overlay or the dither; the
four-tone ladder is pinned only in g-paper (`RattaInkMapTest`). The SN-facing engine surface a
direct stroke path touches is: `setExclusionRects` (seven sites), `releaseRender()` behind
`PenIdle` (≈twenty sites — the overlay clear before chrome), `isPenActive` (the pen-activity gate),
`releaseForHandoff` / `resumeDrawing`, `notifyContentChanged`, `renderToBitmap`, and the page-swap
law (`clearSelection → clearForContentSwap → setPageSize → setTemplate → loadStrokes`). None of
`settleDisplay`, `directRaster`, `rearmOnPenApproach` or `SupernoteInk` is reachable from SN.

### 1.3 What a direct **stroke-mode** path would be

A g-paper change — call it **Phase 42 "Ink on the panel"** — not an SN one. The missing piece is
exactly the stated blocker: a flatten base. On a raster page the base is the two page images; on
a stroke page it would be **the committed picture** — the same thing `recordCommitted()` already
draws into the `RenderNode` (white → template → below-strokes objects → strokes → above-strokes
objects), rendered once into a page-sized grey bitmap at page load and re-rendered (whole page or
the touched rect) after every commit, erase, undo, object edit and template change. Then the live
pen segment goes into a live mask exactly as `extendLiveInk` does today, `toneAndPost` dithers the
dirty rect over `committed ⊕ live`, and the panel takes it. At pen-up the stroke is committed,
the committed bitmap is re-rendered over the stroke's rect, and that rect is presented — no
`pendingBake`, no ladder.

What the daemon's absence **retires** on such a page: laws 1, 2 and 4 (co-presented frame, eaten
clears + the ≤450 ms retry ladder, `clearAll` between record and invalidate), the deferred bake
and `releaseFirmwareOverlay`, the barrel / lasso hover-stream suppresses (a full-screen disable
already stands), and the pen-approach re-arm *for ink*. What **stays**: the +2 / +3 px
registration shift (the digitizer's, not the daemon's), palm rejection and the hover stream, host
exclusion rects as stroke splitters, and the rule **"never invalidate the window mid-stroke"**
(the compositor rewrites frame 0 every 0.2–1.4 s; on a stroke page this becomes load-bearing for
the first time — the window must mirror the panel only at pen-up).

### 1.4 The costs and risks, per item

1. **The lasso's live trail.** On a direct page the daemon that drew the `LASSO_DASH` trail is
   off and the base draws none (`rendersLiveTrail = !firmware`): the sketch face ships with **no
   trail** because nothing there lassoes. Every writing face lassoes constantly. Two honest
   answers: (a) paint the trail ourselves on the panel — we own the pixels, so a dashed outline is
   a rect post like any other and its erasure a re-present of the committed picture underneath;
   (b) keep the daemon *on* for the lasso tools only — a per-tool daemon switch, which the direct
   path deliberately does not do today (one full-screen disable for every tool), and which brings
   the hover-latch timing back for that one switch. (a) is the one that keeps the page ours.
2. **Page-flip ghosting.** A dithered page is pure black-and-white, and the HWC's
   `getBestDisplayMode` reads that as two-level and picks the ghosting waveform; the notebook's
   anti-aliased greys ask for the clean sixteen-level one today and get it
   (`RattaPaperView.kt:236-246`). Dithering the stroke page imports the sketch face's page-flip
   ghosting into every writing face — the one place a **page-turn full refresh** (built, off by
   decision) would be re-decided.
3. **The committed bitmap's cost.** `renderToBitmap()` of a 1404×1711 raster flatten is 37 ms;
   a dense vector page (thousands of strokes plus text objects) re-rendered whole at every pen-up
   would cost more — the rect-bounded re-render is the design, and a dense page's first render at
   load is the number to measure. Memory: one page-sized grey bitmap per face (~2.4 MB as
   `ALPHA_8`, ~9.6 MB as ARGB); the sticky editor and the event note are far smaller.
4. **Erasers and undo.** The point eraser's live feedback today is the daemon's; on the direct
   path an erase is a stroke removal → rect re-render → rect present, the same shape as the
   rubber's per-batch post (`onRasterErasedBatch`). Undo/redo, page load and object edits are
   whole-page re-render + present (the sketch's loaded-page path, ~100–200 ms on the Nomad).
5. **Objects and templates.** Headings, text, links, shapes, sticky icons and the template are
   already part of the committed picture, so they come along for free in the flatten — but a
   template's own greys (guide lines) will show **dithered** too, as the sketch page's does.
6. **Text-entry and chrome over the page.** Every floating bar today calls `releaseRender()` so
   the daemon's overlay does not sit over chrome; on a direct page there is no overlay and the
   window is authoritative for chrome, which is exactly the sketch face's arrangement — the
   `PenIdle`-gated calls become no-ops, not hazards.
7. **Fallback.** When the panel refuses to open (`panel.isOpen` false) the page must stay the
   daemon's, with every law intact — as the sketch face does. So none of the daemon code goes
   away; it becomes the fallback branch, as it already is for raster pages.
8. **Greys are a reversal, not an addition.** True-tone grey ink on the writing faces means a
   shade choice comes back to the pen — the R3 decision removed exactly that. With the direct
   path the four-tone live limit that made greys pointless is gone (the panel dithers the true
   tone live), so the reason for the removal no longer holds; but re-adding it is the user's call,
   and the sketch face's `PaletteBar` on the pen's re-tap (arc 46) is the ready-made shape.
   Exports need `ARGB_8888` bake bitmaps for neutral greys.
9. **Measurement.** `probe-ebc`'s `DrawActivity` logs event → `DISPAREA` per stroke; the engine
   logs `live …: N events … tone Y ms total (max Z ms/event)`, `dither: rect … in N ms`, `panel:
   WxH presented in N ms`. There is no pen-to-glass photon rig; the hand walk is the truth, as it
   was for every sketch phase.

### 1.5 What does **not** transfer

The rubbing eraser, the finger smudge and "Bring in ink" are raster ideas with no vector
counterpart. The pencil, pressure-varied width and tilt are out of the user's scope. The Onyx /
BOOX engine is untouched by any of this (the sketch path is Ratta-only).

### 1.6 Recommendation

Do it as one g-paper phase plus one SN arc, staged so each face is a separate switch:

1. **g-paper Phase 42 "Ink on the panel"** — the stroke-mode direct path behind an explicit host
   opt-in (`paper.directInk = true`, off by default so no other consumer changes), the committed
   bitmap as flatten base, the app-painted lasso trail, the daemon as fallback. Walk it on the
   demo's stroke page first; measure the dense-page render and page-flip ghosting there.
2. **SN arc — opt the faces in one at a time**, notebook first (the largest surface, the most
   lasso), then pad and calendar (shared base), then the sticky editor and the event note.
3. **Greys last**, if the user re-decides R3: the pen's re-tap opens the shade panel, sixteen
   tones, `ARGB_8888` bakes.

---

## 2. Decisions (the user's, 2026-09-22 — never re-ask)

1. **Go.** The direct stroke-mode path is built: g-paper **Phase 42 "Ink on the panel"** (branch
   `panel-ink`, → 0.1.56) and SN **arc 49 "Panel"** on branch `tools`, this file the plan + ledger.
2. **The lasso trail is app-painted on the panel.** We own the pixels: the dashed outline is posted
   like a stroke segment and wiped by re-presenting the committed picture under it. No per-tool
   daemon switching, no trail-less lasso.
3. **No page-turn refresh** — the sketch face's arrangement. Revisit only if a walk shows a
   text-dense page ghosting worse than the sketch does.
4. **Greys come back to the pen: sixteen tones on the armed pen's re-tap** — the sketch face's
   `PaletteBar` shape (Atelier's sixteen in a 4 × 4, the pen glyph filled with the shade). This
   **reverses R3's removal** of the pen panels for the shade only; widths and styles stay as they
   are. Exports bake `ARGB_8888`.
5. **Every face with a pen button gets the shade panel** — notebook, scratch pad, calendar,
   sticky editor. The event note (no toolbar) stays black.
6. **One device-wide shade**, remembered in host prefs like the sketch face's tools — never in
   the `.soil`, never in a backup. Grey picked in the notebook is grey in the pad.
7. **All five faces opt in, notebook first**: notebook → scratch pad + calendar → event note →
   sticky editor. Each its own phase and walk; a face whose walk fails stays on the daemon.
8. **The point eraser's strokes vanish as the tip crosses them** — each erase batch re-renders
   and presents its rect at once; no eraser cursor trail.
9. **Recipe: Fable writes it all**, no subagents; the user walks each phase on the Nomad. No
   code-review agent unless asked.
10. Scope held from the wizard: **no pencil, no pressure width, no tilt** on the writing faces;
    SN only; the Onyx engine untouched.

### Derived shape (not decisions — the plan's own reading of them)

- The opt-in is an explicit host flag, **`PaperView.directInk`** (default false, so the demo, the
  sketch face and every other consumer are unchanged), honoured only when `firmware &&
  panel.isOpen && pageMode == STROKE`. When the panel refuses, the page is the daemon's with
  every law intact — the fallback branch, as it already is for raster pages.
- The flatten base on a stroke page is **the committed picture**: `drawCommittedContent` drawn
  into a page-sized `ALPHA_8`-equivalent grey bitmap (`committedGrey`) at load and re-drawn over
  the touched rect after every commit, erase batch, undo/redo, object edit (`notifyContentChanged`)
  and template change; whole page when the change is page-wide. Live ink goes into the existing
  `liveInk` mask through `extendLiveInk`; `toneAndPost` dithers `committedGrey ⊕ liveInk` over the
  dirty rect. The window mirrors at pen-up only (the "never invalidate mid-stroke" rule).
- The trail: a `LASSO_DASH`-style dashed polyline rasterised into a `liveTrail` mask and posted
  per segment; on close or cancel the trail's union rect is re-presented from `committedGrey`.
  The closed outline and selection chrome stay the window's (they are chrome, not ink).
- The device-wide shade for extension faces (pad, calendar) crosses the seam: one parcel tail on
  the ink host interface (`API_VERSION` 21 → 22), the sketch's `ISketchHost` tool-memory tails
  the model. `PaletteBar` + `ShadeIcon` + `SketchPalette.TONES` move from `:ext-sketch` to
  `:sn-screen` so the four toolbars and the sketch face share one panel.
- Every `releaseRender()` site behind `PenIdle` stays as it is: on a direct page it is a no-op,
  on a fallback page it is the overlay clear it always was.

---

## 3. Phases

| Phase | Where | What | Walk / gate |
|---|---|---|---|
| **P0 — g-paper Phase 42 "Ink on the panel"** | `~/git/g-paper`, branch `panel-ink` | `directInk` flag; `committedGrey` + its rect/whole re-render on every commit path; live pen through `extendLiveInk` → `toneAndPost` on stroke pages; pen-up = commit + rect re-render + present, no `pendingBake`; per-batch erase present (decision 8); undo/redo/load/object/template = whole-page present; app-painted lasso trail (decision 2); no page-turn refresh (decision 3); daemon fallback; the demo's stroke page gains a `directInk` toggle; JVM tests for the gate, the re-render rects, the trail's wipe; `PLAN.md` § Phase 42; publish 0.1.56 to mavenLocal | User walks the demo's stroke page on the Nomad: black pen, lasso trail + close, point eraser, undo, page turn, dense page (the fill door), chrome over the page. Measure: `live …` tone ms, `dither: rect … ms`, first render of a dense page, PSS |
| **P1 — the notebook** | `:sn-screen` pin → 0.1.56; `:app` `NotebookActivity` | `paper.directInk = true`; audit the notebook's own exclusion copy (`:4794-4837`) and the BLOCK_ALL-while-panels rule (still right: the window is authoritative); Contents/Recents/Insert/lasso popups over a direct page; `docs/notebook.md` | Nomad walk: write, lasso, erase, undo, objects, page turn, sticky icon, export unchanged |
| **P2 — pad + calendar + event note** | `:ext-scratchpad`, `:ext-calendar` | `directInk = true` in `PaperScreenActivity`'s init (pad + calendar inherit) and in `NoteSurface`; calendar template greys dithered — check the month grid reads; `docs/scratchpad.md`, `docs/calendar.md` | Nomad walk each; the event note's keyboard-up block |
| **P3 — the sticky editor** | `:app` `StickyEditorActivity` | `directInk = true`; the off-page fence bands as exclusion rects on a direct page (the panel post must clip to the minted page) | Nomad walk |
| **P4 — greys** | `:sn-screen`, `:extension-api`, `:app`, the four toolbars, `:ext-sketch` (the move) | `PaletteBar` / `ShadeIcon` / the sixteen tones into `:sn-screen`; re-tap the armed pen on `NotebookToolbar`, `ScratchToolbar`, `CalendarToolbar`, the sticky editor's bar → the panel; the pen glyph filled with the shade (`ic_pen` + `ic_pen_fill`, the ballpen); one device-wide shade in host prefs + the API 22 tail for extension faces; `ARGB_8888` in `PageRaster`, `PagePreview`, `TextCover`, `TemplateThumbnails`, `PdfAssembly`; tests: palette pin shared, codec round-trip of each tone, the tail's floor | Nomad walk: every tone live vs. export side by side (the sketch's own acceptance) |
| **P5 — freeze** | docs, root + SN `CLAUDE.md`, `BACKLOG.md` | This file's ledger; `docs/sn-screen.md`; the R3 reversal recorded where R3 is; memory | — |

Each phase ends with its ledger entry below; a phase whose walk fails is recorded and the next
one waits for the user's word.

### Ledger

- **P0 — g-paper Phase 42 "Ink on the panel" — BUILT 2026-09-22, walk pending** (g-paper
  branch `panel-ink`, commit 0becc76, 0.1.56 in mavenLocal; the demo APK installed on the
  Nomad). Built as the derived shape says, with these readings worth carrying:
  - The flatten base is a view-sized **ARGB** `committedPage` (~10 MB Nomad, ~20 MB Manta),
    not the plan's "`ALPHA_8`-equivalent grey": a `Canvas` into an `ALPHA_8` bitmap keeps
    coverage and loses luma, and a grey template line or a grey pen must dither as grey. It
    stands where the graphite image stood (`flattenBase`), so the live flatten, the display
    dither and every rect present are Phase 28–41's code unchanged; the ink image is absent.
    `RGB_565` would halve it at 6-bit luma — not taken; a walk decides whether memory bites.
  - **Pen-up is a composite, not a re-render**: the live layer goes into the image by the
    raster bake's integer `SRC_OVER` over the mark's runs, so the page holds exactly the
    panel's pixels and nothing moves at pen-up; a later whole re-render from the vector may
    differ by ±1/255 at a dither threshold (a dot flipping at a page reload — a device
    question).
  - **The pencil is not previewed on a stroke page** (`directInkStyle`: `PEN`, `BRUSH`,
    `CALLIGRAPHY`); decision 10 offers no pencil on the writing faces, and its stroke-page
    bake would be a second derivation of the grain. It appears at pen-up with the other
    styles — the demo's Style cycler will show that.
  - Four new core seams, all `protected`, Onyx untouched: `bakeAfterCommit(stroke)`,
    `onCommittedStrokesChanged(rect)`, `onLassoTrailExtended(points)`,
    `strokeEraseRedrawIntervalMs`; `rasterDirtyAlong` made `protected`. One public addition:
    `PaperView.directInk`.
  - **Every panel post is cut around the exclusion rects** (`PanelClip`) — on raster pages
    too (nothing changes on the sketch face, whose rects are empty). P1's audit of the
    notebook's exclusion copy is what makes that matter.
  - Both lassoes get the same 12/8 dashed trail; the daemon's x-stream for the lasso eraser
    is not reproduced.
  - The walk list is in g-paper `PLAN.md` § Phase 42's gate. After it: P1 pins `:sn-screen`
    to 0.1.56 and sets `paper.directInk = true` in `NotebookActivity`.

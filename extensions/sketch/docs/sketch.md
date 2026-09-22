# Sketch (arcs 43–46)

**NSE · Sketch** gives a notebook page a second surface beside its ink: **two raster pictures,
one flattened**, a graphite pencil and a rubbing eraser over a graphite bitmap, a gel pen (and
"Bring in ink") over an ink bitmap that the rubber never touches — over a page-sized surface. A
notebook created from the third radio (Handwritten / Text / **Sketch**) opens straight into this
face; any page of it may carry a sketch, the way any page may carry one document. The host owns
every `.soil` read and write, as it does for the document editor; the extension owns nothing but
the pixels on the glass while the showing lasts.

This is the user's 2026-09-15 decision, landing SN's **tenth** extension point (`ACTION_SKETCH` /
`ACTION_SKETCH_SCREEN`) and its **sixteenth** module — the second, after **NSE · Bible**, living
outside `apps/notesprout_sn`'s own Gradle root (`extensions/sketch/`, included by
`settings.gradle.kts`'s `projectDir`). `extensions/sketch/SKETCH_PLAN.md` is both the plan and the
ledger: the fourteen locked decisions, the derived shape, and the phase-by-phase record (K0–K7)
that every number and trap below comes from. Built on branch `sketch`; frozen at K8. The seam
itself — `ISketch`, `ISketchHost`, the two host-side-stub tails, the boundary-audit rows — is
documented once, in
[`apps/notesprout_sn/docs/extensions.md`](../../../apps/notesprout_sn/docs/extensions.md) § "The
Sketch point (arc 43)" and its § "Arc 45: two rasters"; this doc is the feature side and links
there rather than repeating it.

Arc 44 "Pencils" (2026-09-17, branch `pencils`, `PENCILS_PLAN.md` the plan + ledger, phases
T0–T5) grew the one pencil into **six shades, twelve leads and a gel pen**, all of it remembered
on the device.

Arc 45 "Ink" (2026-09-17/18, branch `ink`, `INK_PLAN.md` the plan + ledger, phases G0–G5, **G4
skipped**) is the user's decision that ink is more permanent than pencil: the one raster became
**two**, graphite and ink, the rubber confined to graphite, and the two flattened wherever the
sketch is seen. Complete on the user's Nomad hand walk of 2026-09-18 ("Clean!"). Before the merge,
the same day, the user narrowed the pencil's shades to **the four tones the firmware has** —
black, grey, light grey, white (levels 0 · 5 · 9 · 15) — the white lead being a lightener over
graphite; g-paper 0.1.40 lets it preview LIGHT_GRAY (see "Arc 45's decisions", 6).

Arc 46 "Palette" (2026-09-19, branch `tools`, `PALETTE_PLAN.md` the plan + ledger, phases Q0–Q4)
is the user's decision that, with the pencil and the pen going direct to the panel under a dither
(g-paper 0.1.41–0.1.43), the four-tone limit no longer applies: **Atelier's sixteen tones, for the
pencil and the gel pen alike; one pencil width (4 px, a real pencil's — **1 px since 2026-09-21** — the user's trials 4 → 2 → 1 after the flank came off); the shade panel on each
pen button's re-tap, Atelier's swatches in a 4 × 4; both pen glyphs wearing their own shade** —
and, from the first walk, **ink flattened over graphite** ("a white gel pen can write over
anything"; g-paper Phase 30 → **0.1.44**, amending arc 45's "no top and no bottom"). The seam
grew one parcel tail (`SketchToolSettings.penShade`, API 20 → 21). See "Arc 46's decisions"
below.

**Engine.** g-paper is pinned at **0.1.55** (**Phase 41, 2026-09-22: the panel is always dithered, nothing settles — see "Always dithered" below**; **Phase 40, 2026-09-22: the finger smudge, arc 48 — see "The smudge" below**; **Phase 39, 2026-09-21: the side-of-the-lead shading of arc 47 is OFF — "just normal pencil regardless of tilt"**: the Supernote pencil bakes upright always and draws with the round lead; the flank stays in `GraphiteGrain` as an opt-in no engine uses, and Phase 38 — its grain as the point's, in grey — was built, walked twice and withdrawn the same day because the live path could not afford it; both deferred in root `BACKLOG.md` § "NSE · Sketch — the flank"; Phase 37, 2026-09-21: a mark settles into tone **only** on the sketch face's **one-finger swipe down** or a page load — Phase 35's 2.5 s pause and Phase 32's tool / shade / rub / undo settles all **withdrawn**; see "The settle gesture" below; Phase 36 "The flank", arc 47 — see "Arc 47's decisions" below; Phases 31–34: the rubber settling before it posts, 2026-09-19: on the Supernote panel
and in the window a mark is a blue-noise dither only while it is **live** or **waiting** — it
settles into its true tone, the pen's line solid and the pencil's flecks each at their own alpha
in the lead's tone, at the next thing that is not a mark: a tool or shade pick, a rub, an undo, a
page load, or the host's `settleDisplay()` before chrome opens; `DitherFlatten.coverage` is the
one rule, `settled` the flag; a loaded page is shown settled), over
**0.1.44** (Phase 30, "Ink over graphite", 2026-09-19 — the flatten is ink `SRC_OVER` graphite,
see arc 46's decision 9; the paragraph below is 0.1.39's account of the two rasters and stands but
for the operator), over **0.1.39** (Phase 26, "Two rasters: graphite and ink",
2026-09-17): `CanvasPaperView` holds two lazily-allocated rasters, `graphiteRaster` and
`inkRaster`, each first allocated on its own first mark; `RasterLayer { GRAPHITE, INK }` names
them and `RasterLayer.of(style)` is the **one** routing site — `StrokeStyle.PENCIL` bakes into
graphite, every other style (the gel pen's `PEN`, "Bring in ink") bakes into ink.
`compositeIntoRaster` routes each stroke to its raster with one `Canvas` per layer actually
touched. Flatten is a single blit: `drawCommittedContent` draws graphite, then draws ink over it
with `PorterDuff.Mode.DARKEN` (**until 0.1.43** — since arc 46 / g-paper 0.1.44 the ink is drawn
plain **over** the graphite, `SRC_OVER`, ink on top; the rest of this paragraph is 0.1.39's
account) (each output pixel is the darker of the two — order-independent, so
`renderToBitmap()` **is** the flatten, with no "which is on top" to explain and no fringe where a
pencil stroke crosses a pen stroke). `eraseRasterAlong` names `graphiteRaster` only — the ink
raster is never read, allocated, or announced by an erase, so a page with ink and no graphite
rubs nothing (decision 1). The engine's whole raster API is now layer-qualified —
`load`/`get`/`copy`/`read`/`swapPageRaster` each take a `RasterLayer`, and
`PaperListener.onRasterWillChange`/`onRasterChanged` carry one — with the un-layered forms kept
as interface defaults meaning `GRAPHITE`, so a 0.1.38 host still compiles against the re-pin; the
un-layered listener is **silent for ink, deliberately** (its `readPageRaster(rect)` reads
graphite, so forwarding an ink change through it would hand the listener the wrong before-image).
Load and clear announce both layers, graphite first, whole page, even when one is empty. Over
0.1.38 (Phase 25, "Graphite on paper", 2026-09-17, post-freeze
maintenance on the user's Manta screencap — the 96 px lead baked as "a series of tiny lines": each
cross-section is a rigid comb turned to the smoothed travel direction, and ~2° of wobble × a 48 px
lever arm piles the rim's combs up every ~4 px. `GraphiteGrain` now loosens flecks along the
stroke in proportion to their distance from the centre line, streaks the skate *along* the stroke
instead of banding it across, and mottles by a **page-space** tooth field the sheet shares under
every stroke; total ink within 1 %, so the shade ladder below did not move. `gpaper-core` only, no
API change; walked by the user's hand on the Manta: "Sooooo much better"), over 0.1.37 (Phase 24,
"The lead goes wide on Ratta", the T3 hand walk of 2026-09-17 — `RattaEmr.EMR_MAX` lifted from 1200 to **9600**, 96 px, so every lead the
sketch face now offers previews at the width it bakes), over 0.1.36 (Phase 23, "Pencil preview
tones", T1 — `RattaInkMap.pencilPreviewFor`, arc 44's own preview ladder beside the untouched
`firmwareColorFor`), 0.1.35 (Phase 22, "The pencil bakes upright on Ratta", the 2026-09-17 Manta
walk — see Traps), 0.1.34 (Phase 21, "The door closes", K8), 0.1.33 (Phase 20, "A mark says where
it landed", K6) and 0.1.32 (Phase 19, "Ratta and the raster page", K1). The
four values K1 measured on the Nomad — cadence **16 ms**, `PENCIL`'s EMR floor **120**, a
`DARK_GRAY` preview needle, and a **constant bake pressure of 0.5** for `PENCIL` on Ratta — are
plain constants in `gpaper-ratta` and `gpaper-core`; the measurement door (`RattaTuning`, "a
measurement door, not host API") closed at K8 (g-paper Phase 21 → 0.1.34) and no longer exists; the numbers did not change, only their mutability. Nothing reads a system property
to pick a cadence any more. **Arc 44 moved none of the four**: the EMR floor **120**, the bake
pressure **0.5**, the bake tilt **0** (0.1.35's own fix) and the **16 ms** cadence all stand exactly
as K1/K8 froze them — only the EMR *ceiling* moved, and only for the pencil's widest leads.
**Arc 45 moved none of the four either** — the two-raster split is an engine-side routing and
flatten change, not a tuning one; cadence, EMR floor, bake pressure and bake tilt are exactly
what K1/K8/0.1.35 left them. **Over 0.1.41 / 0.1.42 / 0.1.43 (g-paper Phase 28 + maintenance + Phase 29, re-pinned
2026-09-19 as SN maintenance, not an arc):** the pencil previews direct on the panel and the
display is dithered (see "The Ratta preview" under Tools); on that path the bake pressure is the
hand's (0.5 only on the needle fallback), the tilt stays 0, the cadence and EMR floor are
untouched. The face decodes both rasters before loading either (one dither rebuild per page open,
~100–200 ms on the Nomad) and a page turn awaits the pixel copy, not the encode (§ Saves).
**0.1.43 (Phase 29):** the rubber and the gel pen go through the panel too — the daemon is off for
the whole sketch page, no overlay, no clear ladder there — and the pen-up bake is the live layer
composited (no second grain pass; a dense scribble's pen-up no longer costs ~1 s). One trade: a
lasso outline on a sketch page has no live dashed trail (nothing that ships lassoes one).

---

## The decisions

The user's, locked 2026-09-15 (`SKETCH_PLAN.md`); phase-start questions could not reopen them:

1. **Creation — a third radio, Handwritten / Text / Sketch**, exclusive. A Sketch notebook opens
   into the sketch face; every page keeps its ink canvas and optional document, and may also carry
   one sketch. A foreign file claiming both Text and Sketch bits reads as Text (logged).
2. **Where — `extensions/sketch` (`:ext-sketch`)**, the Bible module's own pattern: the tenth
   extension point, screen-owning, the Scratch Pad's shape. The host owns every `.soil` write.
3. **Pencil — Paintsprout's one pencil, as is**: `StrokeStyle.PENCIL`, 1.2 px, `#505050`, pressure
   → darkness on BOOX/Paintsprout, no tilt, no width choice, no colour. (Ratta bakes it at a
   constant pressure instead — see K1's measurement below; decision 3 stands unchanged for every
   other engine.) **Amended by arc 44** ("Pencils", the user's fresh decision of 2026-09-17 — §
   "Arc 44's decisions" below): "no width choice, no colour" is withdrawn — the face offers six
   greyscale shades and twelve leads, plus a gel pen. "No tilt" stands (the bake is upright since
   g-paper 0.1.35), which is exactly why a chosen tone is the only way to shade a sketch on
   Supernote. **Amended again by arc 46** ("Palette", 2026-09-19): the width choice is withdrawn
   — one 4 px lead — and the shades are the whole sixteen-level ladder, for the pen too.
4. **Eraser — the rubbing eraser** (g-paper's `RasterRubbing`, 12 px). Live lifting on Supernote
   was a measurement, not a design choice going in; K1 found it real.
5. **Export — the sketch is its own page**, after the ink page, in PDF/PNG export. Document-source
   export is unchanged.
6. **Cover — the sketch of the last-shown page** over paper white; falls back to the ink bake only
   when that page has no sketch.
7. **Page turns — the sketch screen turns pages itself**; the notebook catches up to the page it
   ended on when the screen closes. **Amended at the K5 hand walk** ("that was a mistake if I
   decided that"): the face also inserts and deletes pages, exactly as the notebook does (K5b).
   **Amended again the same day**: the face's own undo/redo gestures reverse one of those, so a
   delete can be taken back without leaving for the notebook (K5b).
8. **Ink bake — a "Bring in ink" door**: the page's bare strokes cross the bind and are composited
   as drawn, in black pen, as one undo entry. Link-wrapped and sticky ink are excluded.
9. **Toggle door — `btnSketch` on the notebook's bottom strip, left of Bible** — a further granted
   exception to "bottom bars are pager-only" (the third, after `btnBible` and the reader's `btnNotes`). Sketch notebooks only; `GONE`
   never disabled; mirrored in the collapsed overflow. The sketch screen's own top bar carries
   **Show pages** back.
10. **Paper — plain white, always**, under the sketch; no template crosses the seam.
11. **Erase page — ink only.** Erase page never touches the sketch row.
12. **Undo chrome — gestures only** (two-/three-finger taps via `PageGestures`); no arrows.
13. **Engine owner — Opus writes the g-paper Ratta phases on a Fable brief**; Fable reviews each
    diff and the Nomad numbers before `publishToMavenLocal` and the SN re-pin.
14. **Recipe** — Fable plans and orchestrates; Opus features; Sonnet scaffolding, XML, strings,
    docs, adb walks on the Nomad (`SN078D10012852`, `.dev` builds); no Haiku; no code review this
    arc (the same waiver family arcs 37–42 took).

### Arc 44's decisions (2026-09-17)

The user's, locked in `PENCILS_PLAN.md`; phase-start questions could not reopen them. Decisions 2,
3 and 4 were later **amended by the hand** on the T3 Nomad walk — both the original and the final
value are given.

1. **Ratta live preview across shades** — map the pencil shade to the nearest usable firmware tone
   (a g-paper change, `RattaInkMap.pencilPreviewFor`); thresholds settled by the user's hand at T1.
   The bake is always the true shade.
2. **Which shades** — original: fifteen, `#000000 … #EEEEEE` in `0x11` steps, default `#555555`
   (level 5). **Amended at T3's walk**: only levels 0, 5 and 9 preview *exactly* as they bake (the
   firmware's three tones), so the list settled at **six** — levels **1, 3, 5, 7, 9, 11** — every
   other rung from 1 to 11, no pure black on the pencil (the gel pen is black). Default stays
   level 5. **Amended again 2026-09-18 (arc 45's decision 6)**: **four** — levels **0, 5, 9, 15**,
   the firmware's four tones; see below.
3. **Which sizes** — original: five, 1.2 / 2 / 4 / 7 / 12 px. **Amended at T3's walk**: the hand
   walked every lead above 12 px against a lifted EMR ceiling and found none of them laggy, so the
   list widened to **twelve** — 1.2 · 2 · 4 · 7 · 12 · 16 · 20 · 24 · 32 · 48 · 64 · 96 px — in two
   rows of six under the one row of shades. Default stays the finest, 1.2 px.
4. **Gel pen** — `StrokeStyle.PEN`, black, one size, no options. **Amended at T3's walk**: the
   starting width of 3 px (the notebook pen's own) read "a tad small" by hand, moved to 7 px, then
   proved too heavy, and settled at **5 px**.
5. **Chrome** — a third top-bar tool button, Pencil · **Pen** · Eraser. Re-tapping the armed
   Pencil opens one `AnchoredBar` under it — shade swatches over a size row, the first tool-options
   bar on an SN paper screen since P1 removed the tool panels, granted for this face alone. The
   collapsed mini toolbar gains Pen.
6. **Memory** — remembered on the device: two `ISketchHost` tails, `API_VERSION` 18 → 19; the host
   keeps it in device-local prefs, one setting for all notebooks, never in the `.soil`, never in a
   backup.
7. **Pen glyph** — Tabler `ballpen` → `ic_ballpen` in `:sn-screen`'s shared drawables, hint "Pen".
   `ic_pen` (Tabler's pencil) stays on the Pencil.
8. **Naming** — Arc 44 "Pencils" · Notesprout branch `pencils` · g-paper branch `pencil-tones` ·
   letter T · `PENCILS_PLAN.md`.
9. **Models** — Fable orchestrates, writes the AIDL seam and reviews every g-paper diff and every
   doc draft; Opus codes; Sonnet scaffolds (XML / strings / drawables / doc drafts), runs JVM
   tests and walks the Nomad; no Haiku; no code review (arc 43's waiver carried).

### Arc 45's decisions (2026-09-17)

The user's, locked in `INK_PLAN.md`; phase-start questions could not reopen them:

1. **How the rubber treats ink** — **never erases.** The eraser rubs the graphite raster only;
   the ink raster is never read, never rubbed, never in an eraser's undo tiles. (A future
   "resists" would be an `inkLift` fraction on `RasterRubbing` — a fresh decision, not this arc.)
2. **Naming** — Arc 45 **"Ink"** · Notesprout branch **`ink`** · g-paper branch **`two-rasters`**
   (Phase 26 → 0.1.39) · letter **G** · `INK_PLAN.md`.
3. **Storage** — **two rows per page, `sketch_graphite` + `sketch_ink`**, symmetric names
   (`TYPE_SKETCH` renamed, every site with it); **each a page-sized lossless WebP with alpha**
   (RGBA — colour-ready for other platforms; grey+alpha PNG was declined for that reason). The
   6 MiB cursor-window refusal applies **per row**.
4. **Legacy** — **none.** No migration, no PNG sniffing, no warning. Existing `sketch` PNG rows
   on the Nomad dev library and the Manta release library are lost — "nothing there worth
   keeping." The Nomad's dev sketches were removed before G3's walk.
5. **Models** — unchanged from arcs 43–44: Fable orchestrates, writes the AIDL seam and the
   g-paper briefs, reviews every g-paper diff and every doc draft; Opus codes; Sonnet scaffolds,
   runs JVM tests and walks the Nomad; no Haiku; no code review.
6. **The palette is the firmware's four tones** (2026-09-18, before the merge; amends arc 44's
   decision 2 a second time) — **black, grey, light grey, white**: ladder levels **0 · 5 · 9 ·
   15**. 0, 5 and 9 are the three the hand found previewing exactly as they bake (BLACK /
   DARK_GRAY / GRAY); the six of 2026-09-17 kept three more that previewed a shade off, and a lead
   the hand aims wrong with is not worth its swatch. **White (level 15) is a different kind of
   lead**: the pencil's flecks go down `SRC_OVER` on the graphite raster, so a white one **pales
   the graphite under it** — precision lightening the rubber does not give — and lays nothing on
   bare paper under the `DARKEN` flatten. It previews **LIGHT_GRAY** on Ratta (g-paper Phase 27 →
   **0.1.40**: one rung on `pencilPreviewFor` above luma 246.5, so only white reaches it; every
   grey still tops out at GRAY, the hand's 2026-09-17 measurement standing). Default stays level 5.
   The gel pen is unchanged. `SketchPalette.SHADE_LEVELS` was the one line, plus a `WHITE_SHADE`
   name for the hint, and the armed swatch gained a black hairline at its fill's edge because the
   white gap ring says nothing against a white fill. This is arc 44's "white/highlight pencil"
   future, decided.

### Arc 46's decisions (2026-09-19)

The user's, locked in `PALETTE_PLAN.md`; phase-start questions could not reopen them:

The first Nomad walk (the same day) amended 2, 4 and 5 and added 9 — both the opening value and
the final one are given.

1. **No size choice.** One pencil width, "the size of a real pencil, whatever Atelier uses":
   **4 px** (`SketchPalette.PENCIL_WIDTH_PX`, arc 44's third lead; **1 px since 2026-09-21** — "let's try a smaller stroke size", 4 → 2 → 1, walk pending). Atelier's nib was never
   measured — its black HB reads back ~2.7 black px per px of stroke length on the Nomad
   (g-paper `probe-ebc/README.md`) — so 4 px is walked against Atelier by hand, and the one
   constant is the knob if it reads heavy. Withdraws arc 44's decision 3 (the twelve leads).
2. **All sixteen greys** for the pencil, ladder levels 0–15; white stays the lightener lead.
   Withdraws arc 45's decision 6 (the four tones) — that limit was the firmware needle's, and the
   direct panel path dithers every level honestly. **Amended at the first walk** ("the shades seem
   off from what Atelier has"): the sixteen are **Atelier's own tones**, verbatim — `ffffff dddddd
   d0d0d0 c8c8c8 c0c0c0 b6b6b6 aaaaaa a0a0a0 909090 888888 808080 707070 686868 606060 505050
   000000` — not the `0x11` ladder; `SketchPalette.TONES`, darkest first so 0 is black and 15 white
   as every earlier build stored them; the pencil's default is `#505050` (level 1, arc 43's exact
   tone).
3. **The gel pen takes the same sixteen-shade choice, white included** — the user's explicit
   choice over a fifteen-shade pen. (It drew nothing under `DARKEN`; decision 9 is what makes it
   a pen.)
4. **Swatches Atelier's way**: a filled circle of the shade, a white gap, a **dotted** black
   outer ring on every swatch; the **selected** swatch's outer ring is **solid**. **Amended at the
   first walk**: **4 × 4**, not two rows of eight, white at the top left as Atelier lays them out.
5. **Its own button and panel** — opened as a top-bar Palette button (Tabler `palette`, "Shades",
   after the eraser). **Withdrawn at the first walk**: "since pencil and pen have independent shade
   selections, it makes better sense to just attach the selection to the tool … my idea for the
   separate toolbar button was based on Atelier, and now I realize that doesn't make much sense."
   **Final**: the panel hangs under the **armed pen button on its re-tap** — the Pencil's for the
   pencil's shade, the Pen's for the pen's — on the top bar and on the mini row alike; no palette
   button, no overflow entry. It does not re-arm anything.
6. **Both pen buttons wear their own shade**: the Pencil fill stays; the ballpen glyph gains a
   fill body (`ic_ballpen_fill`) tinted with the pen's shade — on the top bar, the mini row, and
   the corner knob while that kind is armed.
7. **Naming** — Arc 46 "Palette" · branch **`tools`** (expected to carry further tool arcs) ·
   letter **Q** · `PALETTE_PLAN.md`. No `versionName` bump (version moves at a release).
8. **Recipe** unchanged from arcs 43–45; no code review (the standing waiver).
10. **The pen shows its true tone** (the second walk: "the panel is capable of showing the true
   tone without dithering … if the dithering is just so the stroke keeps with the nib, can we
   have it rebake with the true tone of the pen? I'm only asking about the pen tool, not
   pencil") — g-paper **Phase 31 → 0.1.45**: baked ink is displayed at its grey, on the panel and
   in the window; a live pen stroke still dithers so it lands under the nib; graphite stays a
   dither throughout. Covers and exports were never dithered and are unchanged. **Amended on the
   third walk** ("instead of on pen up … anything other than drawing will rebake with the correct
   tone"): the mark stays dithered at pen-up and **settles** in tone at the next non-drawing
   event — a tool or shade pick, a rub, an undo, a page load (g-paper Phase 32 → **0.1.46**), or
   the face's own `paper.settleDisplay()` before the shade panel, a chrome flip or the collapsed
   rows open (Phase 33 → **0.1.47**). **Amended 2026-09-21 (g-paper Phase 37 → 0.1.52):** a mark
   settles **only** on a **one-finger swipe down** (`settleDisplay()`) or a page load — the tool and
   shade picks, the rub, the undo, the chrome opens and the 2.5 s pause (Phase 35 → **0.1.50**)
   all withdrawn: each landed a settle the hand had not asked for ("undesired side effects"). **And the pencil the same way** (the fourth
   walk: "perhaps it will look more natural with real tones with the grain?" — Phase 34 →
   **0.1.48**): a settled fleck shows at its own alpha in the lead's tone, grain in grey; the
   dither is the live picture only.
9. **Ink over graphite** (the first walk: "the white pen should be able to write over the pencil.
   It correctly writes over the darker pen. In the real world, a white gel pen can write over
   anything"; asked plainly and decided) — the flatten is **`SRC_OVER`, ink on top, everywhere**
   the sketch is seen: g-paper **Phase 30 → 0.1.44** (`CanvasPaperView.drawRasterLayers`,
   `DitherFlatten.luma` + `band`), the host's `SketchRaster` (export) and `SketchCover`. The
   consequence, stated and accepted: **pencil drawn over an ink line is hidden by the ink** (real
   graphite mostly slides off dry gel ink). With a black pen the two operators are pixel-identical,
   so no existing page moves. **Amends arc 45's "no top and no bottom".**

**Derived** (not re-asked): no g-paper change *at Q0–Q4* (decision 9 brought one) — `penColor` on a `PEN` contact already dithers
live and bakes true grey into the ink raster (Phase 29), `penWidth` is plumbed unclamped and 4 px
sits above every floor; the seam grows **one compatible parcel tail** rather than moving a floor
(§ The seam); dropping the size menu removes the one place g-paper read `penWidth` un-latched
mid-contact.

**Derived** (reconciled from the G0 exploration, not re-asked): flatten = darken, everywhere the
two rasters become one picture; routing by style, at the one `RasterLayer.of(style)` site; the
rubber never allocates the ink raster; one contact touches one raster, so per-contact undo bytes
never double; two dirty flags / two saves / two parks (one push lock, FIFO, still stands); the
seam's chunk calls take a layer, replacing the un-layered pair outright (no legacy to keep them
for); the image guard becomes a WebP guard (`ImageHeader`); encoding is
`Bitmap.CompressFormat.WEBP_LOSSLESS` on API 30+, `WEBP` at quality 100 on 29 (`minSdk` 29); blank
means absent, per row; reads that must not see the rows exclude both new names **and** the dead
name `sketch`; export stays one sketch page per sketched page, the flatten happening inside
`SketchRaster`; the cover flattens both rows of the last-shown page; one more page-sized bitmap
alive in the extension (9.5 MB Nomad / 18.4 MB Manta, allocated lazily like `pageRaster` always
was).

Three shapes were weighed in the planning session and declined, so as not to be re-proposed: **a
colour key** ("pure black means ink", one bitmap, an `inkLift` fraction on the rubber, `DARKEN`
for pencil flecks over ink) — cheaper, but rests on the pencil never baking `#000000`, breaks the
day the pen gets a second colour, and scuffs the antialias fringe where ink crosses graphite;
**user-facing layers** (a layer UI, z-order, visibility) — the app never grows a layer panel; and
**one blob container** holding both rasters (declined: not a picture — a row a reader can decode
on its own is) and **a grey+alpha PNG** per raster (declined: colour is coming on other platforms,
so RGBA).

---

## The data

### Arc 47's decisions (2026-09-19/20)

The user's, locked in `SIDE_PLAN.md`; phase-start questions could not reopen them:

1. **Calibration first.** `probe-tilt` (a dependency-free g-paper app) walked on the Nomad and
   the Manta before any curve: the Ratta HAL puts signed **tilt-X in degrees** in `AXIS_TILT`
   and signed **tilt-Y in degrees** in `AXIS_ORIENTATION` (panel space, both live). The polar lean
   is `hypot(x, y)`; the azimuth `atan2(y, x)` turned panel→screen (`EbcGeometry.screenAzimuth`;
   the Nomad's 137° raw ≡ the Manta's 42°). The 2026-09-17 Manta bloom was this units bug, so
   g-paper Phase 28 decision 5 ("the Supernote pencil stays upright") is **amended**: upright
   for every ordinary grip, flank past a side threshold.
2. **Threshold from the hand:** tip only ≤ **45°**, smoothstep bloom 45° → **54°**, full flank
   past it. Writing never crossed 43° on either device; shading never dropped under 50°.
3. **Flank extent 20× the lead** — 80 px on the 4 px lead (`FLANK_EXTENT`, the knob).
4. **The flank pales as it widens** (`FLANK_LIGHTEN`, fitted to the user's own recorded
   shading strokes, not a synthetic sweep).
5. **Asymmetric**, because both Supernotes report a live lean direction: a one-sided capsule
   from the tip along the azimuth toward the barrel, densest at the tip, trailing off
   (`FLANK_TAIL_BARE`); a stroke dragged along its own lean stays thin. The user is
   right-handed, which fixed the sign from the Manta data; both devices confirmed on walks.
6. Arc 47 "Side", branch `tools`, letter R, `SIDE_PLAN.md`; g-paper Phase 36 → **0.1.51** on
   branch `side-lead` (merged to g-paper `main` at the freeze). Same recipe; no code review.

**As built (g-paper, no seam change).** `StrokePoint.azimuth` (a defaulted tail);
`CanvasPaperView.sampleTilt`/`sampleAzimuth` capture seams (core defaults unchanged; Ratta
decodes); `GraphiteGrain.Lead { ROUND, FLANK }` opt-in on `of`/`begin` — ROUND and every
sub-threshold flank mark are bit-identical to before; `rasterDirtyWidth` seam so an 80 px band
is not clipped from its own bake; `bakeTilt` passes the lean on the direct panel path, 0 on the
needle fallback. Pressure was already live (Phase 28) and keeps its role.

**Two walk findings that shaped it.** (1) The flank was clumpy on the device while the offline
synthetic stroke was even: the cause was the **fan** of cross-sections on a turning path
(stations 0.8 px apart; a hand's 0.24° median / 4.6° p95 turn per station slips the 80 px rim
up to 6 px, so combs crowd then part — worms). Found only by rendering the user's real probe
strokes (`FlankRenderHarness` + `GPAPER_PROBE_CSV`); fixed by depositing per unit of paper
swept and splitting stations where the fan opens (`FAN_SPLIT_MAX`). (2) The sheet's tooth
entered `catches` as an offset, which clamped light shading to nothing; the flank now takes it
as a multiplicative depth (`FLANK_TOOTH_DEPTH`). Lesson kept: **test the grain on the hand's
own recorded strokes, never a synthetic straight sweep.**

### The rows

A sketch is now **two** rows, one per raster: `type='sketch_graphite'`
(`SoilSchema.TYPE_SKETCH_GRAPHITE`) and `type='sketch_ink'` (`SoilSchema.TYPE_SKETCH_INK`), arc
45 / G2's rename of the single `TYPE_SKETCH` (now `SoilSchema.TYPE_SKETCH_DEAD = "sketch"`, kept
only as an **exclusion** name — decision 4 waived legacy outright, so a leftover PNG row from
before the rename is never read, never surfaced, never migrated, just excluded everywhere the
live names are). Both are additive row types, ordered outside every mark's stacking space at the
unchanged `SoilSchema.SKETCH_ORDER = -1`. Up to one live row of each kind per page, parented to
the page; `blob` is a **lossless WebP with alpha (RGBA)**, transparent where empty, **exactly**
the page's `width`×`height` — nothing else about either row is used. `SketchRows.typeFor(layer)`
answers the row name for a `RasterLayer`; `SketchRows.toRow(pageId, layer, …)` builds either row
the same way; `SketchRows.imageBytes` reads either row's blob back — the dead name is refused by
both.

**Minted on the first save per row, never on open.** `SketchRepository.get` only reads; a page
nobody has drawn on either raster has no rows at all, so a Sketch notebook costs what its sketches
cost. The first save to a raster `upsert`s a whole row with a fresh id; every save after it
rewrites that row's pixels in place (`SoilDao.setBlob`, `createdAt` kept) — a row per save would
make a notebook's size a function of how long the person worked rather than how much they drew. A
page inked on only one raster carries only that row: **blank means absent, per row** — an empty
byte array for a layer clears that layer's row and that layer's row alone, the other untouched.

**The `ImageHeader` guard stands in front of every decode, both ways**, replacing the PNG-era
`PngHeader`/IHDR check (arc 45 / G2): pure, hand-parsed, little-endian, checked on a save before
anything is written and on a read before anything is composited. It looks for `RIFF` + `WEBP`,
then either a `VP8L` chunk (signature `0x2F`, 14-bit width−1/height−1) or a `VP8X` chunk (24-bit
width−1/height−1) — libwebp writes one or the other for lossless-with-alpha; 25 bytes are enough
to parse a `VP8L` header, 30 for `VP8X`. A save whose committed bytes are not exactly the page's
declared size, in either chunk form, throws `SKETCH_BAD_IMAGE` (renamed from `SKETCH_BAD_PNG` at
G2 — both sides rebuilt, no legacy to keep the old name for) and writes nothing. A PNG is simply
not a WebP and fails the guard outright — there is no sniffing. **A row already stored that fails
the guard on read is soft-deleted, never overwritten** (`SketchRepository.get`) — the pixels are
unreadable either way, and leaving them would hand the same refusal to every future reader.

**Size caps are per row.** `SketchContract.WATCH_BYTES` = 4 MB is a silent log line, asked of
each raster independently — a page on its way to the real limit on *either* raster shows up in a
walk's log before it is a problem. `SketchContract.MAX_BYTES` = **6 MiB is a hard refusal, per
row**, thrown as `SKETCH_TOO_LARGE` for that layer alone, nothing written for it, the other
layer's row untouched. This is the sketch seam's one deliberate deviation from "never refuse" in
the whole app: a row crosses back out through a SQLCipher cursor window, and an image written
above that window can never be *read back* — accepting it would trade a refusal the person can
see today for pixels that quietly stop existing later.

**`updatedAt` is sacred, per row.** `sketchDigest(pageId, type)` (blob-free, `length(blob)`) is
asked first for whichever row is being saved, so an unequal length settles most saves without
reading a megabyte of pixels back out of the file; a save whose bytes are already stored writes
nothing at all for that row, so opening and closing a sketch untouched on both rasters never
re-flags the notebook for backup.

### Index and meta

`NotebookFlags.SKETCH = 8` (bit 4) travels with `NotebookFlags.TEXT_DOCUMENT` and is mirrored into
`notebook_meta.sketch` at every meta site: `NewNotebookActivity`, `TextDocumentCreate`,
`IndexRepository.createNotebook`/`importNotebookRow`, `NotebookImport.refreshMeta`, `ImportFlow`,
`ExportArtifact.stampExportedAt`, `NotebookSession.refreshMeta` — the `TEXT_DOCUMENT` wipe-trap
rule, applied a second time.

`NotebookKind { HANDWRITTEN, TEXT, SKETCH }` is the one place that turns flags into a kind and
back (`of`/`flagBits`/`fromMeta`/`metaConflicting`). It is pure — it cannot log — so the two sites
that can meet a foreign row (`NotebookSession.open`, the importer) ask `conflicting` beside it and
log the choice themselves. **TEXT wins when both bits are set**: no build of this app can write
both, so a row that does is foreign or damaged, and the two mistakes are not the same size — a text
document opened as a sketchbook shows a blank page where its words are, while a sketchbook opened as
a text document shows an empty editor with **Show pages** one tap away. The recoverable answer wins.

### Reads that must not see the rows

- `SoilDao.childrenOf` (untyped, blob-inclusive; feeds export bake, previews, labels, the link
  picker) excludes **all three names** — `sketch_graphite`, `sketch_ink`, and the dead `sketch` —
  so a leftover pre-rename PNG row on a device can never surface as a child either.
- `SoilDao.liveDescendantIds` (page copy/cut/paste/delete/undo) **carries both** live rows — a page
  copy or delete must take its sketches with it.
- `SoilDao.liveErasableIds` = `liveDescendantIds` **minus both** rows — what **Erase page**
  actually clears (decision 11, unchanged: ink stays out of Erase page's reach the same way
  graphite does). Erase page's undo/redo replay is unchanged: it still replays by id.
- Not in `liveContentIds`, not in any document-staleness whitelist, not in `ORDERED_TYPES` (never
  reachable from an objects paste).
- `SoilDao.hasLiveSketch` / `SketchDao.pagesWithSketch` answer **"either row live"** — a page inked
  on only one raster still counts as a sketched page.

`SketchDao` (separate from `SoilDao`, `DocumentDao`'s shape) reads **per type**:
`sketchDigest(pageId, type)` (blob-free existence + length) and `sketchFor(pageId, type)` (the
whole row, pixels included — the one read in the app that pulls a page-sized image), each asked
once per raster; `pagesWithSketch` (which of a notebook's live pages carry a live sketch on
*either* raster, asked once per notebook, ids only — K4's cover and K7's export bundle both use it
instead of a digest per page). `SketchRepository` is the **only writer** — `has` (either row
live) / `get` / `save` / `clear(pageId, layer, …)`, every one of the last three taking a
`RasterLayer` and applying every rule above **per row** — layered over `SketchDao` + `SoilDao`,
pure `suspend`, no Android classes, so every rule above is provable off-device.

### Clipboard

`ClipEnvelope.MAX_BYTES` (6 MB) is unchanged; a refused envelope that happens to be carrying
either sketch row names it (`ClipMessages.tooLarge`, `clip_too_large_sketch`: *"This page's sketch
makes the copy too large to hold on the clipboard. The clipboard was left unchanged."*) rather than
giving the generic size message — the message names the page's sketch, not which raster. A
cross-notebook paste into a non-Sketch notebook carries **both** rows along and never shows either
— the destination simply has a page with a sketch nobody can see.

---

## The seam

`ExtensionContract.API_VERSION` moved 16 → **17** for the birth of `ACTION_SKETCH` /
`ACTION_SKETCH_SCREEN` (`SketchContract.MIN_API_VERSION_FOR_SKETCH = 17`, a birth floor in
`MIN_API_VERSIONS` — no existing floor moved), → **18** at K5b for a compatible **method** tail
(`SketchContract.MIN_API_VERSION_FOR_SKETCH_PAGES = 18`, gating five later `ISketchHost` methods),
→ **19** at T2 for another compatible method tail (`MIN_API_VERSION_FOR_SKETCH_TOOLS = 19`) —
and, at **arc 45 / G2**, → **20**, where the birth floor itself finally moves (and at **arc 46 /
Q1**, → **21**, a parcel tail — § "Q1's tail" below):
`MIN_API_VERSION_FOR_SKETCH` **17 → 20**. This one is not a tail — the point's own chunk-transfer
codes 3–4 (`readSketchChunk`/`saveSketchChunk`) **changed shape in place** to take a layer rather
than growing a new pair beside them, so a host or screen on either side of the break simply never
binds the other at all: a screen declaring below 20 never sees `ACTION_SKETCH` offered, and a host
below 20 is refused by `:ext-sketch`'s own manifest declaration before any call is attempted.
`MIN_API_VERSION_FOR_SKETCH_PAGES` (18) and `MIN_API_VERSION_FOR_SKETCH_TOOLS` (19) stay in the
source as history — both are now ≤ 20 and therefore inert, since nothing binds below the action
floor to begin with. This is the family's first move of a *single point's* action floor (arc 22 /
X1 moved three points' floors at once, when floors were introduced — G2's own ledger corrects an
earlier "first ever" claim to that).

`ISketch` carries **no store** — the sixth screen-owning point and the second with none (the tag
manager's shape): nothing here has anything of its own to remember. The bookmark is the notebook's
own page and undo is in-session and dies with the showing; a store lent here would still be an
empty file and a binder to revoke, for nothing. **The one thing that does persist — which tool is
armed, and the pencil's and the pen's shades (arc 44 / T2, arc 46 / Q1) — crosses as small indices on the held
`ISketchHost`** rather than through a store of the face's own: `toolSettings()` /
`putToolSettings()` are two more tails on the *host's* binder, answered from the host's own
device-local prefs, never from a file this extension owns.

```
interface ISketch {
    void begin(ISketchHost host);   // hold host for the showing
    void end();                     // drop it, and any parked save
}
```

`ISketchHost` is the **second host-side stub** on any SN seam, after `IDocumentHost`, built to its
recipe: minted per showing, uid-gated in every method, revoked with the unbind.

```
interface ISketchHost {
    SketchPageState current();
    SketchPageState requestPage(int direction);            // PAGE_PREV/PAGE_NEXT; same page = edge
    byte[] readSketchChunk(int layer, int chunkIndex);
    void   saveSketchChunk(String pageKey, int layer, int chunkIndex, byte[] chunk, boolean last);
    int    requestInk(String pageKey);                      // chunk count; 0 = no bare ink, no exception
    List<WireStroke> readInkChunk(int chunkIndex);
    // K5b — method floor 18 (history; inert below the action floor of 20):
    SketchPageState insertPage(int direction);
    SketchPageState deletePage(String pageKey);
    int    pageContent(String pageKey);                     // bit 1 ink/objects, bit 2 document
    SketchPageState undoPage(String token);
    SketchPageState redoPage(String token);
    // T2 — method floor 19 (history; inert below the action floor of 20); the parcel grew a
    // fourth int, penShade, at arc 46 / Q1 (MIN_API_VERSION_FOR_SKETCH_PEN_SHADE = 21):
    SketchToolSettings toolSettings();
    void   putToolSettings(SketchToolSettings settings);
}
```

The pair `readSketchChunk`/`saveSketchChunk` **replaced** their un-layered predecessors in place
at arc 45 / G2 rather than growing a third pair beside them — there was no legacy to keep the old
shape for (decision 4) — which is exactly why the point's action floor had to move instead of a
method floor growing another tail.

`SketchContract.LAYER_GRAPHITE` = 0, `LAYER_INK` = 1, `LAYERS` (`[LAYER_GRAPHITE, LAYER_INK]`, the
order the host announces and flattens them in — graphite first, ink darkened over it), and
`isLayer(Int)` name the two rasters on the wire; a layer outside `LAYERS` on either chunk call is
`IllegalArgumentException`, checked **before** anything else about the call is read (an unknown
layer on `saveSketchChunk` never touches the accumulator).

**The WebP of the named layer crosses chunked, each layer independent**, at
`SketchContract.SKETCH_CHUNK_BYTES` = 512 KiB (`ByteChunks`, the `TextChunks` recipe applied to
bytes — no surrogate pairs, so `join` is plain concatenation). **An empty image is one empty
chunk**, never zero chunks: clearing a page's sketch on either raster rides the same shape as
every other save, and the host's per-layer accumulator has one path rather than two. Reads are a
pull — every state-answering call parks **both** images atomically in the host's per-layer **read
windows** alongside the `SketchPageState` it returns, and `readSketchChunk(layer, i)` serves the
window named; writes are a push — `saveSketchChunk` accumulates in order from chunk 0 **per
layer**, the last chunk commits that layer, and the accumulator re-checks the running total
against `MAX_BYTES` on every chunk (over it: `SKETCH_TOO_LARGE` for that layer alone, that layer's
accumulation reset, nothing written for it — the other layer's accumulation is untouched).

**A save is accepted for any live page of the open notebook**, named by `pageKey` — unlike the
document editor's mode-routed target, there is no routing guard here: the screen turns its own
pages, and a save flushed a moment after a turn still belongs to the page it was drawn on. What the
key still guarantees is that pixels can never land on a page nobody drew them on; a key naming a
deleted page is `IllegalArgumentException("Unknown page")`.

`SketchPageState`'s wire form is `pageKey · pageIndex · pageCount · width · height ·
graphiteBytes · graphiteChunks · inkBytes · inkChunks · structuralToken` — the single
`sketchBytes`/`sketchChunks` pair grew into two pairs at arc 45 / G2, one per raster, each chunk
count pinned to `ByteChunks.countFor(...Bytes)` so a hand-built state can never disagree with the
chunker that will serve it; `structuralToken` (K5b's compatible tail) stays last, the next tail's
slot, empty on every answer that is not a page insert or delete. `hasSketch` answers "**either**
raster live"; `hasLayer(layer)` / `bytesFor(layer)` / `chunksOf(layer)` answer the same questions
for one named raster.

**Only `SecurityException` / `IllegalArgumentException` / `IllegalStateException` may cross.** The
two typed `IllegalStateException` strings — `SKETCH_TOO_LARGE`, `SKETCH_BAD_IMAGE` (renamed from
`SKETCH_BAD_PNG` at G2, the format in the name) — are compared `==`, never `contains`. **There is
deliberately no "no ink" refusal**: `requestInk` on a bare page answers **0**, a legal count for a
legal question (the pad's zero-chunk park precedent) — the screen words its own "No ink on this
page" from the number, rather than handling an exception where a count would do.

### K5b's five tails

Added the same day as decisions 7's second amendment, all behind the method floor 18 — history
since arc 45 / G2 moved the action floor itself to 20, above it:

- **`insertPage(direction)`** — a blank page next to **the face's target**, not the notebook's
  displayed page, on the `PAGE_PREV`/`PAGE_NEXT` side named. The host inserts it exactly as its own
  notebook does (inherits the target's template and size), records **one entry on the notebook's
  own undo stack**, loads the read window with the new (empty) page, and answers its state carrying
  a fresh `structuralToken`.
- **`deletePage(pageKey)`** — must be the face's current target (`IllegalArgumentException`
  otherwise: pixels and pages can never be acted on at a distance). Soft-deletes the page and every
  live descendant it owns — strokes, objects, document, **and sketch** — as one entry on the
  notebook's own undo stack. Deleting a notebook's only page answers a fresh blank page rather than
  an empty notebook. The face **flushes the doomed page first** (`deletePageNow` →
  `saver.flushAndAwait()`), because what comes back on undo is what was last saved; a flush that
  fails is logged and the pixels stay parked. A push already in flight when the row goes is refused
  as an unknown page.
- **`pageContent(pageKey)`** — `PAGE_HAS_INK` (bit 1, bare strokes/objects) / `PAGE_HAS_DOCUMENT`
  (bit 2, a live document row); zero means nothing but the sketch. Exists purely so the delete
  confirm can name what else goes, rather than warn about content that is not there.
- **`undoPage(token)` / `redoPage(token)`** — take back or put back the insert/delete `token`
  names. `token` is a *name*, never a payload: the snapshot the edit needs to be reversible (the
  live page ids either side, the rows soft-deleted, where the notebook was standing) is the
  notebook's own undo record, kept on the notebook's own stack — the host looks the token up and
  replays it through the **same arm the notebook's own undo runs**, then moves that very entry
  across the notebook's stack. An unknown token (already undone, or the host died and came back) is
  `IllegalArgumentException`; the face drops that entry and carries on.

`SketchContract.MAX_STRUCTURAL_TOKEN_CHARS` = 64, space-free (a token is one word in a log line,
never displayed, never parsed).

### T2's two tails

Added arc 44 / T2 (2026-09-17), behind the method floor `SketchContract.MIN_API_VERSION_FOR_SKETCH_TOOLS`
= 19 (`API_VERSION` 18 → 19; at the time no action floor moved — `MIN_API_VERSION_FOR_SKETCH`
stayed 17, `MIN_API_VERSION_FOR_SKETCH_PAGES` stayed 18 untouched. History since arc 45 / G2 moved
the action floor to 20, above this one too):

- **`toolSettings(): SketchToolSettings?` / `putToolSettings(settings)`** — what this device last
  remembered the face's tools to be, and how the face pushes a new pick. **What crosses is
  indices, never values**: `SketchToolSettings(tool, shade, size, penShade)` is four small `Int`s
  (three until arc 46 / Q1), each bounded `0..SketchContract.MAX_TOOL_SETTING_INDEX` (255) — a
  **sanity** bound on an unmarshalled integer, not the palette's own size. The host never learns
  how many shades exist, never
  clamps a value against a palette it does not know, and a stored index this build no longer
  offers is not a wire error — `SketchToolState.fromSettings` reads it as an ordinary miss and
  falls back to that field's own default.
- **Null is the answer, not a failure.** `toolSettings()` answering null is the seam's word for
  "nothing has been remembered on this device yet" — a first showing, cleared app data — never an
  exception; the defaults live in `SketchToolState` on the face's side precisely so that answer
  can be given honestly.
- **The host never clamps or knows the palette.** `SketchToolCodec.decode` (the pure half of the
  host's prefs) repeats none of `SketchToolState`'s bounds; it only reads the stored integers as
  a legal `SketchToolSettings` or answers null. Retuning a width or a shade, or changing the
  offered ladder, is a change inside `:ext-sketch` alone.
- **The eraser is never remembered.** It is not a field of `SketchToolSettings` at all — a face
  that opened on the eraser would read as a broken pencil, and remembering it would need a fourth
  index for no reason the seam can name.
- **`SketchToolPrefs`** (`data/prefs/SketchToolPrefs.kt`) is the host's own device-local store:
  `SharedPreferences("sn_sketch_tools")` — deliberately **not** `sn_tool`, the name
  `SnApplication.LEGACY_TOOL_PREFS` deletes at every process start — one setting for every
  notebook, never the `.soil`, never a backup. `put` is one `edit()`/`apply()` of all four keys,
  so a reader can never see a new pencil shade against an old pen shade.
- **Pushed on every pick, fire-and-forget, on IO under a fair mutex.** `SketchActivity`'s
  `toolPushes` mutex serialises the pushes in pick order — Fable's one T3 review fix, after two
  quick picks raced on separate IO hops and left the device remembering the first of the two
  rather than the second.

### Q1's tail (arc 46)

Added arc 46 / Q1 (2026-09-19), `API_VERSION` 20 → **21**, named by
`SketchContract.MIN_API_VERSION_FOR_SKETCH_PEN_SHADE` = 21 — **a parcel tail, not a method**:

- **`SketchToolSettings` grows a fourth `int`, `penShade`** (the gel pen's ladder level, 0 =
  black), after `size`, read with the exhausted-parcel rule (`SketchPageState.structuralToken`'s):
  a three-int parcel from a 20 host reads as pen shade 0, and a four-int parcel handed to a 20
  host leaves one int unread. Neither side is broken, so **no floor moves** —
  `MIN_API_VERSION_FOR_SKETCH` stays 20 and a 20 screen still binds; the constant is named the
  way the method floors are, as the ledger of when the field arrived, and `:ext-sketch` declares
  21 because it is what it requires of the host.
- **`size` is a dead slot from arc 46 on.** The face offers one pencil width, writes 0 there and
  never reads it back; removing it would be an in-place shape change and would move the point's
  action floor for nothing. A remembered size from arc 44 is simply not read.
- **Host prefs**: a fourth key `pen_shade`; `SketchToolCodec.decode` reads a **missing** pen shade
  as 0 (the tail's own default, the exhausted-parcel rule mirrored in the file) so a device
  upgraded from arc 44/45 keeps its tool and pencil shade; any *present* value that cannot be an
  index still refuses the whole answer, as before. The host still never clamps.
- A contract test asserting this floor is **above** the action floor is the correct sense (the
  reverse of G2's inversion, § Traps).

### `SketchHostSession` and `SketchHostBinder`

`SketchHostSession` (pure) holds **two** read windows and **two** save accumulators, one per
raster, under one monitor — `setWindows(pageKey, graphite, ink): Windows` sets both atomically
with the state they belong to, `readChunk(layer, i)` serves the named window, and
`acceptChunk(pageKey, layer, …): Commit(pageKey, layer, bytes)` accumulates that layer alone (a
key is taken from the save's chunk 0 and every later chunk for that layer must repeat it, in
order; per-chunk and running-total caps enforce `MAX_BYTES`; an empty join is a clear) — plus the
ink window (staged `WireStroke` chunks, 0 legal), unchanged. **The two accumulations are
independent**: a refusal on one layer (`SKETCH_TOO_LARGE`, a bad key) leaves the other's
accumulation exactly where it was, and an unknown layer is refused **before** anything else about
the call is read. **A window swap mid-save leaves the accumulation exactly where it was** — a
flush a moment after a page turn still lands on the page it was drawn on. `SketchHostBinder` is
the `ISketchHost.Stub`: `gate()` is still the first statement of all **thirteen** methods (the
un-layered pair was **replaced** by the layered pair in place, not added to — the count did not
grow), a `hook {}` funnel keeps only marshalable exceptions crossing, log lines name the layer,
and `revoke()` clears the session.

### `SketchClient` and `SketchEntry`

`SketchClient` is `DocumentEditorClient`'s shape minus the store: mint the binder, `hold`,
`begin(host)` ≤ 2 s, launch the package-pinned screen Intent; `finish()` is `end()` ≤ 15 s then
unbind + revoke in `finally`. `SketchEntry` (`DocumentEditorEntry` + `ExtensionScreenEntry`'s paper
pieces) is the notebook's door: open drains, binds, begins, sets `EXTRA_CHROME_HIDDEN`, dismisses
floating chrome, ends any running transform, calls `releaseForHandoff`, then launches and attaches
to the surface stack; the result pops chrome, reclaims the pipeline, runs a lazy finish job, and
calls `onClosed(code)`. `SketchEntry.discovered()` is true only when the notebook **is** a Sketch
notebook *and* a trusted `NSE · Sketch` is installed — one answer, one place visibility is decided,
and `openIntoSketch` **awaits** it rather than reading a cached flag (reading `isAvailable` loses
the discovery race about half the time).

---

## The screen

`SketchActivity : PaperScreenActivity` — SN's sixth screen-owning extension point, built on
`:ext-ink`'s `PaperScreenActivity` (lifted out of `InkScreenActivity` at K2 for exactly this
reuse): chrome bars and their toggle, the collapsed corner button, the free band, stylus-vs-finger
dispatch, the pen-idle gate, and the EPD handoff are all inherited; what lives in this file is what
a *raster page* is.

**The caller check runs first**, before anything is inflated: the screen is exported (it has to be
— the host launches it by action) and `HostCallerCheck.enforce` refuses anything but a
`startActivityForResult` from the host package. `onScreenDestroyed` still runs on a bounced launch
(the Bible's B2 lesson) — guarded with `::saver.isInitialized`, after one bug shipped without it
(see Traps).

**Tools.** `pageMode = RASTER` before any content loads; the toolbar carries three tools — Pencil ·
Pen · Eraser (arc 44 / T3) — over
`RasterRubbing()` defaults, **nothing set in the removed `RattaTuning`** — the K1 measurements are
the engine's own defaults now. Both pens are `Tool.PEN` to g-paper; which kind is armed, and each
kind's own shade, live in `SketchToolbar`'s `SketchToolState` and are applied to the engine as
`penStyle`/`penWidth`/`penColor` — never a
second tool for g-paper to know about. `smartLassoEnabled = false`, `scribbleEraseEnabled = false`
(hatching is not a gesture); `collapsedTools()` still answers `[PEN, ERASER]` — two slots, not
three — because the PEN slot itself carries **both** kinds via `collapsedPenKinds()`
(`CollapsedChrome.PenKinds`), so the collapsed mini row reads Pencil · Pen · Eraser, the top bar's
own order, with no change to how many tools the base class thinks this screen has. Overflow behind
`…` is unchanged: Back · Bring in ink · Show pages. There is no `EraserBar` — Point and Lasso are stroke erasers, and a raster page has neither.

**Always dithered (2026-09-22, g-paper Phase 41 → 0.1.55).** The Supernote panel shows the
dither always — pencil and pen, a loaded page included — and nothing settles: `RattaPaperView.
DISPLAY_SETTLES` is false, `settleDisplay()` is a no-op there, and the face's swipe-down binding
below is gone (the gesture is unassigned on this surface again). The page images, the covers and
every export keep the true greys, so **the glass is the dither and the export is the tone** — the
user's word with both pictures side by side: "the dither looks great on the device, and the true
tone looks great on the Mac. But the dither looks awful on Mac and the true tone doesn't quite
look right on the device." Existing sketches show dithered from their next load; their stored
pixels are untouched. The paragraph below is history.

**The settle gesture (2026-09-21, withdrawn 2026-09-22).** A **one-finger swipe down** on the page called
`paper.settleDisplay()`: every mark still on the glass as the dither it was drawn under goes to
its true tone — the pen's line solid, the pencil's grain in grey. It is `PageGestures.onSwipeDown`,
the notebook's Contents gesture, which this surface never used (a sketch has no Contents); the
face overrides it in `gestureListener`. It replaces the 2.5 s pause settle (g-paper Phase 35 →
0.1.50), withdrawn in **0.1.52** (Phase 37) on the user's word — a settle the hand did not ask for
landed where it was about to draw. Pen-gated by the detector like every finger gesture; a finger is
never the pen, so the settle posts straight to the panel; with nothing waiting it is a no-op. The
**It and a page load are the only settles** (first-walk amendment the same day: "only … the swipe
gesture, page flip, or closing the sketch. No longer on tool change"): a tool or shade pick, a rub,
an undo and the chrome opens no longer settle — g-paper's setter and raster-change settles are
gone, and the face's, `PaperScreenActivity.toggleChrome`'s and `CollapsedChrome.open`'s calls to
`settleDisplay()` with them (their reason, a setter settle painting over a freshly opened panel,
no longer exists). Closing the sketch needs nothing: the notebook shows the flattened rasters.

**The smudge (arc 48, 2026-09-22).** A **one-finger back-and-forth** on the page rubs the
graphite under it the way a finger blends pencil on paper: separate lines run together into a
tone. It is not a tool and never the stylus — the user's word: "a single-finger gesture of a
natural rub feel … rapid and in short distances, so it doesn't trigger the swipe gesture" — and it
touches the **pencil only**: the ink raster is never read, never allocated, never announced (the
rubber's rule, held by the same construction). It **blends and redistributes, never lifts and never
clumps**: under the finger every graphite pixel is pulled toward the mean of its neighbourhood
(g-paper Phase 40 → **0.1.54**, `RasterSmudge`: a box of `spread` px on premultiplied channels, so
bare paper lends emptiness and never colour; `strength × coverage` per batch over the rubber's own
feathered corridor; `loss` pales it a little after the blend, as a real smudge does; the graphite the
blur pushes past the corridor's edge is the other honest paling). **Once per pixel per pass**, again on
each reversal of travel — the rubber's pass mask, so a dense digitizer or a slow hand blends no
more than a quick one (the first Nomad probe compounded per batch: 240 batches for eight strokes
of the arm left 1 % of the tone). `RasterSmudging(strength 0.45, spread 6, feather 0.5, loss 0.02, gamma 3, carry 40, deposit 0.5)`
and `smudgeRadius` 32 px (a fingertip at 300 ppi) are the knobs, set on the engine's defaults.
**`gamma` is the tone it converges to** (the user's first walk: "it looks like it is removing it"):
the pencil lays sparse dark flecks and the eye reads a hatch by them, so their plain mean spread
evenly across the corridor is a pale wash; the target is the mean of darkness raised to `gamma`
and brought back by the root — at 2 the root mean square, so a hatch settles denser than its
mean, the way crushed graphite reads, and an even corridor is its own fixed point. **Cost is the
swept area** (g-paper's per-segment coverage field, samples thinned under 2 px, batches chunked
at 16, a warn line over 30 ms): the first build tested every pixel against every segment and the
user's real finger hung the Nomad 12 s on one event — an ANR that closed the face, not a crash.
**The finger carries graphite** (the second walk: "it should be able to smudge out past the
boundary … a gradient depletion … just enough to dirty the paper under it"): the darkest local
tone under the finger's core is its load; it decays by `e^(−travel / carry)` px of travel, is
topped up wherever the finger crosses something darker, and under the finger a pixel is pulled at
least to `deposit` of it, in the load's colour where the pixel has none of its own — so a rub out
of a mark leaves a fading trail about three carries long. `gamma` went 2 → 3 the same walk ("too
light … closer to the original").

The recogniser is the face's (`SmudgeRub`, pure Kotlin): a sequence that began on one finger, on
the page, with the pen gate open arms on the first **reversal of travel** — hops of
`SMUDGE_HOP_PX` 10 px, a turn past 120° — while the finger is still within `SMUDGE_ARM_WITHIN_PX`
280 px of where it landed. A swipe goes one way and never arms; a swipe that bounced turns back far
from home and never arms; the swipe's own floor is 30 % of the axis (421 px on the Nomad), so the
two cannot meet. The samples before arming are delivered as the first batch, so the rub's first
stroke smudges too. It is fed from `SketchActivity.dispatchTouchEvent` **before** the base feeds
`PageGestures`, and `standDown = { smudge.active }` stands every page gesture down on the event
that armed it (the detector cancels all and ignores the rest of the sequence). The gate
(`!paper.isPenActive`) is re-asked at the turn and on every batch — a pen arriving mid-rub ends the
smudge; a second finger ends an armed rub and kills an unarmed one; a lift ends it.

On the engine side the sweep is the rubber's with the arithmetic swapped: `beginSmudge()` →
`smudgeAlong(points)` per batch (the previous batch's last sample chained on) → `endSmudge()`. Each
batch announces `onRasterWillChange(GRAPHITE, rect)` → blend → `onRasterChanged(GRAPHITE, rect)`,
so the face's per-contact undo entry ([Undo](#undo)) and the save governor need nothing new;
`endSmudge()` fires `onPenLifted`, which closes the entry. On the Nomad the blended corridor goes
straight into the panel per batch (`onRasterSmudgedBatch` → `toneAndPost`, the rubber's posting);
a smudge settles nothing that is waiting (Phase 37's rule — the swipe down still does that).
`extensions/sketch/SMUDGE_PLAN.md` is the plan + ledger. **Debug builds carry a probe**: the
broadcast `<ext pkg>.SMUDGE_PROBE --ei x --ei y --ei half --ei passes --ei step --ei hist`
synthesises a finger back-and-forth through `dispatchTouchEvent` (window coordinates), because
`adb shell input` takes about a second per event — the long-press fires first — and the touch
node is not shell-writable. Release never compiles it in.

### Tools (arcs 44–46)

- **`SketchPalette`** (`:ext-sketch`, pure Kotlin) is the one place the tones and the two widths
  are written down. **`TONES` is Atelier's sixteen, darkest first** (decision 2 as amended:
  `000000 505050 606060 686868 707070 808080 888888 909090 a0a0a0 aaaaaa b6b6b6 c0c0c0 c8c8c8
  d0d0d0 dddddd ffffff`), and a stored shade is **a level — a position in `TONES` — never a
  position in `SHADE_LEVELS`**, the list of levels this build offers (all sixteen). Kept darkest
  first so 0 is black and 15 is white, the two values every earlier build stored (the `0x11`
  ladder before arc 46 had the same ends). `SHADE_LEVELS` is the one line to change to offer a
  different subset; a device remembering a level a build lacks reads as that kind's default.
  `BLACK_SHADE` (0) and `WHITE_SHADE` (15) name the two ends for their hints. `ROW_BREAK` = **4**:
  `shadeRows()` is **four rows of four, white first** (Atelier's order, `SHADE_LEVELS.asReversed()`
  chunked). **There are no sizes** (decision 1): `PENCIL_WIDTH_PX` = **4** is the pencil's one
  lead and `PEN_WIDTH_PX` = 5 the gel pen's one width, settled at T3's walk. `DEFAULT_SHADE` =
  **1** (`#505050`, arc 43's exact tone) is the pencil's default, `DEFAULT_PEN_SHADE` = 0 the
  pen's; `shade(level, fallback)` folds an unoffered level onto the fallback the caller names, so
  each kind falls to its own.
- **`SketchToolState`** (`:ext-sketch`, pure Kotlin) is the armed kind plus **a shade per kind** —
  `tool · pencilShade · penShade`, one seam parcel (`toSettings()`/`fromSettings()`, `size`
  written 0 and never read). Both kinds are `Tool.PEN` to g-paper, so "which pen is armed" has
  nowhere else to live; each kind's shade is kept **through** a switch to the other and back —
  picking the pen is not forgetting the pencil, and a grey chosen for the pen is not the
  pencil's. `withShade(level)` moves the **armed kind's** shade only; `armedShade` is what the
  panel paints as selected; `pencilReport` / `penReport` are what the two glyphs wear, **whatever
  kind is armed** — a button says what a tap on it will bring back. Every field read from outside
  comes in through `of`/`fromSettings` and falls back to its own default independently.
- **`PaletteBar`** (`:ext-sketch`, over `:sn-screen`'s `AnchoredBar`; arc 44 / T3's `PencilBar`
  remade) is the shade panel, the `EraserBar` pattern applied to four rows of four. A swatch is not
  an icon button: **Atelier's shape** (decision 4) — the grey itself as a filled circle with a
  1 dp black hairline at its edge (what makes the white lead a circle against the white bar), a
  white gap, and a 2 dp black outer ring, **dotted** (`DashPathEffect`, 2 dp on / 2 dp off) on
  every swatch and **solid** on the selected one — painted in `onDraw`, because the fill has to
  read at every level offered and a cell border says nothing on black. The bar is hung under the
  **armed pen button by its re-tap** — the Pencil's for the pencil's shade, the Pen's for the
  pen's, on the top bar or the mini row (`PaperToolbar.onPenReTap(alt)` and
  `CollapsedChrome.PenKinds.onReTap(alt, anchor)`, both taking the kind since arc 46; the mini
  row's alt button is its own anchor); it **stays open after a pick** and closes on that button's
  next re-tap, any tool change, a page swap, a finger gesture, a chrome flip, a contact outside
  it, or the exit — every dismissal path is in T3's ledger, unchanged. It edits the armed kind's
  shade and re-arms nothing.
- **`ShadeIcon`** (`:ext-sketch`; arc 44 / T3's `PencilIcon` generalised) is a `LayerDrawable`
  recipe: a fill body (`ic_pen_fill` / `ic_ballpen_fill`), tinted with that kind's shade, under the
  untouched black outline (`ic_pen` / `ic_ballpen`) — `pencil(ctx, ink)` and `pen(ctx, ink)` are
  the two glyphs it knows. Black reads as a fully black glyph, every other level as a black
  outline with that grey inside it. Worn by the top bar's two pen buttons, the mini row's two, and
  the collapsed corner knob (the armed kind's — `CollapsedChrome.PenKinds.primaryIcon` /
  `altIcon`, the latter arc 46's one `:sn-screen` addition beside `syncAltPen`) — one recipe, not
  four spellings of it. Each fill stays put while the other kind or the rubber is armed.
- **`ic_ballpen`** (`:sn-screen`, Tabler's `ballpen`) is the gel pen's glyph, in the shared
  vocabulary rather than `:ext-sketch` alone, beside `PaperToolbar`'s new `btnAltPen` /
  `altPenArmed` / `onPenKindPicked` / `onPenReTap` (a defaulted, trailing parameter set — every
  existing caller with one pen is unchanged) and `CollapsedChrome`'s `PenKinds` (the alt button
  built right after the primary one, so the row reads Pencil · Pen · Eraser) and the three
  defaulted `PaperScreenActivity` hooks the sketch face overrides (`collapsedPenKinds` ·
  `onCollapsedClosing` · `keepCollapsedUnder`). Both bars test which kind is armed against
  `CollapsedTools.penButtonSelected(tool, altArmed, isAltButton)` — one rule, asked by both, never
  two spellings of "is this the armed pen?".
- **Restore and Bring in ink.** `SketchActivity.restoreTools()` reads `toolSettings()` and applies
  the answer inside `openPage`, **before** `opened = true` — before the first mark on the glass is
  possible — so nothing is ever drawn with a pencil the person did not choose. `restorePen()` puts
  the armed pen back on the engine after "Bring in ink" or the debug fill door composite: those two
  bake **strokes**, which carry their own colour/width/style in `PageMode.RASTER` rather than the
  armed pen's, so nothing is disturbed today — the call is defence against that ever changing.
- **The Ratta preview — direct on the panel since g-paper 0.1.41 (Phase 28 "Graphite on the
  panel", re-pinned 2026-09-19, 0.1.42).** The pencil no longer previews through the firmware
  needle at all: the engine opens `/dev/ebc` itself (the vendor policy lets any app), lays the
  stroke's own grain live into the panel's frame, and shows the page — live and after pen-up —
  as a **blue-noise dither** of the true-grey page image, black or white per pixel, so every
  shade lands under the nib on the panel's first frame and nothing changes at pen-up. The page
  image, the WebP rows, covers and exports stay true grey; only what the glass shows is dithered.
  Pressure is back on the Supernote pencil (the constant-0.5 bake was a compensation for the
  needle); the pencil stays upright (no tilt widening — the user's decision). The daemon is
  full-screen-disabled while the pencil is armed; the gel pen and the rubber still use it. The
  firmware-needle path (`pencilPreviewFor`, the four-tone ladder, the 0.5 / 0 bake constants) is
  now the **fallback** when the panel refuses to open. Reference: g-paper `PLAN.md` § Phase 28 and
  `probe-ebc/README.md` (the measurements, incl. Atelier's shade ladder).
- **`SketchLayers`** (`:ext-sketch`, arc 45 / G3, pure Kotlin) is the **one** translation between
  `SketchContract.LAYER_*` (the wire) and `RasterLayer` (the engine): `wireOf(RasterLayer)`,
  `of(Int)`, and `all` (`[GRAPHITE, INK]`, the same order as `SketchContract.LAYERS`) — pinned to
  both by test, so the two enumerations can never drift silently apart. Every layered call in the
  face (`readPageRaster`, `swapPageRaster`, a save's `markDirty`) goes through it rather than
  hand-mapping an `Int`.

**Page turns, inserts, deletes** all go through `runPageOp`, which serializes page ops on the
lifecycle scope and turns any thrown exception into a `Log.w` rather than a crash. A turn
(`turnPageNow`) flushes the current page first, asks the host to `requestPage`, and compares
`pageKey`: the **same key back means the edge**, never an exception or a null the screen has to
word, and the arrows (well, gestures — decision 12) simply do nothing there. An insert
(`insertPageNow`) is the same shape with `insertPage`; a delete (`deletePageNow`) flushes the
doomed page, asks `deletePage`, and lands on whatever page the host answers. Every successful
structural call records its `structuralToken` into the face's own history (`rememberStructural`) so
the face's own undo/redo gesture can reach it later.

**Delete confirm.** A one-finger long-press on the page opens the pager's sheet
(`confirmDeletePage` → `askAboutDelete`), which asks the host `pageContent` first and words the
body from the two bits: *"Its handwriting will be deleted too."* / *"Its document will be deleted
too."* / *"Its handwriting and document will be deleted too."* / a plain confirm when the page
carries nothing but its sketch.

**Page insert gestures**: a single-finger swipe past the **last** page inserts a page after it; a
two-finger swipe inserts in the direction swiped (before or after the current page) — both route to
`insertPageNow` through `PageGestures.Listener.onInsertAfter`/`onInsertBefore`.

**Bring in ink** (`bringInInk`): stages the page's bare strokes over `requestInk` (0 = "No ink on
this page" alert, never an exception), reads them chunk by chunk (`readInkChunk`), turns them into
strokes via `InkBake.toBakedStrokes` — `InkWire.toStrokes` plus one colour override, opaque black,
width and style kept as drawn — and composites them as one undo entry (`composite`). **The bake
lands in the ink raster** (`InkBake`'s own KDoc says so, arc 45 / G3 — the strokes it produces are
`StrokeStyle.PEN`, so `RasterLayer.of(style)` routes them there the same as the gel pen). A page
dense past the pad's own transfer caps (`MAX_TRANSFER_STROKES` 10 000 / `MAX_TRANSFER_POINTS`
400 000) arrives **cut**: the prefix that fits, in writing order, logged host-side — a partial
bake, never no bake at all.

**The debug fill door** (`BuildConfig.DEBUG` only): a long-press on the page indicator composites
`TEST_PATTERN_LINES` = 12 diagonal `PENCIL` strokes as one undo entry — **landing in graphite**, by
the same routing — so adb (which cannot draw) can still produce a non-blank save to walk against;
arc 45 / G3 also logs a debug-only `encodeTable` (WebP effort 0/25/50/75/100 + PNG, bytes and ms)
from this same door, the source of the G3 measurement table below.

**Saves and reconnect.** `SketchSession.FlushHook` (`flushBlocking`/`pushBlocking`) and
`BeginListener` are the screen's static hand-off to `SketchService`: a host restart while the
screen is up delivers a fresh `begin` to the **live screen** rather than the screen re-launching,
and the service's `end()` calls `flushBlocking` through the screen's own push lock before
re-pushing anything parked.

**Exit.** Back / Show pages both route through `exit(resultCode)`, which cancels the save timers
and awaits a final flush (`leaveWhenFlushed`, non-cancellable). A flush that fails opens the "Sketch
not saved" dialog (see Failure table) rather than a log line — pixels have no other copy. Show
pages answers `SketchContract.RESULT_SKETCH_SHOW_PAGES` (1); Back answers `RESULT_CANCELED`.
Nothing else rides the result Intent but `EXTRA_CHROME_HIDDEN`.

**A page turn's full refresh — built, walked, and turned OFF (2026-09-19).** `EinkRefresh`
(`:sn-screen`) can ask the framework's eink service for a full refresh by reflection
(`IEinkManager.screenRefresh(hide, 1)` — the second argument is the refresh kind, **1 = full
regardless of what the compositor thinks changed**; 0/2/3 do nothing once the panel has been drawn
on directly; `hide` makes no difference — measured on the Nomad by cycling every variant through
g-paper's probe). `SketchActivity.REFRESH_ON_TURN` is **false** by the user's decision ("a bit much;
I can always manually refresh"): the door stays for a later choice. The flip ghosting it cleared is
the dithered dot field itself — dense pencil scribbles ghost worst, solid gel-pen strokes not at
all; presenting a loaded page through the panel first (g-paper `PRESENT_LOADED_PAGE_VIA_PANEL`,
off) made no difference. Accepted as is; a coarser dither is the one future thread.

- **`EinkRefresh` lives in `:sn-screen`** (`core/EinkRefresh.kt`), beside `TopGuard`, because a
  panel refresh is a property of the **screen**, not of the paper — there is no g-paper call for it,
  and any of the four paper surfaces could want it next. It is all reflection: a probe found that an
  ordinary app may reach the firmware's own service — `ServiceManager.getService("eink")` →
  `android.os.IEinkManager$Stub.asInterface(binder)` → `screenRefresh(false, 0)`, which returns
  without throwing and needs no permission. None of those three names is in the public SDK, so the
  object's whole contract is *it either refreshes or it does nothing*: Ratta-only (its own
  `isRattaDevice()`, g-paper's being `internal` to its Ratta module), the proxy and method resolved
  once and cached, **every failure of any kind one `Log.w` and a remembered refusal** — never a
  crash, and never a second attempt for the life of the process. The successful call logs the ms it
  took, one line per refresh, so a walk can read the cost straight off the log.
- **Posted, never inline.** The load has just handed the engine both rasters and the engine rebuilds
  and presents them; a refresh asked before that present would clear the panel and let the new page
  land on it partially — the ghosting it exists to remove, put back.
- **A page change, and only a page change.** Every `loadPage(firstLoad = false)` is one — a turn, an
  insert, a delete, a structural replay's landing, the replay walk's last step. The first open of
  the face is not (the activity transition refreshes the panel by itself), and neither is a raster
  undo/redo, which swaps tiles in place and never comes through here.
- **`SketchActivity.REFRESH_ON_TURN` = `true`** is the one switch. Its KDoc says what is still open:
  **the user's walk decides whether the refresh stays at all**, and whether `EinkRefresh.MODE` — the
  second, undocumented `int` of `screenRefresh` — should be something other than the `0` the probe
  used. It is a constant rather than a setting because there is nothing for a person to choose:
  either the panel is better for it on every turn or on none.

### The host side (`NotebookActivity`, `SketchHostHooks`)

`NotebookActivity` holds `sketchEntry` (the door — `btnSketch`, left of `btnBible`, mirrored in the
collapsed overflow with `ic_brush`) and `sketchHooks` (the callback binder's four original hooks
plus K5b's three). The open route forks on `session.isSketch` through `SketchRouting`
(`FaceRouting`'s generic open/close tables under the sketch face's own names): a fresh Sketch
notebook launches the face; a live showing reconnects; a notebook whose canvas has already been
shown this incarnation is ordinary from then on. **The one place the sketch and text faces differ**:
the editor's advisory is a field it sets on the way out, so silence is a genuine unanswered
question; the sketch face's advisory is the **Activity result code**, and the framework always
delivers one — so `RESULT_CANCELED`, null, and any unknown code all read as to-library, with no
extra rule needed (`SketchRouting`'s own KDoc).

`SketchHostHooks.target` (`@Volatile`) is the host's memory of **where the face is** — moved only by
`requestPage`/`insertPage`/`deletePage`, restored from saved state on a reconnect, falling back to
the displayed page if the face's target no longer exists. **Every move also writes the notebook's
last-opened pointer** (`rememberLastOpened` → `NotebookSession.saveLastOpened`, 2026-09-19, the
user's finding): the notebook screen's own pointer writes run at its `onStop` and `close()`, and
that screen is stopped for the whole life of the face — so a notebook killed by the system while
the face was up used to reopen at the page the face *opened* at. A failed write is a log line and
never fails the move. Verified on the Nomad: page 2 → two turns → both processes force-stopped →
relaunch opened on page 4. **The two undo histories are one
history**: a page insert or delete is recorded on the **notebook's own** undo stack
(`NotebookUndo.Action.Page`, via `onStructural`, posted to Main since the hook runs on a pooled
Binder thread) so the notebook's own undo still restores it after Show pages — but a small
per-showing **ledger** (`undoable`/`redoable`, tokens `s1`, `s2`, …, capped at 100, cleared with the
showing) lets the face's own gesture ask for that very edit back (`undoPage`/`redoPage` →
`onStructuralUndone`/`onStructuralRedone`, which pop-and-verify-then-push the matching entry across
the notebook's stack rather than trusting the token blindly). **Known looseness, written down**: a
pixel edit on the face clears the face's own redo side but not the notebook's, so an insert undone
on the face and drawn past can still be redone later from the notebook — benign (the page comes
back whole), and the alternative is a Binder call per mark.

---

## Saves

`SketchSaveGovernor` (pure Kotlin, no Android types; unchanged class) is the whole decision of
whether a drawing gets written — arc 45 / G3 runs **one instance per raster**, so a pencil
scribble never re-encodes the ink row and a pen stroke never re-encodes graphite. `SketchSaver` is
the plumbing (timers, threads, the binder call) that carries out what the governor says,
`markDirty(layer)` its per-raster hook.

**Dirty is a flag, not a comparison, per raster** — a page of pixels cannot be compared without
re-encoding it, which is the work the question exists to avoid. `markDirty(layer)` follows the
engine's own layered `onRasterChanged`; the flag is cleared **when the copy is taken**, not when
the write lands, so a mark arriving mid-encode re-dirties that raster's page and a second save
follows rather than being lost in the gap between an old copy and a flag cleared too late.

**States**: `request()` (an ordinary trigger — the debounce tick, a turn, `onPause`, a retry)
answers `Idle` / `Save` (take the copy now) / `Wait` (a push is already in flight; its own
completion re-asks) — **newest wins, and wins after**, never beside, the one already running (two
overlapping chunk streams would interleave on the host's accumulator). `flushRequest()` /
`saveNow()` / `flushAndAwait()` **walk `SketchLayers.all`**, one raster after the other, so both
dirty rasters of a page are pushed by one call. `flushRequest()` (a **leave** trigger: Back, Show
pages, `end()`) ignores `inFlight` on purpose — the real exclusion is **one push lock, FIFO**: the
two rasters of a page cross the wire one after the other under the same lock, never
interleaved, and a leave flush queued behind an in-flight push still lands, in order, rather than
leaving with the newest pixels unwritten. Completion bookkeeping and the retry beat are kept
**per layer**, but the retry itself is **one beat for the whole page** — a page with both rasters
dirty gets one retry tick, not two racing ones.

**Cadence**: 3 s pen-idle-gated debounce; a failed push retries after 2 s; flush points are a page
turn/insert/delete, `onPause`, Back, and Show pages. Each save is a **Main-thread bitmap copy** →
WebP encode on IO → chunked push under the one push lock (one push in flight at a time, ever, for
the whole page). Encoding is `RasterImage.compressLossless`: `Bitmap.CompressFormat.WEBP_LOSSLESS`
on API 30+, `WEBP` at quality 100 on 29 (documented lossless there; `minSdk` is 29). The `quality`
argument doubles as an **effort** dial on the lossless path; `RasterImage.WEBP_EFFORT` = **100**,
the G3 phase-start answer after Sonnet's Nomad encode table (below): the top of the dial is the
smallest encode on **both** rasters and still no slower than the PNG it replaces, running on an IO
thread three seconds behind the last mark. **The dial is not monotonic — measure, never
interpolate** (see Traps): 75 encoded *larger* than 50 on the graphite lattice at G3. A
debug-only `encodeTable` (efforts 0/25/50/75/100 plus PNG, bytes and ms each) is logged from the
fill door for exactly this reason. A failed copy (an allocation the device refuses) leaves that
raster's page dirty and re-arms the retry with nothing pushed; a failed push leaves that raster
dirty, parks its pixels, and re-arms the retry.

**`PendingImagePark`** (renamed from `PendingPngPark` at G3, the format in the name) is the one
place a page's pixels live, **per raster**, when a push could not be delivered — the host process
died, its binder was revoked, a chunk was refused. **One slot per raster, keyed by page and
layer**: a second failure on a *different* page (on the same raster) displaces the first slot for
that raster (the newer pixels, the ones the hand is closest to, win; the caller logs the
displacement) — a graphite failure and an ink failure on the same page occupy their own slots and
never displace each other. `clear(pageKey, layer)` drops one slot; `take()` answers
**graphite-first** when both are parked. Every caller that can find a parked slot **drains** it:
`retryParked`, the service's `flushBeforeRevoke`, and `pushPendingInBackground` all walk both
layers (each proved terminating). `ISketch.end()` re-pushes whatever is parked, on either raster,
while the host binder is still valid — the last moment it is — via
`SketchSession.FlushHook.pushBlocking(pageKey, layer, bytes)`, now layer-taking. Unlike the
document editor's park, a sketch save is accepted for any live page, so a park never has to match
what the host is currently showing to be worth writing back.

**The page turn awaits the copy, not the encode** (2026-09-19, the user's finding and decision on
the Nomad: a flip straight after drawing took "a few seconds", a flip after a pause was instant).
The log said why — the graphite WebP encode at `WEBP_EFFORT` 100 costs 0.5–3.5 s on a real page
(2 792 / 3 543 / 3 310 ms on three ~1 MB pencil pages) — and a page turn's flush was awaiting it.
A turn now takes `flushForTurn`, which awaits only the **Main-thread pixel copy** of each owed
raster (`governor.flushRequest()` + `getPageRaster`, a few ms) and lets the encode and the push run
on IO, under the same one push lock, while the next page loads. The copy is what has to happen
before the paper changes — it freezes what will be written — and the encode only has to happen
before the row is **read**. So the one rule the change adds is about reads: **a page whose push is
still in the air is not read until that push has landed.** `SketchSaver` records every push under
the page key its copy was taken on (`PushTracker` — pure Kotlin, keyed), and `loadPage`, the only
place the face reads a raster back through the host, asks `isPushPending(pageKey)` first; if it is,
it awaits that page's pushes, takes one more `flushForTurn` of whatever the *outgoing* page gained
while it waited (that page is still on the glass, and the load's `markClean()` would otherwise throw
a late mark away), **and then re-asks the host for the state** (`current()`), because the
`SketchPageState` it was handed carries the byte and chunk counts of read windows the host loaded
*before* the push landed. **A different page's load never waits and costs no extra Binder call** —
which is the whole point of keying it: turning *away* from a page just drawn on is free, and only
turning straight *back* to it pays anything. The other flush points are unchanged and deliberately
so: Back, Show pages, `end()` and the Binder-thread `flushBlocking` are **leave** flushes and the
host reads the row the moment they return (`flushForExit` now also drains every background push,
whatever page it was for, before it flushes the current one); a page insert, a page delete and a
structural replay keep the awaiting flush because they are about to change the notebook's own page
set, and a delete in particular must have written whatever its undo will bring back. The save log
line names all three halves — `copy N ms, encode N ms, push N ms` — so a walk can read straight off
it which part of a save a turn is still paying for. The undo ledger's replay path needs no rule of
its own: `applyEdit` swaps tiles in the engine (`swapPageRaster`) and `RasterEditBuilder` reads
them with `readPageRaster`, both in-process, and the only host read it makes is through the
`loadPage` at the end of `walkTo` / `applyStructural`, which rule (1) already covers.

**Loading a page** (`loadPage`) loads **both** layers always, sequentially, one decoded bitmap
alive at a time (peak memory: one array plus one bitmap, not two of each) — a null layer is
absent, dropped rather than swapped in as a blank raster. `readSketch(state, layer)` answers an
absent layer's own bytes without a Binder call at all, straight from the parked `SketchPageState`.
`deletePageNow` clears the park **on both layers** before the delete crosses. `composite()` (the
ink bake, the fill door) no longer pre-opens a raster-edit builder on a fixed layer — the listener
opens it on whichever layer the engine actually names for the stroke it is baking (there is no way
to know in advance which raster a mixed batch will touch), and marks dirty each distinct
`RasterLayer.of(style)` the batch produced.

---

## Undo

`RasterTiles` (`CELL` = 64 px) divides the page into a fixed grid aligned to its own top-left
corner. `RasterEditBuilder` opens at the first `onRasterWillChange` of a contact (a pen stroke, an
eraser sweep, or the "Bring in ink" bake) and reads each cell the paper touches **once per
contact**, never again for the rest of that contact — an eraser sweep is dozens of engine batches a
second whose rectangles overlap heavily, and reading a tile per batch would let one slow rub pile up
tens of megabytes of near-duplicate pixels inside a single entry. Sixty-four is small enough that a
hairline stroke costs 16–64 KB and large enough that a page-wide sweep is not tens of thousands of
tiny reads.

**One contact is one entry on one raster, and it is its own inverse**
(`SketchEdit.RasterChanged(pageKey, pageIndex, layer, tiles)`, `layer` added at arc 45 / G3): the
tiles go onto the named raster via g-paper's layered `swapPageRaster(edit.layer, …)` and come back
holding what that raster was holding, so the entry that undid a change is the entry that redoes
it, with no second copy and no second shape of call. **One contact touches one raster** (a pencil
stroke, a pen stroke, an eraser sweep, or the ink bake), so per-contact undo bytes **do not
double** now that there are two rasters — `SketchActivity` overrides only the **layered**
`onRasterWillChange`/`onRasterChanged` callbacks (the un-layered pair, silent for ink by g-paper's
own design, was deleted rather than kept), and reads via `readPageRaster(layer, …)`. A belt
closes an open entry if a second layer's `onRasterWillChange` ever arrives inside what the builder
thought was one contact — a defence against a mixed batch, not something the engine is known to
do. **Undo bytes still bound the whole entry to one page's raster** — a 9.5 MB Nomad page or an
18.4 MB Manta page is the absolute ceiling for one contact on one raster, well under the 48 MB
budget (`SketchEdit.UNDO_BUDGET_BYTES`, unchanged), which `UndoRedoStack`'s `evictForBudget`
enforces by dropping the **oldest costed entry**, never the newest.

**`SketchEdit.Structural` is the one zero-byte kind** (K5b): a `PageInserted`/`PageDeleted` entry
carries only its host `token`, no pixels at all, so the byte budget can **never** evict one to make
room for pixel entries. **The history is screen-level, not page-level** — undoing "the last thing I
did" after turning pages away from where it happened has to turn back first, which is why every
entry (pixel or structural) carries a `pageIndex` alongside its `pageKey`: the key names the page,
the index says which way and how far to walk (`PageTurn.directionTowards`/`maxSteps`, bounded — the
recorded distance plus one step of slack — because pages can move under the entry after K5b).

**Replay** (`doReplay`): walk to the edit's page (turning, bounded) → `awaitPenIdle` → a generation
re-check (if a mark landed on the page while the replay waited, the edit is put back **beneath** it
rather than applied out of order) → the swap (pixel entry) or the Binder replay (structural entry)
→ mark dirty → **never a reload from disk**. A fresh delete drops the deleted page's pixel history
entries (`dropPixelsOf`); a **replay never drops entries**, only re-indexes them
(`PageTurn.reindexAfterInsert`/`reindexAfterDelete`) — this is the one bug K5b's hand walk found and
fixed: an *undo* of an insert had been dropping the page's own pixel entries even though they sat
waiting on the redo side one step above, which meant insert → draw → undo → undo → redo → redo did
not bring the drawing back. Fixed by making a replay re-index only, never drop; only a fresh delete
drops.

---

## Export, clipboard, erase, cover

**Export** (`ExportRender`, K7): a bundle interleaves each page's ink then, if it has one, its
sketch — page 1 ink, page 1 sketch, page 2 ink, … — with endnotes after the last sketch. Which
pages carry one is **planned, not discovered**: one blob-free `SketchDao.pagesWithSketch` query
before the first page is drawn (arc 45's own answer: **either row live**), so the bundle can
declare its header page count up front. `bundlePositions` shifts every endnote's `fromPage` by the
sketch pages ahead of it (`fromPageLabel` keeps the notebook's own page number). The **two** row
images are read through `SketchDao.sketchFor(pageId, type)` (once per row that exists) +
`SketchRows.imageBytes`/`fitsPage` — **never `SketchRepository.get`**, which would soft-delete a
row the render must not touch — and a page whose rows are both gone or refused between the plan
and the bake writes a **plain white page of the page's own size** (`SketchRaster.blank`, logged)
rather than closing the bundle short by one page. `SketchRaster.toWebp(w, h, graphite?, ink?)`
(arc 45 / G2: grew from one guarded blob to two optional ones) decodes **one raster at a time**
(peak still two bitmaps alive together, one per raster, not more) and composites white, then
graphite plain, then ink plain **over** it (`SRC_OVER` since arc 46 / g-paper 0.1.44; `DARKEN`
before that), into `RGB_565`, plain white never the
template (decision 10). `ExportNaming.pageStem(sketch = true)` appends **` sketch`** to the ink
page's own stem (`K7 - Heading.png` / `K7 - Heading sketch.png`) — the phase-start answer, so the
two files sort together and the suffix never eats into the title's own cap; the exported file is
still one flattened image regardless of how many of the two rasters it drew from.

**Page-scope export of a sketched page is a folder, not a single file** (`ExportDelivery.perPage`,
the K7 hand-found bug): a two-page bundle handed to a single-file image exporter as "one page" threw
`bundle carries 2 pages; one expected` with nothing written. Fixed by counting a sketched page at
page scope like the calendar's Day — `perPage(delivery, scope, pageHasSketch)` — with
`SoilDao.hasLiveSketch(pageId)` (blob-free, either row) answering the Export screen's one open.

**Clipboard/erase**: see § The data above — `clip_too_large_sketch`, `liveErasableIds` (both now
excluding both rows).

**Cover** (`SketchCover`, decision 6): `render(repo, pageId, graphite?, ink?)` — rendered from the
**stored rows**, never the extension's live surface — the pixels are in another process, and the
only honest picture is the one that landed, which is why the caller reads the rows *after*
`end()`'s save has been joined. Both rasters are decoded at **one `sampleFor` size derived from
the page's own size**, not from whichever decodes first — the ground bitmap is allocated from that
sampled size (ceiling division) **before either decode**, an arc 45 / G2 deviation from the K4
recipe needed so two separate decodes both register onto the same card rather than the second
silently sizing itself off the first. `sampleFor` is still the largest power-of-two `inSampleSize`
that keeps the long edge at or above `CoverSnapshot.LONG_EDGE_PX`, so the decoder never allocates a
full page for a 512 px card. Composited over opaque white (a transparent card would show the
library's own background through the strokes) — graphite plain, ink plain over it (`SRC_OVER`
since arc 46; `DARKEN` before), the same flatten as everywhere else. Falls back to the ordinary ink bake when the page carries
neither row, and only once the canvas has actually loaded (an unloaded surface's "bake" would be a
blank card).

**Compaction**: unchanged. A soft-deleted sketch row purges like any row at close; a purged page
cascades and takes its sketch with it.

---

## The door

`btnSketch` sits on the notebook's bottom strip, immediately left of `btnBible` (decision 9), `GONE`
— never disabled — for a non-Sketch notebook or with no trusted `NSE · Sketch` installed; mirrored
in the arc-36 collapsed overflow (`ic_brush`). It is the **third** deliberate exception to "bottom
bars are pager-only," after `btnBible` (arc 37) and the reader's `btnNotes` (arc 42). The sketch screen's own top bar carries **Show pages** back
out; there is no door the other way from the library (a Sketch notebook always opens through the
notebook screen first).

---

## Failure table

| Situation | What happens |
|---|---|
| A save's running total on one raster passes `MAX_BYTES` (6 MiB) | `SKETCH_TOO_LARGE` for that layer alone thrown at the accumulator; nothing written for it; stored row unchanged; the **other layer's row and accumulation are untouched**; the screen keeps the pixels on the glass (they have no other copy) |
| Committed save bytes on a layer are not a WebP of exactly the page's size | `SKETCH_BAD_IMAGE` (renamed from `SKETCH_BAD_PNG`) for that layer; nothing written for it |
| A stored row fails the header guard on read | Soft-deleted on the way past, never overwritten; `get` answers null for that row |
| A page is turned away from and straight back to before its background push has landed | The load waits for that page's own pushes and re-asks the host for its state; the wait is the remainder of the encode, and no other page's load waits at all |
| A **background** push (one a page turn left running) fails | Its pixels are parked and the raster is marked dirty exactly as any failure is — but the page now showing is the one marked, so it costs one redundant save of bytes the host already has; the parked bytes are still re-pushed at a host reconnect and at `end()` |
| The host dies mid-save (Binder revoked, `DeadObjectException`) | The pixels are parked (`PendingImagePark`) by page key **and layer**; the governor keeps that raster's page dirty and retries |
| Final flush before Back/Show pages fails | "Sketch not saved" dialog — Try again / Leave anyway; "leave anyway" still leaves the pixels parked, on whichever layer(s) failed, for `end()`'s own retry |
| A page turn/insert/delete lands at the notebook's boundary | The host answers the **same page unchanged**; the screen compares `pageKey` and does nothing — no dialog, no toast |
| `deletePage` is asked for a page that is not the face's current target | `IllegalArgumentException` |
| `undoPage`/`redoPage` is asked for an unknown token | `IllegalArgumentException`; the face drops that history entry |
| A `readInkChunk`/`readSketchChunk` index is out of range | Refused (contract-typed exception) |
| An unknown layer is named on `readSketchChunk`/`saveSketchChunk` | `IllegalArgumentException`, checked **before** the chunk index or any accumulator state is read |
| A page has no bare ink for "Bring in ink" | `requestInk` answers **0** (not an exception); the screen shows "No ink on this page" |
| A page is denser than the transfer caps | Ink arrives cut to the prefix that fits, in writing order; a partial bake, never none |
| `am start` targets the exported screen directly | `HostCallerCheck` refuses before anything is inflated; `onScreenDestroyed` still runs and must not crash the process (see Traps) |
| No trusted `NSE · Sketch` is installed | A Sketch notebook loads its ordinary canvas silently; `btnSketch` stays `GONE` |
| A replay's page-history entry names a page since renumbered | Bounded walk (`PageTurn.maxSteps`); a stale key that never resolves drops the entry rather than looping |
| A structural replay lands mid-mark | Put back **beneath** the newer mark (generation re-check), never applied out of order |
| The host answers **null** for `toolSettings()` | Read as "nothing remembered yet," never a failure — the face arms its own defaults (the pencil at level 5, the pen at black) |
| A remembered shade level this build does not offer | That field alone falls to its kind's default (`SketchToolState.of`) — the other remembered fields are unaffected |
| A 20 host answers a three-int `SketchToolSettings` | The pen shade reads as black (the exhausted-parcel rule); the tool and pencil shade are honoured |
| A stored prefs file with no `pen_shade` key (upgraded from arc 44/45) | Decodes with pen shade 0 — never "nothing remembered" |
| The gel pen is set to white | It covers graphite and darker ink alike (ink over graphite, decision 9) and shows nothing only over bare paper; its pixels are ink, so the rubber never lifts them |
| Pencil is drawn across an ink line | The graphite is hidden where the ink is (ink on top, decision 9) — real graphite slides off dry gel ink; not a bug |
| A host below API 20 | `:ext-sketch` never binds it at all — the manifest declaration is the guard (arc 45 / G2 moved the action floor from 17 to 20, K5b's arrangement repeated at the new number); there is no runtime version test in `restoreTools()` |

---

## Frame-silence ledger

- `loadPageRaster` — **still silent, layered**, as `swapPageRaster` always was, since g-paper 0.1.33
  (K6): a page the host replaced is the host's own news, not the engine's, on either raster. The
  screen's earlier `loadingRaster` guard against a spurious dirty flag on load is gone with it —
  there is nothing left to guard against.
- `swapPageRaster` (an undo/redo swap) — silent, unchanged from every earlier pin, now layered.
- `addStrokes` in RASTER (the ink bake) — **reports**, as an ordinary raster change, on the one
  layer the baked style routes to.
- `loadStrokes` (a strokes bake at load) and `clear()` — **report**, and since arc 45 / G1
  **announce both layers, graphite first, whole page, even when one is empty**: these are changes
  to the rasters' own content, not a page swap underneath them, so the silence rule above does not
  apply. (`loadPageRaster` — the host handing a raster in — is the silent one, above.)
- `commitCapturedStroke` / the eraser's batches — report per-segment dirty rects (0.1.33's
  `RasterDirty.along`), not one page-wide rect — the K6 change that took the worst-case undo entry
  from 550 cells / 8.98 MB to 134 cells / 2.19 MB on a corner-to-corner Nomad hairline.
- Two page turns after drawing produce **no** undo entry and **no** dirty flag — the load being
  silent is not merely unnoticed, it was measured to produce nothing.
- **`PaletteBar`'s open and its repaint after a pick (arc 44 / T3, arc 46) are the floating-bar exception,
  not a new silence** — one chrome frame each at a deliberate tap, with the pen that made it still
  hovering (`isPenActive` counts hover; gating the frame itself would hold the bar back until the
  hand left the glass, which every other sub-bar in the family already refuses). What *is*
  pen-gated is the render release: `pick()` calls `PenIdle.releaseRenderIfIdle(paper)` before
  `onPicked` assigns the new state, `EraserBar`'s own contract — an ungated release inside the
  pen-active window can cost a live stroke.

---

## Traps

- **Ink is on top since arc 46 / g-paper 0.1.44 — pencil over an ink line is hidden by it.**
  Under 0.1.39–0.1.43's `DARKEN` a white gel pen drew nothing over graphite and a grey one could
  not show over darker graphite; the user's decision 9 put ink over graphite everywhere (engine,
  export, cover). A white pen stroke over bare paper still shows nothing, and its pixels are ink,
  which the rubber never lifts — expected, not a bug. Don't re-introduce `DARKEN` in any one
  flatten copy (`SketchRaster`, `SketchCover`, the engine): the three must agree.
- **`SketchPalette` levels are positions in Atelier's `TONES`, not `n * 0x11` (arc 46).** A stored
  5 is `#808080` now, not `#555555`; only 0 and 15 mean what they always did. Any arithmetic that
  derives a grey from a level is wrong — ask `shade(level)`.
- **`SketchToolSettings.size` is a dead wire slot (arc 46 / Q1)** — written 0, never read. A
  contract test asserting `MIN_API_VERSION_FOR_SKETCH_PEN_SHADE` is **above** the action floor is
  the correct sense for a live tail, the reverse of G2's inversion below.

- **The pencil bakes upright on Ratta (g-paper 0.1.35, 2026-09-17, branch `sketch-manta`).** The
  stable build went onto the Manta unwalked, and there the baked hairline landed **10–15× wider**
  than the firmware's live line. Not a panel difference and not the EMR size (suspected first —
  a `setprop` door was re-opened for it and closed again unused): g-paper's graphite widens a
  leaned lead up to ~11×, and the Supernote live line is one width whatever the tilt. Both
  devices deliver `AXIS_TILT`; the Nomad walks had simply been at an upright grip. g-paper's
  `bakeTilt` seam (beside `bakePressure`) bakes Ratta's `PENCIL` at tilt 0 — decision 3's "no
  tilt", now true in the pixels. Walked by hand on both the Manta and the Nomad. **The size of a
  mismatch names its cause: an EMR error is tens of percent, a lean error an order of magnitude.**
- **`RattaNotebookView`-style sibling-copy risk was avoided on purpose.** `FaceRouting` is one
  generic open/close table; `TextDocRouting` and `SketchRouting` are thin facades naming its
  answers under each face's own words. A fix to the shared table helps both faces at once — do not
  fork it.
- **`am start` of the exported sketch screen crashed the extension process** the first time it was
  tried: the caller check correctly refused the launch, but the refused instance's
  `onScreenDestroyed` then threw `UninitializedPropertyAccessException` on the `lateinit` saver,
  taking down the process **with the legitimate face already running in it**. Fixed by guarding on
  `::saver.isInitialized`; any future `lateinit` field touched from a lifecycle callback that a
  bounced launch still receives needs the same guard (the root `IndexGuard.bounced` rule, restated
  here for a screen with no index).
- **A finger `input swipe` never draws real ink on Ratta** — adb cannot exercise the pencil, the
  eraser, or the undo/redo gestures; every one of those is a hand-only walk item, and an agent
  "pass" on one gets re-driven by the user before a phase counts as done.
- **`adb shell input tap` costs about a second per call on the Nomad**, which rules out injecting a
  double-tap (chrome collapse, the undo/redo gestures) — the hand, or raw `sendevent`, only.
- **The Nomad's page size moved between K1's demo (1404×1685) and the shipped host (1404×1872 in
  one walk, matching the walk agent's screen height)** — budget arithmetic for undo/export should
  be re-derived from the live device, not assumed from an earlier measurement.
- **A stale incremental-dex snapshot** pointing at the pre-rename `apps/notesprout_ratta/…` path
  broke a dependent module's `mergeLibDexDebug` the first time a class was deleted from `:ext-ink`;
  `./gradlew :ext-ink:clean` clears it — expect it again after the next class deletion in a
  long-lived module.
- **A NUL byte inside a Kotlin char literal compiles and passes silently** — only `cat -v` shows it.
  A defect of exactly this shape was caught in review before K2's commit (`SketchPageState`'s key
  check carried a literal NUL byte where the `'\u0000'` escape belonged), and this arc's own
  Markdown carried one twice — `SKETCH_PLAN.md` from K2's ledger until K4, and the first draft of
  this very doc — which makes `grep` treat the file as binary (silent no-match; `file` says
  "data"). Check any new `require` line against `DocumentPageState`'s existing escapes, and
  `file` any doc that grep goes quiet on.
- **The Supernote DocumentsUI *file* picker (`ACTION_OPEN_DOCUMENT`) is as adb-proof as the folder
  picker** — synthetic taps on rows are ignored; walk import by hand, and know that a second tap on
  Import can reopen the picker with no back door but `am force-stop com.android.documentsui`
  (confirmed safe: it returns `RESULT_CANCELED`, nothing double-imported).
- **The ext-sketch debug APK reads larger on disk than its JVM test byte count would suggest** — the
  known zipflinger inflation, not a dependency regression.
- **The demo's Shade / Lead cyclers are raster-only and the bar scrolls sideways (T1)** — say so in
  any walk checklist that uses g-paper's own demo rather than the shipped face.
- **`adb shell monkey` exits 251 on the Nomad (T1)**, with the app launched or not launched — don't
  chain a walk step on it.
- **No test implements `SketchHostBinder.Hooks` (T2)** — the binder shell has no JVM coverage
  (`Binder.getCallingUid`), so `toolSettings`/`putToolSettings` were proven only by T3's device
  walk (picks surviving a reopen and a process death).
- **The fill door draws on whatever page is showing (T3).** The adb walk composited its test
  pattern over the user's own tree sketch on the dev library's K7 page 2, and a process-death step
  in the same walk took the undo history with it. **Every walk brief now says "turn to a blank page
  first."**
- **Two `Tool.PEN` kinds are one tool to g-paper (T3)** — the screen alone owns which kind is
  armed, and any "is the pen armed?" test in shared chrome has to ask the screen (`altPenArmed`),
  never the tool alone.
- **`SketchPalette.ROW_BREAK` (8) is inert at six offered shades (T3)** — kept anyway, as the rule
  `shadeRows()` derives from, rather than deleted for a list that may grow again.
- **`SketchActivity` is ~1465 lines (2026-09-19, up from ~1330 at G3 and ~1230 at T3)**, past the ~800-line guide — the
  growth is the layered raster-load/save wiring; `SketchPageLoad` was **not** split out at G3 (it
  would need `callHost` and the lifecycle checks handed in as lambdas for roughly 45 lines saved —
  not worth the indirection yet), so what remains past the guide is screen wiring, not a candidate
  for a quick split.
- **With the action floor above `_PAGES`/`_TOOLS`, a test asserting a method floor is *above* the
  action floor inverts (G2).** Before arc 45, `MIN_API_VERSION_FOR_SKETCH_PAGES` (18) and
  `MIN_API_VERSION_FOR_SKETCH_TOOLS` (19) were each above the birth floor (17), so a contract test
  naturally asserted "the method floor is ≥ the action floor." Once the action floor itself moved
  to 20, both are now *below* it, and `SketchContractTest` had to flip the assertion and say why —
  any future contract test written from the old pattern will silently invert.
- **"First floor move after birth" needed a correction (G2).** Arc 22 / X1 had already moved three
  points' floors at once when floors were introduced; arc 45 / G2 is the first move of a *single*
  point's own action floor, not the first floor move ever. Don't repeat the broader claim.
- **A reopen is no longer a literal 0-pixel screencap diff (G3)** — K5's "0-pixel" line is now
  "**≤ 1/255**": the flattened round trip differs by up to 0.09 % of pixels at one delta unit,
  along the graphite grain's antialiased edges. This is **premultiplied-alpha rounding on the
  encode/decode round trip of semi-transparent pixels, not codec loss** — WebP lossless is exact on
  the unpremultiplied bytes it is handed — and it is invisible on a 16-grey e-ink panel. Don't
  chase it as a regression; measure against ≤ 1/255, not 0.
- **The WebP effort dial is not monotonic (G3)** — effort 75 encoded *larger* than effort 50 on the
  graphite test lattice (libwebp changes heuristics as the dial moves, not a smooth cost curve).
  Measure every effort you care about; never interpolate between two measured points.
- **Plain PNG can still beat lossless WebP on a synthetic pure-black lattice (G3)** — the fill
  door's test ink lattice encoded smaller as PNG (53 065 B) than as lossless WebP at any effort
  (88 642 B at effort 100). Real handwritten ink is far sparser than a filled test lattice, so this
  is a property of the *test pattern*, not evidence to revisit the WebP choice.
- **A stale device-wide `sn_chrome.xml` with `hidden=true`, left by an earlier walk, hides every
  paper bar (G3)** — including the sketch face's own toolbar. An adb walk that cannot find the fill
  door should check that prefs file before suspecting the build; it was reset via `run-as` + `cp`.
- **`dumpsys package` does not surface a `<service>`'s declared `<meta-data>` (G3)** — the API
  version a service actually requires has to be read from the built manifest, not asked of the
  running device.
- **A page turn's flush no longer means the row is written (2026-09-19).** `flushForTurn` awaits
  the copy only, so between a turn and a few seconds later the `.soil` row is one save behind what
  the glass held. Anything that reads a page's raster back through the host has to await that
  page's pushes first (`SketchSaver.awaitPushes`) **and re-ask for the state**, because a
  `SketchPageState` describes read windows the host loaded at the time it answered. Today
  `loadPage` is the only such read; a future one — a thumbnail, a second face — that skips either
  half will silently show the page as it was before the last strokes.
- **The panel-refresh door is unpublished, and its refusal is logged exactly once (2026-09-19).**
  `"eink"`, `android.os.IEinkManager$Stub` and `screenRefresh(boolean, int)` appear in no SDK and
  could be gone after any firmware update; the `int` argument's meaning is unknown and the probe's
  `0` is a guess (`EinkRefresh.MODE`). `EinkRefresh` remembers its first failure for the life of the
  process, so a walk grepping the log after a dozen turns finds **one** `EinkRefresh` warning, not
  one per turn — absence of further lines is the design, not evidence the door opened. The proof it
  did is the `full panel refresh in N ms` line, one per page change, on a debug build.
- **`PaperView` is an interface, not a `View`** — `paper.post { … }` does not compile; the handle to
  post on is `paper.asView()`. Trivial, and caught by the compiler, but it is the second time a
  helper written against "the paper" reached for a `View` method that is not there.
- **A true double-tap is still out of adb's reach (G3)** — `input swipe x y x y 800` stands in for
  a long-press, but the finger double-tap chrome toggle has no adb equivalent and stayed a
  hand-only walk item this arc too.

---

## Nomad numbers

**K1 (g-paper demo, before the pin was even in SN):**

| Measurement | Result |
|---|---|
| Cadence | 16 ms chosen — 756 frames / 82% janky / p50 20 p90 28 p99 40 over the measurement window, but "the eraser works better on Ratta hardware than it does on Onyx"; 100 ms and 60 ms were smoother on paper and worse by hand |
| `PENCIL` EMR floor | 120 — matches the baked hairline; 150/200 unneeded |
| Preview tone | `DARK_GRAY` needle — "spot on"; plain black was "way off", pressure-tracked INK was indistinguishable from a flat tone on Ratta's one-tone firmware |
| Eraser-end pressure | Real: 2 852 batches measured, 0.06–0.49, median 0.24 |
| Undo swap (demo page) | 10–14 tiles, 14–20 ms |
| Page swap (Nomad demo, 1404×1685 ≈ 9.46 MB) | 79–126 ms; demo PSS 78 MB |

**K5 (first face walk, fresh `.dev` install):**

- Fill door → **undo entry: 414 tiles, 6 782 976 B** (before K6's per-segment rects)
- Save: 133 482 B encoded in 610 ms, pushed in 24 ms, host committed in 20 ms, 1 chunk
- Reopen after Back: **0-pixel screencap diff** against the pre-close capture
- `am kill` of the host behind the face: save failed (`DeadObjectException`) and parked; Back →
  dialog → Try again failed again → Leave anyway → Bootstrap relaunch → **the parked 136 554 B was
  pushed and committed on the very next attempt**
- PSS: host 65.8 MB / extension 64.2 MB (Paintsprout's Onyx equivalent: 255 MB)
- Idle `gfxinfo`: 0 frames / 30 s

**K6 (g-paper 0.1.32 → 0.1.33, one corner-to-corner pencil hairline on a fresh 1404×1685 page):**

| | 0.1.32 (before) | 0.1.33 (after) |
|---|---|---|
| 64 px cells read at pen-up | 550 | **134** |
| Undo entry bytes | 8 985 600 | **2 190 336** |
| Main-thread ms reading the before-image | 40 | **18** |
| Undo/redo swap | 550 tiles both ways | 134 tiles both ways |

4.1× fewer cells and bytes for the worst-case mark; two page turns after drawing produced no undo
entry and no dirty flag.

**K7 (clipboard/erase/export, notebook "K7", page 1 fill-door sketched, page 2 blank):**

- Copy/paste page: pasted sketch **byte-identical** (screencap lattice pixel-equal)
- Whole-notebook PDF: `rendered 2 page(s) + 1 sketch(es) + 0 endnote(s)`, pulled `/Count 3`
- Page-scope PDF: `/Count 2`
- Whole-notebook PNG: 3 files — `K7 - page 1.png` 11 964 B · `K7 - page 1 sketch.png` 109 995 B ·
  `K7 - page 2.png` 11 964 B
- `.soil` 159 744 B → delete the sketched page → close → `SoilCompactor: purged 2 row(s)` →
  **24 576 B**

**Across every phase (`assembleDebug`, JVM):** `:extension-api` 281 → 286 (K5b) → 292 (arc 44 / T2)
→ **299** (arc 45 / G2); `:sn-screen` 101 → 109 → 114 → **118** (arc 44 / T3, unchanged through
arc 45); `:ext-sketch` 0 → 52 → 60 → 87 (arc 44 / T3) → **97** (arc 45 / G3); `:app` 1717 → 1816 →
1822 (arc 44 / T2) → **1844** (arc 45 / G2). **3631 tests in the SN root** at arc 45's freeze (3621
at G2, 3592 at arc 44's), 0 failures at every gate. **Arc 46 / Q3: 3632** (`:ext-sketch` 96 — the
size tests gone, the two-shade tests added; `:extension-api` 300; `:app` 1845; `:sn-screen` 118).

**T1–T3 (arc 44):**

| Measurement | Result |
|---|---|
| T1 starting ladder (0–2 BLACK, 3–9 DARK_GRAY, 10–14 GRAY) vs. baked | 7–9 previewed **darker than they baked**; DARK_GRAY ceiling moved 161.5 → 110.5 |
| T1 final ladder | **0–2 BLACK · 3–6 DARK_GRAY · 7–14 GRAY** — 12–14 also preview darker as GRAY than they bake; LIGHT_GRAY trialled for them and rejected ("that doesn't work") — GRAY stays the palest rung |
| T1 lead sizes (1.2 / 2 / 4 / 7 / 12 px) | Preview ↔ bake width agree at all five; no EMR change |
| T3 sizes (1.2 / 2 / 4 / 7 / 12 px) | Preview ↔ bake agree, as at T1 |
| T3 gel pen width | 3 → 7 → **5 px** ("5 is perfect") |
| T3 shades (of the fifteen offered) | Only **0, 5, 9** previewed exactly as they bake (the firmware's three tones); the offered list settled at six — **1, 3, 5, 7, 9, 11** |
| T3 wide-lead walk (EMR ceiling lifted, g-paper demo) | 16 / 20 / 24 / 32 / 48 / 64 / 96 px all walked — "all of those wide-lead sizes work", no lag |
| T3 pencil bar position (Nomad, 1404 px wide) | x 8–940, the 8 + 7 swatch layout before the cut to six — fully on-screen |
| T3 tool memory across a reopen and a process death | `tools restored: remembered — tool=1, shade=0, size=4` after `am force-stop` — the T2 binder overrides proven only here |
| T3 heaviest save, 12 px black, four fill-door passes | 607 519 → 591 880 → 559 914 → **524 533 B** — an order of magnitude under the 4 MB watch |

The 96 px lead was not measured for save bytes at T3 (a later walk's number if it matters).

**G1 (g-paper demo, 0.1.39, Nomad, `ratta` engine):**

| Measurement | Result |
|---|---|
| `renderToBitmap()` of the flatten, 1404×1711 | **37 ms** |
| Demo PSS | 48.8 MB at launch → **70.9 MB** after the walk with both rasters live (~9.6 MB each — the "one more page-sized bitmap" the plan priced) |
| Undo swap | 7–20 ms for 6–9 graphite tiles |
| A pen line's undo entry | 33 tiles / 540 KB, on ink alone |
| Every rub entry | on graphite alone — a rub never reads ink, and the entry proves it |

The "flatten redraw ms **vs. 0.1.38**" line in the phase table could not be honoured literally:
`Dump` (the demo's wall-clocked flatten → PNG instrument) is new at G1, and 0.1.38 has no
equivalent measurement to compare against — the 37 ms is recorded as this arc's own baseline.

**G3 (the face, Nomad, fresh `.dev`, notebook "G3", 1404×1872):**

| WebP effort | graphite (pencil lattice) | ink (gel-pen lattice) |
|---|---|---|
| 0 | 217 924 B / 426 ms | 116 542 B / 393 ms |
| 25 | 217 776 B / 400 ms | 116 542 B / 341 ms |
| 50 | 216 084 B / 405 ms | 105 236 B / 341 ms |
| 75 | 284 888 B / 431 ms | 98 412 B / 352 ms |
| **100** | **146 498 B / 619 ms** | **88 642 B / 346 ms** |
| PNG (for comparison) | 222 066 B / 656 ms | 53 065 B / 602 ms |

- Saves: `save (graphite): 146498 B encoded in 674 ms, pushed in 25 ms`, host committed 21 ms, 1
  chunk; `save (ink): 88642 B … 365 ms / 21 ms`; **no second graphite save after the ink one** —
  confirming the per-layer dirty flag holds independently.
- `.soil` (`sqlcipher`): exactly `sketch_graphite|146498` + `sketch_ink|88642`, both
  `RIFF…WEBP…VP8X`.
- Reopen: `page 1/1 open (g 146498 B, ink 88642 B)`; screencap diff **2 445 px at max delta 1/255**
  (0.09 %, see Traps). Turn back: byte-exact. Swipe past the last page inserted page 2. Copy/paste
  page → `page 3/3 open` with the same two byte counts. PNG export: `3 page(s) + 2 sketch(es)`, the
  sketch file shows both lattices with the pen solid through the graphite.
- Undo entries: graphite 368 tiles / 6 029 312 B read 27 ms; ink 368 tiles / 6 029 312 B read
  11 ms — the same lattice on each raster, so a pen entry costs exactly what a pencil entry does.
- PSS: extension **67.7 MB**, host **67.4 MB** (K5's PNG-era numbers: 64.2 MB / 65.8 MB — the
  priced second bitmap).
- `logcat -b crash` empty; no `E/` from any sketch tag.

**The hand (2026-09-18).** Pencil over pen and rubbed, pen over pencil and rubbed, a rub over ink
alone, undo/redo across both rasters, Bring in ink then rub then undo, turn and back, Back and
reopen, the finger double-tap chrome toggle, PDF export and the cover — *"Clean!"*

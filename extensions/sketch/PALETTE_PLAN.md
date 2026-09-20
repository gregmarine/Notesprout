# Arc 46 "Palette" — the sketch face's tool settings (branch `tools`)

**Status:** 🔄 IN PROGRESS (2026-09-19). Letter **Q**. Phases Q0–Q4 below; the ledger is appended
as each closes. `docs/sketch.md` is the reference once frozen; this file is then history.

## Why

Arc 44 gave the pencil four shades (after arc 45's decision 6) and twelve leads, picked in a bar
hung under the Pencil on a re-tap, and a fixed black gel pen. Since g-paper 0.1.41–0.1.43 the
Supernote pencil and pen go direct to `/dev/ebc` and the glass shows a blue-noise dither of the
true-grey page — every one of the sixteen ladder greys previews honestly now. The four-tone
palette was a firmware-needle constraint that no longer applies on the direct path.

## The user's decisions (2026-09-19 — never re-ask)

1. **No size choice.** One pencil width, "the size of a real pencil, whatever Atelier uses" —
   **4 px** (`SketchPalette.PENCIL_WIDTH_PX`, the old third lead). Atelier's nib was never
   measured; its black HB reads back ~2.7 black px per px of length on the Nomad, so 4 px is
   walked against Atelier and the one constant is the knob.
2. **All sixteen greys** for the pencil (ladder levels 0–15); white stays the lightener lead.
3. **The gel pen takes the same sixteen-shade choice, white included** (the user's explicit
   choice over a fifteen-shade pen; see Traps — a white pen draws nothing under `DARKEN`).
4. **Swatches Atelier's way**: a filled circle of the shade, a white gap, a **dotted** black
   outer ring on every swatch; the **selected** swatch's outer ring is **solid**.
5. **Its own button and panel**: the picker leaves the Pencil re-tap and becomes a top-bar
   button — Tabler `palette` — with its own anchored panel. The panel edits the shade of the
   armed pen kind (pencil or gel pen; with the eraser armed, the kind last armed —
   `SketchToolState.tool` never holds the eraser).
6. **Both pen buttons wear their own shade**: the Pencil fill stays; the ballpen glyph gains a
   fill body (`ic_ballpen_fill`) tinted with the pen's shade.
7. **Branch `tools`** — expected to carry further tool arcs. Arc 46 "Palette", letter Q,
   this file.
8. Recipe unchanged from arcs 43–45: Fable orchestrates + writes the seam; Opus codes; Sonnet
   scaffolds; no Haiku; no code review (the standing waiver).

## Derived shape (reconciled, not re-asked)

- **No g-paper change.** `penColor` on a `PEN` contact already dithers live and bakes true grey
  into the ink raster (Phase 29); `penWidth` is plumbed unclamped, and 4 px sits above every
  floor (`GraphiteGrain.MIN_WIDTH_PX` 1, EMR hairline 120) and below every cap. Dropping the
  size menu also removes the one place `penWidth` was read un-latched mid-contact.
- **The seam grows one compatible tail**: `SketchToolSettings` gains a fourth field `penShade`
  (wire `int tool · int shade · int size · int penShade`, exhausted-parcel read → 0). `size`
  stays on the wire as a dead slot the face writes as 0 — removing it would be an in-place
  shape change and move the action floor for nothing. `API_VERSION` 20 → **21**, named by
  `SketchContract.MIN_API_VERSION_FOR_SKETCH_PEN_SHADE` = 21; `MIN_API_VERSION_FOR_SKETCH` stays
  20. Host prefs: a missing `penShade` decodes as 0 so a device upgraded from arc 44 keeps its
  tool and pencil shade.
- **`SketchPalette`**: `SHADE_LEVELS` = 0..15, one list for both kinds; `ROW_BREAK` 8 → two rows
  of eight; sizes deleted; `PENCIL_WIDTH_PX` 4, `PEN_WIDTH_PX` 5; defaults pencil 5, pen 0.
- **`SketchToolState`**: `tool · pencilShade · penShade`; `penColor` is the armed kind's shade;
  `pencilReport` / `penReport` for the two glyphs; `withShade` sets the armed kind's.
- **Chrome**: `btnPalette` after the eraser; `PaletteBar` (was `PencilBar`); `ShadeIcon` (was
  `PencilIcon`) for both glyphs; `ic_palette` + `ic_ballpen_fill` in `:sn-screen`; a Shades entry
  in the collapsed overflow; `CollapsedChrome.PenKinds` gains a defaulted `altIcon`.

## Phases

- **Q0** — branch `tools` + this plan.
- **Q1** — the seam (`:extension-api`, host prefs, contract-pin tests, the ext manifest → 21).
- **Q2** — `SketchPalette` + `SketchToolState` + their tests.
- **Q3** — the chrome (icons, layout, strings, `PaletteBar`, `ShadeIcon`, `SketchToolbar`,
  `SketchActivity`, `CollapsedChrome.altIcon`).
- **Q4** — docs (`docs/sketch.md`, SN `CLAUDE.md`, root `CLAUDE.md`), freeze on the user's Nomad
  hand walk. No `versionName` bump (version moves at a release).

- **Second walk (2026-09-19) — "the pen in its true tone":** the user saw the panel holds sixteen
  greys (Atelier's pen is solid) and asked, for the pen only, that the stroke re-present in its
  true tone after the live dither that keeps it under the nib. g-paper **Phase 31 → 0.1.45** on
  branch `ink-true`: `DitherFlatten.coverage` is the one display rule — a pixel the ink image
  covers with no live ink shows `255 − luma`; bare graphite and live ink dither; the pen-up bake
  re-posts each run through it, so the line lands solid; the window's `ALPHA_8` display byte is
  now a coverage (the band kernel writes tones for ink pixels; `landDitherRect` uses the byte as
  alpha; `presentPageViaPanel` maps through `LEVEL_OF_COVERAGE`); graphite is never shown in tone.
  core+ratta 342 green; published; SN re-pinned 0.1.45; installed on the Nomad. **Device
  questions**: the pen-up re-present (dithered white dots pass through black on the way to
  grey), an anti-aliased ink edge over dithered graphite, whether a grey pen's pen-up feels late.

- **Third walk (2026-09-19) — "not at pen-up":** "That looks okayish … instead of on pen up,
  perhaps when the tool is changed, or when flipping pages, or anything other than drawing."
  g-paper **Phase 32 → 0.1.46** (`ink-true`): the baked pen mark stays on the glass as its
  dither; its runs wait in `pendingInk`, and `settleInkTone()` re-renders them in tone at every
  non-drawing event the engine sees — the tool / penColor / penWidth / penStyle setters (a tool
  pick, a shade pick), and every raster change that is not the ink bake's own announce (a rub, an
  undo, a page load). 343 green; published; SN re-pinned 0.1.46; installed on the Nomad. Not
  settled by anything the engine cannot see (a chrome flip alone) — a host door would be a fresh
  decision.

- **Third walk, second finding (2026-09-19):** a re-tap on the armed pen changed no engine
  property, so nothing settled until a shade was picked — and that settle posted straight to the
  panel under the open panel, painting the stroke and its rect over the bar. g-paper **Phase 33
  → 0.1.47**: `PaperView.settleDisplay()` (a defaulted no-op; Ratta settles through window and
  panel), called by the host **before** chrome opens — the sketch face before the shade panel
  shows (`togglePaletteBar`), `PaperScreenActivity.toggleChrome` and `CollapsedChrome.open`
  (shared, `:ext-ink` / `:sn-screen`); setter-triggered settles are now window-only (the
  compositor carries them to the panel a beat later). SN re-pinned 0.1.47; on the Nomad.

- **Fourth walk (2026-09-19) — "the pencil too":** "That works. … The grain looks good. But if
  it is just black being dithered, perhaps it will look more natural with real tones with the
  grain?" g-paper **Phase 34 → 0.1.48**: `toneInk` becomes `settled`; a settled pixel either
  raster covers shows `255 − luma` — a fleck at its own alpha in the lead's tone, grain in grey;
  the graphite bake's runs wait in the same `pendingRuns` and settle at the same events; a loaded
  page is shown settled. The dither is now the **live** picture only (amends g-paper Phase 28's
  decision 7). SN re-pinned 0.1.48; on the Nomad. Walk question: a pale lead's low-alpha grain
  through the compositor's 16-level table.

## Traps to record at Q4

- A **white gel pen draws nothing** under `DARKEN` yet still lands opaque white pixels in the ink
  raster (allocating it) that the rubber never lifts — offered by decision 3, documented, not guarded.
- A **grey pen cannot show over darker graphite** — `DARKEN` is min per channel.
- The needle fallback (`RattaInkMap.pencilPreviewFor` / `firmwareColorFor`) still maps to four
  tones, only when `/dev/ebc` refuses to open.

## Ledger

- **Q0 ✅ 2026-09-19** — branch `tools` from `main` (94da6447); this plan.
- **Q1 ✅ 2026-09-19** — the seam: `SketchToolSettings.penShade` (fourth int, exhausted-parcel
  read → 0, `size` a dead slot kept on the wire), `SketchContract.MIN_API_VERSION_FOR_SKETCH_PEN_SHADE`
  = 21, `API_VERSION` 20 → 21, `:ext-sketch` manifest 21, `SketchToolPrefs`/`SketchToolCodec`
  fourth key `pen_shade` (missing → 0). Pin tests: `:extension-api` SketchContract 16 /
  SketchToolSettings 4 / ExtensionContract 10, `:app` SketchToolCodec 7 — all green.
- **Q2 ✅ 2026-09-19** — `SketchPalette` (sixteen levels, two rows of eight, `PENCIL_WIDTH_PX` 4,
  sizes gone, `DEFAULT_PEN_SHADE` 0, `shade(level, fallback)`), `SketchToolState`
  (`tool · pencilShade · penShade`, `armedShade`, `pencilReport` / `penReport`, `withShade` on the
  armed kind, `toSettings` writes `size` 0). Tests rewritten: `:ext-sketch` 96.
- **Q3 ✅ 2026-09-19** — the chrome: `ic_palette` + `ic_ballpen_fill` and `CollapsedChrome.PenKinds.altIcon`
  + `syncAltPen` (corner knob wears the armed kind's painted glyph) in `:sn-screen`; `ShadeIcon`
  (was `PencilIcon`), `PaletteBar` (was `PencilBar` — dotted ring on every swatch, solid on the
  armed, hairline on every fill), `btnPalette` after the eraser, `paletteBar` in the layout,
  strings `cd_tool_palette` "Shades" / `cd_shade*`; `SketchToolbar` takes `btnPalette` +
  `onPalette`, drops the pencil re-tap, re-inks both glyphs; `SketchActivity` rewired (Shades
  entry in the collapsed overflow anchors the panel on its own row). `:sn-screen` 118 green;
  both debug APKs build; the built ext manifest declares 21.
- **Q4 (docs) 2026-09-19** — `docs/sketch.md` (title arcs 43–46, the arc 46 decisions section,
  § "Q1's tail", § "Tools (arcs 44–46)", failure-table rows, traps, counts), SN `CLAUDE.md`
  (the arc 46 entry, `:ext-sketch` declares 21, the API ledger's 21), root `CLAUDE.md` (sketch
  bullet + table row, branch `tools`). Root suite **3632**, 0 failures. Debug host + ext-sketch
  installed on the Nomad for the user's hand walk. **Freeze awaits the walk.**
- **First walk (2026-09-19) — the user's amendments, all built the same day:**
  1. **Ink over graphite** — "the white pen should be able to write over the pencil … in the real
     world, a white gel pen can write over anything." Asked plainly and decided: the flatten is
     `SRC_OVER`, ink on top, everywhere (pencil over an ink line is hidden by the ink). g-paper
     **Phase 30 → 0.1.44** on branch `ink-over` (`CanvasPaperView.drawRasterLayers` null paint,
     `DitherFlatten.luma` + `band` blend ink over the graphite result, `DitherFlattenTest`'s darken
     test becomes the over test; core+ratta 341 green; published to mavenLocal). SN re-pinned;
     `SketchRaster` + `SketchCover` flip the same operator. **Amends arc 45's "no top and no
     bottom".**
  2. **Atelier's sixteen tones**, verbatim (`ffffff dddddd d0d0d0 c8c8c8 c0c0c0 b6b6b6 aaaaaa
     a0a0a0 909090 888888 808080 707070 686868 606060 505050 000000`), replace the `0x11` ladder:
     `SketchPalette.TONES` darkest first (0 black, 15 white — the two names every earlier build
     stored), a stored shade is a position in it; `DEFAULT_SHADE` 5 → **1** (`#505050`, arc 43's
     exact tone).
  3. **4 × 4**, white first, Atelier's order (`ROW_BREAK` 4, `shadeRows()` reversed).
  4. **The panel goes back onto the tool**: a re-tap on the armed Pencil opens it for the pencil's
     shade, on the armed Pen for the pen's — "since pencil and pen have independent shade
     selections, it makes better sense to attach the selection to the tool." `btnPalette`,
     `ic_palette`, `cd_tool_palette` and the Shades overflow entry are gone; `PaperToolbar.onPenReTap`
     and `CollapsedChrome.PenKinds.onReTap` take the kind (`:sn-screen`, defaulted, no other
     caller moves); the mini row's alt button is its own anchor. Root 3632 green; installed on
     the Nomad.

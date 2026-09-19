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

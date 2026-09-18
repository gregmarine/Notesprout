# PENCILS_PLAN.md — arc 44 "Pencils": pencil shades, pencil sizes and a gel pen (branch `pencils`)

The plan **and the ledger** for the second arc of `NSE · Sketch`, in the shape of
`SKETCH_PLAN.md` beside this file. Phases are appended to the ledger at the bottom as they land;
the reference doc stays `extensions/sketch/docs/sketch.md`, grown at T5.

**Status: Arc 44 — IN PROGRESS, T0 ✅ T1 ✅ T2 ✅ T3 ✅ T4 ✅ (folded into T3's walk) T5 ⬜.** Phases are lettered **T**
("Tools" — arc 37 "Bible" used B, arc 38 "Reference" R, arc 40 "Verses" V, arc 42 "Notes" N,
arc 43 "Sketch" K; T was checked unused across every `*_PLAN.md` at T0). The g-paper half is
g-paper's own **Phase 23 → 0.1.36** on its branch `pencil-tones`.

## Context

The sketch face has one tool: a 1.2 px `#505050` pencil (arc 43, decision 3: "no tilt, no width
choice, no colour"). Tilt is firmware-gated on Supernote — the bake is upright since g-paper
0.1.35 — so a sketch cannot be shaded. The user's decision of 2026-09-17 opens this arc: **fifteen
greyscale pencil shades, five pencil sizes, and one fixed gel pen**, remembered on the device.

It is a fresh user decision in the two places the standing rules require one: it **amends arc 43's
decision 3** ("no width choice, no colour"), and it grants the **first tool-options bar on an SN
paper screen** since P1 removed the tool panels ("no next Sketch phase … without a fresh user
decision" — this is that decision). Neither is a precedent for the notebook's own toolbar.

What the exploration found (2026-09-17), which shapes every phase:

- **The gel pen and the sizes need no engine change.** A gel pen is `StrokeStyle.PEN`, black, 3 px
  in `PageMode.RASTER` (3 px = `NotebookToolbar.PEN_WIDTH_PX`; Ratta arms `NEEDLE`, above
  `RattaEmr.EMR_MIN` = 200). `penWidth` already runs through `GraphiteGrain.of` on the bake and
  `RattaEmr.penSize` on the preview (PENCIL hairline floor 120, ceiling 1200 = 12 px).
- **Shades bake correctly from `penColor` today**, but `RattaPaperView.firmwarePenColor()` forces
  `PENCIL_PREVIEW_GREY` (DARK_GRAY) for every pencil, and the firmware has four colour codes of
  which three are usable. That is the one g-paper change.
- **Nothing is remembered today**, the seam carries no tool state, and an extension may not write
  to disk itself.

## Decisions (the user's, 2026-09-17 — binding; phase-start questions may not reopen them)

| # | Question | Decision |
|---|---|---|
| 1 | Ratta live preview across shades | **Map the pencil shade to the nearest usable firmware tone** (a g-paper change); thresholds are settled by the user's hand. The bake is always the true shade. |
| 2 | Which shades | **Fifteen**: `#000000 … #EEEEEE` in `0x11` steps, the e-paper ladder without white. Default `#555555` (level 5), replacing `#505050`. |
| 3 | Which sizes | **Five: 1.2 / 2 / 4 / 7 / 12 px** — starting values the hand may adjust. Default 1.2. |
| 4 | Gel pen | `StrokeStyle.PEN`, black, **3 px** (the notebook pen's width), one size, no options. "We may adjust after some testing of that." |
| 5 | Chrome | A third top-bar tool button — Pencil · **Pen** · Eraser. **Re-tapping the armed Pencil** opens one `AnchoredBar` under it: shade swatches (8 + 7) over a size row. The collapsed mini-toolbar gains Pen. |
| 6 | Memory | **Remembered on the device**: two `ISketchHost` tails, `API_VERSION` 18 → 19; the host keeps it in device-local prefs — one setting for all notebooks, not in the `.soil`, not in backups. |
| 7 | Pen glyph | Tabler **`ballpen`** → `ic_ballpen` in `:sn-screen`'s shared drawables; hint "Pen". `ic_pen` (Tabler's pencil) stays on the Pencil. |
| 8 | Naming | Arc 44 "Pencils" · Notesprout branch `pencils` · g-paper branch `pencil-tones` · letter T · this file. |
| 9 | Models | **Fable orchestrates**, writes the briefs and the AIDL seam, reviews every g-paper diff and every doc draft; **Opus codes**; **Sonnet** does scaffolding (XML / strings / drawable / doc drafts), JVM test runs and Nomad adb walks; **no Haiku**; no code review (arc 43 decision 14 carried). |

### Derived (reconciled from the exploration — not re-asked)

- **Carried unchanged from arc 43:** the rubbing eraser at 12 px; undo by gestures only, no
  arrows; no lasso on the face, ever; Erase page = ink only; "Bring in ink" composites black;
  `MAX_BYTES` 6 MiB; plain white paper; **no engine constant re-stated in the host**
  (`SketchToolbar`'s KDoc) — bake pressure 0.5, bake tilt 0, both EMR floors and the 16 ms cadence
  do not move in this arc.
- **Stored as indices, not values.** `SketchToolSettings` = tool (pencil / pen) + shade level
  0–14 + size index 0–4, so retuning a size at T4 never strands a stored value; anything out of
  range reads as the default.
- **The preview map is its own function.** `RattaInkMap.firmwareColorFor`'s thresholds are "do not
  revisit" and stay untouched; the pencil gets `pencilPreviewFor(argb)` with its own ladder,
  because a pencil bakes at pressure 0.5 through grain and reads lighter than its nominal colour.
  It must answer DARK_GRAY for `#555555` (K1's "spot on") and never LIGHT_GRAY (near-invisible).
- **`PaperToolbar` is changed in `:sn-screen`, never forked** — a second PEN-kind button and a
  pen re-tap hook, with `sync()` still the one truth. `PencilBar` itself lives in `:ext-sketch`
  (one consumer); `ic_ballpen` lives in `:sn-screen` (the shared vocabulary).
- **An older host (API 18) simply does not remember** — the two tails sit under a method floor,
  action floors untouched.
- **Bring in ink re-states pen colour / width / style for its bake** (`SketchActivity` ~L889);
  the armed settings must be restored after it.
- **Colour rule:** greys are ink, and appear in chrome only as the swatches being chosen (and, if
  T3's phase-start answer says so, the Pencil button reporting the armed shade).

## Phases (letter T; one per session; ⬜ 🔄 🧪 ✅; each ends commit + push; ledger below)

| Phase | Owner | What lands | Gate |
|---|---|---|---|
| **T0 — Plan lands** ✅ | Fable | Branches `pencils` (Notesprout) and `pencil-tones` (g-paper); this file; g-paper `PLAN.md` Phase 23 ⬜; memory `project_pencils_arc.md`. | Both branches pushed; `file` says this is text (the NUL trap). |
| **T1 — g-paper Phase 23 → 0.1.36: pencil preview tones** ✅ | Opus on Fable's brief; Fable reviews the diff; Sonnet runs tests + installs the demo | Pure `RattaInkMap.pencilPreviewFor(argb)`, own thresholds — start: levels 0–2 → BLACK, 3–9 → DARK_GRAY, 10–14 → GRAY. `firmwarePenColor()` routes PENCIL through it; the `PENCIL_PREVIEW_GREY` constant goes. `RattaInkMapTest` pins the ladder. The demo's raster toggle gains shade + size cyclers (the walk surface) and renders each of the five lead sizes to a PNG before a panel sees it (g-paper `CLAUDE.md`'s rule) — Sonnet pulls them, Fable looks. A temporary measurement door only if the first thresholds miss, removed before the phase closes. `docs/api.md` + `CLAUDE.md` in the same commit. | g-paper `./gradlew test` green (215 core / 12+ ratta); Paintsprout Onyx 203 green on its pin; **the user's hand on the Nomad** settles the thresholds; 0.1.36 published to mavenLocal (by the user's hand if the classifier refuses). |
| **T2 — Seam + host memory (API 19)** ✅ | **Fable: the seam**; Opus: host side; Sonnet: test runs | `SketchToolSettings` parcelable in `:extension-api`; `ISketchHost.toolSettings()` / `putToolSettings(…)` appended after the existing tails under `SketchContract.MIN_API_VERSION_FOR_SKETCH_TOOLS = 19`; `API_VERSION` 18 → 19, action floors untouched. Host: `SketchHostBinder` + a small prefs store, `SharedPreferences("sn_sketch_tools")` — **not** `sn_tool`, which `SnApplication` deletes at start. | `:extension-api:testDebugUnitTest` (**the pin test — must be run**) + `:app:test` green. |
| **T3 — The face** ✅ | Opus; Sonnet: layout XML, strings, `ic_ballpen`, the adb walk | Re-pin g-paper 0.1.36 (`sn-screen/build.gradle.kts` + the pin sentences). Pure `SketchPalette` (15 shades, 5 sizes, defaults; names "Black" / 1–14) + pure `SketchToolState`, both JVM-tested. `PaperToolbar` learns the second PEN-kind button + `onPenReTap`; `CollapsedTools` takes the third tool. `PencilBar` over `AnchoredBar` (the `EraserBar` pattern: armed entry `isSelected`; swatch = black ring + ink fill, a white gap ring when selected; rects unioned into `floatingRects()` / `floatingContains()`; `PenIdle.releaseRenderIfIdle` before assigning; never a frame while `paper.isPenActive`). `SketchToolbar` applies state → `penStyle` / `penWidth` / `penColor`; Bring in ink restores it. Settings loaded at `begin`, pushed on every pick. **Phase-start question:** how the Pencil button reports the armed shade (a light tint is invisible on e-ink — a floored tint vs. none). | `:ext-sketch:test` + `:sn-screen:test` + root `./gradlew test` green; Sonnet's Nomad adb walk (bar opens / closes, picks survive a reopen and a process death, screenshots, save bytes of a heavy 12 px black scribble against 4 MB watch / 6 MiB); then **the hand**: shades, sizes, gel pen feel, preview ↔ bake width agreement at every size. |
| **T4 — Walk adjustments** ✅ (inside T3) | Opus (small); Fable if engine | Whatever the hand found: size values, gel pen width, thresholds (engine → another g-paper patch + Fable's review). Skipped if T3's walk is clean. | The user's word. |
| **T5 — Docs + freeze** ⬜ | Sonnet drafts; Fable reviews every draft | `docs/sketch.md` (tools, seam tails, traps, numbers), SN `CLAUDE.md` (API 19, pin, counts, arc row), root `CLAUDE.md`, SN `docs/notebook.md` frame-silence ledger if touched, g-paper `PLAN.md` close, memory. **Phase-start question:** a `versionName` bump. Merge `--no-ff` in both repos **only on the user's word**; the Manta only if asked. | `file` on every touched doc = text; the final hand walk. |

**Order:** T0 → T1 → T2 → T3 → (T4) → T5. T2 does not depend on T1 and may go first if the Nomad
is not at hand.

## Critical files

- **g-paper:** `gpaper-ratta/…/RattaPaperView.kt` (`firmwarePenColor` ~L281, `PENCIL_PREVIEW_GREY`
  ~L124), `RattaInkMap.kt`, `RattaInkMapTest.kt`, `demo/…/MainActivity.kt` (`toggleRaster` ~L557),
  `gradle.properties` (`GPAPER_VERSION`), `docs/api.md`, `CLAUDE.md`, `PLAN.md`.
- **Seam:** `apps/notesprout_sn/extension-api/…/SketchContract.kt`, `ISketchHost.aidl`,
  `ExtensionContract.kt`, `ExtensionContractTest`; host `SketchHostBinder` / `SketchHostSession`.
- **Shared screen:** `apps/notesprout_sn/sn-screen/…/notebook/PaperToolbar.kt`,
  `CollapsedTools.kt`, `AnchoredBar.kt`, `EraserBar.kt` (the pattern), `res/drawable/ic_ballpen.xml`
  (new), `sn-screen/build.gradle.kts` (the pin).
- **Face:** `extensions/sketch/…/SketchToolbar.kt`, `SketchActivity.kt`,
  `res/layout/activity_sketch.xml`; new `SketchPalette.kt`, `SketchToolState.kt`, `PencilBar.kt`
  + tests.
- **Reference only:** `apps/notesprout_android/…/PenPalette.kt`, `PenColorPanelController.kt`.

## Verification

- **JVM:** g-paper `./gradlew test`; SN root `./gradlew test` from `apps/notesprout_sn` (baseline
  1816 `:app` / 3549 root — the `test-results` XML counts every test twice, halve it);
  `:extension-api:testDebugUnitTest` at the bump.
- **Device:** the Nomad `SN078D10012852` only; release extension packages `pm disable-user`'d for
  a `.dev` walk. adb cannot draw ink or double-tap — every feel judgment is the user's hand, given
  as a short numbered checklist.
- **Measured at T3:** PNG save size and undo tile bytes under heavy 12 px black coverage, against
  the K6 table in `docs/sketch.md`.

## Open items parked as futures (each needs a fresh decision — not this arc)

Gel pen sizes or colours · eraser sizes · a white / highlight pencil · per-notebook tool memory ·
generic-engine dirty-rect padding for a leaned wide pencil (`RasterDirty.of` pads by the nominal
width; harmless on Ratta and Onyx, which bake upright / report no tilt).

## Working protocol

1. One phase per session; the user clears context between phases.
2. Every phase starts by reading this file, `docs/sketch.md`, and the SN `CLAUDE.md` (T1: g-paper's
   `CLAUDE.md` + `PLAN.md` too).
3. Phase-start questions are wizard-style, one at a time, the explanation first.
4. Fable reviews every g-paper diff before publish and every Sonnet doc draft before commit.
5. Sub-agents never run git.
6. Walks on the Nomad only; the Manta only on the user's word.
7. Commit + push only on a green gate; one ledger entry per phase (Phase-start answers · Landed ·
   Deviations · Tests · Measured · Traps found · The hand · Next).

## Ledger

### T0 — Outcome (2026-09-17)

**Landed.** Branch `pencils` here and `pencil-tones` in `~/git/g-paper`, both from a clean `main`;
this file; g-paper `PLAN.md` Phase 23 ⬜. The nine decisions above were taken wizard-style in the
planning session, after three read-only explorations (the face, the engine, SN's rules).

**Traps found.** None yet. Letter T verified unused (K, N, B, Z, X are the letters in use across
the plan files).

**Next.** T1 — g-paper Phase 23. Needs the Nomad and the user's hand for the thresholds.

### T1 — Outcome (2026-09-17)

**Phase-start answers.** None asked.

**Landed.** g-paper Phase 23, **0.1.36** published to mavenLocal, `pencil-tones` `564d387` pushed.
`RattaInkMap.pencilPreviewFor(argb)` — `PENCIL`'s own ladder beside the untouched
`firmwareColorFor`; `firmwarePenColor()` routes `PENCIL` through it; `PENCIL_PREVIEW_GREY` gone.
`penColor`'s setter already re-arms the firmware pen, so a shade pick shows on the next mark. The
demo's raster page gained Shade + Lead cyclers (the walk surface). `PencilRenderHarness` (a JVM
test) renders the five leads × three shades to PNGs — geometry evidence only, it hand-mirrors
`drawPencil`'s fleck loop because the real bake is `android.graphics`.

**Deviations.** Fable made the install and the threshold edits directly (one-line changes inside
a live walk) instead of routing them through Sonnet / Opus.

**Tests.** g-paper 216 core / 17 ratta green; `:demo:assembleDebug`; Paintsprout Onyx 203 green on
its pin.

**Measured (the hand, Nomad).** The starting ladder put 3–9 on DARK_GRAY; **7–9 previewed darker
than they baked**, so the DARK_GRAY ceiling moved 161.5 → 110.5. **Final: 0–2 BLACK · 3–6
DARK_GRAY · 7–14 GRAY.** 12–14 also preview darker as GRAY than they bake; **LIGHT_GRAY was
trialled for them and rejected** ("that doesn't work") — GRAY stays the palest rung. All five
lead sizes pass: preview ↔ bake width agree at 1.2 / 2 / 4 / 7 / 12; no EMR change.

**Traps found.** The demo's Shade / Lead buttons are raster-only and the bar scrolls sideways —
say so in any walk checklist. `adb shell monkey` exits 251 on the Nomad with the app launched or
not; don't chain on it.

**Open, carried to T3's walk (the user's word, 2026-09-17).** The user may drop **levels 13 and
14** from the real palette — "keep the full list and I'll decide once it's in T3". T3 builds all
fifteen; `SketchPalette` and `PencilBar`'s 8 + 7 rows must make a shorter list a one-line change.
Stored shade indices already read out-of-range as the default.

**Next.** T2 — seam + host memory (API 19). No device needed.

### T2 — Outcome (2026-09-17)

**Phase-start answers.** None asked.

**Landed.** `ExtensionContract.API_VERSION` 18 → **19**. The seam (Fable): the
`SketchToolSettings` parcelable (`int tool · int shade · int size`, value semantics), two
`ISketchHost` tails after `redoPage` — `toolSettings()` (transaction code 12, **null = nothing
remembered**) and `putToolSettings(in …)` (13) — behind the method floor
`SketchContract.MIN_API_VERSION_FOR_SKETCH_TOOLS` = 19; `TOOL_PENCIL` 0 / `TOOL_PEN` 1;
`MAX_TOOL_SETTING_INDEX` 255. No action floor moved. The host (Opus): `data/prefs/SketchToolPrefs`
(`sn_sketch_tools`, three int keys, one `edit()` per put) over the pure `SketchToolCodec.decode`,
two new `SketchHostBinder.Hooks` members gated and funnelled like the rest, `SketchHostHooks`
delegating to a `SketchToolPrefs` built in `NotebookActivity` with the application context.

**Judgment calls (the seam).** The parcelable's bound is a **sanity bound (0–255), not the
palette's**: the host never learns how many shades or sizes exist and never clamps, so dropping
levels 13–14 at T3 is a face-only change and a stored 14 is a legal parcel the face reads as its
default. The defaults live in the face only — hence the null answer rather than a host-made
default. The eraser is never a remembered tool. Three small integers are not content and may be
logged.

**Deviations.** None. `:ext-sketch` still declares 18 — it redeclares 19 at T3, when it first
calls the tails.

**Tests.** `:extension-api:testDebugUnitTest` (the pin test, run) 292 green; `:app` **1822**
(+6, `SketchToolCodecTest`); root **3561** (+12: the codec's 6, `SketchToolSettingsTest` 4,
`SketchContractTest` +2). Sonnet ran the root.

**Traps found.** No test implements `SketchHostBinder.Hooks` — the binder shell has no JVM
coverage (`Binder.getCallingUid`), so the two overrides are proven only by T3's device walk
(picks survive a reopen and a process death).

**Next.** T3 — the face. Needs the Nomad; starts with the phase-start question on how the Pencil
button reports the armed shade.

### T3 — Outcome (2026-09-17) — with T4 folded in

**Phase-start answers.** *How the Pencil button reports the armed shade:* **the pencil glyph is
filled with the armed shade, its outline solid black** (the user's own shape, over the three
offered — a corner swatch dot, a floored tint, none); the gel pen's button never changes; the
collapsed corner knob and the mini row's pencil wear the same fill.

**Landed.** g-paper re-pinned **0.1.36 → 0.1.37** (`:sn-screen`). `:ext-sketch` declares API
**19** and calls the two T2 tails. Pure `SketchPalette` (an explicit list of ladder **levels**, a
list of px sizes, defaults, row breaks) and `SketchToolState` (tool · shade level · size index,
`fromSettings` reading anything unoffered as the default, `toSettings`, derived
style/width/colour, `reportedShade`). `:sn-screen` `PaperToolbar` grew a defaulted second
PEN-kind button (`btnAltPen` · `altPenArmed` · `onPenKindPicked` · `onPenReTap`), the armed test
against the *kind* not the tool; `CollapsedTools.penButtonSelected` + `iconFor(altPen)`;
`CollapsedChrome.PenKinds` (the alt button built right after `Tool.PEN`, a primary re-tap hook
that hangs a bar under the mini row's own button — the Insert precedent — and `PenIcon` tokens so
a filled glyph swaps only on a change); `AnchoredBar.addRow`; three defaulted hooks on
`PaperScreenActivity` (`collapsedPenKinds` · `onCollapsedClosing` · `keepCollapsedUnder`).
`PencilBar` in `:ext-sketch` over `AnchoredBar`: swatch = black ring + ink fill, a white gap ring
when selected (a black border is invisible on a dark swatch); size dots a legible dp ladder, never
literal px; **stays open after a pick**, closes on the Pencil re-tap, any tool change, a page
swap, a finger gesture, a chrome flip, an outside contact (a `dispatchTouchEvent` override), the
exit. `PencilIcon` (a `LayerDrawable`: tinted fill under the untouched outline). `SketchToolbar`
owns the state and applies it; `SketchActivity` restores it from `toolSettings()` inside
`openPage` before `opened = true` (a host below 19 never binds this extension — the manifest
declaration is the guard, K5b's arrangement), pushes `putToolSettings` on every pick and pen-kind
switch, fire-and-forget on IO **under a fair mutex** (Fable's one review fix: two quick picks
raced on separate IO hops), and `restorePen()` after every bake (defence only — `addStrokes` in
RASTER bakes from each stroke's own style and never touches the armed pen). `ic_ballpen` in
`:sn-screen` (Tabler upstream verified — its body path carries no trailing `z`); `ic_pen_fill`.

**The walk (the user's hand, Nomad, 2026-09-17) — T4 happened inside it, in five installs:**
- Sizes 1.2 / 2 / 4 / 7 / 12: preview ↔ bake agree. Gel pen **3 → 7 → 5 px** ("5 is perfect").
- Shades: of the fifteen, **only 0, 5 and 9 previewed as they bake** (the firmware's three
  tones). The list went 15 → 3 (0 · 5 · 9) → 6 (+2, 6, 11) → (6 → 7) → (0, 2 → 1, 3) → **1 · 3 ·
  5 · 7 · 9 · 11**, every other rung from 1 to 11, two leads per firmware band, no pure black on
  the pencil (the gel pen is black). Default stays 5. Decision 2 is **amended** to this.
- Wide leads: the g-paper demo with the EMR ceiling lifted walked **16 / 20 / 24 / 32 / 48 / 64 /
  96 px** — "all of those wide-lead sizes work", no lag. **g-paper Phase 24 → 0.1.37**
  (`pencil-tones` `fccfacd`): `RattaEmr.EMR_MAX` 1200 → **9600**; the old ceiling had never been
  reached by anything and its "the daemon lags" was never a measurement (g-paper `CLAUDE.md`'s
  new standing bullet). The sketch now offers **twelve sizes** — 1.2 · 2 · 4 · 7 · 12 · 16 · 20 ·
  24 · 32 · 48 · 64 · 96 — in two rows of six under the one row of shades. Decision 3 is
  **amended** to this.
- The filled pencil icon, the shade fill kept while Pen / Eraser are armed, the collapsed knob
  and mini row, the bar's dismissals, Bring in ink restoring the pen, undo/redo: all pass.

**Measured (Sonnet's adb walk, Nomad, `.dev`).** Bar at x 8–940 of 1404 (the 8 + 7 layout, before
the cut), fully on-screen; picks survive a reopen **and a process death** (`tools restored:
remembered — tool=1, shade=0, size=4` after `am force-stop`; the T2 binder overrides are now
proven). Heaviest save, black at 12 px, four fill-door passes: **607 519 → 591 880 → 559 914 →
524 533 B** — under the 4 MB watch by an order of magnitude; the 96 px lead was not measured for
save bytes (a later walk's number if it matters). `logcat -b crash` empty.

**Deviations.** T4 did not get its own session — every adjustment was a one-line change made and
installed inside T3's walk, five times over, so the phase is marked done here. Fable made the
gel-pen, shade-list and re-pin edits directly (T1's precedent) and did the g-paper demo's
ceiling experiment by hand; Opus wrote the Phase 24 patch and every larger face change.

**Tests.** `:ext-sketch` 60 → **87**; `:sn-screen` 114 → **118**; `:app` 1822 (unchanged);
`:extension-api` unchanged; SN root `./gradlew test` green. g-paper 216 core / **18** ratta.

**Traps found.**
- **The fill door draws on whatever page is showing.** The adb walk composited its twelve black
  test lines, four times, over the user's own tree sketch on the dev library's K7 page 2, and the
  process-death step took the undo history with it. **Every walk brief says "turn to a blank page
  first"** from now on.
- Two `PEN`-kind tools are one `Tool.PEN` to g-paper — the screen owns which kind is armed, and
  every "is the pen armed?" test in shared chrome has to ask the screen (`altPenArmed`), never the
  tool alone.
- `ROW_BREAK` (8) is inert at six shades; kept as the rule `shadeRows()` derives from.
- `SketchActivity` is ~1230 lines, past the ~800 guide; what remains is screen wiring.

**Next.** T5 — docs + freeze: `docs/sketch.md` (tools, the seam tails, the numbers, the amended
decisions 2 / 3 / 4), SN `CLAUDE.md` (API 19, pin 0.1.37, counts, the arc row), root `CLAUDE.md`,
g-paper `PLAN.md` close, memory; the `versionName` phase-start question; merge `--no-ff` in both
repos only on the user's word.

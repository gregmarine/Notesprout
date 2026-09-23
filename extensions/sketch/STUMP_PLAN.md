# Arc 50 "Stump" — the smear follows the hand, and a stylus Smudge tool (branch `tools`)

**Status:** 🔧 BUILT 2026-09-22, tests green (g-paper 809 / `RasterSmudgeTest` 19, sn-screen 124,
ext-sketch 104); dev host + ext-sketch installed on the Nomad; **awaiting the user's Nomad hand
walk.** Letter **U**. g-paper Phase 45 → **0.1.60** on branch `smudge-tool` (off `main`, unmerged);
SN pinned 0.1.60 on `tools`. `docs/sketch.md` § "The stump" is the reference; this file is the
plan + ledger.

## Why

The user, on arc 48's finger smudge (2026-09-22): *"does it push graphite around based on the
direction of the finger movement? For example, if rubbing left and right, does it push left and
right? Or if rubbing up and down, does it push up and down? And diagonally?"* It did not: the
0.1.54 blend was a square box, the same in every direction; only the carried load followed the
finger. *"If we can do that, let's give it a try. And let's also add a smudge toolbar button to
implement the ability to also do this with the stylus. Finger smudge would always be available.
But to use the stylus for the work, an explicit tool selection would be needed."*

## The user's decisions (2026-09-22 — never re-ask)

1. **The smear follows the hand.** Graphite is pushed along the direction of travel — left-right,
   up-down, diagonal alike.
2. **A stylus Smudge tool, by explicit selection.** A Smudge button on the sketch face arms the
   *stylus* for the rub; the finger's one-finger rub of arc 48 stays available under every tool.
   This **amends arc 48's decision 3** ("a one-finger gesture, not a stylus tool") — the gesture
   stands; the tool is added beside it, on the user's word.
3. Recipe as arc 48's: built directly by Fable (engine and face), JVM-tested, the user walks; no
   code review.

## Derived shape (reconciled, not re-asked)

- **Engine (g-paper Phase 45, 0.1.60).** `RasterSmudge.axis(sweep)` — the sweep's principal axis
  (the eigenvector of its segments' structure tensor, so an out-and-back batch reads as one line of
  travel; null under 2 px of travel). With an axis every plane's mean is gathered over the box
  **turned to it**: `spread` px to either side along, **`RasterSmudging.across`** (new, default
  **2**) to either side across, rounded to the lattice and deduplicated, cached per axis. No axis
  (a dwell, one sample) → the square box of 0.1.54. The read is still padded by `spread`.
  **`Tool.SMUDGE`**: the base's touch handling drives `beginSmudge` → `smudgeAlong` → `endSmudge`
  from the nib within **`PaperView.smudgeToolRadius`** (new, default **16 px** — a stump, half the
  fingertip's 32); Ratta's `firmwareInkSuppressed` includes it (no needle under the nib), Onyx's
  raw pipeline is off under it as under `NONE`. The barrel button / eraser end still point-erases.
- **Face (`:ext-sketch` + `:sn-screen`).** `btnSmudge` on the top bar after the rubber (Tabler
  `hand-finger` as `ic_smudge`, hint "Smudge"); `PaperToolbar` grew a defaulted `btnSmudge`;
  `CollapsedTools.ORDER` = PEN · ERASER · **SMUDGE** · LASSO_ERASER · LASSO and the sketch face's
  mini toolbar lists three tools. Not remembered on the device (the rubber's rule — no options).
  `SketchToolbar` sets `paper.smudgeToolRadius = SMUDGE_TOOL_RADIUS_PX` (16). The finger rub's
  gate (`!paper.isPenActive`) already keeps the two apart; the stylus smudge's `endSmudge` fires
  `onPenLifted`, so one undo entry per contact as before.
- **No seam change**, no `.soil` change, no `versionName` bump, no API bump.

## Phases

- **U0** — g-paper Phase 45 (`axis`, `orientedOffsets`, `orientedMean`, `RasterSmudging.across`,
  `Tool.SMUDGE`, `smudgeToolRadius`; Ratta + Onyx) + `RasterSmudgeTest` + 5; 0.1.60 published to
  mavenLocal. ✅ 2026-09-22 (core + ratta 809 green).
- **U1** — SN: pin 0.1.60, `ic_smudge`, `tool_smudge`, `PaperToolbar.btnSmudge`, `CollapsedTools`
  + `CollapsedChrome` hint, the sketch face's button + `collapsedTools()`; pin test moved. ✅
  2026-09-22 (sn-screen 124, ext-sketch 104 green); dev host + ext-sketch on the Nomad.
- **U2** — docs (this file, `docs/sketch.md` § "The stump", SN `CLAUDE.md`, root `CLAUDE.md`). ✅
- **U3** — the user's Nomad hand walk. Knobs if it is wrong: `RasterSmudging.across` (how much
  bleeds beside the line of travel — 0 is a pure streak, 6 is the old square box),
  `smudgeToolRadius` (the stump's width), and arc 48's `strength` / `spread` / `gamma` / `carry` /
  `deposit`. Freeze on the user's word; g-paper `smudge-tool` → `main` on the user's word.

## Walk ledger

- (pending)

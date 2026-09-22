# Arc 48 "Smudge" — the finger rub that blends graphite (branch `tools`)

**Status:** 🔧 BUILT 2026-09-22, dev build on the Nomad, **WALK PENDING**. Letter **S**. g-paper
Phase 40 → **0.1.54** on branch `smudge` (off `settle-gesture`; unmerged, mavenLocal).
`docs/sketch.md` § "The smudge" is the reference; this file is the plan + ledger.

## Why

A pencil hatch on the Nomad (screencap 2026-09-22) reads as separate lines with bare paper
between them. On paper the artist would rub a finger across it and the lines would run together
into a tone. The rubber (arc 43) lifts; nothing on the face *moves* graphite.

## The user's decisions (2026-09-22 — never re-ask)

1. **Pencil only.** The smudge reads and writes the graphite raster; the ink raster is never
   read, never allocated, never announced — the rubber's rule, held by the same construction.
2. **Blend and redistribute, never lift, never clump.** The look is shading, not charcoal: lines
   run together into a tone. As the graphite is redistributed the tone *may* pale a little, the
   way a real smudge does — "similar to the rub eraser, but this isn't removing".
3. **A one-finger gesture, not a stylus tool.** A natural rub: rapid, short back-and-forth. It
   must not fire the swipe gestures, and a swipe must not smudge.
4. Recipe: built directly by Fable (engine and face), JVM-tested, the user walks; no code review.

## Derived shape (reconciled, not re-asked)

- **Engine (g-paper Phase 40, 0.1.54).** `RasterSmudge` (pure, `geometry/`): under the finger
  every pixel is pulled toward the mean of its neighbourhood — a separable box of `spread` px on
  premultiplied channels — by `strength × coverage` per batch; coverage is the rubber's feathered
  corridor, **once per pixel per pass** on a pass mask like the rubber's (a reversal starts the
  next); `loss` scales the alpha per pass the same way. The read is
  padded by `spread`; the write and every announce are the corridor's rect. `RasterSmudging
  (strength 0.45, spread 6, feather 0.5, loss 0.04)` + `smudgeRadius` 32 px on `PaperView`;
  `beginSmudge()` / `smudgeAlong(points)` / `endSmudge()` are **host-driven** (the engine's own
  touch handling never starts one — a finger is never a tool there). Announces
  `onRasterWillChange(GRAPHITE)` / `onRasterChanged(GRAPHITE)` per batch like a rub;
  `endSmudge` fires `onPenLifted` so the face's per-contact undo entry closes unchanged.
  Ratta posts each batch to the panel (`onRasterSmudgedBatch` → `toneAndPost`); a smudge
  settles nothing that is waiting (Phase 37's rule).
- **Face (`:ext-sketch`).** `SmudgeRub` (pure Kotlin, JVM-tested): a one-finger sequence arms
  on the first **reversal of travel** (hops of 10 px, a turn past 120°) while the finger is
  still within **280 px** of where it landed — a swipe goes one way (never arms) and a bounced
  swipe turns back far away (never arms); the swipe's own floor is 30 % of the axis, 421 px on
  the Nomad. Samples before arming are delivered as the first batch, so the first stroke of the
  rub smudges too. Fed from `SketchActivity.dispatchTouchEvent` before the base feeds
  `PageGestures`; `standDown = { smudge.active }` stands every page gesture down on the event
  that armed it. Down-time gate: finger tool type, page open, pen gate open, not over chrome;
  the gate is re-asked at the turn and on every batch (a pen arriving mid-rub ends it). A second
  finger ends an armed rub and kills an unarmed one.
- **No seam change**, no `.soil` change, no `versionName` bump.

## Phases

- **S0** — g-paper Phase 40 (`RasterSmudge`, `RasterSmudging`, the `PaperView` triple, Ratta's
  post) + `RasterSmudgeTest` (8); 0.1.54 published to mavenLocal. ✅ 2026-09-22 (g-paper
  `smudge` 1bb5554; core + ratta 379 green).
- **S1** — `SmudgeRub` + `SmudgeRubTest` (8), the face wiring, SN re-pinned 0.1.54. ✅
  2026-09-22 (ext-sketch 104, sn-screen 214 green).
- **S2** — docs (this file, `docs/sketch.md` § "The smudge", SN `CLAUDE.md`, root `CLAUDE.md`);
  dev host + ext-sketch on the Nomad. ✅ 2026-09-22.
- **S3** — the user's Nomad walk: the scribble on page 20/20 rubbed with one finger. Knobs if it
  is wrong: `RasterSmudging.strength` (how fast it blends), `spread` (how far a line reaches —
  the hatch's gap is ~8–15 px, so 6 each side is two passes to close it), `loss` (how much
  pales), `smudgeRadius` (the fingertip); on the face `SMUDGE_HOP_PX` / `SMUDGE_ARM_WITHIN_PX`
  if it arms too eagerly or too late. Freeze on the user's word; merges (g-paper `smudge` →
  `main`) on the user's word.

## Walk ledger

- **Probe 1 (2026-09-22, agent-driven):** `adb shell input` cannot rub (a second per event —
  the long-press fired first) and the touch node is not shell-writable, so a **debug-only
  broadcast** in `SketchActivity` (`<pkg>.SMUDGE_PROBE --ei x --ei y --ei half --ei passes`)
  synthesises a finger back-and-forth through `dispatchTouchEvent`. Armed after 17 samples,
  stood the page gestures down, ran as batches to the panel, closed as one graphite undo
  entry (10 tiles), no crash. **Too pale**: the pull and the loss compounded per batch — 240
  batches for eight strokes left ~1 % of the tone. Reworked the same day to **per pass** on a
  pass mask (g-paper 0.1.54 republished): pull once per pixel per stroke of the arm, reset on
  a reversal.
- **Probe 2 (2026-09-22, agent-driven, per-pass build):** the same 180 px × 8 rub across a fresh
  strip of the hatch. The band went from separate lines to an even tone (screencap greyscale
  spread 47 → 12) at the same mean darkness as the untouched hatch above and below it (band
  210 between 227 and 197) — blended, redistributed, nothing lost beyond the 4 % loss. Armed at
  17 samples, one graphite undo entry (10 tiles), no crash. **The user's hand walk is next.**

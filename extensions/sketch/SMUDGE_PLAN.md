# Arc 48 "Smudge" — the finger rub that blends graphite (branch `tools`)

**Status:** ✅ COMPLETE + FROZEN 2026-09-22 on the user's Nomad hand walks ("It is looking so good
right now. Let's freeze this arc … This arc is a wrap!"). Letter **S**. g-paper Phase 40 →
**0.1.54** on branch `smudge` (off `settle-gesture`) and Phase 41 → **0.1.55** on branch
`always-dither` (off `smudge`) — SN pinned at 0.1.55; the g-paper branches **merged to g-paper `main` 2026-09-22 (`--no-ff`)
and deleted** (with `settle-gesture`, `ink-over`, `ink-true`); SN `tools` stays open as the
working branch by the user's word. Undo memory:
the user chose to leave the 48 MB before-image cap as it is (compressed tiles or session-merged
rubs are the levers if it ever matters). `docs/sketch.md` § "The smudge" is the reference; this
file is history (plan + ledger).

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
   must not fire the swipe gestures, and a swipe must not smudge. **Amended by arc 50 "Stump"
   (2026-09-22, `STUMP_PLAN.md`):** the gesture stands, and a stylus Smudge tool is added beside
   it on the user's word — "Finger smudge would always be available. But to use the stylus for
   the work, an explicit tool selection would be needed." Arc 50 also turns the blend's box to
   the line of travel (g-paper 0.1.60).
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
- **S3** ✅ — four Nomad hand walks 2026-09-22 (the ledger below), frozen after the fourth. Knobs if it
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
  17 samples, one graphite undo entry (10 tiles), no crash.
- **Walk 1 (2026-09-22, the user's hand): "It doesn't quite work as expected … it may have caused
  a crash … it just closed the sketchbook. The smudging seems to be more like an eraser … it
  looks like it is removing it. When I smudge, I would be making several passes of the spot."**
  Two findings. (1) **ANR, not a crash**: the dropbox trace put the main thread in
  `RasterRub.coverage` inside `smudgeBatch` — every pixel of the batch's rect against every
  segment; a real finger's event carries dozens of samples, the queue coalesced behind the
  first slow batch, 12 s on one event, "Input dispatching timed out", the face force-finished.
  Fixed in g-paper (0.1.54 republished, cc2827f): a per-segment **coverage field** (cost = the
  swept area), samples thinned under 2 px, batches chunked at 16, a `GPaperCore` warn line for
  any batch over 30 ms. (2) **The eraser look was the plain mean**: the pencil lays sparse dark
  flecks and the eye reads the hatch by them; their mean alpha spread evenly across a 64 px
  corridor is a pale wash. Now the target is the **power mean of darkness** (`gamma` 2 = RMS):
  a hatch settles to a tone denser than its mean — the way crushed graphite reads — and an even
  corridor is its own fixed point, so passes converge. `loss` 0.04 → 0.02.
- **Probe 3 (2026-09-22, finger-dense: step 2 px, 8 samples an event, 20 passes, 3 600
  samples):** 5.3 s of main-thread time in all (~1.5 ms a sample, a real finger sends one every
  ~8 ms), one batch logged over 30 ms (the first, 119 ms — warm-up + arming). The band is an even
  tone (greyscale spread 8) **darker** than the untouched hatch beside it (215 vs 228), feathered
  at the ends, no clumps.
- **Walk 2 (2026-09-22, the user's hand, before / after / after-settled screencaps in Preview):**
  no ANR, three rubs each one undo entry, the scribble one even cloud. Two asks: **"too light …
  closer to the original"** → `gamma` 2 → **3**; and **"it should be able to smudge out past the
  boundary … fade … just enough to dirty the paper under it"** → **the finger's load** (g-paper
  2eacf8c → ee99029): the darkest local tone under the finger's core is picked up, decays by
  `e^(−travel / carry)` (carry **40 px**), is topped up wherever the finger crosses something
  darker, and under the finger a pixel is pulled at least to `deposit` (**0.5**) of it, in the
  load's colour where the pixel has none; `carry` 0 lays nothing. First cut (corridor average,
  0.35, 30 px) laid a trail too faint to see (251–254 past the edge) — the average was watered
  down by the paper beside the mark. Probe 4 (200 px rub from inside the patch out): the trail
  now runs ~120 px past the edge, 232 → 252 fading to paper. **Walk 3 (the user's hand) next.**
  Knobs: `deposit` (how dark the trail starts), `carry` (how far it runs), `gamma` (the tone).
- **Walk 3 (2026-09-22):** page 22 smudged darker with a fading trail off the edge — no complaint
  on the smudge itself. Side decision, not this arc's but landed on it: **the panel is always
  dithered, the export always true tone** (g-paper Phase 41 → 0.1.55, `DISPLAY_SETTLES` false;
  the face's swipe-down settle removed) — "the dither looks great on the device, and the true
  tone looks great on the Mac." SN re-pinned 0.1.55. Next: the user's drawing test, then an
  export pulled to the Mac for Preview.
- **Walk 4 (2026-09-22, page 24):** "it seems I lost the sketch for a moment … a series of
  undo/redo … eventually the sketch I was working on magically came back." The log: every turn
  to page 24 (457 KB graphite) answered `the page's GRAPHITE raster could not be read:
  DeadObjectException` while page 23 (156 KB) read fine — the 512 KiB chunk reply overran
  Binder's shared 1 MB buffer beside the save push in flight; the pixels were never lost.
  `SKETCH_CHUNK_BYTES` 512 → **128 KiB** (`MAX_CHUNKS` 49), the pin tests moved, host + ext
  rebuilt. Not a smudge bug; a `docs/sketch.md` trap. Glass vs export of page 24 put side by side
  in Preview through the new `SKETCH_DUMP` door (the dither on the glass, the tone in the file).

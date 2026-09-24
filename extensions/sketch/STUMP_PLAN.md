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

- **Walk 1 (2026-09-22, the user's hand; screencap + `SKETCH_DUMP` export side by side in
  Preview):** the streaks follow the hand — horizontal in the top mass, diagonal in the lower
  stroke, the vertical tails kept — but the export was **far paler** than arc 48's blends: along
  a streak the graphite only thins (2 px across gathers almost nothing from the neighbours), and
  every pass still paid `loss`. Thin dark rules at each turn of the arm, where the axis flips and
  the load tops up. *"I want to keep the directional feel and the darkness."* → **`across` 2 → 4,
  `loss` 0.02 → 0** (g-paper 0.1.60 republished, SN rebuilt + reinstalled on the Nomad).
- **Walk 2 (2026-09-22): "It stopped responding."** Not an ANR: the log put every finger event at
  2–3.5 s inside the smudge — the gathered oriented kernel cost 117 samples a pixel a plane at
  `across` 4. **The oriented mean went separable** (g-paper bdc78c6): the axis quantized to the
  lattice's four (`quantizeAxis`), two running-sum line passes per plane (`lineMean`), O(n)
  whatever the reach; batches back to 32–48 ms. Screencap + export: vertical streaks hanging
  off the rubbed edge, fading — the direction reads; the export still pale in true tone.
  *"This is getting closer."* The stylus and finger looked different (16 vs 32 px reach; the
  stylus starts at the down, the finger at its first reversal) → **`SMUDGE_TOOL_RADIUS_PX` 16 →
  24** (2026-09-23, ext-sketch rebuilt + reinstalled).
- **Walk 3 (2026-09-23, screencap + export in Preview): "It looks good."** One ask: the smudge
  started before the nib moved and bloomed around the landing, a little of it *against* the
  travel — "if the smudge stroke is only down, the smudge effect should only go down". Two
  causes, neither asked for: the landing sample was a one-point batch with no axis, so it fell
  back to the square box; and the kernel was symmetric, reaching behind the nib as well as
  ahead. **The smear is now one-sided and travel-gated** (g-paper ae19161): along the axis a
  pixel is pulled toward the mean of the `2 × spread` px *behind* it in the direction of travel
  (`lineMean` takes `before` / `after`; `heading` = the sweep's net travel gives the sign), the
  corridor is clipped behind the sweep's first sample (`coverageField(heading)`), and a batch
  with no net travel returns before it announces anything (`smudgeChunk` too, so no undo tiles
  are read for a still nib). The read pad is `2 × spread`. Tests moved to two-point sweeps;
  813 green. Rebuilt + reinstalled on the Nomad.
- **Walk 4 (2026-09-23): "the page went blank. Even flipping pages didn't bring up the other
  pages."** Not arc 48's Binder overrun (no DeadObjectException, crash, ANR or memory pressure).
  The log: from 19:38:40 the stylus landed and lifted **four times a second for 45 s** under
  Smudge — *"I was rubbing quickly with the stylus"* — the EMR tip switch chattering at stump
  pressure while the Ratta ink daemon saw one long contact. Each contact (1) cancelled every
  finger gesture (`PageGestures.cancelAll` on a pen down), so no page turn ever completed, and
  (2) presented a window frame at its end (`finalizeEraseRedraw`), ~180 frames the display
  composer logged a rejection for from 19:38:53 while the daemon blanked and unblanked the
  framebuffer around its own pen sessions; the exact step that whitened the page is not in our
  log. Two hardenings (g-paper 0.1.60 republished; ext-sketch rebuilt + reinstalled): **the
  window mirror is deferred and coalesced on the direct path** (`onSmudgeEnded` hook,
  `RattaPaperView.SMUDGE_MIRROR_DELAY_MS` 400 — one frame a beat after the last contact, every new
  contact pushes it back), and **a chattering contact continues the same undo entry**
  (`SketchActivity.SMUDGE_CHATTER_MS` 300: under Smudge the entry stays open a beat after the
  lift; a replay flushes it first).
- **Walk 4, second finding (2026-09-23): "everything I had done is gone" after the reinstall.**
  The log: 100 s of smudging (a contact every 300 ms) after the reopen, and **no save in it** —
  every change restarted the 3 s debounce, and the pen-idle gate waits for hover to end besides,
  so a hand that keeps working never reaches a save until it pauses and lifts away; the install's
  force-stop skipped `onPause`. A crash would do the same. **The save now has a deadline**
  (`SketchSaveCadence`, pure + 4 tests; `SketchSaver.schedule` bounded; `notePenLifted` from the
  face): 15 s of unsaved work at most while the hand keeps going, then a copy at idle, at the
  next pen lift, or regardless (`MAX_DIRTY_MS` 15 s, `IDLE_LIMIT_MS` 5 s, `LIFT_LIMIT_MS` 5 s).
  ext-sketch 108 green, reinstalled on the Nomad.
- **Walk 5 (2026-09-23, screencap + export):** the pull reads in all three patches (down, right,
  diagonal), the export darker than earlier rounds. The log showed the deadline's save starting
  mid-work and then **follow-ups chaining at the encoder's rate** — a 3–4 s lossless encode back
  to back, fifteen in a minute — the old "a mark during the push is written the instant it
  lands" rule, harmless while saves began only at idle. Now the mark is put back as owed and the
  follow-up goes through the debounce and the deadline (`SketchSaver.finishPush` → `schedule()`).
  Walk 6 next.

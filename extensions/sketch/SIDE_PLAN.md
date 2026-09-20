# Arc 47 "Side" — the pencil's flank (branch `tools`)

**Status:** ✅ COMPLETE + FROZEN 2026-09-20 on the user's Nomad + Manta walks ("This feels good.
Let's freeze the arc."). Letter **R**. g-paper Phase 36 → 0.1.51 on branch `side-lead` (merged to
g-paper `main` at the freeze, branch deleted). `docs/sketch.md` § "Arc 47's decisions" is the
reference; this file is history (plan + ledger).

## Why

A real pencil draws with its tip through almost the whole range of grip. Only when the lead is
laid right over does the flank meet the paper: the mark becomes several times broader, the tip
edge stays densest, and the tone pales toward the far edge. That is how anyone shades.

g-paper's `GraphiteGrain` has carried a tilt model since Phase 11, but it was fitted to
Paintsprout's Wacom feel: the mark widens continuously from 9° (≈4.9× at 44°, ≈10.9× at 75°)
and pales as it goes. On the Manta (2026-09-17) that bloomed a hairline 10–15× at an ordinary
writing grip, and the user locked *"the Supernote pencil stays upright"* (`RattaPaperView.bakeTilt`
returns 0 for `PENCIL`; g-paper Phase 28 decision 5). Pressure, by contrast, is already right:
since Phase 28 the direct panel path bakes and previews at the hand's real pressure (darkness only).

This arc amends Phase 28 decision 5 rather than reversing it: **upright for every ordinary grip,
flank only past a side threshold**, with the threshold read from the user's own hand.

## The user's decisions (2026-09-19 — never re-ask)

1. **Calibration first.** A logging probe walk on the Nomad (`probe-tilt`, a dependency-free
   g-paper app like `probe-ebc`) before any curve work: upright · writing · shading · flat ·
   free passes; tilt, orientation, pressure per sample to a CSV. The threshold band and the
   dead-zone ceiling are fitted to those numbers, not guessed.
2. **Flank extent 20× the lead — 80 px on the 4 px lead** (decided after the probe walk,
   2026-09-19; about a real pencil's side, 5–8 mm ≈ 60–90 px at 300 ppi). One constant, the
   walk's knob.
3. **The flank pales as it widens** — the same graphite over a broader band; the existing
   lighten factor, retuned on the walk.
4. **Tip-dense, trailing to the far edge only if the device reports orientation**
   (`AXIS_ORIENTATION`). If it does not, the flank is a symmetric wider mark that does not trail
   off — no orientation model is invented. **R0 answered it: both Supernotes report a live lean
   direction**, so the flank is asymmetric — a one-sided strip from the tip along the lean
   toward the barrel, densest at the tip. The user is right-handed (confirmed 2026-09-19), which
   fixed the sign convention from the Manta's data alone.
5. Arc 47 "Side", branch `tools`, letter R, this file; g-paper branch `side-lead`.
6. Recipe unchanged from arcs 43–46: Opus codes the g-paper work on Fable's brief, Fable reviews
   every diff, the user walks on the Nomad; no code review (the standing waiver).

## Derived shape (reconciled, not re-asked)

- **Supernote only.** Onyx reports zero tilt by decision (`REPORT_TILT`), Paintsprout's Wacom
  curve is not the thing being changed. The new response is selected by the engine, not baked
  into the one core curve.
- **Preview and bake agree** (the standing rule): on the direct panel path the live mark is the
  grain's own flecks, so a leaned lead previews exactly as it bakes. On the needle fallback
  (panel closed) `bakeTilt` stays 0 — the firmware line cannot widen.
- **Fit offline first** with the Phase 25 recipe (fleck dump + numpy render) before a panel walk.

## Phases

### R0 — Probe (2026-09-19)
`probe-tilt` in g-paper: one Activity, five labelled passes, CSV of every stylus sample
(`pass, seq, t, action, tool, x, y, pressure, size, tiltDeg, orientDeg, distance, hover`), a crude
widen past 50° as on-screen feedback only. Installed on the Nomad; the user walks; the CSV is
pulled and read for: the writing-grip tilt distribution, the shading-grip distribution, the
laid-flat ceiling, and whether orientation is live (a real azimuth) or constant.

### R0 — Results (2026-09-19, Nomad `tilt-20260919-220438.csv` + Manta `tilt-20260919-221419.csv`)
The Ratta HAL breaks Android's axis contract: `AXIS_TILT` = signed **tilt-X in degrees**,
`AXIS_ORIENTATION` = signed **tilt-Y in degrees**, both live, panel space. Polar lean =
`hypot(x, y)`; azimuth = `atan2(y, x)` rotated panel→screen (Nomad 137° raw ≡ Manta 42°).

| grip | Nomad median (p95 / min) | Manta median (p95 / min) |
|---|---|---|
| upright | 10° (p95 17°) | 7° (p95 10°) |
| writing | 39° (p95 43°) | 29° (p95 40°) |
| shading | 56° (min 54°) | 61° (min 55°) |
| flat | 54° (min 50°) | 61° (min 56°) |
| ceiling | 62° | 72° |

**Threshold (fitted, both devices): tip only ≤ 45°, smoothstep bloom 45° → 54°, full flank ≥ 54°.**
Writing never crosses 43°; shading never drops under 50°.

**The Manta bloom of 2026-09-17 was a units bug, not a curve problem:** g-paper read `AXIS_TILT`
as radians, so a raw 30 became ~1719° and the old curve saturated (positive sign on the Manta;
negative on the Nomad, which clamped to 0 and hid it). Phase 28 decision 5 is amended, not
reversed.

### R1 — Curve (g-paper Phase 36 "The flank", 0.1.51)
The threshold response in `GraphiteGrain`, engine-selected; `RattaPaperView.bakeTilt` passes tilt
through for the pencil on the direct path; width cap + lighten retuned; orientation-driven
asymmetry only per decision 4. Offline fit, then a Nomad walk.

### R2 — Re-pin + docs
SN `:sn-screen` re-pinned; `docs/sketch.md` § "Arc 47's decisions"; PLAN + CLAUDE.md in both repos.

## Ledger
- 2026-09-19 · decisions 1–6 taken; g-paper `side-lead` branched; R0 probe commissioned.
- 2026-09-19 · R0 walked on the Nomad and the Manta (probe-tilt `c6dd29c`); decisions 2 and 4
  resolved; threshold fitted; the Manta units bug found. R1 briefed to Opus (Phase 36).
- 2026-09-19 · R1 built: g-paper Phase 36 `70fa080` (0.1.51) reviewed + committed; SN re-pinned
  `d064f44b`; dev host + ext-sketch on Nomad + Manta. Walk 1: writing grip unchanged, band lands,
  but **the flank is clumpy** (tooth-field cells decide whole sites at the flank's mid coverage).
  Fix `8acaf60`: `FLANK_TOOTH_WEIGHT` 0.20 on the spread, tone held. Walk 2: **still clumpy** on the
  Manta — the synthetic harness stroke (press 0.65, straight) was unrepresentative; the real
  shading hand is ≈0.22 pressure with jitter. Next: render from the real probe strokes, fix the
  cause, republish, reinstall; the user walks again 2026-09-20.
- 2026-09-20 · **The clumps' cause found and fixed** (g-paper `598da3c`, 0.1.51 republished): not
  the grain, the pressure or the sheet — the **fan** of cross-sections on a turning path. Stations
  sit 0.8 px apart along the path; a hand's 0.24° median / 4.6° p95 turn per station slips the
  80 px rim 0.34 / 6.4 px, so combs crowd three deep then leave 6 px bare — the worms. Rendered
  from the user's real Manta shading strokes (`FlankRenderHarness`, `GPAPER_PROBE_CSV`): a single
  pass 1.83 → 1.07 clump. Fix: deposit per unit of paper swept + split stations where the fan
  opens (`FAN_SPLIT_MAX`, ≤8/row); and the sheet now *scales* the catch odds (`FLANK_TOOTH_DEPTH`
  0.5) instead of offsetting them (the old form clamped light shading to nothing). `FLANK_LIGHTEN`
  0.84, fitted to the hand's strokes. ROUND + straight cells bit-identical. Dev host + ext-sketch
  reinstalled on Nomad + Manta. **Walk 3 pending** — the user.

### Walk 3 list (2026-09-20)
1. Writing grip: unchanged from before the arc.
2. Shading sweeps, the scribbled patch you actually shade with: even fine grit, no worms.
3. Which side the band lands: toward the hand (say if not, per device).
4. Light vs hard while leaned (range is narrower now, both ends toward "grey however hard").
5. A dab laid over → a short streak of grit. A drag along the lean → thin.
6. Speed: a fast turning sweep — any lag on the live line (the fan costs 2.8× stations on turns).
- 2026-09-20 · Walk 3 (Nomad + Manta): even grit, band toward the hand on both devices, light/hard,
  the roll — "This feels good. Let's freeze the arc." **FROZEN.** `tools` stays open for later tool arcs.

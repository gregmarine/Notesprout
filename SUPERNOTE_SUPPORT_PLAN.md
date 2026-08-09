# Supernote (Ratta) Support — Implementation Plan

> **Branch:** `supernote` · **Targets:** Supernote Nomad + Supernote Manta
> **Overall status:** Phases 0–8 ✅ done (2026-08-08/09, both devices, firmware 2389).
> `RattaNotebookView` ships the deferred-handoff writing loop — live firmware ink is
> hardware-validated ("the writing experience is gold"), smart lasso / scribble-erase traces
> self-clear via the **clear+frame retry ladder** (see Phase 3 findings: the two measured
> laws of the overlay wipe — a clear needs a co-presented app frame, and clears near pen-lift
> are eaten by a variable finalization window). **Phases 6–8 must inherit both laws, plus
> Phase 5's third law: the firmware latches pen state at contact start, so any suppress
> must be issued from the HOVER stream, before the tip lands.**
> Phase 5 shipped the hardware lasso trails (code 4 dashes / code 3 x-stream) and folded
> the physical eraser end into the hover-ahead barrel suppress.
> Phase 6 shipped the lifecycle layer: the static `inkOwner` guard (the successor's onResume
> precedes the predecessor's detach, so unguarded teardowns killed the new session — guard
> proved necessary statically), `resumeDrawing()` reclaim, real enable/disableDrawing, and a
> full `releaseResources` release; ten-scenario lifecycle script passed both devices round 1.
> Phase 7 extended the path to the remaining hosts (HWR enrollment descoped by the user) with
> ZERO host-side changes — the whole phase was two screen-space fixes in `RattaNotebookView`
> (complement disable bands clipping ink to the view's screen rect; real-panel-size
> fullScreenDisable). Passed both devices round 1; the round surfaced a PRE-EXISTING
> live-vs-baked horizontal registration offset, A/B-verified against a Phase 6 build and
> filed into Phase 8.
> Phase 8 closed the appearance gap: the registration offset was the MotionEvent stream
> (live ink is true to the tip) — fixed by a per-panel input x-shift (+2 px Nomad / +3 px
> Manta, REG-lab-measured); live overlay colour now maps to the nearest of the four firmware
> greys via `RattaInkMap` (thresholds calibrated to measured render tones — the codes paint
> far lighter than their names); baked ink keeps true hex, screencap-verified in colour.
> Next: Phase 9 (docs, device tiers, wrap-up — no device test).

---

## Start-of-session checklist

This plan is written to survive a cleared context. At the start of every session:

1. Read this file top to bottom (it is the only Supernote document — there is no `docs/` companion
   until Phase 9). **Read the *Prior art* section carefully** — a working PoC exists at
   `~/git/SupernoteDemo` and it corrects several things this plan originally got wrong.
2. Read **Phase status** below to find the first phase that is not `✅ DONE`.
3. Read that phase's section in full — it lists the files, the exact changes, the exit criteria, and
   the device-test script.
4. Re-read the **As-built code facts** section before touching any file. Line numbers in this plan
   are checked as of commit `be703f9` (2026-08-08); verify with `grep` rather than trusting them.
5. Build + install on **both** devices, hand the test script to the user, wait for the result.
6. On pass: tick the phase status here, commit, push. On fail: record the finding under the phase's
   **Findings** heading and iterate — do not advance the status.

**Do not skip ahead.** Each phase's exit criteria are what the next phase assumes to be true.

---

## Phase status

| # | Phase | Device test | Status |
|---|---|---|---|
| 0 | Baseline & firmware probe | ✅ yes | ✅ DONE (2026-08-08) |
| 1 | Port `SupernoteInk` + live-ink proof | ✅ yes | ✅ DONE (2026-08-08) — **dashed style found (code 4): Phase 5 GO** |
| 2 | Device gate + engine factory (no behaviour change) | ✅ yes | ✅ DONE (2026-08-08) |
| 3 | `RattaNotebookView` — core writing loop | ✅ yes | ✅ DONE (2026-08-08) |
| 4 | Handoff boundaries & mode transitions | ✅ yes | ✅ DONE (2026-08-09) |
| 5 | Firmware dashed ink for the live lasso path | ✅ yes | ✅ DONE (2026-08-09) — **+ hover-ahead suppress law; eraser-end folded into barrel suppress** |
| 6 | Lifecycle, process-global state, close | ✅ yes | ✅ DONE (2026-08-09) — **inkOwner guard proved necessary; full-screen disable at final teardown confirmed harmless system-wide** |
| 7 | The remaining five drawing hosts | ✅ yes | ⬜ NOT STARTED |
| 8 | Ink mapping & EMR stroke-width tuning | ✅ yes | ⬜ NOT STARTED |
| 9 | Docs, device tiers, wrap-up | — | ⬜ NOT STARTED |

Legend: ⬜ NOT STARTED · 🔧 IN PROGRESS · 🧪 AWAITING DEVICE TEST · ✅ DONE · ⛔ BLOCKED

---

## Decisions (2026-08-08, before any code)

These were settled with the user at plan time. Do not re-litigate them mid-branch.

1. **Engine structure: sibling copy.** `RattaNotebookView.kt` is a **copy** of
   `GenericNotebookView.kt` with the live-ink parts edited. `GenericNotebookView` is **not touched**
   — no `open`, no `protected`, no extracted base class. This is deliberate: it gets us running on
   Ratta hardware with provably zero risk to the ten non-BOOX devices already shipping on the
   Generic engine. The user's words: *"I know this isn't the wisest… but for now, this gets us up and
   running on Ratta's hardware. We can review and make a better solution in a future effort."*
   Phase 9 files the follow-up (collapse Generic + Ratta into a `CanvasNotebookView` base) into
   `BACKLOG.md`.

2. **No fallback.** There is no kill switch and no mid-session engine swap. Engine choice is decided
   once, at construction: `isRattaDevice() && SupernoteInk.isAvailable()` → `RattaNotebookView`,
   otherwise the existing `isBooxDevice()` / Generic split. If the firmware misbehaves *after* that
   — a dead binder, a throwing transact, a missing `enableFullUiAuto` — we **log it and show a
   toast**, and keep running the Ratta view. The user's words: *"If something is broken, I want to
   know."* Failures must be loud, never silently papered over.
   - `Log.w` (survives release) for every firmware failure, not `Slog.d`.
   - One toast per Activity instance, so a broken binder doesn't spam a whole writing session.

3. **Host scope: notebook first, then all six.** The factory (Phase 2) routes all six drawing hosts
   from day one, but the firmware path is proven on `NotebookActivity` through Phases 3–5 and only
   then extended to the other five in Phase 7. The scratch pad and sticky-note editor are
   **translucent overlay windows** and are the likeliest place a firmware overlay misbehaves — they
   get their own test pass rather than riding along.

---

## As-built code facts

Checked against the tree at `be703f9`. **The previous version of this plan was wrong about all
three of these** — it was written before the drawing stack was refactored.

### Engine construction happens in SIX places, not one

| File | Line | Screen |
|---|---|---|
| `NotebookActivity.kt` | 1239 | the notebook (primary) |
| `CalendarActivity.kt` | 389 | Month/Week/Day canvas |
| `DayDetailActivity.kt` | 372 | day-window **Note** canvas |
| `ScratchpadActivity.kt` | 274 | scratch pad (translucent window) |
| `StickyNoteEditorActivity.kt` | 142 | sticky-note editor (translucent window) |
| `HwrEnrollmentActivity.kt` | 156 | TrOCR enrollment writing surface |

All six are the identical line:

```kotlin
drawingView = if (isBooxDevice()) OnyxNotebookView(this) else GenericNotebookView(this)
```

The old plan named `NotebookActivity.kt:641` as *the* selection site. Editing only that line would
leave five screens on the Generic engine. **Phase 2 replaces all six with a factory call.**

### `isBooxDevice()` lives in `core/Device.kt`

The whole file is six lines:

```kotlin
package com.notesprout.android.core
import android.os.Build
import java.util.Locale

fun isBooxDevice(): Boolean =
    Build.MANUFACTURER.lowercase(Locale.ROOT).contains("onyx")
```

`isRattaDevice()` goes here, next to it. The old plan pointed at `NotebookActivity.kt:6709`, which
is unrelated code.

### `HiddenApiBypass` is already in place

`NotesproutApplication.kt:60` (not `:33`) calls `HiddenApiBypass.addHiddenApiExemptions("")` before
anything else runs. That is the one prerequisite for reflecting into `android.os.ServiceManager` and
the hidden `eink` system service — no new setup needed.

### Generic's live-stroke path (the thing Ratta replaces)

`GenericNotebookView.onTouchEvent` — `ACTION_MOVE` appends points to `activePoints` and calls
`invalidate()` **per event batch** (line ~353). `onDraw` (line ~719) then re-blits the committed
`RenderNode` *and* re-strokes the whole in-progress polyline:

```kotlin
if (!isEraserActive && !isLassoMode && activePoints.size >= 2) {
    val path = Path()
    path.moveTo(activePoints[0].x, activePoints[0].y)
    for (i in 1 until activePoints.size) path.lineTo(activePoints[i].x, activePoints[i].y)
    strokePaint.color = penColorInt
    canvas.drawPath(path, strokePaint)
}
```

**That per-move `invalidate()` → full-view redraw → EPD panel update is the latency.** On Ratta both
the `invalidate()` and this `onDraw` block go away; the firmware paints the live stroke instead.

`ACTION_UP` (line ~363) is unchanged in spirit: append the final point, `commitActiveStroke()` (which
appends a `LiveStroke` and re-records the committed `RenderNode`), `invalidate()`, then
`checkAndDispatchGesture()` which runs the smart-lasso / scribble-erase gates and finally fires
`onPenLifted`. Ratta keeps all of it and adds one thing: clear the firmware overlay once the baked
stroke is on the panel.

### Debug spike activities are an established pattern

`app/src/debug/` already holds `ColorInkSpikeActivity`, `PenToolSpikeActivity`, `CryptoSpikeActivity`
etc., each registered `exported="true"` in `app/src/debug/AndroidManifest.xml` with a long comment
recording its findings, launched by:

```sh
adb -s <serial> shell am start -n com.notesprout.android.dev/com.notesprout.android.debug.<Name>
```

Phases 0–1 use exactly this pattern (`SupernoteProbeActivity`). Findings get written into the
manifest comment, the same way the pen-tool and colour-ink spikes did.

---

## Prior art — `~/git/SupernoteDemo` (the user's own working PoC) ⭐

**This is the single most important input to the whole branch.** The user has a complete,
hardware-validated proof of concept, and it **corrects several things this plan got wrong**.

```
~/git/SupernoteDemo/
  NOTESPROUT_SUPERNOTE_INTEGRATION_PLAN.md   ← the user's own integration plan; read it
  README.md
  app/src/main/java/org/iccnet/supernotedemo/
    SupernoteInk.kt      ← 261 lines, self-contained, production-ready. Phase 1 ports this.
    DrawingView.kt       ← the reference implementation of the deferred handoff. Phase 3 mirrors it.
    MainActivity.kt  SupernoteDemoApp.kt  model/Note.kt  io/NoteStore.kt
```

Validated on **both** a Nomad and a Manta. What it proved on real hardware: the binder is reachable;
**live hardware ink under the pen is smooth at sub-frame latency**; points come from `MotionEvent`
(the firmware returns none); real EMR pressure arrives per point; software-hit-test erase works;
persistence round-trips; it survives a task switch; and — the headline — **no per-stroke flash or
ghost, once the deferred handoff model was adopted.**

`SupernoteInk.kt` has **no Notesprout dependencies** and ports verbatim with a package rename.

### ⚠️ Corrections the PoC forces on this plan

Read these before Phase 1. Each one was written into this plan from the KOReader source and is
**wrong**; the PoC found out on hardware.

1. **Never bake + `clearAll()` on every pen-lift.** This plan's Phase 3 originally specified exactly
   that, complete with three candidate orderings for the flash it produces. **The whole exercise was
   misconceived** — per-lift handoff fights the hardware and causes a flash plus a ghost/enlargement
   artefact. The correct model is a **deferred handoff** (below). Phase 3 has been rewritten.
2. **Pen type is `NEEDLE` (10), not `INK` (16).** This plan expected Ink. NEEDLE is the uniform-width
   ballpoint and matches our uniform-width baked polyline; INK and CALLIGRAPHY vary width with
   pressure/angle, so the live overlay visibly disagrees with the baked stroke.
3. **EMR size is `(widthPx * 100).coerceIn(200, 1200)`** — a width-3 pen → EMR **300**. This plan
   said to start "≈ our 3.0f stroke", i.e. EMR ≈ 3, which paints a **sub-pixel, invisible** line.
   The PoC hit this: *"This one bug masqueraded as 'no live ink.'"* Had we shipped the plan as
   written, Phase 1 would likely have been recorded as a failure. Eraser EMR is
   `(eraserRadius * 50).coerceAtLeast(400)`.
4. **Eraser mode calls `setEraser`** rather than merely suppressing the pen. The point is to stop the
   firmware painting NEEDLE ink along the eraser path while our software hit-test does the real
   removal. (Full-screen disable is still the right tool for lasso / text / dialog modes, where we
   want *no* firmware painting at all.)
5. **`enableAutoRegal(context, true)` at setup** — the anti-ghosting waveform. And **avoid
   `screenRefresh` / `sendOneFullFrame` per stroke**; both flash. `sendOneFullFrame` is fine on
   clear / erase-all, where a flash is expected anyway.
6. **`onWindowFocusChanged(true)` must re-assert the whole setup**, not just re-enable. The view
   stays attached across a task switch so `onAttachedToWindow` never re-runs, and while we are away
   the firmware hands the pen back to other apps and resets full-UI ink.

### The deferred handoff model (replaces per-lift baking)

```
While writing, per stroke:
  ACTION_DOWN / MOVE  → capture points from MotionEvent
  ACTION_UP           → add the stroke to the model AND to pendingStrokes.
                        Do NOTHING to the overlay — no bake, no clearAll, no refresh.
                        The firmware keeps showing it.

At a handoff boundary → releaseFirmwareOverlay():
  1. bake the pending strokes into the app layer   (one repaint)
  2. SupernoteInk.clearAll()                       (wipe the firmware overlay)
  3. invalidate()
```

The stroke enters the **model** on pen-up, so saves, snapshots, hit-tests and gesture gates are all
correct immediately — only the *visual* bake is deferred. This is exactly how `OnyxNotebookView`
already behaves, which is why `NotebookView` already exposes every hook the boundaries need.

**In Notesprout this is simpler than in the PoC.** The PoC keeps a separate `pendingStrokes` list and
replays it onto its bitmap. Our committed `RenderNode` re-records from the whole `strokes` list, so
the bake is just the existing `redrawCanvas()` call — deferring it means *skipping* `redrawCanvas()`
in `commitActiveStroke()` and calling it at the boundary instead. `pendingStrokes` shrinks to a
boolean "is anything pending".

### On the README's "injected touch events" caveat

`SupernoteDemo/README.md` says its verification table was produced "via injected touch events",
which would not exercise the EMR digitizer at all. The later
`NOTESPROUT_SUPERNOTE_INTEGRATION_PLAN.md` supersedes it, reporting live hardware ink verified on
both devices — and its account of the invisible-sub-pixel-EMR bug could only have come from real pen
testing. Treat the integration plan as authoritative. Phase 1 still confirms live ink under a real
pen as its exit criterion: it is the make-or-break, and it costs one minute.

---

## Reference material

### KOReader plugin source (local)

```
/Users/gregmarine/Downloads/koreader-supernote-eink-v1/plugins/pencil.koplugin/lib/supernote_ink.lua
```

Verified present. 255 lines of Lua/JNI. It is the primary spec for Phase 1 — port it call for call.

### Upstream Kotlin original

```
https://github.com/plateaukao/supernote_draw/blob/main/app/src/main/java/com/example/supernotedraw/SupernoteInk.kt
```

A decompile of the Supernote Document app's `HandWriteClient`. **The EMR size → stroke-width mapping
is missing from the Lua and lives only here** — pull it before Phase 8 (and ideally before Phase 3,
so the first ink is a sane width).

---

## How the Supernote ink path actually works

`supernote_ink.lua` is **not** a drawing engine — it is a thin **JNI client for a firmware-side ink
daemon**:

- The firmware registers a Binder service `service_myservice` (legacy alias `service.myservice`),
  interface token `android.demo.IMyService`.
- The app talks to it via raw `Parcel` / `IBinder.transact`. Every transaction writes
  `writeInterfaceToken(token)` + `writeString(appName)`, then a small int payload:

  | tx | Name | Payload | Purpose |
  |---|---|---|---|
  | 0 | `WRITE_APP_INFO` | `mode`, `value` | claim pen ownership |
  | 1 | `DISABLE_AREA` | `count`, then `x,y,w,h,flags` per rect | where the firmware must **not** paint |
  | 2 | `PEN` | `type`, `sizeEmr`, `color` | pen or eraser configuration |
  | 6 | `DRAW_BUFFER` | `255`, `0` | clear the EPDC ink overlay |

- Pen type codes (Nomad, deviceType = 3 / A5X2): Needle 10, Ink 16, Mark 11 (highlighter),
  Calligraphy 15. Eraser is written through **tx=2** as type `1` (freehand) or `3` (rectangular)
  with colour `255`.
- Colour codes: `BLACK 0`, `DARK_GRAY -101`, `GRAY -102`, `LIGHT_GRAY 254`. **Four values, that's
  the whole palette** (see the ink-mapping problem in Phase 8).
- `enableFullUiAuto(true)` via reflection on `getSystemService("eink")` — required so a
  *third-party* app gets ink painted everywhere, not just inside whitelisted firmware apps.

**The critical architectural fact:** the firmware paints stroke pixels to the EPDC overlay at
sub-frame latency, but it gives back **no point data**. KOReader configures the overlay, lets the
firmware paint live, then *clears the overlay once the finished stroke is baked into its own
buffer*. Points come from the normal Android input stream (`MotionEvent`).

**`DISABLE_AREA` is also the "firmware off" switch.** There is no explicit disable transaction —
`setFullScreenDisable(w, h)` sends a single rect covering the whole panel, which is how you stop the
firmware painting at all (dialogs, lasso mode, eraser mode). `clearDisableAreas()` sends a zero-rect
list to turn it back on. This is not spelled out in the Lua's naming and is easy to miss.

This maps onto our existing engine model:

| Concern | Onyx | **Ratta (new)** | Generic |
|---|---|---|---|
| Live-stroke ink | SDK overlay (`TouchHelper`) | **Firmware binder overlay** | App Canvas (slow on e-ink) |
| Point capture | `RawInputCallback` | **`MotionEvent` (onTouchEvent)** | `MotionEvent` |
| Bake + erase/lasso/text/snapshot | committed `RenderNode` | **committed `RenderNode`** | committed `RenderNode` |
| "Release the overlay" | `setRawDrawingRenderEnabled(false)` | **`clearAll()` + full-screen disable** | n/a |
| "Keep ink off the toolbar" | `setLimitRect(limit, exclusion)` | **`setDisableAreas([toolbar])`** | n/a (toolbar eats its own touches) |

Everything else — software erase hit-testing, lasso, smart-lasso / scribble detection, text
placement, shape transform, snapshot capture, `buildRenderBitmap` / `loadStrokesWithBitmap` — is
byte-for-byte the Generic logic and needs no change.

---

# Phases

---

## Phase 0 — Baseline & firmware probe · ✅ DONE

**Goal:** answer "is the firmware path even available to us, and does the pen reach us as a stylus?"
before writing a line of engine code. Also establish the latency baseline we are trying to beat.

**Nothing in `src/main` changes in this phase.**

### Build

- `app/src/debug/kotlin/com/notesprout/android/debug/SupernoteProbeActivity.kt` — new. Read-only
  detection, results rendered as large on-screen text (the panel is the report; e-ink screenshots
  can't capture an EPD overlay, and BOOX/Ratta logcat ring buffers overflow — see
  `reference_boox_logcat_flood`). Report:
  - `Build.MANUFACTURER`, `Build.MODEL`, `Build.BRAND`, `Build.DEVICE`, SDK level
  - `ServiceManager.getService("service_myservice")` → present / null; same for `"service.myservice"`
  - `IBinder.getInterfaceDescriptor()` and `isBinderAlive()` on whichever resolved
  - `getSystemService("eink")` → present / null; class name; whether `enableFullUiAuto(boolean)`
    exists on it via reflection (do **not** call it yet)
  - a scratch strip that reports, for the last touch: `getToolType(0)`, `pressure`,
    `getAxisValue(AXIS_TILT)`, `buttonState`, and whether an eraser end / barrel button reports as
    `TOOL_TYPE_ERASER`
  - `DisplayMetrics` (w × h px, density) and the activity window's `getLocationOnScreen` offset for
    a full-bleed child view
- `app/src/debug/AndroidManifest.xml` — register it `exported="true"`, matching the existing spikes.

### Device test

```sh
cd apps/notesprout_android && ./gradlew assembleDebug
adb -s SN078D10012852 install -r app/build/outputs/apk/debug/app-debug.apk   # Nomad
adb -s <MANTA-SERIAL>  install -r app/build/outputs/apk/debug/app-debug.apk   # Manta
adb -s <serial> shell am start -n com.notesprout.android.dev/com.notesprout.android.debug.SupernoteProbeActivity
```

Ask the user to, **on each device**:
1. Read back the probe report (photo or read-aloud).
2. Touch the scratch strip with the pen, the eraser end, and a finger; read back each tool type.
3. Then open a real notebook in the dev app, write a few lines, and describe the lag — this is the
   Generic-engine baseline we are trying to beat.

### Exit criteria

- [x] The binder resolves on **both** devices (`service_myservice`; the `service.myservice` alias
      is absent — the first name always wins).
- [x] The `eink` service exists and exposes `enableFullUiAuto(boolean)` on both.
- [x] The pen reports `TOOL_TYPE_STYLUS` (eraser end `TOOL_TYPE_ERASER`, finger `TOOL_TYPE_FINGER` —
      the full three-way identity, identical on both).
- [x] Baseline writing latency described for both devices (unusable — see Findings).
- [x] Nomad and Manta report the same binder / service / tool-type story (the plan's premise that
      they are one target).

### Findings

#### Part 1 — adb interrogation (2026-08-08) ✅

Everything reachable over adb is **done**; only the runtime half (tool type, pressure, the binder
handshake, the pen-type sweep) still needs the probe app. Both devices attached and interrogated.

**Devices.** Serials: Nomad `SN078D10012852`, Manta `SN100C10023972`.

| Prop | Nomad | Manta |
|---|---|---|
| `ro.product.manufacturer` | `Supernote` | `Supernote` |
| `ro.product.model` | `Supernote Nomad` | **`Supernote Nomad`** ⚠️ |
| `ro.product.brand` / `.device` / `.name` | `Supernote` | `Supernote` |
| `ro.build.display.id` | `Chauvet.E103.2605211001.2347_release` | *identical* |
| Android | 11 (SDK 30) | 11 (SDK 30) |
| Physical size | 1404 × 1872 | 1920 × 2560 |
| Density | 300 | 300 |

Three things fall out of that table:

1. **`Build.MANUFACTURER` is `"Supernote"`, not `"ratta"`.** The `isRattaDevice()` sketch in Phase 2
   guessed `contains("ratta")` — that would return **false** on both devices. Match on
   `"supernote"` (manufacturer *or* brand; both carry it). Phase 2 has been corrected.
2. ⚠️ **The Manta identifies itself as a Nomad.** Every product property is byte-identical across the
   two devices — model included. **There is no name-based way to tell them apart**; the only
   difference visible to an app is the resolution (1404×1872 ≈ 7.8″ vs 1920×2560 ≈ 10.7″, both at
   300 dpi). Any future per-device branching must key off screen size, never `Build.MODEL`.
3. **They run the identical firmware build**, down to the build id — and have identical service
   tables (179 services each, `service_myservice` at the same index). This is much stronger evidence
   for the plan's "treat them as one target" premise than the KOReader author's inference was.

**The binder is present on both.** `service list` reports `service_myservice: []` on each. The empty
interface descriptor is expected and *good* — it means a raw `Binder`, not an AIDL stub, which is
exactly the `Parcel`/`transact` design the Lua implements. Only `service_myservice` is registered;
the legacy `service.myservice` alias is **not** present, so the first name always wins. No system app
under `/system/priv-app` or `/system/app` contains the token — the service is registered natively
(`ht.hardware.hteink@1.0-service` is the likely host), so there is nothing further to decompile and
the Lua remains the spec for the transaction payloads.

**The eink service is real, and richer than the Lua uses.** `eink: [android.os.IEinkManager]` is
registered, and `android.os.EinkManager` was dumped out of `/system/framework/framework.jar` with
`dexdump`. **Confirmed present, with exact signatures:**

```
enableFullUiAuto(Z)V            enableFullUiAuto(ZZ)V      ← the one the Lua needs, plus an overload
isValid()Z                      getEinkEnabled()Z
screenRefresh(ZI)V              sendOneFullFrame()V        freezeScreen(ZI)V
setScreenMode(IZ)V              setDitherType(I)V
enableAutoRegal(ZI)V            disableRegal(ZI)V
setStylusGuestureEnabled(Z)V    setGlobalGuestureEnabled(Z)V     [sic — "Guesture"]
```

with screen-mode constants `EINK_SCREEN_MODE_CLEAR / DEFAULT / SMOOTH / SPEED`.

Two of these are likely to matter later and are **not** in the Lua:

- **`setScreenMode(EINK_SCREEN_MODE_SPEED)`** looks like the Supernote analogue of the BOOX app-scope
  handwriting fast-mode that fixed our first-stroke lag (`docs/drawing-engine.md`). If Phase 3 shows
  a warm-up delay on the first stroke, this is the first thing to try.
- **`setStylusGuestureEnabled(false)`** may be needed to stop the firmware's own system stylus
  gestures competing with our lasso and scribble gestures.

**Prior art found.** `org.iccnet.supernotedemo` — see the *Prior art* section above. It changes how
Phase 1 should be approached.

#### Part 2 — probe app ✅ (2026-08-08)

Built as one combined Phase 0 part 2 + Phase 1 deliverable: `SupernoteInk.kt` ported to
`notebook/ratta/` (src/main) and `SupernoteProbeActivity` (debug) — report panel, live touch
readout, firmware write area, 0…31 pen sweep, CLEAR / STRIP / FULL FRAME buttons. Installed and
exercised on both devices. **Everything below verified identical on Nomad and Manta unless noted.**

- **Runtime binder handshake works.** `service_myservice: alive=true desc=""`;
  `service.myservice` null (as the adb half predicted); `SupernoteInk.isAvailable()=true`.
  `eink=android.os.EinkManager` with `enableFullUiAuto` present.
- **Pen tip reports `TOOL_TYPE_STYLUS`** — the make-or-break criterion passes. Dense point
  stream (590/699 pts for one squiggle), real pressure, real tilt (signed, e.g. −6/+27).
  Risk 7 (coarse MotionEvent stream) is dead: point capture is full-rate.
- **Eraser end reports `TOOL_TYPE_ERASER`** with its own full point stream. ⭐ **Unplanned
  finding: the firmware natively erases its own overlay ink along the eraser-end path** —
  the probe never called `setEraser` (pen was still armed as code 10), yet the physical
  eraser end wiped overlay pixels. So the eraser-end case comes free at the overlay level;
  `setEraser` remains relevant for erasing with the pen *tip* when our eraser tool is armed.
  Caveat for Phase 4: firmware erase only touches pixels the firmware painted — baked
  app-layer strokes need our software erase + redraw, exactly as the plan already models.
- **Finger reports `TOOL_TYPE_FINGER`, paints nothing**, and delivers a full drag point
  stream (129/96 pts) — everything the pen-activity gate and finger gestures need.
- **Live firmware ink under a real pen: "fantastic" (user's word).** NEEDLE(10) @ EMR 300.
  CLEAR leaves no ghost.
- **Screencap cannot see the firmware overlay** (confirmed empirically: blank write area in
  `screencap` while visible ink on panel) — same trap as the BOOX EPD overlay. Android-UI
  chrome (report/readout) *is* capturable, so probe readouts can be read over adb; ink
  appearance needs the user's eyes/photos.

- ⭐ **The ink path survived a firmware update** (2026-08-08, mid-test): the Manta was updated
  `Chauvet.E103.2605211001.2347_release` → `Chauvet.E103.2606141001.2389_release` and the binder
  (`alive=true`), `enableFullUiAuto`, and live pen ink all still work. First real evidence the
  reverse-engineered path has longevity across Ratta firmware revisions. (Nomad being updated to
  match; until then the two devices span two firmware builds — a behavioural *difference* between
  them from here on would be firmware drift, not device drift.)

- **STRIP disable-area boundary: works** ("like a charm") — ink stops at the band's edge and
  returns when cleared. The toolbar-exclusion mechanism is proven.
- **Home → relaunch → write: passes** on both devices — the `onWindowFocusChanged(true)`
  re-assert reclaims the pen and full-UI ink after a task switch.
- **Generic-engine baseline (the thing this branch replaces): unusable.** User's words: *"I
  could be finished writing a word before the first letter of that word appears."* Strokes do
  render (slowly) and persist across close/reopen — the data path is fine, the live-ink
  latency is the whole problem. This is the before-picture Phase 3 is measured against.
- Both devices ended the session on firmware `2606141001.2389` (Nomad updated mid-test to
  match the Manta).

---

## Phase 1 — Port `SupernoteInk` + live-ink proof · ✅ DONE

**Goal:** get the proven firmware client into Notesprout and confirm live ink under a real pen.
Thanks to the PoC this is now a **port, not a translation** — most of the original Phase 1 scope is
already done and hardware-validated.

### Build

- `notebook/ratta/SupernoteInk.kt` — **copy
  `~/git/SupernoteDemo/app/src/main/java/org/iccnet/supernotedemo/SupernoteInk.kt` verbatim.** It has
  no Notesprout dependencies. Changes to make:
  - package → `com.notesprout.android.notebook.ratta`
  - `APP_NAME` → `"notesprout"` (arbitrary strings are accepted — proven, see Prior art)
  - logging → keep `Log.w` / `Log.i` as-is. **Do not** convert to `Slog.d`: per Decision 2 firmware
    failures must survive into release builds.
  - add the one-toast-per-Activity reporting required by Decision 2, or expose a failure callback the
    view can toast from. Keep `SupernoteInk` itself Context-light.
  - keep `einkApiDumped` — it is a useful one-time diagnostic and already gated.
  - keep both `SERVICE_NAMES` entries even though Phase 0 found only `service_myservice` registered;
    the fallback costs one null check.

- `SupernoteProbeActivity` (debug) — the driver panel for the sweep below. Do **not** rebuild the
  read-only detection UI; Phase 0 part 1 already answered all of it over adb. What is still needed:
  - a live readout of the last touch: `getToolType(0)`, `pressure`, `AXIS_TILT`, `buttonState`
  - **the pen-type sweep** (below)
  - a white write area, and buttons for *Clear all*, *Disable top strip*, *Clear disable areas*

- **Pen-type sweep.** The Lua and the PoC both name four codes (Needle 10, Mark 11, Calligraphy 15,
  Ink 16) — those are the four the *Supernote UI* exposes, not necessarily all the firmware holds.
  Step a raw code over **0…31**, writing a short stroke at each, and record what renders. This is the
  same sweep `PenToolSpikeActivity` ran against Onyx, which found three working styles past the SDK's
  published constant list — including `STROKE_STYLE_DASH`.

  **We are hunting for a dashed or patterned style**, because that is what unlocks Phase 5. Use a
  known-visible EMR (300) for every code, or a thin one will read as "renders nothing".

### Device test

Install both. In the probe, on **each** device:

1. Write in the white area → **is the ink instant and smooth?** (Setup runs on attach.)
2. *Clear all* → does it vanish cleanly, no ghost?
3. Step the pen-type code 0 → 31, writing a short stroke at each. Record which render and what each
   looks like. **Is any of them dashed or patterned?**
4. Confirm the tool-type readout says `TOOL_TYPE_STYLUS` for the pen tip, and what the eraser end and
   barrel button report.
5. *Disable top strip* → write across it → does ink stop at the boundary? *Clear disable areas* →
   does it paint there again?
6. Home, then relaunch → does ink still work? (This is the focus-regain re-assert.)
7. Touch with a finger → no ink.

### Exit criteria

- [x] Live firmware ink under a **real pen**, on both devices, visibly faster than Generic
      (user: "fantastic" vs. the Generic baseline's word-behind lag).
- [x] `clearAll()` leaves no ghost.
- [x] Disable areas suppress painting in the given rect (STRIP test).
- [x] Pen reports `TOOL_TYPE_STYLUS`; finger does not ink.
- [x] The 0…31 sweep is recorded — **YES, a dashed style exists: code 4 (dashes), plus code 3
      (x-stream). Phase 5 is GO.**
- [x] Identical on Nomad and Manta (sweep run on both pre-update; production codes 10/4/3
      re-verified on the updated 2389 firmware).

> **If live ink does not appear, check the EMR size first.** An EMR near zero paints an invisible
> sub-pixel line and looks exactly like a dead firmware path. The PoC lost time to this. Use 300.

### Findings

#### The 0…31 pen-type sweep (2026-08-08, user-run, EMR 300, colour BLACK) ✅

| Code | Renders | Appearance / notes |
|---|---|---|
| 0 | ✅ | solid, steady (uniform width) |
| 1 | ✅ | solid, **pressure-sensitive** — NOT an eraser when sent with black (eraser semantics evidently come from the colour-255 payload, not the code alone) |
| 2 | ✅ | solid, pressure-sensitive |
| 3 | ✅ | **stream of tiny x's** — the Supernote lasso-eraser trail. Does not erase (with black). ⭐ Phase 5's lasso-eraser visual |
| 4 | ✅ | **stream of dashes** — the Supernote lasso-selector trail. ⭐⭐ **THE DASHED STYLE EXISTS — Phase 5 is unblocked** |
| 5 | ✅ | solid, steady |
| 6, 7, 9, 13 | ❌ | nothing |
| 8 | ✅ | solid, steady |
| 10 | ✅ | solid, steady — matches NEEDLE as assumed (production live-ink pen) |
| 11 | ✅ | solid, steady — does **not** render as a marker here; explore someday |
| 12 | ⚠️ | solid, steady, but **sometimes a giant laggy blob — treat as broken, never use** |
| 14 | ✅ | possibly calligraphy |
| 15 | ✅ | calligraphy — matches as assumed |
| 16–31 | ✅ | all identical: solid, pressure-sensitive (16 = INK as assumed). Codes past 16 evidently alias/clamp to INK ⇒ **the real table ends at 16; no extended sweep needed** |

Consequences:
- **Phase 5 is GO**, with better-than-hoped materials: code 4 (dashes) for the live lasso
  outline, code 3 (x's) for the lasso-eraser trail — the exact two visuals the phase needs,
  and they're the firmware's own lasso vocabulary so they'll look native.
- Codes 1/3 rendering as pens (not erasers) with black confirms the eraser distinction lives
  in the full payload (colour 255), not the type code alone — `setEraser`'s colour-255 write
  is load-bearing.
- Code 12 is a landmine; exclude it from any future pen-tool offering.

---

## Phase 2 — Device gate + engine factory · ✅ DONE

**Goal:** one place decides the engine, and all six hosts use it. **Deliberately no behaviour
change** — this phase is a pure refactor that must leave BOOX and Generic devices byte-identical.
Doing it separately gives a clean regression checkpoint before any Ratta code exists.

### Build

- `core/Device.kt` — add:

  ```kotlin
  fun isRattaDevice(): Boolean =
      Build.MANUFACTURER.lowercase(Locale.ROOT).contains("supernote")
  ```

  **This is the measured string, not a guess** — Phase 0 confirmed `ro.product.manufacturer` is
  `Supernote` on both devices (`ro.product.brand` too, if a second check is ever wanted). Do **not**
  match on `"ratta"`: the company name appears nowhere in the build properties, and an earlier draft
  of this plan guessed it and would have returned false on both devices.

  Note the function keeps the `isRattaDevice` name for symmetry with `isBooxDevice` (both name the
  vendor, not the brand) — the *string* it matches is what had to change.

- `notebook/NotebookViewFactory.kt` — new:

  ```kotlin
  fun createNotebookView(context: Context): NotebookView = when {
      isBooxDevice()                    -> OnyxNotebookView(context)
      isRattaDevice() &&
          SupernoteInk.isAvailable()    -> RattaNotebookView(context)
      else                              -> GenericNotebookView(context)
  }
  ```

  Ordered so BOOX is decided first (cheap, no reflection) and the binder probe only runs on a Ratta
  device. In **this** phase `RattaNotebookView` does not exist yet — the Ratta branch is commented
  out with a `TODO(Phase 3)` and the factory returns Generic there, so the refactor stays a no-op.

  Log the chosen engine once at construction (`Log.i`, not `Slog.d` — one line per screen open is
  worth having in a release bug report).

- Replace all six construction sites with `drawingView = createNotebookView(this)` and drop the
  now-unused `isBooxDevice` / `OnyxNotebookView` / `GenericNotebookView` imports from each.

### Device test

Install on: **G102** (flagship, BOOX regression), **Nomad**, **Manta**.

- G102: open a notebook, the calendar day-note, the scratch pad, a sticky note, and HWR enrollment.
  Write on each. Everything must behave exactly as before — this is a "nothing changed" test.
- Nomad + Manta: same five surfaces, still on the Generic engine, still working.

### Exit criteria

- [x] No visible change anywhere on G102.
- [x] No visible change on Nomad/Manta.
- [x] `isRattaDevice()` returns true on both Supernotes (check the engine log line).

### Findings

**Built 2026-08-08, awaiting device test.** As planned, plus:
- The factory log line also reports the binder probe on Ratta devices, so Phase 2's log
  verifies the *whole* Phase 3 gate ahead of time:
  `NotebookViewFactory: engine=GenericNotebookView ratta=true firmware=true`
  (`isAvailable()` is cached + side-effect-free, and only called when `ratta=true`).
- `HwrEnrollmentActivity`'s KDoc no longer name-drops the two engine classes (imports gone).
- Installed on Nomad + Manta same day; **G102 was not attached** — its regression pass is
  pending until it's plugged in. Do not install on a substitute device.
- **Supernote half PASSED (2026-08-08, user-run):** all surfaces except HWR enrollment
  (user skipped it deliberately — they are considering dropping the enrollment feature and
  don't plan to use it on Supernote; do not chase this as a test gap). "Seems the same, as
  expected." Log pulled from both devices — every screen open reports
  `engine=GenericNotebookView ratta=true firmware=true`: the gate identifies the device and
  the binder probe passes, so the whole Phase 3 condition is proven live on both units.
- **G102 regression PASSED (2026-08-08, user-run):** every screen open logs
  `engine=OnyxNotebookView ratta=false` with zero `SupernoteInk` lines — the binder probe
  never runs on BOOX, exactly as the factory's ordering intends.

---

## Phase 3 — `RattaNotebookView`, core writing loop · ✅ DONE

**Goal:** first real ink in a real notebook, on the **deferred handoff** model. Pen tool only —
other tools are Phase 4.

> **This phase was rewritten after the PoC was found.** An earlier version specified baking and
> clearing the overlay on every pen-lift, with three candidate orderings to chase the resulting
> flash. That is the wrong model and the flash is what it produces. See *Prior art → Corrections*.
> `~/git/SupernoteDemo/app/.../DrawingView.kt` is the reference implementation — mirror it.

### Build

- `notebook/RattaNotebookView.kt` — **copy `GenericNotebookView.kt` verbatim**, rename the class,
  then make these edits (per Decision 1, `GenericNotebookView.kt` is not touched):

  1. **`firmware` flag** — `private val firmware by lazy { SupernoteInk.isAvailable() }`. Every
     firmware call sites off it, so the file still behaves exactly like Generic if the binder
     vanishes.

  2. **`ACTION_MOVE`** — keep collecting points and keep the dwell tracking
     (`checkAndDispatchGesture` needs it), but **drop the `invalidate()`**. The firmware paints the
     live trace. This is the latency fix.

  3. **`onDraw`** — delete the in-progress-stroke block (`activePoints.size >= 2`). Everything else
     stays: drag layer, committed `RenderNode` blit, shape-transform overlay, lasso overlay.

  4. **`commitActiveStroke()` — defer the bake.** Add the `LiveStroke` to `strokes` exactly as now,
     but **skip `redrawCanvas()`** and set a `pendingBake = true` flag instead. The stroke is in the
     model immediately, so saves, snapshots, erase hit-tests and the smart-lasso / scribble gates all
     stay correct — only the *visual* bake waits. Do **not** call `clearAll()` or any refresh here.

  5. **`releaseFirmwareOverlay()`** — the handoff, called only at boundaries (Phase 4 wires them all):
     ```kotlin
     private fun releaseFirmwareOverlay() {
         if (!firmware) return
         if (pendingBake) { pendingBake = false; redrawCanvas() }   // bake: re-records the node
         SupernoteInk.clearAll()                                    // wipe the firmware overlay
     }
     ```
     `redrawCanvas()` already re-records from the full `strokes` list and invalidates, so there is no
     separate replay list to maintain — this is where our `RenderNode` model is simpler than the
     PoC's bitmap.

  6. **`setupFirmwareInk()` / `teardownFirmwareInk()`** — mirror the PoC:
     ```kotlin
     claimPen(); enableFullUiAuto(context, true); enableAutoRegal(context, true)
     applyDisableAreas(); applyToolToFirmware()
     ```
     Called from `onAttachedToWindow` (after first layout) **and from `onWindowFocusChanged(true)`** —
     the view stays attached across a task switch, so attach alone is not enough. Teardown on focus
     loss and detach.

  7. **Pen configuration** — `setPen(Pen.NEEDLE, emrSize(strokeWidthPx), Color.BLACK)` where
     `emrSize(w) = (w * 100f).toInt().coerceIn(200, 1200)`. NEEDLE for uniform width; the EMR clamp
     is what makes the ink visible at all. Colour stays BLACK this phase — Phase 8 maps the palette.

  8. **Class header comment** — state that this file is a copy of `GenericNotebookView`, list the
     edits, and note the two are slated to collapse into a shared base (BACKLOG). A lasso fix must be
     applied to both files.

- `NotebookViewFactory.kt` — enable the Ratta branch.

**Minimum boundaries for this phase** (the full table is Phase 4): `releaseRender()`,
`onWindowFocusChanged(false)`, page load/`setTemplate`, and detach. Without at least these the
overlay outlives its page.

### Device test

Install both. Open a notebook, **pen tool only**:

1. Write several sentences — is the ink instant and smooth, matching the Phase 1 probe?
2. **Write ten strokes in a row without touching anything else — is there any flash or size/darkness
   jump between strokes?** There should be none; nothing is baking yet.
3. Now touch the toolbar. **One** clean handoff should occur: the firmware ink is replaced by the
   app's baked strokes. Look for a size shift, a darkness shift, or a ghost.
4. Write near all four edges and in the corners.
5. Fill a page (~30 strokes), flip pages, come back — every stroke present?
6. Draw a fast closed circle around some writing (smart lasso) and a scribble over some writing
   (scribble-erase) — both must still fire; they run off `activePoints`, not the overlay.
7. Close the notebook and reopen — everything persisted, and the cover thumbnail correct?
8. Task-switch away and back, then write — is ink still live? (This is the focus re-assert.)

### Exit criteria

- [x] Live ink is instant, and **there is no per-stroke flash, ghost or enlargement**
      (user round 1: "everything works really well"; round 7: "the writing experience is gold").
- [x] The handoff at the first boundary is a single clean transition with no visible size or darkness
      shift (minor width mismatch is Phase 8's tuning, but note it here).
- [x] Strokes persist across page flips and close/reopen; covers are correct.
- [x] Smart lasso and scribble-erase still fire — **and their traces now self-clear** (rounds 2–7).
- [x] Ink survives a task switch.
- [x] Identical on Nomad and Manta (the ladder absorbed the Nomad/Manta finalization-window variance).

### Findings

**Built + installed on both devices 2026-08-08; smoke-checked on the Nomad over adb**
(launch-restore reopened a notebook → `engine=RattaNotebookView ratta=true firmware=true`,
binder found, `enableFullUiAuto`/`enableAutoRegal` ok on attach *and* focus gain, crash buffer
clean). Awaiting the user's pen test. Build notes beyond the plan's edit list:

- **`redrawCanvas()` carries the handoff guard**: every re-record bakes the whole `strokes`
  list (pending included), so if `pendingBake` was set it flips it off and calls `clearAll()`
  right after recording. `releaseFirmwareOverlay()` is `if (pendingBake) redrawCanvas() else
  clearAll()` — equivalent to the plan's sketch, but any redraw triggered *outside* the
  release path (throttled erase redraws, drag-move commit) can never leave the same ink
  displayed by both layers.
- **Eraser contact is a Phase-3 boundary too**: `ACTION_DOWN` with `erasing=true` (eraser end,
  barrel button, or eraser tool) releases the overlay first, so software erase + redraw works
  on a fully-baked page. (The firmware natively wipes only *its own* overlay pixels under the
  eraser end — baked strokes need our hit-test, as Phase 0 recorded.)
- **The three gesture-consuming post-blocks release the overlay** (shape-dwell, smart lasso,
  scribble-erase): the gesture stroke is firmware ink and would otherwise linger after the
  stroke is removed from the model.
- **`clearForPageLoad()` is overridden** (Generic inherits the `eraseAll` fallback): release
  the overlay, then drop content *without* re-recording, so the outgoing page stays on the
  panel until `loadStrokesWithBitmap` swaps with a single refresh — no white double-flash.
- **No disable areas in this phase** — setup calls `clearDisableAreas()`; a pen touch on the
  toolbar may leave a transient mark that the toolbar's own `releaseRender()` immediately
  wipes. The real exclusion geometry is Phase 4, as planned.
- Failure toast: `SupernoteInk.onFailure` is bound on attach (one toast per view instance),
  unbound on detach.

**Device test round 1 (2026-08-08, user-run, both devices):** everything passed except the two
smart gestures — the pen path itself was "works really well". ⭐ **New firmware fact: a
`clearAll()` issued in the immediate wake of a pen-lift is unreliable** — the trace can survive
it. Evidence: the smart-lasso circle (one immediate clear, ms after lift) *always* lingered
until the next toolbar tap re-cleared; the scribble (which gets a second clear ~100 ms later
from the host's erase rebuild) lingered only *sometimes*; a toolbar-tap clear (human-scale
later) always works, as did the Phase 1 probe's CLEAR button. This is the same hardware
behaviour behind the PoC's per-lift "ghost" artefact. ~~First fix attempt: a delayed follow-up `clearAll` at 400 ms~~ — **FAILED on both devices
(round 2), and the failure sharpened the model**: two bare `clearAll`s (immediate + 400 ms)
both leave the trace standing, yet the *identical call* from a later toolbar tap always works.
So it is not timing. ⭐ **Corrected firmware fact: overlay ink only leaves the panel where the
app repaints those pixels — `clearAll` clears the buffer but a pixel the app never redraws
keeps its stale overlay ink.** Every working clear co-occurs with a repaint of the inked
pixels (our normal handoff bakes the very strokes the overlay showed; KOReader bakes each
stroke into its framebuffer before clearing; the PoC's clears all accompany a full-area
repaint; the round-1 scribble "sometimes cleared" exactly when the host's erase rebuild
repainted overlapping pixels). A gesture-consumed stroke is the one case where overlay ink
corresponds to NOTHING in the app layer.

~~Second fix attempt: the gesture-trace ghost (bake the consumed stroke black, then unbake
to force a black→white push at exactly the trace pixels)~~ — **FAILED on both devices
(round 3), no visible change at all.** That negative is itself a measurement: ⭐ **the
overlay composites ABOVE the framebuffer — no app-side repaint of those pixels can ever
remove painted overlay ink.** (The user's read, consistent with all rounds: `clearAll`
empties the buffer but the panel is never reconciled; the trace is a stale EPDC layer that
only firmware-side activity drops. Page turns / tool toggles / toolbar taps clear it as a
side effect of whatever EPDC reconcile they trigger.) The repaint-invariant theory of round
3 is dead; the ghost machinery was removed again.

~~Third fix attempt: `screenRefresh(full=false, 0)` after the clear~~ — **FAILED (round 4,
both devices): it full-screen-flickers ("definitely not good"), and reconciles
inconsistently** (Manta's lasso trace cleared, Nomad's did not; the scribble trace stood on
both — a race between the async buffer clear and the refresh). Round 4 also produced a new
artefact that reveals overlay semantics: **pixels under overlay ink are frozen** — the
scribble host's rebuild repainted the erased strokes to white everywhere EXCEPT inside the
scribble's own trace, where the stale stroke pixels survived. The refresh was reverted; the
engine's `releaseGestureTrace()` is back to bake + clear + guarded 450 ms follow-up.

**Round 5: the probe's delayed clear-matrix (user-run on the Nomad) — the decisive
measurement.** Eight sequences, each armed by a tap but fired 2 s later hands-off:

| Sequence | Result |
|---|---|
| `dCLR` (bare clearAll) | nothing |
| **`dCLR+INV` (clearAll + invalidate of the view over the ink)** | ⭐ **cleared, no flicker** |
| `dCLR+PEN` (clear + claim + setPen) | nothing |
| `dCLR+DIS` / `dDIS` (full-screen-disable round-trips) | nothing |
| `dCLR+UI` (fullUiAuto off/on) | nothing |
| `dCLR+RF1` (screenRefresh mode 1) | cleared, but full-screen flicker |
| `dCLR+RF2` (screenRefresh mode 2) | nothing |

Finger touches never flushed a stale clear (the input theory is dead — and separately,
⭐ **while the stylus is in EMR range the system suppresses finger taps entirely**, the same
palm-rejection-above-the-driver behaviour the G102 has; recorded for future gesture
debugging). Follow-up hover test: `dCLR+INV` **clears even with the pen hovering over the
ink**, so hover is irrelevant to the wipe.

⭐ **The two measured laws of the overlay wipe:** (1) `clearAll` only reaches the panel when
an **app frame is presented in the same breath** — a bare clear reconciles nothing, ever;
(2) a clear issued in the **immediate wake of a pen-lift is eaten** by the daemon's
stroke-finalization window (~150–200 ms — the scribble host's ~100–200 ms rebuild, which
does pair clear+frame, cleared only *sometimes*; the probe's 2 s sequences always worked).
Every prior round split the pair: the immediate attempt paired them but fired inside the
window; every delayed attempt sent `clearAll` without a frame (or, round 3, a frame without
a clear).

~~Fix round 6: one `clearAll()` + `invalidate()` pair at 450 ms~~ — **close but not
pruned**: Manta failed at first then became reliable every time; Nomad still failing. The
finalization window's length evidently varies by device and moment, and a single attempt
sits on its edge (the probe's 2 s pair always worked — on the Nomad itself).

**Fix (round 7): retry ladder + contact flush — ✅ PASSED on both devices (2026-08-08).**
The armed clear fires `clearAll` + `invalidate` pairs at **450 ms → 1 s → 1.9 s**
(idempotent, invisible once cleared, no refresh calls) plus an immediate attempt at the
**next EMR pen-down** — the one moment measured working in every round (toolbar tap,
tap-to-dismiss), which also covers writing again quickly. Guards: mid-stroke → re-post; new
ink pending → self-disarm (next natural boundary owns the overlay). **Phases 4–8 must
inherit both laws and the ladder pattern.**

**User verdict:** the gesture-trace clear "reads as a little sluggish" (up to ~2 s worst
case by design) but is **accepted as good enough for Supernote** — "the writing experience
is gold. So, these little nuances are okay." Do not chase further latency here without
being asked; the trace lag is a measured firmware constraint, not an app defect.

---

## Phase 4 — Handoff boundaries & mode transitions · ✅ COMPLETE (2026-08-09, both devices)

**Goal:** wire every boundary to `releaseFirmwareOverlay()`, and make every non-writing mode stop the
firmware painting. **This is where the "no flash, no ghost" behaviour actually comes from** — Phase 3
builds the mechanism, this phase decides when it fires.

### Build — all in `RattaNotebookView.kt`

**Handoff boundaries** — each calls `releaseFirmwareOverlay()` (bake + `clearAll()`):

| Trigger | Notes |
|---|---|
| Tool change (pen ↔ eraser ↔ lasso ↔ lasso-eraser ↔ text ↔ shape-transform) | release **first**, then reconfigure the firmware for the new tool |
| `releaseRender()` — fires on **every** toolbar touch, from all six hosts | the Ratta analogue of Onyx releasing its overlay so toolbar state is visible |
| `releaseForHandoff()` — before launching another drawing screen | also `enableFullUiAuto(false)` |
| `resetOverlay()` | release, then re-arm |
| `onWindowFocusChanged(false)` | release + `enableFullUiAuto(false)` + drop the pen claim |
| Snapshot capture | release **first** so the panel matches the snapshot (the snapshot itself renders from `strokes`, so its data is already correct) |
| Page nav, `clearForPageLoad()`, `loadStrokesWithBitmap()`, `setTemplate()` | release **before** the content swap, or old-page ink survives onto the new page |
| `eraseAll()` / clear | clear the model + `clearAll()`; `sendOneFullFrame()` is acceptable here — a flash is expected on a clear |

**Per-tool firmware state:**

| Mode | Firmware action |
|---|---|
| Pen | `claimPen()` + `setPen(NEEDLE, emrSize(w), BLACK)` |
| Eraser | **`setEraser(false, eraserEmr())`** where `eraserEmr() = (eraserRadius * 50).coerceAtLeast(400)`. This stops the firmware painting NEEDLE ink along the eraser path; our software hit-test still does the actual removal. *(An earlier draft said to suppress the pen instead — the PoC uses `setEraser`, and it is proven.)* |
| Lasso / lasso-eraser | ~~`setFullScreenDisable(w, h)`~~ **Superseded by Phase 5**: `setPen(LASSO_DASH/LASSO_X, 300, BLACK)` — the firmware paints the live trail; toolbar disable areas only. |
| Text placement | `setFullScreenDisable(w, h)` so the placement tap starts no ink |
| Shape transform | `setFullScreenDisable(w, h)` — handles and the rotate knob are Canvas-drawn |
| Drag-move (inside lasso) | **Phase 5**: `setFullScreenDisable(w, h)` issued from the HOVER stream while over the selection box (a disable at ACTION_DOWN is too late — the contact-start latch) |
| Leaving any of the above | `clearDisableAreas()` then re-apply the toolbar areas |

**`setToolbarExclusion(rect)`** — Generic no-ops this; Ratta must implement it. ⚠️ **Our geometry
differs from the PoC's.** The PoC's `applyDisableAreas()` assumes the toolbar sits *above* the view
and disables `Rect(0, 0, screenW, top)` from `getLocationOnScreen`. In Notesprout the toolbar
**overlays** the drawing view inside a `FrameLayout` — same origin and size — so the incoming rect is
in *view* coordinates and must be offset by `getLocationOnScreen` into *screen* coordinates before
being sent. Null/empty ⇒ `clearDisableAreas()`.

The pen-colour panel protects itself through the same call
(`penColorPanel.panelRectIn(drawingView.asView())` — `ScratchpadActivity.kt:2158` and siblings), so
one correct conversion covers both.

**Do not** call `screenRefresh()` or `sendOneFullFrame()` per stroke or per handoff — both flash.
`enableAutoRegal(true)` at setup is what keeps handoffs clean.

### Device test

Install both. In a notebook, for **each** of pen / eraser / lasso / lasso-eraser / insert-text /
shape-transform:

1. Switch to the tool, use it, switch away — a single clean handoff each, no stale overlay ink.
2. Eraser: erase strokes — **no ink painted along the eraser path**, strokes removed cleanly.
3. Lasso: drag a selection — dashed box clean, no firmware ink over it.
4. Write with the pen across the toolbar — ink stops at the toolbar edge.
5. Open the pen-colour panel and drag the stylus over it — no ink on the panel.
6. Open the overflow menu, a text-edit dialog, the page-index screen — no stray ink anywhere.
7. Write, then touch the toolbar repeatedly — one handoff, then nothing further to bake.

### Exit criteria

- [x] No firmware ink over the toolbar, colour panel, any dialog or menu.
- [x] Eraser leaves no painted trail.
- [x] Every tool switch is one clean handoff with no stale overlay ink.
- [x] Lasso / text / shape overlays render clean.
- [x] Returning to the pen restores instant firmware ink.
- [x] Identical on Nomad and Manta (Manta: full script + barrel in one pass, "all good").
- [x] *(added this phase)* Barrel-button erase: no x-stream/ink along the path, sticky
      erase through early button release, no phantom strokes, self-clearing ghosts.

### Findings

**Built + installed on both devices 2026-08-09; smoke-checked on the Nomad over adb**
(`engine=RattaNotebookView ratta=true firmware=true`, binder found, setup ok on attach and
focus gain, crash buffer clean). Awaiting the user's device test. Build notes beyond the
plan's table:

- **One shared boundary helper**: `firmwareToolBoundary()` = `releaseFirmwareOverlay()` then
  `applyToolToFirmware()` — called from `setEraserMode`, `setLassoMode`, `setLassoEraserMode`,
  `setTextPlacementMode`, `enterShapeTransform`, `exitShapeTransform`. `applyToolToFirmware()`
  reads the mode flags: any Canvas-overlay mode (lasso / lasso-eraser / text placement /
  shape transform, incl. drag-move which lives inside lasso mode) → `setFullScreenDisable`;
  eraser tool → `setEraser(false, eraserEmr())`; else disable areas + `setPen(NEEDLE…)`.
- **Text placement's internal exit** (`handleTextPlacementTouch` UP) re-applies the tool
  itself — the host's later `setTextPlacementMode(false)` is not the only path out.
- **"Drop the pen claim" on focus loss has no firmware transaction** — implemented as
  `setFullScreenDisable` in `teardownFirmwareInk()` (and on detach, replacing the Phase 3
  `clearDisableAreas`), so an unfocused/dying view can never have ink painted on its
  behalf. Focus gain re-runs `setupFirmwareInk` → `applyToolToFirmware`.
- **`setToolbarExclusion` applies live** (view rect + `getLocationOnScreen` offset →
  `setDisableAreas`) unless a full-screen-disable mode owns the areas — leaving that mode
  re-applies through `applyToolToFirmware`. Hosts already push updated rects for the open
  overflow menu / shape toolbar / colour panel, so those are covered by the same call.
- **`captureSnapshot()` releases first**, per the table; `resetOverlay()` = release +
  full `setupFirmwareInk()`; `releaseForHandoff()` = release + `enableFullUiAuto(false)`.
- ~~Known edge, deliberately unhandled: barrel-button erase~~ — the user has EMR pens with
  barrel buttons; handled in round 2 (below).

**Device test round 1 (2026-08-09, user-run, Nomad only):** everything in the test list works
**except**: drawing in the overflow menu's blank (button-free) areas painted nothing — correct —
but the strokes **appeared on the page later** (after a page flip round-trip). Cause: the
firmware disable area stops the *painting*, but the overflow's blank areas don't consume
touches, so the stylus events fell through to `onTouchEvent` and the points entered the model
invisibly, surfacing at the next bake. Manta deliberately untested until the fixes land.

**Round 2 fixes (2026-08-09, built + installed on both, Nomad relaunch smoke-checked clean):**

- **Model-side exclusion filter** — `appendStrokePoints()` now feeds every pen point through
  the toolbar exclusion rect (view coords, `firmware` only): points inside are dropped, and
  a stroke that crosses the zone is **split into separate segments** at the boundary —
  the model now matches exactly what the firmware painted. A gesture that never left the
  zone commits nothing, and `gestureHadInk` gates `checkAndDispatchGesture` so the gesture
  gates can't re-examine a stale earlier stroke (Generic always commits ≥1 stroke per lift;
  this engine no longer does).
- ~~Barrel-button erase support in the engine~~ — **pulled back out of `RattaNotebookView`
  (user decision): work it out in the lab first.** The engine keeps only the pre-existing
  behaviour (a button-held contact soft-erases via the `erasing` check; the firmware may
  paint along the path until the next boundary). The candidate design — mirror the barrel
  into `setEraser`/`setPen`, tracked from hover so the firmware re-arms before the tip
  lands — now lives in **`SupernoteProbeActivity`'s barrel lab** behind its MIRROR toggle,
  and returns to the engine once proven.

**Round 3 — barrel-button lab + OS-preference read (2026-08-09, installed on both):**

- `SupernoteProbeActivity` gained a **barrel line** (live decoded `buttonState` from BOTH
  streams — hover generic-motion and contact touch — with a press/release transition
  counter Δ and `Log.i` records), and a **MIRROR button** replicating the engine candidate
  (barrel held → `setEraser(false, 750)`, released → `setPen`). "Barrel pressed" accepts
  `BUTTON_STYLUS_PRIMARY` **or** the pre-M `BUTTON_SECONDARY` mapping.
- ⭐ **The OS side-button preference is app-readable**: `Settings.System`
  `end_button_behavior` (=2 on the Nomad right now), verified rendering in the probe's
  report on-device. A pattern scan of all three settings tables (`pen|stylus|button|lamy|
  eraser`) surfaced no other stylus keys — this is the one preference. The report re-reads
  on every focus gain, so flip the OS setting → return → the line updates.
- Kernel level (adb `getevent -pl`): the Wacom digitizer exposes `BTN_STYLUS` +
  `BTN_STYLUS2`, so the button reaches the input stack; the open question is only whether
  the framework forwards it to apps under each `end_button_behavior` value.
- **Lab results (user-run on the Nomad, button pen, `end_button_behavior=2`):**
  1. ⭐ **The OS delivers the side button to third-party apps, from hover**: `32[S1]`
     (`BUTTON_STYLUS_PRIMARY`) while held, `0` released, Δ ticking on both edges.
  2. ⭐ **The firmware natively reacts to the held button by painting its lasso-erase
     x-stream trace (the code-3 visual) along the pen path, IGNORING the app's pen
     config** — identical with MIRROR off and on, so `setEraser` mirroring is useless
     against the button. The trace **lingers** like any overlay ink.
  3. ⭐ **Disable areas DO suppress the button trace** (no x-stream inside the STRIP band)
     — the suppression lever the pen config isn't.

**Round 4 — barrel support shipped to the engine (2026-08-09, built + installed on both,
Nomad smoke-checked clean):** `updateBarrelSuppress()` in `RattaNotebookView`: button
pressed (tracked on hover, generic-motion AND contact streams; accepts `S1` or the pre-M
`BUTTON_SECONDARY`) → `setFullScreenDisable` so the firmware paints nothing; released →
`applyToolToFirmware()` (which also resets the flag, so any tool push supersedes the
transient disable and the next button event re-asserts it). The software erase does the
actual removal and shows progress through its own redraws. Safety net: an erase contact
that involved the barrel — or converted to erasing mid-stroke, abandoning a partial pen
stroke on the overlay — arms `releaseGestureTrace()`'s clear ladder on lift (idempotent
when the overlay is already clean). MIRROR stays in the probe as the historical negative.

**Round 5 — two barrel fixes from the instrumented run (2026-08-09, built + installed on
both).** The round-4 build's user test: barrel-erase painted nothing ✓ and erased the
crossed stroke from the DB ✓, but the erased stroke stayed visible until a page flip and
the erase path *reappeared as a normal stroke*. The engine's Slog trace (now permanent —
barrel PRESS/RELEASE with source stream, penDOWN/penUP with buttonState + flags, commits,
erase removals, gesture verdicts) pinned both:

- ⭐ **The barrel is released a beat before the pen lifts** (`ACTION_BUTTON_RELEASE`
  arrives mid-contact, then `penUP btn=0`): per-event `erasing` recomputation sent the UP
  down the normal-pen branch, committing the stale down/up points as a phantom 2-point
  stroke. Fix: **the erase decision is sticky per contact** — `erasing` now also includes
  `strokeSawBarrel`, so a contact that ever saw the button stays an erase until lift.
- ⭐ **The pen-down bake+clear can be eaten too** (not just near-lift clears): the erased
  stroke's overlay twin stayed frozen on the panel through the whole erase, hiding the
  app-side repaint until page nav reconciled it. Fix: **every erase contact now ends by
  arming `releaseGestureTrace()`'s ladder** (was: only barrel/mid-stroke-conversion
  contacts) — idempotent and invisible when the panel is already clean.

**Round 5 validated (user, Nomad, 2026-08-09): "looks good now"** — and the log confirms
the same button-release-before-lift sequence now takes the erase branch on penUP
(`erasing=true sawBarrel=true`, no phantom commit, `erase removed=1`). Barrel-erase is
done on the Nomad. Remaining before ticking Phase 4: the full test script on the
**Manta**, and optionally the OS-setting enumeration below.

**Phase closed 2026-08-09 — Manta full pass ("all good").** Closing bonus, from a
screencap of both devices: baked strokes are screencap-visible **in colour** (a green
stroke drawn with the v1.2 colour system round-tripped to the committed layer as true
green on both devices) — the deferred handoff bakes exactly as designed, and Phase 8's
remaining work is only the live overlay's grey mapping.

**Deferred lab item (not phase-blocking):** enumerate the OS side-button setting's UI
options ↔ `end_button_behavior` values (flip each, re-run the probe's hover-Δ test) — if
some value makes the OS swallow the button, barrel-erase goes inert for that user (no
misbehaviour beyond the firmware possibly painting its native trace with no app-side
suppression); the value is app-readable at runtime if it ever warrants a hint.

---

## Phase 5 — Firmware dashed ink for the live lasso path · ✅ COMPLETE (2026-08-09, both devices)

> **Gated on Phase 0/1's pen-type sweep.** If no firmware pen code renders a dashed or patterned
> stroke, mark this phase ⛔ BLOCKED, leave Phase 4's Canvas path in place, and move on. Everything
> downstream is independent of it.

**Goal:** the lasso outline should be *drawn by the stylus in hardware*, at pen speed, the same way
ink is — not chased by a software Canvas path at e-ink redraw rates.

Today every engine draws the lasso with `lassoPaint` — a Canvas `Paint` with
`DashPathEffect(floatArrayOf(12f, 8f), 0f)` — refreshed at most every `LASSO_REFRESH_INTERVAL_MS`
(60 ms). That throttle *is* the look the user dislikes: the dashed line visibly trails the pen.
The Onyx SDK has a real hardware dashed stroke (`STROKE_STYLE_DASH = 5`, confirmed rendering on all
five BOOX devices by `PenToolSpikeActivity` — see `docs/onyx-pen-tools.md`) that we have never
leveraged. **Doing it on Supernote from day one is the point of this phase**; the Onyx equivalent is
filed to `BACKLOG.md` in Phase 9.

### Scope — what can and cannot move to the firmware

The firmware only paints **where the pen is touching**. So:

| Lasso visual | Drawn by | Can it be firmware ink? |
|---|---|---|
| Live outline while the stylus is down (`lassoOverlayPath`) | stylus contact | ✅ **yes — this phase** |
| Lasso-eraser's jittered grey trail (`lassoEraserDisplayPath`) | stylus contact | ✅ **yes — this phase** |
| Dashed selection box after lift (`lassoSelectionBox`) | no contact | ❌ stays Canvas |
| Drag-move preview (backing bitmap + translated objects + box) | contact, but it's a *drag*, not a trace | ❌ stays Canvas |
| Snap guides | no contact | ❌ stays Canvas |

So this is precisely the thing the user called out — the line being drawn under the stylus — and
nothing more.

### Build — `RattaNotebookView.kt`

1. **Lasso mode no longer full-screen-disables.** Replace Phase 4's blanket suppression for
   `setLassoMode(true)` / `setLassoEraserMode(true)` with: arm the firmware pen at the dashed code
   found in Phase 1 (lasso-eraser: whichever code best matches its grey trail), keep disable areas on
   the toolbar only.
2. **`handleLassoTouch` / `handleLassoEraserTouch` `ACTION_MOVE`** — keep building `lassoGesturePath`
   (the hit test needs the real geometry), but stop assigning `lassoOverlayPath` /
   `lassoEraserDisplayPath` and drop the throttled `invalidate()`. The firmware paints the trace.
   The `LASSO_REFRESH_INTERVAL_MS` throttle becomes dead code on this path — that is the win.
3. **`ACTION_UP`** — `clearAll()` to wipe the firmware trace, then the existing flow runs unchanged:
   hit test → `onLassoComplete` → the host calls `setLassoOverlay(null, box)` → Canvas draws the
   selection box. Same sequencing risk as the Phase 3 pen-lift handoff; reuse whichever of (a)/(b)/(c)
   won there.
4. **Drag-move** is entered from `ACTION_DOWN` inside an existing selection box. That branch must
   suppress firmware ink immediately (`setFullScreenDisable`) — a drag must not leave a dashed trail
   — and restore on `ACTION_UP`.
5. **`setLassoOverlay` / `setLassoSelectedIds`** — no firmware involvement; they only set the box.

### Device test

Install both. In a notebook:
1. Lasso some writing — **does the dashed outline keep up with the pen?** Compare against the same
   gesture on the G102 (the current software look) if one is to hand.
2. Lasso quickly and in a tight circle — does the trace break up or lag?
3. On lift — does the trace vanish cleanly and the dashed selection box appear in its place, with no
   double image?
4. Drag the selection — no dashed trail follows the pen; the box and objects move as before.
5. Tap outside to dismiss; lasso again — no stale trace.
6. Smart lasso (fast closed circle in **pen** mode) still selects — it must not have picked up the
   dashed pen.
7. Lasso-eraser: draw across some strokes — trail keeps up, strokes erase, no residue.
8. Lasso, then switch tools mid-gesture via the toolbar — nothing left painted.

### Exit criteria

- [x] The live lasso outline is drawn in hardware and keeps pace with the stylus.
- [x] Lift → box handoff is clean, no double image, no residue.
- [x] Drag-move leaves no trail (after the hover-ahead fix below).
- [x] Smart lasso and scribble-erase (pen mode) are unaffected.
- [x] Lasso-eraser trail likewise.
- [x] Identical on Nomad and Manta.

### Findings (2026-08-09, both devices — everything but drag-move passed first round)

**Pen codes as planned:** lasso trail = `Pen.LASSO_DASH` (4), lasso-eraser trail =
`Pen.LASSO_X` (3), both at **EMR 300** (the sweep's measured-visible size, kept as
`LASSO_TRAIL_EMR` independent of the ink pen's width mapping) with a **BLACK** payload
(paints, never erases — eraser semantics are the colour-255 payload). Both trails look
native (they *are* the firmware's own lasso vocabulary) and keep pace with the pen; the
60 ms Canvas throttle is dead code on the firmware path. Lift-wipe reuses the Phase 3
`releaseGestureTrace` ladder unchanged — a trail is exactly a gesture trace (overlay ink
corresponding to nothing app-side).

⭐ **The third overlay LAW — the firmware latches pen state at contact start.** A
full-screen disable issued at the drag-move's `ACTION_DOWN` was too late: the stroke had
already begun and the whole drag painted a dashed trail (both devices, round 1). A
suppress can only take effect if it is in place **before the tip lands**, and the hover
stream is the early warning — this is also, in hindsight, exactly why Phase 4's barrel
suppress worked (the button reports on HOVER). Fix: `updateLassoDragHoverSuppress` —
while the stylus hovers over the selection box, full-screen disable; hover out re-arms
the trail pen; every tool push resets the flag and the next hover event re-asserts.
The `ACTION_DOWN` disable stays as a backstop for a contact with no hover warning.
**Every later phase that needs to stop the firmware painting for a given contact must
arrange the disable from hover, not from the touch stream.**

**Off-script bonus (user-requested): the physical eraser end folded into the barrel
suppress.** Its native firmware handling pixel-wipes the panel along the path — visible
as a partial pixel-level erase flashing across strokes before the software stroke-level
erase repaints. `updateBarrelSuppress` now treats `TOOL_TYPE_ERASER` in hover range
exactly like a held barrel button (hover-ahead full-screen disable; the native wipe
respects disable areas, device-confirmed). Flip back to the pen tip re-arms the tool.
Erase now removes whole strokes with no pixel-wipe artifact; validated on both devices,
including eraser-end use inside lasso mode and rapid flip-erase-flip-write.

Also fixed en route: `setLassoMode(false)` now cancels a live drag **before** the tool
boundary (the old order let `applyToolToFirmware` push a stale full-screen disable), and
barrel/eraser-end contacts in Canvas-overlay modes arm the clear ladder on lift.

---

## Phase 6 — Lifecycle, process-global state, close · ✅ COMPLETE (2026-08-09, both devices)

**Goal:** the parts of the lifecycle Phase 4's boundary table doesn't cover — re-claiming across
screens, and the fact that firmware ink state is **process-global**, exactly like Onyx's
`TouchHelper` pipeline.

Phase 4 already wired the boundaries; do not duplicate them here. What is left:

### Build — `RattaNotebookView.kt`

- **Re-claim discipline.** `setupFirmwareInk()` must run from `onAttachedToWindow` (after first
  layout) **and** `onWindowFocusChanged(true)` **and** `resumeDrawing()` (every host calls the latter
  from `onResume`). The firmware hands the pen back to other apps while we are away and resets
  full-UI ink, so a partial re-enable is not enough — re-assert the whole setup. Onyx treats focus as
  unreliable on e-ink and leans on `resumeDrawing()`; expect the same and wire both.
- **`enableDrawing()` / `disableDrawing()`** — disable = `setFullScreenDisable`; enable = restore
  disable areas + `applyToolToFirmware()`.
- **Process-global state.** Full-UI ink and the pen claim are system-wide, not per-view. When screen
  A launches drawing screen B, A must relinquish (`releaseForHandoff()` → release overlay +
  `enableFullUiAuto(false)`) before B claims. Unlike Onyx there is no single-owner *pipeline* to
  protect, so no `penOwner` guard is needed — but the **panel overlay** is shared state and must be
  handed over clean. Verify a late `onDetachedFromWindow` from the outgoing screen cannot wipe the
  incoming screen's overlay; if it can, an ownership guard mirroring Onyx's becomes necessary.
- **`releaseResources()`** — `releaseFirmwareOverlay()` + `clearDisableAreas()` +
  `enableFullUiAuto(false)`, then the inherited `RenderNode` / bitmap teardown.

### Device test

Install both. In a notebook:

1. Write, flip to the next page — is it clean? Flip back — intact?
2. Write, then flip immediately, before touching anything (the flip is the first boundary).
3. Write, press home, return, write again.
4. Write, lock the screen, unlock, write again.
5. Write, open the scratch pad from the toolbar, come back, write again.
6. Write, open a sticky note, come back, write again.
7. Notebook A → notebook B → back to A, writing on each.
8. Write, close the notebook — library cover includes the last stroke?
9. Insert a page, delete a page, change the page template.
10. Two-finger swipe down to Today, then back.

### Exit criteria

- [x] No overlay ink ever survives a page change or a screen change.
- [x] Ink is live again after every background/foreground and screen-to-screen round trip above.
- [x] Covers and snapshots include the final stroke.
- [x] Leaving the app entirely restores normal system ink behaviour (nothing left claimed).
- [x] Identical on Nomad and Manta.

### Findings

**Device-validated 2026-08-09 — the full ten-scenario script passed on both devices, first
round.** The full-screen-disable-at-final-teardown choice (below) stood: leaving the app left
the system's own ink behaviour normal, so the daemon does reset per-claim state — no flip to
`clearDisableAreas` needed. What was decided at build time:

- **The ownership guard IS necessary — settled statically, no device round needed.** Android runs
  the incoming screen's `onResume` (→ `setupFirmwareInk`) **before** the outgoing screen's
  `onDestroy`/`onDetachedFromWindow`, and the Phase 4 detach path unconditionally did
  `clearAll` + full-screen disable + `enableFullUiAuto(false)` — which would land right on top of
  the successor's freshly-claimed session. Added `inkOwner` (static, mirroring Onyx's `penOwner`):
  `setupFirmwareInk` claims it; every process-global teardown (`teardownFirmwareInk`, detach,
  `releaseForHandoff`, `releaseResources`) and the **gesture-trace clear ladder** (a ladder armed
  by a lasso lift must not fire `clearAll` into a successor's session after fast navigation) checks
  `inkOwner === this` first. Detach/`releaseResources` null it when they run as owner (also frees
  the static view ref — no Activity leak).
- **Re-claim discipline:** setup now runs from attach **post-layout** (deferred to `onSizeChanged`
  when width/height are still 0 — a 0×0 full-screen disable is an empty rect and
  `getLocationOnScreen` is garbage before layout), from focus gain (unchanged), and from the new
  `resumeDrawing()` override (every host's onResume — the focus-independent reclaim, and the path
  that flips `inkOwner` back after a translucent overlay host, since our onResume precedes its
  onDestroy). `onSizeChanged` also re-asserts on every resize so disable-area screen offsets track
  layout.
- **`enableDrawing`/`disableDrawing`** (were no-ops): enable = `applyToolToFirmware()` (restores
  disable areas + armed tool); disable = **bake first** (`releaseFirmwareOverlay` — a same-window
  view switch like the day window's Note→Events crosses no focus boundary, so pending overlay ink
  would float above the new view) then full-screen disable. Both ownership-gated.
- **`releaseResources`** (host onDestroy): when still owner, full firmware release — bake+clear,
  then **full-screen disable, not the plan's `clearDisableAreas`** (kept Phase 4's detach
  rationale: nothing may paint stray ink in the app's non-drawing screens; the daemon is expected
  to reset per-claim state when another app claims the pen). ⚠️ Watch in test: after leaving the
  app entirely, the Supernote's own notes app must still ink — if it doesn't, the lingering
  disable area is global, flip this to `clearDisableAreas`.

---

## Phase 7 — The remaining five drawing hosts · ✅ DONE (2026-08-09, both devices, round 1)

**Goal:** extend the proven path to calendar, day-note, scratch pad, sticky editor, and HWR
enrollment. The factory already routes them (Phase 2); this phase is about the host-specific quirks.

**The two translucent hosts are the risk.** `ScratchpadActivity` and `StickyNoteEditorActivity` are
overlay windows over the notebook. A firmware overlay that paints in screen space knows nothing
about our window stack, so ink may land in the wrong place or bleed under the host. Expect real work
here; it is why they got their own phase.

### Build

Per host: confirm the toolbar/chrome rect reaches `setToolbarExclusion` in **screen** coords (each
host computes it differently), confirm `releaseRender()` and `resumeDrawing()` are wired, and handle
any window-offset correction the translucent hosts need.

### Device test

Install both. On each of the four surfaces (HWR enrollment descoped — user is staying on ML Kit and
considering removing the custom engine): write, switch tools, use lasso, use the eraser, leave and
return, and (scratch pad / sticky note) transfer content back to the page.

Additionally for the calendar: Month, Week and Day views, plus the full-view export.

**Band-specific checks (the Phase 7 fix is disable-area geometry):** write right up to every edge of
each canvas — ink must stop exactly at the canvas edge; pen taps on the toolbar ABOVE the calendar /
day-note canvas must leave no firmware streaks; on the translucent hosts nothing may paint on the
chrome bar, the bottom toolbar, or the notebook visible around the 75% window.

### Exit criteria

- [x] Firmware ink works on all four in-scope hosts, positioned correctly.
- [x] No ink bleeds outside the translucent hosts' windows.
- [x] Content transfer (scratch pad → page, sticky → page) is unaffected.
- [x] Identical on Nomad and Manta.

### Findings

**Build (2026-08-09, awaiting device test).** The host audit came back clean — all five hosts already
make every interface call the notebook host does (`resumeDrawing` from onResume, `releaseResources`
from onDestroy, `releaseRender` on chrome touches, overflow-menu + pen-colour-panel exclusion rects
in view coords, and the day window's `resumeDrawing`/`disableDrawing` view switch that Phase 6
designed for). **Zero host-side changes.** The whole phase landed in `RattaNotebookView`, which had
two full-screen-host assumptions baked in that only `NotebookActivity` satisfies:

1. **"Disable everywhere" was `setFullScreenDisable(width, height)` — view dims at screen origin.**
   The day-window canvas sits below a toolbar (`Rect(0,0,w,h)` missed the bottom strip of the panel);
   the translucent hosts inset the view further. Fix: `fullScreenDisable()` helper using the real
   panel size (`Display.getRealSize`, cached at attach/onSizeChanged because detach-time teardowns
   can no longer reach the display), replacing all eight call sites (teardown, detach,
   suppressed-mode push, barrel suppress, lasso-drag hover suppress, drag DOWN backstop,
   `disableDrawing`, `releaseResources`).
2. **With no toolbar exclusion pushed, `applyDisableAreas` cleared ALL disable areas** — the firmware
   (which paints in screen space, knowing nothing of view bounds or the window stack) could ink over
   the calendar/day-window toolbar, the translucent hosts' chrome, and the notebook visible around
   the inset window. Fix: `applyDisableAreas` now always sends **complement bands** — up to four
   rects covering everything outside the view's screen rect — plus the host's overlay exclusion.
   On the full-bleed notebook host all four bands are empty, so Phases 3–6 behaviour is bit-identical.

**~~Watch item~~ resolved by the round:** the disable-area transaction now carries up to five rects
where every prior phase sent at most one, and edge containment passed everywhere — **the firmware
accepts at least five rects per `TX_DISABLE_AREA` transaction.**

**Device round 1 (2026-08-09, both devices): tests pass.** One observation from the round —
**baked strokes land slightly LEFT of the live firmware ink**, on every writing canvas, visible at
each bake. **A/B-verified pre-existing, NOT a Phase 7 regression:** the Phase 6 build (c6e2feb,
where the notebook host's firmware traffic is bit-identical) shows the identical shift on the
notebook canvas. So it dates to Phase 3 and went unnoticed until this round's edge-precision
scrutiny. Root cause: a small horizontal registration offset between where the firmware paints its
live ink (raw pen stream) and where the digitizer's `MotionEvent`s land (what we bake and persist).
**Filed into Phase 8**, which is the live-vs-baked appearance-matching phase — see the registration
item added there.

---

## Phase 8 — Ink mapping & EMR stroke-width tuning · ✅ DONE (2026-08-09, both devices)

**Goal:** make the live firmware ink *look like* the ink we bake. Until now Phase 3 has hardcoded
black at a default width.

**This is the problem the old plan missed entirely.** v1.2 shipped colour ink: the palette offers 16
greys **and** 16 colours (`core/InkColor.kt`, `notebook/PenPalette.kt`), and the armed colour is
pushed to the engine via `setPenColor(hex)` from `PenColorPreferences`. The Supernote firmware pen
accepts **four** colour codes — `BLACK 0`, `DARK_GRAY -101`, `GRAY -102`, `LIGHT_GRAY 254` — on a
greyscale panel. So:

- The **baked stroke keeps its true stored hex**, exactly as on every other device. Nothing about
  the data model changes; a note written on a Nomad opens in colour on a NoteAir5C.
- The **live firmware overlay** gets the nearest of the four codes, chosen by luminance. Since the
  panel is greyscale, its rendering of a colour is a grey anyway — the mapping only has to match
  what the panel would have shown.
- Build the mapping off the Phase 1 findings (what each code actually renders), not off theory.
- **A legitimate cheaper option:** the PoC simply left the live preview BLACK and let the baked
  stroke carry the real colour. The handoff already produces one visible transition; if the mapped
  greys turn out not to help, keeping BLACK is a defensible outcome for this phase rather than a
  failure. Decide on-device.

Also in this phase:
- **Horizontal registration (found in Phase 7's device round, A/B-verified pre-existing since
  Phase 3).** Baked strokes land slightly LEFT of the live firmware ink on every canvas — the
  firmware's raw pen stream and the digitizer's `MotionEvent` x disagree by a few pixels. The fix
  direction depends on which one is under the physical pen tip, and only an eye on the device can
  say:
  - **Live ink tracks the tip; bake jumps left of the tip** → the `MotionEvent` stream is offset —
    compensate by a measured constant x-shift on the Ratta *input* path (this corrects persisted
    data toward physical truth; strokes written on Supernote open correctly aligned everywhere).
  - **Live ink sits right of the tip; bake corrects to under the tip** → the firmware is the one
    that's off; baked data is already true, so the only options are accept the one-time jump or
    (if it grates) bias the live EMR rendering — nothing about persisted data may change.
  - Measurement tool: `SupernoteProbeActivity` — a dual-render lab that draws the app polyline at
    `MotionEvent` coords WITHOUT clearing the overlay leaves both lines on the panel at once; the
    offset becomes a directly visible double line (photograph it — screencap cannot see the
    overlay). Check both devices: Nomad and Manta differ in resolution (1404 vs 1920 wide), so
    determine whether the delta is constant in px or scales.
  which reports 3px paint ↔ EMR 300 looking good. What is left is *tuning*: our stroke widths are
  2.5f (Generic's paint) and 3.0f (Onyx's), and any mismatch shows as a one-time size or darkness
  shift **at the handoff moment**, when firmware ink is replaced by the baked polyline. Nudge the
  multiplier until that transition is invisible. The firmware's Needle `penSizeArray` runs
  ~200…2400, so there is headroom above the current 1200 clamp if a thicker pen is ever wanted.
- ~~Pen type: decide whether Ink (16) or Needle (10) better matches our round-cap polyline.~~
  **Settled by the PoC: NEEDLE (10)** — uniform width, matching our uniform-width baked polyline.
  Already wired in Phase 3.

### Build

- `RattaNotebookView.setPenColor(hex)` — keep the stored hex for baking (inherited behaviour), and
  additionally arm the firmware with the mapped code.
- A small mapping helper next to `SupernoteInk` — `fun firmwareColorFor(hex: String): Int` — with the
  measured luminance thresholds as documented constants.
- Width mapping constant(s), likewise documented with where the numbers came from.

### Device test

Install both. **Registration first, in `SupernoteProbeActivity` (both devices — the delta may
differ between the 1404-wide Nomad and 1920-wide Manta):**
1. Tap **REG** (pen re-arms at the engine's exact EMR 250; label shows `reg +0,+0`). Draw a few
   strokes — vertical lines show the x-offset best. Each stroke's app-drawn twin (the engine's
   exact 2.5 px bake) appears at pen-up next to the firmware ink, which is deliberately NOT
   cleared.
2. Nudge **X+** (the twin is expected LEFT of the firmware ink) until the twin **disappears under
   the firmware line** — overlay pixels freeze app updates, so vanishing = aligned. Check **Y±**
   too while you're there. Read the offset off the label and report it for each device.
3. While the double lines are up: same weight and darkness between twin and firmware ink? (This is
   the width-tuning check — EMR 250 vs the 2.5 px bake.)
4. Tap **COL** to step BLACK → DK-GRAY → GRAY → LT-GRAY, drawing a line with each — how light is
   each grey really? (Calibrates `RattaInkMap`'s provisional thresholds.)

Then in a notebook:
5. Write with black; compare live ink to baked ink at the moment of lift — same weight, same darkness?
6. Step through all 16 greys — does the live ink track the baked ink plausibly?
7. Step through several colours — does the live grey match what the baked stroke renders as?
8. Write fast and slow — does the width look consistent?

### Exit criteria

- [x] Live and baked ink are visually indistinguishable at pen-lift for black.
- [x] Every palette entry produces a sensible live grey with no jarring jump — as far as the
      hardware allows; see the grey-ladder note in the round-2 findings.
- [x] Stroke width matches.
- [x] Baked strokes land under the live ink (registration offset measured and compensated).
- [x] Identical on Nomad and Manta.

### Findings

**Fix direction settled before the round (2026-08-09, user observation):** while the stylus is
drawing, the live firmware ink sits correctly under the tip; the leftward shift appears only when
the bake replaces it. That is the first branch of the registration item — **the `MotionEvent`
stream is the one that's offset**, so the fix is a measured constant x-shift on the Ratta input
path, correcting persisted data toward physical truth. Only the constant's value (per device?) is
still unknown.

**Round-1 build (2026-08-09, installed on both, awaiting device test):**
- `notebook/ratta/RattaInkMap.kt` (new) — `firmwareColorFor(hex)`: Rec. 601 luma → nearest of the
  four firmware codes. Thresholds 48 / 128 / 184 are PROVISIONAL midpoints (the Phase 1 sweep never
  measured what the three grey codes render); the probe's COL cycler calibrates them this round.
- `RattaNotebookView.applyPenToFirmware()` now arms the mapped grey for the armed ink (was
  hardcoded BLACK); `setPenColor` re-arms the firmware when the plain pen is the active tool
  (colour-panel touch is a chrome boundary, so the overlay is already baked+clear — only the pen
  config needs refreshing).
- Registration mechanism wired but inert: `REG_OFFSET_X_PX = 0f` + `compensateRegistration(event)`
  (offsetLocation for stylus/eraser tools only, applied at onTouchEvent / onHoverEvent /
  onGenericMotionEvent entry — one chokepoint ahead of every consumer, so writing, erasing, lasso
  and taps stay mutually consistent). Round 2 fills in the measured constant(s).
- Probe REG lab: with REG on, the pen arms at the engine's exact EMR (250) and every stylus stroke
  is also drawn app-side at raw MotionEvent coords in the engine's exact bake style (2.5 px black,
  round caps) WITHOUT clearing the overlay — both renders sit on the panel at once. X±/Y± nudge the
  twin in 1 px steps; since overlay pixels freeze app updates, the twin vanishes under the firmware
  line exactly when the offset is nulled. COL cycles the four firmware colour codes through every
  probe pen site.

**Round-1 measurements (2026-08-09, probe labs, both devices):**
- **Registration: Nomad +2 px, Manta +3 px** (x only; y clean). The delta scales with the panel
  (1404- vs 1920-wide), so the constant is per-device — branched on min screen dimension
  (≥ 1600 → Manta-class), since the Manta reports `Build.MODEL` as `Supernote Nomad`.
- **Grey codes render far lighter than their names** (identical on both): DARK_GRAY ≈ a light
  #AAAAAA-ish tone (~luma 170), GRAY ≈ #CCCCCC (~204), LIGHT_GRAY near-invisible on the white
  panel (≈ #F0F0F0, ~240). The provisional thresholds were far too dark.

**Round-2 build (2026-08-09, installed on both):** `regOffsetXPx` now live —
+2/+3 px by panel size via `compensateRegistration` (REG_OFFSET_MANTA_PX / REG_OFFSET_NOMAD_PX /
REG_MANTA_MIN_DIM = 1600). `RattaInkMap` thresholds recalibrated to the measured render tones:
BLACK ≤ 85 · DARK_GRAY ≤ 187 · GRAY ≤ 222 · LIGHT_GRAY above (midpoints of measured anchors
0/170/204/240). Net effect: most of the palette's dark half stays BLACK live; only genuinely light
inks get a firmware grey; only near-white ink maps to the near-invisible LIGHT_GRAY (consistent —
near-white baked ink is equally invisible on paper).

**Round-2 device test (2026-08-09, both devices): PASS — phase complete.** Registration, black
live-vs-baked, colours, and fast/slow width all pass cleanly; the baked stroke now lands exactly
under the live ink. The 16-grey ladder is accepted as **best-possible, not perfect**: the firmware
offers only three visible shades plus a near-invisible one (its GRAY level reads near-white even in
Supernote's native notes app), so 16 baked tones cannot each get a distinct live grey — the
nearest-tone mapping is already optimal, and shifting thresholds would only misalign live from
baked to fake variety the panel cannot render. Not a deficiency to revisit. Screencap check on both
devices confirmed the other half of the contract: baked strokes carry their true hex (red / green /
yellow / purple readable in the framebuffer) on a panel that shows the user only grey — a notebook
written on a Supernote opens in full colour elsewhere.

**Registration scope caveat (discussed at phase close) — n = 1 per model.** The offset is a
disagreement between two software readings of the SAME digitizer (the firmware daemon paints true;
Android's MotionEvent pipeline lands left) — a physical per-unit misalignment would shift both
paths equally and produce no live-vs-baked divergence at all. That, plus the near-proportional
numbers (2/1404 ≈ 3/1920 ≈ 0.15% of panel width — smells like a coordinate-scaling/rounding
artifact in the firmware↔Android mapping), argues the constants are MODEL-level, not unit-level.
But with one unit of each model that is inference, not measurement: another unit could differ if
Supernote applies per-unit factory calibration on only one of the two paths. Worst case for a
mismatched unit is a 1–3 px bake shift — the artifact we shipped unnoticed for five phases — so
this is deferred, filed for Phase 9's BACKLOG sweep as a **user-facing stylus calibration screen**
(the probe's REG lab is the prototype: draw, nudge to null, store per-device; would replace the
hardcoded constants, and would also cover the Paper 7's whole-pipeline tip offset — a different
class: on the Generic engine live and baked agree with each other but can both miss the pen).
Note BOOX can never need the Supernote half of this: SDK overlay and bake share one input
pipeline, so live-vs-baked divergence is structurally impossible there.

---

## Phase 9 — Docs, device tiers, wrap-up · ⬜ NOT STARTED

No device test. Documentation and housekeeping.

- `docs/drawing-engine.md` — add Ratta to the engine table (line ~10 lists the engines, line ~84 the
  pen-activity hook table). Document the firmware overlay model, the disable-area mechanism, the
  mode-transition table from Phase 4, and the pen-lift sequencing decided in Phase 3.
- `CLAUDE.md` — add Ratta to the drawing-engine row of the docs table; note the sibling-copy
  arrangement so nobody fixes a lasso bug in only one of the two files.
- `README.md` (line ~36 "Future") and `.claude/skills/device-build-install/SKILL.md` — move Supernote
  Nomad & Manta out of **Future** into a tier, and add the Manta serial to the device table. **These
  two lists drift — change both.**
- `BACKLOG.md` — file the deferred work:
  - collapse `GenericNotebookView` + `RattaNotebookView` into a shared `CanvasNotebookView` base
    (the explicit Decision-1 follow-up)
  - **Onyx: draw the live lasso outline with the SDK's hardware `STROKE_STYLE_DASH` (= 5)** instead
    of the software `DashPathEffect` Canvas path. This is the BOOX half of Phase 5 — the user has
    explicitly said the current software look is not what they want. `PenToolSpikeActivity` already
    proved `DASH` renders on all five BOOX devices and that `setStrokeStyle` needs no
    `restartRawDrawing` and survives the handwriting fast-mode pin (`docs/onyx-pen-tools.md`), so the
    research is done — this is a build task, not a spike. Whatever Phase 5 learns about the
    lift → selection-box handoff applies directly.
  - firmware pen types beyond the default (Needle / Mark / Calligraphy, plus whatever the Phase 1
    sweep turns up) as a possible pen-tool offering, alongside the Onyx equivalents in
    `docs/onyx-pen-tools.md`
  - **user-facing stylus calibration screen** (from Phase 8's registration caveat): the Ratta
    offsets are believed model-level but were measured on one unit each; a calibration surface
    (REG-lab pattern — draw, nudge to null, store per-device) would replace the hardcoded
    constants and also cover whole-pipeline tip offsets on Generic-engine devices (the Paper 7
    is the known suspect)
  - anything Phases 0–8 punted
- Decide the fate of `SupernoteProbeActivity`: keep it (like `PenToolSpikeActivity`, as the
  calibration tool for EMR width and colour codes) with its findings written into the debug manifest
  comment. Recommended: **keep**.
- Delete this file once merged, per the project's convention of retiring completed plans to git
  history — **after** its content is in `docs/`.

---

## Risks / open questions

1. **Reverse-engineered, firmware-specific.** ~~Manta is *believed* to share firmware with the
   Nomad~~ — **Phase 0 measured this and it holds**: both devices run the byte-identical firmware
   build `Chauvet.E103.2605211001.2347_release` with identical 179-entry service tables. The
   one-target premise is now evidence, not inference. Phase 1's exit criteria still require the two
   to match behaviourally, since identical firmware does not guarantee identical *panel* behaviour.
   ⚠️ **New trap from the same measurement: the Manta reports `Build.MODEL` as `Supernote Nomad`.**
   The two are indistinguishable by name — branch on screen size or not at all.
2. ~~**Pen-lift sequencing** — clear too early and the stroke blinks, clear too late and it
   double-darkens~~ — **dissolved by the PoC.** The question only existed because the plan assumed a
   per-lift bake. Under the deferred handoff there is no per-lift sequencing at all: the firmware
   simply keeps its ink until a boundary. The residual risk moves to **boundary coverage** — miss a
   boundary and stale overlay ink outlives its page (Phase 4's table is the mitigation) — and to
   **baked-vs-firmware width match**, which shows as a one-time size or darkness shift at the
   handoff moment (Phase 8 tunes it; the PoC reports 3px paint ↔ EMR 300 looked good).
3. **Screen-space vs view-space coordinates.** The firmware paints in screen coordinates; every
   `MotionEvent` we bake is in view coordinates. Disable areas must be converted via
   `getLocationOnScreen`. A mismatch shows up as ink that stops at the wrong boundary, or a baked
   stroke that jumps on pen-lift.
4. **Translucent hosts** (scratch pad, sticky editor) — a screen-space overlay under a translucent
   window is untested territory. Phase 7.
5. ~~**`enableFullUiAuto` may be absent**~~ — **resolved in Phase 0.** `android.os.EinkManager`
   on this firmware exposes both `enableFullUiAuto(Z)V` and `enableFullUiAuto(ZZ)V`, dumped from
   `framework.jar`. Keep the guard anyway (it costs nothing and protects future firmware), but this
   is no longer an open risk. Per Decision 2, a failure logs + toasts rather than degrading.
6. ~~**`APP_NAME` may be whitelisted**~~ — **resolved in Phase 0.** The user's own
   `org.iccnet.supernotedemo` sends `"supernote-demo"` and works, so the firmware accepts arbitrary
   names. `"notesprout"` is fine.
7. **No point data from the firmware.** Everything we persist comes from `MotionEvent`. If the
   Supernote's `MotionEvent` stream is lower-resolution than the firmware's own sampling, baked
   strokes will look slightly coarser than the live ink did. Watch for it in Phase 3.

### Open — needs the user

- ~~**Manta ADB serial is unknown.**~~ **Resolved 2026-08-08: `SN100C10023972`.** Added to
  `.claude/skills/device-build-install/SKILL.md` alongside the Nomad's `SN078D10012852`. The skill's
  entry carries the "identifies as a Nomad" warning, since serial is the only reliable way to tell
  the two apart from the host side. (Phase 9 still covers the tier move.)
- ~~**Is the source for `org.iccnet.supernotedemo` available?**~~ **Yes — `~/git/SupernoteDemo`,
  read 2026-08-08.** It carries the user's own `NOTESPROUT_SUPERNOTE_INTEGRATION_PLAN.md`, whose
  substance is now folded into this file (see *Prior art*, and the rewritten Phases 1, 3 and 4).
  That repo stays the reference for `SupernoteInk.kt` and `DrawingView.kt`; **this file is the single
  source of truth for the branch** — do not work from two plans.
